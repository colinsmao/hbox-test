# Settings (technical)

**This is the technical reference** for agents and maintainers working on the
config stack (MaLiLib registration, JSON, option types, how to add options).
**Player-facing settings help belongs elsewhere** (a publish-time README or wiki);
read this file when you need implementation detail.

General project facts live in `[project.md](project.md)`; rules in
`[../AGENTS.md](../AGENTS.md)`. How overlays are *drawn* lives in
`[rendering.md](rendering.md)`.

> Version facts below target Minecraft `26.1.2` and MaLiLib `0.28.9`. Verify
> class names against the resolved jars when upgrading.



## Stack

```
Pause → Mods (ModMenu) → Configure → GuiConfigs (MaLiLib GuiConfigsBase)
  → Configs (IConfigHandler) → config/mobwalk.json on screen close
```

- **MaLiLib** `0.28.9` (`fi.dy.masa.malilib.`*) is the settings UI and JSON
persistence stack. Loom uses plain `implementation` (non-remapping); ModMenu is
`compileOnly` + `localRuntime` so Configure appears in `runClient` without
bundling ModMenu into the jar.
- `MobWalkClient` registers `InitHandler` via MaLiLib’s `InitializationHandler`.
`InitHandler` registers the `IConfigHandler` with `ConfigManager` and the
config-screen factory with `Registry.CONFIG_SCREEN` (`ModInfo` → `GuiConfigs`).
- ModMenu’s Configure button is wired by `MobWalkModMenuIntegration` (Fabric
entrypoint `modmenu`).
- Widgets write option values immediately; JSON is written on config-screen close
through `IConfigHandler.save()` → `config/mobwalk.json` under the game config
directory. Load runs at init via `IConfigHandler.load()`.

Key files: `InitHandler.java`, `Configs.java`, `GuiConfigs.java`,
`MobWalkModMenuIntegration.java`, lang `assets/mobwalk/lang/en_us.json`.

## Live Generic options

Category key prefix: `mobwalk.config.generic` (`.apply(GENERIC_KEY)`). JSON
category name: `"Generic"`. Screen title lang: `mobwalk.gui.title.configs`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `enabled` | `ConfigBoolean` | `true` | Gates `CollisionSurfaceOverlay.isVisible()` each frame (existing snapshot stays; enable alone does not re-flood). |
| `mobProfile` | `ConfigOptionList` | `Player` (`EntityProfile.Option`) | Cycles Point / Player / Ravager. Source of truth for the active flood profile; `setValueChangeCallback` → `reselectWithMobProfile` when a selection is active. |
| `floodRadius` | `ConfigInteger` | `20` (min `0`, max `30`, slider) | `setValueChangeCallback` → `CollisionSurfaceOverlay.applyFloodRadius` (updates session radius and re-floods an active selection). |

Helpers: `Configs.mobProfile()`, `Configs.cycleMobProfile()`, `Configs.floodRadius()`.

Lang: player-facing `comment.*` tooltip for every option. Row labels use the option
id when `name.*` is omitted (`Configs.refreshDisplayNames`); an optional `name.*`
entry still overrides. No `prettyName.*` — MaLiLib falls back to `splitCamelCase`
for toggle messages.

`config_version` (`1`) is written beside the category objects in `mobwalk.json`.

## Screen layout

`GuiConfigs` implements `IConfigGuiAllTab` with filter buttons (not inline LABELs):

- **All** — General → Appearance → Debug (default tab)
- **General** — `Configs.Generic.OPTIONS` (JSON category stays `"Generic"`)
- **Appearance** — `Configs.Appearance.OPTIONS`
- **Debug** — `Configs.Debug.OPTIONS`

Tab button lang: `mobwalk.gui.button.config_gui.general` / `.appearance` / `.debug`
(All uses MaLiLib’s `IConfigGuiAllTab` key).

## Live Appearance options

Category key prefix: `mobwalk.config.appearance` (`.apply(APPEARANCE_KEY)`). JSON
category name: `"Appearance"`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `walkableColor` | `ConfigColor` | `#8066CC66` (light green, ~50% alpha) | RGB for tops/skirts when Debug `shadeByDepth` is off; alpha used for top fill. Read live in `emit`. |
| `showHoleBeams` | `ConfigBoolean` | `true` | When on: `emitHoles` draws through-walls beams at hole rims. When off: beams are skipped. |
| `holeBeamColor` | `ConfigColor` | `#80F2261A` (red, 50% alpha) | RGB + alpha for hole beams (uniform along the beam). |

Helpers: `Configs.walkableColor()`, `Configs.holeBeamColor()` → `Color4f`;
`Configs.showHoleBeams()`.

## Live Debug options

Category key prefix: `mobwalk.config.debug` (`.apply(DEBUG_KEY)`). JSON category
name: `"Debug"`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `crouchSeeThroughWalls` | `ConfigBoolean` | `true` | When on: crouching routes tops + rect borders into the depth-off layer. When off: tops stay depth-tested and crouch borders stay off. Skirts stay depth-tested either way. |
| `crouchScrollRadius` | `ConfigBoolean` | `true` | When on: stick + crouch + scroll adjusts flood radius (`wantsRadiusScroll`). When off: that gesture is inactive — scroll never changes the radius. |
| `crouchCycleProfile` | `ConfigBoolean` | `true` | When on: stick + crouch + right-click air advances `Configs.MOB_PROFILE` and pings the HUD. When off: air-click still clears the selection; the profile stays put. |
| `shadeByDepth` | `ConfigBoolean` | `false` | When on: tops/skirts use the cyclic BFS-depth hue (`depthColor`). When off: they use Appearance `walkableColor`. Cutoff ring (when shown) still greys via `greyBlend`. |
| `showCutoffRing` | `ConfigBoolean` | `true` | When on: draw the outermost flood-depth rings greyed (`greyBlend`). When off: those ring depths are not drawn. |

Helpers: `Configs.crouchScrollRadius()`, `Configs.crouchSeeThroughWalls()`,
`Configs.crouchCycleProfile()`, `Configs.shadeByDepth()`,
`Configs.showCutoffRing()` — read live each use (no value-change callbacks).

## Lang convention (`name.*` / `comment.*`)

- `name.<id>` — optional override for the row label. If missing,
  `Configs.refreshDisplayNames()` (on config screen open) sets the display name to
  the option id (same as the last segment of the apply() key), so the GUI does not
  show a raw translation key.
- `comment.<id>` — tooltip. Required for every option (missing → raw key).
  Player-facing only: help the player decide or use the option. Skip implementation
  jargon. Prefer short copy; extra length only when useful. A later UX pass may
  refine tone.
- Do not ship `prettyName.*` unless a toggle message needs custom phrasing.

## Adding an option

Match the existing Generic / Debug pattern in `Configs.java`:

1. Declare a `public static final` config option on the category nested class
  (`Configs.Generic`, `Configs.Appearance`, or `Configs.Debug`).
2. Add it to that class’s `OPTIONS` list.
3. Call `.apply(…_KEY)` so translated name/comment resolve under
  `mobwalk.config.generic.*`, `…appearance.*`, or `…debug.*`.
4. Add a player-facing `comment.*` in `en_us.json` (required). Optional `name.*`
  only when the row label should differ from the option id.
5. Wire live apply with `setValueChangeCallback` when changing the value should
  update an overlay immediately (see `FLOOD_RADIUS`); otherwise read the
  option live from a `Configs.*()` helper.
6. Persistence is automatic via `ConfigUtils.readConfigBase` /
  `writeConfigBase` over that category’s `OPTIONS` — keep the option on that list.

New categories: add another nested class + OPTIONS list, another JSON category
string in load/save, and a matching filter tab in `GuiConfigs`.

## MaLiLib config types (0.28.x)

From MaLiLib’s `ConfigType` enum for this line. Use these classes under
`fi.dy.masa.malilib.config.options` (and `…options.table` for tables).

### Scalars


| Type        | Class              | GUI                                | Role                            |
| ----------- | ------------------ | ---------------------------------- | ------------------------------- |
| BOOLEAN     | `ConfigBoolean`    | On/Off                             | Feature flags                   |
| INTEGER     | `ConfigInteger`    | Field or slider (optional min/max) | Counts, radius, ticks           |
| DOUBLE      | `ConfigDouble`     | Field or slider                    | Alphas, scales, timeouts        |
| FLOAT       | `ConfigFloat`      | Field or slider                    | Same as double, `float` storage |
| COLOR       | `ConfigColor`      | Color picker (`0xAARRGGBB`)        | Overlay / HUD tint              |
| STRING      | `ConfigString`     | Text field                         | Names, free-form ids            |
| BLOCK_STATE | `ConfigBlockState` | Block-state picker                 | A specific block state          |




### Lists and enums


| Type          | Class                   | GUI                                   | Role                       |
| ------------- | ----------------------- | ------------------------------------- | -------------------------- |
| STRING_LIST   | `ConfigStringList`      | Editable string list                  | Id lists, allow/deny lists |
| COLOR_LIST    | `ConfigColorList`       | List of colors                        | Multi-color palettes       |
| OPTION_LIST   | `ConfigOptionList`      | Cycle `IConfigOptionListEntry` values | Discrete modes             |
| OPTION_VALUES | (option-values helpers) | Multi-select style                    | Several discrete values    |
| LOCKED_LIST   | `ConfigLockedList`      | Ordered fixed membership              | Reorder-only lists         |
| TABLE         | `ConfigTable`           | Multi-column table                    | Structured rows            |




### Input and composites


| Type          | Class                   | GUI                        | Role                             |
| ------------- | ----------------------- | -------------------------- | -------------------------------- |
| HOTKEY        | `ConfigHotkey`          | Keybind button (multi-key) | Open config, cycle profile, etc. |
| *(composite)* | `ConfigBooleanHotkeyed` | Boolean + hotkey           | Toggleable flag with a bound key |


`ConfigBooleanHotkeyed` extends `ConfigBoolean` and stores both the boolean and  
its keybind (serialized as an object with `enabled` + `hotkey`).

