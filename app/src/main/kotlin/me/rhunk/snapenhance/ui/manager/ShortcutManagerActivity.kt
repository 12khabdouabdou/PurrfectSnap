package me.rhunk.snapenhance.ui.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import me.rhunk.snapenhance.data.Friend
import me.rhunk.snapenhance.data.ShortcutDatabase

class ShortcutManagerActivity : ComponentActivity() {
    
    private lateinit var database: ShortcutDatabase
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        database = ShortcutDatabase(this)
        
        setContent {
            MaterialTheme {
                ShortcutManagerScreen(
                    database = database,
                    friendsProvider = {
                        // TODO: Get friends from Snapchat
                        // For now, return empty list
                        getFriendsFromSnapchat()
                    },
                    onBack = { finish() },
                    onSendShortcut = { shortcut ->
                        // TODO: Trigger send
                        // Send broadcast or use shared preferences
                    }
                )
            }
        }
    }
    
    private fun getFriendsFromSnapchat(): List<Friend> {
        // TODO: Query Snapchat's friend list
        // This needs to hook into Snapchat's contacts
        return emptyList()
    }
}
