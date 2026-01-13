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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.delay
import me.eternal.purrfectsnap.ui.manager.components.AestheticDialog
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.setup.screens.SetupScreen
import me.eternal.purrfectsnap.ui.util.scaleOnPress

enum class InstallMode { ROOT, NON_ROOT }

class InstallModeScreen(
    private val onModeChosen: (InstallMode) -> Unit,
    private val onSkipAutoSetup: () -> Unit
) : SetupScreen() {
    private var selectedMode: InstallMode? = null
    private var skipAutoSetup = false

    override fun init() {
        selectedMode = null
        skipAutoSetup = false
    }

    override fun onLeave() {
        if (skipAutoSetup) return
    }

    @Composable
    override fun Content() {
        var choice by remember { mutableStateOf(selectedMode) }
        var skipSelected by remember { mutableStateOf(skipAutoSetup) }
        var showGuides by remember { mutableStateOf(true) }
        var timeout by remember { mutableIntStateOf(15) }

        LaunchedEffect(choice, skipSelected) {
            selectedMode = choice
            skipAutoSetup = skipSelected
            allowNext(choice != null || skipSelected)
            if (!skipSelected) {
                choice?.let(onModeChosen)
            }
        }

        LaunchedEffect(showGuides) {
            if (showGuides) {
                timeout = 15
                while (timeout > 0) {
                    delay(1000)
                    timeout--
                }
            }
        }

        if (showGuides) {
            val confirmLabel = if (timeout > 0) "I understand (${timeout}s)" else "I understand"
            AestheticDialog(
                onDismissRequest = { if (timeout == 0) showGuides = false },
                title = "Please note!",
                text = "",
                icon = Icons.Filled.Warning,
                confirmButtonText = confirmLabel,
                onConfirm = { if (timeout == 0) showGuides = false },
                confirmEnabled = timeout == 0,
                showCloseButton = false,
                customContent = {
                    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = PurrfectPalette.textSecondary,
                        lineHeight = 18.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PurrfectPalette.cardOverlayColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                                    PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                                )
                            )
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Select the type of device you have: rooted or non-rooted. If you are unsure, choose Non-root and continue.",
                                style = bodyStyle,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Non-rooted devices",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                            Text(
                                text = "Select Non-root and the app will handle everything. Tap Install Patched Snapchat when it appears. After it installs, do not open Snapchat yet. Continue the PurrfectSnap setup; once it finishes, you can open Snapchat and enjoy.",
                                style = bodyStyle,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Rooted devices",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                            Text(
                                text = "Make sure you have flashed LSPosed first. We recommend JingMatrix LSPosed or LSPosed Irena. After you select Root, the app will install the recommended Snapchat version. Do not open it yet; continue the PurrfectSnap setup. When setup finishes, enable PurrfectSnap in LSPosed and reboot your phone. Then start using Snapchat. We highly recommend detaching Snapchat from the Play Store with the Zygisk Detach module to prevent auto-updates.",
                                style = bodyStyle,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "If you run into any installation issues, the solution will appear here. Please read it carefully.",
                                style = bodyStyle,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                        append("Note: ")
                                    }
                                    append("New Accounts easily get locked! It is recommended to use an older account with PurrfectSnap.")
                                },
                                style = bodyStyle,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            )
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
                    onClick = {
                        choice = InstallMode.ROOT
                        skipSelected = false
                    }
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
                    onClick = {
                        choice = InstallMode.NON_ROOT
                        skipSelected = false
                    }
                )
            }
            val interactionSource = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .scaleOnPress(interactionSource)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        skipSelected = true
                        choice = null
                        onSkipAutoSetup()
                        goNext()
                    },
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Skip Auto Setup",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = PurrfectPalette.glowSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
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
