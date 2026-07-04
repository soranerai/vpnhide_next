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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            hideVpnApps = isJavaHookBitEnabled(context, JAVA_HOOK_BIT_HIDE_VPN_APPS)
            hideSelf = isJavaHookBitEnabled(context, JAVA_HOOK_BIT_SELF_HIDE)
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
                SectionHeader(stringResource(R.string.settings_section_diagnostics))
            }
            item(key = "backup_restore_card") { BackupRestoreCard() }
            item(key = "debug_logging_card") { DebugLoggingCard() }
            item(key = "debug_log_export_card") { DebugLogExportCard(selfNeedsRestart) }

            item(key = "section_testing") {
                SectionHeader(stringResource(R.string.settings_section_testing))
            }
            item(key = "group_testing") {
                SettingsGroup {
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
