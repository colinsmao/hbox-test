# AGENTS.md

**Rules and instructions for AI agents working in this repo.** This file is
deliberately lean — it is *rules*, not background. Keep it that way: project
facts (what the mod is, status/milestones, repo layout, versions, install,
roadmap) live in **[`docs/project.md`](docs/project.md)**; subsystem depth lives
in **[`docs/rendering.md`](docs/rendering.md)** and
**[`docs/geometry.md`](docs/geometry.md)** (read the relevant one before working
in that area); the *current short-term plan* lives in **[`PLAN.md`](PLAN.md)**
(transient, often empty between tasks — never durable knowledge).

> **The core workflow:** work proceeds one plan step at a time, and **each step is
> its own commit.** A step is not done until its enumerated in-game checklist has
> passed in-game (`./gradlew runClient`) — a green build is not enough — and only
> then is it committed. A `git commit` hook enforces the pause at each commit, so
> keeping steps small and committing each one is what makes the gate work per step.
> See **[Stage-gating and commit-per-step](#stage-gating-and-commit-per-step)**.

## Stage-gating and commit-per-step

The backbone of all work here: **a plan is a sequence of small steps, and each step
is validated in-game and committed before the next one begins.** Behavior is almost
entirely runtime/visual, so a green build proves little on its own; validating and
committing each step catches a regression at the step that introduced it, and the
per-commit hook (below) only becomes a per-step checkpoint if each step is its own
commit. This has historically been the most-skipped rule here, so treat it as the
default shape of every task.

**The rules:**

1. **Design the plan as committable steps.** Each step should be a self-contained,
   independently verifiable unit of work — the thing that becomes one commit. Prefer
   more small steps over a few large ones. If a step can't be validated and committed
   on its own, it's too big; split it.
2. **Every step has its own enumerated in-game checklist.** Not a summary, not
   "verify it works": a numbered list of concrete `action → exact expected on-screen
   result` items (e.g. "right-click a slab → its half-height top face is drawn, and
   *only* that block"), plus a regression line for anything the step could break.
   Writing the checklist is part of writing the plan — a step without one is
   incomplete, so don't present a plan as ready until every step carries a checklist.
3. **Validate in-game, then commit, before the next step.** Run the step's checklist
   in-game (`./gradlew runClient`) — you or the user tick every box — then commit that
   step. Do not start the next step until the current one is validated and committed.
   A green `./gradlew build` / `./gradlew test` proves nothing about what is displayed.
4. **"It's just a logical change" is not an exemption.** A purely internal refactor
   with provably zero behavioral effect *may* gate on unit tests alone, but almost
   every logical change alters what gets calculated and therefore what is drawn.
   Treat logical steps as visual steps by default; when in doubt, it needs an in-game
   checklist.

**The procedure, every step:**

1. **Write the step's enumerated checklist into `PLAN.md`** — `action → expected
   on-screen result`, plus a regression line.
2. **When the step's code builds, surface that checklist verbatim** and run it
   in-game (`runClient`) — or hand it to the user — and record each result. If you
   can't run `runClient` yourself, pause and hand off rather than proceeding.
3. **Commit the step once its checklist passes in-game**, then move to the next step
   (see **Git / workflow**). One validated step → one commit.

Every step's checklist also ends with the build gate (below) and inherits these
**cross-cutting checks** (necessary, not sufficient): `runClient` launches with no
log errors; no errors on world load/unload or window resize; the mod does nothing
on a dedicated server. The current full feature checklist lives in
[`docs/project.md`](docs/project.md).

**Enforcement hooks (`.cursor/hooks.json`).** Project [Cursor hooks](https://cursor.com/docs/hooks)
back these rules mechanically, so they aren't honor-system only — do not treat them
as a substitute for actually validating in-game, and keep them working if you touch
`.cursor/hooks/`:
- `commit-gate.js` (`beforeShellExecution`) turns every `git commit` into a manual
  **ask** carrying the checklist reminder — a hook can't verify you ran `runClient`,
  only force the pause, so the confirmation is on you.
- `plan-checklist-nudge.js` (`postToolUse`) reminds, on each `PLAN.md` edit, that
  every step needs its own enumerated in-game checklist.
- `stop-ingame-reminder.js` (`stop`) nudges once when a turn ends with uncommitted
  `src/`/`docs/` changes. Caveat: `stop` hooks don't run on cloud agents and have a
  known Windows stdout-capture quirk, so this one is best-effort — the commit gate is
  the reliable backstop.

## Build, test & run

```bash
./gradlew build        # compile + run unit tests + produce build/libs/*.jar (CI gate)
./gradlew test         # run the pure-logic unit tests only
./gradlew runClient    # launch a dev client with the mod loaded
```

- Use the Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows); do **not**
  assume a system Gradle is installed.
- **Unit-test gate:** `./gradlew test` must pass. Pure logic (rect/geometry ops,
  occluder-edge classification, the headroom predicate, profile values) is
  unit-tested under `src/test/java` with `fabric-loader-junit`. **New pure logic
  should land with a test here.**
- **Build gate:** `./gradlew build` must pass (compiles + runs `test` +
  `fabric.mod.json` schema processing). This only proves **logic + compile** —
  everything visual is runtime-only and gated in-game (see Stage-gating).
- **Mappings gotcha:** Minecraft `26.1.2` ships **non-obfuscated**, so Loom rejects
  an explicit `mappings` line — do **not** add `loom.officialMojangMappings()` (or
  any `mappings ...`) to `build.gradle`, or the build fails with "Cannot use Mojang
  mappings in a non-obfuscated environment".

## Shell environment (check it first)

The shell differs by host: the local dev machine runs **PowerShell on Windows**,
but a cloud agent runs **Linux/bash**. **Detect the shell before composing
commands** (e.g. `$PSVersionTable.PSVersion` succeeds only in PowerShell; `uname`
only in a POSIX shell) instead of assuming bash — most slips below come from
writing bash on PowerShell.

PowerShell gotchas that bite repeatedly (use the wrapper, not bash habits):

- **No heredocs.** `<<'EOF'` and `$(cat <<EOF …)` fail. For a multi-line commit
  message use repeated `-m` flags or `git commit -F <file>`; for a PR body use
  `gh pr create --body-file <file>`. Write the file with the editor tools.
- **`&&` / `||` chaining is unreliable** (PowerShell 7+ only, not 5.1). Prefer
  separate tool calls; use `;` only when you don't care whether an earlier command
  failed (it does **not** short-circuit on error like `&&`).
- **Quoting.** Double-quote paths with spaces; `<`, `>`, `|`, `&`, `@`, `$` are
  special outside quotes.

Prefer the specialized file tools over shell for reading/searching/editing — that
sidesteps most quoting issues; reserve the shell for real commands (`git`, `gh`,
`./gradlew`).

## Key constraints (all work)

These apply to **any** feature, so they live here rather than in a subsystem guide.

- **Client-only.** The mod must never be required on a server: keep
  `"environment": "client"` in `fabric.mod.json`, declare only a `client`
  entrypoint, and put client code in the Loom `client` source set
  (`splitEnvironmentSourceSets()`). Holds for *any* feature, incl. a future
  settings screen.
- **`26.1.2` ≠ `1.21.x`, and is a real release.** Since `1.21.11` Minecraft uses a
  year-based `YY.major.minor` scheme; `26.1.2` is current — do **not** "correct" it
  to the old scheme. Class/package/API names often differ from what you remember
  (e.g. `Identifier`, not `ResourceLocation`). **Do not trust training-data
  knowledge for this version** — it postdates most cutoffs and the rendering API
  churns hard. Verify names against the resolved jars and live docs (the compiler
  is the oracle, not memory). Authoritative sources are listed in
  [`docs/project.md`](docs/project.md).
- **No third-party rendering libraries** — a thin in-house abstraction is more
  stable than a dependency that must also chase the API churn (see
  [`docs/rendering.md`](docs/rendering.md)).
- **Surface/collision geometry stays in rect/double space, not a pixel raster**
  (a raster rewrite was prototyped and rejected — see
  [`docs/geometry.md`](docs/geometry.md)).
- **Mismatched JDK is the most common setup failure** — verify `java -version` is
  `25` before debugging build issues.

## Git / workflow

- **One commit per validated plan step — docs included.** Each plan step (see
  **Stage-gating and commit-per-step**) is its own commit; equivalently, one
  conceptually-distinct change per commit, never split by file or by a trailing
  "docs" pass. Each commit must be self-contained and **fully update its own
  documentation in that same commit** — the relevant `docs/*.md`, `docs/project.md`
  status, code comments, and `PLAN.md` — so no step ever lands with stale or deferred
  docs. Never lump distinct steps together, and never defer doc updates to the end.
  This cadence is also what makes the commit hook a per-step checkpoint: batching many
  steps into one commit defeats it.
- **Don't commit until the step is confirmed in-game.** A green `./gradlew build` is
  not sufficient — behavior here is runtime/visual. Make the edits, run the gates,
  then **wait until the step's in-game checklist passes** (user confirms, or you run
  `runClient` and verify) before committing that step. This applies to `PLAN.md` / doc
  updates too: don't commit a step's plan or doc change until that step is confirmed
  working. (The `git commit` hook in `.cursor/hooks.json` will pause each commit to
  reconfirm this.)
- **Sole-agent assumption.** Unless told otherwise, assume you are the only
  agent/person in this repo; no need to defensively re-check remote/branch state for
  concurrent changes before each action (a quick check when something looks off is
  enough). Amend/force-push on your own feature branch is low-risk when it makes
  history clearer — but **do not force-push or amend unless asked.**
- **Branch naming** for agent work: `cursor/<descriptive-name>-3c2f` (lowercase),
  branched off `main`.
- **Squash planning commits before dev.** If a task went through plan mode and
  produced multiple `Plan:` commits, squash them into one plan commit before
  starting implementation, so history is one plan commit followed by dev commits.
- After pushing, open/update a PR against `main`. Verify `./gradlew build` passes
  before considering a code change complete, and confirm docs are current (below)
  before opening/updating the PR.

## Documentation & conventions

- **Keep the docs current — it is part of the work, not an afterthought.** Whenever
  a change alters behavior, architecture, status, constraints, versions, or adds a
  non-obvious gotcha, update the relevant docs **in the same commit/PR** (see the
  per-concept commit rule above) so the next agent inherits accurate knowledge.
  Reviewing docs for staleness is a normal step before opening a PR.
- **Where knowledge goes (keep `AGENTS.md` lean):** place it by scope —
  - a project-wide *rule/instruction* that applies to any task → **`AGENTS.md`**;
  - a project *fact* (status, layout, versions, roadmap) → **`docs/project.md`**;
  - subsystem implementation detail → that subsystem's guide under **`docs/`**;
  - specific to one file/widget → a **code comment** next to the code.

  Do **not** put subsystem detail or project background into `AGENTS.md`; an agent
  working in an unrelated area shouldn't have to read it.
- **Log mid-task design changes into `PLAN.md` as they land.** When a decision or
  logical change emerges *in conversation* during a task (a new approach, an
  algorithm/rendering change, a reversed choice), write it into `PLAN.md`
  immediately — **including its step's in-game checklist** — rather than only at the
  end. `PLAN.md` is the accumulating scratch the end-of-task durable-doc update is
  distilled from, so knowledge survives an interrupted session and the final docs
  pass is a consolidation, not a recollection.
- **Comments explain intent, not narration.** Don't add comments that merely
  restate what the code does; explain non-obvious intent, trade-offs, or constraints.
- **Package base** is `com.example.overlay` (mod id `overlay`). If the user provides
  a real maven group / mod id / author, update the docs and code consistently.
