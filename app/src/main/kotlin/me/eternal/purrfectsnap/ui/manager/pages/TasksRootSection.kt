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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import me.eternal.purrfectsnap.ui.manager.ManagerTheme
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.OnLifecycleEvent
import me.eternal.purrfectsnap.ui.util.coil.cacheKey
import java.io.File
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.text.Regex

class TasksRootSection : Routes.Route() {
    internal var activeTasks by mutableStateOf(listOf<PendingTask>())
    internal lateinit var recentTasks: MutableList<Task>
    internal var lastFetchedTaskId: Long? by mutableStateOf(null)
    internal val taskSelection = mutableStateListOf<Pair<Task, DocumentFile?>>()

    internal fun isRecentTasksInitialized(): Boolean = ::recentTasks.isInitialized

    internal fun fetchActiveTasks(scope: CoroutineScope = context.coroutineScope) {
        scope.launch(Dispatchers.IO) {
            activeTasks = context.taskManager.getActiveTasks().values.sortedByDescending { it.taskId }.toMutableList()
        }
    }

    internal fun fetchNewRecentTasks(scope: CoroutineScope = context.coroutineScope) {
        scope.launch(Dispatchers.IO) {
            val tasks = context.taskManager.fetchStoredTasks(lastFetchedTaskId ?: Long.MAX_VALUE, limit = 20)
            if (tasks.isNotEmpty()) {
                lastFetchedTaskId = tasks.keys.last()
                val activeTaskIds = activeTasks.map { it.taskId }
                recentTasks.addAll(tasks.filter { it.key !in activeTaskIds }.values)
            }
        }
    }

    internal fun mergeSelection(selection: List<Pair<Task, DocumentFile>>) {
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

    internal fun clearTasks(alsoDeleteFiles: Boolean, scope: CoroutineScope) {
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
        }
    }

    @Composable
    internal fun TaskDangerDialog(
        visible: Boolean,
        title: String,
        message: String,
        showDeleteFiles: Boolean,
        deleteFilesChecked: Boolean,
        tasksTranslation: me.eternal.purrfectsnap.common.bridge.wrapper.LocaleWrapper,
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
                Box(modifier = Modifier.background(PurrfectPalette.cardOverlay, dialogShape)) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.08f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))) {
                                Box(modifier = Modifier.size(56.dp).background(Brush.linearGradient(listOf(PurrfectPalette.glowPrimary.copy(alpha = 0.38f), PurrfectPalette.glowSecondary.copy(alpha = 0.32f)))), contentAlignment = Alignment.Center) {
                                    Icon(imageVector = Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color.White)
                                }
                            }
                            Column {
                                Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(text = message, fontSize = 13.sp, color = PurrfectPalette.textSecondary)
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
                                            text = tasksTranslation["delete_files_option"],
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = tasksTranslation.getOrNull("delete_files_option_hint") ?: "Permanently remove the original files from storage",
                                            color = PurrfectPalette.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                }
                            }
                        }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onDismiss() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                                Text(text = context.translation["button.cancel"])
                            }
                            Button(onClick = { onConfirm() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B152E)), shape = RoundedCornerShape(14.dp)) {
                                Text(text = context.translation["button.positive"], fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun TasksEmptyState(text: String) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.08f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))) {
                Box(modifier = Modifier.size(58.dp).background(Brush.linearGradient(listOf(PurrfectPalette.glowPrimary.copy(alpha = 0.32f), PurrfectPalette.glowSecondary.copy(alpha = 0.28f))), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = text, tint = Color.White)
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White
            )
        }
    }


    @Composable
    internal fun TaskCard(modifier: Modifier, task: Task, pendingTask: PendingTask? = null) {
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
            onStateChange = { taskStatus = it },
            onProgress = { label, progress -> taskProgressLabel = label; taskProgress = progress }
        ) }

        LaunchedEffect(Unit) { pendingTask?.addListener(listener) }
        DisposableEffect(Unit) { onDispose { pendingTask?.removeListener(listener) } }

        fun toggleSelection() {
            if (isSelected) { taskSelection.removeIf { it.first == task }; return }
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
                    onTap = { if (taskSelection.isNotEmpty()) toggleSelection() else openFile() },
                    onLongPress = { if (taskSelection.isNotEmpty()) openFile() else toggleSelection() }
                )
            }
            .let {
                if (isSelected) {
                    it.border(2.dp, PurrfectPalette.glowSecondary, MaterialTheme.shapes.large).clip(MaterialTheme.shapes.large)
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

        Surface(
            modifier = cardModifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = MaterialTheme.shapes.large,
            color = Color.White.copy(alpha = 0.04f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(modifier = Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(imageVector = if (task.type == TaskType.DOWNLOAD) Icons.Default.Download else Icons.Default.Transform, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
                    if (isActive) {
                        CircularProgressIndicator(progress = { (taskProgress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), color = PurrfectPalette.glowPrimary, strokeWidth = 3.dp, trackColor = Color.Transparent, strokeCap = StrokeCap.Round)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = task.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = taskProgressLabel ?: task.author ?: "", fontSize = 12.sp, color = PurrfectPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chipLabel != null || chipIcon != null) {
                    AssistChip(onClick = {}, label = { chipLabel?.let { Text(it) } }, leadingIcon = { chipIcon?.let { Icon(it, null, modifier = Modifier.size(18.dp)) } }, colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, leadingIconContentColor = Color.White), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape = CircleShape)
                }
            }
        }
    }

    override val init: () -> Unit = {
        recentTasks = mutableStateListOf()
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(initialValue = context.config.root.global.uiSettings.managerTheme.get()) {
            while (true) { delay(300); value = context.config.root.global.uiSettings.managerTheme.get() }
        }
        key(themeId) {
            LaunchedEffect(themeId) {
                routes.navigation?.globalScrollOffset = 0
            }
            with(ManagerTheme.fromId(themeId).theme) {
                this@TasksRootSection.TasksScreen(nav)
            }
        }
    }

    override val topBarActions: @Composable RowScope.() -> Unit = {
        var showConfirmDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        if (taskSelection.isNotEmpty()) {
            val hapticFeedback = LocalHapticFeedback.current
            if (taskSelection.size > 1) {
                val canMergeSelection by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(taskSelection.size)) {
                    taskSelection.all { it.second?.type?.contains("video") == true }
                }
                if (canMergeSelection) {
                    TopBarActionButton(onClick = { if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); mergeSelection(taskSelection.toList().also { taskSelection.clear() }.map { it.first to it.second!! }) }, icon = Icons.Filled.Merge, text = translation["merge_button"])
                }
            }
            IconButton(onClick = { if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showConfirmDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = translation["clear_button_description"])
            }
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
                title = titleText ?: "",
                message = messageText ?: "",
                showDeleteFiles = isSelection,
                deleteFilesChecked = alsoDeleteFiles,
                tasksTranslation = translation,
                onToggleDeleteFiles = { alsoDeleteFiles = it },
                onConfirm = {
                    showConfirmDialog = false
                    clearTasks(alsoDeleteFiles, coroutineScope)
                },
                onDismiss = { showConfirmDialog = false }
            )
        }
    }
}




