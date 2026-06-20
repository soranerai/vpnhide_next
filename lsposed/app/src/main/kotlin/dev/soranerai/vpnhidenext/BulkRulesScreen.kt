package dev.soranerai.vpnhidenext

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.soranerai.vpnhidenext.db.AppDatabase
import dev.soranerai.vpnhidenext.db.DbMassPortRule
import kotlinx.coroutines.launch

@Composable
internal fun BulkRulesScreen(
    initialRules: List<PortRule>,
    onBack: () -> Unit,
    onSave: (List<PortRule>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val localRules = remember { mutableStateListOf<PortRule>().apply { addAll(initialRules) } }
    var editingRule by remember { mutableStateOf<PortRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

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
                        stringResource(R.string.bulk_rules_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.bulk_rules_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (localRules.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SettingsSuggest,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.no_rules),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(localRules, key = { it.id }) { rule ->
                        BulkRuleCard(
                            rule = rule,
                            onEdit = { editingRule = rule },
                            onDelete = {
                                localRules.removeIf { it.id == rule.id }
                            },
                            onToggle = {
                                val idx = localRules.indexOfFirst { it.id == rule.id }
                                if (idx != -1) {
                                    localRules[idx] = rule.copy(enabled = !rule.enabled)
                                }
                            },
                        )
                    }
                    item {
                        val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Spacer(Modifier.height(bottomNavPadding + 100.dp))
                    }
                }
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
                                    .clickable { onSave(localRules.toList()) },
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

        if (showAddDialog || editingRule != null) {
            BulkRuleDialog(
                initialRule = editingRule,
                existingRules = localRules.toList(),
                onDismiss = {
                    showAddDialog = false
                    editingRule = null
                },
                onConfirm = { newRule ->
                    if (editingRule != null) {
                        val idx = localRules.indexOfFirst { it.id == editingRule!!.id }
                        if (idx != -1) {
                            localRules[idx] = newRule
                        }
                    } else {
                        localRules.add(newRule)
                    }
                    showAddDialog = false
                    editingRule = null
                },
            )
        }
    }
}

@Composable
private fun BulkRuleCard(
    rule: PortRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    ElevatedCard(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (rule.label.isNotEmpty()) {
                    Text(
                        text = rule.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val protoLabel =
                    when (rule.protocol) {
                        PortProtocol.TCP -> "TCP"
                        PortProtocol.UDP -> "UDP"
                        PortProtocol.BOTH -> stringResource(R.string.protocol_both)
                    }
                Text(
                    text =
                        if (rule.startPort == rule.endPort) {
                            stringResource(R.string.port_rule_port_format, rule.startPort)
                        } else {
                            stringResource(R.string.port_rule_range_format, rule.startPort, rule.endPort)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${stringResource(R.string.protocol)}: $protoLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkRuleDialog(
    initialRule: PortRule? = null,
    existingRules: List<PortRule>,
    onDismiss: () -> Unit,
    onConfirm: (PortRule) -> Unit,
) {
    var label by remember { mutableStateOf(initialRule?.label ?: "") }
    var startPort by remember { mutableStateOf(initialRule?.startPort?.toString() ?: "") }
    var endPort by remember { mutableStateOf(initialRule?.endPort?.toString() ?: "") }
    var protocol by remember { mutableStateOf(initialRule?.protocol ?: PortProtocol.BOTH) }

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
                    text = if (initialRule == null) stringResource(R.string.new_bulk_rule) else stringResource(R.string.edit_bulk_rule),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.label_optional)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startPort,
                        onValueChange = { if (it.length <= 5) startPort = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.port_start)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endPort,
                        onValueChange = { if (it.length <= 5) endPort = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.port_end)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.protocol), style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PortProtocol.values().forEachIndexed { index, p ->
                            val pLabel =
                                when (p) {
                                    PortProtocol.TCP -> "TCP"
                                    PortProtocol.UDP -> "UDP"
                                    PortProtocol.BOTH -> stringResource(R.string.protocol_both)
                                }
                            SegmentedButton(
                                selected = protocol == p,
                                onClick = { protocol = p },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = PortProtocol.values().size),
                            ) {
                                Text(pLabel, fontSize = 10.sp)
                            }
                        }
                    }
                }

                val currentRule =
                    PortRule(
                        id = initialRule?.id ?: "",
                        startPort = startPort.toIntOrNull() ?: (endPort.toIntOrNull() ?: 1),
                        endPort = endPort.toIntOrNull() ?: (startPort.toIntOrNull() ?: 65535),
                        protocol = protocol,
                        label = label,
                        enabled = true,
                    )
                val violation = validateRule(currentRule, existingRules)
                if (violation.type != RuleViolationType.NONE) {
                    val msg =
                        when (violation.type) {
                            RuleViolationType.DUPLICATE -> {
                                stringResource(R.string.err_rule_exists)
                            }

                            RuleViolationType.REDUNDANT -> {
                                val target = violation.coveringRule
                                if (target?.label?.isNotEmpty() == true) {
                                    stringResource(R.string.err_rule_redundant, target.label)
                                } else {
                                    stringResource(R.string.err_rule_redundant, "${target?.startPort}-${target?.endPort}")
                                }
                            }

                            else -> {
                                ""
                            }
                        }
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = violation.type == RuleViolationType.NONE,
                        onClick = {
                            val s = startPort.toIntOrNull()
                            val e = endPort.toIntOrNull()
                            val finalStart = s ?: (e ?: 1)
                            val finalEnd = e ?: (s ?: 65535)
                            onConfirm(
                                PortRule(
                                    id =
                                        initialRule?.id ?: java.util.UUID
                                            .randomUUID()
                                            .toString(),
                                    startPort = finalStart,
                                    endPort = finalEnd,
                                    protocol = protocol,
                                    label = label,
                                    enabled = initialRule?.enabled ?: true,
                                ),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (initialRule == null) stringResource(R.string.add_rule) else stringResource(R.string.btn_save))
                    }
                }
            }
        }
    }
}
