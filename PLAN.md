# PLAN — MobWalk rebrand (Milestone 5.5) + settings (Milestone 6)

Executable plan for a follow-up agent. Two milestones, each its own branch/PR:
the **rebrand** ships first (a package move touches every file, so it stays out of
the settings diff), then the **settings** milestone builds on top.

Per [`AGENTS.md`](AGENTS.md): each step below is one commit, validated in-game via
`./gradlew runClient` before the next, with its docs updated **in the same commit**.
`./gradlew build` (compile + `test`) must pass before every commit. Every step also
inherits the cross-cutting checks: `runClient` launches with no log errors; no errors
on world load/unload or window resize; the mod does nothing on a dedicated server.

## Branching / PRs

- **Milestone 5.5 — rebrand** (Step 1): branch `cursor/rebrand-mobwalk-31f3`;
  PR "Milestone 5.5: rebrand to MobWalk (dev.kelianmao.mobwalk)";
  commit `Milestone 5.5: rebrand com.example.overlay → dev.kelianmao.mobwalk`.
  Merge before starting settings.
- **Milestone 6 — settings** (Steps 2–7): branch `cursor/mobwalk-settings-31f3`;
  PR "Milestone 6: settings (config, hitbox library, ModMenu/Cloth screen)";
  one commit per step.

## Identity

| Field | Value |
| ----- | ----- |
| maven group | `dev.kelianmao` |
| mod id | `mobwalk` |
| package base | `dev.kelianmao.mobwalk` |
| display name | MobWalk |
| jar / archives base | `mobwalk` (`mobwalk-1.0.0.jar`) |
| config file | `config/mobwalk.json` |

Name is free on Modrinth and CurseForge as of planning; claim the public slug at
publish time (distribution is out of scope here). The two **mod-identity** classes
are renamed to the product: `OverlayMod` → `MobWalk` (shared mod id/logger) and
`OverlayClient` → `MobWalkClient` (client entrypoint). The **overlay-framework**
class names (`OverlayManager` / `WorldOverlayManager` and the `*Overlay` / `*Span`
types) are **kept** — they name the framework, not the product — so the rebrand is
a package/id/metadata move plus those two identity renames, not a class-rename churn.

---

# Milestone 5.5 — Rebrand

## Step 1 — Rebrand to MobWalk

**Scope:** replace the `com.example` / `overlay` / "example" placeholders with the
MobWalk identity. Pure rename, zero behavior change.

- `gradle.properties`: `maven_group=dev.kelianmao`, `archives_base_name=mobwalk`.
- `settings.gradle`: `rootProject.name` if set.
- Package move `com.example.overlay` → `dev.kelianmao.mobwalk` across `src/main`,
  `src/client`, `src/test` (directories + `package`/`import` statements);
  overlay-framework class names unchanged.
- Rename the two mod-identity classes (files + declarations + all references):
  `OverlayMod` → `MobWalk`, `OverlayClient` → `MobWalkClient`.
- `MobWalk`: `MOD_ID = "mobwalk"`, logger name.
- Keymapping translation key `key.overlay.occluder_style` (the K key) →
  `key.mobwalk.occluder_style` (string rename only; K behaviour unchanged).
- `fabric.mod.json`: `id` `mobwalk`, `name` "MobWalk", `description`, `authors`,
  `contact`, client entrypoint FQCN `dev.kelianmao.mobwalk.client.MobWalkClient`.
- Docs in commit: `AGENTS.md` "**Package base** is …" line → `dev.kelianmao.mobwalk`
  (mod id `mobwalk`); `docs/project.md` repo-layout paths and jar name; any other
  `com.example.overlay` references in `docs/*`.

**Unit tests:** existing suite must still pass (compile + package refactor only).

**In-game checklist:**
1. `./gradlew build` and `./gradlew test` → pass.
2. `runClient` → mod loads under id `mobwalk` / name "MobWalk" (visible in the mod
   list); no errors.
3. Regression: HUD readout, stick selection, radius scroll, K cycle, and profile
   cycle all work exactly as before.

---

# Milestone 6 — Settings

The headline feature is a **user-editable hitbox/profile library** (built-in vanilla
presets + custom hitboxes); the rest is a master toggle, flood defaults, keybinds,
and one appearance knob. All settings ship together, delivered over Steps 2–7.

## Stack

```
Pause menu → [Mods] (ModMenu) → [Configure] → Cloth screen → OverlayConfig → Gson → config/mobwalk.json
```

- **ModMenu** = entry point (the Configure button). **Cloth Config** = screen
  builder. **`OverlayConfig` + Gson** = persistence (Cloth's own serializer is not
  used). Distinct layers, not stacked GUIs.
- This repo uses the non-remapping `net.fabricmc.fabric-loom` (official 26.1
  mappings), so mod dependencies use plain **`implementation` / `compileOnly`**
  (not `modImplementation` / `modCompileOnly`), matching `build.gradle`'s existing
  fabric-api line.
- All new classes live in the **client** source set (`splitEnvironmentSourceSets`);
  the mod stays `"environment": "client"`.

### Dependencies

- Cloth Config: `me.shedaniel.cloth:cloth-config-fabric:26.1.154` — repo
  `https://maven.shedaniel.me/` — `implementation`; `depends.cloth-config` in
  `fabric.mod.json`.
- ModMenu: `com.terraformersmc:modmenu:18.0.0-alpha.8` — repo
  `https://maven.terraformersmc.com/releases/` — `compileOnly` + a dev-runtime
  declaration (`localRuntime`/`runtimeOnly`) so `runClient` shows the Configure
  button; `suggests.modmenu` in `fabric.mod.json`.
- Gson: already on the Minecraft classpath.

## `config/mobwalk.json`

```json
{
  "enabled": true,
  "profiles": [
    {"name": "Point",   "width": 0.0,  "height": 0.0,  "reach": 1.0, "builtin": true,  "enabled": true},
    {"name": "Player",  "width": 0.6,  "height": 1.8,  "reach": 1.0, "builtin": true,  "enabled": true},
    {"name": "Ravager", "width": 1.95, "height": 2.2,  "reach": 1.0, "builtin": true,  "enabled": true},
    {"name": "Warden",  "width": 0.9,  "height": 2.9,  "reach": 1.0, "builtin": true,  "enabled": true},
    {"name": "Zombie",  "width": 0.6,  "height": 1.95, "reach": 1.0, "builtin": true,  "enabled": true}
  ],
  "activeProfile": "Player",
  "defaultRadius": 3,
  "requireSneakForRadiusScroll": true,
  "showHoleBeams": true
}
```

- Unknown/missing keys tolerated on load (defaults filled, then re-saved).
- `reach` is `EntityProfile.reach`; GUI label "Reach", tooltip *max(jump, step) —
  height difference two surfaces can bridge*. Humanoids are `1.0` (Player step is
  0.6 but jump reach is 1.0), so this matches current behaviour.
- No `occluderStyle` key — occluder marker style stays K-driven/session-only this
  milestone; its final look is a future graphics-milestone decision.
- `activeProfile` is the **live** value: runtime cycling updates it and saves
  (survives restart). Default active profile is **Player** (was Point — an
  intentional behaviour change).

## Hitbox / profile library

One unified `profiles` list. A built-in and a custom hitbox are the same data
`{name, width, height, reach}`; `builtin` only decides delete-vs-reset. `enabled`
controls cycle membership (the cycle keybind and sneak+right-click iterate enabled
entries only); `delete` removes custom entries entirely. **Point is protected**
(non-deletable / auto-restored) — `W=0, H=0` is the pure point-walker and the
eager-vs-lazy correctness oracle ([`docs/geometry.md`](docs/geometry.md)); at least
one enabled profile must always exist.

Seeded on first run: Point, Player, Ravager, Warden, Zombie (dimensions from the
[Minecraft Wiki](https://minecraft.wiki/w/Hitbox/Entities%27_hitboxes), all
`reach = 1.0`). "Add from vanilla" offers those plus other humanoids (Skeleton,
Enderman, Iron Golem, Pillager, …), auto-filling a row. **Spider (wall climb) and
Slime (horizontal hop) are excluded** — they break the walk/flood model.

Validation (also the only perf guardrails — assume a reasonable user otherwise):
`width` 0–4, `height` 0–8, `reach ≥ 0`, radius 0–20; unique non-empty names;
`activeProfile` resolves to an enabled entry else first-enabled else Point.

## Settings inventory

- **General:** enable overlay (master toggle); active profile (from enabled entries).
- **Hitboxes:** the editable library (Step 7).
- **Flood:** default radius (0–20 slider); require-sneak-for-radius-scroll toggle.
- **Appearance:** show hole beams (only knob; grows in graphics polish).
- **Keybinds** (vanilla Controls, not JSON): toggle overlay; cycle profile.

## Step 2 — Dependencies and scaffold

**Scope:** wire ModMenu + Cloth Config into the build; no mod behaviour yet.

- `gradle.properties`: add `modmenu_version=18.0.0-alpha.8`,
  `cloth_config_version=26.1.154`.
- `build.gradle`: add both mavens to `repositories`; add the Cloth `implementation`
  and ModMenu `compileOnly` + dev-runtime declarations (confirm the dev-runtime
  config works with this loom).
- `fabric.mod.json`: `depends.cloth-config: "*"`, `suggests.modmenu: "*"`.
- Docs in commit: `docs/project.md` dependency table + versions.

**Unit tests:** none (build wiring).

**In-game checklist:**
1. `./gradlew build` → BUILD SUCCESSFUL.
2. `runClient` → launches, no new log errors.
3. Pause → Mods → list shows ModMenu present and the MobWalk entry.
4. Regression: stick selection, radius scroll, K cycle, profile cycle unchanged.

## Step 3 — Config core (pure logic + tests)

**Scope:** the data layer. No GUI yet; behaviour becomes config-driven with the
defaults above (default profile now Player).

- New `OverlayConfig` (client source set): fields per the JSON; `load()`/`save()`
  via Gson at `FabricLoader.getInstance().getConfigDir().resolve("mobwalk.json")`;
  seed defaults + save when absent; tolerate unknown keys; validation clamps above.
- `EntityProfile`: keep the record `(name, width, height, reach)`; keep a single
  `POINT` constant (oracle/fallback); remove `PLAYER`/`RAVAGER` statics and `CYCLE`.
- New `ProfileRegistry` (or methods on `OverlayConfig`): enabled profiles,
  `active()`, `cycleNext()` (over enabled, wraps), preset table (name → dims).
- `MobWalkClient`: `OverlayConfig.load()` early in `onInitializeClient`.
- `CollisionSurfaceOverlay`: initial `profile`/`selectionRadius` from config;
  sneak+right-click cycle uses the registry (over enabled) and **persists** the
  new active profile.
- Docs in commit: `docs/geometry.md` "Entity profiles" section (config-driven,
  seeded set, reach = max(jump, step)); `docs/project.md` status.

**Unit tests:**
- Rewrite `EntityProfileTest`: registry cycle over enabled wraps; disabled entries
  skipped; single-enabled cycles to itself.
- New `OverlayConfigTest`: Gson round-trip; missing-file seeds defaults;
  unknown-key tolerance; validation clamps (out-of-range width / name collision /
  bad activeProfile); preset values (Player 0.6/1.8/1.0, Ravager 1.95/2.2/1.0,
  Warden 0.9/2.9/1.0, Zombie 0.6/1.95/1.0); Point always present/protected.

**In-game checklist:**
1. Delete any `config/mobwalk.json`; launch → file created with seeded defaults.
2. Hold stick, right-click a block → surfaces use the **Player** hitbox by default.
3. Sneak+right-click air repeatedly → active profile cycles through enabled entries
   (Player→Ravager→Warden→Zombie→Point→…), HUD shows each name.
4. Quit; hand-edit `defaultRadius` to 6 and `activeProfile` to `Ravager`; relaunch
   → first selection uses radius 6 and Ravager.
5. Set an enabled entry to `false` in JSON, relaunch → cycle skips it.
6. Regression: Point selection matches pre-change Point behaviour; K cycle still
   works; no errors on world reload.

## Step 4 — Consume: master enable + show-hole-beams

**Scope:** two toggles read from config.

- `CollisionSurfaceOverlay.isVisible()`: also require `config.enabled`.
- Hole-beam emission (`emitHoles`) gated on `config.showHoleBeams`.
- Docs in commit: `docs/rendering.md` (hole-beam toggle); `docs/project.md` status.

**Unit tests:** none new (render-gated booleans); covered in-game.

**In-game checklist:**
1. `enabled=false`, relaunch, right-click with stick → no overlay drawn.
2. `enabled=true` → overlay returns.
3. `showHoleBeams=false` → a drop over the void draws its down-skirt but no red beam.
4. `showHoleBeams=true` → beam returns.
5. Regression: everything else (incl. K) unchanged; no errors.

## Step 5 — Keybinds + sneak-scroll gate

**Scope:** discoverable rebindable keys; optional sneak requirement for radius scroll.

- `MobWalkClient`: register `key.mobwalk.toggle_overlay` and
  `key.mobwalk.cycle_profile` (default unbound, `KeyMapping.Category.MISC`); toggle
  flips `config.enabled` (+save); cycle calls registry `cycleNext()` (+save), HUD
  shows name.
- `CollisionSurfaceOverlay.wantsRadiusScroll()`: when
  `requireSneakForRadiusScroll=false`, allow radius scroll while holding the stick
  without sneaking.
- `assets/mobwalk/lang/en_us.json`: add key names (first lang file in repo),
  including the K key's `key.mobwalk.occluder_style`.
- Docs in commit: `docs/rendering.md` (keybinds + lang file); `docs/project.md`
  controls checklist.

**Unit tests:** none (input wiring); in-game.

**In-game checklist:**
1. Options → Controls → bind "Toggle overlay" and "Cycle profile"; names render
   (not raw keys); the K "occluder style" key also shows a proper name now.
2. Press toggle key → overlay hides/shows; persists across relaunch.
3. Press cycle key → active profile advances over enabled entries; persists.
4. `requireSneakForRadiusScroll=false`: hold stick, scroll without sneaking →
   radius changes (hotbar not switched). `true`: plain scroll switches hotbar,
   sneak+scroll changes radius.
5. Regression: sneak+right-click cycle, stick selection, and K cycle still work.

## Step 6 — Cloth screen: scalar settings + ModMenu entry

**Scope:** everything except the hitbox list.

- `MobWalkModMenuIntegration implements ModMenuApi` → `getModConfigScreenFactory()`
  returns the Cloth screen; `fabric.mod.json` `entrypoints.modmenu`.
- `OverlayConfigScreen`: Cloth `ConfigBuilder` with categories General / Flood /
  Appearance: enable (boolean), active profile (selector over enabled names),
  default radius (int slider 0–20), require-sneak (boolean), show hole beams
  (boolean). Save handler → mutate config + `save()` + apply live. (No
  occluder-style control this milestone.)
- Docs in commit: `docs/rendering.md` (config-screen architecture: ModMenu → Cloth
  → Gson); `docs/project.md`.

**Unit tests:** none (GUI); in-game.

**In-game checklist:**
1. Mods → MobWalk → Configure → Cloth screen with three categories.
2. Toggle enable, change radius slider, toggle hole beams, change active profile →
   Save → changes take effect immediately in-world.
3. Reopen / relaunch → values persisted in `config/mobwalk.json`.
4. Cancel after edits → no changes applied/persisted.
5. Regression: keybinds + JSON hand-edit + K still work; no errors on
   open/close/resize.

## Step 7 — Cloth hitbox editor (headline, highest risk)

**Scope:** the editable profile library UI. Spike the Cloth widget first, ship the
highest clean rung of the fallback ladder, and record which rung shipped here.

Fallback ladder (prefer the top; drop down only if the rung above is unwieldy in
Cloth, whose list controllers are scalar-only):
1. **Full 2D** — a list of editable rows: enabled + name + width/height/reach +
   per-row delete (custom) / reset (builtin), plus "Add custom" and "Add from
   vanilla ▼". Likely needs one Cloth sub-category per profile, or a custom entry.
2. **1D** — built-ins as name-only enable toggles (their W/H/reach are fixed vanilla
   values, not edited) **plus a single editable "Custom" profile** with its own
   width/height/reach and enable toggle.
3. **MVP** — built-in name toggles only; no custom profile.

- Validation on save reuses Step 3's clamps (reject/repair invalid rows; keep
  Point; ensure ≥1 enabled).
- Docs in commit: `docs/geometry.md` (user-editable library + which rung shipped);
  `docs/project.md` (feature complete); README/install note (Cloth required +
  ModMenu suggested for the screen).

**Unit tests:** none new beyond Step 3's validation (covers the model); GUI in-game.

**In-game checklist (full-2D wording; reduce to the shipped rung):**
1. Configure → Hitboxes → seeded rows/toggles show correct data + builtin markers.
2. Disable a builtin → Save → in-world cycle skips it; definition remains.
3. (2D) Edit a builtin's width → Save → selection reflects it; "reset to vanilla"
   restores the seeded value.
4. (2D/1D) Add/define a custom profile → Save → appears in the cycle and works.
5. (2D) "Add from vanilla ▼" → pick Enderman → row auto-fills (0.6/2.9/1.0) →
   usable.
6. (2D) Delete the custom row → Save → gone; Point cannot be deleted.
7. Invalid value (name collision / negative width) → save rejected/repaired with
   feedback; config not corrupted.
8. Spider/Slime absent from any "Add from vanilla" list.
9. Regression: scalar settings, K cycle, config round-trip still work; no errors on
   open/close/resize/world reload.

---

## Open item

- **Hitbox editor rung** — resolved during the Step 7 Cloth spike (full 2D / 1D /
  MVP), recorded in the Step 7 commit.

## Deferred / out of scope

- Occluder marker style settings + K-key resolution — future graphics milestone.
- HUD settings (offset, duration, colour, anchor) — anchored-HUD roadmap.
- Visual constants (height ramp, alphas, skirt/beam styling, ring grey) — graphics
  polish (extends the Appearance category).
- Activation item picker; custom profile fields beyond W/H/reach.
- Flood perf hardening (timeout, threading, frame-slicing) — deferred algorithm
  milestone.
- Server-side config (client-only mod).
