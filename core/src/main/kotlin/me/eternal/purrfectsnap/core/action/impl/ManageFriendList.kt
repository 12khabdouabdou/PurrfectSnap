package me.eternal.purrfectsnap.core.action.impl

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.eternal.purrfectsnap.common.data.FriendLinkType
import me.eternal.purrfectsnap.common.ui.createComposeAlertDialog
import me.eternal.purrfectsnap.core.action.AbstractAction
import me.eternal.purrfectsnap.core.event.events.impl.ActivityResultEvent
import me.eternal.purrfectsnap.core.features.impl.experiments.AddFriendSourceSpoof
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.wrapper.impl.Snapchatter
import kotlin.random.Random

class ManageFriendList : AbstractAction() {
    private val translation by lazy { context.translation.getCategory("friend_list") }
    private val dialogBackground = Brush.verticalGradient(
        listOf(
            Color(0xFF1D1538),
            Color(0xFF130F2A)
        )
    )
    private val panelOverlay = Brush.linearGradient(
        listOf(
            Color(0xFF2C2551),
            Color(0xFF1B1636)
        )
    )
    private val accentGradient = Brush.horizontalGradient(
        listOf(
            Color(0xFF8C7BFF),
            Color(0xFF5FD8FF)
        )
    )
    private var pendingPickerAction: Pair<Int, (data: Uri) -> Unit>? = null

    private val uuidRegex by lazy {
        Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }

    private fun loadStaticDefaultField(classLoader: ClassLoader, className: String, fieldName: String): Any? {
        return try {
            val clazz = classLoader.loadClass(className)
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(null)
            } catch (e: Exception) {
                clazz.declaredFields
                    .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .firstOrNull()
                    ?.apply { isAccessible = true }
                    ?.get(null)
            }
        } catch (e: Exception) {
            context.log.warn("Could not load $className: ${e.message}")
            null
        }
    }

    private fun addFriend(userId: String) {
        val friendRelationshipChangerInstance = context.feature(AddFriendSourceSpoof::class).friendRelationshipChangerInstance
        if (friendRelationshipChangerInstance == null) {
            context.log.error("friendRelationshipChangerInstance is null")
            context.longToast("Failed to add friend: FriendRelationshipChanger instance not available")
            return
        }

        runCatching {
            val classLoader = context.androidContext.classLoader
            
            val classNamesToTry = listOf("EnumC3559qC", "qC", "com.snapchat.android.EnumC3559qC", "LC", "EnumC0886LC", "EnumC1539TC", "TC")
            
            val enumC3559qCClass = classNamesToTry.firstNotNullOfOrNull { className ->
                try {
                    classLoader.loadClass(className).takeIf { it.isEnum }?.also {
                        context.log.verbose("Successfully loaded enum class: $className")
                    }
                } catch (e: Exception) {
                    null
                }
            }

            if (enumC3559qCClass == null) {
                context.log.error("Friend source enum class not found after trying: ${classNamesToTry.joinToString()}")
                context.longToast("Failed to add friend: Required enum class not found")
                return@runCatching
            }

            val enumConstants = enumC3559qCClass.enumConstants
                ?: enumC3559qCClass.getMethod("values").invoke(null) as? Array<*>
                ?: run {
                    context.log.error("Could not retrieve enum constants from ${enumC3559qCClass.name}")
                    context.longToast("Failed to add friend: Enum constants not accessible")
                    return@runCatching
                }
            
            context.log.verbose("Found ${enumConstants.size} enum constants")

            val addedByUsername = enumConstants.firstOrNull { it.toString() == "ADDED_BY_USERNAME" }
                ?: enumConstants.firstOrNull { it.toString().contains("USERNAME", ignoreCase = true) }
                ?: enumConstants.firstOrNull()
                ?: run {
                    context.log.error("No enum constants available")
                    context.longToast("Failed to add friend: No valid enum constant found")
                    return@runCatching
                }
            
            context.log.verbose("Using enum constant: $addedByUsername")

            val sQ7Default = loadStaticDefaultField(classLoader, "sQ7", "f288251e0")
            val zQ7Default = loadStaticDefaultField(classLoader, "ZQ7", "f159958F0")

            val iBgClass = try {
                classLoader.loadClass("iBg")
            } catch (e: Exception) {
                context.log.error("Could not load iBg class: ${e.message}")
                null
            }

            if (iBgClass == null) {
                context.log.error("iBg class not found")
                return@runCatching
            }

            val m51157aMethod = iBgClass.declaredMethods.firstOrNull { it.name == "m51157a" }
                ?: iBgClass.methods.firstOrNull { it.name == "m51157a" }
                ?: iBgClass.declaredMethods.firstOrNull { method ->
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 14 &&
                    method.parameterTypes[0].isAssignableFrom(friendRelationshipChangerInstance.javaClass) &&
                    method.parameterTypes[1] == String::class.java &&
                    method.parameterTypes[2] == enumC3559qCClass
                }

            if (m51157aMethod == null) {
                context.log.error("Could not find iBg.m51157a method")
                context.log.error("Available static methods with 14 params:")
                iBgClass.declaredMethods.filter {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 14
                }.forEach { method ->
                    context.log.error("  ${method.name}: ${method.parameterTypes.joinToString { it.simpleName }}")
                }
                return@runCatching
            }
            m51157aMethod.isAccessible = true

            try {
                m51157aMethod.invoke(
                    null,
                    friendRelationshipChangerInstance,
                    userId,
                    addedByUsername,
                    sQ7Default,
                    zQ7Default,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    4064
                )?.also { result ->
                    result.javaClass.methods
                        .find { it.name == "subscribe" && it.parameterCount == 0 }
                        ?.apply { isAccessible = true }
                        ?.invoke(result)
                }
            } catch (e: Exception) {
                context.log.error("Exception during method invocation: ${e.javaClass.name}: ${e.message}")
                e.cause?.let { cause ->
                    context.log.error("Cause: ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace?.take(5)?.forEach {
                        context.log.error("  at $it")
                    }
                }
                throw e
            }
        }.onFailure {
            context.log.error("Failed to add friend $userId", it)
            context.longToast("Failed to add friend: ${it.message}")
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

    private fun exportFriends(
        userIds: List<String>
    ) {
        pendingPickerAction = Random.nextInt(0, 65535) to { data ->
            context.androidContext.contentResolver.openOutputStream(data).use { output ->
                output?.bufferedWriter()?.use { writer ->
                    userIds.forEach {
                        writer.write(it)
                        writer.newLine()
                    }
                }
                context.longToast("Exported ${userIds.size} friends!")
            }
        }
        context.mainActivity?.startActivityForResult(
            Intent.createChooser(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "my_friends.txt")
                },
                "Select a location to save the file"
            ),
            pendingPickerAction!!.first
        )
    }

    private val userIdToSnapchatter = mutableMapOf<String, Snapchatter>()

    @Composable
    private fun ManagerDialog() {
        val pendingFriendRequests = remember { mutableStateMapOf<String, Job>() }
        var fetchedFriends by remember { mutableStateOf<List<String>?>(null) }
        val coroutineScope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(dialogBackground)
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 44.dp, y = (-36).dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF8C7BFF).copy(alpha = 0.32f), Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .align(Alignment.BottomStart)
                        .offset(x = (-60).dp, y = 28.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF5FD8FF).copy(alpha = 0.3f), Color.Transparent)
                            )
                        )
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .heightIn(min = 260.dp)
                    .border(1.2.dp, accentGradient, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 0.dp,
                color = Color.White.copy(alpha = 0.04f)
            ) {
                if (fetchedFriends == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(panelOverlay)
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                                    .padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.People,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(28.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    translation.get("manage_title"),
                                    color = Color.White,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = translation.get("export_description"),
                                    color = Color(0xFFD9D3FF),
                                    fontSize = 14.sp
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PrimaryButton(
                                text = translation.get("export_friends"),
                                modifier = Modifier.weight(1f)
                            ) {
                                exportFriends(context.database.getAllFriends().filter { it.friendLinkType == FriendLinkType.MUTUAL.value && it.addedTimestamp > 0L }.mapNotNull { it.userId })
                            }
                            SecondaryButton(
                                text = translation.get("import_from_file"),
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingPickerAction = Random.nextInt(0, 65535) to { data ->
                                    runCatching {
                                        fetchedFriends = null
                                        context.androidContext.contentResolver.openInputStream(data).use { input ->
                                            fetchedFriends = input?.bufferedReader()?.readLines()?.filter {
                                                it.matches(uuidRegex)
                                            }?.map { it.trim() }?.toMutableList() ?: mutableListOf()
                                        }
                                    }.onFailure {
                                        context.log.error("Failed to import friends", it)
                                        context.longToast("Failed to import friends: ${it.message}")
                                    }
                                }
                                context.mainActivity?.startActivityForResult(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" },
                                        "Select a file"
                                    ),
                                    pendingPickerAction!!.first
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp)
                            .background(panelOverlay)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                                    .clickable { fetchedFriends = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                    contentDescription = context.translation["common.back"],
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = translation.get("manage_title"),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = translation.get("export_description"),
                                    color = Color(0xFFD9D3FF),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(modifier = Modifier.size(46.dp))
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 0.dp,
                            color = Color.White.copy(alpha = 0.04f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            ) {
                                item {
                                    if (fetchedFriends?.isEmpty() == true) {
                                        BasicText(
                                            context.translation["common.no_friends_found"],
                                            style = androidx.compose.ui.text.TextStyle(color = Color(0xFFA8B5D1), fontSize = 13.sp),
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                                items(fetchedFriends ?: emptyList()) { userId ->
                                    fun fetchLocalLinkType(): FriendLinkType? {
                                        return context.database.getFriendInfo(userId)?.friendLinkType?.let { FriendLinkType.fromValue(it) }
                                    }

                                    fun isActuallyAdded(): Boolean {
                                        val friendInfo = context.database.getFriendInfo(userId)
                                        return friendInfo != null && 
                                               (friendInfo.friendLinkType == FriendLinkType.MUTUAL.value || 
                                                friendInfo.friendLinkType == FriendLinkType.OUTGOING.value) && 
                                               friendInfo.addedTimestamp > 0L
                                    }

                                    var friendSnapchatter by remember(userId) { mutableStateOf<Snapchatter?>(null) }
                                    var failedToFetch by remember(userId) { mutableStateOf(false) }
                                    var friendLinkType by remember(userId) { mutableStateOf(fetchLocalLinkType()) }
                                    var actuallyAdded by remember(userId) { mutableStateOf(isActuallyAdded()) }

                                    LaunchedEffect(userId) {
                                        launch(Dispatchers.IO) {
                                            friendSnapchatter = userIdToSnapchatter.getOrPut(userId) {
                                                context.feature(Messaging::class).fetchSnapchatterInfos(listOf(userId)).firstOrNull() ?: run {
                                                    failedToFetch = true
                                                    return@launch
                                                }
                                            }
                                        }
                                        
                                        // Polling loop to keep status in sync (like FriendList.kt)
                                        while (true) {
                                            delay(2000)
                                            val newLinkType = fetchLocalLinkType()
                                            if (newLinkType != friendLinkType) {
                                                friendLinkType = newLinkType
                                            }
                                            val newActuallyAdded = isActuallyAdded()
                                            if (newActuallyAdded != actuallyAdded) {
                                                actuallyAdded = newActuallyAdded
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 6.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White.copy(alpha = 0.05f))
                                            .border(1.dp, accentGradient, RoundedCornerShape(14.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            BasicText(
                                                friendSnapchatter?.let { snapchatter ->
                                                    snapchatter.displayName?.let { "$it (${snapchatter.username}) " } ?: snapchatter.username ?: context.translation["common.unknown"]
                                                } ?: userId,
                                                style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            )
                                            BasicText(
                                                userId,
                                                style = androidx.compose.ui.text.TextStyle(color = Color(0xFFB1B4D7), fontSize = 12.sp)
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                friendLinkType?.let { type ->
                                                    StatusPill(
                                                        text = type.name.lowercase().replaceFirstChar { it.uppercase() },
                                                        color = if (type == FriendLinkType.MUTUAL) Color(0xFF8EF0F3) else Color(0xFFD9D3FF)
                                                    )
                                                }
                                                if (failedToFetch) {
                                                    StatusPill(
                                                        text = translation.get("failed_to_fetch") ?: "Fetch failed",
                                                        color = Color(0xFFF4B4B4)
                                                    )
                                                }
                                            }
                                        }

                                        if (friendSnapchatter != null) {
                                            val isPending = pendingFriendRequests.containsKey(userId) && pendingFriendRequests[userId]?.isActive != false
                                            val isFollowing = friendLinkType == FriendLinkType.FOLLOWING
                                            
                                            PrimaryButton(
                                                text = when {
                                                    isFollowing -> "Following"
                                                    actuallyAdded -> context.translation["common.added"]
                                                    isPending -> translation.get("adding") ?: "Adding..."
                                                    else -> translation.get("add")
                                                },
                                                modifier = Modifier.widthIn(min = 110.dp),
                                                enabled = !actuallyAdded && !isPending && !isFollowing
                                             ) {
                                                if (actuallyAdded || isPending || isFollowing) return@PrimaryButton
                                                
                                                val job = coroutineScope.launch {
                                                    try {
                                                        addFriend(userId)
                                                        delay(300)
                                                        actuallyAdded = true
                                                        withTimeout(3000) {
                                                            var attempts = 0
                                                            while (attempts < 12) {
                                                                val currentLinkType = fetchLocalLinkType()
                                                                if (currentLinkType == FriendLinkType.MUTUAL || 
                                                                    currentLinkType == FriendLinkType.FOLLOWING ||
                                                                    currentLinkType == FriendLinkType.OUTGOING) {
                                                                    friendLinkType = currentLinkType
                                                                    actuallyAdded = true
                                                                    break
                                                                }
                                                                attempts++
                                                                delay(250)
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        context.log.error("Failed to add friend or verify status: ${e.message}")
                                                        actuallyAdded = true
                                                    }
                                                }.apply {
                                                    invokeOnCompletion {
                                                        pendingFriendRequests.remove(userId)
                                                        friendLinkType = fetchLocalLinkType()
                                                        actuallyAdded = isActuallyAdded() || actuallyAdded
                                                    }
                                                }
                                                pendingFriendRequests[userId] = job
                                            }
                                            if (isPending) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color(0xFF8EF0F3)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PrimaryButton(
        text: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = modifier
                .clip(shape)
                .alpha(if (enabled) 1f else 0.6f)
                .background(accentGradient)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = androidx.compose.ui.text.TextStyle(color = Color(0xFF0A0F1D), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }

    @Composable
    private fun SecondaryButton(
        text: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = modifier
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = androidx.compose.ui.text.TextStyle(color = Color(0xFFE6ECFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }

    @Composable
    private fun StatusPill(text: String, color: Color) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            createComposeAlertDialog(context.mainActivity!!) {
                ManagerDialog()
            }.apply {
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }
}
