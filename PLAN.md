# PLAN — Settings (this branch)

**Branch:** `cursor/mobwalk-malilib-settings-31f3` — settings work lives here.

This file is the **settings ideas backlog** for the branch: what we want, what’s
already shipped, and notes that should survive across chats. It is **not** an
executable step list.

**How work proceeds:** each chunk of settings work is planned and executed
in-conversation with a **temporary plan** (its own steps + in-game checklists
per [`AGENTS.md`](AGENTS.md)). When that chunk lands, distill durable facts into
`docs/` and fold leftover ideas back into the lists below.

## Shipped on this branch

- MaLiLib `0.28.9` + ModMenu Configure → `GuiConfigs` with filter tabs **All** /
  **General** / **Appearance** / **Debug** (default **All**).
- General (`Configs.Generic` / JSON `"Generic"`): **enabled**; **mobProfile**
  (Point/Player/Ravager, default Player); **floodRadius** (0–30, default 20).
- Appearance: **walkableColor** (`#8066CC66` light green with alpha).
- Debug: **crouchSeeThroughWalls**; **crouchScrollRadius**; **crouchCycleProfile**
  (default on); **shadeByDepth** (default off — depth-hue debug aid).
- Save-on-close → `config/mobwalk.json`; player-facing `comment.*` tooltips;
  optional `name.*` (else option id via `refreshDisplayNames`).

Stack (technical detail in [`docs/settings.md`](docs/settings.md)):

```
Mods → Configure → GuiConfigsBase (All/General/Appearance/Debug) → live options → save on close → mobwalk.json
```

## Ideas / backlog

Add or reorder freely; pick items up via a temporary plan when ready.

- Hotkey open for the config screen.
- Profiles UI / hitbox library (seeded vanilla + custom beyond the cycle list).
- Show hole beams toggle.
- Keybinds: toggle overlay; cycle profile (vanilla Controls or MaLiLib hotkeys).
- Persist occluder style (K) and surface-height mode (V).
- HUD settings (offset, duration, colour, anchor).
- Activation item picker (stick today).
- More Appearance options (skirt/beam styling, ring grey, height ramp).
- Flood perf hardening (timeout, threading, frame-slicing).
- Settings tooltip UX pass (tone/length).
- Server-side config (only if the mod ever grows a server half).

## Scratch

Durable settings facts (stack, live options, MaLiLib types) live in
[`docs/settings.md`](docs/settings.md). Session notes and mid-task decisions can
land here; clear when folded into docs or the backlog above.
