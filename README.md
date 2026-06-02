# Code Tour

IntelliJ plugin that hosts a small MCP server inside the IDE so [Claude Code](https://docs.anthropic.com/claude/code) can drive guided code tours: pre-populate an ordered list of items — each a file + inlay-hint explanations + optional region highlights — and the user navigates from a tool window in the IDE.

## Install the plugin

Two ways:

- **From a release ZIP (recommended):** download the latest `ide-tour-plugin-<version>.zip` from [Releases](https://github.com/h0tk3y/ide-tour-plugin/releases). In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the ZIP → restart the IDE.
- **From source:** `./gradlew buildPlugin`, then install the ZIP from `build/distributions/`.

Built against IntelliJ Platform 2025.1; no upper-bound declared, so it loads in newer EAPs as well.

## Wire it to Claude Code

Two one-liners, once per machine. From any directory:

```sh
# 1. Register the MCP server in your user-level Claude Code config.
claude mcp add --transport http intellij-tour http://127.0.0.1:64343/mcp

# 2. Install the companion skill so Claude knows how to use the tools.
mkdir -p ~/.claude/skills && \
  curl -sSL https://github.com/h0tk3y/ide-tour-plugin/releases/latest/download/intellij-mcp-code-tour.md \
  -o ~/.claude/skills/intellij-mcp-code-tour.md
```

Both steps target user-level config, so the tour skill becomes available in every project you open with Claude Code. For project-scoped wiring instead, add `-s project` to the first command (writes a `.mcp.json` next to where you run it) and copy the skill into `<repo>/.claude/skills/` instead of `~/.claude/skills/`.

Restart Claude Code if `mcp__intellij-tour__*` doesn't show up in the tool list, or if `/intellij-mcp-code-tour` isn't recognized as a skill.

## How it works

When you open a project in IntelliJ, the plugin starts an HTTP MCP server on `127.0.0.1:64343/mcp`. Claude connects and uses these tools:

| Tool | Purpose |
|---|---|
| `start_tour` / `end_tour` | Allocate (optionally populating items in one call) / dispose a tour |
| `add_items` / `set_items` | Append more items / replace the item list |
| `update_item` / `remove_item` / `list_items` | Surgical edits and inspection |
| `goto_item` / `next_item` / `prev_item` / `get_current_item` | Navigation (and polling) |
| `navigate` | Stateless `file:line` jump, no tour required |

Each item materializes as:

- An **inlay hint** above/below/at-end-of the specified line, with a state prefix (`✓` passed / `▶` current / `○` future).
- Optional **range highlighters** marking the relevant code region.

A **Code Tour** tool window (right side) shows the active tour's item list with Prev / Next / Stop icon buttons; click an item to jump. A **status bar widget** shows the current position. Only one tour is visible at a time — switching tours hides the previous and shows the new.

## Smoke test

After installing + wiring, with a project open in IntelliJ:

```sh
curl -sS -X POST http://127.0.0.1:64343/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 200
```

Returns a JSON envelope listing the tools.

## Develop

```sh
./gradlew runIde        # sandbox IDE with the plugin pre-installed
./gradlew buildPlugin   # produce the sideloadable ZIP
```

Auto-reload is enabled in the sandbox (`-Didea.auto.reload.plugins=true`); rebuilding in another shell hot-swaps the plugin without a sandbox restart. The plugin is unload-safe — a `DynamicPluginListener` stops the HTTP server + its thread pool cleanly before the classloader is released.

## Layout

```
src/main/
├── kotlin/com/gradle/idetour/
│   ├── PluginStartupActivity.kt       # boots the MCP server on first project open
│   ├── PluginUnloadListener.kt        # stops the server before classloader unload
│   ├── mcp/                           # MCP HTTP server + JSON-RPC framing
│   │   ├── McpServerService.kt
│   │   ├── McpHttpHandler.kt
│   │   ├── McpProtocol.kt
│   │   ├── ToolRegistry.kt
│   │   └── tools/                     # one file per MCP tool
│   ├── tours/                         # tour state, inlay renderer, materialization
│   └── ui/                            # tool window panel + status bar widget
└── resources/META-INF/plugin.xml      # plugin descriptor

skill/
└── intellij-mcp-code-tour.md          # companion Claude Code skill; published with each release
```

## Releases

Tag-driven. Push a `v*` tag (e.g. `v0.1.0`) and `.github/workflows/release.yml` builds the plugin and attaches the ZIP to a GitHub release with auto-generated notes.
