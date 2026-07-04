package dev.soranerai.vpnhidenext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.db.JAVA_HOOK_BIT_HIDE_VPN_APPS
import dev.soranerai.vpnhidenext.db.JAVA_HOOK_BIT_SELF_HIDE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHookTesting: () -> Unit,
    onOpenDiagnosticsDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hideVpnApps by remember { mutableStateOf(false) }
    var hideSelf by remember { mutableStateOf(false) }
    var simSpoofMode by remember { mutableStateOf("none") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            hideVpnApps = isJavaHookBitEnabled(context, JAVA_HOOK_BIT_HIDE_VPN_APPS)
            hideSelf = isJavaHookBitEnabled(context, JAVA_HOOK_BIT_SELF_HIDE)
            simSpoofMode = getSimSpoofMode(context)
        }
    }

    val simSpoofNone = stringResource(R.string.settings_sim_spoof_option_none)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "spacer_top") { Spacer(Modifier.height(8.dp)) }

            item(key = "section_testing") {
                SectionHeader(stringResource(R.string.settings_section_testing))
            }
            item(key = "group_testing") {
                SettingsGroup {
                    SettingsNavRow(
                        title = stringResource(R.string.diag_hook_isolation_title),
                        subtitle = stringResource(R.string.diag_hook_isolation_description),
                        onClick = onOpenHookTesting,
                    )
                    SettingsRowDivider()
                    SettingsNavRow(
                        title = stringResource(R.string.settings_diagnostics_detail_title),
                        subtitle = stringResource(R.string.settings_diagnostics_detail_desc),
                        onClick = onOpenDiagnosticsDetail,
                    )
                }
            }

            item(key = "section_experimental") {
                SectionHeader(stringResource(R.string.settings_section_experimental))
            }
            item(key = "group_experimental") {
                SettingsGroup {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_toggle_hide_vpn_apps_title),
                        subtitle = stringResource(R.string.settings_toggle_hide_vpn_apps_desc),
                        checked = hideVpnApps,
                        onCheckedChange = { newValue ->
                            val previous = hideVpnApps
                            hideVpnApps = newValue
                            scope.launch(Dispatchers.IO) {
                                val success = setJavaHookBit(context, JAVA_HOOK_BIT_HIDE_VPN_APPS, newValue)
                                if (!success) {
                                    withContext(Dispatchers.Main) { hideVpnApps = previous }
                                }
                            }
                        },
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_toggle_hide_self_title),
                        subtitle = stringResource(R.string.settings_toggle_hide_self_desc),
                        checked = hideSelf,
                        onCheckedChange = { newValue ->
                            val previous = hideSelf
                            hideSelf = newValue
                            scope.launch(Dispatchers.IO) {
                                val success = setJavaHookBit(context, JAVA_HOOK_BIT_SELF_HIDE, newValue)
                                if (!success) {
                                    withContext(Dispatchers.Main) { hideSelf = previous }
                                }
                            }
                        },
                    )
                    SettingsRowDivider()
                    SettingsDropdownRow(
                        title = stringResource(R.string.settings_sim_spoof_title),
                        subtitle = stringResource(R.string.settings_sim_spoof_desc),
                        options = listOf(simSpoofNone),
                        selected = if (simSpoofMode == "none") simSpoofNone else simSpoofMode,
                        onSelect = { _ ->
                            simSpoofMode = "none"
                            scope.launch(Dispatchers.IO) { setSimSpoofMode(context, "none") }
                        },
                    )
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
