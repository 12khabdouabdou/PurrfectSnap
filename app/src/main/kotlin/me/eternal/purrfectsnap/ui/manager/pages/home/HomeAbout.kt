package me.eternal.purrfectsnap.ui.manager.pages.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import me.eternal.purrfectsnap.R
import me.eternal.purrfectsnap.common.util.ktx.openLink
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.components.FloatingTopBar
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import android.os.SystemClock

class HomeAbout : Routes.Route() {
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val avenirNext = remember {
            FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium))
        }
        val scrollState = rememberScrollState()
        val aboutStory = remember {
            """
            PurrfectSnap was founded on 2nd of October, 2025, as a fork of SnapEnhance by ΞTΞRNAL with a vision to provide users the quality Snapchat experience they deserve. This app was just meant to be a minor update in the SnapEnhance repository, but it soon became a separate app wherein the contributors kept adding features. Then the developer <RSR/> joined the team, and this app soon became a huge success. We received much love and support and gained 1K+ downloads in just two days! We thank all users and contributors; without your support, we wouldn't have reached this place. We would also like to convey our huge thanks to rhunk, the lead developer of SnapEnhance, as without him, this app wouldn't even exist. We are immensely grateful to him. Lastly, we would like to thank all our admins, notably: CLASSIC GENIUS, Harry, SUJΛL, Zain & scrodingerspet, who were right there with us from the very beginning. We would also like to thank all testers, notably Leo & Toxic, who tested and reported bugs continuously. We are immensely grateful for your contribution.
            
            
            """.trimIndent()
        }
        val pagePadding = 16.dp
        val bottomPadding = routes.bottomPadding +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            24.dp
        val tapSource = remember { MutableInteractionSource() }
        val tapTimeoutMs = 1500L
        val tapCount = remember { mutableIntStateOf(0) }
        val lastTapTime = remember { mutableLongStateOf(0L) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = bottomPadding)
            ) {
                FloatingTopBar(
                    title = routeInfo.translatedKey?.value ?: "About",
                    onBack = { routes.navController.popBackStack() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .padding(horizontal = pagePadding)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(PurrfectPalette.panelGradient)
                            .padding(horizontal = 22.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "PurrfectSnap",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PurrfectPalette.textPrimary,
                            fontFamily = avenirNext,
                            modifier = Modifier.clickable(
                                interactionSource = tapSource,
                                indication = null
                            ) {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastTapTime.longValue > tapTimeoutMs) {
                                    tapCount.intValue = 0
                                }
                                tapCount.intValue += 1
                                lastTapTime.longValue = now
                                if (tapCount.intValue >= 5) {
                                    tapCount.intValue = 0
                                    routes.retroGame.navigate()
                                }
                            }
                        )
                        Text(
                            text = "An Xposed Module meant to enhance your Snapchat experience!",
                            fontSize = 13.sp,
                            color = PurrfectPalette.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Lead Developers",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DeveloperCard(
                                name = "ΞTΞRNAL",
                                imageRes = R.drawable.pfp_external,
                                avenirNext = avenirNext,
                                modifier = Modifier.weight(1f)
                            )
                            DeveloperCard(
                                name = "<RSR/>",
                                imageRes = R.drawable.pfp_rsr,
                                avenirNext = avenirNext,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier
                        .padding(horizontal = pagePadding)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(PurrfectPalette.cardOverlay)
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Our Story",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = aboutStory,
                            fontSize = 14.sp,
                            color = PurrfectPalette.textSecondary,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier
                        .padding(horizontal = pagePadding)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "With love, PurrfectSnap Team",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { context.androidContext.openLink("https://github.com/particle-box/PurrfectSnap") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF1B152E)
                                )
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_github),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "GitHub", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { context.androidContext.openLink("https://t.me/purrfectsnap_official") },
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Telegram", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    @Composable
    private fun DeveloperCard(
        name: String,
        imageRes: Int,
        avenirNext: FontFamily,
        modifier: Modifier = Modifier
    ) {
        val cardShape = RoundedCornerShape(20.dp)
        val imageRing = Brush.linearGradient(
            listOf(
                PurrfectPalette.glowPrimary,
                PurrfectPalette.glowSecondary
            )
        )

        Surface(
            modifier = modifier,
            shape = cardShape,
            color = Color.White.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .border(2.dp, imageRing, CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = imageRes),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = avenirNext,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
}
        }
    }
}
