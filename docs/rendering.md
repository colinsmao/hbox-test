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
- **Transient widgets** gate `isVisible()` on a timer: `RadiusIndicatorOverlay`
  (the current built-in widget; it replaced the original demo box) shows the
  flood radius near the crosshair for ~1.5 s after a shift+scroll change, fading
  out over the last 0.5 s. `show(...)` (client thread) writes `volatile`
  radius/expiry that `render`/`isVisible` (render thread) read.

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
  `WorldOverlayManager` owns the `LevelRenderEvents` phases, **two** shared
  pipelines each with its own `BufferBuilder` — a **depth-off `FILLED`** layer
  (draws through walls) and a **depth-on `SKIRT`** layer (occluded by terrain),
  so `emit(matrix, fillBuffer, skirtBuffer)` writes into both and each layer
  batches into one draw call — the `MeshData` → `MappableRingBuffer` →
  render-pass GPU handoff (per layer), the use-key rising-edge dispatch, and GPU
  cleanup on
  `ClientLifecycleEvents.CLIENT_STOPPING` (chosen over a `GameRenderer#close`
  mixin to avoid mixin plumbing; trade-off: freed at shutdown, not on a
  mid-session renderer reload). `CollisionSurfaceOverlay` (Milestone 3, below)
  is the widget that currently exercises this framework.

## Block-hitbox rendering (Milestone 3): `CollisionSurfaceOverlay` + `SurfaceSelection`

Draws the **standable surfaces** of blocks — the upward-facing collision faces
an entity can stand on — for a region the player selects with a stick, where the
region follows **walkable terrain** outward from the clicked block.

- **Stick is a trigger, not a brush.** Right-clicking (use-key rising edge)
  selects the surfaces reachable from the targeted block; right-clicking nothing
  clears. The selection persists across item switches (drawn only while the stick
  is held) until the next trigger or a level change. **Publish-on-action:** the
  snapshot is (re)published on each stick action (select / clear / radius scroll
  / profile cycle); `extract` no longer mutates the selection at all — it only
  does the level-identity reset (the old per-frame `pruneStale` was removed, so
  editing painted terrain needs a re-click).
- **Data source is the collision shape, not the visual shape:**
  `BlockState.getCollisionShape(level, pos, CollisionContext.empty())`. Empty
  shapes are pass-through (tall grass, flowers); `onUseItem` resolves the
  targeted block **downward** (`MutableBlockPos.move(0,-1,0)`, capped and floored
  at `level.getMinY()`) to the first non-empty shape, so looking at tall grass
  resolves to the block beneath it.
- **Surface-indexed walkable flood (`SurfaceSelection.select`).** The flood unit is a
  single standable **surface** (`StandableRect`), not a block, so a stair is two
  nodes (tread + top) and stacked surfaces (spiral staircases, overhangs) stay
  distinct. From a surface at top `T` the flood considers the exposed surfaces of
  the same block (siblings), the 4 neighbor columns, and its **own** column,
  within a bounded vertical window (`topY` in `[T - reach, T + reach]`). An
  edge requires both the height gate (`|T2 - T| <= reach`) **and** footprint
  adjacency (the two rects share an edge with positive overlap), so a partial
  surface only connects on a side it physically touches. `reach` is the active
  **`EntityProfile`**'s single symmetric threshold (see **Profiles** below); it
  replaced the old fixed `MAX_STEP`.
- **Own-column (vertical) edges for partial-footprint blocks.** A glass pane /
  fence / wall sitting on a block leaves a matching hole in that block's top
  (occlusion), so the block's floor abuts the partial block's footprint **at the
  hole edges** — but they live in the same column, one above the other, not a
  horizontal neighbor. So the flood also scans   the surface's own column (skipping
  same-block siblings, which are handled for free) and connects vertically when
  footprint-adjacent and within `reach`. This is why a pane connects to the
  block directly below it.
- **0-1 BFS for the radius budget.** A same-block sibling step is free
  (weight 0, pushed to the deque front); a neighbor-column step costs one
  transition (weight 1, pushed to the back). Finalizing a surface on its first
  pop yields its shortest block-distance (`depth`), which bounds neighbor
  expansion at the current flood radius. (`depth` is no longer surfaced for
  coloring — see height gradient below.)
- **Occlusion-aware extraction (`exposedSurfaces`) replaces "emit every top".** A
  sub-box top at height `h` is standable only over the footprint where nothing
  solid sits **directly above** it: it is clipped (guillotine rectangle
  subtraction, `subtractRects`) by same-block boxes spanning `h` and by the block
  above (shifted up by 1, only relevant at `h == 1.0`). So a stair's bottom-slab
  top becomes just its exposed front strip (not the buried back half), and a
  block directly under another exposes no top. This both **de-ghosts the flood**
  (kills the stair-back "ghost step": you cannot walk up a stair's tall back as a
  0.5 step) **and de-ghosts rendering** (only genuinely exposed tops are drawn).
  Headroom beyond the immediately-above cell is intentionally ignored
  (entity-height headroom is deferred). Walkable-only: hole detection, fall
  tracing, region outlines, and asymmetric up/down steps are deferred.
- **Per-surface draw = top (fill + outline) into the `FILLED` layer, skirt into
  the `SKIRT` layer.** `emit` draws each `StandableRect`'s top as a half-alpha
  fill plus a thin opaque border (4 clamped edge strips, both windings), so
  neighboring surfaces — and the several sub-rects a single block can produce
  (e.g. the 4-rect ring around a fence post) — stay visually separable. It then
  drops a **vertical skirt** from every edge into the depth-tested layer (see
  skirts below).
- **Adjustable flood radius (shift+scroll).** The radius is a mutable field on
  `CollisionSurfaceOverlay` (clamped `[0, 10]`). `OverlayClient` registers
  `ClientHotbarScrollEvents.ALLOW` (Fabric API, the official hotbar-scroll hook —
  no mixin needed) and, **only while holding the stick and sneaking**, changes
  the radius by the scroll direction, re-floods from the last seed so the change
  is immediate, and returns `false` to cancel the vanilla hotbar slot change.
  Plain scroll (or scroll without the stick) is left untouched. Each change pings
  `RadiusIndicatorOverlay` (below).
- **Entity profiles + `reach` (foundation for size-aware surfaces).** An
  `EntityProfile(name, width, reach)` selects how the flood/geometry treat entity
  size. Three ship, cycled in order **Point** (`width 0`, the default — dilation
  no-op, reproduces the point-walker) → **Player** (`0.6`) → **Ravager**
  (`1.95`); `reach` (default `1.0`) is the single symmetric height threshold used
  by the flood. **No keybind:** the cycle rides the use-key dispatch — **sneak +
  right-click at nothing** clears *and* advances the profile, then pings the HUD
  with the new name. The `profile` field is `volatile` (read in `emit`). `width`
  is carried but unused until the dilation work lands.
- **Height-gradient color.** Each surface is tinted by its `topY` mapped across
  the selection's `[minTopY, maxTopY]` range (single color when flat), so
  elevation and drops are readable. This replaced the old flood-distance HSV ramp
  (the `distance`/`DistancedRect` plumbing is gone).
- **Two depth regimes (debug tops, real skirts).** The `FILLED` pipeline still
  disables the depth test (`withDepthStencilState(Optional.empty())`) so **tops
  draw through walls** — making any buried/buggy surface visible. The `SKIRT`
  pipeline keeps the snippet's **default depth test on**, so skirts are occluded
  by terrain: a step reads as a riser, a real drop as an open wall, and interior
  skirts on solid-backed floors self-hide. (A **translucent** solid block writes
  no depth, so it does not occlude a skirt — accepted limitation.)
- **Skirts: fading, dilated.** Each edge drops a double-winding vertical skirt of
  depth `reach + SKIRT_MARGIN` (~2), darker-shaded, **solid over its top half and
  fading to transparent over the bottom half** so a deep drop doesn't read as a
  hard floating wall. For drawing only, each rect is **dilated outward by
  `SKIRT_OFFSET` (0.01)** — top, border, and skirts share the grown bounds — so a
  rect's top meets its own skirts and neighbors overlap instead of sharing a
  coincident skirt edge; this removes top-to-skirt gaps and adjacent-edge
  z-fighting. (Renderer-only; independent of the future entity-width dilation.)
- **World-space doubles, not a 1/16 grid:** each surface is a `StandableRect`
  (`record StandableRect(double minX, minZ, maxX, maxZ, topY)`) in absolute
  world coords (the resolved `BlockPos` folded in). Edge/overlap compares are
  epsilon-tolerant (`EPS = 1e-6`). The representation and the rect-space (not
  pixel-raster) decision behind it live in [`geometry.md`](geometry.md).
- **`SurfaceSelection` is both the selection set and the compute-cache:** a
  `BlockPos -> {BlockState, List<StandableRect>}` map. `select` floods
  and `add`s each reached block's exposed surfaces (compute-once; already-present
  blocks with an unchanged `BlockState` are not recomputed). Storage stays
  **block-keyed**: reaching any one surface stores the whole block's exposed
  rects. With occlusion + footprint edges a block's exposed surfaces are normally
  one connected patch, so this matches the reached set; the rare block with two
  *disjoint* exposed surfaces where only one is reached over-renders the other
  (noted, acceptable). In-memory only, not persisted.
- **No staleness path.** `pruneStale` was **removed**: it only revisited blocks
  already in the map (dropping a broken block's rect but never adding the newly
  exposed block beneath), so it was half a feature and brittle. The selection now
  mutates only on stick actions; editing painted terrain requires a re-click.
- **Lifecycle / threading:** the cache is mutated only on the client/extraction
  thread (`select`/`add`/`clear`); the render thread reads only the immutable
  `allRects()` snapshot (a `List<StandableRect>` — height-tinted at draw, so no
  per-rect tag) published into a `volatile` field (same handoff as the other
  widgets). It is reset by a clearing right-click (`onUseItem`) and by a
  **level-identity check** in `extract` (a changed/`null` `Level` empties it) — a
  self-contained alternative to a manager-side world-unload hook, so world unload
  / dimension change / disconnect all reset it.

## `26.1.2` rendering API names (verified against the resolved jars)

- Draw context is **`net.minecraft.client.gui.GuiGraphicsExtractor`** (not
  `GuiGraphics`).
- Text is drawn with **`text(Font, String, x, y, color, dropShadow)`** (not
  `drawTextWithShadow`/`drawString`).
- Identifiers are **`net.minecraft.resources.Identifier`** via
  `Identifier.fromNamespaceAndPath(...)` (not `ResourceLocation`).

## Where the file-specific gotchas live (inline comments)

- `widgets/CollisionSurfaceOverlay.java`: the downward resolution + cap, the
  right-click trigger (select/clear) + sneak-cycle of the active profile, the
  runtime radius + re-flood (`wantsRadiusScroll`/`adjustRadius`), the
  publish-on-action snapshot, the fill+border top draw (`quad`) plus the
  fading/dilated skirt draw (`fadedSkirt`/`vQuad`, `SKIRT_OFFSET`), the
  height-gradient color (`heightColor`), the level-identity reset, `volatile`
  snapshot/profile handoff, and the double-sided-winding requirement.
- `widgets/RadiusIndicatorOverlay.java`: the timer-gated visibility + fade and the
  `volatile` show/render thread handoff.
- `OverlayClient.java`: the `ClientHotbarScrollEvents.ALLOW` wiring (stick+sneak
  gate, cancels the hotbar slot change) — the composition root that connects the
  scroll input to the world overlay's radius and the HUD indicator.
- `SurfaceSelection.java`: the surface-indexed 0-1 BFS (sibling=free, neighbor=+1),
  the own-column vertical edge (partial-footprint block to the block below),
  occlusion-aware `exposedSurfaces` + guillotine `subtractRects`, the
  footprint-adjacency edge test + profile-`reach` height gate, the bounded
  vertical-window column scan, and the extraction-thread-only (non-thread-safe)
  contract.
- `WorldOverlayManager.java`: the two-layer setup (depth-off `FILLED` tops +
  depth-on `SKIRT`) and per-layer buffer/GPU handoff, the through-walls debug
  aid, the `CLIENT_STOPPING`-vs-mixin GPU-cleanup trade-off, the camera-relative
  translate, and the use-key-edge-vs-`UseItemCallback` debounce.
