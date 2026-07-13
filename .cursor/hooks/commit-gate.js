#!/usr/bin/env node
// beforeShellExecution hook: force a manual approval on every `git commit`.
// HARD RULE: the agent must NEVER commit without explicit human approval. The #1
// repo rule (AGENTS.md -> Stage-gating) is that a step is not done, and must not be
// committed, until its enumerated in-game checklist has passed in `./gradlew
// runClient`. A hook can't verify a human ran the checklist, so it turns every
// commit into an unskippable "ask" that only a human may approve — the agent may
// not self-approve, retry to bypass, or treat a green build/test as approval.
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

const command = String(input.command || "");
const isCommit = /\bgit\b[\s\S]*\bcommit\b/.test(command);

if (!isCommit) {
  process.stdout.write(JSON.stringify({ permission: "allow" }));
  process.exit(0);
}

process.stdout.write(
  JSON.stringify({
    permission: "ask",
    user_message:
      "Commit gate (AGENTS.md #1 rule). Commits require YOUR explicit approval — the agent must never commit on its own. Before approving, confirm: (1) THIS step's enumerated in-game checklist passed via ./gradlew runClient — a green build/test is NOT sufficient; (2) this commit is ONE conceptually-distinct change whose docs are updated in the same commit.",
    agent_message:
      "Stage-gating hook intercepted `git commit`. NEVER commit without explicit human approval — do not self-approve, do not retry to bypass this gate, and do not treat a green build/test as approval. Only proceed if a human has approved AND the current step's in-game checklist has fully passed in-game (./gradlew runClient), and the commit is a single concept with its documentation updated in the same commit (AGENTS.md -> Stage-gating / Git workflow). Otherwise stop and hand the checklist to the user.",
  })
);
process.exit(0);
