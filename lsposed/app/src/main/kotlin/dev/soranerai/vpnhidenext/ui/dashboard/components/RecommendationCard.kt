package dev.soranerai.vpnhidenext.ui.dashboard.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soranerai.vpnhidenext.R
import dev.soranerai.vpnhidenext.domain.models.NativeInstallRecommendation

@Composable
fun NativeInstallRecommendationCard(recommendation: NativeInstallRecommendation) {
    val darkTheme = isSystemInDarkTheme()
    val containerColor =
        if (recommendation.preferKmod) {
            if (darkTheme) Color(0xFF0D47A1).copy(alpha = 0.28f) else Color(0xFFE3F2FD)
        } else {
            if (darkTheme) Color(0xFF4E342E).copy(alpha = 0.32f) else Color(0xFFFFF3E0)
        }

    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor =
                    if (recommendation.preferKmod) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.dashboard_install_recommendation_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    stringResource(
                        R.string.dashboard_install_recommendation_device,
                        recommendation.androidVersion,
                        recommendation.kernelVersion,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )

            val kmiBranch = recommendation.kernelBranch
            if (kmiBranch != null && kmiBranch != recommendation.androidVersion) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            R.string.dashboard_install_recommendation_kmi_note,
                            kmiBranch.replace(" ", "").lowercase(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            val alternative = recommendation.alternativeArtifact
            Text(
                text =
                    when {
                        !recommendation.preferKmod -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod_unsupported,
                                recommendation.recommendedArtifact,
                            )
                        }

                        recommendation.variantAmbiguous && alternative != null -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod_ambiguous,
                                recommendation.recommendedArtifact,
                                alternative,
                            )
                        }

                        else -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod,
                                recommendation.recommendedArtifact,
                            )
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
