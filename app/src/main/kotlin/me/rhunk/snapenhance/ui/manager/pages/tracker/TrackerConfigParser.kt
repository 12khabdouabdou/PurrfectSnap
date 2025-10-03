package me.rhunk.snapenhance.ui.manager.pages.tracker

import me.rhunk.snapenhance.common.data.ExportedTrackerData
import me.rhunk.snapenhance.ui.manager.Routes
import org.json.JSONArray

data class ImportedFeature(
    val category: String,
    val name: String,
    val key: String,
    val value: Any,
    val indentation: Int
)

class TrackerConfigParser(private val context: Routes.Route) {
    fun parse(configJson: String): Map<String, List<ImportedFeature>> {
        val featureMap = mutableMapOf<String, MutableList<ImportedFeature>>()
        val exportedData = context.context.gson.fromJson(configJson, ExportedTrackerData::class.java)
        exportedData.rules.forEach { rule ->
            val features = mutableListOf<ImportedFeature>()
            features.add(ImportedFeature(rule.name, "Author", "author", rule.author ?: "Unknown", 0))
            features.add(ImportedFeature(rule.name, "Enabled", "enabled", rule.enabled, 0))
            rule.events?.forEach { event ->
                features.add(ImportedFeature(rule.name, context.context.translation["tracker_events.${event.eventType}"], event.eventType, event.actions.joinToString(", ") { context.context.translation["tracker_actions.${it.key}"] }, 1))
            }
            featureMap[rule.name] = features
        }
        return featureMap
    }

    fun parseValue(featureKey: String, value: Any): Any {
        return when (value) {
            is Boolean -> if (value) "Enabled" else "Disabled"
            is JSONArray -> {
                val list = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    list.add(value.get(i).toString())
                }
                list
            }
            else -> value.toString()
        }
    }
}
