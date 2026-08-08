package dev.soranerai.vpnhidenext

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val BUILT_IN_DEBUG_PREFS = "built_in_update_debug"
private const val PREF_DEBUG_UNLOCKED = "unlocked"
private const val PREF_DEBUG_ENABLED = "enabled"

internal object BuiltInUpdateDebugPrefs {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(BUILT_IN_DEBUG_PREFS, Context.MODE_PRIVATE)
        _unlocked.value = prefs.getBoolean(PREF_DEBUG_UNLOCKED, false)
        _enabled.value = _unlocked.value && prefs.getBoolean(PREF_DEBUG_ENABLED, false)
        initialized = true
    }

    fun unlock(context: Context) {
        initialize(context)
        if (_unlocked.value) return
        context.applicationContext
            .getSharedPreferences(BUILT_IN_DEBUG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DEBUG_UNLOCKED, true)
            .apply()
        _unlocked.value = true
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        initialize(context)
        if (!_unlocked.value) return
        context.applicationContext
            .getSharedPreferences(BUILT_IN_DEBUG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DEBUG_ENABLED, enabled)
            .apply()
        _enabled.value = enabled
    }
}

internal class RapidTapUnlocker(
    private val requiredTaps: Int = 10,
    private val maximumGapMs: Long = 600,
) {
    private var taps = 0
    private var previousTapMs: Long? = null

    fun recordTap(nowMs: Long): Boolean {
        val previous = previousTapMs
        taps = if (previous == null || nowMs < previous || nowMs - previous > maximumGapMs) 1 else taps + 1
        previousTapMs = nowMs
        if (taps < requiredTaps) return false
        taps = 0
        previousTapMs = null
        return true
    }
}
