---
name: intellij-mcp-code-tour
description: Walk the user through code using the Code Tour plugin's MCP tools. Pre-populates an ordered tour of 
   inlay-hint explanations and optional highlights in their IntelliJ; the user navigates via the Code Tour tool window or asks you to advance. Use for any "show me around X" / "tour me through Y" request when the plugin is installed.
---

# Code tour via the Code Tour plugin

This skill drives a code walkthrough by pre-populating an entire tour as inlay hints (with optional region highlights) 
in IntelliJ. The user navigates from a tool window in the IDE; you optionally narrate each stop in chat.

## Mechanism

The `intellij-tour` MCP server exposes these tools:

| Tool | Purpose |
|---|---|
| `mcp__intellij-tour__start_tour(projectPath, title?, items?)` | Allocate a tour and (preferred) push the full item list in the same call. Auto-activates item 0. Returns `tourId`. |
| `mcp__intellij-tour__add_items(tourId, items)` | Append more items to an already-running tour. Avoid this for the initial push — pass items to `start_tour` instead. |
| `mcp__intellij-tour__set_items(tourId, items)` | Replace the list entirely. |
| `mcp__intellij-tour__update_item(tourId, itemId, item)` | Replace one item in place. |
| `mcp__intellij-tour__remove_item(tourId, itemId)` | Drop one item. |
| `mcp__intellij-tour__list_items(tourId)` | Inspect the canonical state (items + currentIndex). |
| `mcp__intellij-tour__goto_item(tourId, itemId\|index)` | Jump to a specific stop, e.g. when the user asks "go to step 3". |
| `mcp__intellij-tour__next_item(tourId)` / `prev_item(tourId)` | Advance / go back, e.g. when the user asks "next" in chat. |
| `mcp__intellij-tour__get_current_item(tourId)` | Poll the user's position (they navigate in the tool window). |
| `mcp__intellij-tour__end_tour(tourId)` | Dispose annotations and clean up. |
| `mcp__intellij-tour__navigate(projectPath, file, line, col?)` | Stateless file:line navigation for detours, not bound to a tour. |

### Item shape

```json
{
  "title": "string shown in the tool window",
  "file": "project-relative or absolute path",
  "location": "TourState.gotoIndex",
  "anchor": {"line": 42, "col": 1},
  "inlays": [
    {"line": 42, "text": "explanation", "position": "aboveLine"}
  ],
  "highlights": [
    {"startLine": 42, "startCol": 1, "endLine": 50, "endCol": 1}
  ]
}
```

- `inlays` is **required** and must contain at least one entry. `position` is `aboveLine` (default), `belowLine`, or `endOfLine`.
- `highlights` is optional but is useful for marking the region the explanation refers to; if provided, try to highlight a meaningful code regions such as a member or a few of them.
- `anchor` defaults to the first inlay's line.
- `location` is optional but **strongly recommended**. The renderer shows it as a subtitle below the title in a **monospace font**, so it should be **code-like with as little natural language as possible** — an identifier path, signature, or filename, not a description. Good: `TourState.gotoIndex`, `McpHttpHandler#handle`, `ClassName$InnerClass`, `build.gradle.kts`, `package.subpackage.ClassName`. Bad: `the main entry point`, `Settings — General`, `where the tour boots`. If omitted, the renderer falls back to `<basename>:<line>` (e.g. `TourState.kt:48`).
- Inlay text is rendered with a state prefix (`✓` passed / `▶` current / `○` future) automatically — don't include one in `text`.

## Workflow

1. **Scope.** If the user supplied a topic (e.g. `/intellij-mcp-code-tour the build action runners`), use it. Otherwise ask one short clarifying question.
2. **Plan.** Produce an ordered list of stops. Each `TourItem` has a `title`, a `file`, at least one `inlay`, and optional `highlights`. Present the plan in chat in human-readable form.
3. **Confirm via `AskUserQuestion`** with two options:
   - **Push it**: push the tour and hand off — the user navigates with the tool window.
   - **Revise**: edit and re-confirm.
4. **Push.** Call `start_tour(projectPath, title, items)` once with the full item list. Save the returned `tourId`. The first item auto-activates (opens its file, scrolls). Use `add_items` only if you need to append more items to a tour that's already running.
5. **Hand off.** Write one sentence telling the user the tour is ready and that they navigate from the Code Tour tool window (right side, "Prev"/"Next"/"Stop" icons). Stop. You're done until they ask a follow-up.
6. **Follow-ups.** If the user asks in chat to "go to step N" / "next" / "back", call `goto_item` / `next_item` / `prev_item`. Use `get_current_item` to confirm position before answering questions about "what am I looking at?".
7. **Wrap up.** When the user indicates they're done, call `end_tour(tourId)`.

## Don'ts

- Don't split the initial push across `start_tour` + `add_items`, and don't do incremental `add_items` calls — compose the full list and pass it to `start_tour` in one call.
- Don't use this skill when the user only wants a single `file:line` reference; a chat citation is enough.
- Don't navigate via this skill for files outside the open project. Use the stateless `navigate` tool for one-off detours instead.

## Prerequisites (one-time per teammate)

1. **Build the plugin.** In the `ide-tour-plugin/` directory:
   ```sh
   ./gradlew buildPlugin
   ```
   Produces `build/distributions/ide-tour-plugin-<version>.zip`.
2. **Install in IntelliJ.** Settings → Plugins → ⚙ → **Install Plugin from Disk…** → pick the ZIP. Restart the IDE.
3. **Open this project** in IntelliJ — the same project Claude is working with. The MCP server starts automatically on first project open and listens on `127.0.0.1:64343/mcp`.
4. **Register the MCP server with Claude Code.** One-liner — no JSON editing required:
   ```sh
   claude mcp add --transport http intellij-tour http://127.0.0.1:64343/mcp
   ```
   This writes the server entry to your user-level Claude Code config; it'll be available in every project. Add `-s project` to scope it to the current repo instead (writes a `.mcp.json` next to where you ran it).

   Restart Claude Code if `mcp__intellij-tour__*` doesn't show up in the tool list.

### Smoke test the connection

```sh
curl -sS -X POST http://127.0.0.1:64343/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 200
```

Should return a JSON envelope listing the tools.

## Troubleshooting

- **No `mcp__intellij-tour__*` tools in your tool list** → the plugin isn't running. See Prerequisites; ensure the plugin is installed and a project is open in IntelliJ, then restart Claude Code so it picks up the MCP server.
- **Tools list but every call returns `"No open project at …"`** → the user needs to open the project at that exact path in IntelliJ.
- **`Connection refused` on the smoke test** → the IDE isn't running, or no project is open (the server starts on first project open).
