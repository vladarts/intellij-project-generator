package com.github.vladarts.idegen.generate

import com.github.vladarts.idegen.config.ConfigParser
import com.github.vladarts.idegen.config.ProjectGeneratorConfig
import com.github.vladarts.idegen.notify.ProjectGeneratorNotifier
import com.github.vladarts.idegen.settings.ProjectGeneratorSettings
import com.github.vladarts.idegen.settings.expandHome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
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

private val LOG = logger<GenerateService>()

class GenerateService(private val project: Project) {

    private val settings = ProjectGeneratorSettings.getInstance(project)

    data class Stats(
        var cloned: Int = 0,
        var skipped: Int = 0,
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

        // --- Clone ---
        val cloneTargets = entries.filter { it.cloneUrl != null }
        LOG.info("Cloning ${cloneTargets.size} repositories")
        cloneTargets.forEachIndexed { i, entry ->
            indicator.checkCanceled()
            indicator.text = "Cloning repositories… [${i + 1} / ${cloneTargets.size}]"
            indicator.text2 = entry.name
            indicator.fraction = 0.1 + 0.6 * (i.toDouble() / cloneTargets.size.coerceAtLeast(1))
            val cloned = cloneIfAbsent(entry)
            if (cloned) stats.cloned++ else stats.skipped++
        }
        indicator.text2 = ""

        // --- Register modules ---
        indicator.text = "Registering modules…"
        indicator.fraction = 0.75
        stats.modulesAdded = registerModules(entries, indicator)

        // --- Register VCS roots ---
        indicator.text = "Registering VCS roots…"
        indicator.fraction = 0.95
        stats.vcsRootsAdded = registerVcsRoots(entries)

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

    // -------------------------------------------------------------------------
    // Git clone — returns true if cloned, false if skipped
    // -------------------------------------------------------------------------

    private fun cloneIfAbsent(entry: ProjectEntry): Boolean {
        val dir = File(entry.directory)
        if (dir.exists()) {
            if (File(dir, ".git").exists()) {
                val origin = runGit(listOf("remote", "get-url", "origin"), dir)
                if (origin != null && origin != entry.cloneUrl) {
                    throw IllegalStateException(
                        "Directory '${entry.directory}' exists but origin '$origin' " +
                                "does not match config '${entry.cloneUrl}'"
                    )
                }
            }
            LOG.info("Skip clone '${entry.name}': already exists at ${entry.directory}")
            return false
        }

        LOG.info("Cloning '${entry.name}' from ${entry.cloneUrl} to ${entry.directory}")
        dir.parentFile?.mkdirs()
        val result = runGitWithResult(listOf("clone", entry.cloneUrl!!, entry.directory), null)
        if (result != 0) {
            dir.deleteRecursively()
            throw RuntimeException("git clone failed for '${entry.name}' (exit $result)")
        }
        LOG.info("Cloned '${entry.name}' successfully")

        if (entry.fastForward) {
            LOG.debug("Setting pull.ff=only for '${entry.name}'")
            runGit(listOf("config", "pull.ff", "only"), dir)
        }
        return true
    }

    private fun runGit(args: List<String>, workDir: File?): String? {
        val process = ProcessBuilder(listOf("git") + args)
            .also { pb -> workDir?.let { pb.directory(it) } }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0) output else null
    }

    private fun runGitWithResult(args: List<String>, workDir: File?): Int =
        ProcessBuilder(listOf("git") + args)
            .also { pb -> workDir?.let { pb.directory(it) } }
            .inheritIO()
            .start()
            .waitFor()

    // -------------------------------------------------------------------------
    // Module registration — returns number of newly added modules and fills removed list
    // -------------------------------------------------------------------------

    private fun registerModules(entries: List<ProjectEntry>, indicator: ProgressIndicator): Int {
        val basePath = project.basePath ?: run {
            LOG.warn("Project basePath is null, skipping module registration")
            return 0
        }
        val imlDir = File(basePath, ".idea/iml").also { it.mkdirs() }
        val entryDirs = entries.map { it.directory }.toSet()

        val moduleManager = ModuleManager.getInstance(project)

        // Remove modules whose content roots are all absent from the discovered list
        val toRemove = moduleManager.modules.filter { module ->
            val roots = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots
            roots.isNotEmpty() && roots.none { it.path in entryDirs }
        }
        if (toRemove.isNotEmpty()) {
            LOG.info("Removing ${toRemove.size} modules no longer in config: ${toRemove.map { it.name }}")
            WriteAction.runAndWait<Exception> {
                for (module in toRemove) {
                    LOG.debug("Removing module '${module.name}'")
                    moduleManager.disposeModule(module)
                }
            }
            stats.modulesRemoved += toRemove.map { it.name }
        }

        val existingDirs = moduleManager.modules
            .flatMap { m -> com.intellij.openapi.roots.ModuleRootManager.getInstance(m).contentRoots.map { it.path } }
            .toSet()

        val toAdd = entries.filter { it.directory !in existingDirs }
        if (toAdd.isEmpty()) {
            LOG.info("All modules already registered, nothing to add")
            return 0
        }
        LOG.info("Registering ${toAdd.size} new modules (${entries.size - toAdd.size} already exist)")

        val created = mutableListOf<Pair<Module, ProjectEntry>>()
        WriteAction.runAndWait<Exception> {
            for (entry in toAdd) {
                val imlPath = File(imlDir, "${entry.name}.iml").absolutePath
                LOG.debug("Creating module '${entry.name}' at $imlPath")
                created += moduleManager.newModule(imlPath, "") to entry
            }
        }

        created.forEachIndexed { i, (module, entry) ->
            indicator.text2 = "${entry.name} [${i + 1} / ${created.size}]"
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(entry.directory)
            if (vf == null) {
                LOG.warn("Directory not found on VFS, skipping content root for '${entry.name}': ${entry.directory}")
                return@forEachIndexed
            }
            LOG.debug("Adding content root for '${entry.name}': ${entry.directory}")
            ModuleRootModificationUtil.updateModel(module) { rootModel ->
                rootModel.addContentEntry(vf)
            }
        }
        indicator.text2 = ""
        LOG.info("Module registration done")
        return toAdd.size
    }

    // -------------------------------------------------------------------------
    // VCS root registration — removes stale roots, adds new ones
    // -------------------------------------------------------------------------

    private fun registerVcsRoots(entries: List<ProjectEntry>): Int {
        val entryDirs = entries.filter { it.vcsType != null }.map { it.directory }.toSet()

        var added = 0
        ApplicationManager.getApplication().invokeAndWait {
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val current = vcsManager.directoryMappings

            val toRemove = current.filter { it.directory !in entryDirs }
            val toAdd = entryDirs
                .filter { dir -> current.none { it.directory == dir } }
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
            vcsManager.directoryMappings = newMappings
            added = toAdd.size
            LOG.info("VCS root sync done")
        }
        return added
    }
}
