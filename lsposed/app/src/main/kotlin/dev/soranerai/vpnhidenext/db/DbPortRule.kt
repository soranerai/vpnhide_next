package dev.soranerai.vpnhidenext.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.soranerai.vpnhidenext.PortProtocol

@Entity(
    tableName = "port_rules",
    foreignKeys = [
        ForeignKey(
            entity = AppProtection::class,
            parentColumns = ["packageName", "userId"],
            childColumns = ["packageName", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["packageName", "userId"])],
)
internal data class DbPortRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val userId: Int = 0,
    val startPort: Int,
    val endPort: Int,
    internal val protocol: PortProtocol,
    val label: String = "",
    val enabled: Boolean = true,
)
