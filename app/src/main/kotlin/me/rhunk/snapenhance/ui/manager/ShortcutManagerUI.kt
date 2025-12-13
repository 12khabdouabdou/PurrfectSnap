package me.rhunk.snapenhance.ui.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

// ========================================
// DATA MODELS
// ========================================

@Serializable
data class Recipient(
    val userId: String,
    val name: String,
    val username: String = ""
)

@Serializable
data class Shortcut(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val recipients: List<Recipient>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long? = null,
    val useCount: Int = 0
)

data class Friend(
    val userId: String,
    val displayName: String,
    val username: String = "",
    val isSelected: Boolean = false
)

// ========================================
// DATABASE
// ========================================

class ShortcutDatabase(context: Context) : SQLiteOpenHelper(
    context,
    "snapenhance_shortcuts.db",
    null,
    1
) {
    companion object {
        private const val TABLE_SHORTCUTS = "shortcuts"
        private const val TABLE_RECIPIENTS = "recipients"
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SHORTCUTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                description TEXT,
                created_at INTEGER NOT NULL,
                last_used INTEGER,
                use_count INTEGER DEFAULT 0
            )
        """)
        
        db.execSQL("""
            CREATE TABLE $TABLE_RECIPIENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shortcut_id INTEGER NOT NULL,
                user_id TEXT NOT NULL,
                name TEXT NOT NULL,
                username TEXT,
                order_index INTEGER,
                FOREIGN KEY(shortcut_id) REFERENCES $TABLE_SHORTCUTS(id) ON DELETE CASCADE
            )
        """)
        
        db.execSQL("CREATE INDEX idx_recipients_shortcut ON $TABLE_RECIPIENTS(shortcut_id)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RECIPIENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SHORTCUTS")
        onCreate(db)
    }
    
    fun createShortcut(shortcut: Shortcut): Long {
        val db = writableDatabase
        db.beginTransaction()
        
        try {
            val shortcutId = db.insert(TABLE_SHORTCUTS, null, android.content.ContentValues().apply {
                put("name", shortcut.name)
                put("description", shortcut.description)
                put("created_at", shortcut.createdAt)
                put("use_count", 0)
            })
            
            if (shortcutId == -1L) return -1
            
            shortcut.recipients.forEachIndexed { index, recipient ->
                db.insert(TABLE_RECIPIENTS, null, android.content.ContentValues().apply {
                    put("shortcut_id", shortcutId)
                    put("user_id", recipient.userId)
                    put("name", recipient.name)
                    put("username", recipient.username)
                    put("order_index", index)
                })
            }
            
            db.setTransactionSuccessful()
            return shortcutId
        } finally {
            db.endTransaction()
        }
    }
    
    fun getAllShortcuts(): List<Shortcut> {
        val shortcuts = mutableListOf<Shortcut>()
        val db = readableDatabase
        
        val cursor = db.query(TABLE_SHORTCUTS, null, null, null, null, null, "name ASC")
        
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow("id"))
                val recipients = getRecipientsForShortcut(id)
                
                shortcuts.add(Shortcut(
                    id = id,
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    description = it.getString(it.getColumnIndexOrThrow("description")) ?: "",
                    recipients = recipients,
                    createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                    lastUsed = if (it.isNull(it.getColumnIndexOrThrow("last_used"))) null 
                               else it.getLong(it.getColumnIndexOrThrow("last_used")),
                    useCount = it.getInt(it.getColumnIndexOrThrow("use_count"))
                ))
            }
        }
        
        return shortcuts
    }
    
    private fun getRecipientsForShortcut(shortcutId: Long): List<Recipient> {
        val recipients = mutableListOf<Recipient>()
        val db = readableDatabase
        
        val cursor = db.query(
            TABLE_RECIPIENTS,
            null,
            "shortcut_id = ?",
            arrayOf(shortcutId.toString()),
            null,
            null,
            "order_index ASC"
        )
        
        cursor.use {
            while (it.moveToNext()) {
                recipients.add(Recipient(
                    userId = it.getString(it.getColumnIndexOrThrow("user_id")),
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    username = it.getString(it.getColumnIndexOrThrow("username")) ?: ""
                ))
            }
        }
        
        return recipients
    }
    
    fun deleteShortcut(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_SHORTCUTS, "id = ?", arrayOf(id.toString()))
    }
    
    fun updateUsage(shortcutId: Long) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE $TABLE_SHORTCUTS SET last_used = ?, use_count = use_count + 1 WHERE id = ?",
            arrayOf(System.currentTimeMillis(), shortcutId)
        )
    }
}

// ========================================
// MAIN SCREEN
// ========================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShortcutManagerScreen(
    database: ShortcutDatabase,
    friendsProvider: () -> List<Friend>, // Provide list of all Snapchat friends
    onBack: () -> Unit,
    onSendShortcut: (Shortcut) -> Unit
) {
    var shortcuts by remember { mutableStateOf(database.getAllShortcuts()) }
    var selectedShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<Shortcut?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredShortcuts = remember(shortcuts, searchQuery) {
        if (searchQuery.isEmpty()) {
            shortcuts
        } else {
            shortcuts.filter { 
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Send To Shortcuts",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "Create")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New Shortcut") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search shortcuts...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = true
            )
            
            // Statistics
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        value = shortcuts.size.toString(),
                        label = "Shortcuts",
                        icon = Icons.Default.Star
                    )
                    
                    Divider(
                        modifier = Modifier
                            .height(50.dp)
                            .width(1.dp)
                    )
                    
                    StatItem(
                        value = shortcuts.sumOf { it.recipients.size }.toString(),
                        label = "Recipients",
                        icon = Icons.Default.Person
                    )
                    
                    Divider(
                        modifier = Modifier
                            .height(50.dp)
                            .width(1.dp)
                    )
                    
                    StatItem(
                        value = shortcuts.sumOf { it.useCount }.toString(),
                        label = "Sent",
                        icon = Icons.Default.Send
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Shortcuts list
            if (filteredShortcuts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = if (searchQuery.isEmpty()) "No shortcuts yet\nCreate your first one!" else "No shortcuts found",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = filteredShortcuts,
                        key = { it.id }
                    ) { shortcut ->
                        ShortcutCard(
                            shortcut = shortcut,
                            onClick = { selectedShortcut = shortcut },
                            onSend = { onSendShortcut(shortcut) },
                            onDelete = { showDeleteDialog = shortcut },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                }
            }
        }
    }
    
    // Dialogs
    if (showCreateDialog) {
        CreateShortcutDialog(
            database = database,
            friendsProvider = friendsProvider,
            onDismiss = { showCreateDialog = false },
            onCreated = {
                shortcuts = database.getAllShortcuts()
                showCreateDialog = false
            }
        )
    }
    
    showDeleteDialog?.let { shortcut ->
        DeleteConfirmationDialog(
            shortcut = shortcut,
            onConfirm = {
                database.deleteShortcut(shortcut.id)
                shortcuts = database.getAllShortcuts()
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
    
    selectedShortcut?.let { shortcut ->
        ShortcutDetailsSheet(
            shortcut = shortcut,
            onDismiss = { selectedShortcut = null },
            onSend = { 
                onSendShortcut(shortcut)
                selectedShortcut = null
            }
        )
    }
}

// ========================================
// COMPONENTS
// ========================================

@Composable
fun StatItem(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutCard(
    shortcut: Shortcut,
    onClick: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Star,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortcut.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                if (shortcut.description.isNotEmpty()) {
                    Text(
                        text = shortcut.description,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(text = "${shortcut.recipients.size} friends", icon = Icons.Default.Person)
                    if (shortcut.useCount > 0) {
                        Chip(text = "Used ${shortcut.useCount}x", icon = Icons.Default.Send)
                    }
                }
            }
            
            // Actions
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onSend) {
                    Icon(Icons.Default.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun Chip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp))
            Text(text, fontSize = 12.sp)
        }
    }
}

// ========================================
// CREATE SHORTCUT DIALOG
// ========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateShortcutDialog(
    database: ShortcutDatabase,
    friendsProvider: () -> List<Friend>,
    onDismiss: () -> Unit,
    onCreated: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedFriends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var showFriendPicker by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Text("Create Shortcut")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shortcut Name") },
                    placeholder = { Text("Best Friends") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Star, null) }
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    placeholder = { Text("My close friends") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                    maxLines = 2
                )
                
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recipients (${selectedFriends.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        FilledTonalButton(
                            onClick = { showFriendPicker = true }
                        ) {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Select Friends")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (selectedFriends.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No friends selected",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                selectedFriends.take(3).forEach { friend ->
                                    Text(
                                        "• ${friend.displayName}",
                                        fontSize = 14.sp
                                    )
                                }
                                if (selectedFriends.size > 3) {
                                    Text(
                                        "• and ${selectedFriends.size - 3} more...",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
                
                errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        name.isEmpty() -> errorMessage = "Please enter a name"
                        selectedFriends.isEmpty() -> errorMessage = "Please select friends"
                        else -> {
                            val shortcut = Shortcut(
                                name = name,
                                description = description,
                                recipients = selectedFriends.map { 
                                    Recipient(it.userId, it.displayName, it.username) 
                                }
                            )
                            
                            val id = database.createShortcut(shortcut)
                            
                            if (id > 0) {
                                onCreated()
                            } else {
                                errorMessage = "Shortcut name already exists"
                            }
                        }
                    }
                },
                enabled = name.isNotEmpty() && selectedFriends.isNotEmpty()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
    
    if (showFriendPicker) {
        FriendPickerDialog(
            friends = friendsProvider(),
            preSelectedFriends = selectedFriends,
            onDismiss = { showFriendPicker = false },
            onConfirm = { selected ->
                selectedFriends = selected
                showFriendPicker = false
            }
        )
    }
}

// ========================================
// FRIEND PICKER DIALOG WITH SELECT ALL
// ========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendPickerDialog(
    friends: List<Friend>,
    preSelectedFriends: List<Friend>,
    onDismiss: () -> Unit,
    onConfirm: (List<Friend>) -> Unit
) {
    var selectedFriends by remember { 
        mutableStateOf(preSelectedFriends.map { it.userId }.toSet()) 
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectAll by remember { mutableStateOf(false) }
    
    val filteredFriends = remember(friends, searchQuery) {
        if (searchQuery.isEmpty()) {
            friends
        } else {
            friends.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.username.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    // Update selectAll state based on selection
    LaunchedEffect(selectedFriends, filteredFriends) {
        selectAll = filteredFriends.isNotEmpty() && 
                    filteredFriends.all { it.userId in selectedFriends }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f),
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Person, null)
                    Text("Select Friends")
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${selectedFriends.size} selected",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search friends...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    },
                    singleLine = true
                )
                
                // Select All / Deselect All
                Card(
                    onClick = {
                        if (selectAll) {
                            // Deselect all filtered friends
                            selectedFriends = selectedFriends - filteredFriends.map { it.userId }.toSet()
                        } else {
                            // Select all filtered friends
                            selectedFriends = selectedFriends + filteredFriends.map { it.userId }.toSet()
                        }
                        selectAll = !selectAll
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (selectAll) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    if (selectAll) "Deselect All" else "Select All",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${filteredFriends.size} friends",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            Icons.Default.Star,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Divider()
                
                // Friends list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredFriends) { friend ->
                        FriendItem(
                            friend = friend,
                            isSelected = friend.userId in selectedFriends,
                            onToggle = {
                                selectedFriends = if (friend.userId in selectedFriends) {
                                    selectedFriends - friend.userId
                                } else {
                                    selectedFriends + friend.userId
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = friends.filter { it.userId in selectedFriends }
                    onConfirm(selected)
                },
                enabled = selectedFriends.isNotEmpty()
            ) {
                Text("Confirm (${selectedFriends.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendItem(
    friend: Friend,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        onClick = onToggle,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
            
            Icon(
                Icons.Default.AccountCircle,
                null,
                modifier = Modifier.size(40.dp),
                tint = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    friend.displayName,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                if (friend.username.isNotEmpty()) {
                    Text(
                        "@${friend.username}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ========================================
// SHORTCUT DETAILS SHEET
// ========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutDetailsSheet(
    shortcut: Shortcut,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Star,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(shortcut.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (shortcut.description.isNotEmpty()) {
                        Text(
                            shortcut.description,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            item {
                Button(
                    onClick = onSend,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send to ${shortcut.recipients.size} Friends")
                }
            }
            
            item {
                Text(
                    "Recipients (${shortcut.recipients.size})",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            items(shortcut.recipients) { recipient ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            null,
                            modifier = Modifier.size(40.dp)
                        )
                        Column {
                            Text(recipient.name, fontWeight = FontWeight.Medium)
                            if (recipient.username.isNotEmpty()) {
                                Text(
                                    "@${recipient.username}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================
// DELETE DIALOG
// ========================================

@Composable
fun DeleteConfirmationDialog(
    shortcut: Shortcut,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
        },
        title = { Text("Delete Shortcut?") },
        text = {
            Text("Are you sure you want to delete \"${shortcut.name}\"? This cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
