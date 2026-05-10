package dev.soranerai.vpnhidenext

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.faq_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FaqSection(
                title = stringResource(R.string.apps_help_title),
                content = {
                    Text(
                        text = annotatedStringResource(R.string.apps_hint_toggles),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = annotatedStringResource(R.string.apps_hint_restart_target),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = annotatedStringResource(R.string.apps_hint_zygisk),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            FaqSection(
                title = stringResource(R.string.bypass_help_title),
                content = {
                    Text(
                        text = annotatedStringResource(R.string.bypass_hint_logic),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.bypass_hint_kmod),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )


            FaqSection(
                title = stringResource(R.string.ports_help_title),
                content = {
                    Text(
                        text = stringResource(R.string.ports_hint_role),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FaqSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
fun annotatedStringResource(id: Int): AnnotatedString {
    val context = LocalContext.current
    return remember(id) {
        val spanned = context.getText(id)
        if (spanned is Spanned) {
            buildAnnotatedString {
                append(spanned.toString())
                spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
                    val start = spanned.getSpanStart(span)
                    val end = spanned.getSpanEnd(span)
                    when (span) {
                        is StyleSpan -> {
                            if (span.style == Typeface.BOLD) {
                                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                            }
                        }
                    }
                }
            }
        } else {
            AnnotatedString(spanned.toString())
        }
    }
}
