# CLAUDE.md — intellij-project-generator

## Project Overview

**Project Generator** is an IntelliJ Platform plugin that manages local development environments.
It is the IDE-native counterpart of the `ide-gen` Go CLI tool.

- Clones Git repositories into `$vcsRoot/hostname/group/repo` layout
- Auto-discovers repositories via GitLab API with include/exclude regex filtering
- Registers cloned and local directories as IDE modules + VCS roots
- Fully reconciles project state on each run (removes stale modules/VCS roots)

**Plugin ID:** `com.github.vladarts.idegen`
**Gradle group:** `com.github.vladarts`
**Minimum IDE build:** `241` (IntelliJ IDEA 2024.1), no upper bound

---

## Repository Structure

```
build.gradle.kts            # Gradle build — IntelliJ Platform Plugin v2
settings.gradle.kts
gradle.properties           # Disables config cache, Gradle cache
gradle/wrapper/             # Gradle 8.10 wrapper

src/main/
  resources/META-INF/
    plugin.xml              # Plugin descriptor (actions, services, configurable)
  kotlin/com/github/vladarts/idegen/
    actions/
      GenerateAction.kt         # "Generate Project" toolbar/menu action
      EditConfigAction.kt       # "Edit Config" opens YAML in editor
      OpenSettingsAction.kt     # "Project Generator Settings" opens settings panel
      ProjectGeneratorActionGroup.kt  # Dropdown group for toolbar
    config/
      ProjectGeneratorConfig.kt # Data classes: GitEntry, DirectoryEntry, GitLabEntry
      ConfigParser.kt           # SnakeYAML map-based parser (snake_case + camelCase)
    generate/
      GitUrlParser.kt           # SSH/HTTPS URL → hostname + path segments
      GitLabDiscovery.kt        # GitLab API pagination + token resolution
      GenerateService.kt        # Main orchestration: clone, register modules/VCS roots
    notify/
      ProjectGeneratorNotifier.kt  # Balloon notifications (info/warning/error)
    settings/
      ProjectGeneratorSettings.kt           # PersistentStateComponent, project-level
      ProjectGeneratorSettingsConfigurable.kt  # BoundConfigurable, Kotlin UI DSL
```

---

## Key Patterns

**Directory layout:** `$vcsRoot/{hostname}/{group...}/{repo}`
Example: `~/dev/github.com/myorg/myrepo`

**Module naming:** path segments joined with dots, `.git` suffix stripped
Example: `git@github.com:myorg/sub/repo.git` → `myorg.sub.repo`

**Idempotency:** repos that already exist are skipped; origin URL is validated.

**Thread safety:**
- `WriteAction.runAndWait` for module create/delete (write thread required)
- `ApplicationManager.getApplication().invokeAndWait` for VCS root updates (EDT required)

**Token resolution order (GitLab):** `tokenEnvVar` → `tokenCommand` (via `sh -c`) → `token`

**Token logging:** only first 4 chars + `***` are logged; command value is never logged.

---

## Build

```bash
./gradlew buildPlugin          # produces build/distributions/*.zip
./gradlew runIde               # launches sandbox IDE with plugin loaded
```

Requires JDK 17. Gradle wrapper (8.10) downloads itself on first run.

**Do NOT** pass `--configuration-cache` — it is disabled in `gradle.properties`.

---

## IntelliJ Platform SDK Notes

- Uses **IntelliJ Platform Gradle Plugin v2** (`org.jetbrains.intellij.platform` 2.2.1)
- `pluginConfiguration` block in `build.gradle.kts` patches `plugin.xml`; do NOT set `description` there or it overrides the full HTML description in `plugin.xml`
- `untilBuild = provider { null }` removes the upper build bound
- Module creation uses `ModuleManager.newModule()` inside `WriteAction.runAndWait` (the `modifiableModel` API was removed in 2024.1)
- VCS root reconciliation uses `ProjectLevelVcsManager.directoryMappings` setter; must run on EDT

---

## Dependencies

| Artifact | Version | Purpose |
|----------|---------|---------|
| `org.jetbrains.intellij.platform` | 2.2.1 | Gradle plugin |
| `intellijIdeaCommunity` | 2024.1 | Platform SDK |
| `org.yaml:snakeyaml` | 1.33 | YAML config parsing |
| `com.google.code.gson:gson` | 2.10.1 | GitLab API JSON parsing |

---

## Release

Releases are created by pushing a Git tag (`v*`). GitHub Actions builds the plugin and
attaches the `.zip` to the GitHub Release. See `.github/workflows/build.yml`.
