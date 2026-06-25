package dev.soranerai.vpnhidenext

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

internal object AppIconCache {
    private val lru = LruCache<String, Drawable>(120)
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Retrieve icon from the LRU cache or load it using PackageManager.
     * Should be called on Dispatchers.IO.
     */
    fun getOrLoad(
        pm: PackageManager,
        packageName: String,
        apkPath: String?,
    ): Drawable? {
        val cached =
            synchronized(lru) {
                lru.get(packageName)
            }
        if (cached != null) return cached

        val lock = locks.computeIfAbsent(packageName) { Any() }
        val icon =
            synchronized(lock) {
                val doubleCheck =
                    synchronized(lru) {
                        lru.get(packageName)
                    }
                if (doubleCheck != null) return@synchronized doubleCheck

                val loaded =
                    runCatching {
                        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                        val archiveInfo =
                            if (info == null && !apkPath.isNullOrBlank()) {
                                loadArchiveApplicationInfo(pm, apkPath)
                            } else {
                                null
                            }
                        val effectiveInfo = info ?: archiveInfo
                        effectiveInfo?.let { pm.getApplicationIcon(it) }
                    }.getOrNull()

                if (loaded != null) {
                    synchronized(lru) {
                        lru.put(packageName, loaded)
                    }
                }
                loaded
            }
        locks.remove(packageName, lock)
        return icon
    }

    @Suppress("DEPRECATION")
    private fun loadArchiveApplicationInfo(
        pm: PackageManager,
        apkPath: String,
    ): ApplicationInfo? {
        val pkgInfo = runCatching { pm.getPackageArchiveInfo(apkPath, 0) }.getOrNull() ?: return null
        val appinfo = pkgInfo.applicationInfo ?: return null
        appinfo.sourceDir = apkPath
        appinfo.publicSourceDir = apkPath
        return appinfo
    }

    /** Forcefully remove an entry from cache (e.g. if the package is uninstalled). */
    fun evict(packageName: String) {
        synchronized(lru) {
            lru.remove(packageName)
        }
    }

    /** Reset the entire cache. */
    fun evictAll() {
        synchronized(lru) {
            lru.evictAll()
        }
    }
}
