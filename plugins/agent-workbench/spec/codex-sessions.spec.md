---
name: Agent Threads Tool Window (Project-Scoped)
description: Requirements for the Agent Threads tool window with per-project app-server sessions and CodexMonitor parity.
targets:
  - ../plugin/resources/META-INF/plugin.xml
  - ../plugin-content.yaml
  - ../sessions/src/*.kt
  - ../sessions/resources/intellij.agent.workbench.sessions.xml
  - ../sessions/resources/messages/AgentSessionsBundle.properties
  - ../sessions/testSrc/*.kt
---

# Agent Threads Tool Window (Project-Scoped)

Status: Draft
Date: 2026-02-08

## Summary
Provide an Agent Threads tool window that matches CodexMonitor’s core behavior while aligning with IntelliJ Platform visual semantics: sessions are grouped by project, each project has its own Codex app-server session, and the list is a three-level hierarchy (project → thread → sub-agent placeholder).

Threads are rendered as single-line rows with short relative timestamps. The UI shows only active (non-archived) threads, exposes `Open` for closed projects via project-row context menu, exposes `New Thread` via project-row hover action for both open and closed projects, and exposes refresh in the tool window action menu.

Dedicated-frame routing semantics for thread and sub-agent opens are specified in `spec/codex-dedicated-frame.spec.md`.

## Rationale
- CodexMonitor scopes threads to a workspace by running a dedicated app-server per workspace. Mirroring this avoids fragile thread-to-project mapping.
- The Codex app-server `thread/list` response is global and does not include project paths, so per-project sessions are the only reliable way to group threads by project without heuristics.

## Goals
- Match CodexMonitor’s project-scoped session model (one app-server per project).
- Render a three-level hierarchy (project → thread → sub-agent placeholder) with concise, one-line rows and short relative time (e.g., `10m`).
- Support focusing/opening the corresponding IDE project when a thread is clicked.
- Start app-server sessions only for currently open projects to keep resource usage bounded.
- Use Jewel theme typography, colors, and tree metrics instead of hard-coded values.

## Non-goals
- Archived sessions UI or unarchive actions (keep for future work; see TODOs).
- Creating projects/workspaces or modifying Codex configuration from the IDE.
- Thread transcript view, compose, approvals, search, or filtering.
- Sub-agent data retrieval or status mapping (layout only; data is TBD).

## Requirements
- The tool window project registry must include currently open projects (via `ProjectManager.getInstance().openProjects`).
- The tool window project registry must include recent projects (via `RecentProjectsManagerBase.getRecentPaths()`).
- Each open project must have a dedicated Codex app-server process. Closed projects must not spawn a process until opened.
- Thread lists must be fetched per project via `thread/list` and must **exclude archived threads** (do not request archived pages).
- The UI must group threads by project name. Project names must come from Recent Projects metadata when available; otherwise fall back to the filesystem name.
- The tree must support three levels: project nodes contain thread nodes; thread nodes may contain sub-agent leaf nodes when data is available.
- Project row primary click action is open/focus project.
- A closed project group must expose an `Open` action in the row context menu; invoking it must open the project and start its session.
- Project rows must expose a hover `New Thread` action for both open and closed projects.
- Invoking `New Thread` must create a thread scoped to the selected project path and then open chat for that thread.
- `New Thread` chat-open routing must follow dedicated-frame mode semantics:
  - dedicated-frame mode (`agent.workbench.chat.open.in.dedicated.frame=true`): keep source project closed if it is currently closed.
  - current-project mode (`agent.workbench.chat.open.in.dedicated.frame=false`): open/focus source project as needed.
- Clicking `New Thread` must not trigger the project row click action.
- Project rows must always be expandable; expanding a closed project loads threads on demand without opening the IDE window.
- Project groups are expanded by default so thread rows are visible immediately.
- If a user collapses a project group, that collapse preference must be persisted and respected on subsequent renders/restarts.
- Thread row visibility, `More...` rendering, and paging/reveal sequencing must follow `spec/codex-sessions-thread-visibility.spec.md`.
- Open-project thread previews should be restored from persisted UI state for fast first paint, then refreshed in the background.
- Clicking a thread row must open the chat editor according to `agent.workbench.chat.open.in.dedicated.frame`:
  - `true` (default): open in a dedicated AI-chat frame project without opening the source project.
  - `false`: open in the source project frame (opening the source project first when closed).
- Clicking a sub-agent row must open a separate chat editor tab scoped to that sub-agent using the same mode.
- Archived session actions must be hidden from the UI, and the code must include a TODO noting future unarchive support.

Detailed dedicated-frame project lifecycle, visibility, and reuse requirements are defined in `spec/codex-dedicated-frame.spec.md`.

[@test] ../sessions/testSrc/AgentSessionsToolWindowTest.kt

- Integration-style coverage must include backend-backed tree scenarios (saved preview restore and `More...` paging) against both mock and real Codex backends.
- Real Codex backend scenarios are environment-gated (`CODEX_BIN`/auth available); mock backend scenarios must always run.

## User Experience
- Use the standard tool window title bar (do not duplicate the title in the content).
- Tool window action menu (gear/context menu) includes `Open...` (platform `Open File or Project`), `Open Chat in Dedicated Frame` (toggle), and `Refresh`.
- For each project, render a compact header row styled like CodexMonitor’s workspace row, using Jewel theme typography and tree metrics. Keep the row clean, use regular-weight text (no bold), and expose `Open` via the project context menu when the project is closed.
- Project rows expose a trailing hover `New Thread` action for both open and closed projects.
- Hover action reveal must not change project-row height or shift content horizontally.
- `New Thread` hover action uses pointer hand cursor and a stable trailing action slot.
- Project rows use a subtle neutral background tint derived from tree/list selection colors (unless selected/active) to emphasize project grouping.
- Rows use tree metrics for padding and spacing (no extra per-row padding).
- Project rows always show a chevron; there should be no empty chevron placeholders when a row has no children yet.
- Reduce tree indent locally so chevron + depth reads as a single step.
- Expanding a closed project loads threads on demand without opening the IDE window.
- Project groups are expanded by default unless the user previously collapsed that project.
- Each thread row is a single line: default status dot, title, and short relative time (e.g., `now`, `10m`, `2h`). Thread rows use theme regular text and theme small text for time.
- Each project initially shows up to 3 thread rows; detailed `More...` row visibility rules are defined in `spec/codex-sessions-thread-visibility.spec.md`.
- Clicking a thread row opens its chat editor in dedicated frame mode by default; when the dedicated-frame setting is disabled, it opens in the current project frame (opening the project first if needed).
- Sub-agent rows are a third level with a smaller status dot and theme small text (no extra weight). (Data population is TODO.)
- Clicking a sub-agent row opens a separate chat editor tab for that sub-agent.
- When a project is loading, show a lightweight busy indicator in its section.
- Empty state uses `Loading threads...` during the initial refresh.
- After refresh, if there are no project rows, show `Open a project to start activity.` with muted text styling.
- When project rows are present, do not render a global helper or empty-state line above the tree.
- When a project is expanded and has no threads, show a muted `No recent activity yet.` child row.
- Error state should be localized and provide a retry action.

## Data & Backend
- Codex app-server protocol is JSON-RPC over stdio, one JSON object per line (no `jsonrpc` field).
- Each project session must be started with `codex app-server` using the project path as `cwd`.
- Closed-project on-demand operations may use short-lived app-server clients scoped to that project path; long-lived sessions remain tied to open IDE projects.
- Do not set `CODEX_HOME`; rely on the default environment so project `.codex/` config is discovered via `cwd`.
- Ordering: threads must be sorted by `updated_at` descending per project.
- Paging must stop when there is no `nextCursor`.
- Initial per-project fetch should request a larger backend page (50) for responsiveness, while UI reveal remains incremental by 3 rows.
- Sub-agent data is not currently returned by the Codex app-server; keep model placeholders and render sub-agent rows only when data is present.

## Error Handling
- If the Codex CLI is missing or a project session fails to start, surface a project-local error state.
- If a thread list request fails, the project group should show a retry action.
- If a refresh fails for a project that already has loaded threads, keep existing thread rows and cursor state; show a non-blocking inline error with retry.
- If `More...` paging fails, keep already loaded threads and cursor state and show inline retry feedback that retries loading additional threads for the same project.

## Testing / Local Run
- Add UI tests for grouping, `Open` action availability (no inline link), and short relative time formatting.
- Add unit tests for toolwindow gear actions wiring (`Open...` + `Refresh`) and a smoke test that `Refresh` updates sessions state.
- Add service tests for per-project session lifecycle and for skipping archived thread fetches.

## Open Questions / Risks
- Thread “active” highlighting has no IDE equivalent yet; decide whether to add it later.
- Sub-agent status mapping and data source are TBD.

## References
- CodexMonitor source code is the primary behavioral reference for grouping and time formatting.
- `spec/codex-dedicated-frame.spec.md`
- `spec/codex-sessions-thread-visibility.spec.md`
