package dev.soranerai.vpnhidenext

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.soranerai.vpnhidenext.db.JAVA_HOOK_BIT_HIDE_VPN_APPS
import dev.soranerai.vpnhidenext.db.JAVA_HOOK_BIT_SELF_HIDE
import dev.soranerai.vpnhidenext.db.SettingsBackupHelper
import dev.soranerai.vpnhidenext.ui.theme.TelBlue
import dev.soranerai.vpnhidenext.ui.theme.TelGreen
import dev.soranerai.vpnhidenext.ui.theme.TelOrange
import dev.soranerai.vpnhidenext.ui.theme.TelPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnosticsDetail: () -> Unit,
    selfNeedsRestart: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hideVpnApps by remember { mutableStateOf(false) }
    var hideSelf by remember { mutableStateOf(false) }
    var updateCheckEnabled by remember { mutableStateOf(true) }
    var healthCheckEnabled by remember { mutableStateOf(true) }
    var statsRetentionPeriod by remember { mutableStateOf(StatsRetentionPeriod.THIRTY_MIN) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            hideVpnApps = isJavaHookBitEnabled(context, JAVA_HOOK_BIT_HIDE_VPN_APPS)
            hideSelf = isJavaHookBitEnabled(context, JAVA_HOOK_BIT_SELF_HIDE)
            updateCheckEnabled = getUpdateCheckEnabled(context)
            healthCheckEnabled = getHealthCheckEnabled(context)
            statsRetentionPeriod = getStatsRetentionPeriod(context)
        }
    }

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

            item(key = "section_diagnostics") {
                SectionHeader(
                    stringResource(R.string.settings_section_diagnostics),
                    icon = Icons.Default.Troubleshoot,
                    tint = TelBlue,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            item(key = "backup_restore_card") { BackupRestoreCard() }
            item(key = "debug_logging_card") { DebugLoggingCard() }
            item(key = "debug_log_export_card") { DebugLogExportCard(selfNeedsRestart) }

            item(key = "section_statistics") {
                SectionHeader(
                    stringResource(R.string.settings_section_statistics),
                    icon = Icons.Default.BarChart,
                    tint = TelGreen,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            item(key = "group_statistics") {
                val labelsByPeriod = StatsRetentionPeriod.entries.associateWith { it.displayLabel() }
                SettingsGroup {
                    SettingsDropdownRow(
                        title = stringResource(R.string.settings_stats_retention_title),
                        subtitle = stringResource(R.string.settings_stats_retention_desc),
                        options = labelsByPeriod.values.toList(),
                        selected = labelsByPeriod.getValue(statsRetentionPeriod),
                        icon = Icons.Default.Schedule,
                        iconTint = TelGreen,
                        onSelect = { label ->
                            val newPeriod = labelsByPeriod.entries.first { it.value == label }.key
                            val previous = statsRetentionPeriod
                            statsRetentionPeriod = newPeriod
                            scope.launch(Dispatchers.IO) {
                                val success = setStatsRetentionPeriod(context, newPeriod)
                                if (!success) {
                                    withContext(Dispatchers.Main) { statsRetentionPeriod = previous }
                                }
                            }
                        },
                    )
                }
            }

            item(key = "section_testing") {
                SectionHeader(
                    stringResource(R.string.settings_section_testing),
                    icon = Icons.Default.Science,
                    tint = TelPurple,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            item(key = "group_testing") {
                SettingsGroup {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_diagnostics_detail_title),
                        subtitle = stringResource(R.string.settings_diagnostics_detail_desc),
                        icon = Icons.Default.Assessment,
                        iconTint = TelPurple,
                        onClick = onOpenDiagnosticsDetail,
                    )
                }
            }

            item(key = "section_experimental") {
                SectionHeader(
                    stringResource(R.string.settings_section_experimental),
                    icon = Icons.Default.Whatshot,
                    tint = TelOrange,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            item(key = "group_experimental") {
                SettingsGroup {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_toggle_hide_vpn_apps_title),
                        subtitle = stringResource(R.string.settings_toggle_hide_vpn_apps_desc),
                        checked = hideVpnApps,
                        icon = Icons.Default.VisibilityOff,
                        iconTint = TelOrange,
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
                        icon = Icons.Default.Shield,
                        iconTint = TelOrange,
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
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_toggle_update_check_title),
                        subtitle = stringResource(R.string.settings_toggle_update_check_desc),
                        checked = updateCheckEnabled,
                        icon = Icons.Default.SystemUpdate,
                        iconTint = TelOrange,
                        onCheckedChange = { newValue ->
                            val previous = updateCheckEnabled
                            updateCheckEnabled = newValue
                            scope.launch(Dispatchers.IO) {
                                setUpdateCheckEnabled(context, newValue)
                            }
                        },
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_toggle_health_check_title),
                        subtitle = stringResource(R.string.settings_toggle_health_check_desc),
                        checked = healthCheckEnabled,
                        icon = Icons.Default.MonitorHeart,
                        iconTint = TelOrange,
                        onCheckedChange = { newValue ->
                            val previous = healthCheckEnabled
                            healthCheckEnabled = newValue
                            scope.launch(Dispatchers.IO) {
                                setHealthCheckEnabled(context, newValue)
                            }
                        },
                    )
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(24.dp)) }
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
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                SettingsRowIcon(icon = Icons.Default.SettingsBackupRestore, tint = TelBlue)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.backup_restore_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSquareIconButton(
                    icon = Icons.Default.Save,
                    contentDescription = stringResource(R.string.btn_export),
                    onClick = {
                        val timestamp =
                            java.text
                                .SimpleDateFormat(
                                    "yyyyMMdd_HHmmss",
                                    java.util.Locale.US,
                                ).format(java.util.Date())
                        exportLauncher.launch("vpnhide_backup_$timestamp.json")
                    },
                    tint = TelBlue,
                    enabled = !exporting && !importing,
                    loading = exporting,
                )

                SettingsSquareIconButton(
                    icon = Icons.Default.Share,
                    contentDescription = stringResource(R.string.btn_import),
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream"),
                        )
                    },
                    tint = TelBlue,
                    enabled = !exporting && !importing,
                    loading = importing,
                )
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
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon = Icons.Default.BugReport, tint = TelBlue)
            Spacer(Modifier.width(12.dp))
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
private fun DebugLogExportCard(selfNeedsRestart: Boolean) {
    val context = LocalContext.current
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var debugZipFile by remember { mutableStateOf<File?>(null) }

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
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon = Icons.AutoMirrored.Filled.Article, tint = TelBlue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.debug_log_export_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        if (exporting) {
                            stringResource(R.string.btn_export_debug_running)
                        } else {
                            stringResource(R.string.debug_log_export_desc)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (debugZipFile == null) {
                SettingsSquareIconButton(
                    icon = Icons.Default.Download,
                    contentDescription = stringResource(R.string.btn_export_debug),
                    onClick = {
                        exporting = true
                        scope.launch {
                            debugZipFile = exportDebugZip(cm, context, selfNeedsRestart)
                            exporting = false
                        }
                    },
                    tint = TelBlue,
                    enabled = !exporting,
                    loading = exporting,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSquareIconButton(
                        icon = Icons.Default.Save,
                        contentDescription = stringResource(R.string.btn_save_debug),
                        onClick = { saveLauncher.launch(debugZipFile!!.name) },
                        tint = TelBlue,
                    )
                    SettingsSquareIconButton(
                        icon = Icons.Default.Share,
                        contentDescription = stringResource(R.string.btn_share_debug),
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
                        tint = TelBlue,
                    )
                }
            }
        }
    }
}
