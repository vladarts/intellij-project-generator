package com.github.vladarts.idegen.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
    name = "ProjectGeneratorSettings",
    storages = [Storage("projectGeneratorSettings.xml")]
)
@Service(Service.Level.PROJECT)
class ProjectGeneratorSettings : PersistentStateComponent<ProjectGeneratorSettings.State> {

    data class State(
        var configFilePath: String = "",
        var vcsSourcesRoot: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var configFilePath: String
        get() = myState.configFilePath
        set(value) { myState.configFilePath = value }

    var vcsSourcesRoot: String
        get() = myState.vcsSourcesRoot
        set(value) { myState.vcsSourcesRoot = value }

    /** Resolved sources root: setting if set, otherwise ~/dev */
    fun resolvedVcsSourcesRoot(): String {
        val raw = vcsSourcesRoot.trim()
        if (raw.isNotEmpty()) return raw.expandHome()
        return System.getProperty("user.home") + "/dev"
    }

    companion object {
        fun getInstance(project: Project): ProjectGeneratorSettings = project.service()
    }
}

internal fun String.expandHome(): String =
    if (startsWith("~/")) System.getProperty("user.home") + substring(1) else this
