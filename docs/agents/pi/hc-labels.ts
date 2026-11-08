/**
 * hc-labels: writes `resume` and `title` labels on the herdr-core terminal that runs this pi.
 *
 * - resume: a shell command that reopens this session, `pi --session '<file>'`.
 * - title: the session name.
 *
 * Other hc clients (for example the Herdr IntelliJ plugin) read them. Labels stay after pi
 * ends, so a client can resume the session when the terminal comes back as a shell or finished.
 * Outside an hc terminal (no HC_TERMINAL_ID) it does nothing.
 */

import { execFile } from "node:child_process";
import { accessSync, constants } from "node:fs";
import { homedir } from "node:os";
import { delimiter, join } from "node:path";

import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";

const quote = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;

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

export default function (pi: ExtensionAPI) {
  const terminal = process.env.HC_TERMINAL_ID;
  if (!terminal) return;
  const hc = findHc();
  if (!hc) return;

  let written: Record<string, string> = {};

  const write = (ctx: ExtensionContext) => {
    const file = ctx.sessionManager.getSessionFile();
    const next = {
      resume: file ? `pi --session ${quote(file)}` : "",
      title: pi.getSessionName() ?? "",
    };
    const changed = Object.entries(next).filter(([k, v]) => written[k] !== v);
    if (changed.length === 0) return;
    written = { ...written, ...Object.fromEntries(changed) };
    execFile(hc, ["label", terminal, ...changed.map(([k, v]) => `${k}=${v}`)], () => {});
  };

  pi.on("session_start", async (_e, ctx) => write(ctx));
  pi.on("session_info_changed", async (_e, ctx) => write(ctx));
}
