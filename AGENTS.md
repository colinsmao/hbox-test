# AGENTS.md

Guidance for AI agents working in this repository.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD
(Milestone 1) and **in the world** (Milestone 2), with small parallel
frameworks so more overlay widgets are easy to add.

**Read `AGENTS.md` first** — it holds the always-relevant essentials (scope,
layout, versions, build/test, project-wide constraints, conventions). Deep,
subsystem-specific detail lives in focused guides under **`docs/`** (see
**Subsystem guides**); read the relevant one only when working in that area.
**[PLAN.md](PLAN.md)** is reserved for the *current short-term plan* (e.g.
produced in planning mode) and is often empty between tasks; do not put durable
project knowledge there.

## Current status

Milestones 1 and 2 building. The repo is a client-only Fabric Gradle project
generated from `FabricMC/fabric-example-mod` and trimmed to client-only (see
**Repository layout** below). `./gradlew build` passes (produces
`build/libs/graphics-overlay-1.0.0.jar`).

- **Milestone 1 (HUD):** `runClient` shows a demo HUD overlay (a box +
  "Graphics Overlay" label, top-left, hidden by F1) via `Overlay` /
  `OverlayManager` + `HudElementRegistry`.
- **Milestone 2 (in-world):** an annulus is drawn flat on the top face of the
  block under the crosshair, shown only while holding a stick, with right-click
  cycling its color (one step per click, with an arm-swing). Built on
  `WorldOverlay` / `WorldOverlayManager` + `LevelRenderEvents`, with a use-key
  rising-edge dispatch for the color cycle. See
  [`docs/rendering.md`](docs/rendering.md).

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
    └── client/java/com/example/overlay/client/
        ├── OverlayClient.java               # ClientModInitializer entrypoint
        ├── Overlay.java                     # HUD widget interface
        ├── OverlayManager.java              # HUD registry + render dispatch
        ├── WorldOverlay.java                # in-world widget interface
        ├── WorldOverlayManager.java         # in-world registry + GPU plumbing
        └── widgets/
            ├── HelloOverlay.java            # demo HUD box + label
            └── BlockTopAnnulusOverlay.java  # red ring on targeted block top
```

`fabric.mod.json` sets `"environment": "client"`, declares **only** a `client`
entrypoint (`com.example.overlay.client.OverlayClient`), and depends on
`fabricloader >=0.19.2`, `minecraft ~26.1.2`, `java >=25`, and `fabric-api`.

## Target versions (confirm before building)

Versions move fast for Fabric. Always re-check <https://fabricmc.net/develop>
and the latest `FabricMC/fabric-example-mod` tag before building.

| Component     | Version          |
| ------------- | ---------------- |
| Minecraft     | `26.1.2`         |
| Fabric Loader | `0.19.2`         |
| Fabric Loom   | `1.16-SNAPSHOT`  |
| Fabric API    | `0.149.1+26.1.2` |
| JDK           | `25`             |

> **Minecraft versioning is year-based.** Since `1.21.11`, Minecraft uses a
> `YY.major.minor` scheme. `26.1.2` is a **real, current** release (the 2026.1
> line) — **not** a typo or alias for `1.21.x`. Do not "correct" it back to the
> old scheme.

> **Do not trust training-data knowledge for this version.** `26.1.2` postdates
> most models' cutoffs and the rendering API churns hard between releases. Class
> names, packages, and APIs here often differ from what you remember from
> `1.21.x`. Verify everything against **live documentation** and the **resolved
> jars** (the compiler is the most reliable oracle), not memory.

> **Authoritative sources** (pin the version selector to `26.1.2`):
> - Guides: <https://docs.fabricmc.net/develop> — e.g. "Drawing to the GUI" and
>   "Rendering in the World".
> - Fabric API javadocs: <https://maven.fabricmc.net/docs>.
> - Version numbers / template: <https://fabricmc.net/develop>.

> Mismatched JDK is the most common setup failure — verify `java -version` is 25
> before debugging build issues.

## Build & run

```bash
./gradlew build        # compile + produce build/libs/*.jar (CI gate)
./gradlew runClient    # launch a dev client with the mod loaded
```

Use the Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows); do not assume
a system Gradle is installed. `runClient` downloads the game/dependencies on
first run and launches a client with Fabric Loader + Fabric API + this mod
already injected — no manual install needed for testing.

> **Mappings:** Minecraft `26.1.2` ships **non-obfuscated**, so Loom rejects an
> explicit `mappings` line — do **not** add `loom.officialMojangMappings()` (or
> any `mappings ...`) to `build.gradle`, or the build fails with "Cannot use
> Mojang mappings in a non-obfuscated environment".

### Manual install into a real launcher

1. `./gradlew build`, then grab `build/libs/graphics-overlay-1.0.0.jar` (ignore
   any `*-sources.jar`).
2. Install **Fabric Loader** for Minecraft `26.1.2` via the official installer
   (<https://fabricmc.net/use/installer/>).
3. Download **Fabric API** `0.149.1+26.1.2` from Modrinth/CurseForge.
4. Drop both the Fabric API jar and the `graphics-overlay` jar into the `mods/`
   folder of the relevant `.minecraft` profile, then launch that Fabric profile.

## Testing

Rendering is visual, so testing combines an automated build gate with a manual
checklist. Available Fabric testing tooling (not yet wired up): `fabric-loader-junit`
for pure logic, and Fabric client gametests via `./gradlew runClientGameTest`
for end-to-end render smoke tests (run headless in CI with XVFB).

- **Build gate:** `./gradlew build` must pass (compiles + `fabric.mod.json`
  schema processing). This only proves it **compiles** — winding, culling,
  depth/z-fighting, visibility, once-per-click cycling, and the swing animation
  are runtime-only and must be checked with `./gradlew runClient`.
- **Manual acceptance checklist:**
  1. `runClient` launches with no errors in the log.
  2. **HUD:** in a world, the box + label is visible at the chosen corner and
     F1 (hide HUD) hides it.
  3. **In-world:** holding a stick, a ring sits flat on the targeted block's top
     face, tracks the crosshair, disappears when no block is targeted or no
     stick is held, is visible from above and below (double-sided) without bad
     z-fighting, and right-clicking advances the color exactly one step per
     click (holding does not spam) while swinging the arm.
  4. No errors on world load/unload or window resize.
  5. The mod does nothing on a dedicated server.

## Key constraints (all work)

These apply to **any** feature, so they live here rather than in a subsystem
guide.

- **Client-only.** The mod must never be required on a server: keep
  `"environment": "client"` in `fabric.mod.json`, declare only a `client`
  entrypoint, and put client code in the Loom `client` source set
  (`splitEnvironmentSourceSets()`). This holds for *any* feature, including a
  future settings screen.
- **`26.1.2` ≠ `1.21.x`.** Class/package/API names often differ from what you
  remember (e.g. `Identifier`, not `ResourceLocation`). Verify names against the
  resolved jars and live docs (see the version note above) — the compiler is the
  oracle, not training data.
- **No third-party rendering libraries** — a thin in-house abstraction is more
  stable than a dependency that must also chase the API churn (see
  `docs/rendering.md`).

## Subsystem guides

Subsystem-specific depth lives in its own doc so this file stays lean and
broadly relevant — an agent working on, say, a settings GUI should not have to
read the rendering internals. Read the relevant guide **before** touching that
area; add a new guide here as the project grows.

- **Rendering (HUD + in-world):** [`docs/rendering.md`](docs/rendering.md) — the
  HUD/world render APIs, the `Overlay` / `WorldOverlay` frameworks, `26.1.2`
  rendering class names, and pointers to the file-specific gotchas in the code.

## Future work / roadmap

The overlay frameworks (see `docs/rendering.md`) are designed to make these
incremental:

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

## Conventions

- Package base: `com.example.overlay` (mod id `overlay`). If the user provides a
  real maven group / mod id / author, update this file and code consistently.
- **Keep the docs current — every session (≈ every PR) updates documentation.**
  Treat doc upkeep as part of the work, not an afterthought: whenever a change
  alters behavior, architecture, status, constraints, versions, or adds a
  non-obvious gotcha, update the relevant docs **in the same PR** so the next
  agent inherits accurate knowledge. Typical updates: bump **Current status** /
  **Future work** here, refresh the affected **`docs/<subsystem>.md`** guide, add
  **code comments** for file-level gotchas, and clear/refresh **`PLAN.md`**.
  Reviewing the docs for staleness should be a normal step before opening a PR.
- **Where knowledge goes (keep `AGENTS.md` lean):** when you learn something
  non-obvious, place it by scope —
  - specific to one file/widget → a **code comment** next to the code;
  - specific to one subsystem → that subsystem's guide under **`docs/`**
    (e.g. `docs/rendering.md`);
  - a project-wide rule that applies to *any* task → **`AGENTS.md`** (under
    **Key constraints**).

  Don't put subsystem implementation detail (render pipelines, GUI widget APIs,
  etc.) in `AGENTS.md`; a future agent working on an unrelated area shouldn't
  have to read it.
- Don't add code comments that merely narrate what the code does; comments
  should explain non-obvious intent, trade-offs, or constraints, not restate the
  code.

## Git / workflow

- Branch naming for agent work: `cursor/<descriptive-name>-3c2f` (lowercase),
  branched off `main`.
- Commit logical changes separately with clear messages; do not force-push or
  amend unless asked.
- After pushing, open/update a PR against `main`.
- Verify `./gradlew build` passes before considering a code change complete.
- Before opening/updating the PR, update the relevant documentation in the same
  PR (see **Conventions → Keep the docs current**): `AGENTS.md` status/constraints,
  the affected `docs/<subsystem>.md`, code comments, and `PLAN.md`. A PR that
  changes behavior or architecture but leaves the docs stale is incomplete.
