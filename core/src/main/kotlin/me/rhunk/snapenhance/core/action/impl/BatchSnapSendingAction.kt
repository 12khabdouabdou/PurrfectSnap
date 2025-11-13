package me.rhunk.snapenhance.core.action.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.util.protobuf.ProtoWriter
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.FriendLinkType
import me.rhunk.snapenhance.common.database.impl.FriendInfo
import me.rhunk.snapenhance.common.messaging.MessagingConstraints
import me.rhunk.snapenhance.common.messaging.MessagingTask
import me.rhunk.snapenhance.common.messaging.MessagingTaskType
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.util.ktx.copyToClipboard
import me.rhunk.snapenhance.common.util.snap.BitmojiSelfie
import me.rhunk.snapenhance.core.action.AbstractAction
import me.rhunk.snapenhance.core.event.events.impl.ActivityResultEvent
import me.rhunk.snapenhance.core.features.impl.experiments.BetterLocation
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.wrapper.impl.MessageDestinations
import me.rhunk.snapenhance.core.util.CallbackBuilder
import me.rhunk.snapenhance.mapper.impl.CallbackMapper
import me.rhunk.snapenhance.core.wrapper.AbstractWrapper
import me.rhunk.snapenhance.core.ui.ViewAppearanceHelper
import me.rhunk.snapenhance.core.util.EvictingMap
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import kotlin.random.Random

class BatchSnapSendingAction : AbstractAction() {
    private var pendingPickerAction: Pair<Int, (data: Uri) -> Unit>? = null
    private val translation by lazy { context.translation.getCategory("batch_snap_sending_action") }
    private val BATCH_SIZE = 200
    private val DELAY_BETWEEN_BATCHES = 2000L

    @Composable
    private fun ConfirmationDialog(
        message: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(text = translation["confirmation_dialog.title"]) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(text = context.translation["button.positive"])
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    Text(text = context.translation["button.negative"])
                }
            }
        )
    }

    private fun filterFriends(friends: List<FriendInfo>): List<FriendInfo> {
        val userIdBlacklist = arrayOf(
            context.database.myUserId,
            "b42f1f70-5a8b-4c53-8c25-34e7ec9e6781",
            "84ee8839-3911-492d-8b94-72dd80f3713a",
        )
        return friends.filter { friend ->
            friend.userId !in userIdBlacklist && 
            friend.friendLinkType == FriendLinkType.MUTUAL.value && 
            friend.addedTimestamp > 0
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BatchSnapSendingDialog() {
        var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
        var selectedFriends by remember { mutableStateOf<Set<String>>(emptySet()) }
        var allFriends by remember { mutableStateOf<List<FriendInfo>>(emptyList()) }
        var showConfirmation by remember { mutableStateOf(false) }
        var messageContent by remember { mutableStateOf("") }
        val bitmojiCache = remember { EvictingMap<String, Bitmap>(50) }
        val noBitmojiBitmap = remember { BitmapFactory.decodeResource(context.resources, android.R.drawable.ic_menu_report_image).asImageBitmap() }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                allFriends = filterFriends(context.database.getAllFriends())
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = translation["dialog_title"],
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedFriends = allFriends.mapNotNull { it.userId }.toSet()
                    }
                ) {
                    Text(text = translation["select_all"])
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedFriends = emptySet()
                    }
                ) {
                    Text(text = translation["deselect_all"])
                }
            }

            // Media picker
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    pendingPickerAction = kotlin.random.Random.nextInt(0, 65535) to { data ->
                        selectedMediaUri = data
                    }
                    context.mainActivity?.startActivityForResult(
                        Intent.createChooser(
                            Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" },
                            "Select media to send"
                        ),
                        pendingPickerAction!!.first
                    )
                }) {
                    Text(text = translation["pick_media"] ?: "Pick Media")
                }

                Text(
                    text = selectedMediaUri?.lastPathSegment ?: translation["no_media_selected"] ?: "No media selected",
                    modifier = Modifier.weight(1f).padding(8.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .padding(8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            ) {
                if (allFriends.isEmpty()) {
                    item {
                        Text(
                            text = translation["no_friends"],
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(allFriends, key = { it.userId!! }) { friendInfo ->
                        var bitmojiBitmap by remember(friendInfo) { mutableStateOf(bitmojiCache[friendInfo.bitmojiAvatarId]) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    friendInfo.userId?.let { userId ->
                                        selectedFriends = if (selectedFriends.contains(userId)) {
                                            selectedFriends - userId
                                        } else {
                                            selectedFriends + userId
                                        }
                                    }
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = friendInfo.displayName?.takeIf { it.isNotBlank() } ?: friendInfo.mutableUsername ?: "Unknown",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "@",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Light
                                )
                            }

                            if (selectedFriends.contains(friendInfo.userId)) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            TextField(
                value = messageContent,
                onValueChange = { messageContent = it },
                label = { Text(translation["message_hint"]) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                maxLines = 4
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showConfirmation = true
                    },
                    enabled = selectedFriends.isNotEmpty() && messageContent.isNotBlank()
                ) {
                    Text(text = translation["send_message_batch"])
                }
            }

            if (showConfirmation) {
                ConfirmationDialog(
                    message = translation.format(
                        "confirmation_message",
                        "count" to selectedFriends.size.toString(),
                        "batches" to ((selectedFriends.size + BATCH_SIZE - 1) / BATCH_SIZE).toString()
                    ),
                    onConfirm = {
                        showConfirmation = false
                        coroutineScope.launch {
                            sendMessageInBatches(selectedFriends.toList(), messageContent, selectedMediaUri)
                        }
                    },
                    onCancel = {
                        showConfirmation = false
                    }
                )
            }
        }
    }

    private suspend fun sendMessageInBatches(userIds: List<String>, message: String, mediaUri: Uri? = null) {
        val ctx = context.androidContext
        val batches = userIds.chunked(BATCH_SIZE)

        val statusTextView = TextView(ctx)
        val dialog = withContext(Dispatchers.Main) {
            ViewAppearanceHelper.newAlertDialogBuilder(ctx)
                .setTitle("Sending Messages...")
                .setView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(statusTextView.apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                    })
                    addView(ProgressBar(ctx))
                })
                .setCancelable(false)
                .show()
        }

        try {
            batches.forEachIndexed { batchIndex, batch ->
                withContext(Dispatchers.Main) {
                    statusTextView.text = translation.format(
                        "batch_progress",
                        "current" to (batchIndex + 1).toString(),
                        "total" to batches.size.toString(),
                        "count" to batch.size.toString()
                    )
                }

                context.feature(Messaging::class).conversationManager?.getOneOnOneConversationIds(
                    batch,
                    onSuccess = { conversations ->
                        val conversationIds = conversations.map { it.second }
                        
                        if (conversationIds.isNotEmpty()) {
                            try {
                                if (mediaUri != null) {
                                    val mime = ctx.contentResolver.getType(mediaUri)
                                    val isVideo = mime?.startsWith("video") == true
                                    val chunkWidth = 1080
                                    val chunkHeight = 1920
                                    val contentBytes = ProtoWriter().apply {
                                        from(11) {
                                            from(5) {
                                                from(1) {
                                                    from(1) {
                                                        addVarInt(2, 0)
                                                        addVarInt(12, 0)
                                                        addVarInt(15, 0)
                                                        addVarInt(16, chunkWidth)
                                                        addVarInt(17, chunkHeight)
                                                    }
                                                    addVarInt(6, if (isVideo) 1 else 0)
                                                }
                                                from(2) {}
                                            }
                                            from(22) {}
                                        }
                                    }.toByteArray()

                                    val contentArray = contentBytes.joinToString(",") { it.toString() }
                                    val localRefArray = mediaUri.toString().toByteArray().joinToString(",") { it.toString() }

                                    val localMessageContentTemplate = """
                                    {
                                        "mAllowsTranscription": false,
                                        "mBotMention": false,
                                        "mContent": [${contentArray}],
                                        "mContentType": "SNAP",
                                        "mIncidentalAttachments": [],
                                        "mLocalMediaReferences": [{"mId": [${localRefArray}]}],
                                        "mPlatformAnalytics": {
                                            "mAttemptId": null,
                                            "mContent": null,
                                            "mMetricsMessageMediaType": "NO_MEDIA",
                                            "mMetricsMessageType": "TEXT",
                                            "mReactionSource": "NONE"
                                        },
                                        "mSavePolicy": "LIFETIME"
                                    }
                                    """.trimIndent()

                                    val sendMethod = context.classCache.conversationManager.declaredMethods.first { it.name == "sendMessageWithContent" }
                                    val localMessageContent = context.gson.fromJson(localMessageContentTemplate, context.classCache.localMessageContent)
                                    val snapUUIDs = conversationIds.map { convId -> SnapUUID.fromString(convId) }
                                    val messageDestinations = MessageDestinations(AbstractWrapper.newEmptyInstance(context.classCache.messageDestinations)).also {
                                        it.conversations = snapUUIDs.toCollection(ArrayList())
                                        it.mPhoneNumbers = arrayListOf<Any>()
                                        it.stories = arrayListOf<Any>()
                                    }

                                    val callbackClass = runCatching {
                                        var cls: Class<*>? = null
                                        context.mappings.useMapper(CallbackMapper::class) {
                                            cls = callbacks.getClass("SendMessageCallback")
                                        }
                                        cls
                                    }.getOrNull()

                                    val callbackObj = callbackClass?.let { CallbackBuilder(it).build() }

                                    sendMethod.invoke(context.feature(Messaging::class).conversationManager?.instanceNonNull(), messageDestinations.instanceNonNull(), localMessageContent, callbackObj)
                                } else {
                                    val snapUUIDs = conversationIds.map { convId -> SnapUUID.fromString(convId) }
                                    context.feature(Messaging::class).messageSender?.sendChatMessage(
                                        snapUUIDs,
                                        message,
                                        onError = { error -> context.log.error("Failed to send message: $error") },
                                        onSuccess = { context.log.info("Batch ${batchIndex + 1} sent") }
                                    )
                                }
                            } catch (e: Exception) {
                                context.log.error("Error processing batch $batchIndex", e)
                            }
                        }
                    },
                    onError = { error ->
                        context.log.error("Failed to get conversation IDs: Impossible de charger le fichier C:\Users\toshiba\AppData\Local\Programs\Microsoft VS Code\resources\app\out\vs\workbench\contrib\terminal\common\scripts\shellIntegration.ps1, car l’exécution de scripts est désactivée sur ce système. Pour plus d’informations, consultez about_Execution_Policies à l’adresse https://go.microsoft.com/fwlink/?LinkID=135170.")
                        context.shortToast("Failed to get conversations for batch }{batchIndex + 1}: Impossible de charger le fichier C:\Users\toshiba\AppData\Local\Programs\Microsoft VS Code\resources\app\out\vs\workbench\contrib\terminal\common\scripts\shellIntegration.ps1, car l’exécution de scripts est désactivée sur ce système. Pour plus d’informations, consultez about_Execution_Policies à l’adresse https://go.microsoft.com/fwlink/?LinkID=135170.")
                    }
                )

                if (batchIndex < batches.size - 1) {
                    delay(DELAY_BETWEEN_BATCHES)
                }
            }

            withContext(Dispatchers.Main) {
                statusTextView.text = translation["sending_complete"]
            }

            delay(1000)
        } finally {
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                context.shortToast(translation.format("sent_to_friends", "count" to userIds.size.toString(), "batches" to batches.size.toString()))
            }
        }
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            createComposeAlertDialog(context.mainActivity!!) {
                BatchSnapSendingDialog()
            }.apply {
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }

    override fun onActivityCreate() {
        context.event.subscribe(ActivityResultEvent::class) { event ->
            if (event.requestCode == pendingPickerAction?.first) {
                val pendingAction = pendingPickerAction ?: return@subscribe
                this.pendingPickerAction = null
                event.canceled = true
                pendingAction.second(event.intent.data!!)
            }
        }
    }
}
