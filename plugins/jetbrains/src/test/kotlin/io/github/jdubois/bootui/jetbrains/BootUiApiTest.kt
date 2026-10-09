package io.github.jdubois.bootui.jetbrains

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootUiApiTest {
    @Test
    fun `parses only the overview fields needed by the companion`() {
        val overview = BootUiApiParser.parseOverview(
            """
            {
              "bootUiVersion":"1.16.0",
              "applicationName":"orders",
              "frameworkName":"Spring Boot",
              "frameworkVersion":"4.0.0",
              "javaVersion":"21",
              "javaVendor":"Temurin",
              "activeProfiles":["local","dev","unsafe profile"],
              "serverPort":8081,
              "activation":{"enabled":true,"localhostOnly":true,"reason":"unavailable"},
              "unrecognized":{"value":"ignored"}
            }
            """.trimIndent(),
        )

        assertEquals("orders", overview.applicationName)
        assertEquals(listOf("local", "dev"), overview.activeProfiles)
        assertEquals(8081, overview.serverPort)
        assertTrue(overview.activationEnabled == true)
        assertFalse(overview.activeProfiles.contains("unsafe profile"))
    }

    @Test
    fun `panel parser discards unsafe ids and arbitrary properties`() {
        val (_, panels) = BootUiApiParser.parsePanels(
            """
            {
              "platform":"spring-boot",
              "panels":[
                {"id":"health","available":true,"enabled":true,"readOnly":false,"title":"safe"},
                {"id":"../unrecognized","available":true,"enabled":true,"value":"ignored"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, panels.size)
        assertEquals(PanelStatus("health", true, true, false), panels.single())
    }

    @Test
    fun `context export is bounded and excludes mounts unsafe labels and unavailable panel details`() {
        val context = BootUiContextExporter.export(
            BootUiSnapshot(
                overview = Overview(
                    bootUiVersion = "1.16.0",
                    applicationName = "orders",
                    frameworkName = "Spring Boot",
                    frameworkVersion = "4.0.0",
                    javaVersion = "21",
                    javaVendor = "Temurin",
                    activeProfiles = listOf("local", "unsafe profile"),
                    serverPort = 8081,
                    activationEnabled = true,
                    localhostOnly = true,
                ),
                platform = "spring-boot",
                panels = listOf(
                    PanelStatus("health", true, true, false),
                    PanelStatus("unavailable-panel", false, false, true),
                ),
            ),
        )

        assertTrue(context.length <= BootUiContextExporter.MAX_EXPORT_CHARS)
        assertTrue(context.contains("orders"))
        assertTrue(context.contains("\"id\":\"health\""))
        assertFalse(context.contains("unsafe"))
        assertFalse(context.contains("unavailable-panel"))
        assertFalse(context.contains("localhost:"))
    }

    @Test
    fun `invalid JSON shapes are reported without echoing response data`() {
        val error = kotlin.test.assertFailsWith<ApiResponseException> {
            BootUiApiParser.parseOverview("""{"unrecognized":"private-value"}""")
        }
        assertFalse(error.message.orEmpty().contains("private-value"))
    }
}
