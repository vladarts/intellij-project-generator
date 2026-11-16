package com.github.vladarts.idegen.generate

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** No IDE state is touched here; one invocation owns one repository. */
internal class RepositoryWorker(
    private val checkCanceled: () -> Unit,
    private val gitExecutable: String = "git",
    private val gitEnvironment: Map<String, String?> = emptyMap()
) {
    fun prepare(entry: ProjectEntry): Boolean {
        checkCanceled()
        val dir = File(entry.directory)
        if (dir.exists()) {
            check(dir.isDirectory && File(dir, ".git").exists()) { "Existing path is not a Git working tree" }
            val origin = git(listOf("remote", "get-url", "origin"), dir)
            check(origin == entry.cloneUrl) { "Origin does not match configuration" }
            return false
        }
        Files.createDirectories(dir.toPath().parent)
        // Clone into a uniquely owned sibling. Never delete a pre-existing user directory.
        val temporary = Files.createTempDirectory(dir.toPath().parent, ".project-generator-").toFile()
        var moved = false
        try {
            git(listOf("clone", "--", entry.cloneUrl!!, temporary.absolutePath), null)
            if (entry.fastForward) git(listOf("config", "pull.ff", "only"), temporary)
            checkCanceled()
            Files.move(temporary.toPath(), dir.toPath())
            moved = true
            return true
        } finally {
            if (!moved) deleteTemporaryDirectory(temporary.toPath())
        }
    }

    private fun deleteTemporaryDirectory(directory: Path) {
        // walkFileTree does not follow symbolic links unless FOLLOW_LINKS is explicitly enabled.
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun git(args: List<String>, directory: File?): String {
        val output = Files.createTempFile("project-generator-git-", ".log").toFile()
        val errors = Files.createTempFile("project-generator-git-", ".err").toFile()
        try {
            val process = ProcessBuilder(listOf(gitExecutable) + args)
                .directory(directory)
                .redirectError(errors)
                .redirectOutput(output)
                .apply {
                    gitEnvironment.forEach { (key, value) ->
                        if (value == null) environment().remove(key) else environment()[key] = value
                    }
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }
                .start()
            try {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    checkCanceled()
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                }
                checkCanceled()
                check(process.exitValue() == 0) {
                    val diagnostic = sanitizedDiagnostic(errors)
                    "git ${args.first()} failed (exit ${process.exitValue()})" +
                        if (diagnostic.isEmpty()) "" else ": $diagnostic"
                }
                return output.readText().trim()
            } finally {
                if (process.isAlive) {
                    try {
                        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                    } catch (_: RuntimeException) {
                        // Some sandboxed OSes disallow process enumeration. Still reap our direct child.
                    } finally {
                        process.destroyForcibly()
                        var interrupted = false
                        while (process.isAlive) {
                            try { process.waitFor() } catch (_: InterruptedException) { interrupted = true }
                        }
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                }
            }
        } finally {
            output.delete()
            errors.delete()
        }
    }

    private fun sanitizedDiagnostic(file: File): String {
        val limit = 16 * 1024
        val excerpt = file.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(limit)
            var size = 0
            while (size < limit) {
                val count = reader.read(buffer, size, limit - size)
                if (count < 0) break
                size += count
            }
            val text = String(buffer, 0, size)
            // Drop a potentially partial credential on the last line before redaction.
            if (size == limit) text.substringBeforeLast('\n', "") + "\n[truncated]" else text
        }
        val sanitized = excerpt.replace('\r', '\n')
            .filter { it >= ' ' || it == '\n' || it == '\t' }
            .replace(Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\"']+")) { match ->
                match.value.replace(Regex("(://)[^/]*@"), "$1[REDACTED]@")
                    .replace(Regex("[?#].*"), "?[REDACTED]")
            }
            .replace(Regex("(?im)\\b(?:authorization|proxy-authorization)\\s*[:=][^\\n]*"), "Authorization: [REDACTED]")
            .replace(
                Regex("(?i)\\b(?:password|passwd|token|access[_-]?token|private[_-]?token|oauth[_-]?token|client[_-]?secret)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"),
                "credential=[REDACTED]"
            ).trim()
        return if (sanitized.length > 2048) sanitized.take(2048) + " [truncated]" else sanitized
    }
}
