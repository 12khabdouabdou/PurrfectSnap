package me.eternal.purrfectsnap.ui.manager.pages.themes.aphelion

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
import me.eternal.purrfectsnap.common.ui.TopBarActionButton
import me.eternal.purrfectsnap.common.ui.rememberAsyncMutableState
import me.eternal.purrfectsnap.task.*
import me.eternal.purrfectsnap.ui.manager.Routes
import me.eternal.purrfectsnap.ui.manager.pages.TasksRootSection
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette
import me.eternal.purrfectsnap.ui.util.OnLifecycleEvent
import me.eternal.purrfectsnap.ui.util.coil.cacheKey
import me.eternal.purrfectsnap.ui.util.scaleOnPress
import me.eternal.purrfectsnap.ui.util.Motion
import me.eternal.purrfectsnap.ui.util.headerHeightTracker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksRootSection.AphelionTasksScreen(nav: NavBackStackEntry) {
    val scrollState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var controlsHeight by remember { mutableStateOf(100.dp) }

    LaunchedEffect(scrollState.firstVisibleItemScrollOffset, scrollState.firstVisibleItemIndex) {
        val offset = if (scrollState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else scrollState.firstVisibleItemScrollOffset
        routes.navigation?.globalScrollOffset = offset
    }

    val scope = rememberCoroutineScope()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alsoDeleteFiles by remember { mutableStateOf(false) }

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
        val subtitle = if (activeTasks.isNotEmpty()) {
            translation.format(
                "summary_active",
                "active" to activeTasks.size.toString(),
                "recent" to recentTasks.size.toString()
            )
        } else {
            translation.format(
                "summary_idle",
                "recent" to recentTasks.size.toString()
            )
        }

        // The "Structured Glass" Container (1:1 with build 33a7e8f)
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
            color = Color.White.copy(alpha = 0.04f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            LazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = controlsHeight,
                    bottom = routes.bottomPadding + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    if (activeTasks.isEmpty() && recentTasks.isEmpty()) {
                        AphelionTasksEmptyState(text = translation["no_tasks"] ?: "No tasks")
                    }
                }
                items(activeTasks, key = { it.taskId }) { pendingTask ->
                    AphelionTaskCard(modifier = Modifier.fillMaxWidth(), pendingTask.task, pendingTask = pendingTask)
                }
                items(recentTasks, key = { it.hash }) { task ->
                    AphelionTaskCard(modifier = Modifier.fillMaxWidth(), task)
                }
                item {
                    Spacer(modifier = Modifier.height(40.dp))
                    LaunchedEffect(remember { derivedStateOf { scrollState.firstVisibleItemIndex } }) {
                        fetchNewRecentTasks()
                    }
                }
            }
        }

        me.eternal.purrfectsnap.ui.manager.components.FloatingTopBar(
            title = context.translation["manager.routes.tasks"] ?: "Tasks",
            subtitle = subtitle,
            scrollOffset = routes.navigation?.globalScrollOffset ?: 0,
            enableMorph = true,
            modifier = Modifier.headerHeightTracker { controlsHeight = it },
            actions = {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlaylistAddCheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = activeTasks.size.toString(),
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }
                if (taskSelection.size > 1 && taskSelection.all { it.second?.type?.contains("video") == true }) {     
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            mergeSelection(
                                taskSelection.toList().also { taskSelection.clear() }
                                    .map { it.first to it.second!! }
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = PurrfectPalette.glowPrimary.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, PurrfectPalette.glowPrimary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.Merge, contentDescription = translation["merge_button"], tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(translation["merge_button"], color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)     
                    showConfirmDialog = true
                }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = translation["clear_button_description"], tint = Color.White)
                }
            }
        )
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

@Composable
internal fun TasksRootSection.AphelionTasksEmptyState(text: String) {
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
internal fun TasksRootSection.AphelionTaskCard(modifier: Modifier, task: Task, pendingTask: PendingTask? = null) {
    var taskStatus by remember { mutableStateOf(task.status) }
    var taskProgressLabel by remember { mutableStateOf<String?>(null) }
    var taskProgress by remember { mutableIntStateOf(-1) }
    val isSelected by remember { derivedStateOf { taskSelection.any { it.first == task } } }

    var documentFileMimeType by remember { mutableStateOf("") }
    var isDocumentFileReadable by remember { mutableStateOf(true) }
    
    val docVal = task.extra?.toUri()
    val documentFile by rememberAsyncMutableState(
        defaultValue = null as DocumentFile?,
        keys = arrayOf(taskStatus.name)
    ) {
        if (docVal == null) null
        else DocumentFile.fromSingleUri(context.androidContext, docVal)?.apply {
            documentFileMimeType = type ?: ""
            isDocumentFileReadable = canRead()
        }
    }

    val listener = remember { PendingTaskListener(
        onStateChange = { taskStatus = it },
        onProgress = { label, progress ->
            taskProgressLabel = label
            taskProgress = progress
        }
    ) }

    LaunchedEffect(Unit) { pendingTask?.addListener(listener) }
    DisposableEffect(Unit) { onDispose { pendingTask?.removeListener(listener) } }

    val haptic = LocalHapticFeedback.current
    val isActive = pendingTask != null && !taskStatus.isFinalStage()

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
            context.shortToast(translation["failed_to_open_file"] ?: "Failed to open file")
        }
    }

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
                it.border(2.dp, PurrfectPalette.glowSecondary, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
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
        border = BorderStroke(1.dp, if (isSelected) Brush.linearGradient(listOf(PurrfectPalette.glowPrimary, PurrfectPalette.glowSecondary)) else SolidColor(Color.White.copy(alpha = 0.1f)))
    ) {
        Row(modifier = Modifier.background(PurrfectPalette.cardOverlay, cardShape).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.padding(end = 15.dp).size(50.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                var loadFailed by remember { mutableStateOf(false) }
                val doc = documentFile
                if (taskStatus.isFinalStage() && isDocumentFileReadable && !loadFailed && doc != null && (documentFileMimeType.contains("image") || documentFileMimeType.contains("video"))) {
                    val imageRequest = ImageRequest.Builder(context.androidContext)
                        .data(doc.uri)
                        .cacheKey(doc.uri.toString())
                        .placeholder(ColorDrawable(PurrfectPalette.cardOverlayColor.toArgb()))
                        .build()
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = imageRequest,
                            imageLoader = context.imageLoader,
                            onState = { state ->
                                if (state is coil.compose.AsyncImagePainter.State.Error) loadFailed = true
                            }
                        ),
                        contentDescription = null, 
                        contentScale = ContentScale.FillWidth, 
                        modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.medium)
                    )
                } else {
                    when {
                        !isDocumentFileReadable -> Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        documentFileMimeType.contains("image") -> Icon(Icons.Filled.Photo, contentDescription = null)
                        documentFileMimeType.contains("video") -> Icon(Icons.Filled.Videocam, contentDescription = null)
                        documentFileMimeType.contains("audio") -> Icon(Icons.Filled.MusicNote, contentDescription = null)
                        else -> Icon(Icons.Filled.FileCopy, contentDescription = null)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                if (task.type == TaskType.SCHEDULED_SEND) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(context.translation.getOrNull("scheduled_send_title") ?: "Scheduled Snaps", style = MaterialTheme.typography.labelMedium, color = PurrfectPalette.textSecondary)
                        Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        task.author?.takeIf { it != "null" }?.let { recipients ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.People, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = PurrfectPalette.textSecondary)
                                recipients.split(", ").let { list ->
                                    Text(list.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = Color.White, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        task.author?.takeIf { it != "null" }?.let {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = PurrfectPalette.textSecondary)
                        }
                    }
                    Text(task.hash, style = MaterialTheme.typography.labelSmall, color = PurrfectPalette.textSecondary)
                }
                
                Column(modifier = Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    chipLabel?.let { label ->
                        val leadingIcon: (@Composable () -> Unit)? = if (isActive && task.type == TaskType.SCHEDULED_SEND) {
                            { Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else if (isActive) {
                            countdownText?.let { countdown -> { Text(countdown, style = MaterialTheme.typography.labelSmall) } }
                        } else chipIcon?.let { icon -> { Icon(icon, contentDescription = null) } }
                        
                        val displayLabel = if (isActive && task.type == TaskType.SCHEDULED_SEND && countdownText != null) {
                            translation.getOrNull("schedule_sending_in")?.replace("{time}", countdownText) ?: "Sending in $countdownText"
                        } else label
                        
                        AssistChip(onClick = {}, enabled = false, leadingIcon = leadingIcon, label = { Text(displayLabel) }, colors = chipColors)
                    }
                    
                    if (!taskStatus.isFinalStage()) {
                        if (!isActive) {
                            taskProgressLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
                        }
                        if (taskProgress != -1 && taskProgressLabel == null) {
                            LinearProgressIndicator(
                                progress = { taskProgress.toFloat() / 100f },
                                strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth(),
                                color = PurrfectPalette.glowSecondary, trackColor = Color.White.copy(alpha = 0.12f)
                            )
                        }
                        if (!isActive) {
                            task.extra?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PurrfectPalette.textSecondary) }
                        }
                    }
                }
            }
            
            Column {
                if (isActive) {
                    FilledIconButton(
                        onClick = {
                            runCatching { pendingTask?.cancel() }.onFailure { throwable ->
                                context.log.error("Failed to cancel task $pendingTask", throwable)
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFFF6B9B).copy(alpha = 0.35f), contentColor = Color.White)
                    ) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                } else if (taskStatus == TaskStatus.SUCCESS) {
                    AnimatedVisibility(
                        visible = true, 
                        enter = fadeIn(tween(250)) + scaleIn(tween(300)), 
                        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.5f, animationSpec = tween(150))
                    ) {
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(PurrfectPalette.glowPrimary.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
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
