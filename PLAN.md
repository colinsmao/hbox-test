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
- General (`Configs.Generic` / JSON `"Generic"`): **showSurfaces**
(cycle Never / While Holding Wand / Always, default While Holding Wand); **wandItem**
(item id string, default `minecraft:stick`, registry-validated with live invalid
tooltip); **mobProfile**
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
**downSkirtHeight** (0–4, default `2`); **upwardSkirtHeight** (0–4, default `0.25`);
**drawOnVisibleFace** (default on — draws standable tops on the visible block
face for taller-than-collision blocks like soul sand). K occluder-style cycle
removed (heights are Appearance doubles).
- **Visible-surface-top** (`drawOnVisibleFace`, gate-at-compute): the flag is
threaded into `select` as `computeVisualTop`, so the visible-top read is gated and a
value-change callback **re-floods** (the one Appearance option that touches compute).
Skirts/holes are split into two `computeDownSkirts` passes — `topY`-keyed
`dropEdges` feeds hole classification, `visualTopY`-keyed spans render (second pass
only when some rect is actually raised). Covers path-over-soul-sand (raise only the
overlap's `visualTopY`, collision `topY` untouched). Details in
`[docs/geometry.md](docs/geometry.md)` / `[docs/rendering.md](docs/rendering.md)`.
- Debug: **crouchSeeThroughWalls**; **crouchScrollRadius**; **crouchCycleProfile**
(default on); **shadeByDepth** (default off); **showCutoffRing** (default on).
- Save-on-close → `config/mobwalk.json`; player-facing `comment.*` tooltips;
optional `name.*` (else option id via `refreshDisplayNames`).

Stack (technical detail in `[docs/settings.md](docs/settings.md)`):

```
Mods → Configure → GuiConfigsBase (All/General/Appearance/Debug) → live options → save on close → mobwalk.json
```



## Current plan

Nothing in flight. Visible-surface-top (incl. path-over-raised-outline and the
skirt/hole domain split) has landed and is distilled into `docs/`.

## Ideas / backlog

Add or reorder freely; pick items up via a temporary plan when ready.

- Chunked / multi-tick flood so one raised block doesn't repay a full-flood scan
  (deferred with the general flood-perf frame-slicing below).
- Flood perf hardening (timeout, threading, frame-slicing).
- Settings tooltip UX pass (tone/length).

## Plan archive

Parked executable outlines; promote back to **Current plan** when picking them up.



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
