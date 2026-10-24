package com.example.watchlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
) {
    val key: String get() = component.flattenToShortString() + "@" + user.hashCode()
    val packageName: String get() = component.packageName

    @Volatile var full: Bitmap? = null
    @Volatile var small: Bitmap? = null
}

class AppRepository(private val context: Context, private val prefs: Prefs) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Pair<Bitmap, Bitmap>>()
    private var generation = 0

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(null)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(null)
    }

    private var onChanged: (() -> Unit)? = null

    private fun changed(packageName: String?) {
        if (packageName == null) cache.clear() else cache.keys.removeAll { it.startsWith("$packageName/") }
        main.post { onChanged?.invoke() }
    }

    fun register(listener: () -> Unit) {
        onChanged = listener
        launcherApps.registerCallback(callback, main)
    }

    fun unregister() {
        launcherApps.unregisterCallback(callback)
        onChanged = null
    }

    /**
     * Loads the app list in the background. [onList] runs on the main thread as soon as the (sorted) list is known,
     * with icons still empty; [onProgress] runs every few icons while they are drawn, centre first.
     */
    fun load(iconPx: Int, onList: (List<AppEntry>) -> Unit, onProgress: () -> Unit) {
        val gen = ++generation
        worker.execute {
            val entries = ArrayList<AppEntry>()
            for (info in launcherApps.getActivityList(null, Process.myUserHandle())) {
                if (info.componentName.packageName == context.packageName) continue
                entries.add(AppEntry(info.label.toString(), info.componentName, info.user))
            }
            val sorted = if (prefs.order == 1) {
                entries.sortedBy { it.label.lowercase() }
            } else {
                entries.sortedWith(compareByDescending<AppEntry> { prefs.launches(it.key) }.thenBy { it.label.lowercase() })
            }
            for (e in sorted) cache[e.key]?.let { e.full = it.first; e.small = it.second }
            main.post { if (gen == generation) onList(sorted) }

            var n = 0
            for (e in sorted) {
                if (gen != generation) return@execute
                if (e.full != null) continue
                try {
                    val info = launcherApps.getActivityList(e.packageName, e.user).firstOrNull { it.componentName == e.component } ?: continue
                    val full = IconRenderer.render(info.getBadgedIcon(0), iconPx)
                    val small = IconRenderer.half(full)
                    cache[e.key] = full to small
                    e.small = small
                    e.full = full
                } catch (_: Exception) {
                    // An app that vanished mid-load just keeps a grey disc.
                }
                if (++n % 6 == 0) main.post { if (gen == generation) onProgress() }
            }
            main.post { if (gen == generation) onProgress() }
        }
    }

    fun launch(app: AppEntry, bounds: android.graphics.Rect, options: android.os.Bundle?) {
        launcherApps.startMainActivity(app.component, app.user, bounds, options)
        prefs.countLaunch(app.key)
    }

    fun showAppInfo(app: AppEntry, bounds: android.graphics.Rect) {
        launcherApps.startAppDetailsActivity(app.component, app.user, bounds, null)
    }
}
