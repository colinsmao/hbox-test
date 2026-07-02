# Project context

Reference/background for this repo. **`AGENTS.md` holds the rules you must
follow; this file holds the project facts they operate on.** Read this when you
need orientation (what the project is, where code lives, which versions to use);
read the subsystem guides ([`rendering.md`](rendering.md),
[`geometry.md`](geometry.md)) before touching those areas.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD
(Milestone 1) and **in the world** (Milestone 2), with small parallel
frameworks so more overlay widgets are easy to add.

## Current status

Milestones 1–4.5 building. The repo is a client-only Fabric Gradle project
generated from `FabricMC/fabric-example-mod` and trimmed to client-only (see
**Repository layout** below). `./gradlew build` passes (produces
`build/libs/graphics-overlay-1.0.0.jar`). Rendering details live in
[`rendering.md`](rendering.md).

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

## Repository layout

Client-only Fabric mod. Loom's `splitEnvironmentSourceSets()` keeps client code
in the `client` source set so it can never load on a dedicated server; only the
shared `OverlayMod` (mod id + logger) lives in `main` so both source sets share
it.

```
.
├── build.gradle / gradle.properties / settings.gradle
├── gradlew / gradlew.bat / gradle/wrapper/...
└── src
  ├── main/java/com/example/overlay/
  │   └── OverlayMod.java                  # shared constants (MOD_ID, logger)
  ├── main/resources/fabric.mod.json       # client-only; single client entrypoint
  ├── client/java/com/example/overlay/client/
  │   ├── OverlayClient.java               # ClientModInitializer entrypoint (+ debug keybind)
  │   ├── Overlay.java                     # HUD widget interface
  │   ├── OverlayManager.java              # HUD registry + render dispatch
  │   ├── WorldOverlay.java                # in-world widget interface
  │   ├── WorldOverlayManager.java         # in-world registry + GPU plumbing
  │   ├── EntityProfile.java               # entity size/height/reach profiles (Point/Player/Ravager)
  │   ├── StandableRect.java               # world-space standable rectangle
  │   ├── OccluderSpan.java                # upward (wall/ceiling) skirt span (compute-side)
  │   ├── SurfaceSelection.java            # size-aware surface compute: dilation + headroom + lazy flood
  │   └── widgets/
  │       ├── RadiusIndicatorOverlay.java   # transient flood-radius HUD readout
  │       └── CollisionSurfaceOverlay.java  # standable-surface selection widget + drawing
  └── test/java/com/example/overlay/client/ # pure-logic unit tests (fabric-loader-junit)
```

`fabric.mod.json` sets `"environment": "client"`, declares **only** a `client`
entrypoint (`com.example.overlay.client.OverlayClient`), and depends on
`fabricloader >=0.19.2`, `minecraft ~26.1.2`, `java >=25`, and `fabric-api`.

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
3. **In-world:** holding a stick, the standable collision surface of the targeted
   block is drawn flat on top, double-sided without bad z-fighting; sweeping the
   crosshair paints a growing set whose surfaces all stay drawn; per-block shapes
   are correct (full / slab / stairs-as-L / fence-post-tops / carpet-thin) and tall
   grass/flowers resolve to the block below; the selection hides when the stick is
   unequipped and returns on re-equip; right-clicking resets it (exactly once per
   click — holding does not spam) while swinging the arm; breaking/replacing a
   painted block updates or drops its surface. An edge against a **wall** draws an
   **upward** skirt (not a downward drop); a real drop/void keeps its downward
   skirt; the debug key (K) cycles the upward-marker style.
4. **Headroom:** with Player/Ravager selected, a floor under a low ceiling (gap
   `< H`) is **not** painted (its lost headroom shows as an upward skirt marking the
   ceiling), while a tall-enough tunnel paints; Point is unchanged from Milestone 4.
5. No errors on world load/unload or window resize; the selection clears on
   leaving/changing the world.
6. The mod does nothing on a dedicated server.

## Manual install into a real launcher

1. `./gradlew build`, then grab `build/libs/graphics-overlay-1.0.0.jar` (ignore
   any `*-sources.jar`).
2. Install **Fabric Loader** for Minecraft `26.1.2` via the official installer
   (<https://fabricmc.net/use/installer/>).
3. Download **Fabric API** `0.149.1+26.1.2` from Modrinth/CurseForge.
4. Drop both the Fabric API jar and the `graphics-overlay` jar into the `mods/`
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

## Future work / roadmap

The overlay frameworks (see [`rendering.md`](rendering.md)) are designed to make
these incremental:

- **Configuration:** a JSON config (later a Cloth Config / ModMenu screen) to
  toggle individual overlays and set position/scale.
- **Keybinds:** `KeyBindingHelper` to toggle overlays.
- **More widgets:** HUD readouts (FPS/coords/biome, ping) as `Overlay`s;
  in-world markers (block/entity highlights, waypoints) as `WorldOverlay`s.
- **Anchored layout system:** corner/anchor + offset model for consistent HUD
  positioning across resolutions and GUI scales.
- **Data sources:** a small polling/event layer so widgets can subscribe to
  client tick events for values that change over time.
- **Distribution:** `fabric.mod.json` metadata, license, and a Modrinth/
  CurseForge publish pipeline.
