# Surface / collision geometry

How standable surfaces are represented and how entity-size awareness (width
dilation) is computed. This is the **math layer** under the block-hitbox overlay;
how those surfaces are *drawn* lives in [`rendering.md`](rendering.md), and the
staged dilation plan lives in [`PLAN.md`](../PLAN.md).

## Representation: rect/double space, not a pixel grid

A standable surface is a `StandableRect(double minX, minZ, maxX, maxZ, topY)` in
absolute world coordinates (the owning `BlockPos` folded in). Coordinates are
**doubles**, not quantized to a `1/16` grid; edge/overlap compares are
epsilon-tolerant (`EPS = 1e-6`). Quantizing is skipped on purpose — width
dilation (below) expands surfaces by entity-dependent amounts that are not
`1/16`-aligned, so precision is left to the math rather than baked into a grid.

## Why rect/double space, not a pixel raster

This is the load-bearing decision; a pixel-mask rewrite was prototyped and
**rejected**.

- **Minecraft hitboxes are axis-aligned squares that never rotate.** The
  Minkowski sum of two axis-aligned rects is again an axis-aligned rect, so
  entity-width dilation is **exact and closed over rectangles** — no rounding,
  no new primitive. `W/2` is a clean fraction even for odd pixel widths (e.g.
  ravager `31/16` → `31/32`), so there is no parity problem to chase.
- **A raster buys nothing here and costs plenty.** Rebuilding the per-block
  surface compute and the flood on an integer pixel mask (1/16, then 1/32 for
  odd-width parity) made dilation a morphological op but added resampling cost,
  ring/seam artifacts, and a slower flood (the prototype stuttered on click) for
  **no accuracy gain** over exact rect math. The dead-end prototype lives in
  branch history if ever needed.

So: keep surface and collision geometry in rect/double space. Don't reach for a
pixel raster.

## Entity-width dilation (the rect-space model)

Treat the entity as a point and pre-grow the world by its half-width `W/2`:

- **Grow the *forbidden* region (holes / unsupported gaps), not just supports.**
  Dilation distributes over a union (`dilate(A ∪ B) = dilate(A) ∪ dilate(B)`), so
  the forbidden region can be grown **per rect** and unioned, then subtracted from
  the support rects. A gap of width `g` flanked by support loses `W/2` on each
  side: `g ≤ W` bridges (no hole), `g > W` leaves a hole — exactly "can't fall
  into a hole smaller than yourself".
- **Occlusion is the same pass, not a separate subtract.** A box-top at height
  `T` survives where no dilated box spans immediately above it (`minY ≤ T <
  maxY`); the spanning box's own (dilated) top is itself a standable surface. One
  pass both removes the buried lower top and supplies the higher one.
- **Not `max topY`:** non-burying overlaps (an air gap between two tops) stay as
  distinct levels, preserving multi-level / stacked surfaces. A zero-width point
  profile reproduces today's behavior exactly.

Locality is bounded (`< 1` block of growth even for the ravager), so building a
cell's region only needs its `ceil(W/2)` neighborhood. See [`PLAN.md`](../PLAN.md)
for the staged implementation and per-stage in-game tests.
