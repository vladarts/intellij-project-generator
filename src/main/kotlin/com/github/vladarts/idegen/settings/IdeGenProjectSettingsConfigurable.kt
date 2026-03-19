package com.github.vladarts.idegen.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton

class ProjectGeneratorSettingsConfigurable(private val project: Project) : BoundConfigurable("Project Generator") {

    private val settings = ProjectGeneratorSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        group("Configuration") {
            row("Config file:") {
                val pathField = textFieldWithBrowseButton(
                    browseDialogTitle = "Select Project Generator Config File",
                    project = project,
                    fileChooserDescriptor = FileChooserDescriptorFactory
                        .createSingleFileDescriptor("yaml")
                        .withTitle("Select Project Generator Config File")
                )
                    .bindText(settings::configFilePath)
                    .align(AlignX.FILL)
                    .comment("Path to the Project Generator YAML configuration file (e.g. config.yaml)")

                val openButton = JButton(AllIcons.Actions.EditSource).apply {
                    toolTipText = "Open in Editor"
                    isBorderPainted = false
                    isContentAreaFilled = false
                    addActionListener {
                        val path = pathField.component.text.trim().expandHome()
                        if (path.isEmpty()) return@addActionListener
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return@addActionListener
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                }

                // keep button enabled state in sync with the field
                pathField.component.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
                    private fun sync() { openButton.isEnabled = pathField.component.text.trim().isNotEmpty() }
                })
                openButton.isEnabled = settings.configFilePath.trim().isNotEmpty()

                cell(openButton)
            }
            row("VCS sources root:") {
                textFieldWithBrowseButton(
                    browseDialogTitle = "Select VCS Sources Root",
                    project = project,
                    fileChooserDescriptor = FileChooserDescriptorFactory
                        .createSingleFolderDescriptor()
                        .withTitle("Select VCS Sources Root")
                )
                    .bindText(settings::vcsSourcesRoot)
                    .align(AlignX.FILL)
                    .comment("Root directory for cloned repositories. Defaults to ~/dev")
            }
        }
    }
}
