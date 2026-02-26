package me.eternal.purrfectsnap.core.features.impl.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfectsnap.core.event.events.impl.BindViewEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.ui.ViewAppearanceHelper
import me.eternal.purrfectsnap.core.ui.children
import me.eternal.purrfectsnap.core.util.EvictingMap
import java.text.SimpleDateFormat
import java.util.*

class SpotlightCommentsUsername : Feature("SpotlightCommentsUsername") {
    private val usernameCache = EvictingMap<String, String>(150)

    @SuppressLint("SetTextI18n")
    override fun init() {
        if (!context.config.global.spotlightCommentsUsername.get()) return

        onNextActivityCreate(defer = true) {
            val messaging = context.feature(Messaging::class)
            context.event.subscribe(BindViewEvent::class) { event ->
                val posterUserId = event.prevModel.toString().takeIf { it.startsWith("Comment") }
                    ?.substringAfter("posterUserId=")?.substringBefore(",")?.substringBefore(")") ?: return@subscribe

                if (posterUserId == "null") return@subscribe

                fun setUserInfo(username: String) {
                    usernameCache[posterUserId] = username
                    val commentsCreatorBadgeTimestamp = (event.view as ViewGroup).children().filterIsInstance<TextView>()
                        .getOrNull(1) ?: return
                    
                    val customIcon = context.config.global.spotlightCommentsUsernameIcon.get().takeIf { it.isNotBlank() } ?: "[👤]"
                    val exclamationIcon = "  $customIcon"
                    val spannableString = SpannableString(exclamationIcon + commentsCreatorBadgeTimestamp.text.toString())
                    
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            showUserInfoDialog(posterUserId, username)
                        }
                        
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                    }
                    
                    spannableString.setSpan(clickableSpan, 0, exclamationIcon.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    
                    commentsCreatorBadgeTimestamp.text = spannableString
                    commentsCreatorBadgeTimestamp.movementMethod = LinkMovementMethod.getInstance()
                    commentsCreatorBadgeTimestamp.setTextColor(Color.WHITE)
                }

                event.view.post {
                    usernameCache[posterUserId]?.let {
                        setUserInfo(it)
                        return@post
                    }

                    context.coroutineScope.launch {
                        val username = runCatching {
                            messaging.fetchSnapchatterInfos(listOf(posterUserId)).firstOrNull()
                        }.onFailure {
                            context.log.error("Failed to fetch snapchatter info for user $posterUserId", it)
                        }.getOrNull()?.username ?: return@launch

                        withContext(Dispatchers.Main) {
                            setUserInfo(username)
                        }
                    }
                }
            }
        }
    }
    
    private fun showUserInfoDialog(userId: String, username: String) {
        context.coroutineScope.launch {
            val messaging = context.feature(Messaging::class)
            val userInfo = runCatching {
                messaging.fetchSnapchatterInfos(listOf(userId)).firstOrNull()
            }.onFailure {
                context.log.error("Failed to fetch detailed user info for $userId", it)
            }.getOrNull()
            
            withContext(Dispatchers.Main) {
                val builder = ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
                builder.setTitle("User Information")
                 
                 val userInfoText = buildString {
                      append("Username: $username\n")
                      userInfo?.let { info ->
                          append("User ID: ${userId}\n")
                          append("Display Name: ${info.displayName ?: "Not available"}\n")
                      } ?: append("Unable to retrieve additional information")
                  }
                
                builder.setMessage(userInfoText)
                builder.setPositiveButton("OK") { dialog: DialogInterface, _: Int -> 
                    dialog.dismiss() 
                }
                
                val dialog = builder.create()
                dialog.show()
                
                // Make text selectable
                dialog.findViewById<TextView>(android.R.id.message)?.let { messageView ->
                    messageView.setTextIsSelectable(true)
                    messageView.typeface = Typeface.MONOSPACE
                }
            }
        }
    }
    
    private fun formatDate(timestamp: Long): String {
        return if (timestamp > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        } else {
            "Not available"
        }
    }
}
