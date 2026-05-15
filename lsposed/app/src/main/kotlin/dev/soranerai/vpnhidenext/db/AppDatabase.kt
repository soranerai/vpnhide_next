package dev.soranerai.vpnhidenext.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.soranerai.vpnhidenext.PortProtocol
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AppDao {
    @Query("SELECT * FROM app_protection")
    fun getAllAppProtection(): Flow<List<AppProtection>>

    @Query("SELECT * FROM app_protection WHERE packageName = :packageName AND userId = :userId")
    suspend fun getAppProtection(
        packageName: String,
        userId: Int,
    ): AppProtection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppProtection(app: AppProtection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppProtections(apps: List<AppProtection>)

    @Delete
    suspend fun deleteAppProtection(app: AppProtection)

    @Transaction
    @Query("SELECT * FROM app_protection")
    suspend fun getAllAppProtectionSync(): List<AppProtection>
}

@Dao
internal interface PortRuleDao {
    @Query("SELECT * FROM port_rules WHERE packageName = :packageName AND userId = :userId")
    fun getRulesForApp(
        packageName: String,
        userId: Int,
    ): Flow<List<DbPortRule>>

    @Query("SELECT * FROM port_rules WHERE packageName = :packageName AND userId = :userId")
    suspend fun getRulesForAppSync(
        packageName: String,
        userId: Int,
    ): List<DbPortRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: DbPortRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<DbPortRule>)

    @Query("DELETE FROM port_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("DELETE FROM port_rules WHERE packageName = :packageName AND userId = :userId")
    suspend fun deleteRulesForApp(
        packageName: String,
        userId: Int,
    )
}

@Dao
internal interface MassPortRuleDao {
    @Query("SELECT * FROM mass_port_rules")
    fun getMassRules(): Flow<List<DbMassPortRule>>

    @Query("SELECT * FROM mass_port_rules")
    suspend fun getMassRulesSync(): List<DbMassPortRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMassRule(rule: DbMassPortRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMassRules(rules: List<DbMassPortRule>)

    @Query("DELETE FROM mass_port_rules WHERE id = :ruleId")
    suspend fun deleteMassRule(ruleId: Long)

    @Query("DELETE FROM mass_port_rules")
    suspend fun deleteAllMassRules()
}

@Dao
internal interface IfacePrefixDao {
    @Query("SELECT prefix FROM iface_prefixes")
    fun getAllPrefixes(): Flow<List<String>>

    @Query("SELECT prefix FROM iface_prefixes")
    suspend fun getAllPrefixesSync(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrefixes(prefixes: List<DbIfacePrefix>)

    @Query("DELETE FROM iface_prefixes")
    suspend fun deleteAllPrefixes()
}

internal class Converters {
    @TypeConverter
    fun fromProtocol(protocol: PortProtocol): Int = protocol.ordinal

    @TypeConverter
    fun toProtocol(value: Int): PortProtocol = PortProtocol.entries[value]
}

@Database(
    entities = [AppProtection::class, DbPortRule::class, DbMassPortRule::class, DbIfacePrefix::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    abstract fun portRuleDao(): PortRuleDao

    abstract fun massPortRuleDao(): MassPortRuleDao

    abstract fun ifacePrefixDao(): IfacePrefixDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_2_3 = createAppProtectionMigration(2, 3)
        private val MIGRATION_1_3 = createAppProtectionMigration(1, 3)

        private fun createAppProtectionMigration(
            start: Int,
            end: Int,
        ) = object : Migration(start, end) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_protection_new (
                        packageName TEXT NOT NULL,
                        kmod INTEGER NOT NULL,
                        lsposed INTEGER NOT NULL,
                        portHiding INTEGER NOT NULL,
                        PRIMARY KEY(packageName)
                    )
                    """.trimIndent(),
                )

                val cursor = db.query("PRAGMA table_info(app_protection)")
                var hasTunBypass = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "tunBypass") {
                        hasTunBypass = true
                        break
                    }
                }
                cursor.close()

                if (hasTunBypass) {
                    db.execSQL(
                        """
                        INSERT INTO app_protection_new (packageName, kmod, lsposed, portHiding)
                        SELECT packageName, kmod, lsposed, portHiding FROM app_protection
                        """.trimIndent(),
                    )
                } else {
                    db.execSQL(
                        """
                        INSERT INTO app_protection_new (packageName, kmod, lsposed, portHiding)
                        SELECT packageName, kmod, lsposed, 0 FROM app_protection
                        """.trimIndent(),
                    )
                }

                db.execSQL("DROP TABLE IF EXISTS app_protection")
                db.execSQL("ALTER TABLE app_protection_new RENAME TO app_protection")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=OFF")

                    // 1. app_protection
                    db.execSQL(
                        """
                        CREATE TABLE app_protection_v4 (
                            packageName TEXT NOT NULL,
                            userId INTEGER NOT NULL DEFAULT 0,
                            kmod INTEGER NOT NULL,
                            lsposed INTEGER NOT NULL,
                            portHiding INTEGER NOT NULL,
                            PRIMARY KEY(packageName, userId)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO app_protection_v4 (packageName, userId, kmod, lsposed, portHiding)
                        SELECT packageName, 0, kmod, lsposed, portHiding FROM app_protection
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE app_protection")
                    db.execSQL("ALTER TABLE app_protection_v4 RENAME TO app_protection")

                    // 2. port_rules
                    db.execSQL(
                        """
                        CREATE TABLE port_rules_v4 (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            packageName TEXT NOT NULL,
                            userId INTEGER NOT NULL DEFAULT 0,
                            startPort INTEGER NOT NULL,
                            endPort INTEGER NOT NULL,
                            protocol INTEGER NOT NULL,
                            label TEXT NOT NULL,
                            enabled INTEGER NOT NULL,
                            FOREIGN KEY(packageName, userId) REFERENCES app_protection(packageName, userId) ON UPDATE NO ACTION ON DELETE CASCADE 
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX index_port_rules_packageName_userId ON port_rules_v4(packageName, userId)")
                    db.execSQL(
                        """
                        INSERT INTO port_rules_v4 (id, packageName, userId, startPort, endPort, protocol, label, enabled)
                        SELECT id, packageName, 0, startPort, endPort, protocol, label, enabled FROM port_rules
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE port_rules")
                    db.execSQL("ALTER TABLE port_rules_v4 RENAME TO port_rules")

                    db.execSQL("PRAGMA foreign_keys=ON")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS iface_prefixes (
                            prefix TEXT NOT NULL,
                            PRIMARY KEY(prefix)
                        )
                        """.trimIndent(),
                    )
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_protection ADD COLUMN uid INTEGER NOT NULL DEFAULT 0")
                }
            }

        private val MIGRATION_1_5 =
            object : Migration(1, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_1_3.migrate(db)
                    MIGRATION_3_4.migrate(db)
                    MIGRATION_4_5.migrate(db)
                }
            }

        private val MIGRATION_2_5 =
            object : Migration(2, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_2_3.migrate(db)
                    MIGRATION_3_4.migrate(db)
                    MIGRATION_4_5.migrate(db)
                }
            }

        private val MIGRATION_3_5 =
            object : Migration(3, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_3_4.migrate(db)
                    MIGRATION_4_5.migrate(db)
                }
            }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                val newInstance =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "vpnhide_database",
                        ).addMigrations(
                            MIGRATION_1_3,
                            MIGRATION_2_3,
                            MIGRATION_3_4,
                            MIGRATION_4_5,
                            MIGRATION_1_5,
                            MIGRATION_2_5,
                            MIGRATION_3_5,
                            MIGRATION_5_6,
                        ).fallbackToDestructiveMigration()
                        .build()
                instance = newInstance
                newInstance
            }
    }
}
