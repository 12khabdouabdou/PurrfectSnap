package me.eternal.purrfectsnap.common.data

data class ExportedTrackerData(
    val type: ExportType,
    val rules: List<TrackerRule>
)