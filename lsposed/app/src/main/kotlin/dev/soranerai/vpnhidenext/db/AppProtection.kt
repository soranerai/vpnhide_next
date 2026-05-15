package dev.soranerai.vpnhidenext.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_protection", primaryKeys = ["packageName", "userId"])
internal data class AppProtection(
    val packageName: String,
    val userId: Int = 0,
    val uid: Int = 0,
    val kmod: Boolean = false,
    val lsposed: Boolean = false,
    val portHiding: Boolean = false,
)
