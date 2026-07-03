# Surface / collision geometry

How standable surfaces are represented, made entity-size aware (width dilation),
and computed (the output-sensitive flood). This is the **math layer** under the
block-hitbox overlay; how those surfaces are *drawn* lives in
[`rendering.md`](rendering.md). Surface/collision geometry stays in **rect/double
space**, never a pixel raster (a raster rewrite was prototyped and rejected — see
[Appendix A](#appendix-a-rejected-pixel-raster)).

## Representation: rect/double space

A standable surface is a `StandableRect(double minX, minZ, maxX, maxZ, topY)` in
absolute world coordinates (the owning `BlockPos` folded in). Coordinates are
**doubles**, not quantized to a `1/16` grid; edge/overlap compares are
epsilon-tolerant (`EPS = 1e-6`). Quantizing is skipped on purpose: width dilation
(below) expands surfaces by entity-dependent amounts that are not `1/16`-aligned,
so precision is left to the math rather than baked into a grid. Minecraft
hitboxes are axis-aligned squares that never rotate, so the Minkowski sum of two
axis-aligned rects is again an axis-aligned rect — dilation is **exact and closed
over rectangles**, no rounding and no new primitive.

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
3. **Merge.** Coplanar (`|dTopY| < EPS`) rects are unioned into maximal rectangles
   (`mergeCoplanar`: group by `topY`, re-cut each level to a non-overlapping
   `union`, then greedy strip-merge equal-span abutting rects along X then Z to a
   fixpoint). A flat floor collapses from a grid of unit cells to one rect → clean
   skirts, fewer quads. Greedy is not a minimal partition, but a missed merge only
   costs an extra interior skirt, **never reachability**.
4. **Flood / reachability.** Starting from the seed block's surface(s), a surface is
   reached iff it connects to an already-reached surface by **one geometric
   adjacency rule**: the footprints share an edge with positive overlap (or overlap
   with positive area), `footprintAdjacent`, **and** `|dTopY| <= reach` (the
   profile's single step threshold). This subsumes the old same-block /
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
  subtracts (guillotine `subtractRects`) every *dilated* box that occludes its top
  (`yMax > T && (yMin <= T || yMin < T+H)` — the buried-or-headroom test, rule 1),
  yielding 0..N surviving top rects. The boxes it must subtract live in a bounded
  column window — see `occluderColumns` below.

Locality is bounded (growth is `< 1` block even for the Ravager), which is what
makes the output-sensitive flood possible.

## How it is computed: the output-sensitive (lazy) flood

`select` dispatches to **`selectLazy`** (the production path, `LAZY = true`). A
full-window reference implementation, **`selectEager`** (enumerate the whole
`radius + margin` cube → dilate all → merge → flood), is kept behind a flag as the
**correctness oracle**; a `PROFILE_FLOOD` switch runs both per call, asserts they
cover the same area, and logs timing/exposure counts. The lazy path is verified
**set-equal** to eager for Point/Player/Ravager at radii `0..20`.

`LazyFlood` is a **surface BFS that exposes geometry only as it reaches it**, so
cost tracks the reachable set (and its occluder shells) rather than the window
volume — a large win in caves / against walls, and asymptotically on open ground.

- **Nodes are raw per-box dilated tops** (`exposeBox` output, *pre-merge*), each
  tagged with its source cell (`CellSurface`). The union/merge runs **after** the
  flood, on the reached set only (area-preserving, so connectivity is identical with
  or without an early merge).
- **Neighbour search is column-local.** From a popped surface in cell `c`, candidate
  cells are within Chebyshev `floor(W) + 1` of `c` (`1` for Point/Player, `2` for
  Ravager). This is the cell distance at which two dilated tops can still bridge or
  abut (raw gap `Δ-1 <= W`). It is **not** `ceil(W)`: that is `0` for Point and would
  never connect adjacent floor tiles. The same `floor(W)+1` is the eager occluder
  margin too — one reach everywhere.
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
  scanned rows so each `(column,row)` is queried at most once. Only the **origin
  column** exposes its full band — it has to, to seed every standable top there. The
  flood front moves `<= reach` per hop, so by induction everything reachable is found
  near an already-reached height; the band is never needed. This drops the per-column
  vertical factor from `O(radius)` to `O(heights the flood actually traverses there)`,
  so open ground goes `~radius³ → ~radius²`.
- **`occluderColumns` (the occluder shell).** For `exposeBox` to trim a box's
  dilated top correctly it needs every box that can span above that top. A box in
  column `(cx,*)` spans `[cx, cx+1]`, dilated to `[cx-W/2, cx+1+W/2]`, which overlaps
  the target's dilated footprint iff `floor(min - W) <= cx <= ceil(max + W) - 1` per
  axis. This is the exact (full-block-conservative) window — for Point (`W=0`) it
  collapses to the box's own column. It is shared by `exposeBox` (so the eager path
  shifts identically) and `LazyFlood.ensureRows`, keeping the index and the scan in
  lock-step. Tightening it is **result-preserving**: the dropped columns abut with
  zero overlap and trimmed nothing.

Net cost ≈ `columns × rows-per-column`. The XZ laziness + `occluderColumns` trim the
first factor; lazy-Y trims the second — orthogonal, and they compose.

## Reachability model

Reach is a **single threshold** (`profile.reach()`, default `1.0`): two surfaces
connect when their height difference is `<= reach`, anything deeper does not.
Reachability is plain yes/no — a location is reachable from the seed or it is not —
so anything not connected to the seed is simply **unreachable** (a hole/gap), modulo
the radius budget, which can cut off a very long winding path. So a shallow (`<= 1` deep)
trench is reachable and its floor *is* painted (not a hole); to see the width rule you
need a gap that is **deep (`>= 2`) or over the void**. **Entity-height headroom** is
now modelled (rule 1 / Milestone 4.5: a top survives only where `(T, T+H]` is clear),
so a floor under a low ceiling drops out for a tall profile. Explicit hole detection /
classification builds on this in **Milestone 5** (below): the flood still only paints
*coverage*, and a separate predicate reads that coverage to label each drop edge.

## Hole classification (Milestone 5)

Milestone 5 does not change reachability; it **reads the flood output** to label the
**drop edges** of the selection (the `openSpans` edges that are neither equal-height
merge seams nor wall/ceiling up-skirts). One pure predicate,
`SurfaceSelection.classifyDrop`, classifies a **homogeneous** drop sub-span into a
**taxonomy of two**. The rule is trivial: **is there a reached surface strictly below
the edge that overlaps the fall footprint?**

- **BENIGN** — yes: the mob falls onto reachable ground (a surface the flood already
  reached for this entity — width, occlusion, reach all accounted for). Fall distance =
  `T − landing.topY` (to the **topmost** such surface), for Step 5's tall/minor split.
- **HOLE** — no: void (nothing below at all) or a drop onto unreached ground the mob
  cannot escape from. Carries fall distance 0.

No world scanning, no raw-box checks, no within-reach concept. Reachability is purely
**reached-set membership** — the flood already computed it. Step B (pending) will add a
**ledge check**: when a reached floor exists below, scan the world for standable surfaces
between the edge and the floor (via `exposeBox`), and reclassify as HOLE if one is found
(the mob gets trapped on the ledge).

There is **no CUTOFF class**: near the radius the selection is incomplete, but a drop
there is still classified normally — a genuine deep drop reads HOLE. The **outermost edge**
of the selection (at `ringEnd`) is suppressed entirely render-side: skirts and hole beams
there are artifacts of the radius cutoff. Interior border uncertainty is a render concern:
a hole beam in the grey ring is blended toward grey by the same distance falloff that
greys tops/skirts (see [`rendering.md`](rendering.md)), signalling "raise the radius".

Consistent with the [representation](#representation-rectdouble-space) rule, this is all
**rect/double** work: the reached-surface test is a positive-area XZ overlap against the
flood's `StandableRect`s (mirroring `coversAnySeed`), and nothing new is created down in
the hole — the marker is drawn at the rim (see [`rendering.md`](rendering.md)).

The candidate drop spans are the compute-side `DownSkirtSpan`s (every genuine drop
edge). `computeHoles` walks them once per select: for each it builds the **fall
footprint** — a one-block band just beyond the rim, on the drop side — and checks the
reached set (no world read). Because **one edge can span reached and unreached ground**,
it does **not** classify the whole edge at once: `holeSubSpans` subdivides the edge at
reached-rect boundaries into homogeneous sub-spans, classifies each via `classifyDrop`,
and publishes the contiguous `HOLE` pieces (coalesced) as `HoleSpan`s. `BENIGN` sub-spans
keep their ordinary down-skirt.

## Entity profiles (size + headroom)

`EntityProfile(name, width, height, reach)` selects the entity the flood is computed
for. Three ship, cycled Point → Player → Ravager:

| Profile | width `W` | height `H` | reach |
| ------- | --------- | ---------- | ----- |
| Point   | 0.0       | 0.0        | 1.0   |
| Player  | 0.6       | 1.8        | 1.0   |
| Ravager | 1.95      | 2.2        | 1.0   |

`W` drives dilation (above); `H` drives headroom (rule 1); `reach` is the step
threshold (and sets the downward-skirt depth + the upward-skirt clamp). Heights
are the vanilla hitbox heights — doubles, not `1/16`-aligned, consistent with the
rect-space model. **Point keeps `H = 0`** so it stays the pure point-walker and the
eager-vs-lazy oracle baseline.

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
