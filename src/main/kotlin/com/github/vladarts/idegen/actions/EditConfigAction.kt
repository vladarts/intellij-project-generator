package com.github.vladarts.idegen.actions

import com.github.vladarts.idegen.notify.ProjectGeneratorNotifier
import com.github.vladarts.idegen.settings.ProjectGeneratorSettings
import com.github.vladarts.idegen.settings.expandHome
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem

class EditConfigAction : AnAction(
    "Edit Config",
    "Open Project Generator config file in editor",
    AllIcons.Actions.EditSource
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = ProjectGeneratorSettings.getInstance(project)
        val path = settings.configFilePath.trim().expandHome()

        if (path.isEmpty()) {
            ProjectGeneratorNotifier.warning(project, "Config file path is not configured. Go to Settings → Tools → Project Generator.")
            return
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: run {
            ProjectGeneratorNotifier.warning(project, "Config file not found: $path")
            return
        }

        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run { e.presentation.isEnabled = false; return }
        e.presentation.isEnabled = ProjectGeneratorSettings.getInstance(project).configFilePath.trim().isNotEmpty()
    }
}
