package dev.soranerai.vpnhidenext.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

internal class DbHelper(
    context: Context,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        const val DATABASE_NAME = "vpnhide_database"
        const val DATABASE_VERSION = 8
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAllTables(db)
        insertDefaultConfig(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        autoMigrate(db)
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        autoMigrate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys=ON;")
        autoMigrate(db)
    }

    private fun createAllTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_protection (
                packageName TEXT NOT NULL,
                userId INTEGER NOT NULL DEFAULT 0,
                kmod INTEGER NOT NULL DEFAULT 0,
                lsposed INTEGER NOT NULL DEFAULT 0,
                portHiding INTEGER NOT NULL DEFAULT 0,
                uid INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(packageName, userId)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS port_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                packageName TEXT NOT NULL,
                userId INTEGER NOT NULL DEFAULT 0,
                startPort INTEGER NOT NULL,
                endPort INTEGER NOT NULL,
                protocol INTEGER NOT NULL,
                label TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(packageName, userId) REFERENCES app_protection(packageName, userId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_port_rules_packageName_userId ON port_rules(packageName, userId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mass_port_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startPort INTEGER NOT NULL,
                endPort INTEGER NOT NULL,
                protocol INTEGER NOT NULL,
                label TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS iface_prefixes (
                prefix TEXT NOT NULL,
                PRIMARY KEY(prefix)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS global_config (
                id TEXT NOT NULL,
                kernelHookMask INTEGER NOT NULL DEFAULT 4294967295,
                javaHookMask INTEGER NOT NULL DEFAULT 4294967295,
                debugLogging INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
    }

    private fun insertDefaultConfig(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO global_config (id, kernelHookMask, javaHookMask, debugLogging)
            VALUES ('default', 4294967295, 4294967295, 0)
            """.trimIndent(),
        )
    }

    private fun autoMigrate(db: SQLiteDatabase) {
        val existingAppProtection = getExistingColumns(db, "app_protection")
        if (existingAppProtection.isNotEmpty() && !existingAppProtection.contains("userId")) {
            Log.i("VpnHideDb", "Upgrading ancient database from v3 or below")
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE app_protection RENAME TO app_protection_old")
                val existingPortRules = getExistingColumns(db, "port_rules")
                var hasOldPortRules = false
                if (existingPortRules.isNotEmpty()) {
                    db.execSQL("ALTER TABLE port_rules RENAME TO port_rules_old")
                    hasOldPortRules = true
                }
                createAllTables(db)
                db.execSQL(
                    "INSERT INTO app_protection (packageName, userId, kmod, lsposed, portHiding, uid) " +
                        "SELECT packageName, 0, kmod, lsposed, 0, 0 FROM app_protection_old",
                )
                if (hasOldPortRules) {
                    db.execSQL(
                        "INSERT INTO port_rules (id, packageName, userId, startPort, endPort, protocol, label, enabled) " +
                            "SELECT id, packageName, 0, startPort, endPort, protocol, label, enabled FROM port_rules_old",
                    )
                    db.execSQL("DROP TABLE IF EXISTS port_rules_old")
                }
                db.execSQL("DROP TABLE IF EXISTS app_protection_old")
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e("VpnHideDb", "Failed to migrate ancient v3 database: ${e.message}")
            } finally {
                db.endTransaction()
            }
        }

        createAllTables(db)

        val schema =
            mapOf(
                "app_protection" to
                    mapOf(
                        "packageName" to "TEXT NOT NULL DEFAULT ''",
                        "userId" to "INTEGER NOT NULL DEFAULT 0",
                        "kmod" to "INTEGER NOT NULL DEFAULT 0",
                        "lsposed" to "INTEGER NOT NULL DEFAULT 0",
                        "portHiding" to "INTEGER NOT NULL DEFAULT 0",
                        "uid" to "INTEGER NOT NULL DEFAULT 0",
                    ),
                "port_rules" to
                    mapOf(
                        "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
                        "packageName" to "TEXT NOT NULL DEFAULT ''",
                        "userId" to "INTEGER NOT NULL DEFAULT 0",
                        "startPort" to "INTEGER NOT NULL DEFAULT 0",
                        "endPort" to "INTEGER NOT NULL DEFAULT 0",
                        "protocol" to "INTEGER NOT NULL DEFAULT 0",
                        "label" to "TEXT NOT NULL DEFAULT ''",
                        "enabled" to "INTEGER NOT NULL DEFAULT 1",
                    ),
                "mass_port_rules" to
                    mapOf(
                        "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
                        "startPort" to "INTEGER NOT NULL DEFAULT 0",
                        "endPort" to "INTEGER NOT NULL DEFAULT 0",
                        "protocol" to "INTEGER NOT NULL DEFAULT 0",
                        "label" to "TEXT NOT NULL DEFAULT ''",
                        "enabled" to "INTEGER NOT NULL DEFAULT 1",
                    ),
                "iface_prefixes" to
                    mapOf(
                        "prefix" to "TEXT NOT NULL DEFAULT ''",
                    ),
                "global_config" to
                    mapOf(
                        "id" to "TEXT NOT NULL DEFAULT ''",
                        "kernelHookMask" to "INTEGER NOT NULL DEFAULT 4294967295",
                        "javaHookMask" to "INTEGER NOT NULL DEFAULT 4294967295",
                        "debugLogging" to "INTEGER NOT NULL DEFAULT 0",
                    ),
            )

        schema.forEach { (tableName, cols) ->
            val existing = getExistingColumns(db, tableName)
            cols.forEach { (colName, colType) ->
                if (!existing.contains(colName)) {
                    Log.i("VpnHideDb", "autoMigrate: Adding column $colName to table $tableName")
                    try {
                        db.execSQL("ALTER TABLE $tableName ADD COLUMN $colName $colType")
                    } catch (e: Exception) {
                        Log.e("VpnHideDb", "Failed to add column $colName to table $tableName: ${e.message}")
                    }
                }
            }
        }

        insertDefaultConfig(db)
    }

    private fun getExistingColumns(
        db: SQLiteDatabase,
        tableName: String,
    ): Set<String> {
        val columns = mutableSetOf<String>()
        try {
            db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                if (nameIdx >= 0) {
                    while (cursor.moveToNext()) {
                        columns.add(cursor.getString(nameIdx))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VpnHideDb", "Failed to read table info for $tableName: ${e.message}")
        }
        return columns
    }
}
