package com.github.vladarts.idegen.config

data class ProjectGeneratorConfig(
    val git: List<GitEntry> = emptyList(),
    val raw: List<DirectoryEntry> = emptyList(),      // YAML key "raw" or "directory"
    val gitlab: List<GitLabEntry> = emptyList()
)

data class GitEntry(
    val url: String,
    val fastForward: Boolean = false,
    val remotes: Map<String, String> = emptyMap()
)

data class DirectoryEntry(
    val path: String
)

data class GitLabEntry(
    val url: String,
    val token: String = "",
    val tokenEnvVar: String = "",
    val tokenCommand: String = "",
    val tokenType: String = "private",
    val includeArchived: Boolean = false,
    val httpsUrl: Boolean = false,
    val fastForward: Boolean = false,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList()
)
