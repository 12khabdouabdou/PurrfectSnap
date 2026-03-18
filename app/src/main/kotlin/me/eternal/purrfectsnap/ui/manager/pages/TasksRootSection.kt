package me.eternal.purrfectsnap.ui.manager.pages

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.download.DownloadProcessor
import me.eternal.purrfectsnap.bridge.DownloadCallback
import me.eternal.purrfectsnap.common.data.download.MediaDownloadSource
import me.eternal.purrfectsnap.common.data.download.DownloadMetadata
import me.eternal.purrfectsnap.common.ui.TopBarActionButton
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.common.data.download.createNewFilePath
import me.eternal.purrfectsnap.common.util.snap.RemoteMediaResolver
import me.eternal.purrfectsnap.download.FFMpegProcessor
import me.eternal.purrfectsnap.task.PendingTask
import me.eternal.purrfectsnap.task.PendingTaskListener
import me.eternal.purrfectsnap.task.Task
import me.eternal.purrfectsnap.task.TaskStatus
import me.eternal.purrfectsnap.task.TaskType
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.ManagerTheme
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.*
import java.io.File

class TasksRootSection : Routes.Route() {
    internal var activeTasks by mutableStateOf(listOf<PendingTask>())
    internal var recentTasks = mutableStateListOf<Task>()
    internal val taskSelection = mutableStateListOf<Pair<Task, DocumentFile?>>()
    internal var lastFetchedTaskId: Long? by mutableStateOf(null)

    internal fun fetchActiveTasks(scope: CoroutineScope) {
        activeTasks = context.taskManager.getActiveTasks().values.toList()
    }

    internal fun fetchNewRecentTasks() {
        val tasks = context.taskManager.fetchStoredTasks(lastFetchedTaskId ?: Long.MAX_VALUE, limit = 20)
        if (tasks.isEmpty()) return
        
        lastFetchedTaskId = tasks.keys.last()
        val activeTaskHashes = activeTasks.map { it.task.hash }
        val existingHashes = recentTasks.map { it.hash }
        
        val newTasks = tasks.values.filter { it.hash !in activeTaskHashes && it.hash !in existingHashes }
        recentTasks.addAll(newTasks)
    }

    internal fun refreshRecentTasks() {
        val tasks = context.taskManager.fetchStoredTasks(Long.MAX_VALUE, limit = 20)
        val activeTaskHashes = activeTasks.map { it.task.hash }
        val newTasks = tasks.values.filter { it.hash !in activeTaskHashes }
        
        recentTasks.clear()
        recentTasks.addAll(newTasks)
        lastFetchedTaskId = tasks.keys.lastOrNull()
    }

    internal fun isRecentTasksInitialized() = true

    override val init: () -> Unit = {
        recentTasks = mutableStateListOf()
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(initialValue = context.config.root.global.uiSettings.managerTheme.get()) {
            while (true) {
                delay(300)
                value = context.config.root.global.uiSettings.managerTheme.get()
            }
        }

        key(themeId) {
            with(ManagerTheme.fromId(themeId).theme) {
                this@TasksRootSection.TasksScreen(nav)
            }
        }
    }

    @Composable
    internal fun TasksRootSection.TasksScreen(nav: NavBackStackEntry) {
        val listState = rememberLazyListState()
        var controlsHeight by remember { mutableStateOf(100.dp) }

        val computedScrollOffset by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt()
                else listState.firstVisibleItemScrollOffset
            }
        }

        LaunchedEffect(computedScrollOffset) {
            val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
            if (isAphelion) {
                routes.navigation?.globalScrollOffset = computedScrollOffset
            } else {
                routes.navigation?.globalScrollOffset = 0
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                routes.navigation?.globalScrollOffset = 0
            }
        }

        LaunchedEffect(Unit) {
            refreshRecentTasks()
            while (true) {
                fetchActiveTasks(this)
                delay(2000)
            }
        }

        val shouldFetchMore by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItemsNumber = layoutInfo.totalItemsCount
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                lastVisibleItemIndex > (totalItemsNumber - 5)
            }
        }

        LaunchedEffect(shouldFetchMore) {
            if (shouldFetchMore) {
                fetchNewRecentTasks()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(PurrfectPalette.backgroundGradient))

            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(controlsHeight))

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
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 16.dp,
                            bottom = routes.bottomPadding + 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (activeTasks.isEmpty() && recentTasks.isEmpty()) {
                            item {
                                AphelionTasksEmptyState(translation["no_tasks"])
                            }
                        }

                        items(activeTasks, key = { it.task.hash }) { pendingTask ->
                            TaskCard(
                                modifier = Modifier.fillMaxWidth(),
                                task = pendingTask.task,
                                pendingTask = pendingTask
                            )
                        }

                        items(recentTasks.filter { task -> activeTasks.none { it.task.hash == task.hash } }, key = { it.hash }) { task ->
                            TaskCard(
                                modifier = Modifier.fillMaxWidth(),
                                task = task
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Column(modifier = Modifier.headerHeightTracker { controlsHeight = it }) {
                me.eternal.purrfectsnap.ui.manager.components.FloatingTopBar(
                    title = translation["manager.routes.tasks"],
                    subtitle = translation["tasks_tagline"],
                    scrollOffset = computedScrollOffset,
                    enableMorph = true,
                    actions = {
                        topBarActions()
                    }
                )
            }
        }
    }

    internal fun mergeSelection(files: List<Pair<Task, DocumentFile>>) {
        val taskHash = System.nanoTime().toString(36)
        val firstTask = files.first().first
        val pendingTask = context.taskManager.createPendingTask(Task(
            type = TaskType.DOWNLOAD,
            title = firstTask.title,
            author = firstTask.author,
            hash = taskHash
        )).apply {
            status = TaskStatus.RUNNING
        }

        context.coroutineScope.launch(Dispatchers.IO) {
            val filesToMerge = files.mapNotNull { (_, documentFile) ->
                val tempFile = File.createTempFile("merge", ".tmp")
                context.androidContext.contentResolver.openInputStream(documentFile.uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }

            val mergedFile = File.createTempFile("merged", ".mp4")
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
        onToggleDeleteFiles: (Boolean) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        if (!visible) return

        val dialogShape = RoundedCornerShape(24.dp)
        val haptic = LocalHapticFeedback.current
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
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF6B9B),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.White
                            )
                        }

                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PurrfectPalette.textSecondary
                        )

                        if (showDeleteFiles) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleDeleteFiles(!deleteFilesChecked) 
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Checkbox(
                                        checked = deleteFilesChecked,
                                        onCheckedChange = { 
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleDeleteFiles(it) 
                                        },
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
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(context.translation["button.negative"])
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConfirm()
                                },
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
    internal fun TasksEmptyState(text: String) {
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

    @Composable
    internal fun AphelionTasksEmptyState(text: String) {
        TasksEmptyState(text)
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        var showConfirmDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        if (taskSelection.size > 1) {
            val canMergeSelection by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(taskSelection.size)) {
                taskSelection.all { it.second?.type?.contains("video") == true }
            }

            if (canMergeSelection) {
                TopBarActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showConfirmDialog = true
        }) {
            Icon(Icons.Filled.Delete, contentDescription = translation["clear_button_description"])
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
    internal fun TaskCard(modifier: Modifier, task: Task, pendingTask: PendingTask? = null) {
        var taskStatus by remember { mutableStateOf(task.status) }
        var taskProgressLabel by remember { mutableStateOf<String?>(null) }
        var taskProgress by remember { mutableIntStateOf(-1) }
        val isSelected by remember { derivedStateOf { taskSelection.any { it.first == task } } }
        val haptic = LocalHapticFeedback.current

        var documentFileMimeType by remember { mutableStateOf("") }
        var isDocumentFileReadable by remember { mutableStateOf(true) }
        
        val docVal = task.extra?.toUri()
        val documentFile by rememberAsyncMutableState(defaultValue = null as DocumentFile?, keys = arrayOf(taskStatus.name)) {
            if (docVal == null) null
            else DocumentFile.fromSingleUri(context.androidContext, docVal)?.apply {
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
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (taskSelection.isNotEmpty()) {
                            toggleSelection()
                            return@detectTapGestures
                        }
                        openFile()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

        Surface(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(PurrfectPalette.cardOverlay)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        tonalElevation = 0.dp,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            PurrfectPalette.glowPrimary.copy(alpha = 0.22f),
                                            PurrfectPalette.glowSecondary.copy(alpha = 0.18f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            documentFile?.let { doc ->
                                if (documentFileMimeType.contains("image")) {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            ImageRequest.Builder(LocalContext.current)
                                                .data(doc.uri)
                                                .size(120)
                                                .crossfade(true)
                                                .build()
                                        ),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = when {
                                            !isDocumentFileReadable -> Icons.Filled.DeleteOutline
                                            documentFileMimeType.contains("video") -> Icons.Filled.Videocam
                                            documentFileMimeType.contains("audio") -> Icons.Filled.MusicNote
                                            else -> Icons.Filled.FileCopy
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            } ?: run {
                                Icon(
                                    imageVector = when (task.type) {
                                        TaskType.DOWNLOAD -> Icons.Filled.Download
                                        TaskType.CHAT_ACTION -> Icons.Filled.ChatBubble
                                        TaskType.SCHEDULED_SEND -> Icons.Filled.Schedule
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        task.author?.takeIf { it != "null" }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = PurrfectPalette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isActive) {
                            taskProgressLabel?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                            if (taskProgress != -1) {
                                LinearProgressIndicator(
                                    progress = { taskProgress.toFloat() / 100f },
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = PurrfectPalette.glowSecondary,
                                    trackColor = Color.White.copy(alpha = 0.12f)
                                )
                            }
                        } else {
                            chipLabel?.let { label ->
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    leadingIcon = chipIcon?.let { { Icon(it, null, modifier = Modifier.size(14.dp)) } },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = chipColors,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }

                    if (isActive) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pendingTask?.cancel()
                        }) {
                            Icon(Icons.Filled.Close, null, tint = Color(0xFFFF6B9B))
                        }
                    } else if (taskStatus == TaskStatus.SUCCESS) {
                        Icon(Icons.Filled.Check, null, tint = PurrfectPalette.glowSecondary)
                    }
                }
            }
        }
    }
}
