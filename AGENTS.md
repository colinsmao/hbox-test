# AGENTS.md

Guidance for AI agents working in this repository.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD
(Milestone 1) and **in the world** (Milestone 2), with small parallel
frameworks so more overlay widgets are easy to add.

**This file (`AGENTS.md`) is the durable source of truth** for scope,
architecture, versions, constraints, and the build/test process — read it first.
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
  rising-edge dispatch for the color cycle. See **Implemented widgets &
  rendering gotchas** below.

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

## Key technical constraints

- **Client-only.** The mod must never be required on a server. Use
  `"environment": "client"` in `fabric.mod.json`, declare only a `client`
  entrypoint, and put client code in the Loom `client` source set
  (`splitEnvironmentSourceSets()`).
- **HUD rendering API:** use the current **`HudElementRegistry`** API. The
  legacy `HudRenderCallback` is **deprecated (Fabric API 0.116+) — do not use
  it.** Attach relative to `VanillaHudElements` (e.g. `attachElementBefore(...,
  VanillaHudElements.CHAT, ...)`) so the overlay inherits the vanilla "hide HUD"
  (F1) render condition.
- **Stay high-level where possible:** for the HUD, draw via the draw context
  (`fill`, `text`). For arbitrary **in-world** geometry there is no high-level
  path in `26.1.2` — Mojang's extract/draw migration means you must build a
  `BufferBuilder` and upload it through a `RenderPipeline` yourself. Keep that
  low-level `RenderSystem`/GPU code quarantined in `WorldOverlayManager` so
  churn touches one file (see the in-world API note below).
- **In-world rendering API (`26.1.2`):** rendering is split into an
  **extraction** phase and a **drawing** phase. Register
  `LevelRenderEvents.END_EXTRACTION` (read game state into immutable render
  state) and `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` (emit geometry); the
  drawing context is `LevelRenderContext` (`poseStack()`,
  `levelState().cameraRenderState.pos`). Translate vertices by `-cameraPos` so
  world coords are camera-relative. Reuse a vanilla pipeline base
  (`RenderPipelines.DEBUG_FILLED_SNIPPET`, `QUADS` + `POSITION_COLOR`); only go
  fully custom for effects vanilla can't do (e.g. through-walls via
  `withDepthStencilState(Optional.empty())`). Flat/zero-thickness shapes must be
  **double-sided** (emit both windings) or back-face culling hides them from one
  side. The legacy `WorldRenderEvents` vertex-consumer path is **gone** here.
- **Per-click input:** to act once per right-click, edge-detect the use key
  (`ClientTickEvents.END_CLIENT_TICK` watching `options.keyUse.isDown()`), not
  `UseItemCallback` — that event re-fires every tick while the button is held
  for items with no use cooldown (e.g. a stick), causing spam.
- **`26.1.2` API renames (verified against the resolved jars):** the draw
  context is **`net.minecraft.client.gui.GuiGraphicsExtractor`** (not
  `GuiGraphics`); text is drawn with **`text(Font, String, x, y, color,
  dropShadow)`** (not `drawTextWithShadow`/`drawString`); and identifiers are
  **`net.minecraft.resources.Identifier`** via
  `Identifier.fromNamespaceAndPath(...)` (not `ResourceLocation`). The
  `HudElement` functional method is `extractRenderState(GuiGraphicsExtractor,
  DeltaTracker)`.
- **Extension framework:** HUD widgets implement `Overlay` and register via
  `OverlayManager`; in-world widgets implement `WorldOverlay` (an `extract` +
  an `emit` method) and register via `WorldOverlayManager`. Keep all Fabric-API
  and low-level rendering contact inside the managers so widgets stay decoupled.
  See **Implemented widgets & rendering gotchas** below.

## Implemented widgets & rendering gotchas

### HUD framework

`Overlay` is a small interface (`id()`, `render(GuiGraphicsExtractor,
DeltaTracker)`, `isVisible()`). `OverlayManager.bootstrap()` registers built-in
widgets and attaches a **single** root HUD element via `HudElementRegistry`
whose render iterates visible overlays. New HUD widgets implement `Overlay` and
call `OverlayManager.register(...)` — one line, no Fabric-API contact.

### In-world framework

`WorldOverlay` has an `extract(...)` (snapshot game state) + `emit(...)` (append
quads) split, mirroring Mojang's extract/draw phases, plus an `onUseItem(...)`
hook. `WorldOverlayManager` owns everything Fabric/GPU-related: registers the
`LevelRenderEvents` phases, owns **one** shared filled `RenderPipeline` +
`BufferBuilder` so all visible overlays batch into a single draw call, translates
the pose by `-cameraPos`, handles the `MeshData` → `MappableRingBuffer` → render
pass GPU handoff, frees GPU resources on
`ClientLifecycleEvents.CLIENT_STOPPING` (chosen over a `GameRenderer#close`
mixin to avoid mixin plumbing; trade-off: freed at shutdown, not on mid-session
renderer reload), and dispatches `onUseItem` on the use-key rising edge.

### `BlockTopAnnulusOverlay`

- `extract`: read `Minecraft.getInstance().hitResult` (targeted `BlockPos` or
  `null`) and whether the main hand holds a stick.
- `isVisible`: true iff a block is targeted **and** a stick is held.
- `onUseItem`: advance `colorIndex` through `PALETTE` and `player.swing(hand)`.
- `emit`: build the ring as `SEGMENTS` (64) quads between inner/outer radius in
  the horizontal plane at `blockTop + Y_OFFSET`, centered on the block.

Gotchas (all the hard-won, non-obvious ones):

- **Immediate-mode, not retained:** no persistent ring object — each frame is
  rebuilt; the ring "moves"/"disappears" purely by what `extract` reads and
  whether `emit` runs. The only persistent object is the reused GPU vertex
  buffer (a scratchpad), not a scene object.
- **Double-sided:** flat/zero-thickness shapes must emit **both** windings or
  back-face culling hides them from one side (symptom: visible only from below).
- **Input debounce:** per-click actions must edge-detect the use key, not use
  `UseItemCallback` (which re-fires every tick while held — see constraints).
- **`volatile` mutable state:** `target`, `holdingStick`, `colorIndex` are
  written on the tick/extract path and read while drawing.
- **Tuning knobs** (in `BlockTopAnnulusOverlay`): `INNER_RADIUS` /
  `OUTER_RADIUS` (thickness), `SEGMENTS` (smoothness), `Y_OFFSET` (z-fighting),
  `ALPHA`, `PALETTE`, and the `Items.STICK` trigger item. For a through-walls
  variant, add `withDepthStencilState(Optional.empty())` to the pipeline builder
  in `WorldOverlayManager`.
- **Trigger scope:** the cycle fires on any right-click while holding a stick,
  including when aimed at interactive blocks (chests, buttons). Narrow later if
  undesired.

## Future work / roadmap

The frameworks above are designed to make these incremental:

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
- Don't add code comments that merely narrate what the code does.
- Don't introduce third-party rendering libraries. The rendering API churns
  every MC version, so a thin in-house abstraction (the managers) is more stable
  than depending on a library that must also track the churn.

## Git / workflow

- Branch naming for agent work: `cursor/<descriptive-name>-3c2f` (lowercase),
  branched off `main`.
- Commit logical changes separately with clear messages; do not force-push or
  amend unless asked.
- After pushing, open/update a PR against `main`.
- Verify `./gradlew build` passes before considering a code change complete.
