package io.github.jdubois.bootui.jetbrains

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

internal data class Overview(
    val bootUiVersion: String?,
    val applicationName: String?,
    val frameworkName: String?,
    val frameworkVersion: String?,
    val javaVersion: String?,
    val javaVendor: String?,
    val activeProfiles: List<String>,
    val serverPort: Int?,
    val activationEnabled: Boolean?,
    val localhostOnly: Boolean?,
)

internal data class PanelStatus(
    val id: String,
    val available: Boolean,
    val enabled: Boolean,
    val readOnly: Boolean,
)

internal data class BootUiSnapshot(
    val overview: Overview,
    val platform: String?,
    val panels: List<PanelStatus>,
)

internal object BootUiApiParser {
    private const val MAX_PROFILES = 10
    private const val MAX_PANELS = 40

    fun parseOverview(json: String): Overview {
        val root = parseObject(json)
        val activation = root.get("activation")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw ApiResponseException("BootUI overview is missing activation status.")
        val applicationName = safeText(root, "applicationName")
            ?: throw ApiResponseException("BootUI overview is missing application information.")
        val bootUiVersion = safeText(root, "bootUiVersion")
            ?: throw ApiResponseException("BootUI overview is missing version information.")
        val frameworkName = safeText(root, "frameworkName")
            ?: throw ApiResponseException("BootUI overview is missing framework information.")
        return Overview(
            bootUiVersion = bootUiVersion,
            applicationName = applicationName,
            frameworkName = frameworkName,
            frameworkVersion = safeText(root, "frameworkVersion"),
            javaVersion = safeText(root, "javaVersion"),
            javaVendor = safeText(root, "javaVendor"),
            activeProfiles = safeStrings(root.get("activeProfiles"), MAX_PROFILES),
            serverPort = safeInt(root, "serverPort"),
            activationEnabled = activation.get("enabled")?.asBooleanOrNull(),
            localhostOnly = activation.get("localhostOnly")?.asBooleanOrNull(),
        )
    }

    fun parsePanels(json: String): Pair<String?, List<PanelStatus>> {
        val root = parseObject(json)
        val platform = safeText(root, "platform")
        val panels = root.get("panels")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.take(MAX_PANELS)
            ?.mapNotNull { element ->
                val panel = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val id = panel.get("id")?.asSafePanelId() ?: return@mapNotNull null
                PanelStatus(
                    id = id,
                    available = panel.get("available")?.asBooleanOrNull() ?: false,
                    enabled = panel.get("enabled")?.asBooleanOrNull() ?: false,
                    readOnly = panel.get("readOnly")?.asBooleanOrNull() ?: false,
                )
            }
            .orEmpty()
        return platform to panels
    }

    private fun parseObject(json: String): JsonObject = try {
        JsonParser.parseString(json)
            .takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: throw ApiResponseException("BootUI returned an unexpected JSON shape.")
    } catch (exception: JsonParseException) {
        throw ApiResponseException("BootUI returned invalid JSON.")
    }

    private fun safeText(root: JsonObject, field: String): String? =
        root.get(field)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString
            ?.takeIf { it.length <= 160 && it.none(Char::isISOControl) }

    private fun safeInt(root: JsonObject, field: String): Int? =
        root.get(field)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
            ?.takeIf { it.isNumber }
            ?.let { primitive ->
                try {
                    primitive.asBigDecimal.intValueExact()
                } catch (_: NumberFormatException) {
                    null
                } catch (_: ArithmeticException) {
                    null
                }
            }
            ?.takeIf { it in 1..65535 }

    private fun safeStrings(element: JsonElement?, limit: Int): List<String> =
        element?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.take(limit)
            ?.mapNotNull { item ->
                item.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
                    ?.takeIf { it.isString }
                    ?.asString
                    ?.takeIf { it.length <= 80 && SAFE_LABEL.matches(it) }
            }
            .orEmpty()

    private fun JsonElement.asSafePanelId(): String? =
        takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString
            ?.takeIf { PANEL_ID.matches(it) }

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
            ?.takeIf { it.isBoolean }
            ?.asBoolean

    private val SAFE_LABEL = Regex("[A-Za-z0-9._-]{1,80}")
    private val PANEL_ID = Regex("[a-z][a-z0-9-]{0,39}")
}

internal class ApiResponseException(message: String) : IllegalArgumentException(message)
