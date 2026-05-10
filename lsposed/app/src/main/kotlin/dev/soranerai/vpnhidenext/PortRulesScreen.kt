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

            if (rules.isEmpty()) {
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
                            "No rules defined",
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
                    items(rules, key = { it.id }) { rule ->
                        PortRuleCard(
                            rule = rule,
                            onEdit = { editingRule = rule },
                            onDelete = { rules = rules.filter { it.id != rule.id } },
                            onToggle = { rules = rules.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it } },
                        )
                    }
                    item { Spacer(Modifier.height(100.dp)) }
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
                                    "Save & Back",
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    ElevatedCard(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (rule.enabled) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.5f,
                        )
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
                    )
                }
                Text(
                    text = if (rule.startPort == rule.endPort) "Port: ${rule.startPort}" else "Range: ${rule.startPort} - ${rule.endPort}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Protocol: ${rule.protocol}",
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
private fun PortRuleDialog(
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
                    text = if (initialRule == null) "New Port Rule" else "Edit Port Rule",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("e.g. My Server") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startPort,
                        onValueChange = { if (it.length <= 5) startPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Start Port") },
                        placeholder = { Text("1") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endPort,
                        onValueChange = { if (it.length <= 5) endPort = it.filter { c -> c.isDigit() } },
                        label = { Text("End Port") },
                        placeholder = { Text(if (startPort.isEmpty()) "65535" else startPort) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    "Default: All ports (1-65535)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Protocol", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PortProtocol.values().forEachIndexed { index, p ->
                            SegmentedButton(
                                selected = protocol == p,
                                onClick = { protocol = p },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = PortProtocol.values().size),
                            ) {
                                Text(p.name, fontSize = 10.sp)
                            }
                        }
                    }
                }

                val currentStart = startPort.toIntOrNull() ?: 1
                val currentEnd =
                    if (endPort.isEmpty()) {
                        if (startPort.isEmpty()) 65535 else currentStart
                    } else {
                        endPort.toIntOrNull() ?: currentStart
                    }

                val isDuplicate =
                    existingRules.any {
                        it.id != initialRule?.id &&
                            it.startPort == currentStart &&
                            it.endPort == currentEnd &&
                            it.protocol == protocol
                    }

                val isRedundant =
                    !isDuplicate &&
                        existingRules.any { e ->
                            e.id != initialRule?.id &&
                                e.enabled &&
                                e.startPort <= currentStart && e.endPort >= currentEnd &&
                                (e.protocol == PortProtocol.BOTH || e.protocol == protocol)
                        }

                if (isDuplicate) {
                    Text(
                        "This rule already exists",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (isRedundant) {
                    Text(
                        "This rule is redundant (covered by another rule)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    val rulesToRemove =
                        existingRules.filter { e ->
                            e.id != initialRule?.id &&
                                currentStart <= e.startPort && currentEnd >= e.endPort &&
                                (protocol == PortProtocol.BOTH || protocol == e.protocol)
                        }

                    if (rulesToRemove.isNotEmpty()) {
                        Text(
                            "${rulesToRemove.size} redundant rule(s) will be removed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (currentStart in 1..65535 && currentEnd in 1..65535) {
                                onConfirm(
                                    PortRule(
                                        startPort = currentStart,
                                        endPort = currentEnd,
                                        protocol = protocol,
                                        label = label,
                                        enabled = initialRule?.enabled ?: true,
                                    ),
                                )
                            }
                        },
                        enabled = !isDuplicate && !isRedundant,
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    ) {
                        Text(if (initialRule == null) "Add Rule" else "Save Changes")
                    }
                }
            }
        }
    }
}
