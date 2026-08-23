#!/usr/bin/env node
// subagentStart hook: only allow subagents that inherit the parent model, or
// explicitly use composer-2.5 / cursor-grok-4.6-high (AGENTS.md -> Subagents).
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

const subagentModel = String(input.subagent_model || "").trim();
const parentModel = String(input.model || "").trim();

const ALLOWED = new Set(["composer-2.5", "cursor-grok-4.6-high"]);

function deny(reason) {
  process.stdout.write(
    JSON.stringify({
      permission: "deny",
      user_message: reason,
    })
  );
  process.exit(0);
}

function allow() {
  process.stdout.write(JSON.stringify({ permission: "allow" }));
  process.exit(0);
}

function isFastSuffixed(model) {
  return String(model).endsWith("-fast");
}

if (isFastSuffixed(subagentModel)) {
  deny(
    "Subagent model gate (AGENTS.md -> Subagents): `-fast` models are not allowed for subagents. Omit `model` to inherit the parent, or use `composer-2.5` / `cursor-grok-4.6-high`."
  );
}

// Inherit parent when the Task call omits `model`.
if (!subagentModel) {
  allow();
}

if (parentModel && subagentModel === parentModel) {
  allow();
}

if (ALLOWED.has(subagentModel)) {
  allow();
}

deny(
  "Subagent model gate (AGENTS.md -> Subagents): `" +
    subagentModel +
    "` is not allowed. Omit `model` to inherit the parent, or use `composer-2.5` / `cursor-grok-4.6-high`."
);
