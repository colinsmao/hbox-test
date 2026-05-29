# AGENTS.md

Guidance for AI agents working in this repository.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. It renders both on the HUD
(Milestone 1) and **in the world** (Milestone 2), with small parallel
frameworks so more overlay widgets are easy to add.

Read **[PLAN.md](PLAN.md)** first — it is the source of truth for scope,
architecture, versions, and the build/test plan. Keep `PLAN.md` updated when
plans change.

## Current status

Milestones 1 and 2 building. The repo is a client-only Fabric Gradle project
generated from `FabricMC/fabric-example-mod` and trimmed per `PLAN.md` §4.
`./gradlew build` passes (produces `build/libs/graphics-overlay-1.0.0.jar`).

- **Milestone 1 (HUD):** `runClient` shows a demo HUD overlay (a box +
  "Graphics Overlay" label, top-left, hidden by F1) via `Overlay` /
  `OverlayManager` + `HudElementRegistry`.
- **Milestone 2 (in-world):** an annulus is drawn flat on the top face of the
  block under the crosshair, shown only while holding a stick, with right-click
  cycling its color (one step per click, with an arm-swing). Built on
  `WorldOverlay` / `WorldOverlayManager` + `LevelRenderEvents`, with a use-key
  rising-edge dispatch for the color cycle. See `PLAN.md` §11.

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
a system Gradle is installed.

> **Mappings:** Minecraft `26.1.2` ships **non-obfuscated**, so Loom rejects an
> explicit `mappings` line — do **not** add `loom.officialMojangMappings()` (or
> any `mappings ...`) to `build.gradle`, or the build fails with "Cannot use
> Mojang mappings in a non-obfuscated environment".

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
  See `PLAN.md` §4 and §11.

## Conventions

- Package base: `com.example.overlay` (mod id `overlay`). If the user provides a
  real maven group / mod id / author, update `PLAN.md` and code consistently.
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
