package dev.soranerai.vpnhidenext

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class CachedApp(
    val packageName: String,
    val userId: Int,
    val uid: Int,
    val label: String,
    val isSystem: Boolean,
    val apkPath: String?,
)

internal object AppListDiskCache {
    private const val FILE = "app_list_cache.json"
    private const val VERSION = 2

    /** Save the cached app list to filesDir. Should be called on Dispatchers.IO. */
    fun save(
        context: Context,
        apps: List<CachedApp>,
    ) {
        val arr = JSONArray()
        for (a in apps) {
            arr.put(
                JSONObject().apply {
                    put("p", a.packageName)
                    put("u", a.userId)
                    put("uid", a.uid)
                    put("l", a.label)
                    put("s", a.isSystem)
                    put("a", a.apkPath)
                },
            )
        }
        val root =
            JSONObject().apply {
                put("version", VERSION)
                put("apps", arr)
            }
        runCatching {
            context.filesDir.resolve(FILE).writeText(root.toString())
        }
    }

    /**
     * Load the cached app list from filesDir. Returns null if the file does not exist
     * or is incompatible (different cache version).
     * Should be called on Dispatchers.IO.
     */
    fun load(context: Context): List<CachedApp>? {
        val file = context.filesDir.resolve(FILE)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version") != VERSION) return null
            val arr = root.getJSONArray("apps")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CachedApp(
                    packageName = o.getString("p"),
                    userId = o.getInt("u"),
                    uid = o.getInt("uid"),
                    label = o.getString("l"),
                    isSystem = o.getBoolean("s"),
                    apkPath = if (o.isNull("a")) null else o.optString("a").ifEmpty { null },
                )
            }
        }.getOrNull()
    }

    /** Clear the cache file. */
    fun clear(context: Context) {
        runCatching {
            context.filesDir.resolve(FILE).delete()
        }
    }
}
