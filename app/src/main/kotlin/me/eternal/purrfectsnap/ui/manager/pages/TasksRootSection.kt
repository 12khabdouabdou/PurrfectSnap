package me.eternal.purrfectsnap.ui.manager.pages

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.bridge.DownloadCallback
import me.eternal.purrfectsnap.common.data.download.DownloadMetadata
import me.eternal.purrfectsnap.common.data.download.MediaDownloadSource
import me.eternal.purrfectsnap.common.data.download.createNewFilePath
import me.eternal.purrfectsnap.common.ui.TopBarActionButton
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.common.util.ktx.longHashCode
import me.eternal.purrfectsnap.download.DownloadProcessor
import me.eternal.purrfectsnap.download.FFMpegProcessor
import me.eternal.purrfectsnap.task.*
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.OnLifecycleEvent
import me.eternal.purrfectsnap.ui.util.coil.cacheKey
import java.io.File
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.text.Regex

class TasksRootSection : Routes.Route() {
    private var activeTasks by mutableStateOf(listOf<PendingTask>())
    private lateinit var recentTasks: MutableList<Task>
    private val taskSelection = mutableStateListOf<Pair<Task, DocumentFile?>>()

    private fun fetchActiveTasks(scope: CoroutineScope = context.coroutineScope) {
        scope.launch(Dispatchers.IO) {
            activeTasks = context.taskManager.getActiveTasks().values.sortedByDescending { it.taskId }.toMutableList()
        }
    }

    private fun mergeSelection(selection: List<Pair<Task, DocumentFile>>) {
        val firstTask = selection.first().first

        val taskHash = UUID.randomUUID().toString().longHashCode().absoluteValue.toString(16)
        val pendingTask = context.taskManager.createPendingTask(
            Task(TaskType.DOWNLOAD, "Merge ${selection.size} files", firstTask.author, taskHash)
        )
        pendingTask.status = TaskStatus.RUNNING
        fetchActiveTasks()

        context.coroutineScope.launch {
            val filesToMerge = mutableListOf<File>()

            selection.forEach { (task, documentFile) ->
                val tempFile = File.createTempFile(task.hash, "." + documentFile.name?.substringAfterLast("."), context.androidContext.cacheDir).also {
                    it.deleteOnExit()
                }

                runCatching {
                    pendingTask.updateProgress("Copying ${documentFile.name}")
                    context.androidContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                        //copy with progress
                        val length = documentFile.length().toFloat()
                        tempFile.outputStream().use { outputStream ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                                pendingTask.updateProgress("Copying ${documentFile.name}", (outputStream.channel.position().toFloat() / length * 100f).toInt())
                            }
                            outputStream.flush()
                            filesToMerge.add(tempFile)
                        }
                    }
                }.onFailure {
                    pendingTask.fail("Failed to copy file $documentFile to $tempFile")
                    filesToMerge.forEach { it.delete() }
                    return@launch
                }
            }

            val mergedFile = File.createTempFile("merged", ".mp4", context.androidContext.cacheDir).also {
                it.deleteOnExit()
            }

            runCatching {
                context.shortToast(translation.format("merge_files_toast", "count" to filesToMerge.size.toString()))
                FFMpegProcessor.newFFMpegProcessor(context, pendingTask).execute(
                    FFMpegProcessor.Request(FFMpegProcessor.Action.MERGE_MEDIA, filesToMerge.map { it.absolutePath }, mergedFile)
                )
                DownloadProcessor(context, object: DownloadCallback.Default() {
                    override fun onSuccess(outputPath: String) {
                        context.log.verbose("Merged files to $outputPath")
                    }
                }).saveMediaToGallery(pendingTask, mergedFile, DownloadMetadata(
                    mediaIdentifier = taskHash,
                    outputPath = createNewFilePath(
                        context.config.root,
                        taskHash,
                        downloadSource = MediaDownloadSource.MERGED,
                        mediaAuthor = firstTask.author,
                        creationTimestamp = System.currentTimeMillis()
                    ),
                    mediaAuthor = firstTask.author,
                    downloadSource = MediaDownloadSource.MERGED.translate(context.translation),
                    iconUrl = null
                ))
            }.onFailure {
                context.log.error("Failed to merge files", it)
                pendingTask.fail(it.message ?: "Failed to merge files")
            }.onSuccess {
                pendingTask.success()
            }
            filesToMerge.forEach { it.delete() }
            mergedFile.delete()
        }.also {
            pendingTask.addListener(PendingTaskListener(onCancel = { it.cancel() }))
        }
    }

    private fun clearTasks(alsoDeleteFiles: Boolean, scope: CoroutineScope) {
        if (taskSelection.isNotEmpty()) {
            taskSelection.forEach { (task, documentFile) ->
                scope.launch(Dispatchers.IO) {
                    context.taskManager.removeTask(task)
                    if (alsoDeleteFiles) documentFile?.delete()
                }
                recentTasks.remove(task)
            }
            activeTasks = activeTasks.filter { task -> !taskSelection.map { it.first }.contains(task.task) }
            taskSelection.clear()
        } else {
            scope.launch(Dispatchers.IO) { context.taskManager.clearAllTasks() }
            recentTasks.clear()
            activeTasks.forEach {
                runCatching { it.cancel() }.onFailure { throwable ->
                    context.log.error("Failed to cancel task $it", throwable)
                }
            }
            activeTasks = listOf()
            context.taskManager.getActiveTasks().clear()
        }
    }

    @Composable
    private fun TaskDangerDialog(
        visible: Boolean,
        title: String,
        message: String,
        showDeleteFiles: Boolean,
        deleteFilesChecked: Boolean,
        onToggleDeleteFiles: (Boolean) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        if (!visible) return

        val dialogShape = RoundedCornerShape(24.dp)
        val borderGradient = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.65f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.55f)
                )
            )
        }

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = dialogShape,
                color = Color.White.copy(alpha = 0.06f),
                tonalElevation = 0.dp,
                shadowElevation = 20.dp,
                border = BorderStroke(1.dp, borderGradient)
            ) {
                Box(
                    modifier = Modifier
                        .background(PurrfectPalette.cardOverlay, dialogShape)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.08f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    PurrfectPalette.glowPrimary.copy(alpha = 0.38f),
                                                    PurrfectPalette.glowSecondary.copy(alpha = 0.32f)
                                                )
                                            ),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White
                                )
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PurrfectPalette.textSecondary
                                )
                            }
                        }

                        if (showDeleteFiles) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color.White.copy(alpha = 0.04f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleDeleteFiles(!deleteFilesChecked) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Checkbox(
                                        checked = deleteFilesChecked,
                                        onCheckedChange = { onToggleDeleteFiles(it) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = PurrfectPalette.glowPrimary,
                                            uncheckedColor = Color.White,
                                            checkmarkColor = Color.Black
                                        )
                                    )
                                    Column {
                                        Text(
                                            text = context.translation["delete_files_option"],
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = context.translation["delete_files_option_hint"] ?: "Also remove downloaded files",
                                            color = PurrfectPalette.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(context.translation["button.negative"])
                            }
                            Button(
                                onClick = onConfirm,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PurrfectPalette.glowPrimary.copy(alpha = 0.34f),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(context.translation["button.positive"])
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TasksEmptyState(text: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.08f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    PurrfectPalette.glowPrimary.copy(alpha = 0.32f),
                                    PurrfectPalette.glowSecondary.copy(alpha = 0.28f)
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = text,
                        tint = Color.White
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White
            )
        }
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        var showConfirmDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        if (taskSelection.size > 1) {
            val canMergeSelection by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(taskSelection.size)) {
                taskSelection.all { it.second?.type?.contains("video") == true }
            }

            if (canMergeSelection) {
                TopBarActionButton(
                    onClick = {
                        mergeSelection(taskSelection.toList().also {
                            taskSelection.clear()
                        }.map { it.first to it.second!! })
                    },
                    icon = Icons.Filled.Merge,
                    text = translation["merge_button"]
                )
            }
        }

        IconButton(onClick = {
            showConfirmDialog = true
        }) {
            Icon(Icons.Filled.Delete, contentDescription = "Clear tasks")
        }

        if (showConfirmDialog) {
            var alsoDeleteFiles by remember { mutableStateOf(false) }
            val isSelection = taskSelection.isNotEmpty()
            val titleText = if (isSelection) {
                translation.format("remove_selected_tasks_confirm", "count" to taskSelection.size.toString())
            } else {
                translation["remove_all_tasks_confirm"]
            }
            val messageText = if (isSelection) translation["remove_selected_tasks_title"] else translation["remove_all_tasks_title"]

            TaskDangerDialog(
                visible = showConfirmDialog,
                title = titleText,
                message = messageText,
                showDeleteFiles = isSelection,
                deleteFilesChecked = alsoDeleteFiles,
                onToggleDeleteFiles = { alsoDeleteFiles = it },
                onConfirm = {
                    showConfirmDialog = false
                    clearTasks(alsoDeleteFiles, coroutineScope)
                },
                onDismiss = { showConfirmDialog = false }
            )
        }
    }

    @Composable
    private fun TaskCard(modifier: Modifier, task: Task, pendingTask: PendingTask? = null) {
        var taskStatus by remember { mutableStateOf(task.status) }
        var taskProgressLabel by remember { mutableStateOf<String?>(null) }
        var taskProgress by remember { mutableIntStateOf(-1) }
        val isSelected by remember { derivedStateOf { taskSelection.any { it.first == task } } }

        var documentFileMimeType by remember { mutableStateOf("") }
        var isDocumentFileReadable by remember { mutableStateOf(true) }
        val documentFile by rememberAsyncMutableState(defaultValue = null, keys = arrayOf(taskStatus.key)) {
            DocumentFile.fromSingleUri(context.androidContext, task.extra?.toUri() ?: return@rememberAsyncMutableState null)?.apply {
                documentFileMimeType = type ?: ""
                isDocumentFileReadable = canRead()
            }
        }


        val listener = remember { PendingTaskListener(
            onStateChange = {
                taskStatus = it
            },
            onProgress = { label, progress ->
                taskProgressLabel = label
                taskProgress = progress
            }
        ) }

        LaunchedEffect(Unit) {
            pendingTask?.addListener(listener)
        }

        DisposableEffect(Unit) {
            onDispose {
                pendingTask?.removeListener(listener)
            }
        }

        fun toggleSelection() {
            if (isSelected) {
                taskSelection.removeIf { it.first == task }
                return
            }
            taskSelection.add(task to documentFile)
        }

        fun openFile() {
            if (!isDocumentFileReadable || documentFile == null) return
            runCatching {
                context.androidContext.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(documentFile!!.uri, documentFile!!.type)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }.onFailure {
                context.log.error("Failed to open file ${documentFile?.uri}", it)
                context.shortToast(translation["failed_to_open_file"])
            }
        }

        val isActive = pendingTask != null && !taskStatus.isFinalStage()
        val cardModifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (taskSelection.isNotEmpty()) {
                            toggleSelection()
                            return@detectTapGestures
                        }
                        openFile()
                    },
                    onLongPress = {
                        if (taskSelection.isNotEmpty()) {
                            openFile()
                            return@detectTapGestures
                        }
                        toggleSelection()
                    }
                )
            }
            .let {
                if (isSelected) {
                    it
                        .border(2.dp, PurrfectPalette.glowSecondary, MaterialTheme.shapes.large)
                        .clip(MaterialTheme.shapes.large)
                } else it
            }

        val chipLabel = when {
            isActive -> translation.getOrNull("task_sending") ?: "Sending"
            taskStatus == TaskStatus.SUCCESS -> null
            taskStatus == TaskStatus.FAILURE -> translation.getOrNull("task_failed") ?: "Failed"
            taskStatus == TaskStatus.CANCELLED -> translation.getOrNull("task_cancelled") ?: "Cancelled"
            else -> taskStatus.name.lowercase().replaceFirstChar { it.titlecase() }
        }
        val chipIcon = when {
            isActive -> null
            taskStatus == TaskStatus.SUCCESS -> Icons.Filled.Check
            taskStatus == TaskStatus.FAILURE -> Icons.Filled.WarningAmber
            taskStatus == TaskStatus.CANCELLED -> Icons.Filled.Cancel
            else -> Icons.Filled.Info
        }
        val chipColors = when {
            isActive -> AssistChipDefaults.assistChipColors(
                containerColor = Color.White.copy(alpha = 0.08f),
                labelColor = Color.White
            )
            taskStatus == TaskStatus.SUCCESS -> AssistChipDefaults.assistChipColors()
            taskStatus == TaskStatus.FAILURE -> AssistChipDefaults.assistChipColors(
                containerColor = Color(0xFFFF6B9B).copy(alpha = 0.18f),
                labelColor = Color.White
            )
            taskStatus == TaskStatus.CANCELLED -> AssistChipDefaults.assistChipColors(
                containerColor = Color.White.copy(alpha = 0.06f),
                labelColor = PurrfectPalette.textSecondary
            )
            else -> AssistChipDefaults.assistChipColors()
        }
        val countdownText = if (isActive) {
            taskProgressLabel?.let { label ->
                Regex("""(\d+d\s+)?(\d+h\s+)?(\d+m\s+)?\d+s""").find(label)?.value?.trim() ?: label
            }
        } else null

        val cardShape = RoundedCornerShape(22.dp)
        Surface(
            modifier = cardModifier,
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = if (isSelected) 12.dp else 6.dp,
            border = BorderStroke(
                1.dp,
                if (isSelected) Brush.linearGradient(listOf(PurrfectPalette.glowPrimary, PurrfectPalette.glowSecondary))
                else SolidColor(Color.White.copy(alpha = 0.1f))
            )
        ) {
            Row(
                modifier = Modifier
                    .background(PurrfectPalette.cardOverlay, cardShape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 15.dp)
                        .size(50.dp)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    var loadFailed by remember { mutableStateOf(false) }
                    documentFile?.let {
                        if (taskStatus.isFinalStage() && isDocumentFileReadable && !loadFailed && (documentFileMimeType.contains("image") || documentFileMimeType.contains("video"))) {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(context.androidContext)
                                        .data(it.uri)
                                        .cacheKey(it.uri.toString())
                                        .placeholder(ColorDrawable(PurrfectPalette.cardOverlayColor.toArgb()))
                                        .build(),
                                    imageLoader = context.imageLoader,
                                    onError = { loadFailed = true }
                                ),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(MaterialTheme.shapes.medium)
                            )
                        } else {
                            when {
                                !isDocumentFileReadable -> Icon(Icons.Filled.DeleteOutline, contentDescription = "File not found")
                                documentFileMimeType.contains("image") -> Icon(Icons.Filled.Image, contentDescription = "Image")
                                documentFileMimeType.contains("video") -> Icon(Icons.Filled.Videocam, contentDescription = "Video")
                                documentFileMimeType.contains("audio") -> Icon(Icons.Filled.MusicNote, contentDescription = "Audio")
                                else -> Icon(Icons.Filled.FileCopy, contentDescription = "File")
                            }
                        }
                    } ?: run {
                        when (task.type) {
                            TaskType.DOWNLOAD -> Icon(Icons.Filled.Download, contentDescription = "Download")
                            TaskType.CHAT_ACTION -> Icon(Icons.Filled.ChatBubble, contentDescription = "Chat Action")
                            TaskType.SCHEDULED_SEND -> {
                                val isActive = !taskStatus.isFinalStage()
                                val rotation = if (isActive) {
                                    val transition = rememberInfiniteTransition(label = "scheduled_send")
                                    transition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 360f,
                                        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
                                        label = "rotation"
                                    ).value
                                } else 0f
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isActive) {
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        PurrfectPalette.glowPrimary.copy(alpha = 0.25f),
                                                        PurrfectPalette.glowSecondary.copy(alpha = 0.22f)
                                                    )
                                                )
                                            } else {
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        Color.White.copy(alpha = 0.06f),
                                                        Color.White.copy(alpha = 0.06f)
                                                    )
                                                )
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Schedule,
                                        contentDescription = "Scheduled Send",
                                        modifier = Modifier
                                            .size(28.dp)
                                            .rotate(rotation),
                                        tint = if (isActive) PurrfectPalette.glowSecondary else PurrfectPalette.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    if (task.type == TaskType.SCHEDULED_SEND) {
                        // Professional design for scheduled send tasks
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Feature title
                            Text(
                                context.translation.getOrNull("scheduled_send_title") ?: "Scheduled Snaps",
                                style = MaterialTheme.typography.labelMedium,
                                color = PurrfectPalette.textSecondary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            
                            // Scheduled time without icon
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = Color.White
                            )
                            
                            // Recipients with icon
                            task.author?.takeIf { it != "null" }?.let { recipients ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.People,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                                        tint = PurrfectPalette.textSecondary
                                    )
                                    Text(
                                        recipients,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            task.author?.takeIf { it != "null" }?.let {
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = PurrfectPalette.textSecondary)
                            }
                        }
                        Text(task.hash, style = MaterialTheme.typography.labelSmall, color = PurrfectPalette.textSecondary)
                    }
                    Column(
                        modifier = Modifier.padding(top = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        chipLabel?.let { label ->
                            val leadingIcon: (@Composable () -> Unit)? = if (isActive && task.type == TaskType.SCHEDULED_SEND) {
                                {
                                    Icon(
                                        Icons.Filled.Timer,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else if (isActive) {
                                countdownText?.let { countdown ->
                                    {
                                        Text(
                                            countdown,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            } else chipIcon?.let { icon ->
                                { Icon(icon, contentDescription = null) }
                            }
                            val displayLabel = if (isActive && task.type == TaskType.SCHEDULED_SEND && countdownText != null) {
                                val sendingText = translation.getOrNull("schedule_sending_in")?.replace("{time}", countdownText) ?: "Sending in $countdownText"
                                sendingText
                            } else {
                                label
                            }
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                leadingIcon = leadingIcon,
                                label = { Text(displayLabel) },
                                colors = chipColors
                            )
                        }
                        if (taskStatus.isFinalStage()) {
                            if (taskStatus != TaskStatus.SUCCESS) {
                                Text("$taskStatus", style = MaterialTheme.typography.bodySmall, color = PurrfectPalette.textSecondary)
                            }
                        } else {
                            if (!isActive) {
                                taskProgressLabel?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White)
                                }
                            }
                            if (taskProgress != -1 && taskProgressLabel == null) {
                                LinearProgressIndicator(
                                    progress = { taskProgress.toFloat() / 100f },
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = PurrfectPalette.glowSecondary,
                                    trackColor = Color.White.copy(alpha = 0.12f)
                                )
                            }
                            if (!isActive) {
                                task.extra?.takeIf { it?.isNotEmpty() == true }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = PurrfectPalette.textSecondary)
                                }
                            }
                        }
                    }
                }

                Column {
                    if (pendingTask != null && !taskStatus.isFinalStage()) {
                        FilledIconButton(
                            onClick = {
                            runCatching {
                                pendingTask.cancel()
                            }.onFailure { throwable ->
                                context.log.error("Failed to cancel task $pendingTask", throwable)
                            }
                        },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFFFF6B9B).copy(alpha = 0.35f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    } else {
                        if (taskStatus == TaskStatus.SUCCESS) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing)) + scaleIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                                exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.5f, animationSpec = tween(150))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(PurrfectPalette.glowPrimary.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = "Success", tint = Color.White)
                                }
                            }
                        } else {
                            when (taskStatus) {
                                TaskStatus.FAILURE -> Icon(Icons.Filled.Error, contentDescription = "Failure", tint = Color(0xFFFF6B9B))
                                TaskStatus.CANCELLED -> Icon(Icons.Filled.Cancel, contentDescription = "Cancelled", tint = Color(0xFFFF6B9B))
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val scrollState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        recentTasks = remember { mutableStateListOf() }
        var lastFetchedTaskId by remember { mutableStateOf(null as Long?) }
        var showConfirmDialog by remember { mutableStateOf(false) }
        var alsoDeleteFiles by remember { mutableStateOf(false) }

        fun fetchNewRecentTasks() {
            scope.launch(Dispatchers.IO) {
                val tasks = context.taskManager.fetchStoredTasks(lastFetchedTaskId ?: Long.MAX_VALUE, limit = 20)
                if (tasks.isNotEmpty()) {
                    lastFetchedTaskId = tasks.keys.last()
                    val activeTaskIds = activeTasks.map { it.taskId }
                    recentTasks.addAll(tasks.filter { it.key !in activeTaskIds }.values)
                }
            }
        }

        LaunchedEffect(Unit) {
            fetchActiveTasks(this)
        }

        DisposableEffect(Unit) {
            onDispose {
                taskSelection.clear()
            }
        }

        OnLifecycleEvent { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fetchActiveTasks(scope)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PurrfectPalette.backgroundGradient)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = 0.07f),
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = context.translation["manager.routes.tasks"],
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = if (activeTasks.isNotEmpty()) "${activeTasks.size} active · ${recentTasks.size} recent" else "Idle · ${recentTasks.size} recent",
                                color = PurrfectPalette.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (taskSelection.size > 1 && taskSelection.all { it.second?.type?.contains("video") == true }) {
                                Surface(
                                    onClick = {
                                        mergeSelection(
                                            taskSelection.toList().also { taskSelection.clear() }
                                                .map { it.first to it.second!! }
                                        )
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp,
                                    border = BorderStroke(
                                        1.dp,
                                        Brush.linearGradient(listOf(PurrfectPalette.glowPrimary, PurrfectPalette.glowSecondary))
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.Merge, contentDescription = translation["merge_button"], tint = Color.White)
                                        Text(translation["merge_button"], color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Filled.PlaylistAddCheckCircle, contentDescription = null, tint = Color.White)
                                    Text(text = "${activeTasks.size} running", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                            }
                            IconButton(onClick = { showConfirmDialog = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Clear tasks", tint = Color.White)
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 0.dp,
                            bottom = routes.bottomPadding + 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            if (activeTasks.isEmpty() && recentTasks.isEmpty()) {
                                TasksEmptyState(text = translation["no_tasks"])
                            }
                        }
                        items(activeTasks, key = { it.taskId }) { pendingTask ->
                            TaskCard(modifier = Modifier.fillMaxWidth(), pendingTask.task, pendingTask = pendingTask)
                        }
                        items(recentTasks, key = { it.hash }) { task ->
                            TaskCard(modifier = Modifier.fillMaxWidth(), task)
                        }
                        item {
                            Spacer(modifier = Modifier.height(40.dp))
                            LaunchedEffect(remember { derivedStateOf { scrollState.firstVisibleItemIndex } }) {
                                fetchNewRecentTasks()
                            }
                        }
                    }
                }
            }
        }

        if (showConfirmDialog) {
            val isSelection = taskSelection.isNotEmpty()
            val titleText = if (isSelection) {
                translation.format("remove_selected_tasks_confirm", "count" to taskSelection.size.toString())
            } else {
                translation["remove_all_tasks_confirm"]
            }
            val messageText = if (isSelection) translation["remove_selected_tasks_title"] else translation["remove_all_tasks_title"]

            TaskDangerDialog(
                visible = showConfirmDialog,
                title = titleText,
                message = messageText,
                showDeleteFiles = isSelection,
                deleteFilesChecked = alsoDeleteFiles,
                onToggleDeleteFiles = { alsoDeleteFiles = it },
                onConfirm = {
                    showConfirmDialog = false
                    clearTasks(alsoDeleteFiles, scope)
                },
                onDismiss = { showConfirmDialog = false }
            )
        }
    }
}
