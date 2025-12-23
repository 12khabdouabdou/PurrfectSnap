package me.eternal.purrfectsnap.ui.setup.screens.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.setup.screens.SetupScreen
import me.eternal.purrfectsnap.ui.util.scaleOnPress

enum class InstallMode { ROOT, NON_ROOT }

class InstallModeScreen(
    private val onModeChosen: (InstallMode) -> Unit
) : SetupScreen() {
    private var selectedMode: InstallMode? = null

    override fun init() {
        selectedMode = null
    }

    override fun onLeave() {
        selectedMode?.let(onModeChosen)
    }

    @Composable
    override fun Content() {
        var choice by remember { mutableStateOf(selectedMode) }

        LaunchedEffect(choice) {
            selectedMode = choice
            allowNext(choice != null)
        }

        SetupCard {
            StepTitle(
                title = "Choose your device",
                subtitle = "If you don't know, select Non-rooted device and proceed.",
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeOption(
                    title = "Rooted device",
                    subtitle = "Use Lsposed and skip auto patching.",
                    icon = Icons.Filled.VerifiedUser,
                    accent = Brush.horizontalGradient(
                        listOf(
                            PurrfectPalette.glowPrimary.copy(alpha = 0.45f),
                            PurrfectPalette.glowSecondary.copy(alpha = 0.55f)
                        )
                    ),
                    selected = choice == InstallMode.ROOT,
                    onClick = { choice = InstallMode.ROOT }
                )
                ModeOption(
                    title = "Non-rooted device",
                    subtitle = "Use included auto patcher to install patched Snapchat.",
                    icon = Icons.Filled.Shield,
                    accent = Brush.horizontalGradient(
                        listOf(
                            Color(0xFF7DD3FC),
                            Color(0xFF6366F1)
                        )
                    ),
                    selected = choice == InstallMode.NON_ROOT,
                    onClick = { choice = InstallMode.NON_ROOT }
                )
            }
        }
    }

    @Composable
    private fun ModeOption(
        title: String,
        subtitle: String,
        icon: ImageVector,
        accent: Brush,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val background = if (selected) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.04f)
        }
        val borderColor = if (selected) {
            Color.White.copy(alpha = 0.4f)
        } else {
            Color.White.copy(alpha = 0.18f)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .scaleOnPress(interactionSource)
                .clip(RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() },
            color = background,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        modifier = Modifier
                            .background(accent)
                            .clip(RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        color = PurrfectPalette.textSecondary,
                        lineHeight = 18.sp
                    )
                }
                if (selected) {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier
                                .background(accent)
                                .clip(CircleShape)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {}
                }
            }
        }
    }
}
