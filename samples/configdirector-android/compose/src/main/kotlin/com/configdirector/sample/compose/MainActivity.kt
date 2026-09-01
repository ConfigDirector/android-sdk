package com.configdirector.sample.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.configdirector.compose.ConfigDirectorProvider

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sample = application as SampleApplication
        setContent {
            // Every binding below reads the client from here rather than being handed it.
            ConfigDirectorProvider(sample.client) {
                MaterialTheme {
                    SampleScreen(sample.hasSdkKey)
                }
            }
        }
    }
}
