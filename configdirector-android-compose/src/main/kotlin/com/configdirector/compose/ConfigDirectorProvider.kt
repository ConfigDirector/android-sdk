package com.configdirector.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.configdirector.ConfigDirectorClient

/**
 * The client every binding in this artifact reads from, supplied by [ConfigDirectorProvider].
 *
 * Reading it without a provider above it in the tree is a programming error rather than something
 * to recover from, so it fails rather than serving default values that look like real ones.
 */
public val LocalConfigDirectorClient: ProvidableCompositionLocal<ConfigDirectorClient> =
    staticCompositionLocalOf {
        error(
            "No ConfigDirectorClient was provided. Wrap your content in " +
                "ConfigDirectorProvider(client) { ... }, or pass a client to each binding.",
        )
    }

/**
 * Supplies [client] to everything composed inside [content].
 *
 * ```kotlin
 * setContent {
 *     ConfigDirectorProvider(application.client) {
 *         SampleScreen()
 *     }
 * }
 * ```
 */
@Composable
public fun ConfigDirectorProvider(
    client: ConfigDirectorClient,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalConfigDirectorClient provides client, content = content)
}
