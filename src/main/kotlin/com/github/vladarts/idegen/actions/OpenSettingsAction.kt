package com.github.vladarts.idegen.actions

import com.github.vladarts.idegen.settings.ProjectGeneratorSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

class OpenSettingsAction : AnAction(
    "Project Generator Settings",
    "Open Project Generator configuration",
    AllIcons.General.Settings
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            ProjectGeneratorSettingsConfigurable::class.java
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
