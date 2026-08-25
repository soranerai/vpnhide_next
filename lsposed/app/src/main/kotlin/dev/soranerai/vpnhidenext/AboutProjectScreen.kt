package dev.soranerai.vpnhidenext

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.ui.theme.TelBlue
import dev.soranerai.vpnhidenext.ui.theme.TelCyan
import dev.soranerai.vpnhidenext.ui.theme.TelGreen
import dev.soranerai.vpnhidenext.ui.theme.TelOrange

private const val FRONTEND_REPOSITORY_URL = "https://github.com/soranerai/vpnhide_next"
private const val BACKEND_REPOSITORY_URL = "https://github.com/soranerai/vpnhide_next_private"
private const val TELEGRAM_CONTACT_URL = "https://t.me/soranerai"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutProjectScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about_project_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                ProjectIntroCard()
            }
            item {
                ProjectSectionTitle(stringResource(R.string.settings_about_project_frontend_title))
            }
            item {
                ProjectInfoCard(
                    icon = Icons.Default.Share,
                    tint = TelBlue,
                    title = stringResource(R.string.settings_about_project_frontend_title),
                    description = stringResource(R.string.settings_about_project_frontend_desc),
                    link = stringResource(R.string.settings_about_project_frontend_link),
                    onClick = { openProjectLink(context, FRONTEND_REPOSITORY_URL) },
                )
            }
            item {
                ProjectSectionTitle(stringResource(R.string.settings_about_project_backend_title))
            }
            item {
                ProjectInfoCard(
                    icon = Icons.Default.Build,
                    tint = TelOrange,
                    title = stringResource(R.string.settings_about_project_backend_title),
                    description = stringResource(R.string.settings_about_project_backend_desc),
                    link = stringResource(R.string.settings_about_project_backend_link),
                    onClick = { openProjectLink(context, BACKEND_REPOSITORY_URL) },
                )
            }
            item {
                CompatibilityCard()
            }
            item {
                ProjectSectionTitle(stringResource(R.string.settings_about_project_contacts_title))
            }
            item {
                ProjectInfoCard(
                    icon = Icons.Default.VpnKey,
                    tint = TelCyan,
                    title = stringResource(R.string.settings_about_project_telegram_title),
                    description = stringResource(R.string.settings_about_project_telegram),
                    link = stringResource(R.string.settings_about_project_open_link),
                    onClick = { openProjectLink(context, TELEGRAM_CONTACT_URL) },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ProjectIntroCard() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = TelBlue.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, TelBlue.copy(alpha = 0.20f)),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = TelBlue)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "VPNHide Next",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_about_project_philosophy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ProjectInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    description: String,
    link: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            SettingsRowIcon(icon = icon, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(link, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CompatibilityCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = TelGreen.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, TelGreen.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = TelGreen)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_about_project_compatibility_title), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_about_project_compatibility),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openProjectLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
}
