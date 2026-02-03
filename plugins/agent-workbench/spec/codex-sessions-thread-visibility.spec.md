---
name: Agent Threads Visibility and Paging
description: Deterministic rules for thread row visibility, More row rendering, and initial paging normalization in Agent Threads.
targets:
  - ../sessions/src/CodexSessionsService.kt
  - ../sessions/src/SessionTree.kt
  - ../sessions/testSrc/CodexSessionsToolWindowTest.kt
  - ../sessions/testSrc/CodexSessionsPagingLogicTest.kt
---

# Agent Threads Visibility and Paging

Status: Draft
Date: 2026-02-09

## Summary
Define a single state model for project thread rows so `Empty`, `More...`, and paging states are consistent across refresh, on-demand load, and explicit `More...` clicks.

## Goals
- Keep initial project rendering stable and deterministic.
- Avoid contradictory child rows (for example `No recent activity yet.` and `More...` together).
- Preserve incremental reveal by 3 rows per user click while allowing backend paging.

## Non-goals
- Changing backend page size.
- Adding new user-visible strings.
- Changing dedicated-frame chat routing behavior.

## Requirements
- A project with loaded threads must be rendered using descending `updatedAt` ordering.
- Initial reveal size is 3 rows.
- `More...` row is shown when either:
  - there are hidden loaded threads beyond the current visible count, or
  - a backend cursor exists and loaded thread count is at least 3.
- `More...` must not be shown when loaded thread count is 0, 1, or 2.
- Empty row (`No recent activity yet.`) and `More...` row are mutually exclusive.
- For initial refresh/on-demand load, when loaded thread count is below 3 and a cursor exists, implementation must eagerly fetch additional pages until one of the following is true:
  - loaded thread count reaches 3,
  - cursor is absent,
  - a cursor loop/no-progress guard triggers.
- Eager merge must deduplicate by thread id and keep the newest value when ids collide.
- `More...` click behavior remains incremental: first reveal hidden loaded rows by +3, and only page backend when no hidden loaded rows remain.

[@test] ../sessions/testSrc/CodexSessionsToolWindowTest.kt
[@test] ../sessions/testSrc/CodexSessionsPagingLogicTest.kt

## User Experience
- Expanded projects with zero loaded threads show `No recent activity yet.`.
- Expanded projects with one or two loaded threads show only those threads.
- `More...` appears only when it can reveal additional context without conflicting with empty semantics.

## Data & Backend
- Initial fetch still starts with a single page request (`limit=50`).
- Eager normalization may request additional pages before committing state to the UI model.
- Cursor-loop guard must stop repeated requests for already seen cursors.

## Error Handling
- If eager normalization fetch fails, failure follows existing refresh/on-demand error handling paths.
- `More...` paging failure behavior remains unchanged: keep loaded threads/cursor and show load-more retry messaging.

## Testing / Local Run
- `./tests.cmd -Dintellij.build.test.patterns=com.intellij.agent.workbench.sessions.CodexSessionsToolWindowTest`
- `./tests.cmd -Dintellij.build.test.patterns=com.intellij.agent.workbench.sessions.CodexSessionsPagingLogicTest`

## Open Questions / Risks
- If backend repeatedly returns cursors without new unique threads, users may still end with fewer than 3 rows; the loop guard intentionally favors safety over unbounded requests.

## References
- `spec/codex-sessions.spec.md`
- `spec/codex-dedicated-frame.spec.md`
