package me.eternal.purrfectsnap.core.features.impl.downloader

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.common.ui.createComposeAlertDialog
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.PurrfectGlassCard
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayPalette
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayTheme

class ProfilePictureDownloader : Feature("ProfilePictureDownloader") {
    @SuppressLint("SetTextI18n")
    override fun init() {
        if (!context.config.downloader.downloadProfilePictures.get()) return

        var friendUsername: String? = null
        var backgroundUrl: String? = null
        var avatarUrl: String? = null

        onNextActivityCreate(defer = true) {
            context.event.subscribe(AddViewEvent::class) { event ->
                if (event.view::class.java.name != "com.snap.unifiedpublicprofile.UnifiedPublicProfileView") return@subscribe

                event.parent.addView(ImageButton(event.parent.context).apply {
                    val label = this@ProfilePictureDownloader.context.translation["profile_picture_downloader.button"]
                    val density = resources.displayMetrics.density
                    val sizePx = (44f * density).toInt()
                    contentDescription = label
                    setImageResource(android.R.drawable.stat_sys_download)
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#332A2452"))
                        setStroke(3, Color.parseColor("#66AFA3FF"))
                    }
                    setPadding(0, 0, 0, 0)
                    layoutParams = RelativeLayout.LayoutParams(
                        sizePx,
                        sizePx
                    ).apply {
                        setMargins(0, 200, 0, 0)
                    }
                    setOnClickListener {
                        val activity = this@ProfilePictureDownloader.context.mainActivity ?: return@setOnClickListener
                        val translation = this@ProfilePictureDownloader.context.translation
                        val options = buildList {
                            backgroundUrl?.let { add("background_option" to it) }
                            avatarUrl?.let { add("avatar_option" to it) }
                        }
                        createComposeAlertDialog(activity) { alertDialog ->
                            PurrfectOverlayTheme {
                                val dialogTitle = translation["profile_picture_downloader.title"]
                                    ?: "Profile Picture Downloader"
                                val subtitle = friendUsername ?: translation["profile_picture_downloader.subtitle"]
                                    ?: "Choose which image to download"
                                val border = remember {
                                    Brush.linearGradient(
                                        listOf(
                                            PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.55f),
                                            PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.35f)
                                        )
                                    )
                                }
                                val shape = RoundedCornerShape(26.dp)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp),
                                    shape = shape,
                                    color = ComposeColor.Transparent,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 18.dp,
                                    border = BorderStroke(1.dp, border)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .background(PurrfectOverlayPalette.cardOverlay, shape)
                                            .padding(horizontal = 18.dp, vertical = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = ComposeColor.White.copy(alpha = 0.08f)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Download,
                                                    contentDescription = null,
                                                    tint = ComposeColor.White,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = dialogTitle,
                                                    color = ComposeColor.White,
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                                Text(
                                                    text = subtitle,
                                                    color = PurrfectOverlayPalette.textSecondary,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                        if (options.isEmpty()) {
                                            Text(
                                                text = translation["profile_picture_downloader.no_images"]
                                                    ?: "No profile images found.",
                                                color = ComposeColor.White.copy(alpha = 0.8f),
                                                fontSize = 13.sp
                                            )
                                        } else {
                                            options.forEach { (key, url) ->
                                                val labelText = translation["profile_picture_downloader.$key"]
                                                val icon = if (key == "background_option") {
                                                    Icons.Outlined.Image
                                                } else {
                                                    Icons.Outlined.AccountCircle
                                                }
                                                Surface(
                                                    onClick = {
                                                        runCatching {
                                                            this@ProfilePictureDownloader.context.feature(MediaDownloader::class).downloadProfilePicture(
                                                                url,
                                                                friendUsername!!
                                                            )
                                                        }.onFailure {
                                                            this@ProfilePictureDownloader.context.log.error("Failed to download profile picture", it)
                                                        }
                                                        alertDialog.dismiss()
                                                    },
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = ComposeColor.White.copy(alpha = 0.05f),
                                                    border = BorderStroke(1.dp, ComposeColor.White.copy(alpha = 0.12f))
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Surface(
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = ComposeColor.White.copy(alpha = 0.08f)
                                                        ) {
                                                            Icon(
                                                                icon,
                                                                contentDescription = null,
                                                                tint = ComposeColor.White,
                                                                modifier = Modifier.padding(8.dp)
                                                            )
                                                        }
                                                        Text(
                                                            text = labelText ?: key,
                                                            color = ComposeColor.White,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 14.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }.show()
                    }
                })
            }


            context.event.subscribe(NetworkApiRequestEvent::class) { event ->
                if (!event.url.endsWith("/rpc/getPublicProfile")) return@subscribe
                event.onSuccess {  buffer ->
                    ProtoReader(buffer ?: return@onSuccess).followPath(1, 1, 2) {
                        friendUsername = getString(2) ?: return@followPath
                        followPath(4) {
                            backgroundUrl = getString(2)
                            avatarUrl = getString(100)
                        }
                    }
                }
            }
        }
    }
}
