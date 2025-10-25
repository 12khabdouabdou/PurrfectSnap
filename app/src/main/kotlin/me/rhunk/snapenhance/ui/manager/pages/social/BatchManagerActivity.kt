package me.rhunk.snapenhance.ui.manager.pages.social

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import me.rhunk.snapenhance.core.ModContext
import me.rhunk.snapenhance.core.features.impl.messaging.BatchFriendSelector

class BatchManagerActivity : ComponentActivity() {
    
    private lateinit var modContext: ModContext
    private lateinit var batchFeature: BatchFriendSelector
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get mod context from application
        modContext = (application as? me.rhunk.snapenhance.bridge.BridgeClient)?.modContext
            ?: run {
                finish()
                return
            }
        
        batchFeature = modContext.feature(BatchFriendSelector::class)
        
        val sessionId = intent.getStringExtra("SESSION_ID") ?: run {
            finish()
            return
        }
        
        setContent {
            BatchManagerTheme {
                BatchManagerScreen(
                    sessionId = sessionId,
                    batchFeature = batchFeature,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
fun BatchManagerTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFFBB86FC),
        secondary = Color(0xFF03DAC6),
        tertiary = Color(0xFF3700B3),
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        error = Color(0xFFCF6679),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
        onError = Color.Black,
        primaryContainer = Color(0xFF3700B3),
        secondaryContainer = Color(0xFF005B4F),
        surfaceVariant = Color(0xFF2C2C2C)
    )
    
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}
