package dev.soranerai.vpnhidenext.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.soranerai.vpnhidenext.PortProtocol

@Entity(tableName = "mass_port_rules")
internal data class DbMassPortRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startPort: Int,
    val endPort: Int,
    internal val protocol: PortProtocol,
    internal val label: String = "",
    internal val enabled: Boolean = true,
)
