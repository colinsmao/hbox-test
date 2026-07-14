# Project context

Reference/background for this repo. **`AGENTS.md` holds the rules you must
follow; this file holds the project facts they operate on.** Read this when you
need orientation (what the project is, where code lives, which versions to use);
read the subsystem guides ([`rendering.md`](rendering.md),
[`geometry.md`](geometry.md), [`settings.md`](settings.md)) before touching
those areas.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD
(Milestone 1) and **in the world** (Milestone 2), with small parallel
frameworks so more overlay widgets are easy to add.

## Current status

Milestones 1–6.5 merged; Milestone 7 (settings via MaLiLib) in progress — filter
tabs All/General/Appearance/Debug; General enabled / mobProfile / floodRadius;
Appearance walkableColor; Debug crouch gestures + shadeByDepth (live apply,
save-on-close). The repo is a client-only Fabric Gradle project generated
from `FabricMC/fabric-example-mod` and trimmed to client-only (see **Repository
layout** below). `./gradlew build` passes (produces `build/libs/mobwalk-1.0.0.jar`).
Rendering details live in [`rendering.md`](rendering.md); settings/config stack
in [`settings.md`](settings.md).

- **Milestone 1 — minimal working mod (HUD):** a HUD overlay framework
  (`Overlay` / `OverlayManager`); the current widget is `RadiusIndicatorOverlay`,
  a transient flood-radius readout near the crosshair.
- **Milestone 2 — in-world rendering:** a `WorldOverlay` / `WorldOverlayManager`
  framework that draws arbitrary geometry in the world (extract/draw split over
  `LevelRenderEvents`, one shared filled pipeline, use-key edge dispatch).
- **Milestone 3 — block-hitbox rendering:** right-clicking a block with a stick
  selects the standable surfaces reachable by a walkable flood (`SurfaceSelection`),
  drawn as fill + outline; shift+scroll (while holding the stick) sets the flood
  radius. Right-clicking nothing clears it.
- **Milestone 4 — entity-size-aware surfaces:** the flood is **size-aware** via
  configuration-space dilation (each collision footprint grown by the profile's
  half-width before an occlusion/spans-above test), so gaps and walls eat into the
  standable area by the entity's size; `EntityProfile` cycles Point/Player/Ravager.
  `select` is an **output-sensitive lazy flood** (a surface BFS that exposes columns
  *and* block rows on demand), verified set-equal to a full-window eager oracle. The
  geometry/algorithm lives in [`geometry.md`](geometry.md).
- **Milestone 4.5 — occluder-aware skirts + entity-height headroom:** two bundled
  changes. (A) **Occluder-aware skirts:** each surface edge bordering a wall/ceiling
  now draws an **upward** skirt (a wall face rising from the surface) instead of a
  false downward drop; the wall/drop/step classification is computed compute-side
  (`OccluderSpan`, since it reads collision boxes) and published in the snapshot. A
  standalone debug key (default **K**) cycles the marker style (tiny / half-block /
  full / bold-line). (B) **Entity-height headroom:** the occlusion test in
  `exposeBox` widens from the single level `T` to the standing column `(T, T+H]`, so a
  top survives only where the entity's body fits above it; `H = 0` (Point) reproduces
  the old buried test exactly. `EntityProfile` now carries `height`. The first unit
  tests in the repo (pure geometry: rect ops, edge classification, headroom predicate)
  land here behind `./gradlew test`.
- **Milestone 5 — hole detection:** the **drop edges** of the selection are classified
  into *benign* drops (keep their down-skirt) and **holes** (a mob leaving the edge is
  trapped), the latter marked by a **through-walls vertical beam** at the rim. A drop is
  a hole unless a **reached** surface lies below it (reachability = flood membership,
  never re-derived) *and* no standable **ledge** sits between the rim and that floor
  (the ledge scan reuses `exposeBox`). Classification is compute-side (`classifyDrop` /
  `computeHoles` / `holeSubSpans` / `gatherLedges`, published as `HoleSpan`); a single
  edge is subdivided so only its unsafe portion beams. Height coloring uses a
  violet→orange ramp (red is reserved for hole beams), and skirts/beams at the very
  outermost radius edge are suppressed as cutoff artifacts. The geometry/algorithm lives
  in [`geometry.md`](geometry.md).
- **Milestone 6 — polish + fidelity fixes (in progress):** a bundle of correctness
  fixes. (1) **Grey cutoff ring** now spans a **2-block buffer** — the outermost block
  ring is solid grey and the next ring in fades — so an incomplete selection reads more
  clearly (see [`rendering.md`](rendering.md)). (2) **Visible-face surface height:**
  blocks that render taller than they collide (soul sand, mud, cactus, honey) draw their
  standable top on the **visible face** instead of buried at the collision height. The
  visible top is gathered compute-side (memoized per `BlockState`) and baked into each
  rect's `visualTopY`; a standalone key (default **V**) toggles it against the true
  collision height (default **on**), re-flooding on toggle. Walkability math is
  unchanged — this only moves where the paint is drawn. See "Visible-face top vs
  collision top" in [`geometry.md`](geometry.md) and the surface-height toggle in
  [`rendering.md`](rendering.md). (3) **Overlay draws after translucent terrain**
  so ice/glass/honey stay visible under the fill; pond bottoms need crouch to
  show through water (see the recorded translucent-phase decision in
  [`rendering.md`](rendering.md)). (4) **Flood seeds from the
  clicked block's tops**; other surfaces join only via walkable BFS hops.
  (5) **Player / Ravager `reach = 1.2522`** (documented jump peak); Point
  `reach = 1.0`. Milestone 6.5 added `/mobwalk dump` and occluders-from-below in
  ledge gather.
- **Milestone 7 — settings (in progress):** MaLiLib + ModMenu Configure entry;
  Generic options for overlay enable and default flood radius (0–30, default 20)
  with live apply and save-on-close to `config/mobwalk.json`. Debug section
  (flat list LABEL): crouch-to-scroll-radius and crouch-to-see-through-walls
  (both default on; see [`settings.md`](settings.md)).

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
  │   ├── SurfaceSelection.java            # size-aware surface compute: dilation + headroom + lazy flood + hole classification
  │   └── widgets/
  │       ├── RadiusIndicatorOverlay.java   # transient flood-radius HUD readout
  │       └── CollisionSurfaceOverlay.java  # standable-surface selection widget + drawing
  ├── client/resources/assets/mobwalk/lang/en_us.json
  └── test/java/dev/kelianmao/mobwalk/client/ # pure-logic unit tests (fabric-loader-junit)
```

`fabric.mod.json` sets `"environment": "client"`, declares a `client` entrypoint
(`dev.kelianmao.mobwalk.client.MobWalkClient`) and a `modmenu` entrypoint, and
depends on `fabricloader >=0.19.2`, `minecraft ~26.1.2`, `java >=25`,
`fabric-api`, and `malilib`; it suggests `modmenu`.

## Target versions

Versions move fast for Fabric. Always re-check <https://fabricmc.net/develop>
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

- Guides: <https://docs.fabricmc.net/develop> — e.g. "Drawing to the GUI" and
  "Rendering in the World".
- Fabric API javadocs: <https://maven.fabricmc.net/docs>.
- Version numbers / template: <https://fabricmc.net/develop>.

## Current manual acceptance checklist

The cumulative in-game checks for the features shipped so far. (The *rule* that
every plan step needs its own enumerated checklist, and how to gate on it, lives
in `AGENTS.md` under **Stage-gating**; this is the current feature snapshot.)

1. `runClient` launches with no errors in the log.
2. **HUD:** in a world, the box + label is visible at the chosen corner and F1
   (hide HUD) hides it.
3. **In-world:** holding a stick (in **either hand** — off hand acts only when the
   main hand is empty or also a stick), the standable collision surface of the targeted
   block is drawn flat on top, double-sided without bad z-fighting; sweeping the
   crosshair paints a growing set whose surfaces all stay drawn; per-block shapes
   are correct (full / slab / stairs-as-L / fence-post-tops / carpet-thin) and tall
   grass/flowers resolve to the block below; the selection hides when the stick is
   unequipped and returns on re-equip; right-clicking resets it (exactly once per
   click — holding does not spam) while swinging the acting arm; breaking/replacing a
   painted block updates or drops its surface. An edge against a **wall** draws an
   **upward** skirt (not a downward drop); a real drop/void keeps its downward
   skirt; the debug key (K) cycles the upward-marker style. `/mobwalk dump` one-shots
   the flood pipeline to `latest.log` and posts a short chat summary.
4. **Headroom:** with Player/Ravager selected, a floor under a low ceiling (gap
   `< H`) is **not** painted (its lost headroom shows as an upward skirt marking the
   ceiling), while a tall-enough tunnel paints; Point is unchanged from Milestone 4.
5. **Holes:** an edge over the void or an unreachable pit raises a through-walls red
   beam at the rim; a drop onto reachable ground (even deep / roundabout via stairs
   elsewhere) does **not**; an edge over a floor that is only reachable by landing on an
   intermediate ledge does (the ledge would trap the mob). A long dangerous rim shows a
   row of beams. Tops are colored violet (low) → orange (high), never red. Skirts and
   beams at the very outermost radius edge are not drawn.
6. **Surface height (V):** on a **soul sand / mud** block (renders full-height but
   collides at 15/16), the standable top draws on the **visible top face** by default;
   pressing **V** drops it to the true collision height (buried inside the block) and the
   HUD reads `surface: collision`, pressing again restores `surface: visible`; ordinary
   full blocks / slabs / stairs look identical in both modes, and the height colors don't
   shift when toggling.
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
   (<https://fabricmc.net/use/installer/>).
3. Download **Fabric API** `0.149.1+26.1.2` from Modrinth/CurseForge.
4. Drop both the Fabric API jar and the `mobwalk` jar into the `mods/`
   folder of the relevant `.minecraft` profile, then launch that Fabric profile.

## Subsystem guides

Subsystem-specific depth lives in its own doc so `AGENTS.md` stays lean. Read the
relevant guide **before** touching that area; add a new guide as the project grows.

- **Rendering (HUD + in-world):** [`rendering.md`](rendering.md) — the HUD/world
  render APIs, the `Overlay` / `WorldOverlay` frameworks, `26.1.2` rendering class
  names, and pointers to the file-specific gotchas in the code.
- **Surface / collision geometry:** [`geometry.md`](geometry.md) — the
  `StandableRect` representation, the rect/double-space (not pixel-raster)
  decision, the entity-width dilation model, and the entity-height headroom rule.
- **Settings (MaLiLib config):** [`settings.md`](settings.md) — technical
  reference for the config stack, live Generic/Debug options, screen layout
  (flat list + LABEL sections), and MaLiLib option types (player-facing
  settings help is a separate publish-time doc).

## Future work / roadmap

The overlay frameworks (see [`rendering.md`](rendering.md)) are designed to make
these incremental:

- **Configuration:** MaLiLib All/General/Appearance/Debug tabs (enabled,
  mobProfile, floodRadius, walkableColor; Debug crouch gestures + shadeByDepth)
  via Mods → Configure; live apply + save-on-close (see [`settings.md`](settings.md)).
- **Keybinds:** `KeyBindingHelper` to toggle overlays.
- **More widgets:** HUD readouts (FPS/coords/biome, ping) as `Overlay`s;
  in-world markers (block/entity highlights, waypoints) as `WorldOverlay`s.
- **Anchored layout system:** corner/anchor + offset model for consistent HUD
  positioning across resolutions and GUI scales.
- **Data sources:** a small polling/event layer so widgets can subscribe to
  client tick events for values that change over time.
- **Distribution:** `fabric.mod.json` metadata, license, and a Modrinth/
  CurseForge publish pipeline.
- **Settle a single skirt/occluder rendering baseline (deferred from 4.5).** The
  `K`-key `cycleOccluderStyle` debug toggle (tiny / half-block / full / bold-line)
  shipped in 4.5 for A/B'ing the upward-marker look; choosing a baseline style and
  whether to keep the toggle is deferred to a later appearance-focused milestone;
  see [`rendering.md`](rendering.md).
- **Fall-damage / tall-drop warning (Milestone 5 extension).** Every benign drop
  already carries its fall distance (`T − landY` from `classifyDrop`). A drop onto
  reachable ground that is nonetheless tall enough to hurt (fall-damage threshold, or a
  configurable height) could get a distinct lighter warning marker — a shorter/dimmer
  beam or a tinted rim — separate from the red hole beam. Deferred; the fall distance
  is plumbed and ready.
