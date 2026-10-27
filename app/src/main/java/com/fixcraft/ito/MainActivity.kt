package com.fixcraft.ito

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.ui.ItoApp
import com.fixcraft.ito.ui.ItoTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val notice by model.notice.collectAsStateWithLifecycle()
            ItoTheme {
                ItoApp(state, notice, model)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as ItoApplication).connections.stop()
    }
}
