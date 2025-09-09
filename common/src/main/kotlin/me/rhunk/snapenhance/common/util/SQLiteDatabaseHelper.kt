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
            val existingColumnNames = mutableListOf<String>()
            while (cursor.moveToNext()) {
                existingColumnNames.add(cursor.getString(cursor.getColumnIndex("name")))
            }
            cursor.close()

            val schemaColumns = columns.filter { !it.startsWith("PRIMARY KEY") }
            val newColumns = schemaColumns.filter {
                existingColumnNames.none { existingColumnName -> it.startsWith(existingColumnName) }
            }

            if (newColumns.isEmpty()) return@forEach

            AbstractLogger.directDebug("Schema for table $tableName has changed, dropping and recreating")
            sqLiteDatabase.execSQL("DROP TABLE $tableName")
            sqLiteDatabase.execSQL("CREATE TABLE $tableName (${columns.joinToString(", ")})")
        }
    }
}