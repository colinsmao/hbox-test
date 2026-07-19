# Project context

Reference/background for this repo. `AGENTS.md` **holds the rules you must
follow; this file holds the project facts they operate on.** Read this when you
need orientation (what the project is, where code lives, which versions to use);
read the subsystem guides (`[rendering.md](rendering.md)`,
`[geometry.md](geometry.md)`, `[settings.md](settings.md)`) before touching
those areas.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD and **in the
world**, with small parallel frameworks so more overlay widgets are easy to add.

## What the mod does

The mod visualizes **where a chosen entity can stand and walk**. Two small frameworks
host every widget — a HUD overlay (`Overlay` / `OverlayManager`) and an in-world
overlay (`WorldOverlay` / `WorldOverlayManager`); how they draw is in
`[rendering.md](rendering.md)`.

The main widget is the **standable-surface selection** (`CollisionSurfaceOverlay` +
`SurfaceSelection` + `SurfaceEmitter`): right-click a block with the wand (default
stick; configurable item id in settings) and the mod floods outward over
walkable terrain within a spatial radius, painting every surface the chosen entity
could stand on. The flood is **entity-size aware** — width dilation closes gaps
smaller than the entity, and height headroom drops floors under low ceilings — for the
profile chosen in settings (builtin roster: Point / Player / Ravager / Warden /
Zombie-Witch / Skeleton, plus enable toggles and uncapped custom profiles). The
geometry and the output-sensitive flood live in `[geometry.md](geometry.md)`.

Each reached surface draws as a height-tinted top fill with edge markers that read the
terrain:

- **Skirts** — an **upward** wall face where an edge meets a wall or ceiling, a
**downward** skirt on a genuine drop (interior seams between equal-height pieces
stay clean).
- **Hole beams** — a through-walls beam at a drop a mob leaving the edge could not
climb back out of; a drop onto reachable ground keeps its plain skirt.
- **Cutoff ring** — surfaces near the radius limit fade toward grey, so an incomplete
selection reads differently from a real boundary.
- **Visible-face height** — blocks that render taller than they collide (soul sand,
mud) paint on the visible top face while all walkability math stays on the collision
top.

Interaction runs through the wand and crouch gestures: **shift+scroll** adjusts the
radius, **sneak + right-click at nothing** cycles the profile, **crouch** reveals
surfaces through walls, and `/mobwalk dump` writes a one-shot geometry dump. A HUD
readout shows the flood radius after a change. Behavior is configured from a MaLiLib
settings screen (General / Appearance / Debug) reachable via ModMenu → Configure and
persisted to `config/mobwalk.json`; General’s `Edit Built-in Profiles` and
`Edit Custom Profiles` buttons edit the roster; see `[settings.md](settings.md)`.

## Status & milestones

Milestones 1–6.5 are merged; **Milestone 7 (settings) is in progress**. The repo is a
client-only Fabric Gradle project generated from `FabricMC/fabric-example-mod` and
trimmed to client-only (see **Repository layout** below). `./gradlew build` passes
(produces `build/libs/mobwalk-1.0.0.jar`). Per-area detail lives in the subsystem
guides; the delivery history:

- **M1 — HUD framework** (`Overlay` / `OverlayManager`) with the transient
flood-radius readout (`RadiusIndicatorOverlay`).
- **M2 — in-world framework** (`WorldOverlay` / `WorldOverlayManager`), extract/draw
split over `LevelRenderEvents`.
- **M3 — stick surface selection**: a walkable flood from the clicked block, drawn as
fill + outline, with shift+scroll radius.
- **M4 — entity-size awareness**: config-space width dilation and the
output-sensitive lazy flood (adjacency/reach guarded by unit-tested static `flood`).
- **M4.5 — occluder-aware skirts + entity-height headroom**: upward skirts for
walls/ceilings, and the `(T, T+H]` standing-column headroom test. First unit tests
land here.
- **M5 — hole detection**: trapped-drop beams vs benign drops, classified
compute-side and subdivided so only the unsafe portion of an edge beams.
- **M6 / 6.5 — bug fixes**: 2-block grey cutoff ring, visible-face surface height, flood seeded from the clicked block's tops, the documented jump reach, and `/mobwalk dump`.
- **M7 — settings (in progress)**: MaLiLib + ModMenu config screen with
General / Appearance / Debug tabs, live apply, save-on-close, and General
**Built-in Profiles** / **Custom Profiles** roster (enables + order, soft-disable,
uncapped customs); see `[settings.md](settings.md)`.



## Repository layout

Client-only Fabric mod. Loom's `splitEnvironmentSourceSets()` keeps client code
in the `client` source set so it can never load on a dedicated server; only the
shared `MobWalk` (mod id + logger) lives in `main` so both source sets share
it.

```
.
├── build.gradle / gradle.properties / settings.gradle
├── gradlew / gradlew.bat / gradle/wrapper/...
└── src
  ├── main/java/dev/kelianmao/mobwalk/
  │   └── MobWalk.java                     # shared constants (MOD_ID, logger)
  ├── main/resources/fabric.mod.json       # client-only; client + modmenu entrypoints
  ├── client/java/dev/kelianmao/mobwalk/client/
  │   ├── MobWalkClient.java               # ClientModInitializer (+ debug keybinds, /mobwalk dump)
  │   ├── InitHandler.java                 # MaLiLib config + screen registration
  │   ├── Configs.java                     # IConfigHandler → config/mobwalk.json
  │   ├── GuiConfigs.java                  # MaLiLib GuiConfigsBase settings screen
  │   ├── MobWalkModMenuIntegration.java   # ModMenu Configure → GuiConfigs
  │   ├── Overlay.java                     # HUD widget interface
  │   ├── OverlayManager.java              # HUD registry + render dispatch
  │   ├── WorldOverlay.java                # in-world widget interface
  │   ├── WorldOverlayManager.java         # in-world registry + GPU plumbing
  │   ├── EntityProfile.java               # entity size/height/reach profiles (Point/Player/Ravager)
  │   ├── StandableRect.java               # world-space standable rectangle
  │   ├── OccluderSpan.java                # upward (wall/ceiling) skirt span (compute-side)
  │   ├── DownSkirtSpan.java               # downward drop-edge skirt span (compute-side)
  │   ├── HoleSpan.java                     # hole (unescapable drop) beam span (compute-side)
  │   ├── RectMath.java                    # pure rect/interval algebra (Rect, merge, flood, …)
  │   ├── SurfaceSelection.java            # size-aware surface compute: dilation + headroom + lazy flood + hole classification
  │   └── widgets/
  │       ├── RadiusIndicatorOverlay.java   # transient flood-radius HUD readout
  │       ├── CollisionSurfaceOverlay.java  # standable-surface selection input / lifecycle / publish
  │       └── SurfaceEmitter.java           # published snapshots → buffer geometry (+ Palette)
  ├── client/resources/assets/mobwalk/lang/en_us.json
  └── test/java/dev/kelianmao/mobwalk/client/ # pure-logic unit tests (fabric-loader-junit)
```

`fabric.mod.json` sets `"environment": "client"`, declares a `client` entrypoint
(`dev.kelianmao.mobwalk.client.MobWalkClient`) and a `modmenu` entrypoint, and
depends on `fabricloader >=0.19.2`, `minecraft ~26.1.2`, `java >=25`,
`fabric-api`, and `malilib`; it suggests `modmenu`.

## Target versions

Versions move fast for Fabric. Always re-check [https://fabricmc.net/develop](https://fabricmc.net/develop)
and the latest `FabricMC/fabric-example-mod` tag before building. (The *rules*
about these versions — year-based scheme, don't trust training data, authoritative
sources — live in `AGENTS.md` under **Key constraints**.)


| Component     | Version          |
| ------------- | ---------------- |
| Minecraft     | `26.1.2`         |
| Fabric Loader | `0.19.2`         |
| Fabric Loom   | `1.16-SNAPSHOT`  |
| Fabric API    | `0.149.1+26.1.2` |
| MaLiLib       | `0.28.9`         |
| ModMenu       | `18.0.0` (dev)   |
| JDK           | `25`             |


Authoritative sources (pin the version selector to `26.1.2`):

- Guides: [https://docs.fabricmc.net/develop](https://docs.fabricmc.net/develop) — e.g. "Drawing to the GUI" and
"Rendering in the World".
- Fabric API javadocs: [https://maven.fabricmc.net/docs](https://maven.fabricmc.net/docs).
- Version numbers / template: [https://fabricmc.net/develop](https://fabricmc.net/develop).



## Current manual acceptance checklist

The cumulative in-game checks for the features shipped so far. (The *rule* that
every plan step needs its own enumerated checklist, and how to gate on it, lives
in `AGENTS.md` under **Stage-gating**; this is the current feature snapshot.)

1. `runClient` launches with no errors in the log.
2. **HUD:** in a world, the box + label is visible at the chosen corner and F1
  (hide HUD) hides it.
3. **In-world:** holding the wand (in **either hand** — off hand acts only when the
  main hand is empty or also a wand; default item is stick, configurable via General
  `wandItem`), the standable collision surface of the targeted
   block is drawn flat on top, double-sided without bad z-fighting; sweeping the
   crosshair paints a growing set whose surfaces all stay drawn; per-block shapes
   are correct (full / slab / stairs-as-L / fence-post-tops / carpet-thin) and tall
   grass/flowers resolve to the block below; with default General `showSurfaces`
   (**While Holding Wand**), the selection hides when the wand is unequipped and
   returns on re-equip (**Always** keeps it visible; **Never** hides draw); right-clicking
   resets it (exactly once per
   click — holding does not spam) while swinging the acting arm; breaking/replacing a
   painted block updates or drops its surface. An edge against a **wall** draws an
   **upward** skirt (not a downward drop); a real drop/void keeps its downward
   skirt. Appearance `downSkirtHeight` / `upwardSkirtHeight` set those draw heights
   (`0` hides). `/mobwalk dump` one-shots
   the flood pipeline to `latest.log` and posts a short chat summary.
4. **Headroom:** with Player/Ravager selected, a floor under a low ceiling (gap
  `< H`) is **not** painted (its lost headroom shows as an upward skirt marking the
   ceiling), while a tall-enough tunnel paints; Point (zero height) paints regardless
   of ceiling.
5. **Holes:** an edge over the void or an unreachable pit raises a through-walls red
  beam at the rim; a drop onto reachable ground (even deep / roundabout via stairs
   elsewhere) does **not**; an edge over a floor that is only reachable by landing on an
   intermediate ledge does (the ledge would trap the mob). A long dangerous rim shows a
   row of beams. Tops are colored violet (low) → orange (high), never red. Skirts and
   beams at the very outermost radius edge are not drawn.
6. **Surface height (Appearance `drawOnVisibleFace`, default on):** on a **soul
   sand / mud** block (renders full-height but collides at 15/16), the standable top
   draws on the **visible top face** by default; turning the Appearance option off
   drops it to the true collision height (buried inside the block) and re-floods;
   turning it on restores the visible face. A dilated path lip reaching over a soul-sand
   cube paints on the cube top (with its own down-skirt at the step), while its collision
   stays a path. Ordinary full blocks / slabs / stairs look identical in both modes, and
   the height colors don't shift when toggling.
7. **Through water:** right-clicking the bottom of a pond paints its surface,
  visible through the water (water-tinted) **without** crouching; a surface behind
   opaque terrain (a hill) is still occluded unless crouching. (Water itself is not
   walkable.)
8. No errors on world load/unload or window resize; the selection clears on
  leaving/changing the world.
9. The mod does nothing on a dedicated server.



## Manual install into a real launcher

1. `./gradlew build`, then grab `build/libs/mobwalk-1.0.0.jar` (ignore
  any `*-sources.jar`).
2. Install **Fabric Loader** for Minecraft `26.1.2` via the official installer
  ([https://fabricmc.net/use/installer/](https://fabricmc.net/use/installer/)).
3. Download **Fabric API** `0.149.1+26.1.2` from Modrinth/CurseForge.
4. Drop both the Fabric API jar and the `mobwalk` jar into the `mods/`
  folder of the relevant `.minecraft` profile, then launch that Fabric profile.



## Subsystem guides

Subsystem-specific depth lives in its own doc so `AGENTS.md` stays lean. Read the
relevant guide **before** touching that area; add a new guide as the project grows.

- **Rendering (HUD + in-world):** `[rendering.md](rendering.md)` — the HUD/world
render APIs, the `Overlay` / `WorldOverlay` frameworks, `26.1.2` rendering class
names, and pointers to the file-specific gotchas in the code.
- **Surface / collision geometry:** `[geometry.md](geometry.md)` — the
`StandableRect` representation, the rect/double-space (not pixel-raster)
decision, the entity-width dilation model, and the entity-height headroom rule.
- **Settings (MaLiLib config):** `[settings.md](settings.md)` — technical
reference for the config stack, live Generic/Debug options, screen layout
(flat list + LABEL sections), and MaLiLib option types (player-facing
settings help is a separate publish-time doc).
- **Surface / overlay code index:** [`surface-code-index.md`](surface-code-index.md)
  — dense file/function map of `SurfaceSelection`, `RectMath`, `CollisionSurfaceOverlay`,
  `SurfaceEmitter`, and the shared surface records (what each type and method does).



## Future work / roadmap

The overlay frameworks (see `[rendering.md](rendering.md)`) are designed to make
these incremental:

- **Keybinds:** optional MaLiLib hotkeys for overlay toggles later.
- **More widgets:** HUD readouts (FPS/coords/biome, ping) as `Overlay`s;
in-world markers (block/entity highlights, waypoints) as `WorldOverlay`s.
- **Anchored layout system:** corner/anchor + offset model for consistent HUD
positioning across resolutions and GUI scales.
- **Data sources:** a small polling/event layer so widgets can subscribe to
client tick events for values that change over time.
- **Distribution:** `fabric.mod.json` metadata, license, and a Modrinth/
CurseForge publish pipeline.
- **Fall-damage / tall-drop warning.** Every benign drop
already carries its fall distance (`T − landY` from `classifyDrop`). A drop onto
reachable ground that is nonetheless tall enough to hurt (fall-damage threshold, or a
configurable height) could get a distinct lighter warning marker — a shorter/dimmer
beam or a tinted rim — separate from the red hole beam. Deferred; the fall distance
is plumbed and ready.

