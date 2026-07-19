# Surface / collision geometry

How standable surfaces are represented, made entity-size aware (width dilation),
and computed (the output-sensitive flood). This is the **math layer** under the
block-hitbox overlay; how those surfaces are *drawn* lives in
[`rendering.md`](rendering.md). Surface/collision geometry stays in **rect/double
space**, never a pixel raster (a raster rewrite was prototyped and rejected — see
[Appendix A](#appendix-a-rejected-pixel-raster)).

## Representation: rect/double space

A standable surface is a
`StandableRect(double minX, minZ, maxX, maxZ, topY, visualTopY)` in absolute world
coordinates (the owning `BlockPos` folded in). `topY` is the **collision** top that
all math below is keyed on; `visualTopY` is a **draw-only** raise for blocks that
render taller than they collide (see [Visible-face top vs collision
top](#visible-face-top-vs-collision-top)) and equals `topY` for
everything else — nothing in the geometry layer reads it. Coordinates are
**doubles**, not quantized to a `1/16` grid; edge/overlap compares are
epsilon-tolerant (`EPS = 1e-6`). Quantizing is skipped on purpose: width dilation
(below) expands surfaces by entity-dependent amounts that are not `1/16`-aligned,
so precision is left to the math rather than baked into a grid. Minecraft
hitboxes are axis-aligned squares that never rotate, so the Minkowski sum of two
axis-aligned rects is again an axis-aligned rect — dilation is **exact and closed
over rectangles**, no rounding and no new primitive.

**Merge class — the equality tuple.** Two coplanar rects union (rule 3 below) only
when they agree on every component of their **merge class**: the per-rect attributes
the renderer must draw distinctly and therefore may not blend across. Today the tuple
is **`(topY, visualTopY)`** — `topY` because different collision levels are genuinely
different surfaces, `visualTopY` because a raised patch and a flush neighbour draw at
different heights. The tuple is deliberately **extensible**: the next planned
component is a **stand-on hazard class** (soul sand, magma), which lets benign terrain
still merge into one rect while fencing a hazard region onto its own rects. Adding a
component is a one-field change to the record plus one comparator in `RectMath.mergeCoplanar` /
`RectMath.mergeCoplanarSplitFrontier`; while a component is uniform (e.g. hazards off ⇒ every
rect `NONE`) the grouping collapses to the smaller-tuple behaviour at zero cost.
`depth` is outside the tuple — it is aggregated by min over the covered nodes, not
matched on. This is the durable idea behind every "group by …" description below:
those descriptions name the *current* components of one growing equality tuple.

## What the selection is (the computed result)

`SurfaceSelection.select(level, seed, radius, profile)` produces the set of
**dilated, occlusion-aware standable tops reachable from the seed**, merged into
maximal rectangles for drawing. Independent of how it is computed, the result is
defined by four rules:

1. **Occlusion-aware tops, with entity-height headroom.** A collision sub-box's top
   at height `T` is standable only over the footprint that is *not* occluded. A box is
   an occluder iff it **rises above `T`** (`yMax > T`) **and** either is **buried**
   over the top (reaches down to/below the surface, `yMin <= T` — a box resting
   directly on the top has `yMin == T`, a box straddling it has `yMin < T`) **or**
   floats within the entity's **standing column** as a headroom ceiling
   (`yMin < T+H`, `H = profile.height()`). The buried term is the `H = 0` base case —
   exactly the old `minY <= T < maxY` test — so a box directly on the surface still
   buries the top and **embedded/stacked tops are removed**; without it (`yMin < T+H`
   alone) a directly-on-top box at `H = 0` would have `yMin == T` ≮ `T`, so every
   embedded top would leak. **Point is therefore unchanged.** The `yMax > T` bound is
   strict so the box being stood on never self-occludes, and a ceiling bottom exactly
   at `T+H` (`H > 0`) is just-enough clearance (neither term fires). The covering
   box's own top is itself a (higher) standable surface, so one rule both removes the
   buried/headroom-robbed lower top and supplies the higher one; a **ceiling** robs
   headroom from the floor **below** it (the removal zone extends downward by `H`, it
   is not "extend occluders upward"). A partial overhang yields a **partial** surface
   via the same guillotine subtract. Non-burying overlaps (an air gap larger than `H`
   between two tops) stay **distinct levels** — never collapsed to `max topY` — so
   stacked surfaces (overhangs, spiral staircases) are preserved.
2. **Entity-width dilation** (see [below](#entity-width-dilation)): every footprint
   is grown by the profile half-width `W/2` before the spans-above test, so gaps and
   walls eat into the standable area by the entity's size.
3. **Merge.** Coplanar (`|dTopY| < EPS`) rects that share a **merge class** are
   unioned into maximal rectangles (`RectMath.mergeCoplanar`: group by the merge class — the
   extensible equality tuple, **currently `(topY, visualTopY)`**, see [Merge
   class](#representation-rectdouble-space) — re-cut each group to a non-overlapping
   `union`, then greedy strip-merge equal-span abutting rects along X then Z to a
   fixpoint). A flat floor collapses from a grid of unit cells to one rect → clean
   skirts, fewer quads. Greedy is not a minimal partition, but a missed merge only
   costs an extra interior skirt, **never reachability**.
4. **Flood / reachability.** Starting from the clicked seed block's surface(s), a
   surface is reached iff it connects to an already-reached surface by **one
   geometric adjacency rule**: the footprints share an edge with positive overlap
   (or overlap with positive area), `footprintAdjacent`, **and** `|dTopY| <= reach`
   (the profile's single step threshold). This subsumes the old same-block /
   own-column / neighbour-column special cases: a glass pane on a block connects to
   that block's exposed ring because their footprints abut at the hole edges — no
   special case.

**Radius is a spatial budget**, not a graph hop-count: the reached set is bounded by
a Chebyshev cube around the seed — `|x-ox| <= radius` ∧ `|z-oz| <= radius` ∧ `|y-oy|
<= radius` (block coords). It bounds by *physical distance*, so a spiral staircase
is followed only while it stays inside the cube (it does not wind indefinitely) and
a narrow cave only as far as the cube reaches. (A hop-count would be meaningless:
after merge an open floor is a single rect, reached in one hop.)

A **Point** profile (`W = 0`) reproduces the original zero-width point-walker
exactly (dilation is a no-op, tops only abut).

## Entity-width dilation

Treat the entity as a point and pre-grow the world by its half-width `W/2`:

- **The forbidden region grows, not just the supports.** Dilation distributes over
  a union (`dilate(A ∪ B) = dilate(A) ∪ dilate(B)`), so growing each support rect by
  `W/2` and unioning is equivalent to shrinking each gap by `W/2` per side. A gap of
  width `g` flanked by support: `g <= W` bridges (no hole), `g > W` leaves a hole —
  exactly "can't fall into a hole smaller than yourself". Plateau edges overhang by
  `~W/2`; a wall pulls the standable region back `~W/2` from its base (the entity
  perches onto the wall top) as a free byproduct.
- **Occlusion is the same pass.** The spans-above test (rule 1 above) runs on the
  *dilated* footprints, so cutting a buried lower top and supplying the higher one
  is one operation, not a separate subtract.
- **`exposeBox` is the unit op.** It grows one box's footprint by `W/2`, then
  subtracts (guillotine `RectMath.subtractRects`) every *dilated* box that occludes its top
  (`yMax > T && (yMin <= T || yMin < T+H)` — the buried-or-headroom test, rule 1),
  yielding 0..N surviving top rects. The boxes it must subtract live in a bounded
  column window — see `occluderColumns` below.

Locality is bounded (growth is `< 1` block even for the Ravager), which is what
makes the output-sensitive flood possible.

## How it is computed: the output-sensitive (lazy) flood

`select` runs **`LazyFlood`**: a surface BFS that exposes geometry only as it
reaches it, so cost tracks the reachable set (and its occluder shells) rather
than the window volume — a large win in caves / against walls, and asymptotically
on open ground. (During M4 it was cross-checked against a full-window eager scan;
see [`project.md`](project.md) milestones.)

- **Nodes are raw per-box dilated tops** (`exposeBox` output, *pre-merge*), each
  tagged with its source cell (`CellSurface`). The union/merge runs **after** the
  flood, on the reached set only (area-preserving, so connectivity is identical with
  or without an early merge).
- **Neighbour search is column-local.** From a popped surface in cell `c`, candidate
  cells are within Chebyshev `floor(W) + 1` of `c` (`1` for Point/Player, `2` for
  Ravager). This is the cell distance at which two dilated tops can still bridge or
  abut (raw gap `Δ-1 <= W`). It is **not** `ceil(W)`: that is `0` for Point and would
  never connect adjacent floor tiles. The same `floor(W)+1` is the occluder-shell
  reach used when exposing neighbours — one reach everywhere.
- **Lazy in XZ.** A cell's collision boxes are queried on first touch and cached
  per-column, so a column is exposed at most once and only reachable columns (plus
  the occluder shells around them) are ever touched.
- **Lazy in Y (`ensureRows`).** A column is scanned only over the narrow block-row
  windows the flood needs near its current height — never the full `[oy-radius-1,
  oy+radius+1]` band. A neighbour cell is scanned for tops within one `reach` step of
  the popped surface (`collect(cx, cz, h-reach, h+reach)`); each candidate box's
  occluder shell is scanned over the rows from `floor(yMax)-1` up to
  `floor(yMax+H)+1` — the upper bound **extended by the headroom `H`** so the
  ceilings/overhangs in the standing column `(T, T+H]` are exposed before
  `exposeBox` runs (not just the box's own buried shell); `H = 0` collapses it back
  to `floor(yMax)±1`. `exposeBox` is memoized per box (and `H` is fixed per
  `select`, so the memo key is unchanged). A `BitSet` per column records
  scanned rows so each `(column,row)` is queried at most once. The flood **seeds
  from the clicked block's tops** (source block Y = seed Y); other tops in that
  column join only through normal BFS hops (`|dTopY| <= reach` + footprint
  adjacency). The flood front moves `<= reach` per hop, so by induction everything
  reachable is found near an already-reached height. This drops the per-column
  vertical factor from `O(radius)` to `O(heights the flood actually traverses
  there)`, so open ground goes `~radius³ → ~radius²`.
- **`occluderColumns` (the occluder shell).** For `exposeBox` to trim a box's
  dilated top correctly it needs every box that can span above that top. A box in
  column `(cx,*)` spans `[cx, cx+1]`, dilated to `[cx-W/2, cx+1+W/2]`, which overlaps
  the target's dilated footprint iff `floor(min - W) <= cx <= ceil(max + W) - 1` per
  axis. This is the exact (full-block-conservative) window — for Point (`W=0`) it
  collapses to the box's own column. It is shared by `exposeBox` and
  `LazyFlood.ensureRows`, keeping the index and the scan in lock-step. Tightening
  it is **result-preserving**: the dropped columns abut with zero overlap and
  trimmed nothing.

Net cost ≈ `columns × rows-per-column`. The XZ laziness + `occluderColumns` trim the
first factor; lazy-Y trims the second — orthogonal, and they compose.

## Reachability model

Reach is a **single threshold** (`profile.reach()` = `max(jump, step)`): two surfaces
connect when their height difference is `<= reach`, anything deeper does not.
**Player** and **Ravager** use **`1.2522`** (documented living-entity jump peak from
the discrete tick loop on impulse `0.42` / gravity / drag). **Point** uses
**`1.0`**. That value is the profile’s fixed vertical threshold only — the flood
does not model horizontal jump distance (parkour), and it does not apply block or
effect modifiers to jump height (honey, slime-block bounce, Jump Boost, and
similar). Reachability is plain yes/no — a location is reachable from the seed or it is not —
so anything not connected to the seed is simply **unreachable** (a hole/gap), modulo
the radius budget, which can cut off a very long winding path. So a shallow
(`<= reach` deep) trench is reachable and its floor *is* painted (not a hole); to see
the width rule you need a gap that is **deeper than `reach` or over the void**.
**Entity-height headroom** is
modelled (rule 1: a top survives only where `(T, T+H]` is clear),
so a floor under a low ceiling drops out for a tall profile. Explicit hole detection /
classification builds on this (below): the flood still only paints
*coverage*, and a separate predicate reads that coverage to label each drop edge.

## Hole classification

Hole classification **reads the flood output** to label the
**drop edges** of the selection (the `openSpans` edges that are neither equal-height
merge seams nor wall/ceiling up-skirts). One pure predicate,
`SurfaceSelection.classifyDrop`, classifies a **homogeneous** drop sub-span as `HOLE` or
`BENIGN` in two steps:

1. **Is there a reached surface strictly below the edge, under the fall footprint?** If
   **no** &rarr; `HOLE` (the void, or unreached ground the mob cannot climb out of). This
   is the trivial definition of a hole: *not reachable*. Reachability is exactly
   **reached-set membership** — the flood already computed it for this entity (width,
   occlusion, reach all accounted for), so nothing is re-derived here.
2. **If yes** (a reached floor at `landY`), **is there a standable ledge between the edge
   and that floor?** A ledge is a dilated standable surface with top strictly in
   `(landY, T)` overlapping the footprint. If one exists &rarr; `HOLE` (the mob lands on
   the ledge and is trapped above the reachable floor). Otherwise &rarr; `BENIGN`, fall
   distance `T − landY`.

Step 1 is pure rect/double work against the reached `StandableRect`s. Step 2 is the one
world read: `gatherLedges` scans collision boxes in the `(landY, T)` band over the
footprint's columns and runs each through the **same `exposeBox`** the flood uses (dilate
by `W/2`, cut by occluders) — so only flood-standable fragments count as ledges, and a
wide hitbox is handled the same way the flood handles it. The ledge test reuses the
flood's own standability.

Near the radius the selection is incomplete, but a drop there is still classified
normally — a genuine deep drop reads HOLE. The **outermost edge**
of the selection (at `ringEnd`) is suppressed entirely render-side: skirts and hole beams
there are artifacts of the radius cutoff. Interior border uncertainty is a render concern:
a hole beam in the grey ring is blended toward grey by the same distance falloff that
greys tops/skirts (see [`rendering.md`](rendering.md)), signalling "raise the radius".

The candidate drop spans are the compute-side `DownSkirtSpan`s (every genuine drop
edge). `computeHoles` walks them once per select: for each it builds the **fall
footprint** (a one-block band just beyond the rim, on the drop side), gathers ledges, and
classifies. Because **one edge can span reached and unreached ground**,
`holeSubSpans` subdivides the edge at reached-rect
boundaries into homogeneous sub-spans, classifies each via `classifyDrop`, and publishes
the contiguous `HOLE` pieces (coalesced) as `HoleSpan`s. `BENIGN` sub-spans keep their
ordinary down-skirt. Each `HoleSpan` is drawn as its own through-walls beam at the rim
(a long dangerous rim reads as a row of beams clearly marking every unsafe edge).

**Ledge gather Y band — occluders from below.** `gatherLedges` re-runs `exposeBox`
on world boxes whose tops lie in `(landY, topY)`. Candidate tops are only in that
open interval, but the occluder index starts at `floor(landY) - 1` so collision
that *lives in the block row below* `landY` and rises into the band (vanilla
walls/fences at height 1.5) still participates in burial. Those occluders-from-below
keep burial complete for rising shapes. Motivating case: a lantern on
a wall — under Ravager dilation the lantern body (wider than its cap) left a
`7/16` ring with `fall = 0.0625` until the wall box below was in the index.

**Assumption:** one block row below `landY` is enough — the occluding shapes that
matter extend at most ~1.5 upward from their block Y, so they sit in
`floor(landY) - 1` when `landY` is a full-block top. Deeper scan if a single
block's collision grows past that, or if a multi-block pillar's lowest piece sits
further below.

## Visible-face top vs collision top

Everything above derives from `getCollisionShape`, so `StandableRect.topY` is the
collision `yMax`. A handful of blocks **render taller than they collide** — soul
sand collides at `14/16` (`0.875`) but outlines as a full cube, mud at ~`0.9` — so a
marker drawn at the collision top sits **buried inside the visible block**. The fix
is a **draw-only** second height, `StandableRect.visualTopY`, carried alongside the
collision top; the renderer can draw on the face you actually see while **all
walkability math stays on the collision `topY`** (reach, occlusion, holes are
unchanged — a mob really does stand at `0.875`, we just don't want the paint hidden).

- **Source data (paid once per state, no heuristic).** `WorldBox` carries the source
  block's whole-shape `blockCollisionTop` (`y + collisionShape.max(Y)`) and
  `blockOutlineTop`. The outline read (`getShape(...)`) is a real cost, but **every
  block state is checked** — no "renders taller ⟹ collides below `1.0`" shortcut,
  which a mod or future block would break. Instead `visibleTop` memoizes the block's
  outline top per `BlockState` in a static cache (`OUTLINE_TOP_REL`, `NaN` = "no
  separate outline"), so the shape is read **at most once per distinct state ever
  seen** and every later occurrence is a map lookup. It is gated by the render
  toggle (`computeVisualTop`, threaded from `Configs.drawOnVisibleFace()` into
  `select`): off ⇒ `blockOutlineTop = blockCollisionTop`, nothing lifts and no lookup
  is done. Because the flag gates the compute, **toggling the Appearance setting
  re-floods** from the last seed via `reselectWithMobProfile` (cheap: toggling is
  rare). This is the one Appearance option that touches compute, an accepted exception
  to "Appearance is draw-only" given the raise is inherently a compute-side read (it
  joins `floodRadius` / profile changes, which already re-flood). The memo treats the
  property as position-independent (keyed by state only); the few context-dependent
  blocks never have a neighbour-varying *top* raise, so the first-seen value is safe.
  Occluder-only / ledge scans leave the auxiliary-constructor default (`= yMax`), so
  they never raise. Both tops are computed at the node-producing scan site
  (`LazyFlood.ensureRows`).
- **The raise rule (`exposeBox`).**
  `visualTopY = (|topY − blockCollisionTop| ≤ EPS ∧ blockOutlineTop > topY) ?
  blockOutlineTop : topY`. Gating on *"this sub-box is the block's topmost collision
  surface"* is what leaves **stair treads, bottom slabs, and fences** untouched (a
  stair's lower tread is not the block's top; a fence's outline is *shorter* than its
  `1.5` collision top, so `blockOutlineTop > topY` is false). Only full-render-but-
  short-collision tops lift.
- **Through merge.** `visualTopY` is a component of the [merge
  class](#representation-rectdouble-space), so a raised patch and a flush coplanar
  neighbour fall into **different** classes and stay separate rects — the raised paint
  keeps its own height. `DownSkirtSpan` / `OccluderSpan` / `HoleSpan` each carry a
  `visualBaseY` (the source rect's `visualTopY`) alongside the collision `baseY`.
- **Skirts are a render pass, holes a geometry pass.** `computeDownSkirts` runs over
  the merged rects keyed on a chosen height: **collision `topY`** (`dropEdges`, the
  hole-classifier substrate — an equal-`topY` abutting neighbour is a merge seam, not a
  drop) or **`visualTopY`** (the rendered down-skirts — an equal-`visualTopY` neighbour
  is a flush continuation). They differ only where a raise happened, so a visible step
  between two rects at the same collision `topY` but different `visualTopY` (a path lip
  on a soul-sand cube top) gets its skirt, while soul sand's own remnant abutting that
  lip at the same `visualTopY` gets none. `select` computes `dropEdges` once and, only
  when some rect is raised (`hasRaisedRect`; never when the toggle gates the raise off),
  runs the second `visualTopY` pass — otherwise the single pass feeds both holes and
  skirts. Both heights on a span are **load-bearing**: collision `baseY` for the
  hole/geometry pass, render `visualBaseY` for the skirt/beam draw (the renderer hangs
  every skirt/beam from `visualBaseY`, keying color on the collision `depth`).
- **Neighbour-overlap raise (a merge-class split).** A dilated rect owned by block A
  can extend across the top of a touching raised-outline block B — a path lip (`15/16`)
  reaching over soul sand (`14/16` collision, full-cube outline). The rect is a genuine
  A surface at A's collision `topY`, but where it overlaps B's footprint the paint
  would sit **inside B's taller mesh**. The raise lifts `visualTopY` to B's outline top
  **only on that intersection**, splitting the one rect into two pieces:
  `(topY_A, B.outlineTop)` over B and `(topY_A, topY_A)` elsewhere. Because
  `visualTopY` is a merge-class component, the two pieces are automatically different
  classes — the split is literally "reassign the overlap subregion to another equality
  class." Collision `topY` (hence all walkability) is untouched, and B's own exposed
  remnant keeps its own raise, so the covered face reads at one height.

### Known limitation — horizontal inset (not fixed)

Some blocks are inset **horizontally** while still rendering full width — notably
**honey** (`box(1,0,1,15,15,15)`, a `[1/16,15/16]` footprint under a full-cube
render), so its edges/skirts sit ~1px inside the visible face. Honey also has
**two height layers** (collision top `15/16`, full-height translucent/outline
body): with `visualTopY` the paint sits on the outer shell and the fill+honey
body read as stacked layers — see [`rendering.md`](rendering.md). The horizontal
inset is left **unfixed on purpose** because it is **Point-only**: a footprint
dilates by `W/2` per side, so the dilated edge clears the block face whenever
`W ≥ 2/16 = 0.125`, and
every real entity is far past that (smallest vanilla mobs ~`0.4`; all shipped
profiles `≥ 0.6`). Only the zero-width **Point** profile (debug)
keeps the inset footprint. Fixing it would need a full **visual footprint** (four
extra `StandableRect` coords + skirt re-draw + merge-seam handling) for a ~1px border
on a few blocks — poor cost/benefit. The `visualTopY` mechanism generalizes to a
visual footprint if it ever becomes worth it.

### Known limitation — Point profile (not fixed)

The zero-width **Point** profile (debug) has a few other edge-case
draw bugs that do not show up on the shipped dilated profiles. Left unfixed on
purpose due to low ROI. Is a zero-width point supposed to fit through a zero
size gap? Because a 4.0 wide ghast fits into a 4 block gap.

## Entity profiles (size + headroom)

`EntityProfile(name, width, height, reach)` selects the entity the flood is computed
for. The profile is chosen live via the `mobProfile` setting (see
[`settings.md`](settings.md)); the shipped roster is defined in `EntityProfile.Option`,
so it grows there rather than in a table duplicated here.

Each field drives one part of the math: `W` drives dilation (above), `H` drives
headroom (rule 1), and `reach` is the step threshold — `max(jump, step)`. Skirt
*draw* heights are Appearance `downSkirtHeight` / `upwardSkirtHeight` (not `reach`).
Widths and heights are the
vanilla hitbox sizes: doubles, not `1/16`-aligned, consistent with the rect-space
model. Two examples anchor the range:

- **Player** — `W = 0.6`, `H = 1.8`, `reach = 1.2522` (the documented living-entity
  jump peak, shared by the larger Ravager).
- **Point** — `W = 0`, `H = 0`, `reach = 1.0`: zero width makes dilation a no-op and
  zero height reduces headroom to the buried test, so Point stays the pure
  point-walker and geometric baseline.

## Appendix A: rejected pixel raster

A pixel-mask rewrite (rebuild the per-block surface compute and the flood on an
integer `1/16`, then `1/32`-for-odd-width-parity grid, making dilation a
morphological op) was prototyped and **rejected**. Because hitboxes are
non-rotating axis-aligned squares, rect math already gives exact, closed-form
dilation (`W/2` is a clean fraction even for odd pixel widths, e.g. Ravager `31/16
→ 31/32`), so the raster added resampling cost, ring/seam artifacts, and a slower
flood (it stuttered on click) for **no accuracy gain**. The dead-end prototype lives
in branch history if ever needed. Keep surface/collision geometry in rect/double
space.
