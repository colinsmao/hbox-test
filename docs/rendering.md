# Rendering subsystem

Deep-dive reference for the **rendering** code — HUD overlays and in-world
geometry. **Read this only when working on rendering.** General project
guidance (versions, build/run, conventions, git) lives in
[../AGENTS.md](../AGENTS.md); file-specific gotchas live as comments in the
source files. This doc captures the subsystem design and the API facts that the
compiler and stale training data won't hand you.

> All version-specific facts below are for Minecraft `26.1.2`. The rendering API
> churns hard between releases, so **verify class/package names against the
> resolved jars and live docs** (<https://docs.fabricmc.net/develop>,
> <https://maven.fabricmc.net/docs>), not memory.

## Design philosophy

- **No third-party rendering libraries.** The rendering API changes every MC
  version; a thin in-house abstraction (the two managers) is more stable than a
  dependency that must also chase the churn.
- **Quarantine the volatile, low-level code in the managers.** Widgets stay
  decoupled (`Overlay` / `WorldOverlay`); all Fabric-API and GPU contact lives in
  `OverlayManager` / `WorldOverlayManager`, so API churn touches one file.

## HUD rendering (Milestone 1)

- Use the current **`HudElementRegistry`** API. The legacy `HudRenderCallback`
  is **deprecated (Fabric API 0.116+) — do not use it.**
- Attach relative to a `VanillaHudElements` element (e.g.
  `attachElementBefore(VanillaHudElements.CHAT, id, element)`) so the overlay
  inherits that element's render condition (respects the F1 "hide HUD" toggle).
  `addFirst`/`addLast` do not inherit one.
- The `HudElement` functional method in `26.1.2` is
  `extractRenderState(GuiGraphicsExtractor, DeltaTracker)`.
- **Framework:** `Overlay` is a small interface (`id()`,
  `render(GuiGraphicsExtractor, DeltaTracker)`, `isVisible()`).
  `OverlayManager.bootstrap()` registers built-in widgets and attaches a
  **single** root HUD element whose render iterates visible overlays. New HUD
  widgets implement `Overlay` and call `OverlayManager.register(...)` — one line.

## In-world rendering (Milestone 2)

- Rendering is split into an **extraction** phase (read game state into an
  immutable snapshot) and a **drawing** phase (emit geometry). Register
  `LevelRenderEvents.END_EXTRACTION` and
  `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`. The drawing context is
  `LevelRenderContext` (`poseStack()`, `levelState().cameraRenderState.pos`).
- **No high-level path** for arbitrary geometry: build a `BufferBuilder` and
  upload it through a `RenderPipeline` yourself. The legacy `WorldRenderEvents`
  vertex-consumer route is **gone** here. (Boxes/lines can still use vanilla
  `DebugRenderer`, but a filled ring cannot.)
- Translate vertices by `-cameraPos` so absolute world coords become
  camera-relative (matching the world renderer).
- Reuse a vanilla pipeline base (`RenderPipelines.DEBUG_FILLED_SNIPPET`,
  `QUADS` + `POSITION_COLOR`); only go fully custom for effects vanilla can't do
  (e.g. through-walls via `withDepthStencilState(Optional.empty())`).
- **Flat/zero-thickness shapes must be double-sided** (emit both windings), or
  back-face culling hides them from one side.
- **Per-click input:** to act once per right-click, edge-detect the use key
  (`ClientTickEvents.END_CLIENT_TICK` watching `options.keyUse.isDown()`), not
  `UseItemCallback` — that re-fires every tick while the button is held for
  items with no use cooldown (e.g. a stick), causing spam.
- **Framework:** `WorldOverlay` splits into `extract(...)` + `emit(...)`
  (mirroring the extract/draw phases) plus an `onUseItem(...)` hook. The model is
  **immediate-mode** (geometry rebuilt every frame), not retained.
  `WorldOverlayManager` owns the `LevelRenderEvents` phases, a single shared
  filled `RenderPipeline` + `BufferBuilder` (so all visible overlays batch into
  one draw call), the `MeshData` → `MappableRingBuffer` → render-pass GPU
  handoff, the use-key rising-edge dispatch, and GPU cleanup on
  `ClientLifecycleEvents.CLIENT_STOPPING` (chosen over a `GameRenderer#close`
  mixin to avoid mixin plumbing; trade-off: freed at shutdown, not on a
  mid-session renderer reload). `BlockTopAnnulusOverlay` is the demo widget.

## `26.1.2` rendering API names (verified against the resolved jars)

- Draw context is **`net.minecraft.client.gui.GuiGraphicsExtractor`** (not
  `GuiGraphics`).
- Text is drawn with **`text(Font, String, x, y, color, dropShadow)`** (not
  `drawTextWithShadow`/`drawString`).
- Identifiers are **`net.minecraft.resources.Identifier`** via
  `Identifier.fromNamespaceAndPath(...)` (not `ResourceLocation`).

## Where the file-specific gotchas live (inline comments)

- `widgets/BlockTopAnnulusOverlay.java`: immediate-mode rebuild, tuning knobs,
  `volatile` render-state fields, the double-sided-winding requirement, and the
  right-click trigger scope.
- `WorldOverlayManager.java`: the through-walls pipeline variant, the
  `CLIENT_STOPPING`-vs-mixin GPU-cleanup trade-off, the camera-relative
  translate, and the use-key-edge-vs-`UseItemCallback` debounce.
