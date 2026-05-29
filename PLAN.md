# Plan: Minimal Graphics Overlay Fabric Mod

This document is the implementation plan for a minimal, client-side graphics
overlay mod for Minecraft, built on the [Fabric](https://fabricmc.net/)
toolchain. Milestone 1 is intentionally small: a mod that loads cleanly under
Fabric and draws something on the in-game HUD. Milestone 2 extends this to
**in-world** rendering (drawing geometry in the 3D scene rather than on the
HUD). Everything else in this plan exists to make these milestones trivially
extensible into a real overlay mod later.

> **Status:** Milestones 1 and 2 implemented and building. See §11 for the
> in-world rendering work.

---

## 1. Goals and non-goals

### Goals

- A buildable, client-only Fabric mod that produces a `.jar` consumable by the
  standard Fabric Loader; never required on a server, never affects gameplay
  state.
- **Milestone 1 (done):** render an obvious element on the **HUD** (a colored
  box + label) to prove the screen-space path end-to-end.
- **Milestone 2 (done):** render geometry **in the world** (a red ring on the
  targeted block's top face) to prove the 3D path end-to-end. See §11.
- Reproducible setup: clone, build, and launch the dev client with a single
  Gradle command.
- Clean extension frameworks (HUD + in-world) so new widgets are added without
  touching the bootstrap/registration/GPU plumbing.

### Non-goals (for now)

- No configuration UI, keybinds, or persistence yet (designed for, but not
  built).
- No server-side logic, networking, or mixins into gameplay.
- No third-party rendering libraries — the rendering API churns every MC
  version, so a thin in-house abstraction is more stable than a dependency that
  must also track the churn.
- No publishing to Modrinth/CurseForge yet.

---

## 2. Target versions

These are pinned from the official `FabricMC/fabric-example-mod` template and
should be confirmed against <https://fabricmc.net/develop> at implementation
time, since they move quickly.

| Component        | Version            | Notes                                            |
| ---------------- | ------------------ | ------------------------------------------------ |
| Minecraft        | `26.1.2`           | Target game version.                             |
| Fabric Loader    | `0.19.2`           | Runtime mod loader.                              |
| Fabric Loom      | `1.16-SNAPSHOT`    | Gradle build plugin.                             |
| Fabric API       | `0.149.1+26.1.2`   | Rendering APIs (HUD + in-world).                 |
| Java (JDK)       | `25`               | Required by this Minecraft version.              |
| Gradle           | Wrapper-pinned     | Use the `gradlew` shipped with the template.     |

> **Year-based versioning.** Since `1.21.11`, Minecraft uses a `YY.major.minor`
> scheme; `26.1.2` is a **real, current** release (the 2026.1 line), **not** an
> alias for `1.21.x`. Do not "correct" it back to the old scheme. (If you ever
> deliberately target an older `1.21.x` build, drop the JDK to 21 and adjust the
> Fabric API/Loom versions.)

> **Verify against live sources, not memory.** `26.1.2` postdates most models'
> training cutoffs and its rendering API churns hard, so class/package names
> often differ from `1.21.x`. Confirm against the current docs
> (<https://docs.fabricmc.net/develop>, version selector set to `26.1.2`), the
> Fabric API javadocs (<https://maven.fabricmc.net/docs>), and the resolved jars
> (the compiler is the most reliable oracle).

---

## 3. HUD rendering approach (Milestone 1 — done)

> Implemented; kept as a brief reference. For in-world rendering see §11.

The HUD path uses the current **`HudElementRegistry`** API (the legacy
`HudRenderCallback` is deprecated since Fabric API 0.116 — do not use it). Key
facts:

- An overlay is a `HudElement`; in `26.1.2` its functional method is
  `extractRenderState(GuiGraphicsExtractor, DeltaTracker)` (`GuiGraphicsExtractor`
  is this version's `GuiGraphics`).
- Attach relative to a `VanillaHudElements` element (e.g.
  `attachElementBefore(VanillaHudElements.CHAT, id, element)`) so the overlay
  inherits that element's render condition (respects F1). `addFirst`/`addLast`
  do not inherit one.
- `OverlayClient#onInitializeClient` calls `OverlayManager.bootstrap()` and
  attaches a single root HUD element whose render dispatches to all widgets.

---

## 4. Project structure

We will generate the base project from the official template
(<https://fabricmc.net/develop> generator or cloning
`FabricMC/fabric-example-mod`) and then trim it to a client-only overlay mod.
Target layout:

```
.
├── build.gradle
├── gradle.properties
├── settings.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/...
└── src
    ├── main/java/com/example/overlay/
    │   └── OverlayMod.java                  # shared constants (MOD_ID, logger)
    ├── client/java/com/example/overlay/client/
    │   ├── OverlayClient.java               # ClientModInitializer entrypoint
    │   ├── OverlayManager.java              # HUD registry + render dispatch
    │   ├── Overlay.java                     # interface for a HUD widget
    │   ├── WorldOverlayManager.java         # in-world registry + GPU plumbing (§11)
    │   ├── WorldOverlay.java                # interface for an in-world widget (§11)
    │   └── widgets/
    │       ├── HelloOverlay.java            # demo HUD box + label
    │       └── BlockTopAnnulusOverlay.java  # red ring on targeted block top (§11)
    ├── main/resources/
    │   └── fabric.mod.json
    └── client/resources/
        └── assets/overlay/icon.png
```

We use Loom's `splitEnvironmentSourceSets()` so client-only code lives in the
`client` source set and can never be loaded on a dedicated server.

### The extension framework

The "framework for future work" is a tiny abstraction so new overlays are
self-contained and registration is one line:

```java
public interface Overlay {
    /** Stable identifier suffix, unique per overlay. */
    String id();

    /** Draw this overlay. Called every frame the HUD is visible. */
    void render(GuiGraphicsExtractor graphics, DeltaTracker delta);

    /** Optional: allow an overlay to hide itself dynamically. */
    default boolean isVisible() { return true; }
}
```

`OverlayManager` holds an ordered list of `Overlay` instances, registers a
single root HUD element with `HudElementRegistry`, and in its `render` method
iterates visible overlays and calls each one's `render`. This keeps all Fabric
API contact in one place; future widgets only implement `Overlay` and call
`OverlayManager.register(new MyOverlay())` in `bootstrap()`.

---

## 5. `fabric.mod.json` essentials

Client-only: set `"environment": "client"` and declare **only** a `client`
entrypoint (`com.example.overlay.client.OverlayClient`) so the mod is irrelevant
server-side. Depends on `fabricloader >=0.19.2`, `minecraft ~26.1.2`,
`java >=25`, and `fabric-api`. See `src/main/resources/fabric.mod.json` for the
live file.

---

## 6. Scaffolding notes (Milestone 1 — done)

The project was generated from `FabricMC/fabric-example-mod`, trimmed to
client-only, and the HUD framework + `HelloOverlay` added. Two gotchas worth
remembering:

- Do **not** add a `mappings` line to `build.gradle` — `26.1.2` ships
  non-obfuscated and Loom rejects explicit mappings ("Cannot use Mojang mappings
  in a non-obfuscated environment").
- Keep `OverlayMod` (constants/logger) in the `main` source set so both `main`
  and `client` can share it; everything else is client-only.

---

## 7. Build, run, and install instructions

### Prerequisites

- JDK 25 installed and on `PATH` (verify with `java -version`).
- Git. No system Gradle needed — the project uses the Gradle wrapper.

### Local development (recommended workflow)

```bash
# From the project root
./gradlew build              # compiles and produces build/libs/*.jar
./gradlew runClient          # launches a dev Minecraft client with the mod loaded
```

`runClient` downloads the game and dependencies on first run and launches a
client with Fabric Loader + Fabric API + this mod already injected — no manual
install needed for testing. On Windows use `gradlew.bat`.

### Manual install into a real launcher

1. Run `./gradlew build` and grab the jar from `build/libs/`
   (e.g. `graphics-overlay-1.0.0.jar`). Ignore any `*-sources.jar`.
2. Install the matching **Fabric Loader** for Minecraft `26.1.2` using the
   official installer from <https://fabricmc.net/use/installer/>.
3. Download **Fabric API** `0.149.1+26.1.2` from Modrinth/CurseForge.
4. Place both the Fabric API jar and the `graphics-overlay` jar in the
   `mods/` folder of the relevant `.minecraft` profile.
5. Launch the Fabric profile; the overlay should appear on the HUD in-world.

---

## 8. Testing strategy

Mod rendering is inherently visual, so the test plan combines automated build
gating with a short manual checklist.

### Automated

- **Build check**: `./gradlew build` must succeed (compiles + runs Loom's
  validation, including `fabric.mod.json` schema processing). This is the CI
  gate.
- **Datagen/headless smoke (optional, later)**: `./gradlew runClient` can be run
  headlessly in CI to confirm the client reaches the main menu without the mod
  throwing during initialization; capture logs and fail on stacktraces from our
  package.
- **Unit tests** for any pure logic added later (e.g. layout math, formatting of
  overlay values) using JUnit 5 in the `test` source set. The Milestone 1 demo
  has little testable logic, but the framework should not block adding tests.

### Manual acceptance checklist

1. `./gradlew runClient` launches without errors in the log.
2. **HUD:** enter a world; the overlay box + label is visible at the chosen
   corner, and pressing F1 (hide HUD) hides it.
3. **In-world:** while holding a stick, look at a block; a ring sits flat on its
   top face, tracks the crosshair to new blocks, and disappears when looking at
   no block or when not holding a stick. It is visible from above and below
   (double-sided), without bad z-fighting. Right-clicking the stick advances the
   color exactly one step per click (holding does not spam) and swings the arm.
4. No errors are logged on world load/unload or on resizing the window.
5. The mod does nothing when placed on a dedicated server (server starts and
   runs normally / refuses to require the mod).

---

## 9. Future work (the framework enables these)

- **Configuration**: integrate a config layer (e.g. a simple JSON config, later
  a Cloth Config / ModMenu screen) to toggle individual overlays and set
  position/scale.
- **Keybinds**: use Fabric's `KeyBindingHelper` to toggle the overlay on/off.
- **More widgets**: HUD readouts (FPS/coords/biome, ping) as new `Overlay`
  implementations; in-world markers (block/entity highlights, waypoints) as new
  `WorldOverlay` implementations.
- **Anchored layout system**: corner/anchor + offset model so widgets can be
  positioned consistently across resolutions and GUI scales.
- **Data sources**: a small polling/event layer so widgets can subscribe to
  client tick events for values that change over time.
- **Distribution**: set up `fabric.mod.json` metadata, license, and a
  publish pipeline to Modrinth/CurseForge.

---

## 10. Risks and notes

- **Fast-moving API**: both the HUD and world render APIs were rewritten
  recently; always confirm versions/APIs against live docs and the resolved jars
  before building. Prefer `HudElementRegistry` (not the deprecated
  `HudRenderCallback`) and `LevelRenderEvents` (not the removed
  `WorldRenderEvents`).
- **Rendering pipeline migration**: Mojang is splitting rendering into extract
  and draw phases. The HUD stays high-level (`fill`, `text`), but **arbitrary
  in-world geometry has no high-level path** — it requires `BufferBuilder` +
  `RenderPipeline` + manual GPU upload. Quarantine that low-level
  `RenderSystem`/GPU code in `WorldOverlayManager` so churn touches one file.
- **Java version coupling**: this Minecraft version requires JDK 25; mismatched
  JDKs are the most common setup failure — document and check it first.
- **Non-obfuscated mappings**: MC `26.1.2` is distributed non-obfuscated, so
  Loom forbids an explicit `mappings` line in `build.gradle`.
- **`26.1.2` class renames**: the draw context is `GuiGraphicsExtractor` (was
  `GuiGraphics`) and identifiers use `net.minecraft.resources.Identifier` (was
  `ResourceLocation`); confirm names against the resolved jars if a future
  version churns them again.

---

## 11. Milestone 2 — in-world rendering

Milestone 2 moves from screen-space (HUD) rendering to **in-world** rendering:
drawing geometry positioned in the 3D scene. The first widget draws an
**annulus (ring) flat on the top face of the block the player is looking at**,
shown **only while holding a stick**; **right-clicking with the stick cycles its
color** (and plays the arm-swing use animation).

### Why this needs a different path

The HUD path (`HudElementRegistry` + `GuiGraphicsExtractor`) only draws in
screen space. In-world geometry uses Minecraft's world render loop, which in
`26.1.2` is split into two phases (Mojang's extract/draw migration):

- **Extraction phase** — gather the (mutable) game state you need and store it
  as an immutable, thread-safe snapshot ("render state").
- **Drawing phase** — turn that snapshot into vertices and submit them.

The legacy `WorldRenderEvents` vertex-consumer route is **gone** in this
version. There is no high-level "draw a shape in the world" call for arbitrary
geometry; you build a `BufferBuilder` and upload it through a `RenderPipeline`
yourself. (Boxes/lines/labels can still use vanilla `DebugRenderer`, but a
filled ring is not expressible there.)

### The in-world framework

Mirrors the HUD framework so widgets stay one-method-simple and all volatile
rendering code lives in one place:

```java
public interface WorldOverlay {
    String id();
    void extract(LevelExtractionContext context);          // snapshot game state
    void emit(Matrix4fc positionMatrix, BufferBuilder buffer); // append quads
    default boolean isVisible() { return true; }
    default void onUseItem(Player player, InteractionHand hand) {} // right-click hook
}
```

`WorldOverlayManager` owns everything Fabric/GPU-related:

- Registers `LevelRenderEvents.END_EXTRACTION` → dispatches `extract(...)` to
  every overlay, and `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` → dispatches
  the drawing phase.
- Owns **one** shared filled `RenderPipeline` (built from
  `RenderPipelines.DEBUG_FILLED_SNIPPET`, `QUADS` + `POSITION_COLOR`) and a
  shared `BufferBuilder`, so every visible overlay batches into a **single**
  draw call per frame.
- Translates the pose by `-cameraPos` (vertices are submitted in absolute world
  coordinates, made camera-relative for consistency with the world renderer).
- Handles the GPU handoff: build `MeshData`, upload into a reused/resized
  `MappableRingBuffer`, draw via a render pass, then rotate the buffer.
- Frees GPU resources on `ClientLifecycleEvents.CLIENT_STOPPING` (chosen over a
  `GameRenderer#close` mixin to avoid adding mixin plumbing this milestone;
  trade-off: buffers are freed at shutdown, not on mid-session renderer reload).
- Dispatches `onUseItem(...)` on the **rising edge** of the use key. A
  `ClientTickEvents.END_CLIENT_TICK` handler watches `options.keyUse.isDown()`
  and fires only on the up→down transition. This was deliberately **not** done
  with Fabric's `UseItemCallback`: that event re-fires every tick while the
  button is held (a stick has no use cooldown to throttle it), which
  spam-cycled the color. Edge detection gives exactly one cycle per click.

### The annulus widget (`BlockTopAnnulusOverlay`)

- `extract`: read `Minecraft.getInstance().hitResult` (store the targeted
  `BlockPos`, else `null`) and whether the main hand holds a stick
  (`player.getMainHandItem().is(Items.STICK)`).
- `isVisible`: true iff a block is targeted **and** a stick is held.
- `onUseItem`: if the used hand holds a stick, advance `colorIndex` through
  `PALETTE` and call `player.swing(hand)` for the use animation.
- `emit`: build the ring as `SEGMENTS` (64) quads between an inner and outer
  radius, in the horizontal plane at `blockTop + small offset` (avoids
  z-fighting), centered on the block (`x+0.5`, `z+0.5`), colored by the current
  palette entry.

### Rendering model & gotchas

- **Immediate-mode, not retained:** there is no persistent ring object. Each
  frame is rebuilt from scratch; the ring "moves" because `extract` reads a new
  block, and "disappears" by simply not being emitted. The only persistent
  object is the reused GPU vertex buffer (a scratchpad, fully overwritten each
  frame), not a scene object.
- **Double-sided:** flat/zero-thickness shapes must emit **both** windings, or
  back-face culling hides the ring from one side (symptom: it only appears when
  viewed from below). `BlockTopAnnulusOverlay.emit` emits each quad twice.
- **Input debounce:** use repeated-fire (holding a button) must be debounced via
  use-key edge detection, not the per-tick `UseItemCallback` (see the manager
  bullet above). `usePressedLastTick` holds the previous state.
- **Mutable state is `volatile`:** `target`, `holdingStick`, and `colorIndex` are
  written on the client tick/extraction path and read during drawing, so they
  are `volatile`.
- **Build vs runtime:** `./gradlew build` only proves it compiles. Winding,
  culling, depth/z-fighting, visibility, the once-per-click cycle, and the swing
  animation are runtime-only — verify with `./gradlew runClient`.

### Tuning knobs

In `BlockTopAnnulusOverlay`: `INNER_RADIUS` / `OUTER_RADIUS` (ring thickness),
`SEGMENTS` (smoothness), `Y_OFFSET` (z-fighting), `ALPHA`, and the `PALETTE`
colors (and the `Items.STICK` trigger item). For a through-walls variant, add
`withDepthStencilState(Optional.empty())` to the pipeline builder in
`WorldOverlayManager`.

> **Trigger scope:** because the cycle is driven by the use key (not the item-use
> event), it fires on any right-click while holding a stick, including when aimed
> at interactive blocks (chests, buttons). Narrow this later if undesired.
