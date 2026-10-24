package com.example.ioslauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
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
    val category: Int,
) {
    val key: String get() = component.flattenToShortString() + "@" + user.hashCode()
    val packageName: String get() = component.packageName
    val categoryName: String get() = Categories.nameOf(category)

    @Volatile var icon: Bitmap? = null
}

class AppRepository(private val context: Context, private val prefs: Prefs) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private var generation = 0
    private var onChanged: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(null)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(null)
    }

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

    /** [onList] gets the apps (A to Z, no icons yet) on the main thread; [onProgress] fires as icons get drawn. */
    fun load(iconPx: Int, onList: (List<AppEntry>) -> Unit, onProgress: () -> Unit) {
        val gen = ++generation
        worker.execute {
            val infos = launcherApps.getActivityList(null, Process.myUserHandle())
                .filter { it.componentName.packageName != context.packageName }
            val entries = infos.map {
                AppEntry(it.label.toString(), it.componentName, it.user, it.applicationInfo.category)
            }.sortedBy { it.label.lowercase() }
            for (e in entries) cache[e.key]?.let { e.icon = it }
            main.post { if (gen == generation) onList(entries) }

            val byKey = infos.associateBy { it.componentName.flattenToShortString() + "@" + it.user.hashCode() }
            var n = 0
            for (e in entries) {
                if (gen != generation) return@execute
                if (e.icon != null) continue
                try {
                    val info = byKey[e.key] ?: continue
                    val bmp = SquircleIcon.render(info.getBadgedIcon(0), iconPx)
                    cache[e.key] = bmp
                    e.icon = bmp
                } catch (_: Exception) {
                    // keeps a grey placeholder
                }
                if (++n % 8 == 0) main.post { if (gen == generation) onProgress() }
            }
            main.post { if (gen == generation) onProgress() }
        }
    }

    /** Phone, messages, browser and camera apps, for the first dock. */
    fun defaultDockPackages(): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            Intent("android.media.action.STILL_IMAGE_CAMERA"),
        )
        return intents.mapNotNull {
            try {
                pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (_: Exception) {
                null
            }
        }
    }

    fun launch(app: AppEntry, bounds: Rect, options: Bundle?) {
        launcherApps.startMainActivity(app.component, app.user, bounds, options)
        prefs.countLaunch(app.key)
    }

    /** The app's own shortcuts, like iOS quick actions. Android only hands these to the default home app. */
    fun shortcuts(app: AppEntry): List<android.content.pm.ShortcutInfo> = try {
        val q = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        (launcherApps.getShortcuts(q, app.user) ?: emptyList())
            .filter { it.isEnabled && (it.activity == null || it.activity == app.component) }
            .take(4)
    } catch (_: Exception) {
        emptyList()
    }

    fun shortcutIcon(s: android.content.pm.ShortcutInfo): android.graphics.drawable.Drawable? = try {
        launcherApps.getShortcutIconDrawable(s, context.resources.displayMetrics.densityDpi)
    } catch (_: Exception) {
        null
    }

    fun startShortcut(s: android.content.pm.ShortcutInfo) {
        launcherApps.startShortcut(s, null, null)
    }

    fun showAppInfo(app: AppEntry, bounds: Rect) {
        launcherApps.startAppDetailsActivity(app.component, app.user, bounds, null)
    }
}
