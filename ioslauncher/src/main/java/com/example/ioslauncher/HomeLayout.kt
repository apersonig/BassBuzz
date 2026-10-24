package com.example.ioslauncher

/**
 * Where every app lives on the home screen: pages of icons (kept packed, no holes, like iOS), a dock of up to
 * four, and apps that were removed from the home screen and so only appear in the App Library.
 * All operations return a new layout.
 */
data class HomeLayout(
    val pages: List<List<String>>,
    val dock: List<String>,
    val hidden: Set<String>,
) {
    companion object {
        const val DOCK_SIZE = 4
        val EMPTY = HomeLayout(listOf(emptyList()), emptyList(), emptySet())

        /**
         * Brings a saved layout in line with the apps that are installed now: uninstalled apps disappear,
         * new apps are added at the end of the last page. With nothing saved, the default dock is used and
         * everything else is filled in page by page.
         */
        fun reconcile(saved: HomeLayout?, installed: List<String>, defaultDock: List<String>, perPage: Int): HomeLayout {
            val present = installed.toSet()
            val fresh = saved == null || (saved.pages.all { it.isEmpty() } && saved.dock.isEmpty() && saved.hidden.isEmpty())
            if (fresh) {
                val dock = defaultDock.filter { it in present }.distinct().take(DOCK_SIZE)
                val rest = installed.filter { it !in dock }
                return HomeLayout(rest.chunked(perPage), dock, emptySet()).normalize(perPage)
            }
            saved!!
            val dock = saved.dock.filter { it in present }.distinct().take(DOCK_SIZE)
            val pages = saved.pages.map { page -> page.filter { it in present } }
            val hidden = saved.hidden.filter { it in present }.toSet()
            val known = (dock + pages.flatten() + hidden).toSet()
            val added = installed.filter { it !in known }
            val withNew = if (added.isEmpty()) pages else pages.dropLast(1) + listOf(pages.last() + added)
            return HomeLayout(withNew.ifEmpty { listOf(added) }, dock, hidden).normalize(perPage)
        }

        fun parse(text: String?): HomeLayout? {
            if (text.isNullOrBlank()) return null
            var dock: List<String>? = null
            var hidden = emptySet<String>()
            val pages = ArrayList<List<String>>()
            for (line in text.split('\n')) {
                fun items(prefix: String) = line.removePrefix(prefix).split(';').filter { it.isNotEmpty() }
                when {
                    line.startsWith("D:") -> dock = items("D:")
                    line.startsWith("H:") -> hidden = items("H:").toSet()
                    line.startsWith("P:") -> pages.add(items("P:"))
                }
            }
            if (dock == null) return null
            return HomeLayout(pages.ifEmpty { listOf(emptyList()) }, dock, hidden)
        }
    }

    fun serialize(): String = buildString {
        append("D:").append(dock.joinToString(";")).append('\n')
        append("H:").append(hidden.joinToString(";"))
        for (p in pages) append("\nP:").append(p.joinToString(";"))
    }

    fun contains(key: String): Boolean = key in dock || key in hidden || pages.any { key in it }

    /**
     * Keeps every page within [perPage]: extra icons spill to the start of the next page, and (unless
     * [dropEmpty] is false, which a drag in progress wants) empty pages go away. There is always one page.
     */
    fun normalize(perPage: Int, dropEmpty: Boolean = true): HomeLayout {
        val out = ArrayList<List<String>>()
        var carry = emptyList<String>()
        for (p in pages) {
            val all = carry + p
            out.add(all.take(perPage))
            carry = all.drop(perPage)
        }
        while (carry.isNotEmpty()) {
            out.add(carry.take(perPage))
            carry = carry.drop(perPage)
        }
        val kept = if (dropEmpty) out.filter { it.isNotEmpty() } else out
        return copy(pages = kept.ifEmpty { listOf(emptyList()) })
    }

    /** Puts [key] on [page] at [index] (a page past the end creates a new page). */
    fun move(key: String, page: Int, index: Int, perPage: Int, dropEmpty: Boolean = true): HomeLayout {
        val pg = pages.map { it - key }.toMutableList()
        while (pg.size <= page) pg.add(emptyList())
        val target = pg[page].toMutableList()
        target.add(index.coerceIn(0, target.size), key)
        pg[page] = target
        return HomeLayout(pg, dock - key, hidden - key).normalize(perPage, dropEmpty)
    }

    /** Puts [key] in the dock at [index]. A full dock refuses anything that isn't already in it. */
    fun moveToDock(key: String, index: Int, perPage: Int, dropEmpty: Boolean = true): HomeLayout {
        if (key !in dock && dock.size >= DOCK_SIZE) return this
        val d = (dock - key).toMutableList()
        d.add(index.coerceIn(0, d.size), key)
        return HomeLayout(pages.map { it - key }, d, hidden - key).normalize(perPage, dropEmpty)
    }

    fun removeFromHome(key: String, perPage: Int): HomeLayout =
        HomeLayout(pages.map { it - key }, dock - key, hidden + key).normalize(perPage)

    fun addToHome(key: String, perPage: Int): HomeLayout {
        if (key !in hidden) return this
        return HomeLayout(pages.dropLast(1) + listOf(pages.last() + key), dock, hidden - key).normalize(perPage)
    }
}
