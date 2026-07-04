package dev.soranerai.vpnhidenext

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

@Composable
internal fun AppRow(
    app: AppEntry,
    installed: InstalledModules,
    onToggle: (Layer) -> Unit,
    onToggleAll: () -> Unit,
    onTogglePort: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onToggleAll() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp)) {
                app.icon?.let {
                    Image(
                        bitmap = it.toBitmap(48, 48).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface, CircleShape))

                ProfileIconBadge(app.userId)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (app.userId != 0) Color(0xFF2196F3) else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                UidChip(app.uid)
            }

            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.app_row_settings_desc),
                    tint =
                        if (app.hasHookOverride || app.portRules.isNotEmpty()) {
                            if (app.userId != 0) Color(0xFF2196F3) else MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installed.kmod) {
                    ProtectionChip("N", app.kmod, true, app.userId) { onToggle(Layer.KMOD) }
                }

                ProtectionChip("F", app.lsposed, true, app.userId) { onToggle(Layer.LSPOSED) }
                ProtectionChip("P", app.portHiding, true, app.userId) { onTogglePort() }
            }
        }
    }
}

@Composable
private fun ProtectionChip(
    label: String,
    active: Boolean,
    installed: Boolean,
    userId: Int = 0,
    onClick: () -> Unit,
) {
    val activeColor = if (userId != 0) Color(0xFF2196F3) else MaterialTheme.colorScheme.primary
    val onActiveColor = if (userId != 0) Color.White else MaterialTheme.colorScheme.onPrimary
    val color = if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (active) onActiveColor else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        enabled = installed,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (installed) 1f else 0.3f),
        contentColor = textColor,
        modifier = Modifier.size(32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun SkeletonAppRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerPlaceholder(modifier = Modifier.size(40.dp).clip(CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerPlaceholder(modifier = Modifier.width(120.dp).height(16.dp))
            Spacer(Modifier.height(4.dp))
            ShimmerPlaceholder(modifier = Modifier.width(180.dp).height(12.dp))
        }
    }
}

@Composable
internal fun BoxScope.ProfileIconBadge(userId: Int) {
    if (userId != 0) {
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
            shape = CircleShape,
            color = Color(0xFF2196F3),
            tonalElevation = 4.dp,
        ) {
            Icon(
                imageVector = Icons.Default.Work,
                contentDescription = null,
                modifier = Modifier.padding(3.dp).size(12.dp),
                tint = Color.White,
            )
        }
    }
}

@Composable
internal fun UidChip(uid: Int) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.app_row_uid_format, uid),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}
