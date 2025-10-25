    val instantTranslation = container("instant_translation", InstantTranslationConfig()) { requireRestart() }

    val batchFriendSelector = container("batch_friend_selector", BatchFriendSelectorConfig()) {
        addNotices(
            FeatureNotice("Enables batch sending to multiple friends."),
            FeatureNotice("Adjust limits carefully to avoid API throttling.")
        )
    }

    class BatchFriendSelectorConfig : ConfigContainer() {
        val enabled = boolean("enabled", defaultValue = false)
        val batchSize = integer("batch_size", defaultValue = 100) {
            inputCheck = { it.toIntOrNull()?.coerceIn(50, 200) != null }
        }
        val delayBetweenBatches = integer("delay_between_batches", defaultValue = 2) {
            inputCheck = { it.toIntOrNull()?.coerceIn(0, 10) != null }
        }
        val enableNotifications = boolean("enable_notifications", defaultValue = true)
        val notifyOnBatchComplete = boolean("notify_on_batch_complete", defaultValue = true)
        val notifyOnError = boolean("notify_on_error", defaultValue = true)
        val autoCleanupDays = integer("auto_cle_
