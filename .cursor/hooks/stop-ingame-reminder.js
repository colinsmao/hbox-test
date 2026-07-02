#!/usr/bin/env node
// stop hook: when the agent tries to end its turn with uncommitted source/doc
// changes, auto-submit ONE follow-up reminding it not to treat a step as done
// (or commit it) until the in-game checklist has passed. It fires only when the
// tree is dirty with relevant files, so trivial Q&A turns are never nudged.
//
// Caveats (see AGENTS.md / Cursor hooks docs): `stop` does not run on cloud
// agents and has a known Windows stdout-capture bug; invoking node directly
// (not via a PowerShell wrapper) avoids the buffering cause. loop_limit=1 in
// hooks.json plus the loop_count guard below keep this to a single nudge.
"use strict";

function readStdin() {
  try {
    return require("fs").readFileSync(0, "utf8");
  } catch (_e) {
    return "";
  }
}

function done() {
  process.stdout.write("{}");
  process.exit(0);
}

let input = {};
try {
  input = JSON.parse(readStdin() || "{}");
} catch (_e) {
  input = {};
}

if (String(input.status || "") !== "completed") done();
if (Number(input.loop_count || 0) >= 1) done();

const projectDir = process.env.CURSOR_PROJECT_DIR || process.cwd();

let porcelain = "";
try {
  porcelain = require("child_process").execSync("git status --porcelain", {
    cwd: projectDir,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  });
} catch (_e) {
  done();
}

const relevant = porcelain
  .split(/\r?\n/)
  .map((line) => line.slice(3).trim())
  .filter(Boolean)
  // A rename shows as "old -> new"; key off the destination path.
  .map((p) => (p.includes(" -> ") ? p.split(" -> ")[1] : p))
  .filter((p) => !p.startsWith(".cursor/"))
  .filter((p) => /^src\//.test(p) || /^docs\//.test(p) || /\.(java|gradle|md)$/i.test(p));

if (relevant.length === 0) done();

process.stdout.write(
  JSON.stringify({
    followup_message:
      "Before finishing: there are uncommitted changes (" +
      relevant.slice(0, 8).join(", ") +
      (relevant.length > 8 ? ", ..." : "") +
      "). Per AGENTS.md stage-gating, a step is NOT done — and must NOT be committed — until its enumerated in-game checklist has been validated in-game via ./gradlew runClient (a green build/test is not sufficient). If the current step's in-game checklist has not been run, either run it (runClient) or surface the checklist to the user for validation before proceeding. If everything is already validated in-game, briefly confirm that and stop.",
  })
);
process.exit(0);
