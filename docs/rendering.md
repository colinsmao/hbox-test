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
  mid-session renderer reload). `CollisionSurfaceOverlay` (below)
  is the widget that currently exercises this framework.

## Block-hitbox rendering: `CollisionSurfaceOverlay` + `SurfaceSelection`

Draws the **standable surfaces** of blocks — the upward-facing collision faces an
entity of a chosen size can stand on — for a region the player selects with a
stick, where the region follows **walkable terrain** outward from the clicked
block. The geometry (occlusion-aware tops, entity-width dilation, the
output-sensitive flood) is computed by `SurfaceSelection` and documented in
[`geometry.md`](geometry.md); this section covers the **widget and its drawing**.

### Selection lifecycle (input → snapshot)

- **Stick is a trigger, not a brush.** Right-clicking (use-key rising edge) selects
  the surfaces reachable from the targeted block; right-clicking nothing clears. The
  selection persists across item switches (drawn while the stick is held in **either
  hand**) until the next trigger or a level change.
- **Either hand, main-first.** The stick works in the main **or** off hand. The
  acting hand is chosen main-first, falling back to the off hand **only when the main
  hand is empty** (or also a stick): a non-empty main hand is assumed to consume the
  right-click (place/use), so an off-hand stick doesn't also fire — approximating
  vanilla's "main acts first, off hand only if main did nothing" without an
  interaction-result mixin (the trigger is edge-detected off the use key, so the true
  result is unseen). The hand choice lives in `CollisionSurfaceOverlay.onUseItem`, not
  the manager: `WorldOverlay.onUseItem(Player)` takes no hand, so `WorldOverlayManager`
  stays agnostic to which item (the stick) a widget cares about.
- **Publish-on-action.** The drawn snapshot — an immutable `List<StandableRect>` from
  `SurfaceSelection.allRects()`, height-tinted at draw so no per-rect tag — is
  (re)published into a `volatile` field on each stick action (select / clear / radius
  scroll / profile cycle). `extract` only does the
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
  holding the stick (in either hand) and sneaking**, changes the radius by the
  scroll direction (`adjustRadius`), re-floods from the last seed so the change
  is immediate, and returns `false` to cancel the vanilla slot change. When the
  option is off, that gesture is inactive — scroll never changes the radius.
  The radius is clamped `[0, 30]` and steps by **1 up to 10, then by 2** (`12, 14, …,
  30`) — the window grows quadratically, so coarse steps keep the high end usable.
  Each change pings `RadiusIndicatorOverlay`.
- **Threading.** The selection is computed only on the client/extraction thread
  (`select`/`clear`); the render thread reads only the immutable snapshot via the
  `volatile` handoff (same pattern as the other widgets). `SurfaceSelection` holds the
  result list — each action recomputes from scratch.

### Per-surface drawing (`emit`)

Each reached `StandableRect` is drawn as a **top fill**, an optional **border**, and
**skirts**, split across the two `WorldOverlayManager` pipelines (depth-off `FILLED`
through-walls, depth-on `SKIRT` occluded).

- **Top fill, Appearance-colored (or depth-hue debug).** Tops use Appearance
  `walkableColor` (RGB + alpha) by default. Debug `shadeByDepth` (default off)
  switches RGB to the cyclic BFS-depth hue band (`depthColor`, blue at depth 0,
  cycling every `DEPTH_CYCLE` (20) rings) so a continuity bug reads as an
  out-of-sequence color. Cutoff ring greying (`greyBlend`) applies in both modes when
  Debug `showCutoffRing` is on (default); when off, those ring depths are not drawn.
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
  skirt of height Appearance `downSkirtHeight` (default `2`; `0` skips draw), darker,
  **solid over its top half and fading to transparent over the bottom half** so a deep
  drop doesn't read as a hard floating wall. Skirts always live in the depth-tested
  `SKIRT` layer, so a step reads as a riser, a real drop as an open wall, and interior
  skirts on solid-backed floors self-hide. (A **translucent** block writes no depth and
  so doesn't occlude a skirt — accepted limitation.) Tops/borders draw at **true rect
  bounds**; only the skirts are nudged out by a tiny `SKIRT_OFFSET` (0.002, square) to
  dodge z-fighting the coplanar terrain face (`Y_OFFSET` is likewise 0.002). *Skirts are
  square, not splayed: a trapezoidal/dilated skirt clips the block's upper edge and
  re-introduces overlap.*
- **Skirt-diff, computed compute-side (`computeDownSkirts` / `DownSkirtSpan`).** Any
  rectangle partition of a holed / L-shaped level has *internal* edges between
  equal-height pieces; a skirt there is a **false interior wall** (and depth-testing
  can't hide it where it overhangs air near a hole). So each edge is skirted only over
  the sub-spans **not** covered by an equal-height neighbour abutting across it (a
  per-edge 1-D interval subtraction), **and** minus the wall/ceiling occluder sub-spans
  on that edge (they get an upward skirt instead). Partial sharing is handled (a big
  rect's edge shared with a sliver over only part of its length skirts just the unshared
  remainder); true drops / hole outlines / unshared remainders keep their skirts. It is
  computed **compute-side once per select** (`SurfaceSelection.computeDownSkirts`) and
  published as `DownSkirtSpan`s (edge orientation, side, line, `[lo,hi]`, base `T`),
  which `emit` just draws — one pass per select keeps large/bumpy selections smooth
  (the alternative, an `O(n²)` `openSpans` scan over the merged rects every frame,
  hitches). This is the shared drop-edge pass the hole classifier plugs into (a drop
  span is a hole candidate).
- **Upward (occluder) skirts — wall-vs-drop classification.** A
  surface edge bordering a **wall** (a box rising above the surface) or a **ceiling**
  (an overhang within the entity's headroom) is *not* a drop, so a downward skirt
  there reads wrongly. Such edges instead draw an **upward** skirt — a wall face
  rising from the surface top — solid at the base `T` and fading to transparent at the
  marker top (every height fades out at the top). Because the wall/drop split needs
  collision-box data the render thread may not query, the classification is done
  **compute-side** (`SurfaceSelection.computeOccluders`, once per stick action) and
  published as `OccluderSpan`s in the snapshot. The *downward* skirts are computed
  compute-side too (`computeDownSkirts`, above) with the occluder sub-spans already
  subtracted, so an edge is never double-skirted; `emit`
  simply draws the published down spans (`emitDownSkirts`) and the published occluder
  spans as upward skirts (`emitOccluders`). Each span carries its orientation, **side**
  (so the
  skirt is nudged toward the surface interior to dodge z-fighting the wall face, and
  opposite-side edges at one coordinate aren't merged), the dilated edge line, the
  `[lo,hi]` interval, the base height `T`, and the occluder top. The marker sits at
  the **dilated (set-back) edge** — pulled `~W/2` off the real block face (for Point,
  `W = 0`, at the face). This per-edge wall-vs-drop classification is the
  **prerequisite for hole detection**: the drop-classified edges are the
  hole candidates.
- **Upward (occluder) skirts.** Drawn from published `OccluderSpan`s at Appearance
  `upwardSkirtHeight` (default `0.25`; `0` skips draw), clamped to the available wall
  above the surface, solid at the base and fading to transparent at the tip. Same
  depth-tested `SKIRT` layer as down skirts; nudged toward the surface interior by
  `SKIRT_OFFSET`.
- **Flood geometry debug dump (`/mobwalk dump`).** Client chat command. With a stick
  selection active it re-runs `select` once with a one-shot flag, writes a single
  `[flood-debug]` block to `MobWalk.LOGGER` (header → reached → merged → occluders →
  downskirts → holes), and posts a short chat summary (`merged=… occluders=…
  skirts=… holes=… (see latest.log)`). With an empty selection: chat
  `flood-debug: no selection`. Armed by `/mobwalk dump`.
- **Surface-height toggle (visible face vs collision top; default `V`).** Blocks that
  render taller than they collide (soul sand, mud, cactus, honey) would otherwise draw
  their standable top *buried* at the collision height. A standalone key (default `V`,
  registered in `MobWalkClient`) flips `useVisualTop` (default **on** — the fix). It is
  a **compute-side** flag: it is passed into `select`, where the
  visible top is gathered (gated on it, memoized per `BlockState` — see
  [`geometry.md`](geometry.md) "Visible-face top vs collision top") and **baked into
  each rect's `visualTopY` / span `visualBaseY`**. `emit` then *always* draws the top
  fill, borders, and the up/down/hole skirts at that baked render height
  — and it equals the collision top when the mode is off (the raise isn't
  computed then). The height-gradient **color stays keyed on the collision `topY`**, so
  the palette doesn't shift when toggling. Because the flag gates the compute,
  `toggleVisualTop()` **re-floods** from the last seed (cheap: toggling is rare) and
  pings the HUD (`surface: visible` / `surface: collision`). All walkability math is
  unaffected (collision-top only); this is purely where the paint is drawn. The toggle
  is session-only (resets to on at relaunch); a persisted setting can land with the
  config stack.
- **Depth-based grey cutoff (incomplete-selection signal).** When Debug
  `showCutoffRing` is on (default), surfaces near the BFS depth limit are drawn
  blended toward **grey** (`greyBlend`), so a depth-cutoff reads differently from a
  true boundary (a selection stopped by a real drop stays colored). When off, those
  ring depths (`depth > limit−2`) are not drawn. The blend is keyed on each rect's
  `depth` relative to `depthLimit` (= `selectionRadius`):
  `depth <= limit−2` → no grey; `depth == limit−1` → half grey; `depth >= limit` → full
  grey. This is possible because the **frontier-split merge**
  (`mergeCoplanarSplitFrontier`) keeps the frontier ring (depth == limit) as separate
  rects from the inner blob: raw nodes are partitioned into inner (depth < limit) and
  frontier (depth >= limit), each is unioned/strip-merged independently, and the inner
  area is subtracted from the frontier nodes so the two tile cleanly (inner has priority
  in the dilation overlap zone). Without the split, `mergeCoplanar` would collapse the
  whole same-height area into one rect at `min` depth (0), defeating the depth-based
  grey and perimeter suppression. Down-skirt spans and hole beams at the frontier
  (`sp.depth() >= limit`) are suppressed compute-side — they are cutoff artifacts, not
  real geometry.
- **Hole beams.** A drop edge the classifier labels a **hole** (a mob
  leaving it is trapped — see [`geometry.md`](geometry.md)) raises a **vertical
  beam** from the cliff-edge top `T`. Appearance `showBeamsThroughWalls` (default on)
  routes it to a dedicated depth-off `BEAM` layer (same pipeline as crouch-gated tops)
  **after** the depth-tested skirts, so opaque beams cover skirts and read through
  terrain; when off, beams go into the depth-tested `SKIRT` layer and are occluded by
  blocks. Beams rise a fixed world height (`BEAM_HEIGHT`) at the opacity from
  Appearance `holeBeamColor`. Appearance `showHoleBeams` (default on) gates
  drawing; `holeBeamColor` supplies RGB and alpha (uniform along the beam). Benign
  drops keep their ordinary down-skirt and get **no** beam.
  Drop spans at the **frontier** (`depth >= depthLimit`) are skipped by `computeHoles`
  — they are depth-cutoff artifacts, not real geometry (without this, the entire
  perimeter drew a hole-beam wall).
  The hole spans are classified **compute-side** (`SurfaceSelection.computeHoles` +
  `holeSubSpans`, published as `HoleSpan`) and `emit` just draws them (`emitHoles`).
  Because one edge can span reached and unreached ground, `holeSubSpans` **subdivides**
  an edge and emits a beam only over the unreached sub-intervals. One beam is drawn per
  hole edge-span, so a long dangerous rim reads as a row of beams clearly marking every
  unsafe edge (deliberately not coalesced into one beam per region).

## `26.1.2` rendering API names (verified against the resolved jars)

- Draw context is **`net.minecraft.client.gui.GuiGraphicsExtractor`** (not
  `GuiGraphics`).
- Text is drawn with **`text(Font, String, x, y, color, dropShadow)`** (not
  `drawTextWithShadow`/`drawString`).
- Identifiers are **`net.minecraft.resources.Identifier`** via
  `Identifier.fromNamespaceAndPath(...)` (not `ResourceLocation`).

## Where the file-specific gotchas live (inline comments)

- `widgets/CollisionSurfaceOverlay.java`: the downward resolution + cap, the
  right-click trigger (select/clear) + gated sneak-cycle of the active profile, the
  runtime radius + re-flood (`wantsRadiusScroll`/`adjustRadius`), the
  publish-on-action snapshot, the crouch-gated through-walls top + borders, the
  depth-based grey blend (`greyBlend`, keyed on `rect.depth()` vs `depthLimit`),
  the square fading skirt draw (`fadedSkirt`/`vQuad`, tiny `SKIRT_OFFSET`),
  drawing the **published** down-skirt spans (`emitDownSkirts`, from `DownSkirtSpan`;
  frontier spans `depth >= limit` suppressed), **upward occluder skirts**
  (`emitOccluders`, Appearance `upwardSkirtHeight`, side-based interior nudge), and the
  **hole beams** (`emitHoles`, from
  `HoleSpan`, into the depth-off `BEAM` layer or depth-tested `SKIRT` layer per
  `showBeamsThroughWalls`) — `emit` draws only the published spans — the cyclic
  depth-gradient color (`depthColor`, `DEPTH_CYCLE` hue
  band), the level-identity reset,
  `volatile` snapshot/occluder/down-skirt/hole/crouch handoff, and the
  double-sided-winding requirement.
- `widgets/RadiusIndicatorOverlay.java`: the timer-gated visibility + fade and the
  `volatile` show/render thread handoff.
- `MobWalkClient.java`: the `ClientHotbarScrollEvents.ALLOW` wiring (stick+sneak
  gate, cancels the hotbar slot change) — the composition root that connects the
  scroll input to the world overlay's radius and the HUD indicator — the
  surface-height keybind (`KeyMappingHelper.registerKeyMapping`,
  `KeyMapping(..., KeyMapping.Category.MISC)`, `consumeClick` in `END_CLIENT_TICK`),
  and the `/mobwalk dump` client command (`ClientCommandRegistrationCallback` →
  `CollisionSurfaceOverlay.dumpFloodDebug` → `sendSystemMessage` chat summary).
  Note `26.1.2` uses the `keymapping` API (not `keybinding`) and `KeyMapping.Category`
  (not a `String` category).
- `SurfaceSelection.java`: the output-sensitive `LazyFlood` (depth-bounded surface
  BFS, on-demand column + row exposure via `ensureRows`, per-box `exposeBox` memo,
  the `occluderColumns` shell, `floor(W)+1` neighbour reach, merge-after-flood via
  **`mergeCoplanarSplitFrontier`** — union inner and frontier separately, subtract
  inner from frontier so they tile cleanly), the `selectEager` oracle +
  `PROFILE_FLOOD` parity/timing harness, dilation + **headroom** occlusion in
  `exposeBox` (the `(T, T+H]` standing-column predicate, guillotine
  `subtractRects`), the `union` re-cut + `mergeCoplanar` strip-merge (used by eager
  path), the `footprintAdjacent` edge test + profile-`reach` gate, the
  **compute-side occluder-span classification** (`computeOccluders` /
  `occluderSpansForRect` / `wallOccluder` / `mergeOccluderSpans`, published as
  `OccluderSpan`), the **compute-side down-skirt pass** (`computeDownSkirts` /
  `edgeDownSpans` / `subtractIntervals`, published as `DownSkirtSpan`), the **hole
  classification** (`classifyDrop` — pure: HOLE unless a reached surface lies
  strictly below the rim under the fall footprint, and then HOLE anyway if a standable
  **ledge** sits between the rim and that floor; reachability is reached-set membership,
  the ledge scan reuses `exposeBox` — and `computeHoles` / `gatherLedges` / `holeSubSpans`
  / `fallFootprint`; frontier drops `depth >= depthLimit` skipped; subdivided at
  reached-rect boundaries and published as `HoleSpan`),
  and the extraction-thread-only (non-thread-safe) contract.
  **The geometry/algorithm lives in [`geometry.md`](geometry.md); read it first.**
- `WorldOverlayManager.java`: three-layer setup (depth-off `FILLED` tops,
  depth-on `SKIRT`, depth-off `BEAM` last), single draw at `AFTER_TRANSLUCENT_TERRAIN` (ice/honey
  composite; pond bottoms via crouch — see the translucent-phase decision),
  per-layer buffer/GPU handoff, the through-walls debug aid, the
  `CLIENT_STOPPING`-vs-mixin GPU-cleanup trade-off, the camera-relative
  translate, and the use-key-edge-vs-`UseItemCallback` debounce.
