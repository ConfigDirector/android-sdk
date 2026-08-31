package com.configdirector.sample.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = (application as SampleApplication).client
        setContent {
            MaterialTheme {
                SampleScreen(client)
            }
        }
    }
}
