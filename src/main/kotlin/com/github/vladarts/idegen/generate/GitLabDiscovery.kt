package com.github.vladarts.idegen.generate

import com.github.vladarts.idegen.config.GitLabEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val LOG = logger<GitLabDiscovery>()

private const val TOKEN_PREVIEW_BYTES = 4
private fun String.preview() = if (length <= TOKEN_PREVIEW_BYTES) "***" else "${take(TOKEN_PREVIEW_BYTES)}***"

object GitLabDiscovery {

    private val gson = Gson()
    private val client = HttpClient.newHttpClient()

    fun discover(entry: GitLabEntry): List<ProjectEntry> {
        val token = resolveToken(entry)
        val baseUrl = entry.url.trimEnd('/')
        LOG.info("Fetching projects from $baseUrl (tokenType=${entry.tokenType})")
        val projects = fetchAllProjects(baseUrl, token, entry.tokenType)
        LOG.info("Fetched ${projects.size} projects from $baseUrl before filtering")

        val result = projects
            .filter { p -> entry.includeArchived || !p.archived }
            .filter { p -> matchesFilter(p.pathWithNamespace, entry.include, entry.exclude) }
            .map { p ->
                val url = if (entry.httpsUrl) p.httpUrlToRepo else p.sshUrlToRepo
                val parsed = GitUrlParser.parse(url)
                ProjectEntry(
                    name = parsed.pathSegments.joinToString("."),
                    directory = "",   // resolved later with vcsRoot
                    cloneUrl = url,
                    vcsType = "Git",
                    fastForward = entry.fastForward
                )
            }

        LOG.info("${result.size} projects remaining after filtering (include=${entry.include}, exclude=${entry.exclude})")
        return result
    }

    private fun resolveToken(entry: GitLabEntry): String = when {
        entry.tokenEnvVar.isNotBlank() -> {
            LOG.info("Obtaining GitLab token from env var '${entry.tokenEnvVar}'")
            System.getenv(entry.tokenEnvVar) ?: ""
        }
        entry.tokenCommand.isNotBlank() -> {
            LOG.info("Obtaining GitLab token via command: ${entry.tokenCommand}")
            runCommand(entry.tokenCommand).also {
                LOG.info("Token command completed successfully, token starts with: ${it.preview()}")
            }
        }
        entry.token.isNotBlank() -> {
            LOG.info("Using literal GitLab token from config, token starts with: ${entry.token.preview()}")
            entry.token
        }
        else -> {
            LOG.warn("No GitLab token source configured (token, tokenEnvVar, or tokenCommand)")
            ""
        }
    }

    private fun runCommand(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        check(exit == 0) { "token_command failed (exit $exit): $output" }
        return output
    }

    private fun matchesFilter(path: String, include: List<String>, exclude: List<String>): Boolean {
        if (exclude.any { Regex(it).containsMatchIn(path) }) return false
        if (include.isNotEmpty() && include.none { Regex(it).containsMatchIn(path) }) return false
        return true
    }

    private fun fetchAllProjects(baseUrl: String, token: String, tokenType: String): List<GitLabProject> {
        val all = mutableListOf<GitLabProject>()
        var page = 1

        while (true) {
            val url = "$baseUrl/api/v4/projects?membership=true&per_page=100&page=$page"
            LOG.debug("Fetching page $page: $url")
            val (headerName, headerValue) = authHeader(tokenType, token)
            val request = HttpRequest.newBuilder(URI(url))
                .header(headerName, headerValue)
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                throw RuntimeException("GitLab API error ${response.statusCode()}: ${response.body()}")
            }

            val type = object : TypeToken<List<GitLabProject>>() {}.type
            val pageProjects: List<GitLabProject> = gson.fromJson(response.body(), type)
            LOG.debug("Page $page returned ${pageProjects.size} projects")
            all.addAll(pageProjects)

            val nextPage = response.headers().firstValue("x-next-page").orElse("")
            if (nextPage.isBlank()) break
            page = nextPage.toInt()
        }

        return all
    }

    private fun authHeader(tokenType: String, token: String): Pair<String, String> =
        when (tokenType.lowercase()) {
            "job"   -> "JOB-TOKEN" to token
            "oauth" -> "Authorization" to "Bearer $token"
            else    -> "PRIVATE-TOKEN" to token
        }

    private data class GitLabProject(
        val id: Int = 0,
        val archived: Boolean = false,
        val path_with_namespace: String = "",
        val ssh_url_to_repo: String = "",
        val http_url_to_repo: String = ""
    ) {
        val pathWithNamespace get() = path_with_namespace
        val sshUrlToRepo get() = ssh_url_to_repo
        val httpUrlToRepo get() = http_url_to_repo
    }
}
