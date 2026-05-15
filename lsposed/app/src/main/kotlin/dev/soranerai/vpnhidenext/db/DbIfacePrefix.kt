package dev.soranerai.vpnhidenext.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "iface_prefixes")
internal data class DbIfacePrefix(
    @PrimaryKey val prefix: String,
)
