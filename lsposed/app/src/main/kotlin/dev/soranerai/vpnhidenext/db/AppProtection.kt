package dev.soranerai.vpnhidenext.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_protection")
internal data class AppProtection(
    @PrimaryKey val packageName: String,
    val kmod: Boolean = false,
    val lsposed: Boolean = false,
    val tunBypass: Boolean = false,
    val portHiding: Boolean = false,
)
