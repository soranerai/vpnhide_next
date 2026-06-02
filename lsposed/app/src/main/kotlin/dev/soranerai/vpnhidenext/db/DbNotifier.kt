package dev.soranerai.vpnhidenext.db

import kotlinx.coroutines.flow.MutableSharedFlow

internal object DbNotifier {
    val changeFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    fun notifyChanged(table: String) {
        changeFlow.tryEmit(table)
    }
}
