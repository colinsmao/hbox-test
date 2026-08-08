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

## Settings

Config UI, persistence, and MaLiLib option types live in
[`settings.md`](settings.md). This file covers how overlays are drawn.

## HUD rendering

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
  (the current built-in widget) shows the flood radius near the crosshair for
  ~1.5 s after a shift+scroll change, fading out over the last 0.5 s.
  `show(...)` (client thread) writes `volatile` radius/expiry that
  `render`/`isVisible` (render thread) read.
- **GUI scale:** `Overlay` widgets lay out in GUI coordinates
  (`Window.getGuiScaledWidth` / `getGuiScaledHeight`, default `Font`) under
  `HudElementRegistry`, so they track Video Settings **GUI Scale** (including
  Auto) with the rest of the HUD — including the radius/profile toggle flash.
  The applied integer factor (framebuffer pixels per GUI unit) is
  `Window.getGuiScale()`; use it when converting between framebuffer and GUI
  space.

## In-world rendering

- Rendering is split into an **extraction** phase (read game state into an
  immutable snapshot) and a **drawing** phase (emit geometry). Register
  `LevelRenderEvents.END_EXTRACTION` and
  `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`. The drawing context is
  `LevelRenderContext` (`poseStack()`, `levelState().cameraRenderState.pos`).
- **Draw after translucent terrain — ice/glass/honey composite under the fill.**
  Translucent block bodies are already in the color buffer; the overlay then
  blends on top at full authored alpha (`FILL_ALPHA` etc.), with SKIRT on
  `DEBUG_FILLED_SNIPPET` depth state (LEQUAL, `writeDepth=false`) so opaque
  terrain still occludes. Water also writes depth in that translucent pass, so a
  pond bottom in the depth-tested SKIRT layer is hidden until crouch (depth-off
  `FILLED`). **Decision recorded:** prefer this single AFTER pass over (a)
  `BEFORE_TRANSLUCENT_TERRAIN` alone (ice/honey overdraw the fill) and (b)
  dual BEFORE+AFTER with scaled alpha (≈2× emit/upload/draw cost; fills read too
  faint). Through-water without crouch is the lower-priority case.
  - **Honey looks odd (two height layers).** Honey collides as an inset box topped
    at `15/16` and renders/outlines as a full-height translucent body. With
    `visualTopY` the standable paint sits on that outer shell while the true
    standable plane is the lower inset top — so the fill and honey body read as
    stacked layers. Same horizontal-inset Point-only note in
    [`geometry.md`](geometry.md). Left as-is.
  - **Vanilla crosshair block outline.** With a selection drawn, the targeted-block
    highlight can look like a translucent box (vanilla outline compositing over our
    half-alpha fill / after-translucent phase). Cosmetic only; fixing it would mean
    suppressing or re-timing vanilla's outline when the overlay is up — deferred
    unless it becomes a priority.
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
  (tops/borders through walls), a **depth-on `SKIRT`** layer (occluded by terrain),
  and a **depth-off `BEAM`** layer (beams when `showBeamsThroughWalls` is on,
  drawn last so opaque beams cover skirts; when off, beams share `SKIRT`),
  so `emit(matrix, fillBuffer, skirtBuffer)` writes into both and each layer
  batches into one draw call — the `MeshData` → `MappableRingBuffer` →
  render-pass GPU handoff (per layer), the use-key rising-edge dispatch, and GPU
  cleanup on
  `ClientLifecycleEvents.CLIENT_STOPPING` (chosen over a `GameRenderer#close`
  mixin to avoid mixin plumbing; trade-off: freed at shutdown, not on a
  mid-session renderer reload). `CollisionSurfaceOverlay` + `SurfaceEmitter`
  (below) exercise this framework.

## Block-hitbox rendering: `CollisionSurfaceOverlay` + `SurfaceSelection` + `SurfaceEmitter`

Draws the **standable surfaces** of blocks — the upward-facing collision faces an
entity of a chosen size can stand on — for a region the player selects with the
wand (General `wandItem`, default stick), where the region follows **walkable terrain** outward from the clicked
block. The geometry (occlusion-aware tops, entity-width dilation, the
output-sensitive flood) is computed by `SurfaceSelection` and documented in
[`geometry.md`](geometry.md); this section covers the **widget lifecycle** and
**`SurfaceEmitter` drawing**.

### Selection lifecycle (input → snapshot)

- **Wand is a trigger, not a brush.** Right-clicking (use-key rising edge) selects
  the surfaces reachable from the targeted block; right-clicking nothing clears. The
  selection persists across item switches until the next trigger or a level change.
  Draw visibility is sampled in `extract` into a `volatile` flag (General
  `showSurfaces` and related gates — see [`settings.md`](settings.md)); `isVisible()`
  is a field read on the draw path.
- **Either hand, main-first.** The wand works in the main **or** off hand. The
  acting hand is chosen main-first, falling back to the off hand **only when the main
  hand is empty** (or also a wand): a non-empty main hand is assumed to consume the
  right-click (place/use), so an off-hand wand doesn't also fire — approximating
  vanilla's "main acts first, off hand only if main did nothing" without an
  interaction-result mixin (the trigger is edge-detected off the use key, so the true
  result is unseen). The hand choice lives in `CollisionSurfaceOverlay.onUseItem`, not
  the manager: `WorldOverlay.onUseItem(Player)` takes no hand, so `WorldOverlayManager`
  stays agnostic to which item (the wand) a widget cares about.
- **Publish-on-action.** The drawn snapshot — an immutable `List<StandableRect>` from
  `SurfaceSelection.allRects()`, colored at draw so no per-rect tag — is
  (re)published into a `volatile` field on each wand action (select / clear / radius
  scroll / profile cycle). `extract` samples the visibility flag and crouch, and does the
  **level-identity reset** (a changed/`null` `Level` empties it, so world unload /
  dimension change / disconnect all reset it without a manager-side hook). Editing
  painted terrain needs a re-click (publish is action-driven).
- **Data source is the collision shape, not the visual shape:**
  `BlockState.getCollisionShape(level, pos, CollisionContext.empty())`. Empty shapes
  are pass-through (tall grass, flowers); `onUseItem` resolves the targeted block
  **downward** (`MutableBlockPos.move(0,-1,0)`, capped and floored at
  `level.getMinY()`) to the first non-empty shape.
- **Entity profiles + `reach`.** An `EntityProfile(name, width, height, reach)`
  selects the entity the flood/dilation/headroom use (`width` drives dilation,
  `height` headroom, `reach` the step threshold); the roster, values, and examples
  live in [`geometry.md`](geometry.md) and are chosen from settings. The active
  profile is `Configs.mobProfile()` (Generic `mobProfile` option, default
  **Player**). Changing it in the config GUI re-floods an active selection. When
  Debug `crouchCycleProfile` is on, **sneak + right-click at nothing** clears *and*
  advances that option, then pings the HUD with the new name.
- **Adjustable flood radius (shift+scroll).** Gated by Debug
  `crouchScrollRadius` (default on; see [`settings.md`](settings.md)). When on,
  `MobWalkClient` registers `ClientHotbarScrollEvents.ALLOW` and, **only while
  holding the wand (in either hand) and sneaking**, changes General `floodRadius`
  via `Configs.setFloodRadius` (`adjustRadius`), re-floods from the last seed so the
  change is immediate, and returns `false` to cancel the vanilla slot change. When the
  option is off, that gesture is inactive — scroll never changes the radius.
  The radius is clamped `[0, 30]` and steps by **1 up to 10, then by 2** (`12, 14, …,
  30`) — coarse steps keep the high end usable.
  Each change pings `RadiusIndicatorOverlay`. The live option updates immediately;
  JSON is flushed on play disconnect (and on config-screen close).
- **Threading.** The selection is computed only on the client/extraction thread
  (`select`/`clear`); the render thread reads only the immutable snapshot via the
  `volatile` handoff (same pattern as the other widgets). `SurfaceSelection` holds the
  result list — each action recomputes from scratch. `SurfaceEmitter` receives those
  published lists as args and never reads the live compute lists.

### Per-surface drawing (`SurfaceEmitter.emit`)

Each reached `StandableRect` is drawn as a **top fill**, an optional **border**, and
**skirts**, split across the two `WorldOverlayManager` pipelines (depth-off `FILLED`
through-walls, depth-on `SKIRT` occluded). `CollisionSurfaceOverlay.emit` forwards
the published snapshots into `SurfaceEmitter.emit`.

- **Top fill color precedence.** Tops and skirts share one frame fill palette
  (`Palette.FillColors`, hoisted once in `emit` and passed into `emitSkirts`).
  Precedence, highest first: frontier grey (`Palette.GREY_RGB`, walkable alpha) →
  Debug `shadeByDepth` hue (`Palette.depthColor`, walkable alpha) → hazard Color4f
  when that kind’s Appearance show is on (`showWaterHazard` / `waterHazardColor`,
  `showLavaHazard` / `lavaHazardColor`, `showSoulSandHazard` / `soulSandHazardColor`,
  `showMagmaHazard` / `magmaHazardColor`) → Appearance `walkableColor`. Show off keeps
  the surface drawn but uses `walkableColor`; `HazardClass` on the rect is unchanged.
  Frontier greying applies when Debug `showCutoffRing` is on (default); when off,
  frontier tops are not drawn.
- **Crouch-gated through-walls, depth-tested by default.** Gated by Debug
  `crouchSeeThroughWalls` (default on; see [`settings.md`](settings.md)). Seeing
  surfaces *through* blocks is a debug aid, so when the option is on the top is
  routed per-frame: **while sneaking** it goes into the depth-off `FILLED`
  pipeline (`withDepthStencilState(Optional.empty())`) and draws through walls
  (along with the borders); **otherwise** into the depth-on `SKIRT` pipeline,
  occluded by terrain like a real surface. When the option is off, tops stay
  depth-tested and crouch borders stay off.
- **Border (debug, crouch-only).** A thin opaque border (4 clamped edge strips, both
  windings) separates adjacent surfaces and the sub-rects of one block (e.g. the
  4-rect ring around a fence post). It clutters the normal view, so it draws **only
  while sneaking** when `crouchSeeThroughWalls` is on (`crouching`, sampled in
  `extract` — same gate as through-walls tops).
- **Skirts: square, fading, depth-tested.** Each edge drops a double-winding vertical
  skirt of height Appearance `downSkirtHeight` (default `2`; `0` skips draw), dimmed
  by vanilla face brightness (N/S 0.8, E/W 0.6), **fading linearly from the resolved
  fill alpha at the rim to transparent at the far end of the Appearance height**.
  `SkirtSpan` carries the source rect’s `HazardClass` so skirts resolve through the
  same precedence as tops. Draw length is
  `min(Appearance height, maxExtent)`; when `maxExtent` clips the quad shorter than
  the configured height, the tip samples the same fade curve and keeps residual alpha
  (e.g. extent 0.5 with height 2 ends at 0.75× fill alpha). Skirts always live in the
  depth-tested `SKIRT` layer, so a step reads as a riser, a real drop as an open wall,
  and interior skirts on solid-backed floors self-hide. (A **translucent** block writes
  no depth and so doesn't occlude a skirt — accepted limitation.) Tops/borders draw at
  **true rect bounds**; only the skirts are nudged out by a tiny `SKIRT_OFFSET` (0.002,
  square) to dodge z-fighting the coplanar terrain face (`Y_OFFSET` is likewise 0.002).
  *Skirts are square, not splayed: a trapezoidal/dilated skirt clips the block's upper
  edge and re-introduces overlap.*
- **Skirt-diff, computed compute-side (`DownSkirts.compute` / down `SkirtSpan`).** Any
  rectangle partition of a holed / L-shaped level has *internal* edges between
  equal-height pieces; a skirt there is a **false interior wall** (and depth-testing
  can't hide it where it overhangs air near a hole). So each edge is skirted only over
  the sub-spans **not** covered by an equal-height neighbour abutting across it (a
  per-edge 1-D interval subtraction), **and** minus the wall/ceiling occluder sub-spans
  on that edge (they get an upward skirt instead). Partial sharing is handled (a big
  rect's edge shared with a sliver over only part of its length skirts just the unshared
  remainder); true drops / hole outlines / unshared remainders keep their skirts.
  Abutting **lower** reached surfaces set `maxExtent` on those leftovers (gap to the
  lower face); open drops stay unlimited. It is computed **compute-side once per
  select** (`DownSkirts.compute`) and published as down `SkirtSpan`s
  (edge orientation, side, line, `[lo,hi]`, base `T`, `maxExtent`), which `emit` just
  draws — one pass per select keeps large/bumpy selections smooth (the alternative, an
  `O(n²)` `openSpans` scan over the merged rects every frame, hitches). This is the
  shared drop-edge pass the hole classifier plugs into (a drop span is a hole
  candidate).
- **Upward (occluder) skirts — wall-vs-drop classification.** A
  surface edge bordering a **wall** (box rising above the rim) or **ceiling**
  (overhang in headroom) is not a drop, so it gets an **upward** skirt instead.
  Occluders use the same dual rim as downs (collision for holes, visual for paint;
  `maxExtent = wallTop − rim`). Compute-side once per wand (`OccluderSkirts.compute`);
  published UPs are the visual-rim list when raises are active. Downs subtract the
  matching-rim occluder spans so an edge is never double-skirted; `emit` draws both
  via `emitSkirts` (`length = min(Appearance height, maxExtent)`). Spans carry
  orientation, **side**, dilated edge line, `[lo,hi]`, base `T`, and `maxExtent`.
  Markers sit at the **dilated (set-back) edge**. This wall-vs-drop split is the
  **prerequisite for hole detection**.
- **Upward (occluder) skirts.** Drawn from published up `SkirtSpan`s at Appearance
  `upwardSkirtHeight` (default `0.25`; `0` skips draw), clamped to `maxExtent` (wall
  available above the surface). Same single-quad fade as down skirts (peak =
  walkable fill alpha at the surface; tip samples the Appearance-height curve when
  clipped). Same depth-tested `SKIRT` layer; nudged toward the surface interior by
  `SKIRT_OFFSET`.
- **Flood geometry debug dump (`/mobwalk dump`).** Client chat command. With a wand
  selection active it re-runs `select` once with a one-shot flag, writes a single
  `[flood-debug]` block to `MobWalk.LOGGER` (header → reached → merged → occluders →
  downskirts → holes → hazards), and posts a short chat summary (`merged=… occluders=…
  skirts=… holes=… hazards=… (see latest.log)`). Raised selections log `occluders-collision`
  and `occluders-paint`; otherwise one `occluders` list. Empty selection: chat
  `flood-debug: no selection`. Armed by `/mobwalk dump`.
- **Surface-height toggle (Appearance `drawOnVisibleFace`, default on).** Blocks that
  render taller than they collide (soul sand, mud, cactus, honey) would otherwise draw
  their standable top *buried* at the collision height. The Appearance boolean
  `drawOnVisibleFace` (default **on** — the fix) controls it. It is a
  **compute-side** flag: `Configs.drawOnVisibleFace()` is passed into `select` as
  `computeVisualTop`, where the visible top is gathered (gated on it, memoized per
  `BlockState` — see [`geometry.md`](geometry.md) "Visible-face top vs collision top")
  and **written into each rect's `visualTopY` / span `visualBaseY`**. `emit` then
  *always* draws the top fill, borders, and the up/down/hole skirts at that render
  height — which equals the collision top when the setting is off (the raise isn't
  computed then). Draw color is unchanged by the toggle. Because the flag gates the compute, a
  value-change callback (`Configs.initCallbacks`) **re-floods** from the last seed via
  `reselectWithMobProfile` (cheap: toggling is rare). This is the one Appearance option
  that touches compute — an accepted exception to "Appearance is draw-only," since the
  raise is inherently a compute-side read. All walkability math is unaffected
  (collision-top only); this is purely where the paint is drawn.
- **Depth-based grey cutoff (incomplete-selection signal).** When Debug
  `showCutoffRing` is on (default), surfaces on the merge **frontier** band
  (`StandableRect.frontier()`, the `RadiusTier.FRONTIER` ownership class at the
  BFS depth limit) are drawn **fully grey** (`Palette.GREY_RGB`). INNER
  geometry keeps the normal fill precedence (hazard color / `walkableColor`, or
  `shadeByDepth` hue). When off,
  frontier tops are not drawn. Skirt and hole spans on frontier rects are always
  suppressed compute-side — they are cutoff artifacts, not real geometry.
- **Beams (holes + hazard perimeters).** Vertical beams rise from a published
  `BeamSpan`'s `visualBaseY`. Appearance `showBeamsThroughWalls` (default on) routes
  every beam kind to a dedicated depth-off `BEAM` layer (same pipeline as crouch-gated
  tops) **after** the depth-tested skirts, so opaque beams cover skirts and read through
  terrain; when off, beams go into the depth-tested `SKIRT` layer and are occluded by
  blocks. Beams rise a fixed world height (`BEAM_HEIGHT`). `BeamSpan.hazard` selects
  Appearance color/toggle at emit (`Palette.FillColors.beamColor`):
  - **`HOLE`** — trap drop edges from `HoleBeams.compute` (+ `holeSubSpans`). Appearance
    `showHoleBeams` / `holeBeamColor`. Benign drops keep their ordinary down-skirt and
    get no beam. Frontier drop spans (`SkirtSpan.frontier()`) are skipped — without
    that, the entire perimeter drew a hole-beam wall. Because one edge can span reached
    and unreached ground, `holeSubSpans` subdivides and emits only over unreached
    sub-intervals. One beam per hole edge-span (deliberately not coalesced per region).
  - **`WATER` / `LAVA` / `SOUL_SAND` / `MAGMA`** — hazard perimeter from the same
    `HazardBeams.compute` path (non-frontier rect edge minus same-hazard
    equal-`collisionTopY` abutters; no occluder subtract). Fluids use swimmable pool
    footprints; solid hazards use post-punch / occlusion-trimmed footprints (stone-side
    seam on the **block** edge, void-side cliff lip on the dilated rim, soul sand on the
    cut-back edge against a taller wall). Gated by each kind’s existing show/color pair
    (`showWaterHazard` / `waterHazardColor`, and the lava / soul-sand / magma analogues;
    no separate `showHazardBeams`). Show off skips that kind’s beams and falls fill back
    to `walkableColor`. Different kinds abutting keep both faces (e.g. water|lava).
    Pool-rim **hole** beams (trap drops at a high shore) stay `holeBeamColor`;
    fluid-under-fall recolor is backlog.

  `CollisionSurfaceOverlay` publishes a `volatile` hazard list beside holes;
  `SurfaceEmitter.emitBeams` draws both lists via `emitBeam`.

## `26.1.2` rendering API names (verified against the resolved jars)

- Draw context is **`net.minecraft.client.gui.GuiGraphicsExtractor`** (not
  `GuiGraphics`).
- Text is drawn with **`text(Font, String, x, y, color, dropShadow)`** (not
  `drawTextWithShadow`/`drawString`).
- Identifiers are **`net.minecraft.resources.Identifier`** via
  `Identifier.fromNamespaceAndPath(...)` (not `ResourceLocation`).

## Where the file-specific gotchas live (inline comments)

- `surface/CollisionSurfaceOverlay.java`: the downward resolution + cap, the
  right-click trigger (select/clear) + gated sneak-cycle of the active profile, the
  runtime radius + re-flood (`wantsRadiusScroll`/`adjustRadius`), the
  publish-on-action snapshot, the level-identity reset, and the `volatile`
  snapshot/occluder/down-skirt/hole/hazard/crouch handoff into
  `SurfaceEmitter`.
- `surface/SurfaceEmitter.java`: crouch-gated through-walls tops + borders, the
  fill precedence (`Palette.FillColors` / `resolve`: frontier grey → depth hue →
  hazard show+color → `walkableColor`), the square fading
  skirt draw (`vQuad`, tiny `SKIRT_OFFSET`), drawing the **published** skirt spans
  (`emitSkirts`, from `SkirtSpan` UP/DOWN lists; length `min(Appearance height,
  maxExtent)`; fade over Appearance height so a clip keeps tip alpha; frontier spans
  suppressed), and **beams** (`emitBeams` / `emitBeam`, from hole + hazard `BeamSpan`
  lists; `beamColor` by `HazardClass`; depth-off `BEAM` or depth-tested `SKIRT` per
  `showBeamsThroughWalls`) — emit draws only the published spans — and the
  double-sided-winding requirement.
- `overlay/RadiusIndicatorOverlay.java`: the timer-gated visibility + fade and the
  `volatile` show/render thread handoff.
- `MobWalkClient.java`: the `ClientHotbarScrollEvents.ALLOW` wiring (wand+sneak
  gate, cancels the hotbar slot change) — the composition root that connects the
  scroll input to the flood-radius option and the HUD indicator — and the
  `/mobwalk dump` client command (`ClientCommandRegistrationCallback` →
  `CollisionSurfaceOverlay.dumpFloodDebug` → `sendSystemMessage` chat summary).
- `RectMath.java`: pure rect/interval algebra — guillotine `subtractRects`,
  `union` re-cut + strip-merge, depth-aware
  **`mergeCoplanarSplitFrontier`** (one composite `(radiusTier, surfaceClass)`
  priority partition per collision band; INNER owns frontier overlap),
  `footprintAdjacent`, `crossesLine` (a rect reaches across an edge line onto its far
  side — the fall-column predicate),
  `subtractIntervals`, `intersectRect` (unit-tested; production merge-after-flood
  uses `mergeCoplanarSplitFrontier`).
- `WorldGeometry.java`: adapter over the `ColumnBoxes` port — Minecraft
  block/fluid state → domain `WorldBox` / `HazardClass` (`levelColumnBoxes`,
  `fluidSurfaceHeight`, `visibleTop` memo).
- `SurfaceSelection.java`: the output-sensitive `LazyFlood` (depth-bounded surface
  BFS, on-demand column + row exposure via `ensureRows`, per-box `exposeBox` memo,
  the `occluderColumns` shell, `floor(W)+1` neighbour reach, merge-after-flood via
  `RectMath.mergeCoplanarSplitFrontier`), dilation + **headroom** occlusion in
  `exposeBox` (the `(T, T+H]` standing-column predicate, calling
  `RectMath.subtractRects`), orchestration of the peer passes below, and the
  extraction-thread-only (non-thread-safe) contract.
  **The geometry/algorithm lives in [`geometry.md`](geometry.md); read it first.**
- `OccluderSkirts.java`: compute-side UP skirt classification (`compute` /
  `occluderSpansForRect` / `wallOccluder` / `mergeOccluderSpans`), published as up
  `SkirtSpan`s.
- `DownSkirts.java`: compute-side DOWN skirts (`compute` / `edgeDownSpans` /
  land-clamped `maxExtent`), published as down `SkirtSpan`s.
- `HoleBeams.java`: hole classification (`classifyDrop` — pure: HOLE unless a reached
  surface lies strictly below the rim across the `FallColumn`, and then HOLE anyway if
  a standable **ledge** sits between the rim and that floor; reachability is reached-set
  membership, the ledge scan reuses `exposeBox` — and `compute` / `gatherLedges` /
  `holeSubSpans`; frontier drops (`SkirtSpan.frontier()`) skipped; subdivided at
  the line-crossing reached rects' bounds and published as `BeamSpan` with
  `hazard = HOLE`).
- `HazardBeams.java`: hazard perimeter beams (`compute` / `edgeSpans` — non-frontier
  fluid or solid-hazard rect edge minus same-hazard equal-`collisionTopY` abutters;
  no occluder subtract; published as `BeamSpan` with `WATER` / `LAVA` / `SOUL_SAND` /
  `MAGMA`).
- `WorldOverlayManager.java`: three-layer setup (depth-off `FILLED` tops,
  depth-on `SKIRT`, depth-off `BEAM` last), single draw at `AFTER_TRANSLUCENT_TERRAIN` (ice/honey
  composite; pond bottoms via crouch — see the translucent-phase decision),
  per-layer buffer/GPU handoff, the through-walls debug aid, the
  `CLIENT_STOPPING`-vs-mixin GPU-cleanup trade-off, the camera-relative
  translate, and the use-key-edge-vs-`UseItemCallback` debounce.
