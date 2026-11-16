# Project Generator — IntelliJ Platform Plugin

**Project Generator** manages local development environments from a YAML config file.
It clones Git repositories into a structured directory layout, registers them as IDE modules,
and configures VCS roots automatically. On each run it fully reconciles the project state —
modules and VCS roots no longer present in the config are removed.

## Features

- Clone Git repositories into `$vcsRoot/hostname/group/repo` layout
- Check and clone repositories concurrently (4 workers by default, configurable from 1 to 64)
- Skip existing repositories after a local origin check, with no fetch/pull or discovery cache
- Report repository failures without stopping other workers
- Auto-discover repositories via GitLab API with include/exclude regex filters
- Register local directories as modules without cloning
- Automatically register and remove IDE modules and VCS roots to match config
- GitLab token via literal value, environment variable, or shell command

## Installation

Install from the [JetBrains Marketplace](https://plugins.jetbrains.com/) (search for "Project Generator"),
or download the `.zip` from [GitHub Releases](https://github.com/vladarts/intellij-project-generator/releases)
and install via **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Configuration

Go to **Settings → Tools → Project Generator** and set:

- **Config file** — path to your YAML configuration file (supports `~/`)
- **VCS sources root** — root directory for cloned repositories (default: `~/dev`)
- **Repository workers** — maximum concurrent checks/clones (default: `4`, range: `1–64`)

## Config File Format

```yaml
git:
  - url: git@github.com:org/repo.git
    fast_forward: true

raw:
  - path: /path/to/local/dir

gitlab:
  - url: https://gitlab.example.com
    token_command: "openid token --client-id=<id>"
    token_type: oauth          # private | job | oauth
    include_archived: false
    https_url: false
    fast_forward: true
    include:
      - "^group/.*"
    exclude:
      - "^group/archived-.*"
```

### GitLab token options (pick one)

| Field | Description |
|-------|-------------|
| `token` | Literal token value |
| `token_env_var` | Name of environment variable holding the token |
| `token_command` | Shell command whose stdout is the token |

## Usage

Click the **Project Generator** toolbar button (top-right) and choose an action,
or use **Tools → Project Generator** menu items.
Progress is shown in the IDE progress window. A summary notification is posted on completion.

### Failure and cancellation behavior

GitLab discovery runs sequentially on every generation. A discovery or configuration error
stops the run before module/VCS reconciliation, so an incomplete discovery cannot remove roots.
After discovery, repository errors are collected in the summary and other repositories continue.
Failed entries keep any existing module/VCS registration, but receive no new registrations.
Missing raw directories are reported as failures. Conflicting URLs for the same canonical directory
are rejected before cloning; duplicate entries are merged.

Existing Git directories (including linked worktrees) must have an origin matching the configured
URL exactly. They are never fetched, pulled, or cloned again. New repositories are cloned into
uniquely owned temporary sibling directories and moved into place only after success.
Cancellation stops queued work and Git processes and cleans up those temporary clones.
Completed repositories remain on disk. A second Generate cannot run in the same project concurrently.

## Building from Source

Target and minimum supported IDE: IntelliJ IDEA 2026.3 (build 263), Java 25.
Requirements: JDK 17 or 25 to launch Gradle, internet access for Gradle/IDE/JDK downloads.
Gradle automatically provisions the required JDK 25 toolchain via the Foojay resolver if it is not installed.
Gradle wrapper: 9.1.0; IntelliJ Platform Gradle plugin: 2.20.1; Kotlin: 2.4.21.
Older IDE builds are no longer declared compatible with this build.

```bash
./gradlew test buildPlugin verifyPlugin
```

The plugin zip will be at `build/distributions/project-generator-plugin-*.zip`.

The local plugin version is set by `pluginVersion` in `gradle.properties`.
Release builds use the pushed tag without its `v` prefix (for example, `v0.2.0` → `0.2.0`)
for both the ZIP filename and the plugin descriptor. To build a specific version locally,
pass `-PpluginVersion=0.2.0` to Gradle.

## License

MIT
