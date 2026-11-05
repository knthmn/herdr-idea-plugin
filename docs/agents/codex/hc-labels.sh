#!/usr/bin/env bash
# hc-labels for Codex CLI: writes `resume`, `title` and `blocked` labels on the herdr-core
# terminal that runs this Codex. Register it for the hook events in ~/.codex/hooks.json.
#
# - resume: `cd '<cwd>' && codex resume <session id>`.
# - title: the thread name from $CODEX_HOME/session_index.jsonl, when Codex has written one.
# - blocked: the tool call that waits for approval.
#
# Needs jq. Outside an hc terminal (no HC_TERMINAL_ID) it does nothing.

[[ -n $HC_TERMINAL_ID ]] || exit 0
hc=$(command -v hc || echo "$HOME/.local/bin/hc")
[[ -x $hc ]] || exit 0

input=$(cat)
id=$(jq -r '.session_id // empty' <<<"$input")
index="${CODEX_HOME:-$HOME/.codex}/session_index.jsonl"
name=""
if [[ -n $id && -f $index ]]; then
  name=$(jq -r --arg id "$id" 'select(.id == $id) | .thread_name // empty | gsub("\\s+"; " ")' "$index" 2>/dev/null | tail -n 1)
fi

labels=()
while IFS= read -r line; do labels+=("$line"); done < <(jq -r --arg name "$name" '
  def clean: gsub("\\s+"; " ") | ltrimstr(" ") | .[0:120];
  def title: if $name != "" then ["title=\($name | clean)"] else [] end;
  def approval: "Allow \(.tool_name)"
    + ((.tool_input.command // "") | if type == "array" then join(" ") else . end
       | if . == "" then "" else ": \(.)" end);
  (
    if .hook_event_name == "SessionStart" then
      ["resume=cd \(.cwd | @sh) && codex resume \(.session_id)", "blocked="] + title
    elif .hook_event_name == "PermissionRequest" then ["blocked=\(approval | clean)"]
    elif .hook_event_name == "UserPromptSubmit" or .hook_event_name == "Stop" then ["blocked="] + title
    else ["blocked="]
    end
  )[]
' <<<"$input")

((${#labels[@]})) && "$hc" label "$HC_TERMINAL_ID" "${labels[@]}" >/dev/null 2>&1
exit 0
