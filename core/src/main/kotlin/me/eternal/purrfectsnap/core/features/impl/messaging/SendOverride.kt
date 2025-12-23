package me.eternal.purrfectsnap.core.features.impl.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.robv.android.xposed.XC_MethodHook
import kotlinx.coroutines.*
import me.eternal.purrfectsnap.bridge.task.TaskListener
import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.ui.createComposeAlertDialog
import me.eternal.purrfectsnap.common.util.protobuf.ProtoEditor
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.common.util.protobuf.ProtoWriter
import me.eternal.purrfectsnap.core.event.events.impl.MediaUploadEvent
import me.eternal.purrfectsnap.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfectsnap.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.features.impl.experiments.MediaFilePicker
import me.eternal.purrfectsnap.core.messaging.MessageSender
import me.eternal.purrfectsnap.core.util.ktx.getObjectFieldOrNull
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration


@OptIn(ExperimentalMaterial3Api::class)
class SendOverride : Feature("Send Override") {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "scheduled_send"
    }
    
    private var selectedType by mutableStateOf("SNAP")
    private var customDuration by mutableFloatStateOf(10f)
    private var scheduledTime by mutableStateOf<Long?>(null)
    private var showClockPicker by mutableStateOf(false)
    private var clockPickerHour by mutableIntStateOf(12)
    private var clockPickerMinute by mutableIntStateOf(0)
    private var notificationIdCounter = 1000
    private val backgroundHookLock = Any()
    private var backgroundHookRefs = 0
    private var backgroundHooks: List<XC_MethodHook.Unhook>? = null

    private fun acquireScheduledSendBackground(): () -> Unit {
        if (!context.config.messaging.scheduledSendAllowRunningInBackground.get()) return {}
        var enableFailed = false
        synchronized(backgroundHookLock) {
            backgroundHookRefs++
            if (backgroundHookRefs == 1) {
                if (!enableScheduledSendBackgroundLocked()) {
                    backgroundHookRefs--
                    enableFailed = true
                }
            }
        }
        if (enableFailed) return {}
        var released = false
        return {
            synchronized(backgroundHookLock) {
                if (released) return@synchronized
                released = true
                if (backgroundHookRefs > 0) backgroundHookRefs--
                if (backgroundHookRefs == 0) {
                    backgroundHooks?.forEach { it.unhook() }
                    backgroundHooks = null
                }
            }
        }
    }

    private fun enableScheduledSendBackgroundLocked(): Boolean {
        return runCatching {
            val duplexClass = findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy")
            val appStateMethod = duplexClass.methods.firstOrNull { it.name == "appStateChanged" } ?: return false
            val hooks = mutableListOf<XC_MethodHook.Unhook>()
            hooks.addAll(
                duplexClass.hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (param.arg<Any>(0).toString() == "INACTIVE") param.setResult(null)
                }
            )
            hooks.addAll(
                duplexClass.hookConstructor(HookStage.AFTER) { param ->
                    val activeValue = appStateMethod.parameterTypes[0].enumConstants?.firstOrNull { it.toString() == "ACTIVE" }
                        ?: return@hookConstructor
                    appStateMethod.invoke(param.thisObject(), activeValue)
                }
            )
            backgroundHooks = hooks
            true
        }.getOrElse {
            context.log.error("Failed to enable scheduled send background mode", it)
            false
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Scheduled Send",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notifications for scheduled snap sends"
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showNotification(title: String, content: String) {
        val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context.androidContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        notificationManager.notify(notificationIdCounter++, builder.build())
    }

    @OptIn(ExperimentalLayoutApi::class)
    override fun init() {
        createNotificationChannel()
        
        val stripMediaMetadata = context.config.messaging.stripMediaMetadata.get()
        var postSavePolicy: Int? = null

        val configOverrideType = context.config.messaging.galleryMediaSendOverride.getNullable()
        if (configOverrideType == null && stripMediaMetadata.isEmpty()) return

        context.event.subscribe(MediaUploadEvent::class) { event ->
            ProtoReader(event.localMessageContent.content!!).followPath(11, 5)?.let { snapDocPlayback ->
                event.onMediaUploaded { result ->
                    result.messageContent.content = ProtoEditor(result.messageContent.content!!).apply {
                        edit(11, 5) {
                            edit(1) {
                                edit(1) {
                                    snapDocPlayback.getVarInt(2, 99)?.let { customDuration ->
                                        remove(15)
                                        addVarInt(15, customDuration)
                                    }
                                    remove(27)
                                    remove(26)
                                    addBuffer(26, byteArrayOf())
                                }
                            }

                            // set back the original snap duration
                            snapDocPlayback.getByteArray(2)?.let {
                                val originalHasSound = firstOrNull(2)?.toReader()?.getVarInt(5)
                                remove(2)
                                addBuffer(2, it)

                                originalHasSound?.let { hasSound ->
                                    edit(2) {
                                        remove(5)
                                        addVarInt(5, hasSound)
                                    }
                                }
                            }
                        }

                        if (stripMediaMetadata.isNotEmpty()) {
                            when (result.messageContent.contentType) {
                                ContentType.SNAP, ContentType.EXTERNAL_MEDIA -> {
                                    edit(*(if (result.messageContent.contentType == ContentType.SNAP) intArrayOf(11) else intArrayOf(3, 3))) {
                                        if (stripMediaMetadata.contains("hide_caption_text")) {
                                            edit(5) {
                                                editEach(1) {
                                                    remove(2)
                                                }
                                            }
                                        }
                                        if (stripMediaMetadata.contains("hide_snap_filters")) {
                                            remove(9)
                                            remove(11)
                                        }
                                        if (stripMediaMetadata.contains("hide_extras")) {
                                            remove(13)
                                            edit(5, 1) {
                                                remove(2)
                                            }
                                        }
                                    }
                                }
                                ContentType.NOTE -> {
                                    if (stripMediaMetadata.contains("remove_audio_note_duration")) {
                                        edit(6, 1, 1) {
                                            remove(13)
                                        }
                                    }
                                    if (stripMediaMetadata.contains("remove_audio_note_transcript_capability")) {
                                        edit(6, 1) {
                                            remove(3)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }

                        edit(11, 5, 2) {
                            remove(99)
                        }
                    }.toByteArray()
                }
            }
        }

        if (configOverrideType == null) return

        context.event.subscribe(NativeUnaryCallEvent::class) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
            postSavePolicy?.let { savePolicy ->
                context.log.verbose("postSavePolicy=$savePolicy")
                event.buffer = ProtoEditor(event.buffer).apply {
                    edit(4) {
                        remove(7)
                        addVarInt(7, savePolicy)
                    }

                    // remove Keep Snaps in Chat ability
                    if (savePolicy == 1/* PROHIBITED */) {
                        edit(6, 9) {
                            remove(1)
                        }
                    }
                }.toByteArray()
            }
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            postSavePolicy = null
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe
            val localMessageContent = event.messageContent
            // Allow both EXTERNAL_MEDIA (gallery) and SNAP (camera)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA && 
                localMessageContent.contentType != ContentType.SNAP &&
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) return@subscribe

            //prevent story replies
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            val conversationIds = event.destinations.conversations?.map { it.toString() } ?: return@subscribe
            if (conversationIds.isEmpty()) return@subscribe
            
            val recipientNames = conversationIds.mapNotNull { convId ->
                runCatching {
                    val dmParticipant = context.database.getDMOtherParticipant(convId)
                    if (dmParticipant != null) {
                        context.database.getFriendInfo(dmParticipant)?.displayName ?: context.database.getFriendInfo(dmParticipant)?.mutableUsername
                    } else {
                        context.database.getFeedEntryByConversationId(convId)?.feedDisplayName
                    }
                }.getOrNull()
            }.ifEmpty { listOf("Unknown") }
            
            val recipientName = recipientNames.joinToString(", ")

            event.canceled = true

            fun sendMedia(overrideType: String, snapDurationMs: Int?): Boolean {
                if (overrideType != "ORIGINAL" && (messageProtoReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Default.WarningAmber,
                        context.translation["gallery_media_send_override.multiple_media_toast"]
                    )
                    return false
                }

                when (overrideType) {
                    "SNAP", "SAVEABLE_SNAP" -> {
                        postSavePolicy = if (overrideType == "SAVEABLE_SNAP") 3 /* VIEW_SESSION */ else 1 /* PROHIBITED */

                        val extras = messageProtoReader.followPath(3, 3, 13)?.getBuffer()

                        if (localMessageContent.contentType != ContentType.SNAP) {
                            localMessageContent.content = ProtoWriter().apply {
                                from(11) {
                                    from(5) {
                                        from(1) {
                                            from(1) {
                                                addVarInt(2, 0)
                                                addVarInt(12, 0)
                                                addVarInt(15, 0)
                                            }
                                            addVarInt(6, 1)
                                        }
                                        from(2) {}
                                    }
                                    extras?.let {
                                        addBuffer(13, it)
                                    }
                                    from(22) {}
                                }
                            }.toByteArray()
                        }

                        localMessageContent.contentType = ContentType.SNAP
                        localMessageContent.content = ProtoEditor(localMessageContent.content!!).apply {
                            edit(11, 5, 2) {
                                arrayOf(6, 7, 8).forEach { remove(it) }
                                addVarInt(5, messageProtoReader.getVarInt(3, 3, 5, 2, 5) ?: messageProtoReader.getVarInt(11, 5, 2, 5) ?: 1)
                                // set snap duration
                                if (snapDurationMs != null) {
                                    addVarInt(8, snapDurationMs / 1000)
                                    if (snapDurationMs / 1000 <= 0) {
                                        addVarInt(99, snapDurationMs)
                                    }
                                } else {
                                    addBuffer(6, byteArrayOf())
                                }
                            }

                            // set app source
                            edit(11, 22) {
                                remove(4)
                                addVarInt(4, 5) // APP_SOURCE_CAMERA
                            }
                        }.toByteArray()
                    }
                    "NOTE" -> {
                        localMessageContent.contentType = ContentType.NOTE
                        localMessageContent.content =
                            MessageSender.audioNoteProto(
                                messageProtoReader.getVarInt(3, 3, 5, 1, 1, 15) ?: context.feature(MediaFilePicker::class).lastMediaDuration ?: 0,
                                Locale.getDefault().toLanguageTag()
                            )
                    }
                }

                return true
            }

            if (configOverrideType != "always_ask") {
                if (sendMedia(configOverrideType, 10)) {
                    event.invokeOriginal()
                }
                return@subscribe
            }

            context.runOnUiThread {
                val recipientNameForTask = recipientName
                createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                    val mainTranslation = remember {
                        context.translation.getCategory("send_override_dialog")
                    }

                    @Composable
                    fun ActionTile(
                        modifier: Modifier = Modifier,
                        selected: Boolean = false,
                        icon: ImageVector,
                        title: String,
                        onClick: () -> Unit
                    ) {
                        Card(
                            modifier = modifier,
                            onClick = onClick,
                            elevation = if (selected) CardDefaults.elevatedCardElevation(disabledElevation = 3.dp) else CardDefaults.cardElevation(),
                            colors = if (selected) CardDefaults.elevatedCardColors() else CardDefaults.cardColors()
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(75.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(icon, contentDescription = title, modifier = Modifier
                                    .size(32.dp)
                                    .padding(4.dp))
                                Text(title, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, fontWeight = FontWeight.Light, softWrap = true, lineHeight = 14.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val translation = remember {
                            context.translation.getCategory("features.options.gallery_media_send_override")
                        }
                        var scheduleEnabled by remember { mutableStateOf(false) }

                        Text(fontSize = 20.sp, fontWeight = FontWeight.Medium, text = "Send as ${
                            translation[selectedType]}", modifier = Modifier.padding(5.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ActionTile(selected = selectedType == "ORIGINAL", icon = Icons.Filled.Photo, title =
                            translation["ORIGINAL"]) {
                                selectedType = "ORIGINAL"
                            }
                            ActionTile(selected = selectedType == "SNAP" || selectedType == "SAVEABLE_SNAP", icon = Icons.Filled.PhotoCamera, title = translation["SNAP"]) {
                                selectedType = "SNAP"
                            }
                            ActionTile(selected = selectedType == "NOTE", icon = Icons.Filled.MusicNote, title = translation["NOTE"]) {
                                selectedType = "NOTE"
                            }
                        }

                        fun convertDuration(duration: Float): Int? {
                            return when  {
                                duration in -2f..-1f -> 100
                                duration in -1f..-0f -> 250
                                duration in -0f..1f -> 500
                                duration >= 11f -> null
                                else -> ((duration * 1000).toInt() / 1000) * 1000
                            }
                        }

                        when (selectedType) {
                            "SNAP", "SAVEABLE_SNAP" -> {
                                fun toggleSaveable() {
                                    selectedType = if (selectedType == "SAVEABLE_SNAP") "SNAP" else "SAVEABLE_SNAP"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        toggleSaveable()
                                    },
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ){
                                    Checkbox(
                                        checked = selectedType == "SAVEABLE_SNAP",
                                        onCheckedChange = {
                                            toggleSaveable()
                                        }
                                    )
                                    Text(text = mainTranslation["saveable_snap_hint"], lineHeight = 15.sp)
                                }
                                Column(
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = mainTranslation.format("duration",
                                            "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)?.toString(DurationUnit.SECONDS, 2) ?: mainTranslation["unlimited_duration"])
                                        )
                                    )
                                    Slider(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = selectedType != "SAVEABLE_SNAP",
                                        value = customDuration,
                                        onValueChange = {
                                            customDuration = it
                                        },
                                        valueRange = -2f..11f,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = scheduleEnabled,
                                onCheckedChange = {
                                    scheduleEnabled = it
                                    if (!it) scheduledTime = null
                                }
                            )
                            Text(text = mainTranslation["schedule"], modifier = Modifier.weight(1f))
                            if (scheduleEnabled) {
                                Button(onClick = { showClockPicker = true }) {
                                    scheduledTime?.let { time ->
                                        Text(text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(time))
                                    } ?: Text(context.translation["select"])
                                }
                            }
                        }

                        if (scheduleEnabled && showClockPicker) {
                            val datePickerState = rememberDatePickerState(
                                initialSelectedDateMillis = scheduledTime ?: System.currentTimeMillis()
                            )
                            val timePickerState = rememberTimePickerState(
                                initialHour = clockPickerHour,
                                initialMinute = clockPickerMinute
                            )
                            
                            var showDatePickerDialog by remember { mutableStateOf(false) }
                            var showTimePickerDialog by remember { mutableStateOf(false) }
                            
                            // Date Picker Dialog
                            if (showDatePickerDialog) {
                                DatePickerDialog(
                                    onDismissRequest = { showDatePickerDialog = false },
                                    confirmButton = {
                                        TextButton(onClick = { showDatePickerDialog = false }) {
                                            Text(context.translation["button.ok"])
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDatePickerDialog = false }) {
                                            Text(context.translation["button.cancel"])
                                        }
                                    }
                                ) {
                                    DatePicker(state = datePickerState)
                                }
                            }
                            
                            // Time Picker Dialog
                            if (showTimePickerDialog) {
                                AlertDialog(
                                    onDismissRequest = { showTimePickerDialog = false },
                                    confirmButton = {
                                        TextButton(onClick = { 
                                            clockPickerHour = timePickerState.hour
                                            clockPickerMinute = timePickerState.minute
                                            showTimePickerDialog = false 
                                        }) {
                                            Text(context.translation["button.ok"])
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showTimePickerDialog = false }) {
                                            Text(context.translation["button.cancel"])
                                        }
                                    },
                                    text = {
                                        TimePicker(state = timePickerState)
                                    }
                                )
                            }
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        mainTranslation["select_time"], 
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    
                                    // Date Selection Button
                                    OutlinedButton(
                                        onClick = { showDatePickerDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            datePickerState.selectedDateMillis?.let { millis ->
                                                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(millis)
                                            } ?: context.translation.getOrNull("select_date") ?: "Select Date"
                                        )
                                    }
                                    
                                    // Time Selection Button
                                    OutlinedButton(
                                        onClick = { showTimePickerDialog = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Schedule, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            String.format("%02d:%02d", clockPickerHour, clockPickerMinute)
                                        )
                                    }
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        OutlinedButton(onClick = { showClockPicker = false }) {
                                            Text(context.translation["button.cancel"])
                                        }
                                        Button(onClick = {
                                            val selectedDateMillis = datePickerState.selectedDateMillis
                                            if (selectedDateMillis == null) {
                                                context.inAppOverlay.showStatusToast(
                                                    icon = Icons.Default.WarningAmber,
                                                    text = mainTranslation.getOrNull("select_date_first") ?: "Please select a date"
                                                )
                                                return@Button
                                            }
                                            
                                            val calendar = Calendar.getInstance()
                                            calendar.timeInMillis = selectedDateMillis
                                            calendar.set(Calendar.HOUR_OF_DAY, clockPickerHour)
                                            calendar.set(Calendar.MINUTE, clockPickerMinute)
                                            calendar.set(Calendar.SECOND, 0)
                                            calendar.set(Calendar.MILLISECOND, 0)
                                            
                                            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                                                context.inAppOverlay.showStatusToast(
                                                    icon = Icons.Default.WarningAmber,
                                                    text = mainTranslation.getOrNull("invalid_time") ?: "Please select a future time"
                                                )
                                                return@Button
                                            }
                                            
                                            scheduledTime = calendar.timeInMillis
                                            showClockPicker = false
                                        }) {
                                            Text(context.translation["button.ok"])
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = {
                                alertDialog.dismiss()
                            }) {
                                Text(context.translation["button.cancel"])
                            }
                            Button(onClick = {
                                alertDialog.dismiss()
                                val delayMs = scheduledTime?.let { it - System.currentTimeMillis() }
                                if (delayMs != null && delayMs > 0) {
                                    val taskHash = java.util.UUID.randomUUID().toString()
                                    // Format the scheduled time for display
                                    val scheduledDateTime = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(scheduledTime)
                                    context.bridgeClient.getTaskInterface().createTask(
                                        "scheduled_send",
                                        scheduledDateTime,
                                        recipientNameForTask,
                                        taskHash
                                    )
                                    val days = (delayMs / (24 * 60 * 60 * 1000)).toInt()
                                    val hours = ((delayMs / (60 * 60 * 1000)) % 24).toInt()
                                    val minutes = ((delayMs / (60 * 1000)) % 60).toInt()
                                    val seconds = ((delayMs / 1000) % 60).toInt()
                                    
                                    val scheduledTimeText = StringBuilder().apply {
                                        if (days > 0) append("${days}d ")
                                        if (hours > 0 || days > 0) append("${hours}h ")
                                        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
                                        append("${seconds}s")
                                    }.toString()
                                    context.inAppOverlay.showStatusToast(
                                        icon = Icons.Filled.Schedule,
                                        text = context.translation.format("schedule_scheduled_for", "name" to recipientNameForTask, "time" to scheduledTimeText) ?: "Scheduled for $recipientNameForTask in $scheduledTimeText"
                                    )
                                    val releaseBackground = acquireScheduledSendBackground()
                                    val job = context.coroutineScope.launch {
                                        val startTime = System.currentTimeMillis()
                                        while (true) {
                                            delay(1000)
                                            val elapsed = System.currentTimeMillis() - startTime
                                            val remaining = delayMs - elapsed
                                            if (remaining <= 0) break
                                            val days = (remaining / (24 * 60 * 60 * 1000)).toInt()
                                            val hours = ((remaining / (60 * 60 * 1000)) % 24).toInt()
                                            val minutes = ((remaining / (60 * 1000)) % 60).toInt()
                                            val seconds = ((remaining / 1000) % 60).toInt()
                                            
                                            val timeText = StringBuilder().apply {
                                                if (days > 0) append("${days}d ")
                                                if (hours > 0 || days > 0) append("${hours}h ")
                                                if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
                                                append("${seconds}s")
                                            }.toString()
                                            context.bridgeClient.getTaskInterface().updateTaskProgress(
                                                taskHash,
                                                context.translation.getOrNull("schedule_sending_in")?.replace("{time}", timeText) ?: "Sending in $timeText",
                                                -1
                                            )
                                        }
                                        if (sendMedia(selectedType, if (selectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null)) {
                                            event.invokeOriginal()
                                            context.bridgeClient.getTaskInterface().successTask(taskHash)
                                            val successText = context.translation.format("schedule_sent_to", "name" to recipientNameForTask) ?: "Sent to $recipientNameForTask"
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.CheckCircle,
                                                text = successText
                                            )
                                            val notificationTitle = context.translation.getOrNull("schedule_sent") ?: "Scheduled snap sent"
                                            val notificationContent = "$scheduledDateTime\n$recipientNameForTask"
                                            showNotification(
                                                notificationTitle,
                                                notificationContent
                                            )
                                        } else {
                                            context.bridgeClient.getTaskInterface().failTask(taskHash, "Failed to send")
                                            val failureText = context.translation.format("schedule_failed_to", "name" to recipientNameForTask) ?: "Failed to send to $recipientNameForTask"
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.WarningAmber,
                                                text = failureText
                                            )
                                            val failNotificationTitle = context.translation.getOrNull("schedule_failed") ?: "Scheduled snap failed"
                                            val failNotificationContent = "$scheduledDateTime\n$recipientNameForTask"
                                            showNotification(
                                                failNotificationTitle,
                                                failNotificationContent
                                            )
                                        }
                                    }
                                    val listener = object : TaskListener.Stub() {
                                        override fun onCancel() {
                                            job.cancel()
                                        }
                                        override fun onProgress(label: String, progress: Int) {}
                                        override fun onStateChange(status: String) {}
                                        override fun onSuccess() {}
                                    }
                                    context.bridgeClient.getTaskInterface().registerTaskListener(taskHash, listener)
                                    job.invokeOnCompletion { throwable ->
                                        releaseBackground()
                                        context.bridgeClient.getTaskInterface().unregisterTaskListener(taskHash, listener)
                                        if (throwable is CancellationException) {
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.Cancel,
                                                text = context.translation.format("schedule_cancelled_for", "name" to recipientNameForTask) ?: "Cancelled for $recipientNameForTask"
                                            )
                                        }
                                    }
                                } else {
                                    if (sendMedia(selectedType, if (selectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null)) {
                                        event.invokeOriginal()
                                    }
                                }
                            }) {
                                Text(if (scheduledTime != null) mainTranslation["schedule"] else context.translation["button.send"])
                            }
                        }
                    }
                }.show()
            }
        }
    }
}


