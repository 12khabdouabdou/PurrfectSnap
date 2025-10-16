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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.FriendLinkType
import me.rhunk.snapenhance.common.database.impl.ConversationMessage
import me.rhunk.snapenhance.common.database.impl.FriendInfo
import me.rhunk.snapenhance.common.database.impl.FriendFeedEntry
import me.rhunk.snapenhance.common.messaging.MessagingConstraints
import me.rhunk.snapenhance.common.messaging.MessagingTask
import me.rhunk.snapenhance.common.messaging.MessagingTaskType
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.common.util.ktx.copyToClipboard
import me.rhunk.snapenhance.common.util.snap.BitmojiSelfie
import me.rhunk.snapenhance.common.util.snap.RemoteMediaResolver
import me.rhunk.snapenhance.core.action.AbstractAction
import me.rhunk.snapenhance.core.features.impl.experiments.AddFriendSourceSpoof
import me.rhunk.snapenhance.core.features.impl.experiments.BetterLocation
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.ui.ViewAppearanceHelper
import me.rhunk.snapenhance.core.util.EvictingMap
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.mapper.impl.FriendRelationshipChangerMapper
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

class BulkMessagingAction : AbstractAction() {
    enum class SortBy {
        NONE,
        USERNAME,
        ADDED_TIMESTAMP,
        SNAP_SCORE,
        STREAK_LENGTH,
        MOST_MESSAGES_SENT,
        MOST_RECENT_MESSAGE,
        NEAREST_LOCATION
    }

    enum class Filter {
        ALL,
        MY_FRIENDS,
        BLOCKED,
        REMOVED_ME,
        DELETED,
        SUGGESTED,
        BUSINESS_ACCOUNTS,
        STREAKS,
        NON_STREAKS,
        FOLLOWED,
        LOCATION_ON_MAP
    }

    enum class ConversationType {
        FRIENDS_ONLY,
        GROUPS_ONLY,
        BOTH
    }

    private val translation by lazy { context.translation.getCategory("bulk_messaging_action") }
    private val betterLocation by lazy { context.feature(BetterLocation::class) }

    private fun removeAction(
        ctx: Context,
        ids: List<String>,
        delay: Pair<Long, Long>,
        action: suspend (id: String, setDialogMessage: (String) -> Unit) -> Unit = { _, _ -> }
    ) = context.coroutineScope.launch {
        val statusTextView = TextView(ctx)
        val dialog = withContext(Dispatchers.Main) {
            ViewAppearanceHelper.newAlertDialogBuilder(ctx)
                .setTitle("...")
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

        ids.forEachIndexed { index, id ->
            launch(Dispatchers.Main) {
                dialog.setTitle(
                    translation.format("progress_status", "index" to (index + 1).toString(), "total" to ids.size.toString())
                )
            }
            runCatching {
                action(id) {
                    launch(Dispatchers.Main) {
                        statusTextView.text = it
                    }
                }
            }.onFailure {
                context.log.error("Failed to process $it", it)
                context.shortToast(translation.format("failed_to_process", "id" to id))
            }
            delay(Random.nextLong(delay.first, delay.second))
        }
        withContext(Dispatchers.Main) {
            dialog.dismiss()
        }
    }

    @Composable
    private fun ConfirmationDialog(
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(text = translation["confirmation_dialog.title"]) },
            text = { Text(text = translation["confirmation_dialog.message"]) },
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

    private fun filterFriends(friends: List<FriendInfo>, filter: Filter, nameFilter: String): List<FriendInfo> {
        val userIdBlacklist = arrayOf(
            context.database.myUserId,
            "b42f1f70-5a8b-4c53-8c25-34e7ec9e6781", // myai
            "84ee8839-3911-492d-8b94-72dd80f3713a", // teamsnapchat
        )

        // Debug: Log all unique friendLinkType values
        if (filter == Filter.FOLLOWED) {
            val uniqueLinkTypes = friends.map { it.friendLinkType }.distinct().sorted()
            context.log.verbose("BulkMessaging: All unique friendLinkType values in database: $uniqueLinkTypes")
            context.log.verbose("BulkMessaging: FOLLOWING enum value: ${FriendLinkType.FOLLOWING.value}")
        }

        val result = friends.filter { friend ->
            friend.userId !in userIdBlacklist && when (filter) {
                Filter.ALL -> true
                Filter.MY_FRIENDS -> friend.friendLinkType == FriendLinkType.MUTUAL.value && friend.addedTimestamp > 0
                Filter.BLOCKED -> friend.friendLinkType == FriendLinkType.BLOCKED.value
                Filter.REMOVED_ME -> friend.friendLinkType == FriendLinkType.OUTGOING.value && friend.addedTimestamp > 0 && friend.businessCategory == 0 // ignore followed accounts
                Filter.SUGGESTED -> friend.friendLinkType == FriendLinkType.SUGGESTED.value
                Filter.DELETED -> friend.friendLinkType == FriendLinkType.DELETED.value
                Filter.BUSINESS_ACCOUNTS -> friend.businessCategory > 0
                Filter.STREAKS -> friend.friendLinkType == FriendLinkType.MUTUAL.value && friend.addedTimestamp > 0 && friend.streakLength != 0
                Filter.NON_STREAKS -> friend.friendLinkType == FriendLinkType.MUTUAL.value&& friend.addedTimestamp > 0 && friend.streakLength == 0
                Filter.FOLLOWED -> {
                    // Check multiple possible values for followed friends
                    val isFollowed = friend.friendLinkType == FriendLinkType.FOLLOWING.value || 
                                   friend.friendLinkType == FriendLinkType.OUTGOING.value ||
                                   (friend.businessCategory > 0 && friend.friendLinkType == FriendLinkType.OUTGOING.value)
                    context.log.verbose("BulkMessaging: Friend ${friend.mutableUsername} (${friend.userId}) - LinkType=${friend.friendLinkType}, BusinessCategory=${friend.businessCategory}, Match=$isFollowed")
                    isFollowed
                }
                Filter.LOCATION_ON_MAP -> betterLocation.locationHistory.contains(friend.userId)
            } && nameFilter.takeIf { it.isNotBlank() }?.let { name ->
                friend.mutableUsername?.contains(
                    name,
                    ignoreCase = true
                ) == true || friend.displayName?.contains(name, ignoreCase = true) == true
            } ?: true
        }
        
        if (filter == Filter.FOLLOWED) {
            context.log.verbose("BulkMessaging: FOLLOWED filter returned ${result.size} friends")
        }
        
        return result
    }

    private fun getDMLastMessage(userId: String?): ConversationMessage? {
        return context.database.getDMConversationId(userId ?: return null)?.let {
            context.database.getMessagesFromConversationId(it, 1)
        }?.firstOrNull()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun BulkMessagingDialog() {
        val coroutineScope = rememberCoroutineScope { Dispatchers.IO }
        var sortBy by remember { mutableStateOf(SortBy.USERNAME) }
        var filter by remember { mutableStateOf(Filter.REMOVED_ME) }
        var conversationType by remember { mutableStateOf(ConversationType.FRIENDS_ONLY) }
        var sortReverseOrder by remember { mutableStateOf(false) }
        val selectedFriends = remember { mutableStateListOf<String>() }
        val selectedGroups = remember { mutableStateListOf<String>() }
        val friends = remember { mutableStateListOf<FriendInfo>() }
        val groups = remember { mutableStateListOf<FriendFeedEntry>() }
        val bitmojiCache = remember { EvictingMap<String, Bitmap>(50) }
        val noBitmojiBitmap = remember { BitmapFactory.decodeResource(context.resources, android.R.drawable.ic_menu_report_image).asImageBitmap() }

        val focusManager = LocalFocusManager.current
        var nameFilter by remember { mutableStateOf("") }

        suspend fun refreshList(clearSelected: Boolean = true) {
            val myLocation = betterLocation.locationHistory[context.database.myUserId]

            withContext(Dispatchers.IO) {
                // Load friends if needed
                val newFriends = if (conversationType == ConversationType.FRIENDS_ONLY || conversationType == ConversationType.BOTH) {
                    context.database.getAllFriends().let { friends ->
                        val allFriends = friends.size
                        val filtered = filterFriends(friends, filter, nameFilter)
                        context.log.verbose("BulkMessaging: Filter=$filter, All friends=$allFriends, Filtered=${filtered.size}")
                        filtered
                    }.toMutableList()
                } else {
                    mutableListOf()
                }
                
                // Load groups if needed
                val newGroups = if (conversationType == ConversationType.GROUPS_ONLY || conversationType == ConversationType.BOTH) {
                    context.database.getFeedEntries(Int.MAX_VALUE).filter { it.conversationType == 1 && 
                        (nameFilter.isBlank() || it.feedDisplayName?.contains(nameFilter, ignoreCase = true) == true)
                    }.toMutableList()
                } else {
                    mutableListOf()
                }
                
                when (sortBy) {
                    SortBy.NONE -> {}
                    SortBy.USERNAME -> {
                        newFriends.sortBy { it.mutableUsername }
                        newGroups.sortBy { it.feedDisplayName }
                    }
                    SortBy.ADDED_TIMESTAMP -> newFriends.sortBy { it.addedTimestamp }
                    SortBy.SNAP_SCORE -> newFriends.sortBy { it.snapScore }
                    SortBy.STREAK_LENGTH -> newFriends.sortBy { it.streakLength }
                    SortBy.MOST_MESSAGES_SENT -> newFriends.sortByDescending {
                        getDMLastMessage(it.userId)?.serverMessageId ?: 0
                    }
                    SortBy.MOST_RECENT_MESSAGE -> {
                        newFriends.sortByDescending {
                            getDMLastMessage(it.userId)?.creationTimestamp
                        }
                        newGroups.sortByDescending { it.lastInteractionTimestamp }
                    }
                    SortBy.NEAREST_LOCATION -> {
                        if (myLocation != null) {
                            newFriends.sortBy {
                                betterLocation.locationHistory[it.userId]?.distanceTo(myLocation)
                                    ?: Double.MAX_VALUE
                            }
                        }
                    }
                }
                if (sortReverseOrder) {
                    newFriends.reverse()
                    newGroups.reverse()
                }
                withContext(Dispatchers.Main) {
                    if (clearSelected) {
                        selectedFriends.clear()
                        selectedGroups.clear()
                    }
                    friends.clear()
                    friends.addAll(newFriends)
                    groups.clear()
                    groups.addAll(newGroups)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top tabs: Friends Only | Groups Only | Friends & Groups
            val tabs = remember {
                listOf(
                    ConversationType.FRIENDS_ONLY to translation["conversation_types.friends_only"],
                    ConversationType.GROUPS_ONLY to translation["conversation_types.groups_only"],
                    ConversationType.BOTH to translation["conversation_types.both"],
                )
            }
            TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == conversationType }.coerceAtLeast(0)) {
                tabs.forEachIndexed { index, (type, title) ->
                    Tab(
                        selected = conversationType == type,
                        onClick = {
                            if (conversationType != type) {
                                conversationType = type
                                coroutineScope.launch { refreshList() }
                            }
                        },
                        text = { Text(text = title) }
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth())
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var filterMenuExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = filterMenuExpanded,
                    onExpandedChange = { filterMenuExpanded = it },
                ) {
                    ElevatedCard(
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = translation["filters.${filter.name.lowercase()}"],
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = filterMenuExpanded,
                        onDismissRequest = { filterMenuExpanded = false }
                    ) {
                        Filter.entries.forEach { entry ->
                            DropdownMenuItem(
                                onClick = {
                                    filter = entry
                                    filterMenuExpanded = false
                                },
                                text = {
                                    Text(
                                        text = translation["filters.${entry.name.lowercase()}"],
                                        fontWeight = if (entry == filter) FontWeight.Bold else FontWeight.Normal,
                                        color = if (entry == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                modifier = Modifier.background(
                                    if (entry == filter) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                var sortMenuExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = sortMenuExpanded,
                    onExpandedChange = { sortMenuExpanded = it },
                ) {
                    ElevatedCard(
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(text = translation["sort_by"], modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        SortBy.entries.forEach { entry ->
                            DropdownMenuItem(
                                onClick = {
                                    sortBy = entry
                                    sortMenuExpanded = false
                                },
                                text = {
                                    Text(
                                        text = translation["sort_options.${entry.name.lowercase()}"],
                                        fontWeight = if (entry == sortBy) FontWeight.Bold else FontWeight.Normal,
                                        color = if (entry == sortBy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                modifier = Modifier.background(
                                    if (entry == sortBy) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                            )
                        }
                    }
                }

                // Reverse order control removed per user request
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                stickyHeader {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = nameFilter,
                            onValueChange = {
                                nameFilter = it
                                coroutineScope.launch { refreshList(clearSelected = false) }
                            },
                            placeholder = { Text(text = translation["search_by_name"]) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                        )

                        Text(
                            text = context.translation["manager.dialogs.messaging_action.select_all_button"],
                            fontSize = 14.sp
                        )

                        Checkbox(
                            checked = {
                                val totalItems = when (conversationType) {
                                    ConversationType.FRIENDS_ONLY -> friends.size
                                    ConversationType.GROUPS_ONLY -> groups.size
                                    ConversationType.BOTH -> friends.size + groups.size
                                }
                                val selectedItems = when (conversationType) {
                                    ConversationType.FRIENDS_ONLY -> selectedFriends.size
                                    ConversationType.GROUPS_ONLY -> selectedGroups.size
                                    ConversationType.BOTH -> selectedFriends.size + selectedGroups.size
                                }
                                totalItems > 0 && selectedItems == totalItems
                            }(),
                            onCheckedChange = { state ->
                                if (state) {
                                    when (conversationType) {
                                        ConversationType.FRIENDS_ONLY -> {
                                            friends.mapNotNull { it.userId }.forEach { userId ->
                                                if (!selectedFriends.contains(userId)) {
                                                    selectedFriends.add(userId)
                                                }
                                            }
                                        }
                                        ConversationType.GROUPS_ONLY -> {
                                            groups.mapNotNull { it.key }.forEach { conversationId ->
                                                if (!selectedGroups.contains(conversationId)) {
                                                    selectedGroups.add(conversationId)
                                                }
                                            }
                                        }
                                        ConversationType.BOTH -> {
                                            friends.mapNotNull { it.userId }.forEach { userId ->
                                                if (!selectedFriends.contains(userId)) {
                                                    selectedFriends.add(userId)
                                                }
                                            }
                                            groups.mapNotNull { it.key }.forEach { conversationId ->
                                                if (!selectedGroups.contains(conversationId)) {
                                                    selectedGroups.add(conversationId)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    when (conversationType) {
                                        ConversationType.FRIENDS_ONLY -> {
                                            if (nameFilter.isNotBlank()) {
                                                filterFriends(friends, filter, nameFilter).mapNotNull { it.userId }.forEach { userId ->
                                                    selectedFriends.remove(userId)
                                                }
                                            } else {
                                                selectedFriends.clear()
                                            }
                                        }
                                        ConversationType.GROUPS_ONLY -> {
                                            if (nameFilter.isNotBlank()) {
                                                groups.filter { it.feedDisplayName?.contains(nameFilter, ignoreCase = true) == true }
                                                    .mapNotNull { it.key }.forEach { conversationId ->
                                                    selectedGroups.remove(conversationId)
                                                }
                                            } else {
                                                selectedGroups.clear()
                                            }
                                        }
                                        ConversationType.BOTH -> {
                                            if (nameFilter.isNotBlank()) {
                                                filterFriends(friends, filter, nameFilter).mapNotNull { it.userId }.forEach { userId ->
                                                    selectedFriends.remove(userId)
                                                }
                                                groups.filter { it.feedDisplayName?.contains(nameFilter, ignoreCase = true) == true }
                                                    .mapNotNull { it.key }.forEach { conversationId ->
                                                    selectedGroups.remove(conversationId)
                                                }
                                            } else {
                                                selectedFriends.clear()
                                                selectedGroups.clear()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
                item {
                    val isEmpty = when (conversationType) {
                        ConversationType.FRIENDS_ONLY -> friends.isEmpty()
                        ConversationType.GROUPS_ONLY -> groups.isEmpty()
                        ConversationType.BOTH -> friends.isEmpty() && groups.isEmpty()
                    }
                    if (isEmpty) {
                        Text(text = when (conversationType) {
                            ConversationType.FRIENDS_ONLY -> translation["no_friends_found"]
                            ConversationType.GROUPS_ONLY -> translation["no_groups_found"]
                            ConversationType.BOTH -> translation["no_friends_or_groups_found"]
                        }, fontSize = 12.sp, fontWeight = FontWeight.Light, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                items(friends, key = { it.userId!! }) { friendInfo ->
                    var bitmojiBitmap by remember(friendInfo) { mutableStateOf(bitmojiCache[friendInfo.bitmojiAvatarId]) }

                    fun selectFriend(state: Boolean) {
                        friendInfo.userId?.let {
                            if (state) {
                                selectedFriends.add(it)
                            } else {
                                selectedFriends.remove(it)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectFriend(!selectedFriends.contains(friendInfo.userId))
                            }.pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { context.androidContext.copyToClipboard(friendInfo.mutableUsername.toString()) }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LaunchedEffect(friendInfo) {
                            withContext(Dispatchers.IO) {
                                if (bitmojiBitmap != null || friendInfo.bitmojiAvatarId == null || friendInfo.bitmojiSelfieId == null) return@withContext

                                val bitmojiUrl = BitmojiSelfie.getBitmojiSelfie(friendInfo.bitmojiSelfieId, friendInfo.bitmojiAvatarId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D) ?: return@withContext

                                runCatching {
                                    RemoteMediaResolver.downloadMedia(bitmojiUrl) { inputStream, length ->
                                        bitmojiCache[friendInfo.bitmojiAvatarId ?: return@withContext] = BitmapFactory.decodeStream(inputStream).also {
                                            bitmojiBitmap = it
                                        }
                                    }
                                }
                            }
                        }

                        Image(
                            bitmap = remember (bitmojiBitmap) { bitmojiBitmap?.asImageBitmap() ?: noBitmojiBitmap },
                            contentDescription = null,
                            modifier = Modifier.size(35.dp)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ){
                                Text(text = (friendInfo.displayName ?: friendInfo.mutableUsername).toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis, maxLines = 1, lineHeight = 10.sp)
                                Text(text = friendInfo.mutableUsername.toString(), fontSize = 10.sp, fontWeight = FontWeight.Light, overflow = TextOverflow.Ellipsis, maxLines = 1, lineHeight = 10.sp)
                            }
                            val lastMessage by rememberAsyncMutableState(defaultValue = null) {
                                getDMLastMessage(friendInfo.userId)
                            }

                            val userInfo = remember(friendInfo, lastMessage) {
                                buildString {
                                    append(translation["relationship"])
                                    append(context.translation["friendship_link_type.${FriendLinkType.fromValue(friendInfo.friendLinkType).shortName}"])
                                    friendInfo.addedTimestamp.takeIf { it > 0L }?.let {
                                        append("\nAdded ${DateFormat.getDateTimeInstance().format(Date(it))}")
                                    }
                                    friendInfo.snapScore.takeIf { it > 0 }?.let {
                                        append("\nSnap Score: $it")
                                    }
                                    friendInfo.streakLength.takeIf { it > 0 }?.let {
                                        append("\nStreaks length: $it")
                                    }
                                    lastMessage?.let {
                                        append("\nSent messages: ${it.serverMessageId}")
                                        append("\nLast message: ${DateFormat.getDateTimeInstance().format(Date(it.creationTimestamp))}")
                                    }
                                    betterLocation.locationHistory[context.database.myUserId]?.let { myLocation ->
                                        betterLocation.locationHistory[friendInfo.userId]?.let {
                                            append("\n${myLocation.distanceTo(it).let { distance ->
                                                if (distance < 1) "${(distance * 1000).toInt()} m" else "${distance.toInt()} km"
                                            } } away")
                                        }
                                    }
                                }
                            }
                            Text(text = userInfo, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis)
                        }

                        Checkbox(
                            checked = selectedFriends.contains(friendInfo.userId),
                            onCheckedChange = { selectFriend(it) }
                        )
                    }
                }
                
                // Group items
                items(groups, key = { it.key!! }) { groupInfo ->
                    fun selectGroup(state: Boolean) {
                        groupInfo.key?.let {
                            if (state) {
                                selectedGroups.add(it)
                            } else {
                                selectedGroups.remove(it)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectGroup(!selectedGroups.contains(groupInfo.key))
                            }.pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { context.androidContext.copyToClipboard(groupInfo.feedDisplayName.toString()) }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Group icon placeholder
                        Image(
                            bitmap = noBitmojiBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(35.dp)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = groupInfo.feedDisplayName?.toString() ?: translation["unknown_group"], 
                                fontSize = 16.sp, 
                                fontWeight = FontWeight.Bold, 
                                overflow = TextOverflow.Ellipsis, 
                                maxLines = 1, 
                                lineHeight = 10.sp
                            )
                            
                            val groupInfo = remember(groupInfo) {
                                buildString {
                                    append(translation["type_group_chat"])
                                    groupInfo.lastInteractionTimestamp.takeIf { it > 0L }?.let {
                                        append("\nLast interaction: ${DateFormat.getDateTimeInstance().format(Date(it))}")
                                    }
                                }
                            }
                            Text(text = groupInfo, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis)
                        }

                        Checkbox(
                            checked = selectedGroups.contains(groupInfo.key),
                            onCheckedChange = { selectGroup(it) }
                        )
                    }
                }
            }

            var showConfirmationDialog by remember { mutableStateOf(false) }
            var action by remember { mutableStateOf({}) }

            if (showConfirmationDialog) {
                ConfirmationDialog(
                    onConfirm = {
                        action()
                        action = {}
                        showConfirmationDialog = false
                    },
                    onCancel = {
                        action = {}
                        showConfirmationDialog = false
                    }
                )
            }

            val ctx = LocalContext.current

            val actions = remember(selectedFriends.size, selectedGroups.size, conversationType) {
                when (conversationType) {
                    ConversationType.FRIENDS_ONLY -> mapOf<() -> String, () -> Unit>(
                        { translation.format("clean_conversations", "count" to selectedFriends.size.toString()) } to {
                            context.feature(Messaging::class).conversationManager?.getOneOnOneConversationIds(selectedFriends.toList().also {
                                selectedFriends.clear()
                            }, onError = { error ->
                                context.shortToast(translation.format("failed_to_fetch_conversations", "error" to error))
                            }, onSuccess = { conversations ->
                                removeAction(ctx, conversations.map { it.second }.distinct(), delay = 10L to 40L) { conversationId, setDialogMessage ->
                                    cleanConversation(
                                        conversationId, setDialogMessage
                                    )
                                }.invokeOnCompletion {
                                    coroutineScope.launch { refreshList() }
                                }
                            })
                        },
                        { translation.format("remove_friends", "count" to selectedFriends.size.toString()) } to {
                            removeAction(ctx, selectedFriends.toList().also {
                                selectedFriends.clear()
                            }, delay = 500L to 1200L) { userId, _ -> removeFriend(userId) }.invokeOnCompletion {
                                coroutineScope.launch { refreshList() }
                            }
                        },
                        { translation.format("clean_conversations_and_remove_friends", "count" to selectedFriends.size.toString()) } to {
                            context.feature(Messaging::class).conversationManager?.getOneOnOneConversationIds(selectedFriends.toList().also {
                                selectedFriends.clear()
                            }, onError = { error ->
                                context.shortToast(translation.format("failed_to_fetch_conversations", "error" to error))
                            }, onSuccess = { conversations ->
                                removeAction(ctx, conversations.map { it.second }.distinct(), delay = 500L to 1200L) { conversationId, setDialogMessage ->
                                    cleanConversation(
                                        conversationId, setDialogMessage
                                    )
                                    removeFriend(conversations.firstOrNull { it.second == conversationId }?.first ?: return@removeAction)
                                }.invokeOnCompletion {
                                    coroutineScope.launch { refreshList() }
                                }
                            })
                        }
                    )
                    ConversationType.GROUPS_ONLY -> mapOf<() -> String, () -> Unit>(
                        { translation.format("clean_group_conversations", "count" to selectedGroups.size.toString()) } to {
                            removeAction(ctx, selectedGroups.toList().also {
                                selectedGroups.clear()
                            }, delay = 10L to 40L) { conversationId, setDialogMessage ->
                                cleanConversation(
                                    conversationId, setDialogMessage
                                )
                            }.invokeOnCompletion {
                                coroutineScope.launch { refreshList() }
                            }
                        },
                        { translation.format("leave_groups", "count" to selectedGroups.size.toString()) } to {
                            removeAction(ctx, selectedGroups.toList().also {
                                selectedGroups.clear()
                            }, delay = 500L to 1200L) { conversationId, _ -> leaveGroup(conversationId) }.invokeOnCompletion {
                                coroutineScope.launch { refreshList() }
                            }
                        }
                    )
                    ConversationType.BOTH -> mapOf<() -> String, () -> Unit>(
                        { translation.format("clean_all_conversations", "count" to (selectedFriends.size + selectedGroups.size).toString()) } to {
                            // Clean friend conversations
                            if (selectedFriends.isNotEmpty()) {
                                context.feature(Messaging::class).conversationManager?.getOneOnOneConversationIds(selectedFriends.toList(), onError = { error ->
                                    context.shortToast(translation.format("failed_to_fetch_friend_conversations", "error" to error))
                                }, onSuccess = { conversations ->
                                    removeAction(ctx, conversations.map { it.second }.distinct(), delay = 10L to 40L) { conversationId, setDialogMessage ->
                                        cleanConversation(
                                            conversationId, setDialogMessage
                                        )
                                    }.invokeOnCompletion {
                                        // Clean group conversations after friend conversations
                                        if (selectedGroups.isNotEmpty()) {
                                            removeAction(ctx, selectedGroups.toList(), delay = 10L to 40L) { conversationId, setDialogMessage ->
                                                cleanConversation(
                                                    conversationId, setDialogMessage
                                                )
                                            }.invokeOnCompletion {
                                                selectedFriends.clear()
                                                selectedGroups.clear()
                                                coroutineScope.launch { refreshList() }
                                            }
                                        } else {
                                            selectedFriends.clear()
                                            coroutineScope.launch { refreshList() }
                                        }
                                    }
                                })
                            } else if (selectedGroups.isNotEmpty()) {
                                // Only clean group conversations
                                removeAction(ctx, selectedGroups.toList().also {
                                    selectedGroups.clear()
                                }, delay = 10L to 40L) { conversationId, setDialogMessage ->
                                    cleanConversation(
                                        conversationId, setDialogMessage
                                    )
                                }.invokeOnCompletion {
                                    coroutineScope.launch { refreshList() }
                                }
                            }
                        },
                        { translation.format("remove_friends", "count" to selectedFriends.size.toString()) } to {
                            removeAction(ctx, selectedFriends.toList().also {
                                selectedFriends.clear()
                            }, delay = 500L to 1200L) { userId, _ -> removeFriend(userId) }.invokeOnCompletion {
                                coroutineScope.launch { refreshList() }
                            }
                        }
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                actions.forEach { (text, actionFunction) ->
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        onClick = {
                            showConfirmationDialog = true
                            action = actionFunction
                        },
                        enabled = when (conversationType) {
                            ConversationType.FRIENDS_ONLY -> selectedFriends.isNotEmpty()
                            ConversationType.GROUPS_ONLY -> selectedGroups.isNotEmpty()
                            ConversationType.BOTH -> selectedFriends.isNotEmpty() || selectedGroups.isNotEmpty()
                        }
                    ) {
                        Text(text = remember(selectedFriends.size, selectedGroups.size) { text() })
                    }
                }
            }
        }

        LaunchedEffect(sortBy, sortReverseOrder) {
            coroutineScope.launch {
                refreshList(clearSelected = false)
            }
            focusManager.clearFocus()
        }

        LaunchedEffect(filter) {
            coroutineScope.launch {
                // Add a small delay to prevent rapid UI updates that cause blinking
                if (filter == Filter.FOLLOWED) {
                    kotlinx.coroutines.delay(50)
                }
                refreshList()
            }
            focusManager.clearFocus()
        }

        LaunchedEffect(conversationType) {
            coroutineScope.launch {
                refreshList()
            }
            focusManager.clearFocus()
        }
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            createComposeAlertDialog(context.mainActivity!!) {
                BulkMessagingDialog()
            }.apply {
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }

    private fun removeFriend(userId: String) {
        context.mappings.useMapper(FriendRelationshipChangerMapper::class) {
            val friendRelationshipChangerInstance = context.feature(AddFriendSourceSpoof::class).friendRelationshipChangerInstance!!
            val runFriendDurableJobMethod = classReference.getAsClass()?.methods?.first {
                it.name == runFriendDurableJob.getAsString()
            } ?: throw Exception("Failed to find runFriendDurableJobMethod method")

            val removeFriendDurableJob = context.androidContext.classLoader.loadClass("com.snap.identity.job.snapchatter.RemoveFriendDurableJob")
                .constructors.firstOrNull {
                it.parameterTypes.size == 1
            }?.run {
                newInstance(
                    parameterTypes[0].dataBuilder {
                        set("a", userId) // userId
                        set("b", "DELETED_BY_MY_FRIENDS") // deleteSourceType
                        set("f", "")
                    }
                )
            } ?: throw Exception("Failed to create RemoveFriendDurableJob instance")

            val completable = runFriendDurableJobMethod.invoke(null,
                friendRelationshipChangerInstance,
                userId, // userId
                removeFriendDurableJob, // friend durable job
                0x5, // action type
                "DELETED_BY_MY_FRIENDS", // deleteSourceType
            )!!
            completable::class.java.methods.first {
                it.name == "subscribe" && it.parameterTypes.isEmpty()
            }.invoke(completable)
        }
    }

    private fun unfollowUser(userId: String) {
        context.mappings.useMapper(FriendRelationshipChangerMapper::class) {
            val friendRelationshipChangerInstance = context.feature(AddFriendSourceSpoof::class).friendRelationshipChangerInstance!!
            val runFriendDurableJobMethod = classReference.getAsClass()?.methods?.first {
                it.name == runFriendDurableJob.getAsString()
            } ?: throw Exception("Failed to find runFriendDurableJobMethod method")

            val unfollowFriendDurableJob = context.androidContext.classLoader.loadClass("com.snap.identity.job.snapchatter.UnfollowFriendDurableJob")
                .constructors.firstOrNull {
                it.parameterTypes.size == 1
            }?.run {
                newInstance(
                    parameterTypes[0].dataBuilder {
                        set("a", userId) // userId
                    }
                )
            } ?: throw Exception("Failed to create UnfollowFriendDurableJob instance")

            val completable = runFriendDurableJobMethod.invoke(null,
                friendRelationshipChangerInstance,
                userId, // userId
                unfollowFriendDurableJob, // friend durable job
                0x6, // action type
                null, // deleteSourceType
            )!!
            completable::class.java.methods.first {
                it.name == "subscribe" && it.parameterTypes.isEmpty()
            }.invoke(completable)
        }
    }

    private fun leaveGroup(conversationId: String) {
        context.feature(Messaging::class).conversationManager?.clearConversation(
            conversationId,
            onSuccess = {
                context.shortToast(translation["left_group_success"])
            },
            onError = { error ->
                context.shortToast(translation.format("failed_to_leave_group", "error" to error))
            }
        )
    }

    private suspend fun cleanConversation(
        conversationId: String,
        setDialogMessage: (String) -> Unit
    ) {
        val messageCount = mutableIntStateOf(0)
        MessagingTask(
            context.messagingBridge,
            conversationId,
            taskType = MessagingTaskType.DELETE,
            constraints = listOf(MessagingConstraints.MY_USER_ID(context.messagingBridge), {
                contentType != ContentType.STATUS.id
            }),
            processedMessageCount = messageCount,
            onSuccess = {
                setDialogMessage(translation.format("deleted_messages", "count" to messageCount.intValue.toString()))
            },
        ).run()
    }
}
