package me.eternal.purrfectsnap.common.config.impl

import me.eternal.purrfectsnap.common.config.ConfigContainer

class StreaksReminderConfig : ConfigContainer(hasGlobalState = true) {
    val interval = integer("interval", 1)
    val remainingHours = integer("remaining_hours", 13)
    val groupNotifications = boolean("group_notifications", true)
}