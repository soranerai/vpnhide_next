package dev.soranerai.vpnhidenext

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HookInfo(
    val index: Int,
    val name: String,
    val symbol: String,
    val description: String,
)

private val isRussian =
    java.util.Locale
        .getDefault()
        .language == "ru"

val ALL_HOOKS =
    listOf(
        HookInfo(
            0,
            "dev_ioctl",
            "dev_ioctl",
            if (isRussian) {
                "Перехватывает общие IOCTL сетевых интерфейсов (например, SIOCGIFFLAGS, SIOCGIFMTU) для скрытия статуса VPN."
            } else {
                "Intercepts general interface IOCTLs (e.g., SIOCGIFFLAGS, SIOCGIFMTU) to hide VPN interface states."
            },
        ),
        HookInfo(
            1,
            "sock_ioctl",
            "sock_ioctl",
            if (isRussian) {
                "Перехватывает IOCTL сокетов, такие как SIOCGIFCONF, чтобы отфильтровать VPN-интерфейсы при их перечислении."
            } else {
                "Intercepts socket-level IOCTLs like SIOCGIFCONF to filter out VPN interfaces during network enumeration."
            },
        ),
        HookInfo(
            2,
            "rtnl_fill_ifinfo",
            "rtnl_fill_ifinfo",
            if (isRussian) {
                "Фильтрует VPN-интерфейсы из ответов Netlink на дампы линков (RTM_GETLINK)."
            } else {
                "Filters VPN network interfaces out of netlink link-dump (RTM_GETLINK) responses."
            },
        ),
        HookInfo(
            3,
            "inet6_fill_ifaddr",
            "inet6_fill_ifaddr",
            if (isRussian) {
                "Фильтрует IPv6-адреса VPN из ответов Netlink на дампы адресов (RTM_GETADDR)."
            } else {
                "Filters VPN IPv6 addresses out of netlink address-dump (RTM_GETADDR) responses."
            },
        ),
        HookInfo(
            4,
            "inet_fill_ifaddr",
            "inet_fill_ifaddr",
            if (isRussian) {
                "Фильтрует IPv4-адреса VPN из ответов Netlink на дампы адресов (RTM_GETADDR)."
            } else {
                "Filters VPN IPv4 addresses out of netlink address-dump (RTM_GETADDR) responses."
            },
        ),
        HookInfo(
            5,
            "fib_route_seq_show",
            "fib_route_seq_show",
            if (isRussian) {
                "Фильтрует VPN-маршруты при чтении приложениями таблицы маршрутизации IPv4 в /proc/net/route."
            } else {
                "Filters out VPN routes when apps read the IPv4 routing table in /proc/net/route."
            },
        ),
        HookInfo(
            6,
            "ipv6_route_seq_show",
            "ipv6_route_seq_show",
            if (isRussian) {
                "Фильтрует VPN-маршруты при чтении приложениями таблицы маршрутизации IPv6 в /proc/net/ipv6_route."
            } else {
                "Filters out VPN routes when apps read the IPv6 routing table in /proc/net/ipv6_route."
            },
        ),
        HookInfo(
            7,
            "fib_dump_info",
            "fib_dump_info",
            if (isRussian) {
                "Фильтрует VPN-маршруты IPv4 во время дампов таблиц маршрутизации через Netlink."
            } else {
                "Filters out IPv4 VPN routes during netlink routing table dumps."
            },
        ),
        HookInfo(
            8,
            "fib_nl_fill_rule",
            "fib_nl_fill_rule",
            if (isRussian) {
                "Фильтрует VPN-правила маршрутизации (policy routing) из ответов Netlink на дампы правил (RTM_GETRULE)."
            } else {
                "Filters out VPN policy routing rules from netlink rule-dump (RTM_GETRULE) responses."
            },
        ),
        HookInfo(
            9,
            "rt6_fill_node",
            "rt6_fill_node",
            if (isRussian) {
                "Фильтрует VPN-маршруты IPv6 из ответов Netlink на дампы маршрутов (RTM_GETROUTE)."
            } else {
                "Filters out IPv6 VPN routes from netlink route-dump (RTM_GETROUTE) responses."
            },
        ),
        HookInfo(
            10,
            "rt_fill_info",
            "rt_fill_info",
            if (isRussian) {
                "Фильтрует VPN-маршруты IPv4 из ответов Netlink на дампы маршрутов (RTM_GETROUTE)."
            } else {
                "Filters out IPv4 VPN routes from netlink route-dump (RTM_GETROUTE) responses."
            },
        ),
        HookInfo(
            11,
            "sock_setsockopt",
            "sock_setsockopt",
            if (isRussian) {
                "Перехватывает вызовы setsockopt для предотвращения привязки целевых приложений напрямую к VPN-сокетам."
            } else {
                "Intercepts setsockopt calls to prevent target apps from binding directly to VPN sockets or checking parameters."
            },
        ),
        HookInfo(
            12,
            "sock_getsockopt",
            "sock_getsockopt",
            if (isRussian) {
                "Перехватывает getsockopt для подмены опций TCP/UDP (например, TCP_MSS), маскируя присутствие VPN."
            } else {
                "Intercepts getsockopt calls to spoof TCP/UDP options (e.g., TCP_MSS) to pretend no VPN is active."
            },
        ),
        HookInfo(
            13,
            "security_socket_connect",
            "security_socket_connect",
            if (isRussian) {
                "Скрывает порты: блокирует loopback-подключения целевых приложений к управляющим портам VPN-демонов."
            } else {
                "Implements Port Hiding by blocking loopback connections to VPN daemon control ports."
            },
        ),
        HookInfo(
            14,
            "inet_getname",
            "inet_getname",
            if (isRussian) {
                "Подменяет getsockname/getpeername для IPv4 сокетов, скрывая петлевые (loopback) адреса VPN-наблюдателей."
            } else {
                "Spoofs getsockname/getpeername for IPv4 sockets to hide loopback addresses of VPN observers."
            },
        ),
        HookInfo(
            15,
            "inet6_getname",
            "inet6_getname",
            if (isRussian) {
                "Подменяет getsockname/getpeername для IPv6 сокетов, скрывая петлевые (loopback) адреса VPN-наблюдателей."
            } else {
                "Spoofs getsockname/getpeername for IPv6 sockets to hide loopback addresses of VPN observers."
            },
        ),
        HookInfo(
            16,
            "security_socket_bind",
            "security_socket_bind",
            if (isRussian) {
                "Перехватывает bind() на loopback: перенаправляет порты на случайные свободные порты (0) для обхода проверок занятости."
            } else {
                "Intercepts loopback bind() calls: redirects protected ports to random free ephemeral ports (0) to bypass conflict checks."
            },
        ),
    )

val ALL_JAVA_HOOKS =
    listOf(
        HookInfo(0, "LinkProperties", "android.net.LinkProperties", ""),
        HookInfo(1, "NetworkCapabilities", "android.net.NetworkCapabilities", ""),
        HookInfo(2, "NetworkInfo", "android.net.NetworkInfo", ""),
        HookInfo(3, "Network", "android.net.Network", ""),
        HookInfo(4, "WifiInfo", "android.net.wifi.WifiInfo", ""),
        HookInfo(5, "ConnectivityService", "com.android.server.ConnectivityService", ""),
    )

val JAVA_HOOK_DESCRIPTIONS =
    mapOf(
        0 to R.string.hook_desc_link_properties,
        1 to R.string.hook_desc_network_capabilities,
        2 to R.string.hook_desc_network_info,
        3 to R.string.hook_desc_network,
        4 to R.string.hook_desc_wifi_info,
        5 to R.string.hook_desc_connectivity_service,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookTestingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableStateOf(0) }

    // Native Hooks State
    var mask by remember { mutableStateOf<UInt?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var applyingState by remember { mutableStateOf(false) }

    // Java Hooks State
    var javaMask by remember { mutableStateOf<UInt?>(null) }
    var javaErrorMessage by remember { mutableStateOf<String?>(null) }
    var applyingJavaState by remember { mutableStateOf(false) }

    // Localized error resolution inside Composable context
    val errKernelParse = stringResource(R.string.hook_testing_kernel_err_parse)
    val errKernelNotActive = stringResource(R.string.hook_testing_kernel_err_not_active)
    val errKernelSet = stringResource(R.string.hook_testing_kernel_err_set)
    val errFrameworkParse = stringResource(R.string.hook_testing_framework_err_parse)
    val errFrameworkAccess = stringResource(R.string.hook_testing_framework_err_access)
    val errFrameworkSet = stringResource(R.string.hook_testing_framework_err_set)

    fun refreshMask() {
        scope.launch(Dispatchers.IO) {
            val (exit, stdout) = suExec("$KMOD_CTL active_hooks")
            if (exit == 0) {
                val parsed = stdout.trim().toUIntOrNull()
                if (parsed != null) {
                    mask = parsed
                    errorMessage = null
                } else {
                    errorMessage = errKernelParse.replace("%1\$s", stdout.trim())
                }
            } else {
                errorMessage = errKernelNotActive
            }
        }
    }

    fun refreshJavaMask() {
        scope.launch(Dispatchers.IO) {
            val (exit, stdout) = suExec("cat /data/system/vpnhide/vpnhide_active_java_hooks 2>/dev/null || echo 4294967295")
            if (exit == 0) {
                val parsed = stdout.trim().toUIntOrNull()
                if (parsed != null) {
                    javaMask = parsed
                    javaErrorMessage = null
                } else {
                    javaErrorMessage = errFrameworkParse.replace("%1\$s", stdout.trim())
                }
            } else {
                javaErrorMessage = errFrameworkAccess
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshMask()
        refreshJavaMask()
    }

    fun applyNewMask(newMask: UInt) {
        applyingState = true
        scope.launch(Dispatchers.IO) {
            val (exit, _) = suExec("$KMOD_CTL active_hooks $newMask")
            if (exit == 0) {
                mask = newMask
                errorMessage = null
            } else {
                errorMessage = errKernelSet
            }
            applyingState = false
        }
    }

    fun applyNewJavaMask(newMask: UInt) {
        applyingJavaState = true
        scope.launch(Dispatchers.IO) {
            val path = "/data/system/vpnhide/vpnhide_active_java_hooks"
            val cmd =
                "mkdir -p /data/system/vpnhide && echo $newMask > $path && chmod 644 $path" +
                    " && chown system:system $path && chcon u:object_r:system_data_file:s0 $path 2>/dev/null || true"
            val (exit, _) = suExec(cmd)
            if (exit == 0) {
                javaMask = newMask
                javaErrorMessage = null
            } else {
                javaErrorMessage = errFrameworkSet
            }
            applyingJavaState = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hook_testing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text(stringResource(R.string.hook_testing_tab_kernel), fontWeight = FontWeight.Bold) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text(stringResource(R.string.hook_testing_tab_framework), fontWeight = FontWeight.Bold) },
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(16.dp))

                if (selectedTabIndex == 0) {
                    // KERNEL TAB
                    ElevatedCard(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.hook_testing_kernel_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.hook_testing_kernel_card_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = errorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    mask?.let { currentMask ->
                        ElevatedCard(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.hook_testing_kernel_mask_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "0x${currentMask.toString(16).uppercase()} ($currentMask)",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }

                                    if (applyingState) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { applyNewMask(0xFFFFFFFFu) },
                                        enabled = !applyingState && currentMask != 0xFFFFFFFFu,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.ToggleOn, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.hook_testing_btn_enable_all))
                                    }

                                    OutlinedButton(
                                        onClick = { applyNewMask(0x0000u) },
                                        enabled = !applyingState && currentMask != 0x0000u,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.ToggleOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.hook_testing_btn_disable_all))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Text(
                            text = stringResource(R.string.hook_testing_list_kernel),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Spacer(Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (hook in ALL_HOOKS) {
                                val isEnabled = (currentMask and (1u shl hook.index)) != 0u
                                ElevatedCard(
                                    shape = RoundedCornerShape(8.dp),
                                    colors =
                                        CardDefaults.elevatedCardColors(
                                            containerColor =
                                                if (isEnabled) {
                                                    MaterialTheme.colorScheme.surface
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                },
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .padding(16.dp)
                                                .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = { Text("Idx ${hook.index}") },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                                Text(
                                                    text = hook.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color =
                                                        if (isEnabled) {
                                                            MaterialTheme.colorScheme.onSurface
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                                .copy(
                                                                    alpha = 0.5f,
                                                                )
                                                        },
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = hook.symbol,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = if (isEnabled) 1.0f else 0.5f),
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = hook.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isEnabled) 0.8f else 0.4f),
                                            )
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Switch(
                                            checked = isEnabled,
                                            enabled = !applyingState,
                                            onCheckedChange = { checked ->
                                                val newMask =
                                                    if (checked) {
                                                        currentMask or (1u shl hook.index)
                                                    } else {
                                                        currentMask and (1u shl hook.index).inv()
                                                    }
                                                applyNewMask(newMask)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // FRAMEWORK (JAVA) TAB
                    ElevatedCard(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.hook_testing_framework_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.hook_testing_framework_card_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (javaErrorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = javaErrorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    javaMask?.let { currentJavaMask ->
                        ElevatedCard(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.hook_testing_framework_mask_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "0x${currentJavaMask.toString(16).uppercase()} ($currentJavaMask)",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }

                                    if (applyingJavaState) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { applyNewJavaMask(0xFFFFFFFFu) },
                                        enabled = !applyingJavaState && currentJavaMask != 0xFFFFFFFFu,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.ToggleOn, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.hook_testing_btn_enable_all))
                                    }

                                    OutlinedButton(
                                        onClick = { applyNewJavaMask(0x00u) },
                                        enabled = !applyingJavaState && currentJavaMask != 0x00u,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.ToggleOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.hook_testing_btn_disable_all))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Text(
                            text = stringResource(R.string.hook_testing_list_java),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Spacer(Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (hook in ALL_JAVA_HOOKS) {
                                val isEnabled = (currentJavaMask and (1u shl hook.index)) != 0u
                                ElevatedCard(
                                    shape = RoundedCornerShape(8.dp),
                                    colors =
                                        CardDefaults.elevatedCardColors(
                                            containerColor =
                                                if (isEnabled) {
                                                    MaterialTheme.colorScheme.surface
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                },
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .padding(16.dp)
                                                .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = { Text("Idx ${hook.index}") },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                                Text(
                                                    text = hook.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color =
                                                        if (isEnabled) {
                                                            MaterialTheme.colorScheme.onSurface
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                                .copy(
                                                                    alpha = 0.5f,
                                                                )
                                                        },
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = hook.symbol,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = if (isEnabled) 1.0f else 0.5f),
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = stringResource(JAVA_HOOK_DESCRIPTIONS[hook.index] ?: R.string.app_name),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isEnabled) 0.8f else 0.4f),
                                            )
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Switch(
                                            checked = isEnabled,
                                            enabled = !applyingJavaState,
                                            onCheckedChange = { checked ->
                                                val newMask =
                                                    if (checked) {
                                                        currentJavaMask or (1u shl hook.index)
                                                    } else {
                                                        currentJavaMask and (1u shl hook.index).inv()
                                                    }
                                                applyNewJavaMask(newMask)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(bottomNavPadding + 32.dp))
            }
        }
    }
}
