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


| Option          | Class           | Default                          | Behavior                                                                                                                            |
| --------------- | --------------- | -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `enabled`       | `ConfigBoolean` | `true`                           | Gates `CollisionSurfaceOverlay.isVisible()` each frame (existing snapshot stays; enable alone does not re-flood).                   |
| `defaultRadius` | `ConfigInteger` | `20` (min `0`, max `30`, slider) | `setValueChangeCallback` → `CollisionSurfaceOverlay.applyDefaultRadius` (updates session radius and re-floods an active selection). |


Lang keys (prettyName reuses `name.*` so toggle messages share the label):

- `mobwalk.config.generic.name.enabled` / `…name.defaultRadius`
- `mobwalk.config.generic.comment.defaultRadius` (tooltip)

`config_version` (`1`) is written beside the category objects in `mobwalk.json`.

## Screen layout

One scrolling list in `GuiConfigs.getConfigs()` (no category tabs):

1. `Configs.Generic.OPTIONS`
2. A MaLiLib **LABEL** row (`new ConfigOptionWrapper(…)`), lang `mobwalk.config.debug`
3. `Configs.Debug.OPTIONS`

## Live Debug options

Category key prefix: `mobwalk.config.debug` (`.apply(DEBUG_KEY)`). JSON category
name: `"Debug"`.

| Option | Class | Default | Behavior |
| --- | --- | --- | --- |
| `crouchScrollRadius` | `ConfigBoolean` | `true` | When on: stick + crouch + scroll adjusts flood radius (`wantsRadiusScroll`). When off: that gesture is inactive — scroll never changes the radius. |
| `crouchSeeThroughWalls` | `ConfigBoolean` | `true` | When on: crouching routes tops + rect borders into the depth-off layer. When off: tops stay depth-tested and crouch borders stay off. Skirts stay depth-tested either way. |

Helpers: `Configs.crouchScrollRadius()`, `Configs.crouchSeeThroughWalls()` — read
live each use (no value-change callbacks).

Lang keys:

- `mobwalk.config.debug` (section LABEL)
- `mobwalk.config.debug.name.crouchScrollRadius` / `…name.crouchSeeThroughWalls`

## Adding an option

Match the existing Generic / Debug pattern in `Configs.java`:

1. Declare a `public static final` config option on the category nested class
  (`Configs.Generic` or `Configs.Debug`).
2. Add it to that class’s `OPTIONS` list.
3. Call `.apply(…_KEY)` so translated name/comment resolve under
  `mobwalk.config.generic.*` or `mobwalk.config.debug.*`.
4. Set prettyName to the same `name.*` key when toggle/HUD messages should reuse
  the GUI label.
5. Add lang entries in `en_us.json` (`name.*`, and `comment.*` when a tooltip
  helps).
6. Wire live apply with `setValueChangeCallback` when changing the value should
  update an overlay immediately (see `DEFAULT_RADIUS`); otherwise read the
  option live from a `Configs.*()` helper.
7. Persistence is automatic via `ConfigUtils.readConfigBase` /
  `writeConfigBase` over that category’s `OPTIONS` — keep the option on that list.

New categories: add another nested class + OPTIONS list, another JSON category
string in load/save, and append a LABEL section (or tab) in `GuiConfigs` when
the UI needs it.

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

