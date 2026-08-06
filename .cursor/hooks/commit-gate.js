#!/usr/bin/env node
// beforeShellExecution: matcher-selected `git commit` → always ask (human only).
// AGENTS.md stage-gating: in-game checklist before commit; agent must not self-approve.
"use strict";

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
