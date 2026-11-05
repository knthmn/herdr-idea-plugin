/**
 * hc-blocked: sets a `blocked` label on the herdr-core terminal that runs this pi while
 * rpiv-ask-user-question waits for an answer.
 *
 * Pi reports `blocked` through OSC 7501 only for its own dialogs. The question tool draws its
 * own UI (`ctx.ui.custom`), so pi keeps reporting `working`. The label carries the first
 * question, and it is cleared when the tool ends. Outside an hc terminal it does nothing.
 */

import { execFile } from "node:child_process";
import { accessSync, constants } from "node:fs";
import { homedir } from "node:os";
import { delimiter, join } from "node:path";

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const TOOLS = new Set(["ask_user_question"]);
const MAX_LENGTH = 120;

function findHc(): string | undefined {
  const dirs = (process.env.PATH ?? "").split(delimiter).filter(Boolean);
  for (const path of [...dirs.map((d) => join(d, "hc")), join(homedir(), ".local/bin/hc")]) {
    try {
      accessSync(path, constants.X_OK);
      return path;
    } catch {}
  }
  return undefined;
}

function question(args: any): string {
  const first = Array.isArray(args?.questions) ? args.questions[0] : undefined;
  const text = String(first?.question ?? first?.header ?? "Waiting for an answer");
  return text.replace(/\s+/g, " ").trim().slice(0, MAX_LENGTH);
}

export default function (pi: ExtensionAPI) {
  const terminal = process.env.HC_TERMINAL_ID;
  if (!terminal) return;
  const hc = findHc();
  if (!hc) return;

  const open = new Set<string>();
  let written = "";

  const write = (value: string) => {
    if (value === written) return;
    written = value;
    execFile(hc, ["label", terminal, `blocked=${value}`], () => {});
  };

  pi.on("tool_execution_start", async (e) => {
    if (!TOOLS.has(e.toolName)) return;
    open.add(e.toolCallId);
    write(question(e.args));
  });
  pi.on("tool_execution_end", async (e) => {
    if (!open.delete(e.toolCallId)) return;
    if (open.size === 0) write("");
  });
  const clear = async () => {
    open.clear();
    write("");
  };
  pi.on("agent_end", clear);
  pi.on("session_start", async () => {
    written = "-";
    await clear();
  });
  pi.on("session_shutdown", clear);
}
