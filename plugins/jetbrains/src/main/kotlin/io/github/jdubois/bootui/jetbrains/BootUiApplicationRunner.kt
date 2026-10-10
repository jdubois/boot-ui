package io.github.jdubois.bootui.jetbrains

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project

internal object BootUiApplicationRunner {
    fun runSelected(project: Project): String {
        val settings = RunManager.getInstance(project).selectedConfiguration
            ?: return "Select an application run configuration in IntelliJ's Run toolbar, then click Run application."
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        if (ProgramRunner.getRunner(executor.id, settings.configuration) == null) {
            return "The selected configuration cannot be run. Choose an application configuration in IntelliJ's Run toolbar."
        }
        ProgramRunnerUtil.executeConfiguration(settings, executor)
        return "Run requested in IntelliJ. Wait for application startup, then click Connect. The URL is not changed automatically."
    }
}
