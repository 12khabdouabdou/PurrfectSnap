package me.rhunk.snapenhance.common.data

data class ExportedTrackerData(
    val type: ExportType,
    val rules: List<TrackerRule>
)