package dev.soranerai.vpnhidenext.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.soranerai.vpnhidenext.PortProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

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

internal class AppDatabase private constructor(
    context: Context,
) {
    private val helper = DbHelper(context)

    val writableDatabase: SQLiteDatabase
        get() = helper.writableDatabase

    val readableDatabase: SQLiteDatabase
        get() = helper.readableDatabase

    suspend fun <R> withTransaction(block: suspend () -> R): R =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val result = block()
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        }

    fun clearAllTables() {
        val db = writableDatabase
        db.delete("app_protection", null, null)
        db.delete("port_rules", null, null)
        db.delete("mass_port_rules", null, null)
        db.delete("iface_prefixes", null, null)
        db.delete("global_config", null, null)
        db.execSQL(
            "INSERT OR IGNORE INTO global_config (id, kernelHookMask, javaHookMask, debugLogging) " +
                "VALUES ('default', 4294967295, 4294967295, 0)",
        )
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
                withContext(Dispatchers.IO) {
                    readableDatabase
                        .rawQuery(
                            "SELECT * FROM app_protection WHERE packageName = ? AND userId = ?",
                            arrayOf(packageName, userId.toString()),
                        ).use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.toAppProtection()
                            } else {
                                null
                            }
                        }
                }

            override suspend fun insertAppProtection(app: AppProtection) {
                withContext(Dispatchers.IO) {
                    val values = app.toContentValues()
                    writableDatabase.insertWithOnConflict(
                        "app_protection",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    DbNotifier.notifyChanged("app_protection")
                }
            }

            override suspend fun insertAppProtections(apps: List<AppProtection>) {
                withContext(Dispatchers.IO) {
                    val db = writableDatabase
                    db.beginTransaction()
                    try {
                        for (app in apps) {
                            db.insertWithOnConflict(
                                "app_protection",
                                null,
                                app.toContentValues(),
                                SQLiteDatabase.CONFLICT_REPLACE,
                            )
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    DbNotifier.notifyChanged("app_protection")
                }
            }

            override suspend fun deleteAppProtection(app: AppProtection) {
                withContext(Dispatchers.IO) {
                    writableDatabase.delete(
                        "app_protection",
                        "packageName = ? AND userId = ?",
                        arrayOf(app.packageName, app.userId.toString()),
                    )
                    DbNotifier.notifyChanged("app_protection")
                }
            }

            override suspend fun getAllAppProtectionSync(): List<AppProtection> =
                withContext(Dispatchers.IO) {
                    val list = mutableListOf<AppProtection>()
                    readableDatabase.rawQuery("SELECT * FROM app_protection", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            list.add(cursor.toAppProtection())
                        }
                    }
                    list
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
                withContext(Dispatchers.IO) {
                    val list = mutableListOf<DbPortRule>()
                    readableDatabase
                        .rawQuery(
                            "SELECT * FROM port_rules WHERE packageName = ? AND userId = ?",
                            arrayOf(packageName, userId.toString()),
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                list.add(cursor.toDbPortRule())
                            }
                        }
                    list
                }

            override suspend fun insertRule(rule: DbPortRule) {
                withContext(Dispatchers.IO) {
                    val values = rule.toContentValues()
                    if (rule.id != 0L) {
                        values.put("id", rule.id)
                    }
                    writableDatabase.insertWithOnConflict(
                        "port_rules",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    DbNotifier.notifyChanged("port_rules")
                }
            }

            override suspend fun insertRules(rules: List<DbPortRule>) {
                withContext(Dispatchers.IO) {
                    val db = writableDatabase
                    db.beginTransaction()
                    try {
                        for (rule in rules) {
                            val values = rule.toContentValues()
                            if (rule.id != 0L) {
                                values.put("id", rule.id)
                            }
                            db.insertWithOnConflict(
                                "port_rules",
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_REPLACE,
                            )
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    DbNotifier.notifyChanged("port_rules")
                }
            }

            override suspend fun deleteRule(ruleId: Long) {
                withContext(Dispatchers.IO) {
                    writableDatabase.delete(
                        "port_rules",
                        "id = ?",
                        arrayOf(ruleId.toString()),
                    )
                    DbNotifier.notifyChanged("port_rules")
                }
            }

            override suspend fun deleteRulesForApp(
                packageName: String,
                userId: Int,
            ) {
                withContext(Dispatchers.IO) {
                    writableDatabase.delete(
                        "port_rules",
                        "packageName = ? AND userId = ?",
                        arrayOf(packageName, userId.toString()),
                    )
                    DbNotifier.notifyChanged("port_rules")
                }
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
                withContext(Dispatchers.IO) {
                    val list = mutableListOf<DbMassPortRule>()
                    readableDatabase.rawQuery("SELECT * FROM mass_port_rules", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            list.add(cursor.toDbMassPortRule())
                        }
                    }
                    list
                }

            override suspend fun insertMassRule(rule: DbMassPortRule) {
                withContext(Dispatchers.IO) {
                    val values = rule.toContentValues()
                    if (rule.id != 0L) {
                        values.put("id", rule.id)
                    }
                    writableDatabase.insertWithOnConflict(
                        "mass_port_rules",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    DbNotifier.notifyChanged("mass_port_rules")
                }
            }

            override suspend fun insertMassRules(rules: List<DbMassPortRule>) {
                withContext(Dispatchers.IO) {
                    val db = writableDatabase
                    db.beginTransaction()
                    try {
                        for (rule in rules) {
                            val values = rule.toContentValues()
                            if (rule.id != 0L) {
                                values.put("id", rule.id)
                            }
                            db.insertWithOnConflict(
                                "mass_port_rules",
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_REPLACE,
                            )
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    DbNotifier.notifyChanged("mass_port_rules")
                }
            }

            override suspend fun deleteMassRule(ruleId: Long) {
                withContext(Dispatchers.IO) {
                    writableDatabase.delete(
                        "mass_port_rules",
                        "id = ?",
                        arrayOf(ruleId.toString()),
                    )
                    DbNotifier.notifyChanged("mass_port_rules")
                }
            }

            override suspend fun deleteAllMassRules() {
                withContext(Dispatchers.IO) {
                    writableDatabase.delete("mass_port_rules", null, null)
                    DbNotifier.notifyChanged("mass_port_rules")
                }
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
                withContext(Dispatchers.IO) {
                    val list = mutableListOf<String>()
                    readableDatabase.rawQuery("SELECT prefix FROM iface_prefixes", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            list.add(cursor.getString(0))
                        }
                    }
                    list
                }

            override suspend fun insertPrefixes(prefixes: List<DbIfacePrefix>) {
                withContext(Dispatchers.IO) {
                    val db = writableDatabase
                    db.beginTransaction()
                    try {
                        for (prefix in prefixes) {
                            val values =
                                ContentValues().apply {
                                    put("prefix", prefix.prefix)
                                }
                            db.insertWithOnConflict(
                                "iface_prefixes",
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_REPLACE,
                            )
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    DbNotifier.notifyChanged("iface_prefixes")
                }
            }

            override suspend fun deleteAllPrefixes() {
                withContext(Dispatchers.IO) {
                    writableDatabase.delete("iface_prefixes", null, null)
                    DbNotifier.notifyChanged("iface_prefixes")
                }
            }
        }

    private val globalConfigDaoImpl =
        object : GlobalConfigDao {
            override suspend fun getConfig(): DbGlobalConfig? =
                withContext(Dispatchers.IO) {
                    readableDatabase
                        .rawQuery(
                            "SELECT * FROM global_config WHERE id = 'default'",
                            null,
                        ).use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.toDbGlobalConfig()
                            } else {
                                null
                            }
                        }
                }

            override suspend fun insertConfig(config: DbGlobalConfig) {
                withContext(Dispatchers.IO) {
                    val values =
                        ContentValues().apply {
                            put("id", config.id)
                            put("kernelHookMask", config.kernelHookMask)
                            put("javaHookMask", config.javaHookMask)
                            put("debugLogging", config.debugLogging)
                        }
                    writableDatabase.insertWithOnConflict(
                        "global_config",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    DbNotifier.notifyChanged("global_config")
                }
            }
        }

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                if (instance == null) {
                    val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
                    if (!context.isDeviceProtectedStorage) {
                        val ceDbFile = context.getDatabasePath(DbHelper.DATABASE_NAME)
                        if (ceDbFile.exists()) {
                            try {
                                deContext.moveDatabaseFrom(context, DbHelper.DATABASE_NAME)
                            } catch (e: Exception) {
                                Log.e("VpnHideDb", "Failed to move database to device protected storage: ${e.message}", e)
                            }
                        }
                    }
                    instance = AppDatabase(deContext)
                }
                instance!!
            }
    }
}

private fun Cursor.toAppProtection(): AppProtection {
    val pkgIdx = getColumnIndexOrThrow("packageName")
    val userIdx = getColumnIndexOrThrow("userId")
    val uidIdx = getColumnIndexOrThrow("uid")
    val kmodIdx = getColumnIndexOrThrow("kmod")
    val lsposedIdx = getColumnIndexOrThrow("lsposed")
    val portHidingIdx = getColumnIndexOrThrow("portHiding")

    return AppProtection(
        packageName = getString(pkgIdx),
        userId = getInt(userIdx),
        uid = getInt(uidIdx),
        kmod = getInt(kmodIdx) == 1,
        lsposed = getInt(lsposedIdx) == 1,
        portHiding = getInt(portHidingIdx) == 1,
    )
}

private fun AppProtection.toContentValues(): ContentValues =
    ContentValues().apply {
        put("packageName", packageName)
        put("userId", userId)
        put("uid", uid)
        put("kmod", if (kmod) 1 else 0)
        put("lsposed", if (lsposed) 1 else 0)
        put("portHiding", if (portHiding) 1 else 0)
    }

private fun Cursor.toDbPortRule(): DbPortRule {
    val idIdx = getColumnIndexOrThrow("id")
    val pkgIdx = getColumnIndexOrThrow("packageName")
    val userIdx = getColumnIndexOrThrow("userId")
    val startIdx = getColumnIndexOrThrow("startPort")
    val endIdx = getColumnIndexOrThrow("endPort")
    val protoIdx = getColumnIndexOrThrow("protocol")
    val labelIdx = getColumnIndexOrThrow("label")
    val enabledIdx = getColumnIndexOrThrow("enabled")

    return DbPortRule(
        id = getLong(idIdx),
        packageName = getString(pkgIdx),
        userId = getInt(userIdx),
        startPort = getInt(startIdx),
        endPort = getInt(endIdx),
        protocol = PortProtocol.entries[getInt(protoIdx)],
        label = getString(labelIdx),
        enabled = getInt(enabledIdx) == 1,
    )
}

private fun DbPortRule.toContentValues(): ContentValues =
    ContentValues().apply {
        put("packageName", packageName)
        put("userId", userId)
        put("startPort", startPort)
        put("endPort", endPort)
        put("protocol", protocol.ordinal)
        put("label", label)
        put("enabled", if (enabled) 1 else 0)
    }

private fun Cursor.toDbMassPortRule(): DbMassPortRule {
    val idIdx = getColumnIndexOrThrow("id")
    val startIdx = getColumnIndexOrThrow("startPort")
    val endIdx = getColumnIndexOrThrow("endPort")
    val protoIdx = getColumnIndexOrThrow("protocol")
    val labelIdx = getColumnIndexOrThrow("label")
    val enabledIdx = getColumnIndexOrThrow("enabled")

    return DbMassPortRule(
        id = getLong(idIdx),
        startPort = getInt(startIdx),
        endPort = getInt(endIdx),
        protocol = PortProtocol.entries[getInt(protoIdx)],
        label = getString(labelIdx),
        enabled = getInt(enabledIdx) == 1,
    )
}

private fun DbMassPortRule.toContentValues(): ContentValues =
    ContentValues().apply {
        put("startPort", startPort)
        put("endPort", endPort)
        put("protocol", protocol.ordinal)
        put("label", label)
        put("enabled", if (enabled) 1 else 0)
    }

private fun Cursor.toDbGlobalConfig(): DbGlobalConfig {
    val idIdx = getColumnIndexOrThrow("id")
    val kMaskIdx = getColumnIndexOrThrow("kernelHookMask")
    val jMaskIdx = getColumnIndexOrThrow("javaHookMask")
    val debugLoggingIdx = getColumnIndexOrThrow("debugLogging")

    return DbGlobalConfig(
        id = getString(idIdx),
        kernelHookMask = getLong(kMaskIdx),
        javaHookMask = getLong(jMaskIdx),
        debugLogging = getInt(debugLoggingIdx),
    )
}
