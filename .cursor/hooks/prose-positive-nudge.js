#!/usr/bin/env node
// preToolUse: before an agent edits a .md file, remind positive-only prose
// (AGENTS.md -> Documentation). Filter in-script by tool + path.
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
const touchesMd = /\.md(["'\\s]|$)/i.test(toolInput) || /["'][^"']*\.md["']/i.test(toolInput);

if (isEditTool && touchesMd) {
  process.stdout.write(
    JSON.stringify({
      additional_context:
        "Prose reminder (editing .md): Write positively. Describe what something is / does, not what it doesn't or used to do.",
    })
  );
  process.exit(0);
}

process.stdout.write("{}");
process.exit(0);
