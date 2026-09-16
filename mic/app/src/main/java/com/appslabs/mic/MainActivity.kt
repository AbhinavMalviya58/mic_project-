package com.appslabs.mic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.appslabs.mic.ui.AudioRouterScreen
import com.appslabs.mic.ui.MainViewModel
import com.appslabs.mic.ui.theme.MicTheme

class MainActivity : ComponentActivity() {

    // viewModels() survives rotation — MainViewModel.onCleared() is called only
    // when the Activity is genuinely finished, which triggers AudioPlaybackManager.release().
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MicTheme {
                AudioRouterScreen(viewModel = viewModel)
            }
        }
    }
}