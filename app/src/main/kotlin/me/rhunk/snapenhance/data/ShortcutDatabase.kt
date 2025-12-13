package me.rhunk.snapenhance.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable

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
            val shortcutId = db.insert(TABLE_SHORTCUTS, null, ContentValues().apply {
                put("name", shortcut.name)
                put("description", shortcut.description)
                put("created_at", shortcut.createdAt)
                put("use_count", 0)
            })
            
            if (shortcutId == -1L) return -1
            
            shortcut.recipients.forEachIndexed { index, recipient ->
                db.insert(TABLE_RECIPIENTS, null, ContentValues().apply {
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
                shortcuts.add(Shortcut(
                    id = id,
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    description = it.getString(it.getColumnIndexOrThrow("description")) ?: "",
                    recipients = getRecipientsForShortcut(id),
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
            null, null,
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
        writableDatabase.delete(TABLE_SHORTCUTS, "id = ?", arrayOf(id.toString()))
    }
    
    fun updateUsage(shortcutId: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_SHORTCUTS SET last_used = ?, use_count = use_count + 1 WHERE id = ?",
            arrayOf(System.currentTimeMillis(), shortcutId)
        )
    }
}
