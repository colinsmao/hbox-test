# PLAN — Settings via MaLiLib (Milestone 7)

Executable plan. Per [`AGENTS.md`](AGENTS.md): each step is one commit, validated
in-game via `./gradlew runClient` before the next, docs in the same commit.
`./gradlew build` must pass before every commit. Cross-cutting: no log errors on
launch / world load-unload / resize; client-only mod.

## Branching / PRs

- Branch `cursor/mobwalk-malilib-settings-31f3` (from `main`); PR "Milestone 7:
  settings via MaLiLib"; one commit per step.
- Cloth attempt left on `cursor/mobwalk-settings-31f3` for reference.

## Status

**Next:** Step 1 — MaLiLib + ModMenu + minimal config screen.

## Stack

```
Pause menu → Mods (ModMenu) → Configure → MaLiLib GuiConfigsBase
  → live ConfigBoolean / ConfigInteger (immediate)
  → save on screen close (IConfigHandler) → config/mobwalk.json
```

- **ModMenu** = Configure entry (primary open path; hotkey deferred).
- **MaLiLib** = screen + config options + JSON persistence (same stack as
  MiniHUD / Litematica for MC `26.1.2`).
- Widgets write option values **immediately**; `setValueChangeCallback` refreshes
  overlay state so graphics update while the panel is open. Closing the screen
  persists via `ConfigManager.onConfigsChanged` → `IConfigHandler.save()`.
- Loom non-remapping: plain `implementation` / `compileOnly` (+ ModMenu
  `localRuntime`). Client source set only.

### Dependencies

- MaLiLib **`0.28.9`** for MC `26.1.2` — `fi.dy.masa.malilib:malilib-fabric-26.1.2:0.28.9`
  from `https://masa.dy.fi/maven/sakura-ryoko`; `depends.malilib`.
- ModMenu `18.0.0` — `maven.modrinth:modmenu`; `compileOnly` + `localRuntime`;
  `suggests.modmenu`.

### API note (0.28.9)

Use sakura-ryoko `fi.dy.masa.malilib.*`: `GuiConfigsBase`, `ConfigBoolean`,
`ConfigInteger`, `IConfigHandler`, `InitializationHandler`,
`setValueChangeCallback`. (The maruohon rewrite’s `BaseConfigScreen` /
`saveIfDirty` is a different, unreleased API.)

## Defaults (this pass)

- Overlay **enabled** by default.
- Default flood radius **20**, clamp **0–30**.

## Step 1 — MaLiLib + ModMenu + minimal screen

**Scope:** wire MaLiLib and ModMenu; open an empty Generic MaLiLib config screen.
No overlay behaviour change yet.

- `gradle.properties`: `malilib_version=0.28.9`, `modmenu_version=18.0.0`.
- `build.gradle`: masa Maven for `fi.dy.masa`; MaLiLib `implementation`; ModMenu
  `compileOnly` + `localRuntime` from Modrinth.
- `fabric.mod.json`: `depends.malilib`, `suggests.modmenu`, `entrypoints.modmenu`.
- `Configs` (`IConfigHandler`) with empty Generic options list + load/save stub
  for `config/mobwalk.json`.
- `InitHandler` → register config handler + `Registry.CONFIG_SCREEN` factory;
  `MobWalkClient` registers `InitializationHandler`.
- `GuiConfigs extends GuiConfigsBase` showing Generic (empty list OK).
- `MobWalkModMenuIntegration` → `GuiConfigs` with parent.
- Docs in commit: `docs/project.md` (deps + layout); `docs/rendering.md` (stack).

**Unit tests:** none (build wiring).

**In-game checklist:**
1. `./gradlew build` → BUILD SUCCESSFUL.
2. `runClient` → launches; MaLiLib loads; no new log errors.
3. Pause → Mods → MobWalk → Configure → MaLiLib config screen opens (Generic).
4. Close screen → returns to ModMenu/pause without errors.
5. Regression: stick selection (either hand), sneak+scroll radius, sneak+right-click
   profile cycle, K occluder-style, V surface-height, `/mobwalk dump` unchanged;
   no errors on world load/unload / resize.

## Step 2 — General: enable + default radius (live)

**Scope:** two Generic options with live apply and save-on-close.

- `Configs.Generic`: `ENABLED` (`ConfigBoolean`, default true); `DEFAULT_RADIUS`
  (`ConfigInteger`, default 20, min 0, max 30, slider).
- Lang (`assets/mobwalk/lang/en_us.json`): screen title + pretty names + comments
  (MaLiLib `.apply("mobwalk.config.generic")` keys).
- `setValueChangeCallback`: enable gates `CollisionSurfaceOverlay.isVisible()`;
  radius updates selection radius and re-floods an active selection.
- Raise overlay `MAX_RADIUS` to **30**; initial `selectionRadius` from config
  default (20).
- Docs in commit: `docs/rendering.md` (live options + save-on-close);
  `docs/project.md` status.

**Unit tests:** none (GUI / render gate); in-game.

**In-game checklist:**
1. Mods → MobWalk → Configure → Generic shows enable + flood radius (0–30 slider).
2. With a stick selection visible, toggle enable off → overlay hides immediately;
   toggle on → returns.
3. Change radius slider with a selection active → flood re-computes to the new
   radius while the panel is open (or immediately on close if the world was paused).
4. Close Configure → values in `config/mobwalk.json`; relaunch → same values.
5. Hover controls → MaLiLib comment/tooltip text shows.
6. Regression: stick selection, sneak+scroll (clamped to 30), sneak+right-click
   profile cycle, K, V, `/mobwalk dump` unchanged; no errors on open/close/resize.

---

## Deferred / out of scope (follow-ups)

- Hotkey open for the config screen.
- Profiles UI / hitbox library; sneak-scroll gate; hole-beams toggle; keybinds
  (toggle overlay / cycle profile).
- Occluder style / V as persisted settings — graphics / later milestones.
- HUD settings; activation item picker; flood perf hardening; server-side config.
