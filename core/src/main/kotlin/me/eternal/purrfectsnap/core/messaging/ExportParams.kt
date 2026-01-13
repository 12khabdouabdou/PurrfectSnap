package me.eternal.purrfectsnap.core.messaging

import me.eternal.purrfectsnap.common.data.ContentType

class ExportParams(
    val exportFormat: ExportFormat = ExportFormat.HTML,
    val messageTypeFilter: List<ContentType>? = null,
    val amountOfMessages: Int? = null,
    val downloadMedias: Boolean = false,
    val colorSeedHex: String? = null,
    val colorOverrides: Map<String, String>? = null,
)
