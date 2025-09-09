package me.rhunk.snapenhance.ui.manager.pages.tracker

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType as AnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.menuAnchor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.data.TrackerEventType
import me.rhunk.snapenhance.common.data.TrackerRuleAction
import me.rhunk.snapenhance.common.data.TrackerRuleActionParams
import me.rhunk.snapenhance.common.data.TrackerRuleEvent
import me.rhunk.snapenhance.common.data.TrackerScopeType
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableStateList
import me.rhunk.snapenhance.storage.addOrUpdateTrackerRuleEvent
import me.rhunk.snapenhance.storage.deleteTrackerRule
import me.rhunk.snapenhance.storage.deleteTrackerRuleEvent
import me.rhunk.snapenhance.storage.getRuleTrackerScopes
import me.rhunk.snapenhance.storage.getTrackerEvents
import me.rhunk.snapenhance.storage.getTrackerRule
import me.rhunk.snapenhance.storage.getTrackerRuleByName
import me.rhunk.snapenhance.storage.newTrackerRule
import me.rhunk.snapenhance.storage.setRuleTrackerAuthor
import me.rhunk.snapenhance.storage.setRuleTrackerScopes
import me.rhunk.snapenhance.storage.setTrackerRuleName
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.manager.pages.social.AddFriendDialog

class EditRule : Routes.Route() {

    @Composable
    fun ActionCheckbox(
        text: String,
        checked: MutableState<Boolean>,
        onChanged: (Boolean) -> Unit = {}
    ) {
        Row(
            modifier = Modifier.clickable {
                checked.value = !checked.value
                onChanged(checked.value)
            },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(
                modifier = Modifier.size(30.dp),
                checked = checked.value,
                onCheckedChange = {
                    checked.value = it
                    onChanged(it)
                }
            )
            Text(text, fontSize = 12.sp)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun AddEventDialog(
        onDismissRequest: () -> Unit,
        onEventAdd: (TrackerRuleEvent) -> Unit,
        currentEventType: MutableState<String>,
        addEventActions: MutableState<Set<TrackerRuleAction>>,
        addEventActionParams: TrackerRuleActionParams
    ) {
        val expanded = remember { mutableStateOf(false) }
        Dialog(onDismissRequest = onDismissRequest) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Add Event") },
                        navigationIcon = {
                            IconButton(onClick = onDismissRequest) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Close")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    onEventAdd(
                                        TrackerRuleEvent(
                                            id = -1,
                                            enabled = true,
                                            eventType = currentEventType.value,
                                            params = addEventActionParams.copy(),
                                            actions = addEventActions.value.toList()
                                        )
                                    )
                                }
                            ) {
                                Text("Add")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Type", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        ExposedDropdownMenuBox(
                            expanded = expanded.value,
                            onExpandedChange = { expanded.value = !expanded.value }
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .menuAnchor(AnchorType.PrimaryNotEditable)
                                    .widthIn(min = 160.dp),
                                value = context.translation["tracker_events.${currentEventType.value}"],
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                label = { Text("Event type") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded.value)
                                }
                            )
                            ExposedDropdownMenu(
                                expanded = expanded.value,
                                onDismissRequest = { expanded.value = false }
                            ) {
                                TrackerEventType.entries.forEach { eventType ->
                                    DropdownMenuItem(
                                        onClick = {
                                            currentEventType.value = eventType.key
                                            expanded.value = false
                                        },
                                        text = {
                                            Text(context.translation["tracker_events.${eventType.key}"])
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                    )
                                }
                            }
                        }
                    }
                    Text("Triggers", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TrackerRuleAction.entries.forEach { action ->
                            ActionCheckbox(
                                context.translation["tracker_actions.${action.key}"],
                                checked = remember { mutableStateOf(addEventActions.value.contains(action)) }
                            ) {
                                if (it) {
                                    addEventActions.value += action
                                } else {
                                    addEventActions.value -= action
                                }
                            }
                        }
                    }
                    Text("Conditions", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    ConditionCheckboxes(addEventActionParams)
                }
            }
        }
    }

    @Composable
    fun ConditionCheckboxes(
        params: TrackerRuleActionParams
    ) {
        ActionCheckbox(
            text = "Only when I'm inside conversation",
            checked = remember { mutableStateOf(params.onlyInsideConversation) },
            onChanged = { params.onlyInsideConversation = it }
        )
        ActionCheckbox(
            text = "Only when I'm outside conversation",
            checked = remember { mutableStateOf(params.onlyOutsideConversation) },
            onChanged = { params.onlyOutsideConversation = it }
        )
        ActionCheckbox(
            text = "Only when Snapchat is active",
            checked = remember { mutableStateOf(params.onlyWhenAppActive) },
            onChanged = { params.onlyWhenAppActive = it }
        )
        ActionCheckbox(
            text = "Only when Snapchat is inactive",
            checked = remember { mutableStateOf(params.onlyWhenAppInactive) },
            onChanged = { params.onlyWhenAppInactive = it }
        )
        ActionCheckbox(
            text = "No notification when Snapchat is active",
            checked = remember { mutableStateOf(params.noPushNotificationWhenAppActive) },
            onChanged = { params.noPushNotificationWhenAppActive = it }
        )
    }

    override val title: @Composable () -> Unit = { Text("Edit Rule") }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = { navBackStackEntry ->
        val currentRuleId = navBackStackEntry.arguments?.getString("rule_id")?.toIntOrNull()
        val currentEventType = remember { mutableStateOf(TrackerEventType.CONVERSATION_ENTER.key) }
        val addEventActions = remember { mutableStateOf(emptySet<TrackerRuleAction>()) }
        val addEventActionParams = remember { TrackerRuleActionParams() }

        val events = rememberAsyncMutableStateList(defaultValue = emptyList()) {
            currentRuleId?.let { ruleId ->
                context.database.getTrackerEvents(ruleId)
            } ?: emptyList()
        }

        var currentScopeType by remember { mutableStateOf(TrackerScopeType.BLACKLIST) }
        val scopes = rememberAsyncMutableStateList(defaultValue = emptyList()) {
            currentRuleId?.let { ruleId ->
                context.database.getRuleTrackerScopes(ruleId).also {
                    currentScopeType = if (it.isEmpty()) {
                        TrackerScopeType.WHITELIST
                    } else {
                        it.values.first()
                    }
                }.map { it.key }
            } ?: emptyList()
        }

        val ruleName = rememberAsyncMutableState(defaultValue = "", keys = arrayOf(currentRuleId)) {
            currentRuleId?.let { ruleId ->
                context.database.getTrackerRule(ruleId)?.name ?: "Custom Rule"
            } ?: "Custom Rule"
        }
        val authorName = rememberAsyncMutableState(defaultValue = "", keys = arrayOf(currentRuleId)) {
            currentRuleId?.let { ruleId ->
                context.database.getTrackerRule(ruleId)?.author ?: ""
            } ?: ""
        }

        var deleteConfirmation by remember { mutableStateOf(false) }
        var showDuplicateNameDialog by remember { mutableStateOf(false) }

        if (showDuplicateNameDialog) {
            AlertDialog(
                onDismissRequest = { showDuplicateNameDialog = false },
                title = { Text("Duplicate Rule Name") },
                text = { Text("A rule with this name already exists. Please choose a different name.") },
                confirmButton = {
                    Button(onClick = { showDuplicateNameDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }

        if (deleteConfirmation) {
            AlertDialog(
                onDismissRequest = { deleteConfirmation = false },
                title = { Text("Delete Rule") },
                text = { Text("Are you sure you want to delete this rule?") },
                confirmButton = {
                    Button(
                        onClick = {
                            if (currentRuleId != null) {
                                context.database.deleteTrackerRule(currentRuleId)
                            }
                            routes.navController.popBackStack()
                        }
                    ) { Text("Delete") }
                },
                dismissButton = {
                    Button(onClick = { deleteConfirmation = false }) { Text("Cancel") }
                }
            )
        }

        val coroutineScope = rememberCoroutineScope()
        val pagerState = rememberPagerState { 3 }
        val tabs = listOf("General", "Scope", "Events")

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (currentRuleId == null) "New Rule" else "Edit Rule") },
                    navigationIcon = {
                        IconButton(onClick = { routes.navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (currentRuleId == null && context.database.getTrackerRuleByName(ruleName.value.trim()) != null) {
                                showDuplicateNameDialog = true
                                return@IconButton
                            }
                            val ruleId = currentRuleId ?: context.database.newTrackerRule()
                            events.forEach { event ->
                                context.database.addOrUpdateTrackerRuleEvent(
                                    event.id.takeIf { it > -1 },
                                    ruleId,
                                    event.eventType,
                                    event.params,
                                    event.actions
                                )
                            }
                            context.database.setTrackerRuleName(ruleId, ruleName.value.trim())
                            context.database.setTrackerRuleAuthor(ruleId, authorName.value.trim())
                            context.database.setRuleTrackerScopes(ruleId, currentScopeType, scopes)
                            routes.navController.popBackStack()
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                        if (currentRuleId != null) {
                            IconButton(onClick = { deleteConfirmation = true }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(title) }
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                item {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            OutlinedTextField(
                                                value = ruleName.value,
                                                onValueChange = { ruleName.value = it },
                                                label = { Text("Rule Name") },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = authorName.value,
                                                onValueChange = { authorName.value = it },
                                                label = { Text("Author Name") },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                item {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "Scope",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(16.dp)
                                            )

                                            var addFriendDialog by remember { mutableStateOf(null as AddFriendDialog?) }

                                            val friendDialogActions = remember {
                                                AddFriendDialog.Actions(
                                                    onFriendState = { friend, state ->
                                                        if (state) scopes.add(friend.userId) else scopes.remove(friend.userId)
                                                    },
                                                    onGroupState = { group, state ->
                                                        if (state) scopes.add(group.conversationId) else scopes.remove(group.conversationId)
                                                    },
                                                    getFriendState = { friend -> friend.userId in scopes },
                                                    getGroupState = { group -> group.conversationId in scopes }
                                                )
                                            }

                                            val scopeOptions = listOf("All", "Whitelist", "Blacklist")
                                            val selectedScopeIndex = when {
                                                scopes.isEmpty() -> 0
                                                currentScopeType == TrackerScopeType.WHITELIST -> 1
                                                else -> 2
                                            }

                                            SingleChoiceSegmentedButtonRow(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp)
                                            ) {
                                                scopeOptions.forEachIndexed { index, label ->
                                                    SegmentedButton(
                                                        shape = SegmentedButtonDefaults.itemShape(
                                                            index = index,
                                                            count = scopeOptions.size
                                                        ),
                                                        onClick = {
                                                            when (index) {
                                                                0 -> scopes.clear()
                                                                1 -> {
                                                                    currentScopeType = TrackerScopeType.WHITELIST
                                                                    if (scopes.isEmpty()) {
                                                                        addFriendDialog = AddFriendDialog(
                                                                            context,
                                                                            friendDialogActions,
                                                                            pinnedIds = scopes
                                                                        )
                                                                    }
                                                                }
                                                                2 -> {
                                                                    currentScopeType = TrackerScopeType.BLACKLIST
                                                                    if (scopes.isEmpty()) {
                                                                        addFriendDialog = AddFriendDialog(
                                                                            context,
                                                                            friendDialogActions,
                                                                            pinnedIds = scopes
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        selected = index == selectedScopeIndex
                                                    ) { Text(label) }
                                                }
                                            }

                                            if (scopes.isNotEmpty()) {
                                                Button(
                                                    onClick = {
                                                        addFriendDialog = AddFriendDialog(
                                                            context,
                                                            friendDialogActions,
                                                            pinnedIds = scopes
                                                        )
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp)
                                                ) { Text("Select Friends/Groups (${scopes.size})") }
                                            }

                                            addFriendDialog?.Content { addFriendDialog = null }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                item {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column {
                                            var addEventDialog by remember { mutableStateOf(false) }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "Events",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(16.dp)
                                                )
                                                IconButton(
                                                    onClick = { addEventDialog = true },
                                                    modifier = Modifier.padding(8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Add,
                                                        contentDescription = "Add Event",
                                                        modifier = Modifier.size(32.dp)
                                                    )
                                                }
                                            }

                                            if (addEventDialog) {
                                                AddEventDialog(
                                                    onDismissRequest = { addEventDialog = false },
                                                    onEventAdd = { event ->
                                                        events.add(0, event)
                                                        addEventDialog = false
                                                    },
                                                    currentEventType = currentEventType,
                                                    addEventActions = addEventActions,
                                                    addEventActionParams = addEventActionParams
                                                )
                                            }
                                        }
                                    }
                                }

                                item {
                                    if (events.isEmpty()) {
                                        Text(
                                            "No events",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Light,
                                            modifier = Modifier
                                                .padding(10.dp)
                                                .fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                items(events, key = { it.id }) { event ->
                                    var expanded by remember { mutableStateOf(false) }
                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateContentSize()
                                            .padding(4.dp),
                                        onClick = { expanded = !expanded }
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    modifier = Modifier.weight(1f, fill = false),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Column {
                                                        Text(
                                                            context.translation["tracker_events.${event.eventType}"],
                                                            lineHeight = 20.sp,
                                                            fontSize = 18.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = event.actions.joinToString(", ") {
                                                                context.translation["tracker_actions.${it.key}"]
                                                            },
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Light,
                                                            overflow = TextOverflow.Ellipsis,
                                                            maxLines = 1,
                                                            lineHeight = 14.sp
                                                        )
                                                    }
                                                }
                                                OutlinedIconButton(
                                                    onClick = {
                                                        if (event.id > -1) {
                                                            context.database.deleteTrackerRuleEvent(event.id)
                                                        }
                                                        events.remove(event)
                                                    }
                                                ) {
                                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                                                }
                                            }
                                            if (expanded) {
                                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                                    ConditionCheckboxes(event.params)
                                                }
                                            }
                                        }
                                    }
                                }

                                item { Spacer(modifier = Modifier.height(140.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
