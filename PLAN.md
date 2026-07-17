# PLAN — Settings (this branch)

**Branch:** `cursor/mobwalk-malilib-settings-31f3` — settings work lives here.

This file is the **settings ideas backlog** for the branch: what we want, what’s
already shipped, and notes that should survive across chats. It is **not** an
executable step list.

**How work proceeds:** each chunk of settings work is planned and executed
in-conversation with a **temporary plan** (its own steps + in-game checklists
per `[AGENTS.md](AGENTS.md)`). When that chunk lands, distill durable facts into
`docs/` and fold leftover ideas back into the lists below.

## Shipped on this branch

- MaLiLib `0.28.9` + ModMenu Configure → `GuiConfigs` with filter tabs **All** /
**General** / **Appearance** / **Debug** (default **All**).
- General (`Configs.Generic` / JSON `"Generic"`): **enableRendering**; **mobProfile**
(roster-backed cycle among enabled profiles, default Player); **floodRadius**
(0–30, default 20); `Edit Built-in Profiles` (`builtinProfiles` ConfigTable
popup — six builtins, enables + real row order; soft-disable when none enabled;
Cancel|Confirm RESET; live-vs-defaults RESET enable); `Edit Custom Profiles`
(`customProfiles` ConfigTable — uncapped ADD clones the clicked row below it; REMOVE;
Cancel|Confirm RESET → empty).
- Profiles JSON: slim `"builtinProfiles"` `{id, enabled}` + full `"customProfiles"`
table (geometry for builtins from code).
- Appearance: **walkableColor** (`#8066CC66`); **showBeamsThroughWalls** (default
on); **showHoleBeams** (default on); **holeBeamColor** (`#80F2261A`);
**downSkirtHeight** (0–4, default `2`); **upwardSkirtHeight** (0–4, default `0.25`).
K occluder-style cycle removed (heights are Appearance doubles).
- Debug: **crouchSeeThroughWalls**; **crouchScrollRadius**; **crouchCycleProfile**
(default on); **shadeByDepth** (default off); **showCutoffRing** (default on).
- Save-on-close → `config/mobwalk.json`; player-facing `comment.*` tooltips;
optional `name.*` (else option id via `refreshDisplayNames`).

Stack (technical detail in `[docs/settings.md](docs/settings.md)`):

```
Mods → Configure → GuiConfigsBase (All/General/Appearance/Debug) → live options → save on close → mobwalk.json
```



## Ideas / backlog

Add or reorder freely; pick items up via a temporary plan when ready.

- Surface-height mode as Appearance toggle + drop V (see **Plan archive**).
- Activation item picker (stick today).
- Flood perf hardening (timeout, threading, frame-slicing).
- Settings tooltip UX pass (tone/length).

## Plan archive

Parked executable outlines; promote back to **Current plan** when picking them up.

### Surface-height Appearance toggle + drop V

Appearance **`ConfigBoolean useVisibleSurfaceTop`** (default **on** = visible face).
Wire overlay to the config (re-flood on change). Then remove the V keybind so the
mode is Appearance-only. After that, MobWalk registers zero `KeyMapping`s (K
already gone with skirt heights).

**Step A — Appearance toggle (keep V flipping the config)**

- `Configs.java`: `USE_VISIBLE_SURFACE_TOP` on Appearance; helper; value-change
  callback → re-flood.
- `CollisionSurfaceOverlay.java`: read config at select/reselect; drop session
  `useVisualTop` / thin `toggleVisualTop` to flip config.
- `MobWalkClient.java`: keep `surfaceHeightKey`; flip config + HUD ping.
- `en_us.json`: `comment.useVisibleSurfaceTop` (`If enabled,` …).

Checklist: Appearance On by default; soul sand visible vs collision; V stays in
sync with the row; persists across relaunch; skirts/regression unchanged.

**Step B — Drop V**

- Remove `surfaceHeightKey` + tick handler from `MobWalkClient.java`.

Checklist: no Controls row; V inert; Appearance toggle still works.

**Docs when promoted:** settings/rendering/project + clear this archive entry.



## Scratch

Durable settings facts (stack, live options, MaLiLib types) live in
`[docs/settings.md](docs/settings.md)`. Session notes and mid-task decisions can
land here; clear when folded into docs or the backlog above.

**Mod action vs player action.** Options fall into two voices: what the mod
does/looks like (feature actor — Appearance, most General, debug visuals) vs how
the player interacts with it (player actor — crouch gestures, future keybinds).
That split drives bool `comment.*` grammar (see `[docs/settings.md](docs/settings.md)`
Lang convention) and is a useful lens if tabs are ever regrouped. **Debug** stays
the odd category for true debug aids; it need not be a clean “player” or “mod”
bucket.
