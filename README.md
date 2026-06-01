# Claude IDE Tour plugin

An IntelliJ plugin that exposes an MCP server so Claude Code can drive guided code tours: pre-populate an ordered list of items (file + inlay-hint explanations + optional highlights) and let the user navigate item-by-item from a tool window.

Design plan: `../gradle/plan-claude-ide-tour-plugin.md`

## Status

**Phase 0** — plugin skeleton. Loads in the IDE and writes a single info-level log line per project open. No MCP server yet, no tools, no tour state.

## Build & run

```sh
# Build the sideloadable ZIP (output in build/distributions/)
./gradlew buildPlugin

# Sandbox-run a fresh IDE instance with the plugin pre-installed
./gradlew runIde
```

## Verify it loaded

In the sandbox IDE: `Help > Show Log in Finder`, then grep `idetour`:

```
[ide-tour-plugin] started for project: <project name>
```

## Layout

```
src/main/
├── kotlin/com/gradle/idetour/
│   └── PluginStartupActivity.kt    # logs on project open
└── resources/META-INF/
    └── plugin.xml                   # plugin descriptor
```
