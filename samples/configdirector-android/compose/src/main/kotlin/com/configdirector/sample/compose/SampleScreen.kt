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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.configdirector.ConfigDirectorContext
import com.configdirector.compose.LocalConfigDirectorClient
import com.configdirector.compose.configContext
import com.configdirector.compose.configValue
import com.configdirector.compose.isClientReady
import kotlinx.coroutines.launch

/**
 * Every config is read with `configValue` from the Compose artifact, which subscribes to the client
 * and recomposes this screen when a value changes -- from an edit in the dashboard, or from the
 * context update the chips below make.
 */
@Composable
fun SampleScreen(hasSdkKey: Boolean = true, modifier: Modifier = Modifier) {
    val featureFlag = configValue("temporary-feature-flag", true)
    val killSwitch = configValue("permanent-kill-switch", false)
    val integerConfig = configValue("integer-config", 10)
    val dayOfTheWeek = configValue("day-of-the-week-config", "Friday")
    val jsonValue = configValue("json-value-config", "{}")
    val integerAsDouble = configValue("integer-config", 0.0)
    val jsonDocument = configValue("json-value-config", emptyMap<String, Any?>())

    val isReady = isClientReady()
    val context = configContext()

    val client = LocalConfigDirectorClient.current
    var selectedUser by remember { mutableStateOf(SampleUser.CONFIGURED) }
    val scope = rememberCoroutineScope()

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

        ConfigRow("temporary-feature-flag", featureFlag.toString())
        ConfigRow("permanent-kill-switch", killSwitch.toString())
        ConfigRow("integer-config", integerConfig.toString())
        ConfigRow("day-of-the-week-config", dayOfTheWeek)
        ConfigRow("json-value-config", jsonValue)
        ConfigRow("integer-config as a double", integerAsDouble.toString())
        ConfigRow("json-value-config as a map", jsonDocument.toString())

        HorizontalDivider()

        SectionHeader(title = "Context")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleUser.entries.forEach { user ->
                FilterChip(
                    selected = user == selectedUser,
                    onClick = {
                        selectedUser = user
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
