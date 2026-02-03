---
name: Agent Chat Editor (Dedicated Frame + Current Project Frame)
description: Requirements for opening Agent chat as an editor tab via AsyncFileEditorProvider with a terminal-backed UI and dedicated-frame mode.
targets:
  - ../chat/src/*.kt
  - ../chat/resources/intellij.agent.workbench.chat.xml
  - ../chat/resources/messages/CodexChatBundle.properties
  - ../plugin/resources/META-INF/plugin.xml
  - ../plugin-content.yaml
  - ../sessions/src/*.kt
---

# Agent Chat Editor (Dedicated Frame + Current Project Frame)

Status: Draft
Date: 2026-02-04

## Summary
Provide an Agent chat editor that opens as a file editor tab via `AsyncFileEditorProvider`. The default mode opens chat in a dedicated AI-chat frame backed by a hidden internal project. A user-facing advanced setting allows switching to current-project-frame mode.

Dedicated-frame lifecycle and routing details are defined in `spec/codex-dedicated-frame.spec.md`.

## Goals
- Open chat editors in a dedicated frame by default, with a fallback mode for the current project frame.
- Reuse one editor tab per thread (and per sub-agent when applicable).
- Use the reworked terminal frontend as the initial UI surface.
- Keep the entry point and navigation anchored to the Agent Threads tool window.

## Non-goals
- Implementing a Compose-based app-server protocol UI.

## Requirements
- The `intellij.agent.workbench` content module must register a `fileEditorProvider` for Agent chat editors.
- The chat editor must be opened via `AsyncFileEditorProvider` and use the reworked terminal frontend (`TerminalToolWindowTabsManager`) with `shouldAddToToolWindow(false)`.
- Chat editors reuse an existing editor tab for the same `threadId` (and `subAgentId` if present).
- Advanced setting `agent.workbench.chat.open.in.dedicated.frame` controls target frame selection (default `true`).
- When the setting is enabled, chat opens in a dedicated frame project and the source project remains closed if it is currently closed.
- When the setting is disabled, chat opens in the source project frame and closed source projects are opened first.
- Clicking a thread row opens its chat editor. Clicking a sub-agent row opens a separate chat editor tab scoped to that sub-agent.
- The editor tab title must use the thread title (fallback to `Agent Chat` when blank).
- The shell command used to start chat sessions is `codex resume <threadId>`.

For dedicated-frame ownership, reuse policy, and filtering behavior, see `spec/codex-dedicated-frame.spec.md`.

## User Experience
- Single click on a thread row opens the chat editor.
- Single click on a sub-agent row opens a separate chat editor tab for that sub-agent.
- Editor tab name is the thread title; editor icon uses an Agent/communication glyph.
- By default, chat editor opens in a dedicated frame.
- Users can disable dedicated-frame mode from Advanced Settings to restore current-project-frame behavior.

## Data & Backend
- Chat terminal sessions start in the project working directory.
- The Codex CLI invocation is `codex resume <threadId>`.
- Do not override `CODEX_HOME`; rely on the default environment so project `.codex/` config is discovered via `cwd`.

## Error Handling
- If the project path is invalid or project opening fails, do not open a chat editor tab.

## Testing / Local Run
- Add tests for tab reuse per thread/sub-agent and tab title resolution.
- Run: `./tests.cmd -Dintellij.build.test.patterns=<FQN>` for chat module tests when added.

## Open Questions / Risks
- Dedicated-frame storage location policy may be revisited to align with welcome-project conventions.
