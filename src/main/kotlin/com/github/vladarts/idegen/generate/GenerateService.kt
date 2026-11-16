package com.github.vladarts.idegen.generate

import com.github.vladarts.idegen.config.ConfigParser
import com.github.vladarts.idegen.config.ProjectGeneratorConfig
import com.github.vladarts.idegen.settings.ProjectGeneratorSettings
import com.github.vladarts.idegen.settings.expandHome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.io.FileReader
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

private val LOG = logger<GenerateService>()

class GenerateService(private val project: Project) {

    private val settings = ProjectGeneratorSettings.getInstance(project)

    data class Stats(
        var cloned: Int = 0,
        var skipped: Int = 0,
        val failures: MutableList<String> = mutableListOf(),
        var modulesAdded: Int = 0,
        var vcsRootsAdded: Int = 0,
        val modulesRemoved: MutableList<String> = mutableListOf(),
        val vcsRootsRemoved: MutableList<String> = mutableListOf()
    )

    private lateinit var stats: Stats

    fun run(indicator: ProgressIndicator): Stats {
        val configPath = settings.configFilePath.trim()
        check(configPath.isNotEmpty()) {
            "Config file path is not configured. Go to Settings → Tools → Project Generator."
        }

        LOG.info("Starting generation, config: $configPath, vcsRoot: ${settings.resolvedVcsSourcesRoot()}")

        indicator.text = "Loading config…"
        indicator.fraction = 0.0
        val config = FileReader(configPath.expandHome()).use { ConfigParser.parse(it) }
        LOG.info("Config loaded: ${config.git.size} git, ${config.raw.size} raw, ${config.gitlab.size} gitlab entries")

        indicator.text = "Resolving entries…"
        val entries = resolveEntries(config, indicator)
        LOG.info("Resolved ${entries.size} project entries total")

        stats = Stats()

        // Deduplicate before scheduling: a directory must have exactly one owner.
        val unique = entries.groupBy { File(it.directory).canonicalPath }.map { (path, group) ->
            check(group.map { it.cloneUrl }.distinct().size == 1) { "Conflicting entries for $path" }
            group.first().copy(directory = path, fastForward = group.any { it.fastForward })
        }
        val ready = prepareRepositories(unique, indicator)
        indicator.checkCanceled()

        // --- Register modules ---
        indicator.text = "Registering modules…"
        indicator.fraction = 0.75
        stats.modulesAdded = registerModules(ready, unique, indicator)

        // --- Register VCS roots ---
        indicator.text = "Registering VCS roots…"
        indicator.fraction = 0.95
        stats.vcsRootsAdded = registerVcsRoots(ready, unique)

        indicator.text = "Done"
        indicator.fraction = 1.0
        LOG.info("Generation complete: $stats")
        return stats
    }

    // -------------------------------------------------------------------------
    // Entry resolution
    // -------------------------------------------------------------------------

    private fun resolveEntries(config: ProjectGeneratorConfig, indicator: ProgressIndicator): List<ProjectEntry> {
        val vcsRoot = settings.resolvedVcsSourcesRoot()
        val entries = mutableListOf<ProjectEntry>()

        for (git in config.git) {
            val parsed = GitUrlParser.parse(git.url)
            val dir = (listOf(vcsRoot, parsed.hostname) + parsed.pathSegments).joinToString(File.separator)
            val entry = ProjectEntry(
                name = parsed.pathSegments.joinToString("."),
                directory = dir,
                cloneUrl = git.url,
                vcsType = "Git",
                fastForward = git.fastForward
            )
            LOG.debug("Resolved git entry: ${entry.name} -> ${entry.directory}")
            entries += entry
        }

        for (raw in config.raw) {
            val dir = raw.path.expandHome()
            LOG.debug("Resolved raw entry: ${File(dir).name} -> $dir")
            entries += ProjectEntry(name = File(dir).name, directory = dir, cloneUrl = null, vcsType = null)
        }

        for (glEntry in config.gitlab) {
            indicator.checkCanceled()
            indicator.text2 = "GitLab: ${glEntry.url}"
            LOG.info("Discovering GitLab projects from ${glEntry.url}")
            val discovered = GitLabDiscovery.discover(glEntry)
            LOG.info("Discovered ${discovered.size} projects from ${glEntry.url}")
            for (d in discovered) {
                val parsed = GitUrlParser.parse(d.cloneUrl!!)
                val dir = (listOf(vcsRoot, parsed.hostname) + parsed.pathSegments).joinToString(File.separator)
                LOG.debug("Resolved gitlab entry: ${d.name} -> $dir")
                entries += d.copy(directory = dir)
            }
        }
        indicator.text2 = ""

        return entries
    }

    private fun prepareRepositories(entries: List<ProjectEntry>, indicator: ProgressIndicator): List<ProjectEntry> {
        val ready = entries.filter { it.cloneUrl == null && File(it.directory).isDirectory }.toMutableList()
        entries.filter { it.cloneUrl == null && !File(it.directory).isDirectory }.forEach {
            stats.failures += "${it.name}: local directory does not exist"
        }
        val targets = entries.filter { it.cloneUrl != null }
        if (targets.isEmpty()) return ready
        val checkCanceled = {
            if (indicator.isCanceled || project.isDisposed || Thread.currentThread().isInterrupted) {
                throw CancellationException("Generation canceled")
            }
        }
        var completed = 0
        try {
            prepareBatch(targets, settings.repositoryParallelism, checkCanceled,
                prepare = { RepositoryWorker(checkCanceled).prepare(it) }) { result ->
                completed++
                if (result.error != null) {
                    stats.failures += "${result.entry.name}: ${result.error}"
                } else {
                    ready += result.entry
                    if (result.cloned) stats.cloned++ else stats.skipped++
                }
                indicator.text = "Preparing repositories… [$completed / ${targets.size}]"
                indicator.text2 = result.entry.name
                indicator.fraction = 0.1 + 0.6 * completed / targets.size
            }
        } catch (e: CancellationException) {
            throw ProcessCanceledException(e)
        }
        indicator.text2 = ""
        return ready.sortedBy { it.directory }
    }

    // -------------------------------------------------------------------------
    // Module registration — returns number of newly added modules and fills removed list
    // -------------------------------------------------------------------------

    private fun registerModules(entries: List<ProjectEntry>, desired: List<ProjectEntry>, indicator: ProgressIndicator): Int {
        val basePath = project.basePath ?: run {
            LOG.warn("Project basePath is null, skipping module registration")
            return 0
        }
        val imlDir = File(basePath, ".idea/iml").also { it.mkdirs() }
        val entryDirs = desired.map { it.directory }.toSet()

        val alreadyRegistered = ReadAction.compute<Set<String>, RuntimeException> {
            if (project.isDisposed) throw ProcessCanceledException()
            ModuleManager.getInstance(project).modules.flatMap { module ->
                com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots.map { File(it.path).canonicalPath }
            }.toSet()
        }
        // Existing modules need no VFS refresh. Refresh only new roots, outside the write action.
        val roots = entries.filter { it.directory !in alreadyRegistered }.mapNotNull { entry ->
            indicator.checkCanceled()
            val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(entry.directory)
            if (root == null || !root.isDirectory) {
                stats.failures += "${entry.name}: directory unavailable in VFS"
                null
            } else entry to root
        }
        var added = 0
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) throw ProcessCanceledException()
            WriteAction.run<Exception> {
                val manager = ModuleManager.getInstance(project)
                val toRemove = manager.modules.filter { module ->
                    val contentRoots = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots
                    contentRoots.isNotEmpty() && contentRoots.none { File(it.path).canonicalPath in entryDirs }
                }
                for (module in toRemove) {
                    stats.modulesRemoved += module.name
                    manager.disposeModule(module)
                }
                val existing = manager.modules.flatMap { module ->
                    com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots.map { File(it.path).canonicalPath }
                }.toMutableSet()
                val names = manager.modules.map { it.name }.toMutableSet()
                for ((entry, root) in roots) {
                    if (entry.directory in existing) continue
                    var name = entry.name
                    var suffix = 2
                    while (name in names) name = "${entry.name}.${suffix++}"
                    var module: Module? = null
                    try {
                        val created = manager.newModule(File(imlDir, "$name.iml").absolutePath, "EMPTY_MODULE")
                        module = created
                        ModuleRootModificationUtil.updateModel(created) { it.addContentEntry(root) }
                        names += name
                        existing += entry.directory
                        added++
                    } catch (e: ProcessCanceledException) {
                        module?.let { manager.disposeModule(it) }
                        throw e
                    } catch (e: Exception) {
                        module?.let { manager.disposeModule(it) }
                        stats.failures += "${entry.name}: module registration failed (${e.message})"
                    }
                }
            }
        }
        indicator.text2 = ""
        return added
    }

    // -------------------------------------------------------------------------
    // VCS root registration — removes stale roots, adds new ones
    // -------------------------------------------------------------------------

    private fun registerVcsRoots(entries: List<ProjectEntry>, desired: List<ProjectEntry>): Int {
        val entryDirs = entries.filter { it.vcsType != null }.map { it.directory }.toSet()

        var added = 0
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) throw ProcessCanceledException()
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val current = vcsManager.directoryMappings

            val desiredDirs = desired.filter { it.vcsType != null }.map { it.directory }.toSet()
            val toRemove = current.filter { File(it.directory).canonicalPath !in desiredDirs }
            val toAdd = entryDirs
                .filter { dir -> current.none { File(it.directory).canonicalPath == dir } }
                .mapNotNull { dir -> entries.find { it.directory == dir } }

            if (toRemove.isNotEmpty()) {
                LOG.info("Removing ${toRemove.size} VCS roots no longer in config: ${toRemove.map { it.directory }}")
                stats.vcsRootsRemoved += toRemove.map { it.directory }
            }
            if (toAdd.isNotEmpty()) {
                LOG.info("Registering ${toAdd.size} new VCS roots")
            }

            val newMappings = current.toMutableList()
            newMappings.removeAll(toRemove)
            for (entry in toAdd) {
                LOG.debug("Registering VCS root '${entry.name}' (${entry.vcsType}): ${entry.directory}")
                newMappings += VcsDirectoryMapping(entry.directory, entry.vcsType!!)
            }
            if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) vcsManager.directoryMappings = newMappings
            added = toAdd.size
            LOG.info("VCS root sync done")
        }
        return added
    }
}
