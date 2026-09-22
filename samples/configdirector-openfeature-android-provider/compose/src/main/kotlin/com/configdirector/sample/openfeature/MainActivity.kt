package com.configdirector.sample.openfeature

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sample = application as SampleApplication
        setContent {
            MaterialTheme {
                SampleScreen(sample.hasSdkKey)
            }
        }
    }
}
