/**
 * hc-labels for OpenCode: writes `resume`, `title` and `blocked` labels on the herdr-core
 * terminal that runs this OpenCode.
 *
 * - resume: `cd '<dir>' && opencode --session <id>` for the last root session that was created,
 *   updated or selected.
 * - title: the title of that session.
 * - blocked: the first question of an open question, or the permission that waits for approval.
 *
 * Outside an hc terminal (no HC_TERMINAL_ID) it does nothing.
 */

import { execFile } from "node:child_process";
import { accessSync, constants } from "node:fs";
import { homedir } from "node:os";
import { delimiter, join } from "node:path";

import type { Plugin } from "@opencode-ai/plugin";

const MAX_LENGTH = 120;

const quote = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;
const clean = (s: string) => s.replace(/\s+/g, " ").trim().slice(0, MAX_LENGTH);

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

export const HcLabels: Plugin = async ({ directory }) => {
  const terminal = process.env.HC_TERMINAL_ID;
  const hc = terminal ? findHc() : undefined;
  if (!terminal || !hc) return {};

  const titles = new Map<string, string>();
  const open = new Map<string, string>();
  let current = "";
  let written: Record<string, string> = {};

  const write = (next: Record<string, string>) => {
    const changed = Object.entries(next).filter(([k, v]) => written[k] !== v);
    if (changed.length === 0) return;
    written = { ...written, ...Object.fromEntries(changed) };
    execFile(hc, ["label", terminal, ...changed.map(([k, v]) => `${k}=${v}`)], () => {});
  };

  const writeSession = () =>
    write({
      resume: current ? `cd ${quote(directory)} && opencode --session ${current}` : "",
      title: titles.get(current) ?? "",
    });

  const writeBlocked = () => write({ blocked: [...open.values()].at(-1) ?? "" });

  return {
    event: async ({ event }) => {
      const p: any = event.properties;
      switch (event.type) {
        case "session.created":
        case "session.updated":
          if (!p.info?.id || p.info.parentID) return;
          titles.set(p.info.id, clean(p.info.title ?? ""));
          current = p.info.id;
          writeSession();
          return;
        case "tui.session.select":
          current = p.sessionID;
          writeSession();
          return;
        case "question.asked": {
          const q = p.questions?.[0];
          open.set(p.id, clean(q?.question ?? q?.header ?? "Waiting for an answer"));
          writeBlocked();
          return;
        }
        case "permission.asked":
          open.set(p.id, clean(`Allow ${p.permission}: ${(p.patterns ?? []).join(" ")}`));
          writeBlocked();
          return;
        case "question.replied":
        case "question.rejected":
        case "permission.replied":
          open.delete(p.requestID);
          writeBlocked();
          return;
        case "session.idle":
          open.clear();
          writeBlocked();
          return;
      }
    },
  };
};
