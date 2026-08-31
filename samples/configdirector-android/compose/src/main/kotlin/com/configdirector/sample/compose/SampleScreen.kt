package com.configdirector.sample.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.configdirector.ClientEvent
import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.events
import com.configdirector.values
import kotlinx.coroutines.launch

/**
 * Each config is read as a flow, which emits its current value and then every change. The flows are
 * remembered: building one on each recomposition would unsubscribe and resubscribe every frame.
 */
@Composable
fun SampleScreen(
    client: ConfigDirectorClient,
    hasSdkKey: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val darkMode by remember(client) { client.values("dark-mode", false) }
        .collectAsStateWithLifecycle(false)
    val welcomeMessage by remember(client) { client.values("welcome-message", "Hello") }
        .collectAsStateWithLifecycle("Hello")
    val maxItems by remember(client) { client.values("max-items", 10) }
        .collectAsStateWithLifecycle(10)
    val sampleRate by remember(client) { client.values("sample-rate", 0.0) }
        .collectAsStateWithLifecycle(0.0)
    val theme by remember(client) { client.values("theme", "{}") }
        .collectAsStateWithLifecycle("{}")
    val betaBanner by remember(client) { client.values("beta-banner", false) }
        .collectAsStateWithLifecycle(false)

    var isReady by remember(client) { mutableStateOf(client.isReady) }
    var context by remember(client) { mutableStateOf(client.context) }
    var selectedUser by remember { mutableStateOf(SampleUser.CONFIGURED) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client) {
        client.events.collect { event ->
            when (event) {
                is ClientEvent.Ready -> isReady = true
                is ClientEvent.ContextUpdated -> context = event.context
                is ClientEvent.ConfigsUpdated -> Unit
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // The app is edge to edge from targetSdk 35 on, so the content keeps clear of the
            // system bars itself.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasSdkKey) {
            Text(
                "No client SDK key configured. Add configdirector.clientSdkKey to " +
                    "local.properties; until then every config below is its default value.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionHeader(title = "Configs", trailing = if (isReady) "Ready" else "Connecting…")

        ConfigRow("dark-mode", darkMode.toString())
        ConfigRow("welcome-message", welcomeMessage)
        ConfigRow("max-items", maxItems.toString())
        ConfigRow("sample-rate", sampleRate.toString())
        ConfigRow("theme", theme)
        ConfigRow("beta-banner", "$betaBanner (no value served, so the default)")

        HorizontalDivider()

        SectionHeader(title = "Context")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleUser.entries.forEach { user ->
                FilterChip(
                    selected = user == selectedUser,
                    onClick = {
                        selectedUser = user
                        isReady = false
                        scope.launch { client.updateContext(user.context) }
                    },
                    label = { Text(user.label) },
                )
            }
        }

        ContextDetail(context)
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ConfigRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ContextDetail(context: ConfigDirectorContext?) {
    val detail = context?.let {
        "id=${it.id}, name=${it.name}, traits=${it.traits}, anonymous=${it.isAnonymous}"
    } ?: "No context"

    Text(detail, style = MaterialTheme.typography.bodySmall)
}
