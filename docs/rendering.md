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
  mid-session renderer reload). `CollisionSurfaceOverlay` (Milestone 3, below)
  is the widget that currently exercises this framework.

## Block-hitbox rendering (Milestone 3): `CollisionSurfaceOverlay` + `SurfaceCache`

Draws the **horizontal collision surface** of blocks — the upward-facing faces
an entity can stand on — for blocks "painted" with a stick.

- **Data source is the collision shape, not the visual shape:**
  `BlockState.getCollisionShape(level, pos, CollisionContext.empty())`. Empty
  shapes are pass-through (tall grass, flowers); `extract` walks **downward**
  (`MutableBlockPos.move(0,-1,0)`, capped and floored at `level.getMinY()`)
  until a non-empty shape is found, so looking at tall grass resolves to the
  block beneath it.
- **Rendering strategy (deliberately simple):** emit the top (`maxY`) face of
  **every** `VoxelShape.toAabbs()` sub-box and let the `FILLED` pipeline's depth
  test occlude the faces buried inside real geometry. No per-(x,z)-column
  max-height resolution — stairs come out as an L for free; rare blocks with
  internally stacked boxes are out of scope.
- **World-space doubles, not a 1/16 grid:** each top face is a `StandableRect`
  (`record StandableRect(double minX, minZ, maxX, maxZ, topY)`) in absolute
  world coords (the resolved `BlockPos` folded in). Quantizing is skipped on
  purpose — a planned later step expands these by entity dimensions (e.g. ravager
  `1.95`) that are not `1/16`-aligned, so rounding/precision is deferred to that
  future math layer.
- **`SurfaceCache` is both the brush selection set and the compute-cache:** a
  `BlockPos -> {BlockState, List<StandableRect>}` map. While holding the stick,
  `extract` adds the resolved hovered block (compute-once; already-present
  blocks with an unchanged `BlockState` are not recomputed), so sweeping
  accumulates cheaply; every entry's rects are the draw set. It is **in-memory
  only, not persisted.** Each `extract` prunes/recomputes entries whose stored
  `BlockState` no longer matches the world (place/break staleness).
- **Lifecycle / threading:** the cache is mutated only on the client/extraction
  thread (`add`/`pruneStale`/`clear`); the render thread reads only the
  immutable `allRects()` snapshot published into a `volatile` field (same handoff
  as the other widgets). It is cleared by right-click (`onUseItem`) and by a
  **level-identity check** in `extract` (a changed/`null` `Level` empties it) —
  a self-contained alternative to a manager-side world-unload hook, so world
  unload / dimension change / disconnect all reset it. Note: right-click resets
  but, while the stick stays held, the next frame re-adds the hovered block, so
  a reset leaves at most the block under the crosshair.

## `26.1.2` rendering API names (verified against the resolved jars)

- Draw context is **`net.minecraft.client.gui.GuiGraphicsExtractor`** (not
  `GuiGraphics`).
- Text is drawn with **`text(Font, String, x, y, color, dropShadow)`** (not
  `drawTextWithShadow`/`drawString`).
- Identifiers are **`net.minecraft.resources.Identifier`** via
  `Identifier.fromNamespaceAndPath(...)` (not `ResourceLocation`).

## Where the file-specific gotchas live (inline comments)

- `widgets/CollisionSurfaceOverlay.java`: the downward resolution + cap, the
  brush-as-insert flow, the level-identity reset, `volatile` snapshot handoff,
  the double-sided-winding requirement, and the right-click-reset trigger scope.
- `SurfaceCache.java`: the get-or-compute insert, `BlockState`-based staleness
  prune, the extraction-thread-only (non-thread-safe) contract, and the
  collision-shape → `StandableRect` build.
- `WorldOverlayManager.java`: the through-walls pipeline variant, the
  `CLIENT_STOPPING`-vs-mixin GPU-cleanup trade-off, the camera-relative
  translate, and the use-key-edge-vs-`UseItemCallback` debounce.
