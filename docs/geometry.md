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

1. **Occlusion-aware tops.** A collision sub-box's top at height `T` is standable
   only over the footprint where **no box spans immediately above it** (`minY <= T
   < maxY`). The covering box's own top is itself a (higher) standable surface, so
   one rule both removes a buried lower top and supplies the higher one. Non-burying
   overlaps (an air gap between two tops) stay **distinct levels** — never collapsed
   to `max topY` — so stacked surfaces (overhangs, spiral staircases) are preserved.
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
   profile's single symmetric step threshold). This subsumes the old same-block /
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
  subtracts (guillotine `subtractRects`) every *dilated* box that spans immediately
  above its top, yielding 0..N surviving top rects. The boxes it must subtract live
  in a bounded column window — see `occluderColumns` below.

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
  occluder shell is scanned only at the rows around *that box's own top*
  (`floor(yMax)±1`); `exposeBox` is memoized per box. A `BitSet` per column records
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

## Reachability model (current scope)

Reach is a **single symmetric threshold** (`profile.reach()`, default `1.0`): a step
up or down of `<= reach` connects, anything deeper does not. Because the threshold is
symmetric the flood is **reversibly reachable**, so there is no "unreturnable space" —
anything not connected to the seed is simply **unreachable** (a hole/gap), modulo the
radius budget, which can cut off a very long winding path. So a shallow (`<= 1` deep)
trench is reachable and its floor *is* painted (not a hole); to see the width rule you
need a gap that is **deep (`>= 2`) or over the void**. Explicit hole detection /
classification and entity-height headroom are deferred to later milestones — this
stage paints *coverage*, framed as "a `g > W` gap leaves a hole in the standable
coverage", not "the entity falls in".

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
