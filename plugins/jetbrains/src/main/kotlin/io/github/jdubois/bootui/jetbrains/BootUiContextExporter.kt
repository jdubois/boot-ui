package io.github.jdubois.bootui.jetbrains

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object BootUiContextExporter {
    const val MAX_EXPORT_CHARS = 8_000
    private const val MAX_PROFILES = 10
    private const val MAX_PANELS = 20

    fun export(snapshot: BootUiSnapshot): String {
        val overview = snapshot.overview
        val root = JsonObject().apply {
            addProperty("source", "BootUI JetBrains companion")
            addProperty("bootUiVersion", boundedSafeLabel(overview.bootUiVersion))
            addProperty("application", boundedSafeLabel(overview.applicationName))
            addProperty("framework", boundedSafeLabel(overview.frameworkName))
            addProperty("frameworkVersion", boundedSafeLabel(overview.frameworkVersion))
            addProperty("javaVersion", boundedSafeLabel(overview.javaVersion))
            addProperty("javaVendor", boundedSafeLabel(overview.javaVendor))
            addProperty("serverPort", overview.serverPort)
            addProperty("activationEnabled", overview.activationEnabled)
            addProperty("localhostOnly", overview.localhostOnly)
            add("activeProfiles", JsonArray().apply {
                overview.activeProfiles
                    .asSequence()
                    .filter(PROFILE_LABEL::matches)
                    .take(MAX_PROFILES)
                    .forEach(::add)
            })
            addProperty("platform", boundedSafeLabel(snapshot.platform))
            add("panels", JsonArray().apply {
                snapshot.panels
                    .asSequence()
                    .filter { it.available && PANEL_ID.matches(it.id) }
                    .take(MAX_PANELS)
                    .forEach { panel ->
                        add(JsonObject().apply {
                            addProperty("id", panel.id)
                            addProperty("available", panel.available)
                            addProperty("enabled", panel.enabled)
                            addProperty("readOnly", panel.readOnly)
                        })
                    }
            })
        }
        val result = GsonBuilder().disableHtmlEscaping().create().toJson(root)
        if (result.length > MAX_EXPORT_CHARS) {
            throw IllegalStateException("The overview is too large to export safely.")
        }
        return result
    }

    private fun boundedSafeLabel(value: String?): String? =
        value?.takeIf { it.length <= 80 && SAFE_LABEL.matches(it) }

    private val SAFE_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9 ._()+-]{0,79}")
    private val PROFILE_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val PANEL_ID = Regex("[a-z][a-z0-9-]{0,39}")
}
