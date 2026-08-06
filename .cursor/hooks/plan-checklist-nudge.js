#!/usr/bin/env node
// postToolUse hook: when the agent edits PLAN.md, inject a reminder that every
// step needs its own enumerated in-game checklist. No matcher is used (the tool
// name for edits varies); we filter in-script by tool name + path so the nudge
// only fires on an actual PLAN.md edit, not on reads/searches.
"use strict";

function readStdin() {
  try {
    return require("fs").readFileSync(0, "utf8");
  } catch (_e) {
    return "";
  }
}

let input = {};
try {
  input = JSON.parse(readStdin() || "{}");
} catch (_e) {
  input = {};
}

const toolName = String(input.tool_name || "");
const toolInput = JSON.stringify(input.tool_input || {});

const isEditTool = /^(Write|StrReplace|MultiEdit|Edit|EditNotebook)$/i.test(toolName);
const touchesPlan = /PLAN\.md/i.test(toolInput);

if (isEditTool && touchesPlan) {
  process.stdout.write(
    JSON.stringify({
      additional_context:
        "Stage-gating reminder (PLAN.md changed): every step needs its own enumerated in-game checklist (action -> exact on-screen result), validated in-game via ./gradlew runClient before the next step or commit. Keep a short docs-quality TODO on the plan: when correcting docs/comments, replace stale claims rather than padding them — new content may grow (AGENTS.md -> Documentation).",
    })
  );
  process.exit(0);
}

process.stdout.write("{}");
process.exit(0);
