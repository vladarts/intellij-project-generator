# Project Generator — IntelliJ Platform Plugin

**Project Generator** manages local development environments from a YAML config file.
It clones Git repositories into a structured directory layout, registers them as IDE modules,
and configures VCS roots automatically. On each run it fully reconciles the project state —
modules and VCS roots no longer present in the config are removed.

## Features

- Clone Git repositories into `$vcsRoot/hostname/group/repo` layout
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

## Building from Source

Requirements: JDK 17, internet access for Gradle dependency download.

```bash
./gradlew buildPlugin
```

The plugin zip will be at `build/distributions/project-generator-plugin-*.zip`.

## License

MIT
