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

## Block-hitbox rendering (Milestone 3–4.5): `CollisionSurfaceOverlay` + `SurfaceSelection`

Draws the **standable surfaces** of blocks — the upward-facing collision faces an
entity of a chosen size can stand on — for a region the player selects with a
stick, where the region follows **walkable terrain** outward from the clicked
block. The geometry (occlusion-aware tops, entity-width dilation, the
output-sensitive flood) is computed by `SurfaceSelection` and documented in
[`geometry.md`](geometry.md); this section covers the **widget and its drawing**.

### Selection lifecycle (input → snapshot)

- **Stick is a trigger, not a brush.** Right-clicking (use-key rising edge) selects
  the surfaces reachable from the targeted block; right-clicking nothing clears. The
  selection persists across item switches (drawn only while the stick is held) until
  the next trigger or a level change.
- **Publish-on-action.** The drawn snapshot — an immutable `List<StandableRect>` from
  `SurfaceSelection.allRects()`, height-tinted at draw so no per-rect tag — is
  (re)published into a `volatile` field on each stick action (select / clear / radius
  scroll / profile cycle). `extract` never mutates the selection; it only does the
  **level-identity reset** (a changed/`null` `Level` empties it, so world unload /
  dimension change / disconnect all reset it without a manager-side hook). There is no
  per-frame staleness pass, so editing painted terrain needs a re-click.
- **Data source is the collision shape, not the visual shape:**
  `BlockState.getCollisionShape(level, pos, CollisionContext.empty())`. Empty shapes
  are pass-through (tall grass, flowers); `onUseItem` resolves the targeted block
  **downward** (`MutableBlockPos.move(0,-1,0)`, capped and floored at
  `level.getMinY()`) to the first non-empty shape.
- **Entity profiles + `reach`.** An `EntityProfile(name, width, height, reach)`
  selects the entity the flood/dilation/headroom use. Three ship, cycled in order
  **Point** (`width 0`, `height 0`, default — reproduces the zero-width point-walker)
  → **Player** (`0.6`, `1.8`) → **Ravager** (`1.95`, `2.2`); `reach` (default `1.0`)
  is the single step threshold. **No keybind for the cycle:** it rides the
  use-key dispatch — **sneak + right-click at nothing** clears *and* advances the
  profile, then pings the HUD with the new name. The `profile` field is `volatile`
  (read in `emit`); `width` drives dilation and `height` drives headroom (see
  `geometry.md`). (The separate occluder-style debug key above is unrelated to the
  profile cycle.)
- **Adjustable flood radius (shift+scroll).** `MobWalkClient` registers
  `ClientHotbarScrollEvents.ALLOW` (the official Fabric hotbar-scroll hook — no mixin)
  and, **only while holding the stick and sneaking**, changes the radius by the scroll
  direction (`adjustRadius`), re-floods from the last seed so the change is immediate,
  and returns `false` to cancel the vanilla slot change. Plain scroll is untouched.
  The radius is clamped `[0, 20]` and steps by **1 up to 10, then by 2** (`12, 14, …,
  20`) — the window grows quadratically, so coarse steps keep the high end usable.
  Each change pings `RadiusIndicatorOverlay`.
- **Threading.** The selection is computed only on the client/extraction thread
  (`select`/`clear`); the render thread reads only the immutable snapshot via the
  `volatile` handoff (same pattern as the other widgets). `SurfaceSelection` holds the
  result list, not a per-block cache — each action recomputes from scratch.

### Per-surface drawing (`emit`)

Each reached `StandableRect` is drawn as a **top fill**, an optional **border**, and
**skirts**, split across the two `WorldOverlayManager` pipelines (depth-off `FILLED`
through-walls, depth-on `SKIRT` occluded).

- **Top fill (half-alpha), height-gradient colored.** Each surface is tinted by its
  `topY` across the selection's `[minTopY, maxTopY]` range (single color when flat),
  so elevation and drops read at a glance.
- **Crouch-gated through-walls, depth-tested by default.** Seeing surfaces *through*
  blocks is a debug aid, so the top is routed per-frame: **while sneaking** it goes
  into the depth-off `FILLED` pipeline (`withDepthStencilState(Optional.empty())`) and
  draws through walls (along with the borders); **otherwise** into the depth-on
  `SKIRT` pipeline, occluded by terrain like a real surface.
- **Border (debug, crouch-only).** A thin opaque border (4 clamped edge strips, both
  windings) separates adjacent surfaces and the sub-rects of one block (e.g. the
  4-rect ring around a fence post). It clutters the normal view, so it draws **only
  while sneaking** (`crouching`, sampled in `extract`).
- **Skirts: square, fading, depth-tested.** Each edge drops a double-winding vertical
  skirt of depth `reach + SKIRT_MARGIN` (~2), darker, **solid over its top half and
  fading to transparent over the bottom half** so a deep drop doesn't read as a hard
  floating wall. Skirts always live in the depth-tested `SKIRT` layer, so a step reads
  as a riser, a real drop as an open wall, and interior skirts on solid-backed floors
  self-hide. (A **translucent** block writes no depth and so doesn't occlude a skirt —
  accepted limitation.) Tops/borders draw at **true rect bounds**; only the skirts are
  nudged out by a tiny `SKIRT_OFFSET` (0.002, square) to dodge z-fighting the coplanar
  terrain face (`Y_OFFSET` is likewise 0.002). *A trapezoidal/dilated skirt was tried
  and rejected — splaying clipped the block's upper edge and re-introduced overlap.*
- **Skirt-diff, computed compute-side (`computeDownSkirts` / `DownSkirtSpan`).** Any
  rectangle partition of a holed / L-shaped level has *internal* edges between
  equal-height pieces; a skirt there is a **false interior wall** (and depth-testing
  can't hide it where it overhangs air near a hole). So each edge is skirted only over
  the sub-spans **not** covered by an equal-height neighbour abutting across it (a
  per-edge 1-D interval subtraction), **and** minus the wall/ceiling occluder sub-spans
  on that edge (they get an upward skirt instead). Partial sharing is handled (a big
  rect's edge shared with a sliver over only part of its length skirts just the unshared
  remainder); true drops / hole outlines / unshared remainders keep their skirts. This
  used to run **render-side every frame** (`openSpans`, an `O(n²)` scan over the merged
  rects per frame — a real hitch on large/bumpy selections); as of **Milestone 5 Step 2**
  it is computed **compute-side once per select** (`SurfaceSelection.computeDownSkirts`)
  and published as `DownSkirtSpan`s (edge orientation, side, line, `[lo,hi]`, base `T`),
  which `emit` just draws. This is the shared drop-edge pass the hole classifier plugs
  into (a drop span is a hole candidate).
- **Upward (occluder) skirts — wall-vs-drop classification (Milestone 4.5).** A
  surface edge bordering a **wall** (a box rising above the surface) or a **ceiling**
  (an overhang within the entity's headroom) is *not* a drop, so a downward skirt
  there reads wrongly. Such edges instead draw an **upward** skirt — a wall face
  rising from the surface top — solid at the base `T` and fading to transparent at the
  marker top (every height fades out at the top). Because the wall/drop split needs
  collision-box data the render thread may not query, the classification is done
  **compute-side** (`SurfaceSelection.computeOccluders`, once per stick action) and
  published as `OccluderSpan`s in the snapshot. As of **Milestone 5 Step 2** the
  *downward* skirts are computed compute-side too (`computeDownSkirts`, above) with the
  occluder sub-spans already subtracted, so an edge is never double-skirted; `emit`
  simply draws the published down spans (`emitDownSkirts`) and the published occluder
  spans as upward skirts (`emitOccluders`). Each span carries its orientation, **side**
  (so the
  skirt is nudged toward the surface interior to dodge z-fighting the wall face, and
  opposite-side edges at one coordinate aren't merged), the dilated edge line, the
  `[lo,hi]` interval, the base height `T`, and the occluder top. The marker sits at
  the **dilated (set-back) edge** — pulled `~W/2` off the real block face (for Point,
  `W = 0`, at the face). This per-edge wall-vs-drop classification is the
  **prerequisite for Milestone 5 (hole detection)**: the drop-classified edges are the
  hole candidates.
- **Occluder-marker debug style (`cycleOccluderStyle` + a keybind).** The final
  upward-marker look is being A/B'd in-game: a **standalone keybind** (default `K`,
  registered in `MobWalkClient` via `KeyMappingHelper`, **not** tied to the
  scroll/use handlers) increments a `volatile` style index (tiny / half-block / full /
  bold-line, wrapping). It is a pure render-thread choice, so it does **not** touch the
  published spans or re-flood. The `full` style clamps to `reach + SKIRT_MARGIN` so a
  tall wall isn't a giant curtain. **Deferred:** no single baseline style was ever
  chosen and the toggle dropped-or-kept — this appearance decision is deferred to a
  later appearance-focused milestone (see [`project.md`](project.md) roadmap); the `K`
  toggle and the four styles stay as-is until then.
- **Grey cutoff ring (incomplete-selection signal).** Surfaces within the last block
  before the radius cutoff blend toward **grey** (`RING_COLOR`), so a radius cutoff
  reads differently from a true boundary (a selection stopped by a real drop ends
  short of the radius and stays height-colored). `publish` records the ring as a
  Chebyshev square from the seed center (`ringStart`/`ringEnd`, `ringEnd = radius +
  0.5 + halfW`); the per-vertex `vertex(...)` choke point blends every layer toward
  grey by depth into the ring, `sqrt`-eased so the grey fills most of the outer block
  without bleeding inward. `fadedTop` **splits each top at the ring lines** so a long
  merged rect doesn't smear the ramp across its length — the interior stays one
  fully-colored quad, only the ≤1-block outer strips fade. Window-boundary edges keep
  their skirts; the grey alone signals "increase the radius / re-center".
- **Hole beams (Milestone 5).** A drop edge the classifier labels a **hole** (a mob
  leaving it is trapped — see [`geometry.md`](geometry.md)) raises a **through-walls
  vertical beam** from the cliff-edge top `T`, so it reads even when the rim is behind
  terrain: it is drawn in the depth-off `FILLED` layer (the same route as the
  crouch-gated tops), rising a fixed world height (`BEAM_HEIGHT`), solid-ish at the
  base and fading out toward the top, in a distinct red. Benign drops keep their
  ordinary down-skirt and get **no** beam. A hole beam is drawn through the shared
  `vertex` choke point, so a beam in the **grey ring** is blended toward grey — signalling
  "raise the radius". Spans at the **very outermost edge** (`>= ringEnd`) are suppressed
  entirely (`isAtOuterEdge`) — they are radius-cutoff artifacts, not real geometry.
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
  right-click trigger (select/clear) + sneak-cycle of the active profile, the
  runtime radius + re-flood (`wantsRadiusScroll`/`adjustRadius`), the
  publish-on-action snapshot, the crouch-gated through-walls top + borders, the
  ring-split top draw (`fadedTop`/`breakpoints`) and per-vertex grey blend
  (`vertex`, `RING_COLOR`, `ringStart`/`ringEnd`), the square fading skirt draw
  (`fadedSkirt`/`vQuad`, tiny `SKIRT_OFFSET`), drawing the **published** down-skirt
  spans (`emitDownSkirts`, from `DownSkirtSpan`; suppressed at the outermost edge via
  `isAtOuterEdge`), **upward occluder skirts** (`emitOccluders`, the side-based interior
  nudge, the four debug styles + `cycleOccluderStyle`), and the **through-walls hole
  beams** (`emitHoles`, from `HoleSpan`, into the depth-off `FILLED` layer; also
  suppressed at the outermost edge) — `emit` no longer computes any edge spans per
  frame — the height-gradient color (`heightColor`, violet→orange ramp, red reserved for
  holes), the level-identity reset,
  `volatile` snapshot/occluder/down-skirt/hole/profile/crouch/style handoff, and the
  double-sided-winding requirement.
- `widgets/RadiusIndicatorOverlay.java`: the timer-gated visibility + fade and the
  `volatile` show/render thread handoff.
- `MobWalkClient.java`: the `ClientHotbarScrollEvents.ALLOW` wiring (stick+sneak
  gate, cancels the hotbar slot change) — the composition root that connects the
  scroll input to the world overlay's radius and the HUD indicator — and the
  standalone occluder-style debug keybind (`KeyMappingHelper.registerKeyMapping`,
  `KeyMapping(..., KeyMapping.Category.MISC)`, `consumeClick` in `END_CLIENT_TICK`).
  Note `26.1.2` uses the `keymapping` API (not `keybinding`) and `KeyMapping.Category`
  (not a `String` category).
- `SurfaceSelection.java`: the output-sensitive `LazyFlood` (surface BFS,
  on-demand column + row exposure via `ensureRows`, per-box `exposeBox` memo, the
  `occluderColumns` shell, `floor(W)+1` neighbour reach, the 3-D cube cutoff,
  merge-after-flood), the `selectEager` oracle + `PROFILE_FLOOD` parity/timing
  harness, dilation + **headroom** occlusion in `exposeBox` (the `(T, T+H]` standing-
  column predicate, guillotine `subtractRects`), the `union` re-cut + `mergeCoplanar`
  strip-merge, the `footprintAdjacent` edge test + profile-`reach` gate, the
  **compute-side occluder-span classification** (`computeOccluders` /
  `occluderSpansForRect` / `wallOccluder` / `mergeOccluderSpans`, published as
  `OccluderSpan`), the **compute-side down-skirt pass** (`computeDownSkirts` /
  `edgeDownSpans` / `subtractIntervals`, published as `DownSkirtSpan`), the **Milestone 5
  hole classification** (`classifyDrop` — pure: HOLE unless a reached surface lies
  strictly below the rim under the fall footprint, and then HOLE anyway if a standable
  **ledge** sits between the rim and that floor; reachability is reached-set membership,
  the ledge scan reuses `exposeBox` — and `computeHoles` / `gatherLedges` / `holeSubSpans`
  / `fallFootprint`; subdivided at reached-rect boundaries and published as `HoleSpan`),
  and the extraction-thread-only
  (non-thread-safe) contract.
  **The geometry/algorithm lives in [`geometry.md`](geometry.md); read it first.**
- `WorldOverlayManager.java`: the two-layer setup (depth-off `FILLED` tops +
  depth-on `SKIRT`) and per-layer buffer/GPU handoff, the through-walls debug
  aid, the `CLIENT_STOPPING`-vs-mixin GPU-cleanup trade-off, the camera-relative
  translate, and the use-key-edge-vs-`UseItemCallback` debounce.
