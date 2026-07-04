package dev.soranerai.vpnhidenext

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import java.util.UUID

@Composable
internal fun PortRulesScreen(
    app: AppEntry,
    massRules: List<PortRule>,
    onBack: () -> Unit,
    onSave: (List<PortRule>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rules by remember { mutableStateOf(app.portRules) }
    var editingRule by remember { mutableStateOf<PortRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with App Info
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                app.icon?.let {
                    Image(
                        bitmap = it.toBitmap(48, 48).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (rules.isEmpty() && massRules.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Dns,
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
                    if (massRules.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.ports_mass_rules_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        items(massRules, key = { "mass_${it.id}" }) { rule ->
                            PortRuleCard(
                                rule = rule,
                                isReadOnly = true,
                                onEdit = {},
                                onDelete = {},
                                onToggle = {},
                            )
                        }
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            Text(
                                stringResource(R.string.ports_local_rules_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }

                    items(rules, key = { it.id }) { rule ->
                        PortRuleCard(
                            rule = rule,
                            onEdit = { editingRule = rule },
                            onDelete = { rules = rules.filter { it.id != rule.id } },
                            onToggle = { rules = rules.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it } },
                        )
                    }
                    item {
                        val bottomNavPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Spacer(Modifier.height(bottomNavPadding + 100.dp))
                    }
                }
            }
        }

        // Bottom Bar (Pill style - matched with MainActivity)
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
                // Width driver to match MainActivity's centering
                Row(
                    modifier = Modifier.height(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(260.dp))
                    Spacer(modifier = Modifier.width(76.dp))
                }

                // Left Button: Back (Matches Navigation Pill position)
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
                        // Match nav pill width
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                    .clickable { onSave(rules) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.btn_save_back),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // Right Button: Add Rule (Matches MainActivity Save Button position)
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
            PortRuleDialog(
                initialRule = editingRule,
                existingRules = rules,
                massRules = massRules,
                onDismiss = {
                    showAddDialog = false
                    editingRule = null
                },
                onConfirm = { newRule ->
                    val filtered =
                        rules.filter { e ->
                            !(
                                newRule.startPort <= e.startPort && newRule.endPort >= e.endPort &&
                                    (newRule.protocol == PortProtocol.BOTH || newRule.protocol == e.protocol)
                            )
                        }
                    if (editingRule != null) {
                        rules = filtered.map { if (it.id == editingRule!!.id) newRule.copy(id = it.id) else it }
                        // If the editing rule itself was filtered out (it covers itself), we must re-add it
                        if (rules.none { it.id == editingRule!!.id }) {
                            rules = filtered + newRule.copy(id = editingRule!!.id)
                        }
                    } else {
                        rules = filtered + newRule
                    }
                    showAddDialog = false
                    editingRule = null
                },
            )
        }
    }
}

@Composable
private fun PortRuleCard(
    rule: PortRule,
    isReadOnly: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    ElevatedCard(
        onClick = if (isReadOnly) ({}) else onEdit,
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (isReadOnly) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
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
                        color = if (isReadOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text =
                        if (rule.startPort ==
                            rule.endPort
                        ) {
                            stringResource(R.string.port_rule_port_format, rule.startPort)
                        } else {
                            stringResource(R.string.port_rule_range_format, rule.startPort, rule.endPort)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isReadOnly) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                )
                val protoLabel =
                    when (rule.protocol) {
                        PortProtocol.TCP -> "TCP"
                        PortProtocol.UDP -> "UDP"
                        PortProtocol.BOTH -> stringResource(R.string.protocol_both)
                    }
                Text(
                    text = stringResource(R.string.port_rule_protocol_format, protoLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isReadOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
            }
            if (!isReadOnly) {
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Text(stringResource(R.string.bulk_btn).uppercase(), fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortRuleDialog(
    initialRule: PortRule? = null,
    existingRules: List<PortRule>,
    massRules: List<PortRule>,
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text =
                        if (initialRule ==
                            null
                        ) {
                            stringResource(R.string.ports_new_rule_title)
                        } else {
                            stringResource(R.string.ports_edit_rule_title)
                        },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.label_optional)) },
                    placeholder = { Text(stringResource(R.string.ports_label_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startPort,
                        onValueChange = { if (it.length <= 5) startPort = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.port_start)) },
                        placeholder = { Text("1") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endPort,
                        onValueChange = { if (it.length <= 5) endPort = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.port_end)) },
                        placeholder = { Text(if (startPort.isEmpty()) "65535" else startPort) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    stringResource(R.string.ports_default_range_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )

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

                val violationLocal = validateRule(currentRule, existingRules)
                val violationMass = validateRule(currentRule, massRules)
                val violation = if (violationMass.type != RuleViolationType.NONE) violationMass else violationLocal

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
                } else {
                    val rulesToRemove =
                        existingRules.filter { e ->
                            e.id != initialRule?.id &&
                                currentRule.startPort <= e.startPort && currentRule.endPort >= e.endPort &&
                                (protocol == PortProtocol.BOTH || protocol == e.protocol)
                        }
                    if (rulesToRemove.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ports_redundant_rules_removed_warning, rulesToRemove.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
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
                            onConfirm(
                                currentRule.copy(
                                    id = initialRule?.id ?: UUID.randomUUID().toString(),
                                    enabled =
                                        initialRule?.enabled ?: true,
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
