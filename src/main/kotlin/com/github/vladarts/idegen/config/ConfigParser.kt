package com.github.vladarts.idegen.config

import org.yaml.snakeyaml.Yaml
import java.io.Reader

object ConfigParser {

    fun parse(reader: Reader): ProjectGeneratorConfig {
        val loaded = Yaml().load<Any?>(reader)
        val data = if (loaded == null) emptyMap() else stringMap(loaded, "Config")
        val sections = setOf("git", "raw", "directory", "gitlab")
        require(data.keys.all { it in sections }) { "Unknown config sections: ${data.keys - sections}" }

        val git = parseList(data, "git") { m ->
            GitEntry(
                url = m.requiredStr("url"),
                fastForward = m.bool("fast_forward", "fastForward") ?: false,
                remotes = if (m.containsKey("remotes")) {
                    stringMap(m["remotes"], "remotes").mapValues { (key, value) ->
                        require(value is String) { "remotes.$key must be a string" }
                        value
                    }
                } else emptyMap()
            )
        }

        // Support both "raw" (documented) and "directory" (Go JSON tag)
        val raw = parseList(data, "raw") { m ->
            DirectoryEntry(path = m.requiredStr("path"))
        } + parseList(data, "directory") { m ->
            DirectoryEntry(path = m.requiredStr("path"))
        }

        val gitlab = parseList(data, "gitlab") { m ->
            GitLabEntry(
                url = m.requiredStr("url"),
                token = m.str("token"),
                tokenEnvVar = m.str("token_env_var", "tokenEnvVar"),
                tokenCommand = m.str("token_command", "tokenCommand"),
                tokenType = m.str("token_type", "tokenType", default = "private"),
                includeArchived = m.bool("include_archived", "includeArchived") ?: false,
                httpsUrl = m.bool("https_url", "httpsUrl") ?: false,
                fastForward = m.bool("fast_forward", "fastForward") ?: false,
                include = m.strList("include"),
                exclude = m.strList("exclude")
            )
        }

        return ProjectGeneratorConfig(git = git, raw = raw, gitlab = gitlab)
    }

    private fun stringMap(value: Any?, context: String): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) {
            "$context must be a mapping with string keys"
        }
        return value.entries.associate { (key, item) -> key as String to item }
    }

    private fun <T> parseList(
        data: Map<String, Any?>,
        key: String,
        mapper: (Map<String, Any?>) -> T
    ): List<T> {
        if (!data.containsKey(key)) return emptyList()
        val items = data[key]
        require(items is List<*>) { "$key must be a list" }
        return items.mapIndexed { index, item -> mapper(stringMap(item, "$key[$index]")) }
    }

    private fun Map<String, Any?>.str(vararg keys: String, default: String = ""): String {
        val key = keys.firstOrNull { containsKey(it) } ?: return default
        val value = this[key]
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun Map<String, Any?>.requiredStr(key: String): String = str(key).also {
        require(it.isNotBlank()) { "$key must be a non-blank string" }
    }

    private fun Map<String, Any?>.bool(vararg keys: String): Boolean? {
        val key = keys.firstOrNull { containsKey(it) } ?: return null
        val value = this[key]
        require(value is Boolean) { "$key must be a boolean" }
        return value
    }

    private fun Map<String, Any?>.strList(key: String): List<String> {
        if (!containsKey(key)) return emptyList()
        val value = this[key]
        require(value is List<*> && value.all { it is String }) { "$key must be a list of strings" }
        return value.map { it as String }
    }
}
