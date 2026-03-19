package com.github.vladarts.idegen.generate

import java.net.URI

object GitUrlParser {

    data class ParsedUrl(
        val hostname: String,
        val pathSegments: List<String>   // ["org", "repo"] — no .git, no leading slash
    )

    fun parse(url: String): ParsedUrl = when {
        url.startsWith("git@") -> parseScp(url)
        url.startsWith("https://") || url.startsWith("http://") -> parseHttp(url)
        else -> throw IllegalArgumentException("Unsupported git URL format: $url")
    }

    /** git@github.com:org/sub/repo.git */
    private fun parseScp(url: String): ParsedUrl {
        val withoutScheme = url.removePrefix("git@")
        val colon = withoutScheme.indexOf(':')
        check(colon > 0) { "Invalid SCP-style URL: $url" }
        val hostname = withoutScheme.substring(0, colon)
        val path = withoutScheme.substring(colon + 1).removeSuffix(".git")
        return ParsedUrl(hostname, path.split("/").filter { it.isNotEmpty() })
    }

    /** https://github.com/org/sub/repo.git */
    private fun parseHttp(url: String): ParsedUrl {
        val uri = URI(url)
        val path = uri.path.removeSuffix(".git")
        return ParsedUrl(uri.host, path.split("/").filter { it.isNotEmpty() })
    }
}
