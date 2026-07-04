package dev.soranerai.vpnhidenext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.soranerai.vpnhidenext.ui.theme.TelGreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IfacePrefixScreen(
    initialPrefixes: List<String>,
    onBack: () -> Unit,
    onSave: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val localPrefixes = remember { mutableStateListOf<String>().apply { addAll(initialPrefixes) } }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPrefix by remember { mutableStateOf<String?>(null) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        stringResource(R.string.iface_prefixes_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.iface_prefixes_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
            ) {
                // System Section
                Text(
                    stringResource(R.string.section_system_ifaces),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                val baseIfaces = listOf("tun", "wg", "ppp", "ipsec", "xfrm", "tap", "l2tp", "gre")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    baseIfaces.forEach { name ->
                        SystemIfaceChip(name)
                    }
                    // The "if<N>" and "vpn" catch-alls
                    SystemIfaceChip("if*")
                    SystemIfaceChip("*vpn*")
                }

                Spacer(Modifier.height(32.dp))

                // Custom Section
                Text(
                    stringResource(R.string.section_custom_ifaces),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                if (localPrefixes.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.no_prefixes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        localPrefixes.forEach { prefix ->
                            CustomIfaceChip(
                                prefix = prefix,
                                onClick = { editingPrefix = prefix },
                                onDelete = { localPrefixes.remove(prefix) },
                            )
                        }
                    }
                }

                val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(bottomNavPadding + 100.dp))
            }
        }

        // Bottom Bar
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.height(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(260.dp))
                    Spacer(modifier = Modifier.width(76.dp))
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
                    tonalElevation = 12.dp,
                    shadowElevation = 8.dp,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .height(60.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .width(260.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onBack() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.cancel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                    .clickable { onSave(localPrefixes.toList()) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.apply_bulk_btn),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                Surface(
                    onClick = { showAddDialog = true },
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(60.dp)
                            .graphicsLayer {
                                shadowElevation = 8.dp.toPx()
                                shape = RoundedCornerShape(20.dp)
                                clip = true
                            },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }

        if (showAddDialog || editingPrefix != null) {
            IfacePrefixDialog(
                initialPrefix = editingPrefix,
                onDismiss = {
                    showAddDialog = false
                    editingPrefix = null
                },
                onConfirm = { newPrefix ->
                    if (editingPrefix != null) {
                        val idx = localPrefixes.indexOf(editingPrefix)
                        if (idx != -1) {
                            localPrefixes[idx] = newPrefix
                        }
                    } else {
                        if (!localPrefixes.contains(newPrefix)) {
                            localPrefixes.add(newPrefix)
                        }
                    }
                    showAddDialog = false
                    editingPrefix = null
                },
            )
        }
    }
}

@Composable
private fun SystemIfaceChip(name: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = AssistChipDefaults.assistChipBorder(enabled = true),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun CustomIfaceChip(
    prefix: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val greenColor = TelGreen
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = greenColor.copy(alpha = 0.15f),
        border =
            AssistChipDefaults.assistChipBorder(
                enabled = true,
                borderColor = greenColor.copy(alpha = 0.3f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = greenColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = prefix,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = greenColor,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = greenColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun IfacePrefixDialog(
    initialPrefix: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var prefix by remember { mutableStateOf(initialPrefix ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (initialPrefix == null) stringResource(R.string.new_prefix) else stringResource(R.string.edit_prefix),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it.take(15) },
                    label = { Text(stringResource(R.string.prefix_label)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = prefix.isNotBlank(),
                        onClick = { onConfirm(prefix.trim()) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (initialPrefix == null) stringResource(R.string.add_rule) else stringResource(R.string.btn_save))
                    }
                }
            }
        }
    }
}
