package dev.soranerai.vpnhidenext

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Establishes a tun interface scoped to *this app only* (via
 * [android.net.VpnService.Builder.addAllowedApplication]) with a full
 * default route — so [android.net.ConnectivityManager] actually reports
 * `TRANSPORT_VPN` as our own active network, same as a real VPN app would
 * look from a protected app's point of view. No other app on the device is
 * affected: traffic from every other package keeps using the physical
 * network untouched. Nothing is ever read from or written to the tun fd,
 * so even though the route is "0.0.0.0/0", no packet actually leaves the
 * device through it — it only exists to make the checks battery believe
 * a VPN is active.
 *
 * Lets a user verify the kernel module/LSPosed hooks actually work on
 * their device *before* trusting a real VPN app to it.
 *
 * `establish()` is a real OS call that can fail for reasons outside our
 * control (another app holds always-on VPN lockdown, permission revoked
 * mid-flow, OEM quirks) — every step here is wrapped so a failure degrades
 * to "test didn't start" instead of crashing the host process.
 *
 * Teardown does NOT rely on `Context.stopService()` alone: once a VPN is
 * established, the system implicitly binds to this service to track the
 * tunnel, so a plain `stopService()` call from the UI doesn't reliably
 * trigger [onDestroy] (the started/stopped count drops, but the system's
 * own bind keeps the process alive) — which is why the tunnel used to
 * stay up even after the test screen was left or the app was closed.
 * [requestStop] closes the tun fd directly instead of waiting for the
 * service lifecycle to get there. Backed up by two independent triggers
 * that don't depend on any particular screen still being composed:
 * [ActivityLifecycleCallbacks] detecting "zero started activities" (app
 * backgrounded/closed), and a hard [AUTO_STOP_MS] timeout.
 */
internal class SelfTestVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tunFd: ParcelFileDescriptor? = null
    private var autoStopJob: Job? = null
    private var stopWatcherJob: Job? = null
    private var startedActivityCount = 0
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    override fun onCreate() {
        super.onCreate()
        val callbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount++
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    if (startedActivityCount == 0) teardown()
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {}

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) {}

                override fun onActivityDestroyed(activity: Activity) {}
            }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (tunFd == null) {
            tunFd =
                runCatching {
                    Builder()
                        .setSession(getString(R.string.self_test_vpn_session_name))
                        .addAddress(TEST_LOCAL_ADDRESS, 32)
                        .addRoute("0.0.0.0", 0)
                        .addAllowedApplication(packageName)
                        .establish()
                }.onFailure {
                    VpnHideLog.w("VpnHide-SelfTest", "establish() failed: ${it.message}")
                }.getOrNull()
            _established.value = tunFd != null
            if (tunFd == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            autoStopJob =
                serviceScope.launch {
                    delay(AUTO_STOP_MS)
                    teardown()
                }
            stopWatcherJob =
                serviceScope.launch {
                    stopRequested.first { it }
                    teardown()
                }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        teardown()
    }

    override fun onDestroy() {
        teardown()
        serviceScope.cancel()
        lifecycleCallbacks?.let { application.unregisterActivityLifecycleCallbacks(it) }
        super.onDestroy()
    }

    /** Closes the tun fd immediately instead of waiting for [onDestroy] —
     * see the class doc for why that matters. Idempotent: safe to call
     * from any of the several independent triggers, possibly more than
     * once. */
    private fun teardown() {
        autoStopJob?.cancel()
        stopWatcherJob?.cancel()
        runCatching { tunFd?.close() }
        tunFd = null
        _established.value = false
        stopRequested.value = false
        stopSelf()
    }

    companion object {
        private const val TEST_LOCAL_ADDRESS = "192.0.2.1"
        private const val AUTO_STOP_MS = 30_000L

        private val _established = MutableStateFlow(false)
        private val stopRequested = MutableStateFlow(false)

        /** True once `establish()` has actually returned a live tun fd —
         * the UI awaits this before triggering a diagnostics re-run, since
         * `startService()` returning doesn't mean `onStartCommand` has run
         * yet. Goes back to false on any failure or teardown, so the UI's
         * own await-timeout is what surfaces a stuck/failed attempt. */
        val established: StateFlow<Boolean> = _established.asStateFlow()

        /** Ask the running instance (if any) to tear down its tunnel right
         * away. A no-op if no test is in flight. */
        fun requestStop() {
            stopRequested.value = true
        }
    }
}

/**
 * Silently drives [SelfTestVpnService] from [DiagnosticsCache] — no
 * dedicated button, no confirmation dialog of our own. The one system
 * dialog this can't avoid (the first-ever `VpnService.prepare()` consent
 * prompt; every call after the user accepts it once returns null and
 * skips straight to establishing) is launched directly as its own task
 * rather than through an Activity Result API, since [DiagnosticsCache] is
 * a plain object with no Activity to host a launcher.
 */
internal object SelfTestVpnCoordinator {
    private const val CONSENT_TIMEOUT_MS = 20_000L
    private const val ESTABLISH_TIMEOUT_MS = 5_000L

    /** Establishes the test tunnel and waits for confirmation. Returns
     * false (leaving nothing running) if consent is denied/ignored or
     * `establish()` fails for any reason. */
    suspend fun tryEstablish(context: Context): Boolean {
        val prepareIntent =
            runCatching { VpnService.prepare(context) }.getOrNull()
        if (prepareIntent != null) {
            runCatching {
                context.startActivity(prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { return false }
            // `prepare()` returns the same non-null intent whether the user
            // hasn't answered yet or just tapped Deny — there's no distinct
            // "denied" signal to poll for. Instead of polling blindly up to
            // the full timeout, react the moment our activity resumes
            // (i.e. the system consent dialog closed, either way) and
            // re-check immediately — the timeout below is just a backstop
            // in case that resume signal never arrives.
            val granted =
                withTimeoutOrNull(CONSENT_TIMEOUT_MS) {
                    awaitNextResume(context)
                    VpnService.prepare(context) == null
                } ?: false
            if (!granted) return false
        }

        context.startService(Intent(context, SelfTestVpnService::class.java))
        val established =
            withTimeoutOrNull(ESTABLISH_TIMEOUT_MS) {
                SelfTestVpnService.established.first { it }
            } != null
        if (!established) SelfTestVpnService.requestStop()
        return established
    }

    fun stop() {
        SelfTestVpnService.requestStop()
    }

    private suspend fun awaitNextResume(context: Context) {
        val app = context.applicationContext as? Application ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            val callbacks =
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        app.unregisterActivityLifecycleCallbacks(this)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onActivityCreated(
                        activity: Activity,
                        savedInstanceState: Bundle?,
                    ) {}

                    override fun onActivityStarted(activity: Activity) {}

                    override fun onActivityPaused(activity: Activity) {}

                    override fun onActivityStopped(activity: Activity) {}

                    override fun onActivitySaveInstanceState(
                        activity: Activity,
                        outState: Bundle,
                    ) {}

                    override fun onActivityDestroyed(activity: Activity) {}
                }
            app.registerActivityLifecycleCallbacks(callbacks)
            cont.invokeOnCancellation { app.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
}
