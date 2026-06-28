package dev.soranerai.vpnhidenext

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HookInfo(
    val index: Int,
    val nameRes: Int,
    val symbolRes: Int,
    val descriptionRes: Int,
    val indices: List<Int>? = null,
) {
    val allIndices: List<Int>
        get() = indices ?: listOf(index)
}

val ALL_HOOKS =
    listOf(
        HookInfo(
            0,
            R.string.hook_name_dev_ioctl,
            R.string.hook_symbol_dev_ioctl,
            R.string.hook_desc_dev_ioctl,
        ),
        HookInfo(
            1,
            R.string.hook_name_sock_ioctl,
            R.string.hook_symbol_sock_ioctl,
            R.string.hook_desc_sock_ioctl,
        ),
        HookInfo(
            2,
            R.string.hook_name_rtnl_fill_ifinfo,
            R.string.hook_symbol_rtnl_fill_ifinfo,
            R.string.hook_desc_rtnl_fill_ifinfo,
        ),
        HookInfo(
            3,
            R.string.hook_name_inet6_fill_ifaddr,
            R.string.hook_symbol_inet6_fill_ifaddr,
            R.string.hook_desc_inet6_fill_ifaddr,
        ),
        HookInfo(
            4,
            R.string.hook_name_inet_fill_ifaddr,
            R.string.hook_symbol_inet_fill_ifaddr,
            R.string.hook_desc_inet_fill_ifaddr,
        ),
        HookInfo(
            5,
            R.string.hook_name_fib_route_seq_show,
            R.string.hook_symbol_fib_route_seq_show,
            R.string.hook_desc_fib_route_seq_show,
        ),
        HookInfo(
            6,
            R.string.hook_name_ipv6_route_seq_show,
            R.string.hook_symbol_ipv6_route_seq_show,
            R.string.hook_desc_ipv6_route_seq_show,
        ),
        HookInfo(
            7,
            R.string.hook_name_fib_dump_info,
            R.string.hook_symbol_fib_dump_info,
            R.string.hook_desc_fib_dump_info,
        ),
        HookInfo(
            8,
            R.string.hook_name_fib_nl_fill_rule,
            R.string.hook_symbol_fib_nl_fill_rule,
            R.string.hook_desc_fib_nl_fill_rule,
        ),
        HookInfo(
            9,
            R.string.hook_name_rt6_fill_node,
            R.string.hook_symbol_rt6_fill_node,
            R.string.hook_desc_rt6_fill_node,
        ),
        HookInfo(
            10,
            R.string.hook_name_rt_fill_info,
            R.string.hook_symbol_rt_fill_info,
            R.string.hook_desc_rt_fill_info,
        ),
        HookInfo(
            11,
            R.string.hook_name_sys_setsockopt,
            R.string.hook_symbol_sys_setsockopt,
            R.string.hook_desc_sys_setsockopt,
        ),
        HookInfo(
            12,
            R.string.hook_name_sys_getsockopt,
            R.string.hook_symbol_sys_getsockopt,
            R.string.hook_desc_sys_getsockopt,
        ),
        HookInfo(
            13,
            R.string.hook_name_sys_connect,
            R.string.hook_symbol_sys_connect,
            R.string.hook_desc_sys_connect,
        ),
        HookInfo(
            14,
            R.string.hook_name_sys_getsockname_ipv4,
            R.string.hook_symbol_sys_getsockname_ipv4,
            R.string.hook_desc_sys_getsockname_ipv4,
        ),
        HookInfo(
            15,
            R.string.hook_name_sys_getsockname_ipv6,
            R.string.hook_symbol_sys_getsockname_ipv6,
            R.string.hook_desc_sys_getsockname_ipv6,
        ),
        HookInfo(
            16,
            R.string.hook_name_sys_bind,
            R.string.hook_symbol_sys_bind,
            R.string.hook_desc_sys_bind,
        ),
        HookInfo(
            17,
            R.string.hook_name_bpf_stats_spoof,
            R.string.hook_symbol_bpf_stats_spoof,
            R.string.hook_desc_bpf_stats_spoof,
        ),
        HookInfo(
            18,
            R.string.hook_name_enoent_file_hiding,
            R.string.hook_symbol_enoent_file_hiding,
            R.string.hook_desc_enoent_file_hiding,
            indices = listOf(18, 19, 20, 21, 22, 23, 24, 26, 27),
        ),
        HookInfo(
            25,
            R.string.hook_name_udp_sendmsg,
            R.string.hook_symbol_udp_sendmsg,
            R.string.hook_desc_udp_sendmsg,
        ),
        HookInfo(
            28,
            R.string.hook_name_inet6_bind_ll,
            R.string.hook_symbol_inet6_bind_ll,
            R.string.hook_desc_inet6_bind_ll,
        ),
        HookInfo(
            29,
            R.string.hook_name_udpv6_sendmsg_ll,
            R.string.hook_symbol_udpv6_sendmsg_ll,
            R.string.hook_desc_udpv6_sendmsg_ll,
        ),
        HookInfo(
            30,
            R.string.hook_name_fib_trie_seq_show,
            R.string.hook_symbol_fib_trie_seq_show,
            R.string.hook_desc_fib_trie_seq_show,
        ),
        HookInfo(
            31,
            R.string.hook_name_tc_fill_qdisc,
            R.string.hook_symbol_tc_fill_qdisc,
            R.string.hook_desc_tc_fill_qdisc,
        ),
    )

val ALL_JAVA_HOOKS =
    listOf(
        HookInfo(
            0,
            R.string.hook_name_link_properties,
            R.string.hook_symbol_link_properties,
            R.string.hook_desc_link_properties,
        ),
        HookInfo(
            1,
            R.string.hook_name_network_capabilities,
            R.string.hook_symbol_network_capabilities,
            R.string.hook_desc_network_capabilities,
        ),
        HookInfo(
            2,
            R.string.hook_name_network_info,
            R.string.hook_symbol_network_info,
            R.string.hook_desc_network_info,
        ),
        HookInfo(
            3,
            R.string.hook_name_network,
            R.string.hook_symbol_network,
            R.string.hook_desc_network,
        ),
        HookInfo(
            4,
            R.string.hook_name_connectivity_service,
            R.string.hook_symbol_connectivity_service,
            R.string.hook_desc_connectivity_service,
        ),
        HookInfo(
            5,
            R.string.hook_name_package_manager,
            R.string.hook_symbol_package_manager,
            R.string.hook_desc_package_manager,
        ),
        HookInfo(
            6,
            R.string.hook_name_user_manager,
            R.string.hook_symbol_user_manager,
            R.string.hook_desc_user_manager,
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookTestingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableStateOf(0) }

    var nativeWriteJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var javaWriteJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Native Hooks State
    var mask by remember { mutableStateOf<UInt?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var applyingState by remember { mutableStateOf(false) }

    // Java Hooks State
    var javaMask by remember { mutableStateOf<UInt?>(null) }
    var javaErrorMessage by remember { mutableStateOf<String?>(null) }
    var applyingJavaState by remember { mutableStateOf(false) }

    // Localized error resolution inside Composable context
    val errKernelSet = stringResource(R.string.hook_testing_kernel_err_set)
    val errFrameworkSet = stringResource(R.string.hook_testing_framework_err_set)

    fun refreshMasksFromDb() {
        scope.launch(Dispatchers.IO) {
            val db =
                dev.soranerai.vpnhidenext.db.AppDatabase
                    .getInstance(context)
            val config =
                db.globalConfigDao().getConfig()
                    ?: dev.soranerai.vpnhidenext.db
                        .DbGlobalConfig()
            withContext(Dispatchers.Main) {
                mask = config.kernelHookMask.toUInt()
                javaMask = config.javaHookMask.toUInt()
                errorMessage = null
                javaErrorMessage = null
            }
        }
    }

    LaunchedEffect(Unit) { refreshMasksFromDb() }

    fun applyNewMask(newMask: UInt) {
        val oldMask = mask
        mask = newMask
        errorMessage = null
        applyingState = true
        nativeWriteJob?.cancel()
        nativeWriteJob =
            scope.launch(Dispatchers.IO) {
                val db =
                    dev.soranerai.vpnhidenext.db.AppDatabase
                        .getInstance(context)
                val dao = db.globalConfigDao()
                val currentConfig =
                    dao.getConfig() ?: dev.soranerai.vpnhidenext.db
                        .DbGlobalConfig()
                dao.insertConfig(currentConfig.copy(kernelHookMask = newMask.toLong()))
                val success =
                    dev.soranerai.vpnhidenext.db.DatabaseSync
                        .sync(context)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        if (mask == newMask) {
                            mask = oldMask
                            errorMessage = errKernelSet
                        }
                    }
                    applyingState = false
                }
            }
    }

    fun applyNewJavaMask(newMask: UInt) {
        val oldJavaMask = javaMask
        javaMask = newMask
        javaErrorMessage = null
        applyingJavaState = true
        javaWriteJob?.cancel()
        javaWriteJob =
            scope.launch(Dispatchers.IO) {
                val db =
                    dev.soranerai.vpnhidenext.db.AppDatabase
                        .getInstance(context)
                val dao = db.globalConfigDao()
                val currentConfig =
                    dao.getConfig() ?: dev.soranerai.vpnhidenext.db
                        .DbGlobalConfig()
                dao.insertConfig(currentConfig.copy(javaHookMask = newMask.toLong()))
                val success =
                    dev.soranerai.vpnhidenext.db.DatabaseSync
                        .sync(context)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        if (javaMask == newMask) {
                            javaMask = oldJavaMask
                            javaErrorMessage = errFrameworkSet
                        }
                    }
                    applyingJavaState = false
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hook_testing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
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
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
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
                    text = {
                        Text(
                            stringResource(R.string.hook_testing_tab_kernel),
                            fontWeight = FontWeight.Bold,
                        )
                    },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Text(
                            stringResource(R.string.hook_testing_tab_framework),
                            fontWeight = FontWeight.Bold,
                        )
                    },
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
                        colors =
                            CardDefaults.elevatedCardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant,
                            ),
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
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.errorContainer,
                                ),
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
                                            text =
                                                stringResource(
                                                    R.string
                                                        .hook_testing_kernel_mask_title,
                                                ),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text =
                                                "0x${currentMask.toString(16).uppercase()} ($currentMask)",
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
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor =
                                                    MaterialTheme.colorScheme
                                                        .primary,
                                            ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.ToggleOn, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.hook_testing_btn_enable_all))
                                    }

                                    OutlinedButton(
                                        onClick = { applyNewMask(0x0000u) },
                                        enabled = !applyingState && currentMask != 0x0000u,
                                        colors =
                                            ButtonDefaults.outlinedButtonColors(
                                                contentColor =
                                                    MaterialTheme.colorScheme.error,
                                            ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.Default.ToggleOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
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
                                val isEnabled = hook.allIndices.all { idx -> (currentMask and (1u shl idx)) != 0u }
                                ElevatedCard(
                                    shape = RoundedCornerShape(8.dp),
                                    colors =
                                        CardDefaults.elevatedCardColors(
                                            containerColor =
                                                if (isEnabled) {
                                                    MaterialTheme.colorScheme
                                                        .surface
                                                } else {
                                                    MaterialTheme.colorScheme
                                                        .surfaceVariant
                                                        .copy(
                                                            alpha = 0.5f,
                                                        )
                                                },
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = {
                                                        val idxText =
                                                            if (hook.allIndices.size == 1) {
                                                                stringResource(R.string.hook_testing_idx_format, hook.allIndices.first())
                                                            } else {
                                                                "Idx 18-24, 26-27"
                                                            }
                                                        Text(idxText)
                                                    },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                                Text(
                                                    text = stringResource(hook.nameRes),
                                                    style =
                                                        MaterialTheme.typography
                                                            .titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color =
                                                        if (isEnabled) {
                                                            MaterialTheme.colorScheme
                                                                .onSurface
                                                        } else {
                                                            MaterialTheme.colorScheme
                                                                .onSurface
                                                                .copy(
                                                                    alpha = 0.5f,
                                                                )
                                                        },
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(hook.symbolRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color =
                                                    MaterialTheme.colorScheme.secondary
                                                        .copy(
                                                            alpha =
                                                                if (isEnabled) {
                                                                    1.0f
                                                                } else {
                                                                    0.5f
                                                                },
                                                        ),
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = stringResource(hook.descriptionRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color =
                                                    MaterialTheme.colorScheme.onSurface
                                                        .copy(
                                                            alpha =
                                                                if (isEnabled) {
                                                                    0.8f
                                                                } else {
                                                                    0.4f
                                                                },
                                                        ),
                                            )
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Switch(
                                            checked = isEnabled,
                                            enabled = true,
                                            onCheckedChange = { checked ->
                                                var newMask = currentMask
                                                for (idx in hook.allIndices) {
                                                    newMask =
                                                        if (checked) {
                                                            newMask or (1u shl idx)
                                                        } else {
                                                            newMask and (1u shl idx).inv()
                                                        }
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
                        colors =
                            CardDefaults.elevatedCardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.hook_testing_framework_card_title,
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text =
                                    stringResource(
                                        R.string.hook_testing_framework_card_desc,
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (javaErrorMessage != null) {
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.errorContainer,
                                ),
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
                                            text =
                                                stringResource(
                                                    R.string
                                                        .hook_testing_framework_mask_title,
                                                ),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text =
                                                "0x${currentJavaMask.toString(16).uppercase()} ($currentJavaMask)",
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
                                        enabled =
                                            !applyingJavaState &&
                                                currentJavaMask != 0xFFFFFFFFu,
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor =
                                                    MaterialTheme.colorScheme
                                                        .primary,
                                            ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.ToggleOn, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.hook_testing_btn_enable_all))
                                    }

                                    OutlinedButton(
                                        onClick = { applyNewJavaMask(0x00u) },
                                        enabled =
                                            !applyingJavaState && currentJavaMask != 0x00u,
                                        colors =
                                            ButtonDefaults.outlinedButtonColors(
                                                contentColor =
                                                    MaterialTheme.colorScheme.error,
                                            ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.Default.ToggleOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
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
                                                    MaterialTheme.colorScheme
                                                        .surface
                                                } else {
                                                    MaterialTheme.colorScheme
                                                        .surfaceVariant
                                                        .copy(
                                                            alpha = 0.5f,
                                                        )
                                                },
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = { Text(stringResource(R.string.hook_testing_idx_format, hook.index)) },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                                Text(
                                                    text = stringResource(hook.nameRes),
                                                    style =
                                                        MaterialTheme.typography
                                                            .titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color =
                                                        if (isEnabled) {
                                                            MaterialTheme.colorScheme
                                                                .onSurface
                                                        } else {
                                                            MaterialTheme.colorScheme
                                                                .onSurface
                                                                .copy(
                                                                    alpha = 0.5f,
                                                                )
                                                        },
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(hook.symbolRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color =
                                                    MaterialTheme.colorScheme.secondary
                                                        .copy(
                                                            alpha =
                                                                if (isEnabled) {
                                                                    1.0f
                                                                } else {
                                                                    0.5f
                                                                },
                                                        ),
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = stringResource(hook.descriptionRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color =
                                                    MaterialTheme.colorScheme.onSurface
                                                        .copy(
                                                            alpha =
                                                                if (isEnabled) {
                                                                    0.8f
                                                                } else {
                                                                    0.4f
                                                                },
                                                        ),
                                            )
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Switch(
                                            checked = isEnabled,
                                            enabled = true,
                                            onCheckedChange = { checked ->
                                                val newMask =
                                                    if (checked) {
                                                        currentJavaMask or
                                                            (1u shl hook.index)
                                                    } else {
                                                        currentJavaMask and
                                                            (1u shl hook.index).inv()
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

                val bottomNavPadding =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(bottomNavPadding + 32.dp))
            }
        }
    }
}
