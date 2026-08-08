#!/usr/bin/env node
// preToolUse: before plan edits, inject a checklist / anti-churn reminder.
// Soft allow + additional_context (surfaces as system_reminder). Strip BOM;
// match file_path only (not contents — avoids false positives on this script).
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
const touchesTempPlan = /\.plan\.md$/i.test(filePath);
const touchesRepoPlan = /(^|[\\/])PLAN\.md$/i.test(filePath);

if (isEditTool && (touchesRepoPlan || touchesTempPlan)) {
  let reminder =
    "Stage-gating reminder: every step needs its own enumerated in-game checklist (action -> exact on-screen result). Put docs-quality on the tracked TODO list.";
  if (touchesRepoPlan) {
    reminder += " Avoid PLAN.md churn — significant design shifts only, or a short note.";
  }
  process.stdout.write(JSON.stringify({ additional_context: reminder }));
  process.exit(0);
}

process.stdout.write("{}");
process.exit(0);
