---
name: Auto-update flood (M11 steps 5+)
overview: 'Periodically re-run the standable-surface flood without a manual click: a Generic auto-update mode (Disabled / From Seed / From Feet) plus a seconds interval, driven from the client tick. From Feet self-starts and follows the player whenever the selection would be drawn; updates are spaced as a cooldown after each flood completes. Reuses the existing latest-wins, chunked arm/advance/publish machinery, so the new work is a tick trigger plus two settings — small enough to ride on Milestone 11 as steps 5+ rather than a new milestone.'
todos:
  - id: step5-settings
    content: 'Step 5 — settings scaffolding. Add an AutoUpdate enum (Disabled/FromSeed/FromFeet) as a Generic ConfigOptionList (default Disabled, like ShowSurfaces) plus a seconds ConfigDouble autoUpdateInterval (default 0.5, min 0.05, max 5.0, slider); add both to Generic.OPTIONS and FILE_OPTIONS with .apply(GENERIC_KEY); Configs.autoUpdate()/autoUpdateIntervalSeconds() helpers; lang comment.* for both rows and the three autoUpdate.* cycle labels. No behavior yet. In-game checklist: (1) ModMenu -> Configure -> General shows an auto update row cycling Disabled -> From Seed -> From Feet with readable labels; (2) an auto update interval slider reads in seconds (default 0.5) and adjusts; (3) set both non-default, close then reopen the screen -> values retained; (4) set both, Save and Quit to Title, rejoin -> values retained (written on disconnect); (5) hover each new row -> its tooltip shows; regression: existing General options still cycle/persist, ./gradlew build passes.'
    status: pending
  - id: step6-driver-fromseed
    content: 'Step 6 — tick driver + From Seed. Add a default WorldOverlay.onClientTick(Minecraft) hook called from WorldOverlayManager.onClientTick; add SurfaceSelection.isFlooding() (cheaper than progress(), which allocates); implement the inline cooldown timer in CollisionSurfaceOverlay (idleTicks counts only while not flooding; when idleTicks >= max(1, round(seconds*20)) arm from the mode seed and reset); From Seed re-floods lastSeed; a manual wand click resets idleTicks. No unit test (thin branchy policy glue; observable cadence/gating is runtime and covered below). In-game checklist: (1) right-click ground to select, set auto update = From Seed interval 0.5s, break/place blocks inside the selection -> within ~0.5s it auto-updates with no re-click; (2) set Disabled -> edits stop refreshing until a re-click; (3) raise interval to 3s -> updates space out to ~3s; (4) From Seed with no prior click (nothing selected) -> nothing floods; (5) low budget + radius 30 (a multi-tick flood) -> never re-armed mid-flight, each update starts only after the previous finishes; (6) a manual re-click does not trigger an immediate second refresh; regression: ./gradlew build passes, no log errors on world load/unload.'
    status: pending
  - id: step7-fromfeet
    content: 'Step 7 — From Feet. Seed via resolveDownward(player feet block); self-start gated by the isVisible-style rule (showSurfaces ALWAYS runs regardless of the wand; WHILE_HOLDING_WAND runs only while the wand is held; NEVER never runs) plus hasEnabledProfile(); re-flood every interval (stationary included, so world edits under a still player are picked up); set lastSeed to the feet block so /mobwalk dump and radius/profile re-floods stay consistent. Verify the 26.2 player feet BlockPos accessor against the resolved jars. In-game checklist: (1) auto update = From Feet, showSurfaces = While Holding Wand, hold the wand and do not click -> within one interval a flood appears at your feet; (2) walk -> the selection re-centers on you each ~interval; (3) stand still and edit the ground under you -> within ~interval it updates (stationary still refreshes); (4) put the wand away -> updates stop, last selection stays drawn; (5) showSurfaces = Always, wand away, walk -> keeps following empty-handed; (6) showSurfaces = Never -> nothing computes; (7) stand over a hole/ledge/void edge -> seed resolves to the block below (or nothing over void), no crash; (8) switch From Feet -> From Seed with a selection active -> next update uses the last feet block; regression: ./gradlew build passes, no log errors on world load/unload or while walking.'
    status: pending
  - id: step8-polish
    content: 'Step 8 (optional) — polish. Progress-ring interplay under frequent auto-updates (avoid a distracting strobe when From Feet re-floods often at a small budget); any README wand-control note. In-game checklist: (1) From Feet at a small interval + low budget while walking -> the crosshair progress ring behaves acceptably (no distracting strobe); with showFloodProgress off it never shows; regression: ./gradlew build passes, docs updated.'
    status: pending
  - id: docs-quality
    content: 'Tracked docs-quality item (per AGENTS): keep docs current in the same commit as each step — settings.md Generic table + helpers, project.md status/milestone line, changelog.txt, and a README wand-control note if the feature adds one. Write prose as positive facts and replace stale sentences rather than padding; land docs after the step checks pass.'
    status: pending
isProject: false
---
# Auto-update flood — Milestone 11, steps 5+

Periodically re-run the flood without a manual click. Milestone 11 steps 1–4 made the
flood chunked, latest-wins, and frame-driven; this rides on that machinery
(`armFlood → cache.select(...)` → `extract/advanceFlood()` → `publish()`), so the
only new work is a **tick-driven trigger** plus two settings. It is small enough to
extend Milestone 11 rather than open a new milestone.

## 1. What it adds

Two Generic options and a per-tick timer that re-arms the existing flood:

- **`autoUpdate` mode** (`ConfigOptionList`, default **Disabled**):
  - **Disabled** — flood only on a wand action (today's behavior).
  - **From Seed** — re-flood the last manually-clicked seed (`lastSeed`) every
    interval. Requires a prior click. Use case: watch a changing area from a fixed
    point.
  - **From Feet** — **self-starts** (no click): seed is
    `resolveDownward(player feet block)`. Follows the player and re-floods every
    interval whether or not you moved, so a stationary player still picks up world
    edits.
- **`autoUpdateInterval`** (`ConfigDouble`, seconds, default `0.5`, min `0.05`,
  max `5.0`, slider). The driver converts to ticks: `intervalTicks = max(1,
  round(seconds * 20))`. Tick-based, so it pauses with the game.

## 2. Anchor — cooldown after completion

A per-tick `idleTicks` counter **only accumulates while no flood is in flight**; when
it reaches `intervalTicks` the next flood is armed and the counter resets. This gives
no overlap and no starvation at a low budget: the effective period is
`flood time + interval`. Both modes re-flood every interval; the only per-mode
difference is the seed source (`lastSeed` vs current feet). Because the previous
selection stays drawn until the atomic swap, re-flooding a stationary view never
flickers — the only cost is a bounded recompute.

## 3. Gating — run only when the result would be drawn

Auto-update fires only when the selection would currently paint, matching the
`isVisible()` logic so no invisible floods are computed:

- `showSurfaces == ALWAYS` → runs regardless of the wand (**follows you empty-handed**).
- `showSurfaces == WHILE_HOLDING_WAND` → runs only while the wand is held (either hand).
- `showSurfaces == NEVER` → never runs.

Plus `Configs.hasEnabledProfile()`. A manual wand click resets `idleTicks` so it does
not immediately re-fire.

## 4. Where it hooks in

- The only per-**tick** hook today is
  [`WorldOverlayManager.onClientTick`](src/client/java/dev/kelianmao/mobwalk/client/overlay/WorldOverlayManager.java)
  (`END_CLIENT_TICK`), which edge-detects the use key. Add a
  `default void onClientTick(Minecraft)` to
  [`WorldOverlay`](src/client/java/dev/kelianmao/mobwalk/client/overlay/WorldOverlay.java),
  call it in the manager loop, and implement the timer in
  [`CollisionSurfaceOverlay`](src/client/java/dev/kelianmao/mobwalk/client/surface/CollisionSurfaceOverlay.java).
  The interval is in ticks, so it must be tick-driven, not frame-driven `extract`.
- Reuse `armFlood(level, seed, profile)`, `resolveDownward`, the holding-wand check,
  and `lastSeed`. Add a small `SurfaceSelection.isFlooding()` (cheaper than
  `progress()`, which allocates) for the "flood in flight" test.
- Settings follow the [settings.md](docs/settings.md) recipe: an enum +
  `ConfigOptionList` (mirroring `ShowSurfaces`), a `ConfigDouble`, both on
  `Generic.OPTIONS` and `Generic.FILE_OPTIONS` with `.apply(GENERIC_KEY)`,
  `comment.*` / cycle-label lang keys, and `Configs.autoUpdate()` /
  `Configs.autoUpdateIntervalSeconds()` helpers. No `setValueChangeCallback` — the
  driver reads both live each tick.

## 5. The tick policy (inline, no unit test)

`CollisionSurfaceOverlay.onClientTick` holds an `int idleTicks` and each tick:

1. Compute `showable` (the `isVisible`-style gate) and require `hasEnabledProfile()`;
   otherwise reset `idleTicks = 0` and return.
2. If `cache.isFlooding()`, reset `idleTicks = 0` and return (cooldown counts only
   while idle).
3. Resolve the mode seed (From Seed → `lastSeed`, may be null; From Feet →
   `resolveDownward(feet)`, may be null over void). No seed → reset and return.
4. `idleTicks++`; when `idleTicks >= max(1, round(seconds * 20))`, `armFlood(seed)`,
   set `lastSeed = seed`, reset `idleTicks = 0`.

This is small branchy glue, not an algorithm; a `decide()` unit test would only
restate these branches (the tautological-test kind the repo removed in `42ee61f`).
Its observable behavior — cadence, gating, follow-the-player — is runtime/visual and
is exactly what the per-step in-game checklists verify, so it lands **without** a
unit test.

## 6. Steps (each its own commit + in-game checklist)

Per [AGENTS.md](AGENTS.md) stage-gating, each step is validated in-game before commit,
with descriptive commit subjects (continuing Milestone 11 as steps 5+). Full
checklists live in the tracked TODO list above.

5. **Settings scaffolding** — the `AutoUpdate` enum + option, the seconds interval
   option, helpers, lang. No behavior yet.
6. **Driver + From Seed** — `WorldOverlay.onClientTick` hook, `isFlooding()`, the
   inline cooldown timer, From Seed re-flood; manual click resets the timer.
7. **From Feet** — feet-seed resolution, self-start under the `isVisible`-style gate,
   re-flood every interval (stationary included).
8. **Polish (optional)** — progress-ring interplay under frequent updates and any
   final docs/README control note.

## 7. Risks / notes

- **Re-entrancy.** `onClientTick` (arm) and `extract/advanceFlood` (advance) run in
  different frame phases of the same client thread, exactly like today's `onUseItem`;
  latest-wins `select()` swaps the job reference, so no new locking is needed.
- **Feet API.** Verify the player feet `BlockPos` accessor against the resolved
  `26.2` jars (`blockPosition()` / `getOnPos()`), per the AGENTS "don't trust
  training data for 26.2" rule.
- **Wasted work.** The "run only when drawn" gate keeps floods to when `showSurfaces`
  would paint them; the cooldown + budget bound how often and how fast a recompute runs.
- **First update latency.** The cooldown model means the first From Feet flood appears
  after one interval (e.g. ~0.5s) once eligible (wand equipped, or Always mode) —
  consistent, and acceptable for v1.

## Ideas / backlog

- Probably out of scope:
  - ladders/vines
  - soul sand through 0.5 blocks
  - non-collision hazards (eg berry bushes)
  - fall damage
- Definitely out of scope:
  - horizontal velocity when jumping (ie parkour)
  - pathfinding
