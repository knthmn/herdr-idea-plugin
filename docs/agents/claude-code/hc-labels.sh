#!/usr/bin/env bash
# hc-labels for Claude Code: writes `resume`, `title` and `blocked` labels on the herdr-core
# terminal that runs this Claude Code. Register it for the hook events in settings.json.
#
# - resume: `cd '<cwd>' && claude --resume <session id>`.
# - title: the custom session title (--name, /rename), when one is set.
# - blocked: the first question of an open AskUserQuestion, or the tool that waits for approval.
#
# Needs jq. Outside an hc terminal (no HC_TERMINAL_ID) it does nothing.

[[ -n $HC_TERMINAL_ID ]] || exit 0
hc=$(command -v hc || echo "$HOME/.local/bin/hc")
[[ -x $hc ]] || exit 0

labels=()
while IFS= read -r line; do labels+=("$line"); done < <(jq -r '
  def clean: gsub("\\s+"; " ") | ltrimstr(" ") | .[0:120];
  def title: if (.session_title // "") != "" then ["title=\(.session_title | clean)"] else [] end;
  def question: .tool_input.questions[0] | (.question // .header // "Waiting for an answer");
  def approval: "Allow \(.tool_name)"
    + ((.tool_input.command // .tool_input.file_path // "") | if . == "" then "" else ": \(.)" end);
  def ask: if .tool_name == "AskUserQuestion" then question else approval end | clean;
  (
    if .hook_event_name == "SessionStart" then
      ["resume=cd \(.cwd | @sh) && claude --resume \(.session_id)", "blocked="] + title
    elif .hook_event_name == "UserPromptSubmit" then ["blocked="] + title
    elif .hook_event_name == "PreToolUse" then
      if .tool_name == "AskUserQuestion" then ["blocked=\(ask)"] else [] end
    elif .hook_event_name == "PermissionRequest" then ["blocked=\(ask)"]
    else ["blocked="]
    end
  )[]
')

((${#labels[@]})) && "$hc" label "$HC_TERMINAL_ID" "${labels[@]}" >/dev/null 2>&1
exit 0
