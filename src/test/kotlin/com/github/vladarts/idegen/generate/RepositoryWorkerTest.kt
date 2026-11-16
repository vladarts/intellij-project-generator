package com.github.vladarts.idegen.generate

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class RepositoryWorkerTest {
    @get:Rule val gitHome = TemporaryFolder()
    private var inheritedGitEnvironment: Map<String, String> = emptyMap()

    private fun isolatedGitEnvironment(): Map<String, String?> {
        val inherited = System.getenv() + inheritedGitEnvironment
        val templates = File(gitHome.root, "templates").apply { mkdirs() }
        return inherited.keys.filter { it.startsWith("GIT_") }.associateWith { null } + mapOf(
            "HOME" to gitHome.root.path,
            "XDG_CONFIG_HOME" to gitHome.root.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_TEMPLATE_DIR" to templates.path,
            "GIT_CONFIG_COUNT" to "3",
            "GIT_CONFIG_KEY_0" to "commit.gpgSign",
            "GIT_CONFIG_VALUE_0" to "false",
            "GIT_CONFIG_KEY_1" to "tag.gpgSign",
            "GIT_CONFIG_VALUE_1" to "false",
            "GIT_CONFIG_KEY_2" to "core.hooksPath",
            "GIT_CONFIG_VALUE_2" to "/dev/null"
        )
    }

    private fun worker(checkCanceled: () -> Unit = {}, executable: String = "git") =
        RepositoryWorker(checkCanceled, executable, isolatedGitEnvironment())

    private fun entry(dir: File, url: String) = ProjectEntry(dir.name, dir.absolutePath, url, "Git", true)
    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
            .apply {
                environment().putAll(inheritedGitEnvironment)
                isolatedGitEnvironment().forEach { (key, value) ->
                    if (value == null) environment().remove(key) else environment()[key] = value
                }
            }.start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { result }
        return result
    }
    private fun inTemporaryDirectory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("idegen-test-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun cloneThenSkipWithoutContactingRemote() = inTemporaryDirectory { root ->
        val remote = File(root, "remote").apply { mkdir() }
        git(remote, "init", "--bare")
        val target = File(root, "checkout")
        val worker = worker()
        assertTrue(worker.prepare(entry(target, remote.path)))
        assertEquals("only", git(target, "config", "pull.ff"))
        remote.deleteRecursively()
        assertFalse(worker.prepare(entry(target, remote.path)))
    }

    @Test fun linkedWorktreeIsRecognized() = inTemporaryDirectory { root ->
        val main = File(root, "main").apply { mkdir() }
        git(main, "init")
        git(main, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "initial")
        git(main, "remote", "add", "origin", "https://example.com/repo.git")
        val worktree = File(root, "linked")
        git(main, "worktree", "add", "-b", "linked", worktree.path)
        assertTrue(File(worktree, ".git").isFile)
        assertFalse(worker().prepare(entry(worktree, "https://example.com/repo.git")))
    }

    @Test fun wrongOriginDoesNotTouchCheckout() = inTemporaryDirectory { root ->
        git(root, "init")
        git(root, "remote", "add", "origin", "https://example.com/actual.git")
        val sentinel = File(root, "keep").apply { writeText("user data") }
        assertThrows(IllegalStateException::class.java) {
            worker().prepare(entry(root, "https://example.com/other.git"))
        }
        assertEquals("user data", sentinel.readText())
    }

    @Test fun existingNonRepositoryIsFailure() = inTemporaryDirectory { root ->
        assertThrows(IllegalStateException::class.java) {
            worker().prepare(entry(root, "unused"))
        }
        assertTrue(root.exists())
    }

    @Test fun missingOriginIsFailure() = inTemporaryDirectory { root ->
        git(root, "init")
        assertThrows(IllegalStateException::class.java) {
            worker().prepare(entry(root, "unused"))
        }
    }

    @Test fun failedCloneRemovesOnlyOwnedTemporaryDirectory() = inTemporaryDirectory { root ->
        val target = File(root, "checkout")
        val sentinel = File(root, "keep").apply { writeText("user data") }
        assertThrows(IllegalStateException::class.java) {
            worker().prepare(entry(target, File(root, "missing").path))
        }
        assertFalse(target.exists())
        assertEquals(listOf(sentinel.name), root.listFiles()!!.map { it.name })
    }

    @Test fun cancellationStopsRunningGitAndCleansClone() = inTemporaryDirectory { root ->
        val pidFile = File(root, "pid")
        val script = File(root, "slow-git").apply {
            writeText("#!/bin/sh\necho $$ > '${pidFile.path}'\nexec sleep 30\n")
            setExecutable(true)
        }
        val target = File(root, "checkout")
        assertThrows(CancellationException::class.java) {
            worker({ if (pidFile.exists()) throw CancellationException() }, script.path)
                .prepare(entry(target, "unused"))
        }
        val pid = pidFile.readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFalse(target.exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".project-generator-") })
    }

    @Test fun canceledCloneDoesNotFollowCheckedOutSymlinks() = inTemporaryDirectory { root ->
        val outside = File(root, "outside").apply { mkdir() }
        val sentinel = File(outside, "keep").apply { writeText("user data") }
        val remote = File(root, "remote").apply { mkdir() }
        Files.createSymbolicLink(File(remote, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(remote, "dangling").toPath(), File(root, "missing").toPath())
        git(remote, "init")
        git(remote, "add", "link", "dangling")
        git(remote, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "symlinks")
        val target = File(root, "checkout")
        val checkCanceled = {
            if (root.listFiles()!!.any {
                it.name.startsWith(".project-generator-") &&
                    Files.exists(File(it, "link").toPath(), LinkOption.NOFOLLOW_LINKS)
            }) throw CancellationException()
        }
        assertThrows(CancellationException::class.java) {
            worker(checkCanceled).prepare(entry(target, remote.path))
        }
        assertEquals("user data", sentinel.readText())
        assertFalse(target.exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".project-generator-") })
    }

    @Test fun failedCloneDoesNotFollowSymlinksDuringCleanup() = inTemporaryDirectory { root ->
        val outside = File(root, "outside").apply { mkdir() }
        val sentinel = File(outside, "keep").apply { writeText("user data") }
        val script = File(root, "failing-git").apply {
            writeText("#!/bin/sh\nln -s '${outside.path}' \"\$4/link\"\nexit 1\n")
            setExecutable(true)
        }
        val target = File(root, "checkout")
        assertThrows(IllegalStateException::class.java) {
            worker(executable = script.path).prepare(entry(target, "unused"))
        }
        assertEquals("user data", sentinel.readText())
        assertFalse(target.exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".project-generator-") })
    }

    @Test fun batchRetainsBoundedDiagnosticWithoutCredentials() = inTemporaryDirectory { root ->
        val url = "https://sample-user:sample-password@example.com/repo.git?access_token=query-secret"
        val script = File(root, "diagnostic-git").apply {
            writeText("""
                #!/bin/sh
                cat >&2 <<'DIAGNOSTIC'
                fatal: Authentication failed for '$url'
                Authorization: Bearer bearer-secret
                Proxy-Authorization: Basic basic-secret
                password='password with spaces' private_token=private-secret
                ${"detail ".repeat(600)}
                ${"x".repeat(20000)}secret-at-read-boundary
                DIAGNOSTIC
                exit 128
            """.trimIndent() + "\n")
            setExecutable(true)
        }
        val results = mutableListOf<RepositoryResult>()
        prepareBatch(listOf(entry(File(root, "checkout"), url)), 1, {},
            prepare = { worker(executable = script.path).prepare(it) },
            completed = { results += it })
        val error = results.single().error!!
        assertTrue(error.contains("git clone failed (exit 128)"))
        assertTrue(error.contains("Authentication failed"))
        assertTrue(error.contains("example.com/repo.git"))
        assertTrue(error.contains("[REDACTED]"))
        assertTrue(error.contains("[truncated]"))
        assertTrue(error.length < 2200)
        for (secret in listOf("sample-user", "sample-password", "query-secret", "bearer-secret",
            "basic-secret", "password with spaces", "private-secret", "secret-at-read-boundary")) {
            assertFalse("Leaked credential: $secret", error.contains(secret))
        }
    }

    @Test fun gitProcessesIgnoreInheritedSigningAndUrlRewrites() = inTemporaryDirectory { root ->
        val marker = File(root, "signer-invoked")
        val signer = File(root, "signer").apply {
            writeText("#!/bin/sh\ntouch '${marker.path}'\nexit 1\n")
            setExecutable(true)
        }
        val remote = File(root, "remote").apply { mkdir() }
        val config = File(root, "inherited.gitconfig").apply {
            writeText("""
                [commit]
                    gpgSign = true
                [gpg]
                    program = ${signer.path}
                [url "${File(root, "missing").path}"]
                    insteadOf = ${remote.path}
            """.trimIndent())
        }
        inheritedGitEnvironment = mapOf("GIT_CONFIG_GLOBAL" to config.path)
        // Verify the external config really enables signing before exercising isolation.
        val inherited = ProcessBuilder("git", "config", "--global", "--get", "commit.gpgSign")
            .apply { environment().putAll(inheritedGitEnvironment) }.start()
        assertEquals("true", inherited.inputStream.bufferedReader().readText().trim())
        assertEquals(0, inherited.waitFor())
        git(remote, "init")
        assertEquals("false", git(remote, "config", "--get", "commit.gpgSign"))
        git(remote, "-c", "user.name=Test", "-c", "user.email=test@example.com",
            "commit", "--allow-empty", "-m", "unsigned fixture")
        val target = File(root, "checkout")
        assertTrue(worker().prepare(entry(target, remote.path)))
        assertFalse(worker().prepare(entry(target, remote.path)))
        assertEquals(remote.path, git(target, "remote", "get-url", "origin"))
        assertFalse(marker.exists())
    }

    @Test fun batchLimitsConcurrencyAndContinuesAfterFailure() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val firstWave = CountDownLatch(4)
        val results = mutableListOf<RepositoryResult>()
        val entries = (1..12).map { entry(File("/tmp/repo-$it"), "unused") }
        prepareBatch(entries, 4, {}, prepare = { item ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try {
                firstWave.countDown()
                check(firstWave.await(5, TimeUnit.SECONDS))
                if (item.name == "repo-3") error("failure")
                false
            } finally { active.decrementAndGet() }
        }, completed = { results += it })
        assertEquals(4, maximum.get())
        assertEquals(12, results.size)
        assertEquals(1, results.count { it.error != null })
        assertEquals(0, active.get())
    }

    @Test fun batchCancellationStopsWorkersAndQueue() {
        val canceled = AtomicBoolean()
        val started = AtomicInteger()
        val active = AtomicInteger()
        val entries = (1..20).map { entry(File("/tmp/repo-$it"), "unused") }
        val checkCanceled = { if (canceled.get()) throw CancellationException() }
        assertThrows(CancellationException::class.java) {
            prepareBatch(entries, 1, checkCanceled, prepare = {
                started.incrementAndGet()
                active.incrementAndGet()
                try {
                    canceled.set(true)
                    while (true) { checkCanceled() }
                    @Suppress("UNREACHABLE_CODE") false
                } finally { active.decrementAndGet() }
            }, completed = { fail("Canceled work must not produce a result") })
        }
        assertEquals(1, started.get())
        assertEquals(0, active.get())
    }
}
