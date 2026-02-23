package me.eternal.purrfectsnap.common.data

interface TrackerDataManager {
    fun getExportedTrackerData(): ExportedTrackerData
    fun getExportedTrackerData(ruleId: Int): ExportedTrackerData?
    fun importTrackerData(data: ExportedTrackerData)
}