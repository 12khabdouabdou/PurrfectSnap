package me.rhunk.snapenhance.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ShortcutDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "shortcuts.db"
        private const val DATABASE_VERSION = 1
        
        private const val TABLE_SHORTCUTS = "shortcuts"
        private const val COLUMN_ID = "id"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_RECIPIENTS = "recipients"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_LAST_USED = "last_used"
        private const val COLUMN_USAGE_COUNT = "usage_count"
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SHORTCUTS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_RECIPIENTS TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_LAST_USED INTEGER,
                $COLUMN_USAGE_COUNT INTEGER DEFAULT 0
            )
        """)
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SHORTCUTS")
        onCreate(db)
    }
    
    fun createShortcut(name: String, recipients: List<Recipient>): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_RECIPIENTS, json.encodeToString(recipients))
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
            put(COLUMN_USAGE_COUNT, 0)
        }
        return db.insert(TABLE_SHORTCUTS, null, values)
    }
    
    fun getAllShortcuts(): List<Shortcut> {
        val shortcuts = mutableListOf<Shortcut>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SHORTCUTS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_LAST_USED DESC, $COLUMN_USAGE_COUNT DESC"
        )
        
        cursor.use {
            while (it.moveToNext()) {
                shortcuts.add(
                    Shortcut(
                        id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                        name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                        recipients = json.decodeFromString(it.getString(it.getColumnIndexOrThrow(COLUMN_RECIPIENTS))),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                        lastUsed = it.getLong(it.getColumnIndexOrThrow(COLUMN_LAST_USED)).takeIf { l -> l > 0 },
                        usageCount = it.getInt(it.getColumnIndexOrThrow(COLUMN_USAGE_COUNT))
                    )
                )
            }
        }
        
        return shortcuts
    }
    
    fun updateShortcut(id: Long, name: String, recipients: List<Recipient>) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_RECIPIENTS, json.encodeToString(recipients))
        }
        db.update(TABLE_SHORTCUTS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }
    
    fun deleteShortcut(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_SHORTCUTS, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }
    
    fun updateUsage(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_LAST_USED, System.currentTimeMillis())
        }
        db.update(TABLE_SHORTCUTS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
        
        db.execSQL("UPDATE $TABLE_SHORTCUTS SET $COLUMN_USAGE_COUNT = $COLUMN_USAGE_COUNT + 1 WHERE $COLUMN_ID = ?", arrayOf(id))
    }
}
