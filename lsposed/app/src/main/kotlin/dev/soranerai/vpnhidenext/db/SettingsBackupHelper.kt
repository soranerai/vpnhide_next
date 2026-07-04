package dev.soranerai.vpnhidenext.db

import android.content.Context
import dev.soranerai.vpnhidenext.PortProtocol
import dev.soranerai.vpnhidenext.TargetsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SettingsBackupHelper {
    suspend fun exportToString(context: Context): String =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val appDao = db.appDao()
            val portRuleDao = db.portRuleDao()
            val massPortRuleDao = db.massPortRuleDao()
            val ifacePrefixDao = db.ifacePrefixDao()

            val json = JSONObject()
            json.put("version", 1)

            val metadata = JSONObject()
            metadata.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            metadata.put(
                "appVersion",
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrDefault("unknown"),
            )
            json.put("metadata", metadata)

            // 1. Export app protections + nested port rules
            val appsArray = JSONArray()
            val selfPkg = context.packageName
            val apps = appDao.getAllAppProtectionSync().filter { it.packageName != selfPkg }
            for (app in apps) {
                val appObj = JSONObject()
                appObj.put("packageName", app.packageName)
                appObj.put("userId", app.userId)
                appObj.put("kmod", app.kmod)
                appObj.put("lsposed", app.lsposed)
                appObj.put("portHiding", app.portHiding)

                // Nested granular port rules
                val rules = portRuleDao.getRulesForAppSync(app.packageName, app.userId)
                if (rules.isNotEmpty()) {
                    val rulesArray = JSONArray()
                    for (rule in rules) {
                        val ruleObj = JSONObject()
                        ruleObj.put("startPort", rule.startPort)
                        ruleObj.put("endPort", rule.endPort)
                        ruleObj.put("protocol", rule.protocol.name)
                        ruleObj.put("label", rule.label)
                        ruleObj.put("enabled", rule.enabled)
                        rulesArray.put(ruleObj)
                    }
                    appObj.put("portRules", rulesArray)
                }
                appsArray.put(appObj)
            }
            json.put("app_protection", appsArray)

            // 2. Export mass port rules
            val massRulesArray = JSONArray()
            val massRules = massPortRuleDao.getMassRulesSync()
            for (rule in massRules) {
                val ruleObj = JSONObject()
                ruleObj.put("startPort", rule.startPort)
                ruleObj.put("endPort", rule.endPort)
                ruleObj.put("protocol", rule.protocol.name)
                ruleObj.put("label", rule.label)
                ruleObj.put("enabled", rule.enabled)
                massRulesArray.put(ruleObj)
            }
            json.put("mass_port_rules", massRulesArray)

            // 3. Export interface prefixes
            val prefixesArray = JSONArray()
            val prefixes = ifacePrefixDao.getAllPrefixesSync()
            for (prefix in prefixes) {
                prefixesArray.put(prefix)
            }
            json.put("iface_prefixes", prefixesArray)

            // 4. Export global settings
            val config = db.globalConfigDao().getConfig() ?: DbGlobalConfig()
            val globalObj = JSONObject()
            globalObj.put("kernelHookMask", config.kernelHookMask)
            globalObj.put("javaHookMask", config.javaHookMask)
            json.put("global_config", globalObj)

            json.toString(4) // Format with 4 spaces indent
        }

    suspend fun importFromString(
        context: Context,
        jsonStr: String,
    ): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val json = JSONObject(jsonStr)
                val db = AppDatabase.getInstance(context)
                val appDao = db.appDao()
                val portRuleDao = db.portRuleDao()
                val massPortRuleDao = db.massPortRuleDao()
                val ifacePrefixDao = db.ifacePrefixDao()

                db.withTransaction {
                    // Clear all tables atomically
                    db.clearAllTables()

                    // Import global config if present
                    val globalObj = json.optJSONObject("global_config")
                    if (globalObj != null) {
                        val kernelMask = globalObj.optLong("kernelHookMask", 0xFFFFFFFFL)
                        val javaMask = globalObj.optLong("javaHookMask", 0xFFFFFFFFL)
                        db.globalConfigDao().insertConfig(
                            DbGlobalConfig(
                                kernelHookMask = kernelMask,
                                javaHookMask = javaMask,
                            ),
                        )
                    } else {
                        db.globalConfigDao().insertConfig(DbGlobalConfig())
                    }

                    // 1. Import app protections & nested port rules
                    val appsArray = json.optJSONArray("app_protection")
                    if (appsArray != null) {
                        val selfPkg = context.packageName
                        for (i in 0 until appsArray.length()) {
                            val appObj = appsArray.getJSONObject(i)
                            val pkg = appObj.getString("packageName")
                            if (pkg == selfPkg) continue
                            val userId = appObj.optInt("userId", 0)
                            val kmod = appObj.optBoolean("kmod", false)
                            val lsposed = appObj.optBoolean("lsposed", false)
                            val portHiding = appObj.optBoolean("portHiding", false)

                            // Set uid = 0 so dynamic healing in TargetsCache.reload resolves system package UIDs properly
                            appDao.insertAppProtection(
                                AppProtection(
                                    packageName = pkg,
                                    userId = userId,
                                    uid = 0,
                                    kmod = kmod,
                                    lsposed = lsposed,
                                    portHiding = portHiding,
                                ),
                            )

                            val rulesArray = appObj.optJSONArray("portRules")
                            if (rulesArray != null) {
                                val dbRules = mutableListOf<DbPortRule>()
                                for (j in 0 until rulesArray.length()) {
                                    val ruleObj = rulesArray.getJSONObject(j)
                                    val start = ruleObj.getInt("startPort")
                                    val end = ruleObj.getInt("endPort")
                                    val proto = PortProtocol.valueOf(ruleObj.getString("protocol"))
                                    val label = ruleObj.optString("label", "")
                                    val enabled = ruleObj.optBoolean("enabled", true)

                                    dbRules.add(
                                        DbPortRule(
                                            packageName = pkg,
                                            userId = userId,
                                            startPort = start,
                                            endPort = end,
                                            protocol = proto,
                                            label = label,
                                            enabled = enabled,
                                        ),
                                    )
                                }
                                portRuleDao.insertRules(dbRules)
                            }
                        }
                    }

                    // 2. Import mass port rules
                    val massArray = json.optJSONArray("mass_port_rules")
                    if (massArray != null) {
                        val dbMassRules = mutableListOf<DbMassPortRule>()
                        for (i in 0 until massArray.length()) {
                            val ruleObj = massArray.getJSONObject(i)
                            val start = ruleObj.getInt("startPort")
                            val end = ruleObj.getInt("endPort")
                            val proto = PortProtocol.valueOf(ruleObj.getString("protocol"))
                            val label = ruleObj.optString("label", "")
                            val enabled = ruleObj.optBoolean("enabled", true)

                            dbMassRules.add(
                                DbMassPortRule(
                                    startPort = start,
                                    endPort = end,
                                    protocol = proto,
                                    label = label,
                                    enabled = enabled,
                                ),
                            )
                        }
                        massPortRuleDao.insertMassRules(dbMassRules)
                    }

                    // 3. Import interface prefixes
                    val prefixesArray = json.optJSONArray("iface_prefixes")
                    if (prefixesArray != null) {
                        val dbPrefixes = mutableListOf<DbIfacePrefix>()
                        for (i in 0 until prefixesArray.length()) {
                            val prefix = prefixesArray.getString(i)
                            dbPrefixes.add(DbIfacePrefix(prefix))
                        }
                        ifacePrefixDao.insertPrefixes(dbPrefixes)
                    }
                }

                // Database healing: Dynamic UID resolution & SQLite config database sync to /data/system/vpnhide
                TargetsCache.reload(context)

                // Dynamic system settings synchronization to system files observers, kmod & system_server
                DatabaseSync.sync(context)
            }
        }
}
