package me.rhunk.snapenhance.common.data

interface TrackerDataManager {
    fun getExportedTrackerData(): ExportedTrackerData
    fun importTrackerData(data: ExportedTrackerData)
}
