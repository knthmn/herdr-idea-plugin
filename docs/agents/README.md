# Agent integrations

> [!NOTE]
> This document was AI generated, information in it should be verified.

The plugin reads three terminal labels: `resume`, `title` and `blocked`. These are the default keys in the Labels group of Settings > Tools > Herdr. An agent does not write them itself, so each agent needs a small integration that calls `hc label $HC_TERMINAL_ID key=value`. See [Labels](../../README.md#labels-and-agent-integrations) for what the plugin does with each label.

| Agent | Files | `resume` | `title` | `blocked` | Checked |
|---|---|---|---|---|---|
| pi | [`pi-extensions/`](../pi-extensions/) | `pi --session '<file>'` | Session name | Open `ask_user_question` | pi 1.4.0 |
| Claude Code | [`claude-code/`](claude-code/) | `cd '<cwd>' && claude --resume <id>` | Custom session title | Open `AskUserQuestion`, permission prompt | No |
| Codex CLI | [`codex/`](codex/) | `cd '<cwd>' && codex resume <id>` | Thread name | Approval prompt | No |
| OpenCode | [`opencode/`](opencode/) | `cd '<dir>' && opencode --session <id>` | Session title | Open question, permission prompt | No |

- All integrations do nothing outside a herdr-core terminal (no `HC_TERMINAL_ID`). They look for `hc` on `PATH`, then at `~/.local/bin/hc`.
- The Claude Code, Codex and OpenCode examples follow the hook documentation of Claude Code 2.1, Codex CLI 0.163 and OpenCode 1.19. They were not run against these agents.
- The `resume` command starts with `cd`, because these agents find a session by its folder. Resume types the command into the terminal's shell, or runs it in a new terminal when the old one is finished.
- The Claude Code and Codex scripts need `jq`.

## Check an integration

1. Start the agent with `hc run --bg --name probe <agent>`, then run `hc get probe`. `labels` contains `resume`.
2. Name the session. `labels.title` holds the name. For Claude Code and Codex, the name shows after the next prompt.
3. Make the agent ask a question or wait for an approval. `labels.blocked` holds the question. It is empty again after the answer.

## pi

```sh
cp docs/pi-extensions/hc-labels.ts docs/pi-extensions/hc-blocked.ts ~/.pi/agent/extensions/
```

Running pi sessions load them after a restart or `/reload`.

## Claude Code

```sh
mkdir -p ~/.claude/hooks
cp docs/agents/claude-code/hc-labels.sh ~/.claude/hooks/
```

Then merge the `hooks` object of [`claude-code/settings.json`](claude-code/settings.json) into `~/.claude/settings.json`. Running sessions read the hooks after a restart.

- **`title`:** hooks only see a custom title, set with `--name`, `/rename` or a hook. Claude Code's generated titles do not reach hooks. A `/rename` shows at the next prompt.
- **`blocked`:** `PreToolUse` sets it for `AskUserQuestion`, and `PermissionRequest` sets it for every permission prompt. The next tool result, prompt or turn end clears it. An interrupt does not run `Stop`, so after an interrupt the label stays until the next prompt. Use Clear Blocked in the sidebar to remove it at once.
- `PostToolUse` runs the script after every tool call. Each run is one `jq` and one `hc label` call.

## Codex CLI

```sh
mkdir -p ~/.codex/hooks
cp docs/agents/codex/hc-labels.sh ~/.codex/hooks/
```

Then copy [`codex/hooks.json`](codex/hooks.json) to `~/.codex/hooks.json`, or merge it into the file you have. At the next start, Codex lists the new hooks under "Hooks need review". Trust them, or they do not run.

- **`title`:** Codex keeps thread names, from `/rename` or generated, in `$CODEX_HOME/session_index.jsonl`. The script reads the last name for the session at session start, at each prompt and at each turn end.
- **`blocked`:** only approval prompts (`PermissionRequest`). Questions that Codex asks with its `request_user_input` tool do not set it.

## OpenCode

```sh
mkdir -p ~/.config/opencode/plugins
cp docs/agents/opencode/hc-labels.ts ~/.config/opencode/plugins/
```

OpenCode loads the plugin at the next start.

- **One server, many sessions:** the plugin runs in the OpenCode server and sees the events of all its sessions. `resume` and `title` follow the last top-level session that was created, changed or selected in the TUI. Subagent sessions are ignored.
- **Separate server:** with `opencode serve` and `opencode attach`, the plugin runs in the server's process. It writes the labels on the terminal that runs `opencode serve`, not on the terminal of the TUI.
- **`blocked`:** `question.asked` and `permission.asked` set it. A reply, a rejection or `session.idle` clears it.
