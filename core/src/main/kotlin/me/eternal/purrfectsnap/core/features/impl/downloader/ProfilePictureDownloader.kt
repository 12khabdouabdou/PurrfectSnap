package me.eternal.purrfectsnap.core.features.impl.downloader

import android.annotation.SuppressLint
import android.widget.Button
import android.widget.RelativeLayout
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfectsnap.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.ViewAppearanceHelper

class ProfilePictureDownloader : Feature("ProfilePictureDownloader") {
    @SuppressLint("SetTextI18n")
    override fun init() {
        if (!context.config.downloader.downloadProfilePictures.get()) return

        var friendUsername: String? = null
        var backgroundUrl: String? = null
        var avatarUrl: String? = null

        onNextActivityCreate(defer = true) {
            val profileViewClasses = setOf(
                "com.snap.unifiedpublicprofile.UnifiedPublicProfileView",
                "com.snap.modules.profile3.UserProfileV2RootComponent",
                "com.snap.profile.ui.flatland.UnifiedProfileFlatlandProfileView"
            )

            context.event.subscribe(AddViewEvent::class) { event ->
                if (event.view::class.java.name !in profileViewClasses) return@subscribe

                val buttonText = this@ProfilePictureDownloader.context.translation["profile_picture_downloader.button"]
                if ((0 until event.parent.childCount).any {
                    val child = event.parent.getChildAt(it)
                    child is Button && (child as Button).text == buttonText
                }) return@subscribe

                event.parent.addView(Button(event.parent.context).apply {
                    text = buttonText
                    layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 200, 0, 0)
                    }
                    setOnClickListener {
                        ViewAppearanceHelper.newAlertDialogBuilder(
                            this@ProfilePictureDownloader.context.mainActivity!!
                        ).apply {
                            setTitle(this@ProfilePictureDownloader.context.translation["profile_picture_downloader.title"])
                            val choices = mutableMapOf<String, String>()
                            backgroundUrl?.let { choices["background_option"] = it }
                            avatarUrl?.let { choices["avatar_option"] = it }

                            if (choices.isEmpty()) {
                                setMessage("No profile pictures available. Please wait for the profile to load.")
                                setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            } else {
                                setItems(choices.keys.map {
                                    this@ProfilePictureDownloader.context.translation["profile_picture_downloader.$it"]
                                }.toTypedArray()) { _, which ->
                                    runCatching {
                                        this@ProfilePictureDownloader.context.feature(MediaDownloader::class).downloadProfilePicture(
                                            choices.values.elementAt(which),
                                            friendUsername ?: "unknown"
                                        )
                                    }.onFailure {
                                        this@ProfilePictureDownloader.context.log.error("Failed to download profile picture", it)
                                    }
                                }
                            }
                        }.show()
                    }
                })
            }


            fun parseProfileData(buffer: ByteArray) {
                runCatching {
                    ProtoReader(buffer).followPath(1, 1, 2) {
                        friendUsername = getString(2) ?: return@followPath
                        followPath(4) {
                            backgroundUrl = getString(2)
                            avatarUrl = getString(100)
                        }
                    }
                }.onFailure {
                    context.log.error("Failed to parse profile picture data", it)
                }
            }

            context.event.subscribe(NetworkApiRequestEvent::class) { event ->
                if (!event.url.contains("getPublicProfile")) return@subscribe
                event.onSuccess { buffer -> buffer?.let { parseProfileData(it) } }
            }

            context.event.subscribe(UnaryCallEvent::class) { event ->
                if (!event.uri.contains("getPublicProfile")) return@subscribe
                event.addResponseCallback {
                    parseProfileData(buffer)
                }
            }
        }
    }
}