package dev.soranerai.vpnhidenext.db

internal data class DbGlobalConfig(
    val id: String = "default",
    val kernelHookMask: Long = 0xFFFFFFFFL,
    val javaHookMask: Long = 0xFFFFFFFFL,
    val debugLogging: Int = 0,
)

internal interface GlobalConfigDao {
    suspend fun getConfig(): DbGlobalConfig?

    suspend fun insertConfig(config: DbGlobalConfig)
}
