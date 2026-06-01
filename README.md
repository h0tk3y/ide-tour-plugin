# Claude IDE Tour

IntelliJ plugin that hosts a small MCP server inside the IDE so [Claude Code](https://docs.anthropic.com/claude/code) can drive guided code tours: pre-populate an ordered list of items — each a file + inlay-hint explanations + optional region highlights — and the user navigates from a tool window in the IDE.

## Install the plugin

Two ways:

- **From a release ZIP (recommended):** download the latest `ide-tour-plugin-<version>.zip` from [Releases](https://github.com/h0tk3y/ide-tour-plugin/releases). In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the ZIP → restart the IDE.
- **From source:** `./gradlew buildPlugin`, then install the ZIP from `build/distributions/`.

Built against IntelliJ Platform 2025.1; no upper-bound declared, so it loads in newer EAPs as well.

## Wire it to Claude Code

Once, from any directory:

```sh
claude mcp add --transport http intellij-tour http://127.0.0.1:64343/mcp
```

This registers the server in your user-level Claude Code config; the `mcp__intellij-tour__*` tools become available in every project. Add `-s project` to scope to the current repo instead (writes a project-local `.mcp.json`).

Restart Claude Code if the tools don't show up in the tool list.

## How it works

When you open a project in IntelliJ, the plugin starts an HTTP MCP server on `127.0.0.1:64343/mcp`. Claude connects and uses these tools:

| Tool | Purpose |
|---|---|
| `start_tour` / `end_tour` | Allocate / dispose a tour |
| `add_items` / `set_items` | Populate / replace the item list |
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
```

## Releases

Tag-driven. Push a `v*` tag (e.g. `v0.1.0`) and `.github/workflows/release.yml` builds the plugin and attaches the ZIP to a GitHub release with auto-generated notes.
