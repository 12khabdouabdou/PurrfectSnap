package me.rhunk.snapenhance.core.action.impl

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.FriendLinkType
import me.rhunk.snapenhance.common.database.impl.FriendInfo
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.core.action.AbstractAction
import me.rhunk.snapenhance.core.event.events.impl.ActivityResultEvent
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.messaging.MessageSender
import me.rhunk.snapenhance.core.util.EvictingMap
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import kotlin.random.Random

class BatchSnapSendingAction : AbstractAction() {
    private var pendingPickerAction: Pair<Int, (data: Uri) -> Unit>? = null
    private val translation by lazy { context.translation.getCategory("batch_snap_sending_action") }
    // Batch size can be increased up to ~500, but 200 is safe for stability
    // This bypasses Snapchat's UI limit of 200 friends in send-to dialog by using unlimited programmatic selection
    private val BATCH_SIZE = 500  // Increased from 200 to support larger batches while still being safe
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    pendingPickerAction = Random.nextInt(0, 65535) to { data ->
                        selectedMediaUri = data
                    }
                    context.mainActivity?.startActivityForResult(
                        Intent.createChooser(
                            Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" },
                            "Select media"
                        ),
                        pendingPickerAction!!.first
                    )
                }) {
                    Text(text = "Pick Media")
                }

                Text(
                    text = selectedMediaUri?.lastPathSegment ?: "No media",
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
                                    text = "@${friendInfo.mutableUsername}",
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
                    enabled = selectedFriends.isNotEmpty() && (messageContent.isNotBlank() || selectedMediaUri != null)
                ) {
                    Text(text = translation["send_message_batch"])
                }
            }

            if (showConfirmation) {
                val batchCount = (selectedFriends.size + BATCH_SIZE - 1) / BATCH_SIZE
                ConfirmationDialog(
                    message = "Send to ${selectedFriends.size} friends in $batchCount batch(es)?\n\n" +
                        "This bypasses Snapchat's 200-friend send-to limit.\n" +
                        "Batches will be sent with 2-second delays between each.",
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
        val batches = userIds.chunked(BATCH_SIZE)
        val messageSender = MessageSender(context)

        batches.forEachIndexed { batchIndex, batch ->
            withContext(Dispatchers.Main) {
                context.shortToast("Sending batch ${batchIndex + 1}/${batches.size} (${batch.size} friends)")
            }

            context.feature(Messaging::class).conversationManager?.getOneOnOneConversationIds(
                batch,
                onSuccess = { conversations ->
                    val conversationIds = conversations.map { it.second }
                    
                    if (conversationIds.isNotEmpty()) {
                        val snapUUIDs = conversationIds.map { convId -> SnapUUID.fromString(convId) }

                        try {
                            if (mediaUri != null) {
                                messageSender.sendCustomChatMessage(
                                    snapUUIDs,
                                    ContentType.SNAP,
                                    {
                                        from(11) {
                                            from(5) {
                                                from(1) {
                                                    from(1) {
                                                        addVarInt(2, 0)
                                                        addVarInt(12, 0)
                                                        addVarInt(15, 0)
                                                        addVarInt(16, 1080)
                                                        addVarInt(17, 1920)
                                                    }
                                                    addVarInt(6, 1)
                                                }
                                                from(2) {
                                                    addVarInt(5, 1)
                                                    addVarInt(8, 10)
                                                }
                                            }
                                            from(22) {
                                                addVarInt(4, 5)
                                            }
                                        }
                                    },
                                    onError = { error -> 
                                        context.log.error("Batch ${batchIndex + 1}: Send failed: $error")
                                    },
                                    onSuccess = { 
                                        context.log.info("Batch ${batchIndex + 1}: Sent successfully")
                                    }
                                )
                            } else if (message.isNotBlank()) {
                                messageSender.sendChatMessage(
                                    snapUUIDs,
                                    message,
                                    onError = { error -> 
                                        context.log.error("Batch ${batchIndex + 1}: Send failed: $error")
                                    },
                                    onSuccess = { 
                                        context.log.info("Batch ${batchIndex + 1}: Sent successfully")
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            context.log.error("Batch $batchIndex error", e)
                        }
                    }
                },
                onError = { error ->
                    context.log.error("Batch ${batchIndex + 1}: Get conversations failed: $error")
                }
            )

            if (batchIndex < batches.size - 1) {
                delay(DELAY_BETWEEN_BATCHES)
            }
        }

        withContext(Dispatchers.Main) {
            context.shortToast("Sent to ${userIds.size} friends in ${batches.size} batches!")
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
