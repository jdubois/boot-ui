package io.github.jdubois.bootui.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class BootUiPlatformServiceTest : BasePlatformTestCase() {
    fun testRunApplicationExplainsMissingConfiguration() {
        assertEquals(
            "Select an application run configuration in IntelliJ's Run toolbar, then click Run application.",
            BootUiApplicationRunner.runSelected(project),
        )
    }

    fun testProjectServiceUsesInjectedProjectScopeAndLocalSettings() {
        val service = project.getService(BootUiProjectService::class.java)

        assertEquals("http://localhost:8080", service.settings().applicationUrl)
        assertEquals("/bootui/api", service.settings().apiPath)
        assertEquals("/bootui", service.settings().uiPath)
    }
}
