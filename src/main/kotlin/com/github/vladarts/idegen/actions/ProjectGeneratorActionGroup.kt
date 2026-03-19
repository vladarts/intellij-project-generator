package com.github.vladarts.idegen.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup

class ProjectGeneratorActionGroup : DefaultActionGroup() {
    init {
        templatePresentation.text = "Project Generator"
        templatePresentation.icon = AllIcons.Vcs.Fetch
        isPopup = true
    }
}
