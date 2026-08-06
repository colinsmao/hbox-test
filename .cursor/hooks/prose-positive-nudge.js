#!/usr/bin/env node
// preToolUse: before an agent edits a .md file, remind positive-only prose.
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
  input = JSON.parse((readStdin() || "").replace(/^\uFEFF/, "").trim() || "{}");
} catch (_e) {
  input = {};
}

const toolName = String(input.tool_name || "");
const toolInputObj = input.tool_input || {};
const filePath = String(
  toolInputObj.file_path || toolInputObj.path || toolInputObj.target_notebook || ""
);

const isEditTool = /^(Write|StrReplace|MultiEdit|Edit|EditNotebook)$/i.test(toolName);
const touchesMd = /\.md$/i.test(filePath);

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
