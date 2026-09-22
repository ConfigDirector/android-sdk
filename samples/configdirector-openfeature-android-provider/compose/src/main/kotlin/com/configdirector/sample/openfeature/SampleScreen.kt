package com.configdirector.sample.openfeature

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents

/**
 * Every flag is read through the OpenFeature client with its details, so the screen shows the
 * reason and the variant alongside the value. The reads are repeated whenever the provider reports
 * a configuration change or the SDK status moves, which is what makes an edit in the dashboard, or
 * the context change the chips below make, reach the screen.
 */
@Composable
fun SampleScreen(hasSdkKey: Boolean = true, modifier: Modifier = Modifier) {
    val status by OpenFeatureAPI.statusFlow.collectAsState(initial = OpenFeatureStatus.NotReady)
    var generation by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderConfigurationChanged>()
            .collect { generation++ }
    }
    LaunchedEffect(status) {
        generation++
    }

    val flags = remember(generation) { readFlags() }
    val context = remember(generation) { OpenFeatureAPI.getEvaluationContext() }
    var selectedUser by remember { mutableStateOf(SampleUser.CONFIGURED) }

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
                    "local.properties; until then every flag below is its default value.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionHeader(title = "Flags", trailing = status.label())

        flags.forEach { flag -> FlagRow(flag) }

        HorizontalDivider()

        SectionHeader(title = "Context")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleUser.entries.forEach { user ->
                FilterChip(
                    selected = user == selectedUser,
                    onClick = {
                        selectedUser = user
                        OpenFeatureAPI.setEvaluationContext(user.context)
                    },
                    label = { Text(user.label) },
                )
            }
        }

        val detail = context?.let { "targetingKey=${it.getTargetingKey()}, ${it.asObjectMap()}" }
        Text(detail ?: "No context", style = MaterialTheme.typography.bodySmall)
    }
}

private class Flag(val label: String, val value: String, val detail: String)

private fun readFlags(): List<Flag> {
    val client = OpenFeatureAPI.getClient()

    return listOf(
        client.getBooleanDetails("temporary-feature-flag", true).toFlag(),
        client.getBooleanDetails("permanent-kill-switch", false).toFlag(),
        client.getIntegerDetails("integer-config", 10).toFlag(),
        client.getStringDetails("day-of-the-week-config", "Friday").toFlag(),
        client.getStringDetails("json-value-config", "{}").toFlag(),
        client.getDoubleDetails("integer-config", 0.0).toFlag(" as a double"),
        client.getObjectDetails("json-value-config", Value.Structure(emptyMap())).toFlag(" as a structure"),
    )
}

private fun <T> FlagEvaluationDetails<T>.toFlag(suffix: String = ""): Flag = Flag(
    label = flagKey + suffix,
    value = value.toString(),
    detail = listOfNotNull(reason, variant, errorCode?.name, errorMessage).joinToString(" · "),
)

private fun OpenFeatureStatus.label(): String = when (this) {
    OpenFeatureStatus.NotReady -> "Connecting…"
    OpenFeatureStatus.Ready -> "Ready"
    OpenFeatureStatus.Reconciling -> "Reconciling…"
    OpenFeatureStatus.Stale -> "Stale"
    is OpenFeatureStatus.Error -> "Error"
    is OpenFeatureStatus.Fatal -> "Fatal"
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
private fun FlagRow(flag: Flag) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(flag.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(flag.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Text(flag.detail, style = MaterialTheme.typography.bodySmall)
    }
}
