---
name: intellij-mcp-code-tour
description: Walk the user through code using the Claude IDE Tour plugin's MCP tools. Pre-populates an ordered tour of inlay-hint explanations and optional highlights in their IntelliJ; the user navigates via the Code Tour tool window or asks you to advance. Use for any "show me around X" / "tour me through Y" request when the plugin is installed.
---

# Code tour via the Claude IDE Tour plugin

This skill drives a code walkthrough by pre-populating an entire tour as inlay hints (with optional region highlights) in IntelliJ. The user navigates from a tool window in the IDE; you optionally narrate each stop in chat.

## Mechanism

The `intellij-tour` MCP server exposes these tools:

| Tool | Purpose |
|---|---|
| `mcp__intellij-tour__start_tour(projectPath, title?)` | Allocate a tour. Returns `tourId`. |
| `mcp__intellij-tour__add_items(tourId, items)` | Append items. First call auto-activates item 0. |
| `mcp__intellij-tour__set_items(tourId, items)` | Replace the list entirely. |
| `mcp__intellij-tour__update_item(tourId, itemId, item)` | Replace one item in place. |
| `mcp__intellij-tour__remove_item(tourId, itemId)` | Drop one item. |
| `mcp__intellij-tour__list_items(tourId)` | Inspect the canonical state (items + currentIndex). |
| `mcp__intellij-tour__goto_item(tourId, itemId|index)` | Jump (dialog mode). |
| `mcp__intellij-tour__next_item(tourId)` / `prev_item(tourId)` | Advance / go back. |
| `mcp__intellij-tour__get_current_item(tourId)` | Poll the user's position (in case they navigated via the panel). |
| `mcp__intellij-tour__end_tour(tourId)` | Dispose annotations and clean up. |
| `mcp__intellij-tour__navigate(projectPath, file, line, col?)` | Stateless file:line navigation for detours, not bound to a tour. |

### Item shape

```json
{
  "title": "string shown in the tool window",
  "file": "project-relative or absolute path",
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
- `highlights` is optional; useful for marking the region the explanation refers to.
- `anchor` defaults to the first inlay's line.
- Inlay text is rendered with a state prefix (`✓` passed / `▶` current / `○` future) automatically — don't include one in `text`.

## Workflow

1. **Scope.** If the user supplied a topic (e.g. `/intellij-mcp-code-tour the build action runners`), use it. Otherwise ask one short clarifying question.
2. **Plan.** Produce an ordered list of stops. Each `TourItem` has a `title`, a `file`, at least one `inlay`, and optional `highlights`. Present the plan in chat in human-readable form.
3. **Confirm via `AskUserQuestion`** with three options:
   - **Driver mode**: push the tour, then hand off — the user navigates with the tool window.
   - **Dialog mode**: push the tour and step through it yourself, explaining each stop in chat.
   - **Revise**: edit and re-confirm.
4. **Push.**
   - `start_tour(projectPath, title)` once. Save the `tourId`.
   - `add_items(tourId, items)` with the full list. The first item auto-activates (opens its file, scrolls).
5. **Driver mode** — write one sentence telling the user the tour is ready and that they navigate from the Code Tour tool window (right side, "Prev"/"Next"/"Stop" icons). Stop. You're done until they ask a follow-up.
6. **Dialog mode** — after the push, walk through stops one at a time:
   - Call `goto_item(tourId, index=N)` (or `next_item`).
   - Write 1-3 sentences of explanation in chat referencing the item.
   - **Stop and wait.** Use `AskUserQuestion` with "Next stop" / "End the tour" labels; the user can pick "Other" to ask a question or detour.
   - At the start of each narration turn, optionally call `get_current_item(tourId)` to detect tool-window-driven navigation.
7. **Wrap up.** When the user indicates they're done (or after the last stop in dialog mode), call `end_tour(tourId)`.

## Don'ts

- Don't fire multiple navigation tool calls in one turn during dialog mode. One stop, one explanation, then wait.
- Don't push items incrementally with many `add_items` calls — compose the full list and push it once.
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
