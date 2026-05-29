# Plan: Minimal Graphics Overlay Fabric Mod

This document is the implementation plan for a minimal, client-side graphics
overlay mod for Minecraft, built on the [Fabric](https://fabricmc.net/)
toolchain. The goal of the first milestone is intentionally small: a mod that
loads cleanly under Fabric and draws something on the in-game HUD. Everything
else in this plan exists to make that first milestone trivially extensible into
a real overlay mod later.

---

## 1. Goals and non-goals

### Goals (Milestone 1 — "it loads and draws")

- A buildable Fabric mod that produces a `.jar` consumable by the standard
  Fabric Loader.
- Client-side only: the mod must never be required on a server and must not
  affect gameplay state.
- Renders a single, obvious overlay element on the HUD (a colored box plus a
  text label) to prove the rendering path works end-to-end.
- Reproducible setup: anyone can clone, build, and launch the dev client with a
  single Gradle command.
- A clean extension framework so future overlay widgets can be added without
  touching the bootstrap/registration plumbing.

### Non-goals (for Milestone 1)

- No configuration UI, keybinds, or persistence yet (designed for, but not
  built).
- No server-side logic, networking, or mixins into gameplay.
- No third-party rendering libraries — we use only the Fabric HUD API and
  vanilla `GuiGraphics`.
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
| Fabric API       | `0.149.1+26.1.2`   | Provides the HUD rendering API.                  |
| Java (JDK)       | `25`               | Required by this Minecraft version.              |
| Gradle           | Wrapper-pinned     | Use the `gradlew` shipped with the template.     |

> Note: Minecraft's modern versioning has shifted to a `YY.major.minor` scheme
> (e.g. `26.1.2`). If targeting an older 1.21.x release instead, drop the JDK
> requirement to 21 and adjust the Fabric API/Loom versions accordingly.

---

## 3. Rendering approach

Fabric's HUD rendering API was rewritten in the 1.21.6 era. The legacy
`HudRenderCallback` is **deprecated since Fabric API 0.116 and must not be
used**. We will use the current **`HudElementRegistry`** API.

Key facts that shape our design:

- Each overlay is a `HudElement` — effectively a lambda receiving a
  `GuiGraphics` (the draw context) and a `DeltaTracker` (partial-tick info).
- Elements are attached relative to vanilla HUD elements (`VanillaHudElements`),
  e.g. `attachElementBefore(VanillaHudElements.CHAT, id, element)`, or to the
  global extremes via `addFirst` / `addLast`.
- Elements attached relative to a vanilla element inherit that element's render
  condition (most respect the "hide HUD" / F1 option). `addFirst`/`addLast` do
  not inherit any render condition.
- For animations, use `Util.getMillis()` for real-time effects and the
  `DeltaTracker` partial tick only when the animation must track game ticks.

Reference skeleton (the actual entrypoint we will implement):

```java
public final class OverlayClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        OverlayManager.bootstrap(); // registers built-in widgets
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.of(OverlayMod.MOD_ID, "overlay_root"),
            OverlayManager::render
        );
    }
}
```

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
    │   ├── OverlayManager.java              # registry + render dispatch
    │   ├── Overlay.java                     # interface for a drawable widget
    │   └── widgets/
    │       └── HelloOverlay.java            # the demo box + label
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
    void render(GuiGraphics graphics, DeltaTracker delta);

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

Client-only declaration with the client entrypoint wired up:

```json
{
  "schemaVersion": 1,
  "id": "overlay",
  "version": "${version}",
  "name": "Graphics Overlay",
  "environment": "client",
  "entrypoints": {
    "client": ["com.example.overlay.client.OverlayClient"]
  },
  "depends": {
    "fabricloader": ">=0.19.2",
    "minecraft": "~26.1.2",
    "java": ">=25",
    "fabric-api": "*"
  }
}
```

`"environment": "client"` and only declaring a `client` entrypoint guarantees
the mod is irrelevant server-side.

---

## 6. Implementation steps

1. **Scaffold** the project from the Fabric template; set `mod_version`,
   `maven_group=com.example`, and `archives_base_name=graphics-overlay` in
   `gradle.properties`.
2. **Trim to client-only**: remove the server/`main` entrypoint and gameplay
   sample code; keep `OverlayMod` (constants/logger) in `main` so both source
   sets can share it.
3. **Add the framework**: create `Overlay`, `OverlayManager`, and
   `OverlayClient`.
4. **Implement `HelloOverlay`**: draw a filled rectangle with `graphics.fill(...)`
   and a label with `graphics.drawTextWithShadow(...)` near a fixed screen
   corner (top-left, with a margin), pulling the font from the running client.
5. **Wire registration**: `OverlayClient#onInitializeClient` calls
   `OverlayManager.bootstrap()` (which registers `HelloOverlay`) and attaches the
   root HUD element via `HudElementRegistry`.
6. **Write `fabric.mod.json`** per section 5.
7. **Build & run** the dev client (see section 7).
8. **Iterate** on positioning/colors until the overlay is clearly visible.

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

### Manual acceptance checklist (Milestone 1)

1. `./gradlew runClient` launches without errors in the log.
2. Enter a world; the overlay box + label is visible at the chosen corner.
3. Pressing F1 (hide HUD) also hides the overlay (confirms it inherits the
   vanilla render condition when attached relative to `VanillaHudElements`).
4. No errors are logged on world load/unload or on resizing the window.
5. The mod does nothing when placed on a dedicated server (server starts and
   runs normally / refuses to require the mod).

---

## 9. Future work (the framework enables these)

- **Configuration**: integrate a config layer (e.g. a simple JSON config, later
  a Cloth Config / ModMenu screen) to toggle individual overlays and set
  position/scale.
- **Keybinds**: use Fabric's `KeyBindingHelper` to toggle the overlay on/off.
- **More widgets**: FPS/coords/biome readouts, a ping/latency widget, a
  customizable text HUD — each is a new `Overlay` implementation.
- **Anchored layout system**: corner/anchor + offset model so widgets can be
  positioned consistently across resolutions and GUI scales.
- **Data sources**: a small polling/event layer so widgets can subscribe to
  client tick events for values that change over time.
- **Distribution**: set up `fabric.mod.json` metadata, license, and a
  publish pipeline to Modrinth/CurseForge.

---

## 10. Risks and notes

- **Fast-moving API**: the HUD API was rewritten recently; always confirm
  versions at <https://fabricmc.net/develop> before building, and prefer
  `HudElementRegistry` over the deprecated `HudRenderCallback`.
- **Rendering pipeline migration**: Mojang is splitting rendering into extract
  and draw phases. Sticking to high-level `GuiGraphics` calls (`fill`,
  `drawTextWithShadow`) keeps us insulated from low-level `RenderSystem` churn.
- **Java version coupling**: this Minecraft version requires JDK 25; mismatched
  JDKs are the most common setup failure — document and check it first.
