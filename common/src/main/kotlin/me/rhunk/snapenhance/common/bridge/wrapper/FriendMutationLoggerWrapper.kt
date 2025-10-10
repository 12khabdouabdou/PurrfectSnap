package me.rhunk.snapenhance.common.bridge.wrapper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.bridge.logger.FriendMutationLoggerInterface
import me.rhunk.snapenhance.common.bridge.InternalFileHandleType
import me.rhunk.snapenhance.common.util.SQLiteDatabaseHelper
import java.io.File

data class FriendMutationLog(
    val id: Int,
    val timestamp: Long,
    val eventType: String,
    val friendName: String,
    val details: String
)

class FriendMutationLoggerWrapper(
    val databaseFile: File
) : FriendMutationLoggerInterface.Stub() {
    constructor(context: Context) : this(
        File(
            context.getDatabasePath(
                InternalFileHandleType.FRIEND_MUTATION_LOGGER.fileName
            ).absolutePath
        )
    )

    private var _database: SQLiteDatabase? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coroutineScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    private val database
        get() = synchronized(this) {
            _database?.takeIf { it.isOpen } ?: run {
                _database?.close()
                val openedDatabase = SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
                )
                SQLiteDatabaseHelper.createTablesFromSchema(
                    openedDatabase, mapOf(
                        "friend_mutations" to listOf(
                            "id INTEGER PRIMARY KEY AUTOINCREMENT",
                            "timestamp BIGINT",
                            "event_type TEXT",
                            "friend_name TEXT",
                            "details TEXT"
                        )
                    )
                )
                _database = openedDatabase
                openedDatabase
            }
        }

    override fun logFriendMutation(eventType: String, friendName: String, details: String) {
        coroutineScope.launch {
            database.insert("friend_mutations", null, ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("event_type", eventType)
                put("friend_name", friendName)
                put("details", details)
            })
        }
    }

    fun getLogs(
        lastItemTimestamp: Long?,
        limit: Int,
        reverseOrder: Boolean = true,
        filter: String
    ): List<FriendMutationLog> {
        val whereClauses = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (filter.isNotBlank()) {
            whereClauses.add("(friend_name LIKE ? OR event_type LIKE ? OR details LIKE ?)")
            selectionArgs.add("%$filter%")
            selectionArgs.add("%$filter%")
            selectionArgs.add("%$filter%")
        }

        whereClauses.add("timestamp ${if (reverseOrder) "<" else ">"} ?")
        selectionArgs.add((lastItemTimestamp ?: if (reverseOrder) Long.MAX_VALUE else 0).toString())

        val whereStatement = if (whereClauses.isNotEmpty()) "WHERE ${whereClauses.joinToString(" AND ")}" else ""

        return database.rawQuery("SELECT id, timestamp, event_type, friend_name, details FROM friend_mutations " +
                "$whereStatement " +
                "ORDER BY timestamp ${if (reverseOrder) "DESC" else "ASC"} " +
                "LIMIT $limit", selectionArgs.toTypedArray()).use {
            val logs = mutableListOf<FriendMutationLog>()
            while (it.moveToNext()) {
                val log = FriendMutationLog(
                    id = it.getInt(0),
                    timestamp = it.getLong(1),
                    eventType = it.getString(2),
                    friendName = it.getString(3),
                    details = it.getString(4)
                )
                logs.add(log)
            }
            logs
        }
    }

    fun getAllLogs(): List<FriendMutationLog> {
        return database.rawQuery("SELECT id, timestamp, event_type, friend_name, details FROM friend_mutations ORDER BY timestamp DESC", null).use {
            val logs = mutableListOf<FriendMutationLog>()
            while (it.moveToNext()) {
                val log = FriendMutationLog(
                    id = it.getInt(0),
                    timestamp = it.getLong(1),
                    eventType = it.getString(2),
                    friendName = it.getString(3),
                    details = it.getString(4)
                )
                logs.add(log)
            }
            logs
        }
    }

    fun purgeAll() {
        coroutineScope.launch {
            database.execSQL("DELETE FROM friend_mutations")
        }
    }
}