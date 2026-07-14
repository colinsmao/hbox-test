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

- MaLiLib `0.28.9` + ModMenu Configure → `GuiConfigs` (Generic).
- Live options: overlay **enable**; **default flood radius** (0–30, default 20).
- Save-on-close → `config/mobwalk.json`; lang `name.*` + `comment.*` (prettyName
  reuses `name.*`).
- Debug section (flat list LABEL under Generic): **crouch to scroll radius**
  (default on — off deactivates the gesture); **crouch to see through walls**
  (default on; borders share that gate).

Stack (technical detail in [`docs/settings.md`](docs/settings.md)):

```
Mods → Configure → GuiConfigsBase → live config options → save on close → mobwalk.json
```

## Ideas / backlog

Add or reorder freely; pick items up via a temporary plan when ready.

- Hotkey open for the config screen.
- Profiles UI / hitbox library (seeded vanilla + custom; Player default).
- Show hole beams toggle.
- Keybinds: toggle overlay; cycle profile (vanilla Controls or MaLiLib hotkeys).
- Persist occluder style (K) and surface-height mode (V).
- HUD settings (offset, duration, colour, anchor).
- Activation item picker (stick today).
- Appearance / visual constants (height ramp, alphas, skirt/beam styling, ring grey).
- Flood perf hardening (timeout, threading, frame-slicing).
- Server-side config (only if the mod ever grows a server half).

## Scratch

Durable settings facts (stack, live options, MaLiLib types) live in
[`docs/settings.md`](docs/settings.md). Session notes and mid-task decisions can
land here; clear when folded into docs or the backlog above.
