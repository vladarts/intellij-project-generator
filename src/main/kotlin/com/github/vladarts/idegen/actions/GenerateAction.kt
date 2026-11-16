package com.github.vladarts.idegen.actions

import com.github.vladarts.idegen.generate.GenerateService
import com.github.vladarts.idegen.notify.ProjectGeneratorNotifier
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.util.text.StringUtil

class GenerateAction : AnAction(
    "Generate Project",
    "Clone repositories and register modules from Project Generator config",
    AllIcons.Actions.Refresh
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        if (!running.add(project)) return
        try {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project, "Project Generator", /* canBeCancelled = */ true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    try {
                        val stats = GenerateService(project).run(indicator)
                        if (stats.failures.isEmpty()) ProjectGeneratorNotifier.info(project, buildSummary(stats))
                        else ProjectGeneratorNotifier.warning(project, buildSummary(stats))
                    } catch (ex: ProcessCanceledException) {
                        throw ex
                    } catch (ex: Exception) {
                        ProjectGeneratorNotifier.error(project, StringUtil.escapeXmlEntities(ex.message ?: "Unknown error"))
                    }
                }
                override fun onFinished() { running.remove(project) }
            })
        } catch (ex: Exception) {
            running.remove(project)
            throw ex
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.project !in running
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private val running = ConcurrentHashMap.newKeySet<Project>()
    }

    private fun buildSummary(stats: GenerateService.Stats): String = buildString {
        append("Done: ${stats.cloned} cloned, ${stats.skipped} skipped, ")
        append("${stats.failures.size} failed, ")
        if (stats.failures.isNotEmpty()) {
            append("<br/>" + stats.failures.joinToString("<br/>") { StringUtil.escapeXmlEntities(it) } + "<br/>")
        }
        append("${stats.modulesAdded} modules added, ${stats.vcsRootsAdded} VCS roots added")
        if (stats.modulesRemoved.isNotEmpty()) {
            append("<br/>Removed modules: ${stats.modulesRemoved.joinToString(", ") { StringUtil.escapeXmlEntities(it) }}")
        }
        if (stats.vcsRootsRemoved.isNotEmpty()) {
            append("<br/>Removed VCS roots: ${stats.vcsRootsRemoved.joinToString(", ") { StringUtil.escapeXmlEntities(it) }}")
        }
    }
}
