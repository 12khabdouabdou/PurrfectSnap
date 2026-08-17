package me.eternal.purrfect.common.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AphelionFriendMutationToast(
    icon: ImageVector,
    text: String,
    bitmojiUrl: String?,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var bitmojiBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(bitmojiUrl) {
        if (bitmojiUrl != null) {
            runCatching {
                me.eternal.purrfect.common.util.snap.RemoteMediaResolver.downloadMedia(bitmojiUrl) { inputStream, _ ->
                    bitmojiBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        visible = true
        delay(5000)
        visible = false
        delay(500)
        onDismiss()
    }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    val skin = me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin.current
    val cardColor = if (skin.isDark) Color(0xF20A0A0A) else Color(0xF2F5F5F7)
    val textColor = if (skin.isDark) Color.White else Color.Black
    val borderColor = if (skin.isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                translationY = -100f * (1f - progress)
                alpha = progress
                scaleX = 0.96f + (0.04f * progress)
                scaleY = 0.96f + (0.04f * progress)
            }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .heightIn(min = 78.dp)
                    .shadow(12.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = cardColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(textColor.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmojiBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmojiBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Text(
                        text = text,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
