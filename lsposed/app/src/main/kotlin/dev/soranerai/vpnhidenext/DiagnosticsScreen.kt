package dev.soranerai.vpnhidenext

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.soranerai.vpnhidenext.checks.CheckOutput
import dev.soranerai.vpnhidenext.checks.CheckStatus
import dev.soranerai.vpnhidenext.checks.checkBpfIfaceMap
import dev.soranerai.vpnhidenext.checks.checkGetifaddrs
import dev.soranerai.vpnhidenext.checks.checkGetsocknameSpoof
import dev.soranerai.vpnhidenext.checks.checkGetsockoptBind
import dev.soranerai.vpnhidenext.checks.checkGsoAsymmetry
import dev.soranerai.vpnhidenext.checks.checkInetDiag
import dev.soranerai.vpnhidenext.checks.checkIoctlSiocgifconf
import dev.soranerai.vpnhidenext.checks.checkIoctlSiocgifflags
import dev.soranerai.vpnhidenext.checks.checkIoctlSiocgifmtu
import dev.soranerai.vpnhidenext.checks.checkIpv6LinkLocalBruteforce
import dev.soranerai.vpnhidenext.checks.checkNetlinkAnonymousRoute
import dev.soranerai.vpnhidenext.checks.checkNetlinkGetlink
import dev.soranerai.vpnhidenext.checks.checkNetlinkGetneigh
import dev.soranerai.vpnhidenext.checks.checkNetlinkGetroute
import dev.soranerai.vpnhidenext.checks.checkNetlinkGetrule
import dev.soranerai.vpnhidenext.checks.checkPmtuCachePoisoning
import dev.soranerai.vpnhidenext.checks.checkProcNetDev
import dev.soranerai.vpnhidenext.checks.checkProcNetFibTrie
import dev.soranerai.vpnhidenext.checks.checkProcNetIfInet6
import dev.soranerai.vpnhidenext.checks.checkProcNetIpv6Route
import dev.soranerai.vpnhidenext.checks.checkProcNetRoute
import dev.soranerai.vpnhidenext.checks.checkProcNetTcp
import dev.soranerai.vpnhidenext.checks.checkProcNetTcp6
import dev.soranerai.vpnhidenext.checks.checkProcNetUdp
import dev.soranerai.vpnhidenext.checks.checkProcNetUdp6
import dev.soranerai.vpnhidenext.checks.checkProcSysNetConf
import dev.soranerai.vpnhidenext.checks.checkQdiscByIfindex
import dev.soranerai.vpnhidenext.checks.checkRtmGetlinkTrimOracle
import dev.soranerai.vpnhidenext.checks.checkSysClassNet
import dev.soranerai.vpnhidenext.checks.checkTcpInfoMss
import dev.soranerai.vpnhidenext.checks.checkTcpMss
import dev.soranerai.vpnhidenext.checks.checkTimestampingHw
import dev.soranerai.vpnhidenext.checks.checkUdpPmtu
import dev.soranerai.vpnhidenext.checks.checkUdpQueuePressure
import dev.soranerai.vpnhidenext.checks.checkUidRouteRulesLeak
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.SettingsBackupHelper
import dev.soranerai.vpnhidenext.generated.IfaceLists
import dev.soranerai.vpnhidenext.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "VPNHideTest"

@androidx.compose.runtime.Immutable
data class CheckResult(
    val name: String,
    val passed: Boolean?,
    val detail: String,
    val isRunning: Boolean = false,
    val isSkipped: Boolean = false,
)

// Maps each native check's string resource ID to the kernel hook bit indices it depends on.
// Empty array = no specific hook dependency (always runs). A check is skipped when ALL of its
// required bits are disabled in the kernel mask (none set → hook isn't protecting this vector).
private val NATIVE_CHECK_HOOK_BITS: Map<Int, IntArray> =
    mapOf(
        R.string.check_ioctl_flags to intArrayOf(0), // HOOK_DEV_IOCTL
        R.string.check_ioctl_mtu to intArrayOf(0),
        R.string.check_ioctl_conf to intArrayOf(1), // HOOK_SOCK_IOCTL
        R.string.check_getifaddrs to intArrayOf(2, 3, 4), // HOOK_RTNL_FILL, HOOK_INET6_FILL, HOOK_INET_FILL
        R.string.check_netlink_getlink to intArrayOf(2), // HOOK_RTNL_FILL
        R.string.check_netlink_getroute to intArrayOf(7, 9, 10), // HOOK_FIB_DUMP, HOOK_RT6_FILL, HOOK_RT_FILL
        R.string.check_netlink_anonymous_route to intArrayOf(7), // HOOK_FIB_DUMP
        R.string.check_proc_route to intArrayOf(5), // HOOK_FIB_ROUTE
        R.string.check_proc_ipv6_route to intArrayOf(6), // HOOK_IPV6_ROUTE
        R.string.check_proc_if_inet6 to intArrayOf(27), // HOOK_IF6_SEQ
        R.string.check_proc_tcp to intArrayOf(), // proc/net/tcp has no iface names; always runs
        R.string.check_proc_tcp6 to intArrayOf(),
        R.string.check_proc_udp to intArrayOf(),
        R.string.check_proc_udp6 to intArrayOf(),
        R.string.check_proc_dev to intArrayOf(26), // HOOK_DEV_SEQ
        R.string.check_proc_fib_trie to intArrayOf(30), // HOOK_FIB_TRIE
        R.string.check_bpf_iface_map to intArrayOf(17), // HOOK_BPF
        R.string.check_sys_class_net to intArrayOf(18, 19, 20, 21, 22, 23, 24), // file-hiding hooks
        R.string.check_net_iface_enum to intArrayOf(2, 3, 4), // HOOK_RTNL_FILL, HOOK_INET6_FILL, HOOK_INET_FILL
        R.string.check_proc_route_java to intArrayOf(5), // HOOK_FIB_ROUTE
        R.string.check_getsockopt_bind to intArrayOf(11, 12), // HOOK_SETSOCKOPT, HOOK_GETSOCKOPT
        R.string.check_inet_diag to intArrayOf(), // SELinux policy check; no hook dependency
        R.string.check_getsockname_spoof to intArrayOf(14, 15), // HOOK_GETNAME_INET, HOOK_GETNAME_INET6
        R.string.check_netlink_getrule to intArrayOf(8), // HOOK_FIB_RULE_FILL
        R.string.check_tcp_mss to intArrayOf(12), // HOOK_GETSOCKOPT (spoofs IP_MTU + TCP_MAXSEG)
        R.string.check_udp_pmtu to intArrayOf(11), // HOOK_SETSOCKOPT (changes IP_MTU_DISCOVER→PMTUDISC_DONT)
        R.string.check_netlink_getneigh to intArrayOf(), // RTM_GETNEIGH not hooked; always runs
        R.string.check_proc_sys_net_conf to intArrayOf(18, 19, 20, 21, 22, 23, 24),
        R.string.check_udp_queue_pressure to intArrayOf(25), // HOOK_UDP_SENDMSG
        R.string.check_gso_asymmetry to intArrayOf(11), // HOOK_SETSOCKOPT (zeroes UDP_SEGMENT to block GSO probe)
        R.string.check_ipv6_link_local_bruteforce to intArrayOf(28, 29), // HOOK_INET6_BIND_LL, HOOK_UDPV6_SENDMSG
        R.string.check_uid_route_rules_leak to intArrayOf(8), // HOOK_FIB_RULE_FILL (RTM_GETRULE)
        R.string.check_tcp_info_mss to intArrayOf(12), // HOOK_GETSOCKOPT (spoofs TCP_INFO MSS fields)
        R.string.check_qdisc_by_ifindex to intArrayOf(31), // HOOK_TC_FILL_QDISC
        R.string.check_timestamping_hw to intArrayOf(11), // HOOK_SETSOCKOPT (strips HW timestamp bits)
        R.string.check_rtm_getlink_trim_oracle to intArrayOf(2), // HOOK_RTNL_FILL
        R.string.check_traffic_stats to intArrayOf(17), // HOOK_BPF (TrafficStats via BPF maps)
    )

private fun isCheckSkipped(
    resId: Int,
    hookMask: UInt,
): Boolean {
    val bits = NATIVE_CHECK_HOOK_BITS[resId] ?: return false
    if (bits.isEmpty()) return false
    return bits.none { (hookMask and (1u shl it)) != 0u }
}

@androidx.compose.runtime.Immutable
internal data class CheckResults(
    val native: List<CheckResult>,
    val java: List<CheckResult>,
) {
    val all
        get() = native + java
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    selfNeedsRestart: Boolean,
    onOpenHookTesting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val scope = rememberCoroutineScope()

    val diagState by DiagnosticsCache.state.collectAsState()
    var exporting by remember { mutableStateOf(false) }
    var debugZipFile by remember { mutableStateOf<File?>(null) }
    var showAllChecks by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Kick off the diagnostics run once per process. If selfNeedsRestart
    // is true we skip — hooks aren't applied to this app yet, results
    // would be meaningless. DiagnosticsCache.run is idempotent: no-op
    // when Ready/Running.
    LaunchedEffect(selfNeedsRestart) {
        if (!selfNeedsRestart) {
            DiagnosticsCache.run(scope, context)
        }
    }

    val saveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri: Uri? ->
            val zip = debugZipFile ?: return@rememberLauncherForActivityResult
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    zip.inputStream().use { it.copyTo(out) }
                }
            }
        }

    val stateVal = diagState
    val results =
        when (stateVal) {
            is DiagnosticsCache.State.Ready -> stateVal.results
            is DiagnosticsCache.State.Running -> stateVal.results
            else -> null
        }
    // Native probes that couldn't run (ECONNREFUSED from socket()) are
    // represented as passed=null by nativeCheck. Java-level checks never
    // produce that state, so this test isolates the "app has no network
    // permission" banner from everything else.
    val networkBlocked = results?.native?.any { it.passed == null && !it.isRunning } == true
    val hasFailed = results?.all?.any { !it.isSkipped && it.passed == false } == true

    val failedNative = remember(results) { results?.native?.filter { !it.isSkipped && it.passed == false } ?: emptyList() }
    val failedJava = remember(results) { results?.java?.filter { !it.isSkipped && it.passed == false } ?: emptyList() }
    val isChecking = remember(results) { results?.all?.any { it.isRunning } == true }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            DiagnosticsCache.retry(scope, context)
            isRefreshing = false
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
        ) {
            item(key = "spacer_top") { Spacer(Modifier.height(8.dp)) }

            item(key = "backup_restore_card") { BackupRestoreCard() }

            item(key = "spacer_middle") { Spacer(Modifier.height(16.dp)) }

            // Protection check section — its content depends on cache state,
            // but the bottom debug-tools section always renders below so
            // users can collect logs / toggle verbose logging even when
            // VPN is off or a run is in flight.
            if (selfNeedsRestart) {
                item(key = "banner_restart") {
                    StatusBanner(
                        text = stringResource(R.string.banner_added_self),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            } else if (diagState is DiagnosticsCache.State.NotRun) {
                item(key = "progress_indicator") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (diagState is DiagnosticsCache.State.VpnOff) {
                item(key = "vpn_off_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = stringResource(R.string.diag_no_vpn_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.diag_no_vpn_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (diagState is DiagnosticsCache.State.Running || diagState is DiagnosticsCache.State.Ready) {
                if (networkBlocked) {
                    item(key = "banner_network_blocked") {
                        StatusBanner(
                            text = stringResource(R.string.banner_network_blocked),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                results?.let { r ->

                    if (isChecking) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ),
                                border =
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.5.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = stringResource(R.string.diag_running_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.diag_running_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Button(
                                        onClick = { showAllChecks = !showAllChecks },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ),
                                    ) {
                                        Text(
                                            text =
                                                if (showAllChecks) {
                                                    stringResource(R.string.diag_btn_hide_details)
                                                } else {
                                                    stringResource(R.string.diag_btn_show_details)
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!hasFailed) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = TelGreen.copy(alpha = 0.15f),
                                    ),
                                border =
                                    BorderStroke(
                                        1.dp,
                                        TelGreen.copy(alpha = 0.4f),
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = TelGreen,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.diag_all_good_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TelGreen,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.diag_all_good_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Button(
                                        onClick = { showAllChecks = !showAllChecks },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = TelGreen,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ),
                                    ) {
                                        Text(
                                            text =
                                                if (showAllChecks) {
                                                    stringResource(R.string.diag_btn_hide_details)
                                                } else {
                                                    stringResource(R.string.diag_btn_show_details)
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                border =
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = null,
                                            tint = TelRed,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.diag_some_failed_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TelRed,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.diag_some_failed_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Button(
                                        onClick = { showAllChecks = !showAllChecks },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = TelGreen,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ),
                                    ) {
                                        Text(
                                            text =
                                                if (showAllChecks) {
                                                    stringResource(R.string.diag_btn_hide_details)
                                                } else {
                                                    stringResource(R.string.diag_btn_show_details)
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showAllChecks) {
                        checksListCard(r.native)
                        checksListCard(r.java)
                    } else if (hasFailed && !isChecking) {
                        if (failedNative.isNotEmpty()) {
                            checksListCard(failedNative)
                        }
                        if (failedJava.isNotEmpty()) {
                            checksListCard(failedJava)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }

            item { KernelHooksTestingCard(onOpenHookTesting) }

            item { Spacer(Modifier.height(16.dp)) }

            item { DebugLoggingCard() }

            item { Spacer(Modifier.height(16.dp)) }

            item {
                // Collect button
                if (debugZipFile == null) {
                    Button(
                        onClick = {
                            exporting = true
                            scope.launch {
                                debugZipFile = exportDebugZip(cm, context, selfNeedsRestart)
                                exporting = false
                            }
                        },
                        enabled = !exporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (exporting) {
                                stringResource(R.string.btn_export_debug_running)
                            } else {
                                stringResource(R.string.btn_export_debug)
                            },
                        )
                    }
                } else {
                    // Save / Share buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { saveLauncher.launch(debugZipFile!!.name) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_save_debug))
                        }
                        Button(
                            onClick = {
                                val uri =
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        debugZipFile!!,
                                    )
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_share_debug))
                        }
                    }
                }
            }

            item {
                val bottomNavPadding =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(bottomNavPadding + 100.dp))
            }
        }
    }
}

@Composable
private fun DebugLoggingCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(VpnHideLog.enabled) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.diag_debug_logging_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.diag_debug_logging_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { newValue ->
                    enabled = newValue
                    scope.launch(Dispatchers.IO) { setDebugLoggingEnabled(context, newValue) }
                },
            )
        }
    }
}

@Composable
private fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun LazyListScope.checksListCard(checks: List<CheckResult>) {
    itemsIndexed(checks, key = { _, c -> c.name }) { index, check ->
        val isFirst = index == 0
        val isLast = index == checks.lastIndex

        val shape =
            when {
                isFirst && isLast -> RoundedCornerShape(16.dp)
                isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                else -> RectangleShape
            }

        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                CheckRow(check)
                if (!isLast) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckRow(r: CheckResult) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: (r.passed == false)

    val statusIcon =
        when {
            r.isRunning -> null
            r.isSkipped -> Icons.Default.RemoveCircle
            r.passed == true -> Icons.Default.CheckCircle
            r.passed == false -> Icons.Default.Cancel
            else -> Icons.Default.Info
        }
    val statusColor =
        when {
            r.isRunning -> MaterialTheme.colorScheme.primary
            r.isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            r.passed == true -> TelGreen
            r.passed == false -> TelRed
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    val badgeText =
        when {
            r.isRunning -> {
                stringResource(R.string.badge_running)
            }

            r.isSkipped -> {
                stringResource(R.string.badge_skipped)
            }

            else -> {
                stringResource(
                    when (r.passed) {
                        true -> R.string.badge_pass
                        false -> R.string.badge_fail
                        else -> R.string.badge_info
                    },
                )
            }
        }

    val rowBgColor =
        if (expanded) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val toastMsg = stringResource(R.string.toast_copied_to_clipboard)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(rowBgColor)
                .combinedClickable(
                    enabled = !r.isRunning,
                    onClick = {
                        if (r.detail.isNotBlank()) {
                            userExpanded = !expanded
                        }
                    },
                    onLongClick = {
                        val textToCopy =
                            if (r.detail.isNotBlank()) {
                                "${r.name}: $badgeText\n${r.detail}"
                            } else {
                                "${r.name}: $badgeText"
                            }
                        clipboardManager.setText(AnnotatedString(textToCopy))
                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    },
                ).padding(vertical = 10.dp, horizontal = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (r.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                statusIcon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = r.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = badgeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = statusColor,
                )
                if (!r.isRunning && r.detail.isNotBlank()) {
                    Icon(
                        imageVector =
                            if (expanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (expanded && r.detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            val detailBgColor =
                when {
                    r.isSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    r.passed == true -> TelGreen.copy(alpha = 0.08f)
                    r.passed == false -> TelRed.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            val detailTextColor =
                when {
                    r.isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    r.passed == true -> TelGreen
                    r.passed == false -> TelRed
                    else -> MaterialTheme.colorScheme.onSurface
                }
            Surface(
                color = detailBgColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = r.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = detailTextColor.copy(alpha = 0.9f),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

// ==========================================================================
//  Check runner — runs directly in the main process
// ==========================================================================

internal fun getPlaceholderResults(
    context: android.content.Context,
    hookMask: UInt = 0xFFFFFFFFu,
): CheckResults {
    val res = context.resources
    val nativeNames =
        listOf(
            R.string.check_ioctl_flags,
            R.string.check_ioctl_mtu,
            R.string.check_ioctl_conf,
            R.string.check_getifaddrs,
            R.string.check_netlink_getlink,
            R.string.check_netlink_getroute,
            R.string.check_netlink_anonymous_route,
            R.string.check_proc_route,
            R.string.check_proc_ipv6_route,
            R.string.check_proc_if_inet6,
            R.string.check_proc_tcp,
            R.string.check_proc_tcp6,
            R.string.check_proc_udp,
            R.string.check_proc_udp6,
            R.string.check_proc_dev,
            R.string.check_proc_fib_trie,
            R.string.check_bpf_iface_map,
            R.string.check_sys_class_net,
            R.string.check_net_iface_enum,
            R.string.check_proc_route_java,
            R.string.check_getsockopt_bind,
            R.string.check_inet_diag,
            R.string.check_getsockname_spoof,
            R.string.check_netlink_getrule,
            R.string.check_tcp_mss,
            R.string.check_udp_pmtu,
            R.string.check_netlink_getneigh,
            R.string.check_proc_sys_net_conf,
            R.string.check_udp_queue_pressure,
            R.string.check_gso_asymmetry,
            R.string.check_ipv6_link_local_bruteforce,
            R.string.check_uid_route_rules_leak,
            R.string.check_tcp_info_mss,
            R.string.check_qdisc_by_ifindex,
            R.string.check_timestamping_hw,
            R.string.check_rtm_getlink_trim_oracle,
            R.string.check_traffic_stats,
        )
    val javaNames =
        listOf(
            R.string.check_active_capabilities,
            R.string.check_active_properties,
            R.string.check_all_networks_vpn,
            R.string.check_link_properties_dns,
            R.string.check_network_callback,
            R.string.check_vpn_callback_suppression,
            R.string.check_get_network_for_type,
        )
    return CheckResults(
        native =
            nativeNames.map { rid ->
                if (isCheckSkipped(rid, hookMask)) {
                    CheckResult(res.getString(rid), true, "", isSkipped = true)
                } else {
                    CheckResult(res.getString(rid), null, "", isRunning = true)
                }
            },
        java = javaNames.map { CheckResult(res.getString(it), null, "", isRunning = true) },
    )
}

internal suspend fun runAllChecks(
    cm: ConnectivityManager,
    context: android.content.Context,
    hookMask: UInt = 0xFFFFFFFFu,
    onResult: ((CheckResult, isJava: Boolean) -> Unit)? = null,
): CheckResults =
    coroutineScope {
        VpnHideLog.i(TAG, "========================================")
        VpnHideLog.i(TAG, "=== VPNHide — starting all checks (parallel) ===")
        VpnHideLog.i(TAG, "========================================")

        val res = context.resources

        fun skipOrRun(
            resId: Int,
            block: () -> CheckOutput,
        ): suspend () -> CheckResult =
            {
                val name = res.getString(resId)
                if (isCheckSkipped(resId, hookMask)) {
                    CheckResult(name, true, "", isSkipped = true)
                } else {
                    nativeCheck(name, block)
                }
            }

        fun skipOrRunFn(
            resId: Int,
            fn: (String) -> CheckResult,
        ): suspend () -> CheckResult =
            {
                val name = res.getString(resId)
                if (isCheckSkipped(resId, hookMask)) {
                    CheckResult(name, true, "", isSkipped = true)
                } else {
                    fn(name)
                }
            }

        val nativeDefs =
            listOf<suspend () -> CheckResult>(
                skipOrRun(R.string.check_ioctl_flags) { checkIoctlSiocgifflags() },
                skipOrRun(R.string.check_ioctl_mtu) { checkIoctlSiocgifmtu() },
                skipOrRun(R.string.check_ioctl_conf) { checkIoctlSiocgifconf() },
                skipOrRun(R.string.check_getifaddrs) { checkGetifaddrs() },
                skipOrRun(R.string.check_netlink_getlink) { checkNetlinkGetlink() },
                skipOrRun(R.string.check_netlink_getroute) { checkNetlinkGetroute() },
                skipOrRun(R.string.check_netlink_anonymous_route) { checkNetlinkAnonymousRoute() },
                skipOrRun(R.string.check_proc_route) { checkProcNetRoute() },
                skipOrRun(R.string.check_proc_ipv6_route) { checkProcNetIpv6Route() },
                skipOrRun(R.string.check_proc_if_inet6) { checkProcNetIfInet6() },
                skipOrRun(R.string.check_proc_tcp) { checkProcNetTcp() },
                skipOrRun(R.string.check_proc_tcp6) { checkProcNetTcp6() },
                skipOrRun(R.string.check_proc_udp) { checkProcNetUdp() },
                skipOrRun(R.string.check_proc_udp6) { checkProcNetUdp6() },
                skipOrRun(R.string.check_proc_dev) { checkProcNetDev() },
                skipOrRun(R.string.check_proc_fib_trie) { checkProcNetFibTrie() },
                skipOrRun(R.string.check_bpf_iface_map) { checkBpfIfaceMap() },
                skipOrRun(R.string.check_sys_class_net) { checkSysClassNet() },
                skipOrRunFn(R.string.check_net_iface_enum) { checkNetworkInterfaceEnum(it) },
                skipOrRunFn(R.string.check_proc_route_java) { checkProcNetRouteJava(it) },
                skipOrRun(R.string.check_getsockopt_bind) { checkGetsockoptBind() },
                skipOrRun(R.string.check_inet_diag) { checkInetDiag() },
                skipOrRun(R.string.check_getsockname_spoof) { checkGetsocknameSpoof() },
                skipOrRun(R.string.check_netlink_getrule) { checkNetlinkGetrule() },
                skipOrRun(R.string.check_tcp_mss) { checkTcpMss() },
                skipOrRun(R.string.check_udp_pmtu) { checkUdpPmtu() },
                skipOrRun(R.string.check_netlink_getneigh) { checkNetlinkGetneigh() },
                skipOrRun(R.string.check_proc_sys_net_conf) { checkProcSysNetConf() },
                skipOrRun(R.string.check_udp_queue_pressure) { checkUdpQueuePressure() },
                skipOrRun(R.string.check_gso_asymmetry) { checkGsoAsymmetry() },
                skipOrRun(R.string.check_ipv6_link_local_bruteforce) { checkIpv6LinkLocalBruteforce() },
                skipOrRun(R.string.check_uid_route_rules_leak) { checkUidRouteRulesLeak() },
                skipOrRun(R.string.check_tcp_info_mss) { checkTcpInfoMss() },
                skipOrRun(R.string.check_qdisc_by_ifindex) { checkQdiscByIfindex() },
                skipOrRun(R.string.check_timestamping_hw) { checkTimestampingHw() },
                skipOrRun(R.string.check_rtm_getlink_trim_oracle) { checkRtmGetlinkTrimOracle() },
                skipOrRunFn(R.string.check_traffic_stats) { checkTrafficStatsDiscrepancy(cm, context, it) },
            )

        val javaDefs =
            listOf<suspend () -> CheckResult>(
                { checkActiveNetworkCapabilities(cm, res.getString(R.string.check_active_capabilities)) },
                { checkActiveLinkProperties(cm, res.getString(R.string.check_active_properties)) },
                { checkAllNetworksVpn(cm, res.getString(R.string.check_all_networks_vpn)) },
                { checkLinkPropertiesDns(cm, res.getString(R.string.check_link_properties_dns)) },
                { checkNetworkCallback(cm, res.getString(R.string.check_network_callback)) },
                { checkVpnCallbackSuppression(cm, res.getString(R.string.check_vpn_callback_suppression)) },
                { checkGetNetworkForType(cm, res.getString(R.string.check_get_network_for_type)) },
            )

        val nativeDeferred =
            nativeDefs.map { def ->
                async(Dispatchers.IO) {
                    val r = def()
                    onResult?.invoke(r, false)
                    r
                }
            }

        val javaDeferred =
            javaDefs.map { def ->
                async(Dispatchers.IO) {
                    val r = def()
                    onResult?.invoke(r, true)
                    r
                }
            }

        val native = nativeDeferred.awaitAll()
        val java = javaDeferred.awaitAll()

        val all = native + java
        val scored = all.filter { it.passed != null }
        val passed = scored.count { it.passed == true }
        VpnHideLog.i(TAG, "=== SUMMARY: $passed/${scored.size} passed ===")

        CheckResults(native = native, java = java)
    }

private fun nativeCheck(
    name: String,
    block: () -> CheckOutput,
): CheckResult =
    try {
        val out = block()
        val passed =
            when (out.status) {
                CheckStatus.PASS -> true
                CheckStatus.NETWORK_BLOCKED -> null
                CheckStatus.FAIL -> false
            }
        VpnHideLog.i(TAG, "[$name] ${out.status}: ${out.detail}")
        CheckResult(name, passed, out.detail)
    } catch (e: Exception) {
        val detail = e.message ?: e.javaClass.simpleName
        VpnHideLog.e(TAG, "[$name] $detail", e)
        CheckResult(name, false, detail)
    }

// ==========================================================================
//  Java API checks
// ==========================================================================

private fun checkActiveNetworkCapabilities(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    val net = cm.activeNetwork ?: return CheckResult(name, true, "no active network")
    val caps = cm.getNetworkCapabilities(net) ?: return CheckResult(name, true, "no capabilities")

    val hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    // TransportInfo
    val transportInfo = caps.transportInfo
    val hasVpnTransportInfo = transportInfo?.javaClass?.name?.contains("VpnTransportInfo") == true

    // Physical transports presence
    val physicalTransports = mutableListOf<String>()
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) physicalTransports.add("WIFI")
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        physicalTransports.add("CELLULAR")
    }
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        physicalTransports.add("ETHERNET")
    }
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
        physicalTransports.add("BLUETOOTH")
    }

    // Signal strength
    val ss = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.signalStrength else -1
    val isUnspecifiedSignal = ss == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED
    val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val suspiciousSignal = hasWifi && isUnspecifiedSignal

    // Bandwidth
    val down = caps.linkDownstreamBandwidthKbps
    val up = caps.linkUpstreamBandwidthKbps
    val suspiciousHighBandwidth = !hasVpn && (down > 10_000_000 || up > 10_000_000)
    val suspiciousZeroBandwidth = !hasVpn && (down == 0 || up == 0)

    val passed =
        !hasVpn &&
            notVpn &&
            !hasVpnTransportInfo &&
            physicalTransports.isNotEmpty() &&
            !suspiciousSignal &&
            !suspiciousHighBandwidth &&
            !suspiciousZeroBandwidth

    val detail =
        buildString {
            append("hasTransport(VPN)=$hasVpn")
            append(", NET_CAPABILITY_NOT_VPN=$notVpn")
            append(", transportInfo=${transportInfo?.javaClass?.simpleName ?: "null"}")
            append(", physical=[${physicalTransports.joinToString()}]")
            val sigStr = if (isUnspecifiedSignal) "unspec" else ss.toString()
            append(", signalStrength=$sigStr")
            append(", bandwidth=${down}D/${up}U Kbps")
            if (!passed) {
                val issues = mutableListOf<String>()
                if (hasVpn) issues.add("has TRANSPORT_VPN")
                if (!notVpn) issues.add("missing NOT_VPN")
                if (hasVpnTransportInfo) issues.add("has VpnTransportInfo")
                if (physicalTransports.isEmpty()) issues.add("no physical transports")
                if (suspiciousSignal) issues.add("suspicious unspecified signal")
                if (suspiciousHighBandwidth || suspiciousZeroBandwidth) {
                    issues.add("suspicious bandwidth values")
                }
                append(" (issues: ${issues.joinToString(", ")})")
            }
        }
    return CheckResult(name, passed, detail)
}

private fun checkNetworkInterfaceEnum(name: String): CheckResult {
    return try {
        val ifaces =
            NetworkInterface.getNetworkInterfaces()
                ?: return CheckResult(name, true, "returned null")
        val allNames = mutableListOf<String>()
        val vpnNames = mutableListOf<String>()
        for (iface in ifaces) {
            allNames.add(iface.name)
            if (IfaceLists.isVpnIface(iface.name)) vpnNames.add(iface.name)
        }
        val detail =
            if (vpnNames.isEmpty()) {
                "${allNames.size} ifaces [${allNames.joinToString()}], no VPN"
            } else {
                "VPN [${vpnNames.joinToString()}] in [${allNames.joinToString()}]"
            }
        CheckResult(name, vpnNames.isEmpty(), detail)
    } catch (e: Exception) {
        CheckResult(name, false, "${e.message}")
    }
}

@Suppress("DEPRECATION")
private fun checkAllNetworksVpn(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    val networks = cm.allNetworks
    if (networks.isEmpty()) return CheckResult(name, true, "no networks")
    val vpnNetworks =
        networks.filter { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ==
                true
        }
    val detail =
        if (vpnNetworks.isEmpty()) {
            "${networks.size} networks, none have TRANSPORT_VPN"
        } else {
            "${vpnNetworks.size} network(s) with TRANSPORT_VPN"
        }
    return CheckResult(name, vpnNetworks.isEmpty(), detail)
}

private fun checkActiveLinkProperties(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    val net = cm.activeNetwork ?: return CheckResult(name, true, "no active network")
    val lp = cm.getLinkProperties(net) ?: return CheckResult(name, true, "no link properties")

    val ifname = lp.interfaceName ?: "(null)"
    val isVpnIface = IfaceLists.isVpnIface(ifname)

    val routes = lp.routes
    val vpnRoutes =
        routes.filter { route ->
            val iface = route.`interface` ?: return@filter false
            IfaceLists.isVpnIface(iface)
        }

    val dnsCount = lp.dnsServers.size
    val hasDefaultRoute = routes.any { route -> route.destination?.prefixLength == 0 }

    val passed =
        !isVpnIface &&
            vpnRoutes.isEmpty() &&
            lp.interfaceName != null &&
            dnsCount > 0 &&
            hasDefaultRoute

    val detail =
        buildString {
            append("ifname=$ifname")
            append(", routes=${routes.size} (VPN routes=${vpnRoutes.size})")
            append(", dnsCount=$dnsCount")
            append(", hasDefaultRoute=$hasDefaultRoute")
            if (!passed) {
                val issues = mutableListOf<String>()
                if (isVpnIface) issues.add("VPN interface detected")
                if (vpnRoutes.isNotEmpty()) issues.add("${vpnRoutes.size} VPN routes")
                if (lp.interfaceName == null) issues.add("missing interface name")
                if (dnsCount == 0) issues.add("missing DNS servers")
                if (!hasDefaultRoute) issues.add("missing default route")
                append(" (issues: ${issues.joinToString(", ")})")
            }
        }
    return CheckResult(name, passed, detail)
}

private fun checkLinkPropertiesDns(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    val networks = cm.allNetworks
    if (networks.isEmpty()) return CheckResult(name, true, "no networks")

    val leaked = mutableListOf<String>()
    for (net in networks) {
        val lp = cm.getLinkProperties(net) ?: continue
        val ifname = lp.interfaceName
        val dns = lp.dnsServers.map { it.hostAddress ?: "?" }

        if (dns.isNotEmpty()) {
            if (ifname == null) {
                // Anonymous interface (nulled out name but leaking DNS)
                val netId = net.toString()
                leaked.add("anonymous (ID $netId): dns=[${dns.joinToString()}]")
            } else if (IfaceLists.isVpnIface(ifname)) {
                // VPN interface is fully visible
                leaked.add("$ifname: dns=[${dns.joinToString()}]")
            }
        }
    }

    val detail =
        if (leaked.isEmpty()) {
            "no DNS servers exposed via anonymous or VPN interfaces"
        } else {
            "leaked DNS detected:\n" + leaked.joinToString("\n") { "  $it" }
        }
    return CheckResult(name, leaked.isEmpty(), detail)
}

private fun checkProcNetRouteJava(name: String): CheckResult =
    try {
        val allLines = mutableListOf<String>()
        val vpnLines = mutableListOf<String>()
        BufferedReader(InputStreamReader(java.io.FileInputStream("/proc/net/route"))).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                allLines.add(line!!)
                // /proc/net/route is whitespace-separated; check
                // each token instead of just startsWith on the raw
                // line so we don't match e.g. an IP-as-hex by chance.
                if (line!!.split(Regex("\\s+")).any(IfaceLists::isVpnIface)) {
                    vpnLines.add(line!!.take(60))
                }
            }
        }
        val detail =
            if (vpnLines.isEmpty()) {
                "${allLines.size} lines, no VPN entries"
            } else {
                "${vpnLines.size} VPN lines:\n${vpnLines.joinToString("\n") { "  $it" }}"
            }
        CheckResult(name, vpnLines.isEmpty(), detail)
    } catch (e: Exception) {
        val msg = e.message ?: ""
        if (msg.contains("EACCES") || msg.contains("Permission denied")) {
            CheckResult(name, true, "access denied by SELinux")
        } else {
            CheckResult(name, false, "${e.message}")
        }
    }

private fun checkNetworkCallback(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    val latch = java.util.concurrent.CountDownLatch(1)
    var hasVpnTransport = false
    var hasVpnInterface = false
    var receivedCallback = false
    var ifname = "none"
    var netIdDiscrepancy = false
    var activeNetId = -1
    var callbackNetId = -1

    val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: NetworkCapabilities,
            ) {
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    activeNetId = activeNet.hashCode()
                    callbackNetId = network.hashCode()
                    if (activeNet != network) {
                        netIdDiscrepancy = true
                    }
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    hasVpnTransport = true
                }
            }

            override fun onLinkPropertiesChanged(
                network: android.net.Network,
                lp: android.net.LinkProperties,
            ) {
                val iface = lp.interfaceName
                if (iface != null) {
                    ifname = iface
                    if (IfaceLists.isVpnIface(iface)) {
                        hasVpnInterface = true
                    }
                }
                receivedCallback = true
                latch.countDown()
            }
        }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request =
                android.net.NetworkRequest
                    .Builder()
                    .build()
            cm.registerNetworkCallback(request, callback)
        }
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        return CheckResult(name, false, "failed to register callback: ${e.message}")
    } finally {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    if (!receivedCallback) {
        return CheckResult(name, true, "no callback received (timeout)")
    }

    val failed = hasVpnTransport || hasVpnInterface || netIdDiscrepancy
    val detail =
        buildString {
            append("hasTransport(VPN)=$hasVpnTransport")
            append(", ifname=$ifname")
            append(", discrepancy=$netIdDiscrepancy")
            if (netIdDiscrepancy) {
                append(" (leaked netId discrepancy: active=$activeNetId, callback=$callbackNetId)")
            } else if (failed) {
                append(" (VPN leaked via push callback!)")
            } else {
                append(" (clean)")
            }
        }

    return CheckResult(name, !failed, detail)
}

private fun checkVpnCallbackSuppression(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    val latch = java.util.concurrent.CountDownLatch(1)
    var callbackTriggered = false
    val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                callbackTriggered = true
                latch.countDown()
            }
        }
    try {
        val request =
            android.net.NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        cm.registerNetworkCallback(request, callback)
        // Wait 300ms to see if it triggers (our hook blocks it completely)
        latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        return CheckResult(name, false, "failed to register callback: ${e.message}")
    } finally {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }
    val passed = !callbackTriggered
    val detail =
        if (passed) {
            "VPN network callback suppressed successfully"
        } else {
            "leak detected: VPN network callback triggered onAvailable!"
        }
    return CheckResult(name, passed, detail)
}

private fun checkGetNetworkForType(
    cm: ConnectivityManager,
    name: String,
): CheckResult {
    try {
        // ConnectivityManager.TYPE_VPN is 17
        val method =
            ConnectivityManager::class.java.getMethod(
                "getNetworkForType",
                java.lang.Integer.TYPE,
            )
        val net = method.invoke(cm, 17) as? android.net.Network

        val passed = net == null
        val detail =
            if (passed) {
                "getNetworkForType(TYPE_VPN) returned null"
            } else {
                "leaked Network via getNetworkForType(TYPE_VPN): $net"
            }
        return CheckResult(name, passed, detail)
    } catch (e: Exception) {
        return CheckResult(name, true, "not supported or error: ${e.message}")
    }
}

/**
 * Read per-interface byte counters for UP interfaces only.
 *
 * Priority order:
 *   1. `su` (root): full picture including hidden VPN interfaces like tun0;
 *      UP/DOWN state from `ip link show up`.
 *   2. Rust native: bypasses Java SELinux restriction; UP state via NetworkInterface.
 *   3. Java file read: last resort, likely blocked on API 29+.
 *
 * Returns (stats, hasSuAccess) where stats contains ONLY UP interfaces.
 */
private fun readUpIfaceStats(): Pair<Map<String, Pair<Long, Long>>, Boolean> {
    // ── 1. SU path — root shell sees all ifaces, real UP state ──
    try {
        val cmd = "cat /proc/net/dev; echo ---SU_SEP---; ip link show up"
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (exited) {
            val output = proc.inputStream.bufferedReader().readText()
            val sepIdx = output.indexOf("---SU_SEP---")
            if (sepIdx >= 0) {
                val devText = output.substring(0, sepIdx)
                val linkText = output.substring(sepIdx + "---SU_SEP---\n".length)

                val allBytes = mutableMapOf<String, Pair<Long, Long>>()
                for (line in devText.lines().drop(2)) {
                    val t = line.trim()
                    val c = t.indexOf(':')
                    if (c < 0) continue
                    val n = t.substring(0, c).trim()
                    if (n.isEmpty()) continue
                    val f = t.substring(c + 1).trim().split("\\s+".toRegex())
                    allBytes[n] =
                        Pair(
                            f.getOrNull(8)?.toLongOrNull() ?: 0L,
                            f.getOrNull(0)?.toLongOrNull() ?: 0L,
                        )
                }

                val upNames = mutableSetOf<String>()
                Regex("^\\d+:\\s+(\\S+?)(?:@\\S+)?:", RegexOption.MULTILINE)
                    .findAll(linkText)
                    .forEach { upNames.add(it.groupValues[1]) }

                if (allBytes.isNotEmpty()) {
                    return Pair(allBytes.filter { (name, _) -> name in upNames }, true)
                }
            }
        }
        proc.destroyForcibly()
    } catch (_: Exception) {
    }

    // ── 2. Rust native path — may still be blocked by kernel SELinux ──
    try {
        val csv =
            dev.soranerai.vpnhidenext.checks
                .parseProcNetDevCsv()
        if (csv.isNotBlank()) {
            val result = mutableMapOf<String, Pair<Long, Long>>()
            for (line in csv.trim().split('\n')) {
                if (line.isBlank()) continue
                val p = line.split(',')
                if (p.size < 3) continue
                val nm = p[0]
                val isUp =
                    try {
                        java.net.NetworkInterface
                            .getByName(nm)
                            ?.isUp == true
                    } catch (_: Exception) {
                        false
                    }
                if (isUp) result[nm] = Pair(p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
            }
            if (result.isNotEmpty()) return Pair(result, false)
        }
    } catch (_: Exception) {
    }

    // ── 3. Java file fallback ──
    val result = mutableMapOf<String, Pair<Long, Long>>()
    try {
        java.io.File("/proc/net/dev").forEachLine { line ->
            val t = line.trim()
            val c = t.indexOf(':')
            if (c < 0) return@forEachLine
            val nm = t.substring(0, c).trim()
            val isUp =
                try {
                    java.net.NetworkInterface
                        .getByName(nm)
                        ?.isUp == true
                } catch (_: Exception) {
                    false
                }
            if (!isUp) return@forEachLine
            val f = t.substring(c + 1).trim().split("\\s+".toRegex())
            result[nm] = Pair(f.getOrNull(8)?.toLongOrNull() ?: 0L, f.getOrNull(0)?.toLongOrNull() ?: 0L)
        }
    } catch (_: Exception) {
    }
    return Pair(result, false)
}

private fun checkTrafficStatsDiscrepancy(
    cm: ConnectivityManager,
    context: android.content.Context,
    name: String,
): CheckResult {
    try {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return CheckResult(
                name,
                true,
                "TrafficStats per-interface queries require Android 15+ (API 35+)",
            )
        }

        // ── Ground truth: UP interfaces from /proc/net/dev (raw, not hooked) ──
        // readUpIfaceStats() already filters to UP-only, so no further isUp checks needed.
        // hasSuAccess=true means tun0 is visible (hidden from Java but not from root).
        val (rawStats, hasSuAccess) = readUpIfaceStats()
        if (rawStats.isEmpty()) {
            return CheckResult(name, true, "raw iface stats unavailable (no su, SELinux blocked)")
        }

        // ── Get the active cover interface from the kernel module ──
        var coverIfaceName = ""
        val (ctrlExit, ctrlOut) = suExec("cat /dev/vpnhide_ctrl 2>/dev/null")
        if (ctrlExit == 0) {
            val match = Regex("cover_iface:\\s*(\\S+)").find(ctrlOut)
            if (match != null) {
                coverIfaceName = match.groupValues[1]
            }
        }

        // ── Enumerate visible interfaces (hook filters out tun0 etc.) ──
        // Only count UP interfaces — a wlan0 that just disconnected should not
        // participate in the visible-sum.
        var visibleTx = 0L
        var visibleRx = 0L
        val names = mutableListOf<String>()
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        if (ifaces != null) {
            for (iface in ifaces) {
                val upStatus = if (iface.isUp) "up" else "down"
                val t = android.net.TrafficStats.getTxBytes(iface.name)
                val r = android.net.TrafficStats.getRxBytes(iface.name)
                names.add("${iface.name}[$upStatus](tx=${t / 1024}K)")
                if (!iface.isUp) continue
                if (t > 0) visibleTx += t
                if (r > 0) visibleRx += r
            }
        }

        // ── Detect VPN traffic — rawStats already contains only UP interfaces ──
        val vpnPrefixes = listOf("tun", "wg", "ppp", "tap", "ipsec")
        var rawVpnTx = 0L
        var rawVpnRx = 0L
        val vpnIfaceNames = mutableListOf<String>()
        for ((ifaceName, stats) in rawStats) {
            if (vpnPrefixes.any { ifaceName.startsWith(it) }) {
                rawVpnTx += stats.first
                rawVpnRx += stats.second
                vpnIfaceNames.add("$ifaceName(tx=${stats.first / 1024}K)")
            }
        }

        // ── Raw system total: sum of all UP ifaces from rawStats (includes VPN) ──
        // rawStats already contains only UP interfaces, so no further filtering needed.
        val rawSystemTx = rawStats.values.sumOf { it.first }
        val rawSystemRx = rawStats.values.sumOf { it.second }

        // ── Primary check: raw system (UP, with VPN) vs BPF visible (UP, laundered) ──
        val txDiff = rawSystemTx - visibleTx
        val rxDiff = rawSystemRx - visibleRx
        val threshold = 5 * 1024 * 1024L // 5 MB

        // If there's no VPN traffic, discrepancies are natural (not from laundering failure) -> ignore
        val hasVpnTraffic = rawVpnTx > 0L

        val txSuspicious =
            hasVpnTraffic &&
                txDiff > threshold &&
                (rawSystemTx.toDouble() / visibleTx.coerceAtLeast(1L).toDouble() > 1.5)
        val rxSuspicious =
            hasVpnTraffic &&
                rxDiff > threshold &&
                (rawSystemRx.toDouble() / visibleRx.coerceAtLeast(1L).toDouble() > 1.5)

        // ── Secondary check: verify VPN traffic was laundered into cover iface ──
        val vpnTrafficSignificant = rawVpnTx > threshold
        var launderingOk = true
        var launderDetail: String
        val hasCoverIface = coverIfaceName.isNotEmpty() && coverIfaceName != "none"
        if (vpnTrafficSignificant) {
            if (hasCoverIface) {
                val rawCoverTx = rawStats[coverIfaceName]?.first ?: 0L
                val bpfCoverTx = android.net.TrafficStats.getTxBytes(coverIfaceName)
                val expectedCoverTx = rawCoverTx + rawVpnTx
                launderingOk = bpfCoverTx >= (expectedCoverTx * 0.7).toLong()
                launderDetail =
                    if (launderingOk) {
                        "Laundering OK: $coverIfaceName BPF=${bpfCoverTx / 1024}K " +
                            "≈ raw ${rawCoverTx / 1024}K + vpn ${rawVpnTx / 1024}K"
                    } else {
                        "Laundering BROKEN: $coverIfaceName BPF=${bpfCoverTx / 1024}K " +
                            "but expected ~${expectedCoverTx / 1024}K " +
                            "(raw ${rawCoverTx / 1024}K + vpn ${rawVpnTx / 1024}K)"
                    }
            } else {
                launderingOk = false
                launderDetail = "Laundering UNKNOWN: VPN traffic active, but no cover interface set by kmod yet"
            }
        } else if (rawVpnTx in 1..<threshold) {
            launderDetail = "Low VPN traffic (${rawVpnTx / 1024}K raw), skipping launder check"
        } else {
            launderDetail =
                if (hasSuAccess) {
                    "No UP VPN iface seen by root — VPN may be off"
                } else {
                    "No VPN traffic visible (no SU access, hidden ifaces not enumerable)"
                }
        }

        val passed = !txSuspicious && !rxSuspicious && launderingOk

        val detail =
            buildString {
                val src = if (hasSuAccess) "su/root" else "no-su fallback"
                val bpfTotal = android.net.TrafficStats.getTotalTxBytes()
                append("[$src] BPF Total: TX=${bpfTotal / 1024}K. ")
                append("Raw UP sum: TX=${rawSystemTx / 1024}K. ")
                append("Visible (BPF, UP): TX=${visibleTx / 1024}K. ")
                append("Diff: TX=${txDiff / 1024}K, RX=${rxDiff / 1024}K. ")
                append("Ifaces: [${names.joinToString()}]. ")
                if (vpnIfaceNames.isNotEmpty()) {
                    append("Raw VPN: [${vpnIfaceNames.joinToString()}]. ")
                }
                append(launderDetail)
            }

        return CheckResult(
            name,
            passed,
            if (passed) "$detail (clean)" else "$detail (anomaly detected!)",
        )
    } catch (e: Exception) {
        return CheckResult(name, false, "error checking traffic stats: ${e.message}")
    }
}

// ==========================================================================
//  Debug log export
// ==========================================================================

private suspend fun exportDebugZip(
    cm: ConnectivityManager,
    context: android.content.Context,
    selfNeedsRestart: Boolean,
): File? =
    withContext(Dispatchers.IO) {
        // Force-enable debug logging across all four sinks (app, system_server,
        // kmod) while the capture runs so the dump contains VpnHide-
        // tagged lines + verbose dmesg even when the user's persistent toggle
        // is OFF (the default). We restore to whatever the SharedPreferences
        // say at the end — if the user happens to flip the UI toggle mid-
        // capture, we honor their final choice instead of blindly rolling
        // back. applyDebugLoggingRuntime drives all four sinks uniformly, so
        // there's no ad-hoc /proc/vpnhide_debug flip here anymore.
        val loggingWasForced = !VpnHideLog.enabled
        if (loggingWasForced) applyDebugLoggingRuntime(true)
        try {
            // 1. Clear dmesg so we only capture fresh output from the
            //    kmod hooks fired by runAllChecks below.
            suExec("dmesg -c > /dev/null 2>&1")

            // 2. Run all diagnostic checks (this triggers kmod hooks)
            val checkResults = runAllChecks(cm, context)

            // 3. Capture dmesg right after checks
            val (_, dmesg) = suExec("dmesg 2>/dev/null")

            // 4. Collect additional info
            val files = mutableMapOf<String, String>()

            // dmesg (vpnhide lines only + full for context)
            val vpnhideLines = dmesg.lines().filter { it.contains("vpnhide") }
            files["dmesg_vpnhide.txt"] = vpnhideLines.joinToString("\n")
            files["dmesg_full.txt"] = dmesg

            // Diagnostics results
            val diagText =
                buildString {
                    val scored = checkResults.all.filter { it.passed != null }
                    val passed = scored.count { it.passed == true }
                    appendLine("=== Diagnostics: $passed/${scored.size} passed ===")
                    appendLine()
                    appendLine("--- Native level ---")
                    for (c in checkResults.native) {
                        val badge =
                            when (c.passed) {
                                true -> "PASS"
                                false -> "FAIL"
                                null -> "INFO"
                            }
                        appendLine("[$badge] ${c.name}")
                        appendLine("  ${c.detail}")
                    }
                    appendLine()
                    appendLine("--- Java API level ---")
                    for (c in checkResults.java) {
                        val badge =
                            when (c.passed) {
                                true -> "PASS"
                                false -> "FAIL"
                                null -> "INFO"
                            }
                        appendLine("[$badge] ${c.name}")
                        appendLine("  ${c.detail}")
                    }
                }
            files["diagnostics.txt"] = diagText

            // Device info
            val (_, kernelVersion) = suExec("uname -r 2>/dev/null")
            val (_, procVersion) = suExec("cat /proc/version 2>/dev/null")
            val (_, selinuxMode) = suExec("getenforce 2>/dev/null")
            val deviceInfo =
                buildString {
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Kernel: ${kernelVersion.trim()}")
                    appendLine("Kernel full: ${procVersion.trim()}")
                    appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("SELinux: ${selinuxMode.trim()}")
                    appendLine("App package: ${context.packageName}")
                    try {
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        appendLine("App version: ${pInfo.versionName}")
                    } catch (_: Exception) {
                    }
                    appendLine("selfNeedsRestart: $selfNeedsRestart")
                    appendLine()
                    appendLine("=== Root manager ===")
                    val (_, magiskVer) = suExec("magisk -V 2>/dev/null")
                    val (_, magiskVerName) = suExec("magisk -v 2>/dev/null")
                    if (magiskVer.isNotBlank()) {
                        appendLine("Magisk: ${magiskVerName.trim()} (${magiskVer.trim()})")
                    }
                    val (_, ksuVer) = suExec("cat /data/adb/ksu/version 2>/dev/null")
                    if (ksuVer.isNotBlank()) {
                        appendLine("KernelSU: ${ksuVer.trim()}")
                    }
                    val (exitKsuNext, ksuNextVer) = suExec("ksud --version 2>/dev/null")
                    if (exitKsuNext == 0 && ksuNextVer.isNotBlank()) {
                        appendLine("KernelSU-Next: ${ksuNextVer.trim()}")
                    }
                    if (magiskVer.isBlank() &&
                        ksuVer.isBlank() &&
                        (exitKsuNext != 0 || ksuNextVer.isBlank())
                    ) {
                        appendLine("(unknown root manager)")
                    }
                }
            files["device_info.txt"] = deviceInfo

            // Module info
            val moduleInfo =
                buildString {
                    appendLine("=== Kernel module (kmod) ===")
                    val (_, kmodProp) =
                        suExec("cat /data/adb/modules/vpnhide_kmod/module.prop 2>/dev/null")
                    appendLine(kmodProp.ifEmpty { "Not installed" })
                    appendLine()
                    appendLine("=== kmod load_status (boot-time diagnostics) ===")
                    val (_, loadStatus) = suExec("cat $KMOD_LOAD_STATUS_FILE 2>/dev/null")
                    appendLine(
                        loadStatus.ifEmpty {
                            "(not available — module never ran post-fs-data.sh this boot)"
                        },
                    )
                    appendLine()
                    appendLine("=== Current boot_id ===")
                    val (_, curBootId) = suExec("cat /proc/sys/kernel/random/boot_id 2>/dev/null")
                    appendLine(curBootId.trim().ifEmpty { "(not available)" })
                    appendLine()
                    appendLine("=== kmod load_dmesg ===")
                    val (_, loadDmesg) = suExec("cat $KMOD_LOAD_DMESG_FILE 2>/dev/null")
                    appendLine(loadDmesg.ifEmpty { "(not captured)" })
                    appendLine()
                    appendLine("=== Registered kretprobes ===")
                    val (_, kprobes) =
                        suExec("cat /sys/kernel/debug/kprobes/list 2>/dev/null | grep vpnhide")
                    appendLine(kprobes.ifEmpty { "(not available or no vpnhide probes)" })
                    appendLine()
                    appendLine("=== Kernel symbols (hooked functions) ===")
                    val symbols =
                        listOf(
                            "dev_ioctl",
                            "dev_ifconf",
                            "rtnl_fill_ifinfo",
                            "inet6_fill_ifaddr",
                            "inet_fill_ifaddr",
                            "fib_route_seq_show",
                        )
                    for (sym in symbols) {
                        val (_, line) =
                            suExec("cat /proc/kallsyms 2>/dev/null | grep -w $sym | head -3")
                        appendLine("$sym: ${line.trim().ifEmpty { "(not found)" }}")
                    }
                    appendLine()
                    appendLine("=== LSPosed configuration ===")
                    val (_, lsposedDb) =
                        suExec(
                            "sqlite3 /data/adb/lspd/config/modules_config.db " +
                                "\"SELECT mid, module_pkg_name, enabled FROM modules WHERE module_pkg_name LIKE '%vpnhide%';\" 2>/dev/null",
                        )
                    appendLine(lsposedDb.ifEmpty { "(not available or module not in LSPosed)" })
                    val (_, lsposedScope) =
                        suExec(
                            "sqlite3 /data/adb/lspd/config/modules_config.db " +
                                "\"SELECT s.app_pkg_name FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name LIKE '%vpnhide%';\" 2>/dev/null",
                        )
                    if (lsposedScope.isNotBlank()) {
                        appendLine("Scope: ${lsposedScope.trim()}")
                    }
                }
            files["modules.txt"] = moduleInfo

            // Target UIDs
            val (_, procTargets) = suExec("cat /proc/vpnhide_targets 2>/dev/null")
            val db = AppDatabase.getInstance(context)
            val protections = db.appDao().getAllAppProtectionSync()
            val kmodTargets = protections.filter { it.kmod }
            val lsposedTargets = protections.filter { it.lsposed }
            val targetsText =
                buildString {
                    appendLine("=== /proc/vpnhide_targets (live UIDs) ===")
                    appendLine(procTargets.ifEmpty { "(empty)" })
                    appendLine()
                    appendLine("=== kmod targets (DB) ===")
                    if (kmodTargets.isEmpty()) {
                        appendLine("(empty)")
                    } else {
                        kmodTargets.forEach { app ->
                            val entry =
                                if (app.userId == 0) {
                                    app.packageName
                                } else {
                                    "${app.packageName}:${app.userId}"
                                }
                            appendLine("$entry (uid: ${app.uid})")
                        }
                    }
                    appendLine()
                    appendLine("=== lsposed targets (DB) ===")
                    if (lsposedTargets.isEmpty()) {
                        appendLine("(empty)")
                    } else {
                        lsposedTargets.forEach { app ->
                            val entry =
                                if (app.userId == 0) {
                                    app.packageName
                                } else {
                                    "${app.packageName}:${app.userId}"
                                }
                            appendLine("$entry (uid: ${app.uid})")
                        }
                    }
                }
            files["targets.txt"] = targetsText

            // Network interfaces (via su, unfiltered)
            val ifacesText =
                buildString {
                    appendLine("=== ip -d addr ===")
                    val (_, ipAddr) = suExec("ip -d addr 2>/dev/null")
                    appendLine(ipAddr.ifEmpty { "(not available)" })
                    appendLine()
                    appendLine("=== Interface operstate ===")
                    val (_, operstate) =
                        suExec(
                            "for iface in /sys/class/net/*; do " +
                                "echo \"\$(basename \$iface): \$(cat \$iface/operstate 2>/dev/null)\"; " +
                                "done",
                        )
                    appendLine(operstate.ifEmpty { "(not available)" })
                    appendLine()
                    appendLine("=== ip route show table all ===")
                    val (_, routes) = suExec("ip route show table all 2>/dev/null")
                    appendLine(routes.ifEmpty { "(not available)" })
                    appendLine()
                    appendLine("=== ip rule ===")
                    val (_, rules) = suExec("ip rule 2>/dev/null")
                    appendLine(rules.ifEmpty { "(not available)" })
                }
            files["interfaces.txt"] = ifacesText

            // /proc/net files
            val procFiles =
                listOf(
                    "route",
                    "ipv6_route",
                    "if_inet6",
                    "tcp",
                    "tcp6",
                    "udp",
                    "udp6",
                    "dev",
                )
            val procNet =
                buildString {
                    for (pf in procFiles) {
                        appendLine("=== /proc/net/$pf ===")
                        val (_, content) = suExec("cat /proc/net/$pf 2>/dev/null")
                        appendLine(content.ifEmpty { "(not available)" })
                        appendLine()
                    }
                }
            files["proc_net.txt"] = procNet

            // Logcat (app-level logs)
            // Read logcat directly (no su) — app can read its own logs
            val logcat =
                try {
                    val proc =
                        Runtime
                            .getRuntime()
                            .exec(
                                arrayOf(
                                    "logcat",
                                    "-d",
                                    "-s",
                                    "VPNHideTest:*",
                                    "VpnHide:*",
                                    "VpnHide-Dashboard:*",
                                ),
                            )
                    val output = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    proc.destroy()
                    output
                } catch (e: Exception) {
                    "(logcat failed: ${e.message})"
                }
            files["logcat.txt"] = logcat.ifEmpty { "(no logcat entries)" }

            // Create zip
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(context.cacheDir, "vpnhide_debug_$timestamp.zip")
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                for ((name, content) in files) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            VpnHideLog.e(TAG, "Debug export failed", e)
            null
        } finally {
            if (loggingWasForced) {
                val target = isEnabledInPrefs(context)
                if (VpnHideLog.enabled != target) applyDebugLoggingRuntime(target)
            }
        }
    }

@Composable
private fun KernelHooksTestingCard(onOpenHookTesting: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.diag_hook_isolation_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.diag_hook_isolation_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onOpenHookTesting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diag_hook_isolation_configure_btn)) }
        }
    }
}

@Composable
private fun BackupRestoreCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri != null) {
                exporting = true
                scope.launch {
                    try {
                        val jsonStr = SettingsBackupHelper.exportToString(context)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(jsonStr.toByteArray())
                        }
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.export_success),
                                Toast.LENGTH_SHORT,
                            ).show()
                    } catch (e: Exception) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.export_failed, e.message),
                                Toast.LENGTH_LONG,
                            ).show()
                    } finally {
                        exporting = false
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                importing = true
                scope.launch {
                    try {
                        val jsonStr =
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                input.bufferedReader().readText()
                            }
                                ?: throw Exception("Failed to read input stream")

                        val result = SettingsBackupHelper.importFromString(context, jsonStr)
                        if (result.isSuccess) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.import_success),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        } else {
                            val errMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.import_failed, errMsg),
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    } catch (e: Exception) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.import_failed, e.message),
                                Toast.LENGTH_LONG,
                            ).show()
                    } finally {
                        importing = false
                    }
                }
            }
        }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.backup_restore_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val timestamp =
                            java.text
                                .SimpleDateFormat(
                                    "yyyyMMdd_HHmmss",
                                    java.util.Locale.US,
                                ).format(java.util.Date())
                        exportLauncher.launch("vpnhide_backup_$timestamp.json")
                    },
                    enabled = !exporting && !importing,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp).width(110.dp),
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_export),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream"),
                        )
                    },
                    enabled = !exporting && !importing,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp).width(110.dp),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_import),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
