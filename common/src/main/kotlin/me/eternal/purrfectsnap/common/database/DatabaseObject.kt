package me.eternal.purrfectsnap.common.database

import android.database.Cursor

interface DatabaseObject {
    fun write(cursor: Cursor)
}
