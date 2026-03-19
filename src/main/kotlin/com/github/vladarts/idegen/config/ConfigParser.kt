package com.github.vladarts.idegen.config

import org.yaml.snakeyaml.Yaml
import java.io.Reader

object ConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parse(reader: Reader): ProjectGeneratorConfig {
        val data: Map<String, Any> = Yaml().load(reader) ?: emptyMap<String, Any>()

        val git = parseList(data, "git") { m ->
            GitEntry(
                url = m.str("url"),
                fastForward = m.bool("fast_forward") ?: m.bool("fastForward") ?: false,
                remotes = (m["remotes"] as? Map<String, String>) ?: emptyMap()
            )
        }

        // Support both "raw" (documented) and "directory" (Go JSON tag)
        val raw = parseList(data, "raw") { m ->
            DirectoryEntry(path = m.str("path"))
        } + parseList(data, "directory") { m ->
            DirectoryEntry(path = m.str("path"))
        }

        val gitlab = parseList(data, "gitlab") { m ->
            GitLabEntry(
                url = m.str("url"),
                token = m.str("token", ""),
                tokenEnvVar = m.str("token_env_var", "") ?: m.str("tokenEnvVar", "") ?: "",
                tokenCommand = m.str("token_command", "") ?: m.str("tokenCommand", "") ?: "",
                tokenType = m.str("token_type", "private") ?: m.str("tokenType", "private") ?: "private",
                includeArchived = m.bool("include_archived") ?: m.bool("includeArchived") ?: false,
                httpsUrl = m.bool("https_url") ?: m.bool("httpsUrl") ?: false,
                fastForward = m.bool("fast_forward") ?: m.bool("fastForward") ?: false,
                include = m.strList("include"),
                exclude = m.strList("exclude")
            )
        }

        return ProjectGeneratorConfig(git = git, raw = raw, gitlab = gitlab)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> parseList(
        data: Map<String, Any>,
        key: String,
        mapper: (Map<String, Any>) -> T
    ): List<T> =
        (data[key] as? List<Map<String, Any>>)?.map(mapper) ?: emptyList()

    private fun Map<String, Any>.str(key: String, default: String? = null): String =
        (this[key] as? String) ?: default ?: ""

    private fun Map<String, Any>.bool(key: String): Boolean? =
        this[key] as? Boolean

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.strList(key: String): List<String> =
        (this[key] as? List<String>) ?: emptyList()
}
