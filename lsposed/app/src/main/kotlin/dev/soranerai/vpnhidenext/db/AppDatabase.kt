package dev.soranerai.vpnhidenext.db

import android.content.Context
import androidx.room.*
import dev.soranerai.vpnhidenext.PortProtocol
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AppDao {
    @Query("SELECT * FROM app_protection")
    fun getAllAppProtection(): Flow<List<AppProtection>>

    @Query("SELECT * FROM app_protection WHERE packageName = :packageName")
    suspend fun getAppProtection(packageName: String): AppProtection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppProtection(app: AppProtection)

    @Delete
    suspend fun deleteAppProtection(app: AppProtection)

    @Transaction
    @Query("SELECT * FROM app_protection")
    suspend fun getAllAppProtectionSync(): List<AppProtection>
}

@Dao
internal interface PortRuleDao {
    @Query("SELECT * FROM port_rules WHERE packageName = :packageName")
    fun getRulesForApp(packageName: String): Flow<List<DbPortRule>>

    @Query("SELECT * FROM port_rules WHERE packageName = :packageName")
    suspend fun getRulesForAppSync(packageName: String): List<DbPortRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: DbPortRule)

    @Query("DELETE FROM port_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("DELETE FROM port_rules WHERE packageName = :packageName")
    suspend fun deleteRulesForApp(packageName: String)
}

@Dao
internal interface MassPortRuleDao {
    @Query("SELECT * FROM mass_port_rules")
    fun getMassRules(): Flow<List<DbMassPortRule>>

    @Query("SELECT * FROM mass_port_rules")
    suspend fun getMassRulesSync(): List<DbMassPortRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMassRule(rule: DbMassPortRule)

    @Query("DELETE FROM mass_port_rules WHERE id = :ruleId")
    suspend fun deleteMassRule(ruleId: Long)

    @Query("DELETE FROM mass_port_rules")
    suspend fun deleteAllMassRules()
}

internal class Converters {
    @TypeConverter
    fun fromProtocol(protocol: PortProtocol): Int = protocol.ordinal

    @TypeConverter
    fun toProtocol(value: Int): PortProtocol = PortProtocol.entries[value]
}

@Database(
    entities = [AppProtection::class, DbPortRule::class, DbMassPortRule::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    abstract fun portRuleDao(): PortRuleDao

    abstract fun massPortRuleDao(): MassPortRuleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                val newInstance =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "vpnhide_database",
                        ).build()
                instance = newInstance
                newInstance
            }
    }
}
