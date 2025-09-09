package me.rhunk.snapenhance.common.util

import android.annotation.SuppressLint
import android.database.sqlite.SQLiteDatabase
import me.rhunk.snapenhance.common.logger.AbstractLogger

object SQLiteDatabaseHelper {
    @SuppressLint("Range")
    fun createTablesFromSchema(sqLiteDatabase: SQLiteDatabase, databaseSchema: Map<String, List<String>>) {
        databaseSchema.forEach { (tableName, columns) ->
            sqLiteDatabase.execSQL("CREATE TABLE IF NOT EXISTS $tableName (${columns.joinToString(", ")})")

            val cursor = sqLiteDatabase.rawQuery("PRAGMA table_info($tableName)", null)
            val existingColumns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                existingColumns.add(cursor.getString(cursor.getColumnIndex("name")) + " " + cursor.getString(cursor.getColumnIndex("type")))
            }
            cursor.close()

            val schemaColumns = columns.filter { !it.startsWith("PRIMARY KEY") }
            val newColumns = schemaColumns.filter { column ->
                existingColumns.none { existingColumn -> column.split(" ")[0] == existingColumn.split(" ")[0] }
            }

            if (newColumns.isEmpty()) return@forEach

            AbstractLogger.directDebug("Schema for table $tableName has changed, adding new columns: ${newColumns.joinToString(", ")}")
            newColumns.forEach {
                sqLiteDatabase.execSQL("ALTER TABLE $tableName ADD COLUMN $it")
            }
        }
    }
}