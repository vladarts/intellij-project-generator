package com.github.vladarts.idegen.actions

import com.github.vladarts.idegen.generate.GenerateService
import com.github.vladarts.idegen.notify.ProjectGeneratorNotifier
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

class GenerateAction : AnAction(
    "Generate Project",
    "Clone repositories and register modules from Project Generator config",
    AllIcons.Actions.Refresh
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Project Generator", /* canBeCancelled = */ true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                try {
                    val stats = GenerateService(project).run(indicator)
                    ProjectGeneratorNotifier.info(project, buildSummary(stats))
                } catch (ex: Exception) {
                    ProjectGeneratorNotifier.error(project, ex.message ?: "Unknown error")
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun buildSummary(stats: GenerateService.Stats): String = buildString {
        append("Done: ${stats.cloned} cloned, ${stats.skipped} skipped, ")
        append("${stats.modulesAdded} modules added, ${stats.vcsRootsAdded} VCS roots added")
        if (stats.modulesRemoved.isNotEmpty()) {
            append("<br/>Removed modules: ${stats.modulesRemoved.joinToString(", ")}")
        }
        if (stats.vcsRootsRemoved.isNotEmpty()) {
            append("<br/>Removed VCS roots: ${stats.vcsRootsRemoved.joinToString(", ")}")
        }
    }
}
