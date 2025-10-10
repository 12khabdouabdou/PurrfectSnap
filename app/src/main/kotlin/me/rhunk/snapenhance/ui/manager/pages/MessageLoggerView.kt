package me.rhunk.snapenhance.ui.manager.pages

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.bridge.DownloadCallback
import me.rhunk.snapenhance.common.bridge.wrapper.ConversationInfo
import me.rhunk.snapenhance.common.bridge.wrapper.LoggedMessage
import me.rhunk.snapenhance.common.bridge.wrapper.LoggerWrapper
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.download.DownloadMetadata
import me.rhunk.snapenhance.common.data.download.DownloadRequest
import me.rhunk.snapenhance.common.data.download.MediaDownloadSource
import me.rhunk.snapenhance.common.data.download.createNewFilePath
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.common.util.ktx.copyToClipboard
import me.rhunk.snapenhance.common.util.ktx.longHashCode
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.core.features.impl.downloader.decoder.DecodedAttachment
import me.rhunk.snapenhance.core.features.impl.downloader.decoder.MessageDecoder
import me.rhunk.snapenhance.download.DownloadProcessor
import me.rhunk.snapenhance.storage.findFriend
import java.text.DateFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

class MessageLoggerView : LoggerView<LoggedMessage>() {
    private lateinit var loggerWrapper: LoggerWrapper
    override val title: @Composable () -> Unit = { Text("Message Logger") }

    private inline fun decodeMessage(message: LoggedMessage, result: (contentType: ContentType, messageReader: ProtoReader, attachments: List<DecodedAttachment>) -> Unit) {
        runCatching {
            val messageObject = JsonParser.parseString(String(message.messageData, Charsets.UTF_8)).asJsonObject
            val messageContent = messageObject.getAsJsonObject("mMessageContent")
            val messageReader = messageContent.getAsJsonArray("mContent").map { it.asByte }.toByteArray().let { ProtoReader(it) }
            result(ContentType.fromMessageContainer(messageReader) ?: ContentType.UNKNOWN, messageReader, MessageDecoder.decode(messageContent))
        }.onFailure {
            context.log.error("Failed to decode message", it)
        }
    }

    private fun downloadAttachment(creationTimestamp: Long, attachment: DecodedAttachment) {
        context.shortToast("Download started!")
        val attachmentHash = attachment.mediaUniqueId!!.longHashCode().absoluteValue.toString()

        DownloadProcessor(
            remoteSideContext = context,
            callback = object: DownloadCallback.Default() {
                override fun onSuccess(outputPath: String?) {
                    context.shortToast("Downloaded to $outputPath")
                }

                override fun onFailure(message: String?, throwable: String?) {
                    context.shortToast("Failed to download $message")
                }
            }
        ).enqueue(
            DownloadRequest(
                inputMedias = arrayOf(attachment.createInputMedia()!!)
            ),
            DownloadMetadata(
                mediaIdentifier = attachmentHash,
                outputPath = createNewFilePath(
                    context.config.root,
                    attachment.mediaUniqueId!!,
                    MediaDownloadSource.MESSAGE_LOGGER,
                    attachmentHash,
                    creationTimestamp
                ),
                iconUrl = null,
                mediaAuthor = null,
                downloadSource = MediaDownloadSource.MESSAGE_LOGGER.translate(context.translation),
            )
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun LogItemView(item: LoggedMessage) {
        var contentView by remember { mutableStateOf<@Composable () -> Unit>({
            Spacer(modifier = Modifier.height(30.dp))
        }) }

        OutlinedCard(
            modifier = Modifier
                .padding(2.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                contentView()

                LaunchedEffect(Unit, item) {
                    runCatching {
                        decodeMessage(item) { contentType, messageReader, attachments ->
                            @Composable
                            fun ContentHeader() {
                                Text("${item.username} (${contentType.toString().lowercase()}) - ${DateFormat.getDateTimeInstance().format(item.sendTimestamp)}", modifier = Modifier.padding(end = 4.dp), fontWeight = FontWeight.ExtraLight)
                            }

                            if (contentType == ContentType.CHAT) {
                                val content = messageReader.getString(2, 1) ?: "[${translation["empty_message"]}]"
                                contentView = {
                                    Column {
                                        Text(content, modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(Unit) {
                                                detectTapGestures(onLongPress = {
                                                    context.androidContext.copyToClipboard(content)
                                                })
                                            })

                                        val edits by rememberAsyncMutableState(defaultValue = emptyList()) {
                                            loggerWrapper.getChatEdits(selectedScopeId!!, item.messageId)
                                        }
                                        edits.forEach { messageEdit ->
                                            val date = remember {
                                                DateFormat.getDateTimeInstance().format(messageEdit.timestamp)
                                            }
                                            Text(
                                                modifier = Modifier.pointerInput(Unit) {
                                                    detectTapGestures(onLongPress = {
                                                        context.androidContext.copyToClipboard(messageEdit.message)
                                                    })
                                                }.fillMaxWidth().padding(start = 4.dp),
                                                text = messageEdit.message + " (edited at $date)",
                                                fontWeight = FontWeight.Light,
                                                fontStyle = FontStyle.Italic,
                                                fontSize = 12.sp
                                            )
                                        }
                                        ContentHeader()
                                    }
                                }
                                return@runCatching
                            }
                            contentView = {
                                Column column@{
                                    if (attachments.isEmpty()) return@column

                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        attachments.forEachIndexed { index, attachment ->
                                            ElevatedButton(onClick = {
                                                context.coroutineScope.launch {
                                                    runCatching {
                                                        downloadAttachment(item.sendTimestamp, attachment)
                                                    }.onFailure {
                                                        context.log.error("Failed to download attachment", it)
                                                        context.shortToast(translation["download_attachment_failed_toast"])
                                                    }
                                                }
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Default.Download,
                                                    contentDescription = "Download",
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                                Text(translation.format("chat_attachment", "index" to (index + 1).toString()))
                                            }
                                        }
                                    }
                                    ContentHeader()
                                }
                            }
                        }
                    }.onFailure {
                        context.log.error("Failed to parse message", it)
                        contentView = {
                            Text("[${translation["message_parse_failed"]}]")
                        }
                    }
                }
            }
        }
    }

    override suspend fun getScopes(): List<Pair<String, String>> {
        val conversationInfoCache = ConcurrentHashMap<String, String?>()
        fun formatConversationInfo(conversationInfo: ConversationInfo?): String? {
            if (conversationInfo == null) return null

            return conversationInfo.groupTitle?.let {
                translation.format("list_group_format", "name" to it)
            } ?: conversationInfo.usernames.takeIf { it.size > 1 }?.let {
                translation.format("list_friend_format", "name" to ("(" + it.joinToString(", ") + ")"))
            } ?: context.database.findFriend(conversationInfo.conversationId)?.let {
                translation.format("list_friend_format", "name" to "(" + (conversationInfo.usernames + listOf(it.mutableUsername)).toSet().joinToString(", ") + ")")
            } ?: conversationInfo.usernames.firstOrNull()?.let {
                translation.format("list_friend_format", "name" to "($it)")
            }
        }
        return loggerWrapper.getAllConversations().map { it to (conversationInfoCache.getOrPut(it) { formatConversationInfo(loggerWrapper.getConversationInfo(it)) } ?: it) }
    }

    override suspend fun fetchLogs(
        scopeId: String,
        lastItem: LoggedMessage?,
        reverseOrder: Boolean,
        filter: String
    ): List<LoggedMessage> {
        return loggerWrapper.fetchMessages(scopeId, lastItem?.sendTimestamp ?: if (reverseOrder) Long.MAX_VALUE else 0, 30, reverseOrder) { messageData ->
            if (filter.isEmpty()) return@fetchMessages true
            var isMatch = false
            decodeMessage(messageData) { contentType, messageReader, _ ->
                if (contentType == ContentType.CHAT) {
                    val content = messageReader.getString(2, 1) ?: return@decodeMessage
                    isMatch = content.contains(filter, ignoreCase = true)
                }
            }
            isMatch
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { navBackStackEntry ->
        var initialized by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            loggerWrapper = LoggerWrapper(context.androidContext)
            initialized = true
        }
        if (initialized) {
            super.content.invoke(navBackStackEntry)
        }
    }
}