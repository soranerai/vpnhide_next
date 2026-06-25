package dev.soranerai.vpnhidenext.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.soranerai.vpnhidenext.PortProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal interface AppDao {
    fun getAllAppProtection(): Flow<List<AppProtection>>

    suspend fun getAppProtection(
        packageName: String,
        userId: Int,
    ): AppProtection?

    suspend fun insertAppProtection(app: AppProtection)

    suspend fun insertAppProtections(apps: List<AppProtection>)

    suspend fun deleteAppProtection(app: AppProtection)

    suspend fun getAllAppProtectionSync(): List<AppProtection>
}

internal interface PortRuleDao {
    fun getRulesForApp(
        packageName: String,
        userId: Int,
    ): Flow<List<DbPortRule>>

    suspend fun getRulesForAppSync(
        packageName: String,
        userId: Int,
    ): List<DbPortRule>

    suspend fun insertRule(rule: DbPortRule)

    suspend fun insertRules(rules: List<DbPortRule>)

    suspend fun deleteRule(ruleId: Long)

    suspend fun deleteRulesForApp(
        packageName: String,
        userId: Int,
    )
}

internal interface MassPortRuleDao {
    fun getMassRules(): Flow<List<DbMassPortRule>>

    suspend fun getMassRulesSync(): List<DbMassPortRule>

    suspend fun insertMassRule(rule: DbMassPortRule)

    suspend fun insertMassRules(rules: List<DbMassPortRule>)

    suspend fun deleteMassRule(ruleId: Long)

    suspend fun deleteAllMassRules()
}

internal interface IfacePrefixDao {
    fun getAllPrefixes(): Flow<List<String>>

    suspend fun getAllPrefixesSync(): List<String>

    suspend fun insertPrefixes(prefixes: List<DbIfacePrefix>)

    suspend fun deleteAllPrefixes()
}

internal data class VpnHideConfig(
    val globalConfig: DbGlobalConfig = DbGlobalConfig(),
    val apps: Map<Pair<String, Int>, AppProtection> = emptyMap(),
    val portRules: List<DbPortRule> = emptyList(),
    val massPortRules: List<DbMassPortRule> = emptyList(),
    val ifacePrefixes: List<String> = emptyList(),
)

internal class AppDatabase private constructor(
    context: Context,
) {
    private val configFile = File(context.filesDir, "vpnhide_config.json")
    private val atomicFile = android.util.AtomicFile(configFile)
    private val lock = Any()

    @Volatile
    private var config = VpnHideConfig()

    private var inTransaction = false
    private val transactionLock = Any()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        synchronized(lock) {
            if (!configFile.exists()) {
                config = VpnHideConfig()
                return
            }
            try {
                val jsonStr = atomicFile.openRead().use { it.reader().readText() }
                val root = JSONObject(jsonStr)

                val globalObj = root.optJSONObject("globalConfig")
                val globalConfig = if (globalObj != null) globalObj.toDbGlobalConfig() else DbGlobalConfig()

                val appsMap = mutableMapOf<Pair<String, Int>, AppProtection>()
                val appsArray = root.optJSONArray("apps")
                if (appsArray != null) {
                    for (i in 0 until appsArray.length()) {
                        val app = appsArray.getJSONObject(i).toAppProtection()
                        appsMap[app.packageName to app.userId] = app
                    }
                }

                val portRulesList = mutableListOf<DbPortRule>()
                val portRulesArray = root.optJSONArray("portRules")
                if (portRulesArray != null) {
                    for (i in 0 until portRulesArray.length()) {
                        portRulesList.add(portRulesArray.getJSONObject(i).toDbPortRule())
                    }
                }

                val massRulesList = mutableListOf<DbMassPortRule>()
                val massRulesArray = root.optJSONArray("massPortRules")
                if (massRulesArray != null) {
                    for (i in 0 until massRulesArray.length()) {
                        massRulesList.add(massRulesArray.getJSONObject(i).toDbMassPortRule())
                    }
                }

                val prefixesList = mutableListOf<String>()
                val prefixesArray = root.optJSONArray("ifacePrefixes")
                if (prefixesArray != null) {
                    for (i in 0 until prefixesArray.length()) {
                        prefixesList.add(prefixesArray.getString(i))
                    }
                }

                config =
                    VpnHideConfig(
                        globalConfig = globalConfig,
                        apps = appsMap,
                        portRules = portRulesList,
                        massPortRules = massRulesList,
                        ifacePrefixes = prefixesList,
                    )
            } catch (e: Exception) {
                Log.e("VpnHideDb", "Failed to load JSON config, fallback to default", e)
                config = VpnHideConfig()
            }
        }
    }

    private fun saveConfigInternal() {
        synchronized(lock) {
            val root =
                JSONObject().apply {
                    put("globalConfig", config.globalConfig.toJson())
                    put("apps", JSONArray().apply { config.apps.values.forEach { put(it.toJson()) } })
                    put("portRules", JSONArray().apply { config.portRules.forEach { put(it.toJson()) } })
                    put("massPortRules", JSONArray().apply { config.massPortRules.forEach { put(it.toJson()) } })
                    put("ifacePrefixes", JSONArray().apply { config.ifacePrefixes.forEach { put(it) } })
                }
            val jsonStr = root.toString(2)
            var out: FileOutputStream? = null
            try {
                out = atomicFile.startWrite()
                out.write(jsonStr.toByteArray())
                atomicFile.finishWrite(out)
            } catch (e: Exception) {
                Log.e("VpnHideDb", "Failed to write JSON config", e)
                if (out != null) {
                    atomicFile.failWrite(out)
                }
            }
        }
    }

    private fun saveConfig() {
        synchronized(transactionLock) {
            if (inTransaction) return
        }
        saveConfigInternal()
    }

    suspend fun <R> withTransaction(block: suspend () -> R): R =
        withContext(Dispatchers.IO) {
            synchronized(transactionLock) {
                inTransaction = true
            }
            try {
                val result = block()
                saveConfigInternal()
                result
            } finally {
                synchronized(transactionLock) {
                    inTransaction = false
                }
            }
        }

    fun clearAllTables() {
        synchronized(lock) {
            config = VpnHideConfig()
            saveConfig()
        }
        DbNotifier.notifyChanged("app_protection")
        DbNotifier.notifyChanged("port_rules")
        DbNotifier.notifyChanged("mass_port_rules")
        DbNotifier.notifyChanged("iface_prefixes")
        DbNotifier.notifyChanged("global_config")
    }

    fun appDao(): AppDao = appDaoImpl

    fun portRuleDao(): PortRuleDao = portRuleDaoImpl

    fun massPortRuleDao(): MassPortRuleDao = massPortRuleDaoImpl

    fun ifacePrefixDao(): IfacePrefixDao = ifacePrefixDaoImpl

    fun globalConfigDao(): GlobalConfigDao = globalConfigDaoImpl

    private val appDaoImpl =
        object : AppDao {
            override fun getAllAppProtection(): Flow<List<AppProtection>> =
                flow {
                    emit(getAllAppProtectionSync())
                    DbNotifier.changeFlow.collect { table ->
                        if (table == "app_protection") {
                            emit(getAllAppProtectionSync())
                        }
                    }
                }

            override suspend fun getAppProtection(
                packageName: String,
                userId: Int,
            ): AppProtection? =
                synchronized(lock) {
                    config.apps[packageName to userId]
                }

            override suspend fun insertAppProtection(app: AppProtection) {
                synchronized(lock) {
                    val map = config.apps.toMutableMap()
                    map[app.packageName to app.userId] = app
                    config = config.copy(apps = map)
                    saveConfig()
                }
                DbNotifier.notifyChanged("app_protection")
            }

            override suspend fun insertAppProtections(apps: List<AppProtection>) {
                synchronized(lock) {
                    val map = config.apps.toMutableMap()
                    for (app in apps) {
                        map[app.packageName to app.userId] = app
                    }
                    config = config.copy(apps = map)
                    saveConfig()
                }
                DbNotifier.notifyChanged("app_protection")
            }

            override suspend fun deleteAppProtection(app: AppProtection) {
                synchronized(lock) {
                    val map = config.apps.toMutableMap()
                    map.remove(app.packageName to app.userId)
                    config = config.copy(apps = map)
                    saveConfig()
                }
                DbNotifier.notifyChanged("app_protection")
            }

            override suspend fun getAllAppProtectionSync(): List<AppProtection> =
                synchronized(lock) {
                    config.apps.values.toList()
                }
        }

    private val portRuleDaoImpl =
        object : PortRuleDao {
            override fun getRulesForApp(
                packageName: String,
                userId: Int,
            ): Flow<List<DbPortRule>> =
                flow {
                    emit(getRulesForAppSync(packageName, userId))
                    DbNotifier.changeFlow.collect { table ->
                        if (table == "port_rules") {
                            emit(getRulesForAppSync(packageName, userId))
                        }
                    }
                }

            override suspend fun getRulesForAppSync(
                packageName: String,
                userId: Int,
            ): List<DbPortRule> =
                synchronized(lock) {
                    config.portRules.filter { it.packageName == packageName && it.userId == userId }
                }

            override suspend fun insertRule(rule: DbPortRule) {
                synchronized(lock) {
                    val list = config.portRules.toMutableList()
                    val id =
                        if (rule.id == 0L) {
                            (list.maxOfOrNull { it.id } ?: 0L) + 1L
                        } else {
                            rule.id
                        }
                    val newRule = rule.copy(id = id)
                    list.removeAll { it.id == id }
                    list.add(newRule)
                    config = config.copy(portRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("port_rules")
            }

            override suspend fun insertRules(rules: List<DbPortRule>) {
                synchronized(lock) {
                    val list = config.portRules.toMutableList()
                    for (rule in rules) {
                        val id =
                            if (rule.id == 0L) {
                                (list.maxOfOrNull { it.id } ?: 0L) + 1L
                            } else {
                                rule.id
                            }
                        val newRule = rule.copy(id = id)
                        list.removeAll { it.id == id }
                        list.add(newRule)
                    }
                    config = config.copy(portRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("port_rules")
            }

            override suspend fun deleteRule(ruleId: Long) {
                synchronized(lock) {
                    val list = config.portRules.toMutableList()
                    list.removeAll { it.id == ruleId }
                    config = config.copy(portRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("port_rules")
            }

            override suspend fun deleteRulesForApp(
                packageName: String,
                userId: Int,
            ) {
                synchronized(lock) {
                    val list = config.portRules.toMutableList()
                    list.removeAll { it.packageName == packageName && it.userId == userId }
                    config = config.copy(portRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("port_rules")
            }
        }

    private val massPortRuleDaoImpl =
        object : MassPortRuleDao {
            override fun getMassRules(): Flow<List<DbMassPortRule>> =
                flow {
                    emit(getMassRulesSync())
                    DbNotifier.changeFlow.collect { table ->
                        if (table == "mass_port_rules") {
                            emit(getMassRulesSync())
                        }
                    }
                }

            override suspend fun getMassRulesSync(): List<DbMassPortRule> =
                synchronized(lock) {
                    config.massPortRules.toList()
                }

            override suspend fun insertMassRule(rule: DbMassPortRule) {
                synchronized(lock) {
                    val list = config.massPortRules.toMutableList()
                    val id =
                        if (rule.id == 0L) {
                            (list.maxOfOrNull { it.id } ?: 0L) + 1L
                        } else {
                            rule.id
                        }
                    val newRule = rule.copy(id = id)
                    list.removeAll { it.id == id }
                    list.add(newRule)
                    config = config.copy(massPortRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("mass_port_rules")
            }

            override suspend fun insertMassRules(rules: List<DbMassPortRule>) {
                synchronized(lock) {
                    val list = config.massPortRules.toMutableList()
                    for (rule in rules) {
                        val id =
                            if (rule.id == 0L) {
                                (list.maxOfOrNull { it.id } ?: 0L) + 1L
                            } else {
                                rule.id
                            }
                        val newRule = rule.copy(id = id)
                        list.removeAll { it.id == id }
                        list.add(newRule)
                    }
                    config = config.copy(massPortRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("mass_port_rules")
            }

            override suspend fun deleteMassRule(ruleId: Long) {
                synchronized(lock) {
                    val list = config.massPortRules.toMutableList()
                    list.removeAll { it.id == ruleId }
                    config = config.copy(massPortRules = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("mass_port_rules")
            }

            override suspend fun deleteAllMassRules() {
                synchronized(lock) {
                    config = config.copy(massPortRules = emptyList())
                    saveConfig()
                }
                DbNotifier.notifyChanged("mass_port_rules")
            }
        }

    private val ifacePrefixDaoImpl =
        object : IfacePrefixDao {
            override fun getAllPrefixes(): Flow<List<String>> =
                flow {
                    emit(getAllPrefixesSync())
                    DbNotifier.changeFlow.collect { table ->
                        if (table == "iface_prefixes") {
                            emit(getAllPrefixesSync())
                        }
                    }
                }

            override suspend fun getAllPrefixesSync(): List<String> =
                synchronized(lock) {
                    config.ifacePrefixes.toList()
                }

            override suspend fun insertPrefixes(prefixes: List<DbIfacePrefix>) {
                synchronized(lock) {
                    val list = config.ifacePrefixes.toMutableList()
                    for (prefix in prefixes) {
                        if (!list.contains(prefix.prefix)) {
                            list.add(prefix.prefix)
                        }
                    }
                    config = config.copy(ifacePrefixes = list)
                    saveConfig()
                }
                DbNotifier.notifyChanged("iface_prefixes")
            }

            override suspend fun deleteAllPrefixes() {
                synchronized(lock) {
                    config = config.copy(ifacePrefixes = emptyList())
                    saveConfig()
                }
                DbNotifier.notifyChanged("iface_prefixes")
            }
        }

    private val globalConfigDaoImpl =
        object : GlobalConfigDao {
            override suspend fun getConfig(): DbGlobalConfig =
                synchronized(lock) {
                    config.globalConfig
                }

            override suspend fun insertConfig(config: DbGlobalConfig) {
                synchronized(lock) {
                    this@AppDatabase.config = this@AppDatabase.config.copy(globalConfig = config)
                    saveConfig()
                }
                DbNotifier.notifyChanged("global_config")
            }
        }

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                if (instance == null) {
                    val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
                    instance = AppDatabase(deContext)
                }
                instance!!
            }

        fun performMigrationIfRequired(context: Context) {
            val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
            val dbFile = deContext.getDatabasePath("vpnhide_database")
            if (!dbFile.exists()) return

            Log.i("VpnHideDb", "Legacy database found, starting migration")

            var sqliteDb: SQLiteDatabase? = null
            try {
                sqliteDb =
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    )

                // Read apps
                val apps = mutableMapOf<Pair<String, Int>, AppProtection>()
                try {
                    sqliteDb.rawQuery("SELECT * FROM app_protection", null).use { cursor ->
                        val pkgIdx = cursor.getColumnIndex("packageName")
                        val userIdx = cursor.getColumnIndex("userId")
                        val uidIdx = cursor.getColumnIndex("uid")
                        val kmodIdx = cursor.getColumnIndex("kmod")
                        val lsposedIdx = cursor.getColumnIndex("lsposed")
                        val portHidingIdx = cursor.getColumnIndex("portHiding")

                        if (pkgIdx >= 0) {
                            while (cursor.moveToNext()) {
                                val app =
                                    AppProtection(
                                        packageName = cursor.getString(pkgIdx),
                                        userId = if (userIdx >= 0) cursor.getInt(userIdx) else 0,
                                        uid = if (uidIdx >= 0) cursor.getInt(uidIdx) else 0,
                                        kmod = if (kmodIdx >= 0) cursor.getInt(kmodIdx) == 1 else false,
                                        lsposed = if (lsposedIdx >= 0) cursor.getInt(lsposedIdx) == 1 else false,
                                        portHiding = if (portHidingIdx >= 0) cursor.getInt(portHidingIdx) == 1 else false,
                                    )
                                apps[app.packageName to app.userId] = app
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VpnHideDb", "Migration: failed to read app_protection table", e)
                }

                // Read port rules
                val portRules = mutableListOf<DbPortRule>()
                try {
                    sqliteDb.rawQuery("SELECT * FROM port_rules", null).use { cursor ->
                        val idIdx = cursor.getColumnIndex("id")
                        val pkgIdx = cursor.getColumnIndex("packageName")
                        val userIdx = cursor.getColumnIndex("userId")
                        val startIdx = cursor.getColumnIndex("startPort")
                        val endIdx = cursor.getColumnIndex("endPort")
                        val protoIdx = cursor.getColumnIndex("protocol")
                        val labelIdx = cursor.getColumnIndex("label")
                        val enabledIdx = cursor.getColumnIndex("enabled")

                        while (cursor.moveToNext()) {
                            val protoVal = if (protoIdx >= 0) cursor.getInt(protoIdx) else 0
                            val proto =
                                if (protoVal >= 0 && protoVal < PortProtocol.entries.size) {
                                    PortProtocol.entries[protoVal]
                                } else {
                                    PortProtocol.TCP
                                }
                            portRules.add(
                                DbPortRule(
                                    id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L,
                                    packageName = if (pkgIdx >= 0) cursor.getString(pkgIdx) else "",
                                    userId = if (userIdx >= 0) cursor.getInt(userIdx) else 0,
                                    startPort = if (startIdx >= 0) cursor.getInt(startIdx) else 0,
                                    endPort = if (endIdx >= 0) cursor.getInt(endIdx) else 0,
                                    protocol = proto,
                                    label = if (labelIdx >= 0) cursor.getString(labelIdx) else "",
                                    enabled = if (enabledIdx >= 0) cursor.getInt(enabledIdx) == 1 else true,
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VpnHideDb", "Migration: failed to read port_rules table", e)
                }

                // Read mass port rules
                val massPortRules = mutableListOf<DbMassPortRule>()
                try {
                    sqliteDb.rawQuery("SELECT * FROM mass_port_rules", null).use { cursor ->
                        val idIdx = cursor.getColumnIndex("id")
                        val startIdx = cursor.getColumnIndex("startPort")
                        val endIdx = cursor.getColumnIndex("endPort")
                        val protoIdx = cursor.getColumnIndex("protocol")
                        val labelIdx = cursor.getColumnIndex("label")
                        val enabledIdx = cursor.getColumnIndex("enabled")

                        while (cursor.moveToNext()) {
                            val protoVal = if (protoIdx >= 0) cursor.getInt(protoIdx) else 0
                            val proto =
                                if (protoVal >= 0 && protoVal < PortProtocol.entries.size) {
                                    PortProtocol.entries[protoVal]
                                } else {
                                    PortProtocol.TCP
                                }
                            massPortRules.add(
                                DbMassPortRule(
                                    id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L,
                                    startPort = if (startIdx >= 0) cursor.getInt(startIdx) else 0,
                                    endPort = if (endIdx >= 0) cursor.getInt(endIdx) else 0,
                                    protocol = proto,
                                    label = if (labelIdx >= 0) cursor.getString(labelIdx) else "",
                                    enabled = if (enabledIdx >= 0) cursor.getInt(enabledIdx) == 1 else true,
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VpnHideDb", "Migration: failed to read mass_port_rules table", e)
                }

                // Read prefixes
                val prefixes = mutableListOf<String>()
                try {
                    sqliteDb.rawQuery("SELECT prefix FROM iface_prefixes", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            prefixes.add(cursor.getString(0))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VpnHideDb", "Migration: failed to read iface_prefixes table", e)
                }

                // Read global config
                var globalConfig = DbGlobalConfig()
                try {
                    sqliteDb.rawQuery("SELECT * FROM global_config WHERE id = 'default'", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idIdx = cursor.getColumnIndex("id")
                            val kMaskIdx = cursor.getColumnIndex("kernelHookMask")
                            val jMaskIdx = cursor.getColumnIndex("javaHookMask")
                            val debugLoggingIdx = cursor.getColumnIndex("debugLogging")

                            globalConfig =
                                DbGlobalConfig(
                                    id = if (idIdx >= 0) cursor.getString(idIdx) else "default",
                                    kernelHookMask = if (kMaskIdx >= 0) cursor.getLong(kMaskIdx) else 0xFFFFFFFFL,
                                    javaHookMask = if (jMaskIdx >= 0) cursor.getLong(jMaskIdx) else 0xFFFFFFFFL,
                                    debugLogging = if (debugLoggingIdx >= 0) cursor.getInt(debugLoggingIdx) else 0,
                                )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VpnHideDb", "Migration: failed to read global_config table", e)
                }

                // Write migrated config
                val migratedConfig =
                    VpnHideConfig(
                        globalConfig = globalConfig,
                        apps = apps,
                        portRules = portRules,
                        massPortRules = massPortRules,
                        ifacePrefixes = prefixes,
                    )

                // Instantiate AppDatabase on deContext to write it
                val db = getInstance(deContext)
                synchronized(db.lock) {
                    db.config = migratedConfig
                    db.saveConfigInternal()
                }

                Log.i("VpnHideDb", "Migration complete, deleting legacy database files")
            } catch (e: Exception) {
                Log.e("VpnHideDb", "Critical error migrating database", e)
            } finally {
                sqliteDb?.close()
            }

            // Close instance to ensure any connection to database is released
            // Now delete files
            try {
                deContext.deleteDatabase("vpnhide_database")
            } catch (e: Exception) {
                Log.e("VpnHideDb", "Failed to delete legacy database file", e)
            }
        }
    }
}

// ── JSON Conversion Helpers ──────────────────────────────────────────

private fun AppProtection.toJson(): JSONObject =
    JSONObject().apply {
        put("packageName", packageName)
        put("userId", userId)
        put("uid", uid)
        put("kmod", kmod)
        put("lsposed", lsposed)
        put("portHiding", portHiding)
    }

private fun JSONObject.toAppProtection(): AppProtection =
    AppProtection(
        packageName = getString("packageName"),
        userId = optInt("userId", 0),
        uid = optInt("uid", 0),
        kmod = optBoolean("kmod", false),
        lsposed = optBoolean("lsposed", false),
        portHiding = optBoolean("portHiding", false),
    )

private fun DbPortRule.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("packageName", packageName)
        put("userId", userId)
        put("startPort", startPort)
        put("endPort", endPort)
        put("protocol", protocol.name)
        put("label", label)
        put("enabled", enabled)
    }

private fun JSONObject.toDbPortRule(): DbPortRule =
    DbPortRule(
        id = getLong("id"),
        packageName = getString("packageName"),
        userId = optInt("userId", 0),
        startPort = getInt("startPort"),
        endPort = getInt("endPort"),
        protocol = PortProtocol.valueOf(optString("protocol", PortProtocol.TCP.name)),
        label = optString("label", ""),
        enabled = optBoolean("enabled", true),
    )

private fun DbMassPortRule.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("startPort", startPort)
        put("endPort", endPort)
        put("protocol", protocol.name)
        put("label", label)
        put("enabled", enabled)
    }

private fun JSONObject.toDbMassPortRule(): DbMassPortRule =
    DbMassPortRule(
        id = getLong("id"),
        startPort = getInt("startPort"),
        endPort = getInt("endPort"),
        protocol = PortProtocol.valueOf(optString("protocol", PortProtocol.TCP.name)),
        label = optString("label", ""),
        enabled = optBoolean("enabled", true),
    )

private fun DbGlobalConfig.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("kernelHookMask", kernelHookMask)
        put("javaHookMask", javaHookMask)
        put("debugLogging", debugLogging)
    }

private fun JSONObject.toDbGlobalConfig(): DbGlobalConfig =
    DbGlobalConfig(
        id = optString("id", "default"),
        kernelHookMask = optLong("kernelHookMask", 0xFFFFFFFFL),
        javaHookMask = optLong("javaHookMask", 0xFFFFFFFFL),
        debugLogging = optInt("debugLogging", 0),
    )
