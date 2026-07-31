# PLAN

The current plan, committed so it can be handed between agents and machines through git
(cloud agent to local dev agent). Work runs one step at a time: each step carries its own
enumerated in-game checklist, is validated in-game, and is its own commit (see
[`AGENTS.md`](AGENTS.md) → Stage-gating). The **Status** lines say where the work stands.
Durable knowledge lives in `docs/`, so this file is cleared once the plan lands.

# M9 hazards — water and lava as swimmable hazard surfaces

Give water and lava standable swim planes (behind a manual toggle per fluid) so pools
stop reading as false holes, seated at an effective standing height so the existing
reach reproduces the measured escape, with hazard-colored fill plus perimeter beams
marking it.

## The model

A fluid cell contributes **a standable surface and no collision volume**, and the fluid
**taints** whatever surface is standable there. Everything else follows from the
existing rect/flood machinery.

- **Surfaces without volume — the governing rule.** Occlusion here is a property of
  **collision volume**: burial (`yMin <= T`) and headroom (`yMin < T+H`) both ask what
  solid spans above a top. A fluid supplies a surface and no volume, so its box offers a
  standable top and nothing else, and every solid top in the world survives a fluid
  overhead. Solid boxes clip a fluid top exactly as they clip any other top. Once
  created, a fluid surface travels the same path as a block's surface — dilation,
  clipping, flood, merge, skirts, holes — and fluid-specific logic is confined to
  **creation**: which cells emit a box, at what height, and the `0.4` threshold. This is
  a general primitive, a **non-occluding support surface**, whose intended later
  instances are scaffolding and climbables (see the backlog).
- **The column ladder is a consequence, not a feature.** Because a fluid box never
  occludes, the top of *every* fluid cell survives exposure, so a fluid column is a
  stack of standable planes exactly `1.0` apart — within every profile's reach. The
  pool floor therefore connects to the surface and the shore by the ordinary climb test,
  and a waterfall is climbable the same way, with no rule saying anything about water.
- **Why the first pass failed.** It gave the box a clip role with the buried term only.
  Both occlusion terms are volume tests, so the box still deleted the solid top inside
  its cell — and the headroom term alone would do it anyway (`yMax = y+0.778 > y`,
  `yMin = y < y+1.8`), which is why no thin-box variant rescues it. The measured symptom
  was a flowing sheet with adjacent `6/9` and `7/9` cells: the `7/9` box removed the
  floor beneath it, so the `6/9` cell's rim had nothing below it and read as `HOLE`.
  Recorded here because the invariant is easy to re-break: a fluid is a surface, and
  only volumes occlude.
- **Edges against a fluid are ordinary drops.** An edge with no volume facing it stays a
  drop span, and the drop classifier decides it the usual way — a reached surface
  strictly below, across the fall column, is a landing. The kept floor is that landing at
  a `6/9 | 7/9` step, and the plane one cell down is that landing in deep water, so
  fluids need no edge-coverage rule of their own. Marking "a solid faces this rim" stays
  the occluder pass's job.
- **Which fluids.** Detection is keyed on the vanilla `FluidTags.WATER` /
  `FluidTags.LAVA` tags, one manual toggle each. Fluids outside those tags keep today's
  behavior, so modded fluids are out of scope (a modded fluid that opts into a vanilla
  tag comes along for free).
- **One box per fluid cell.** For a cell whose fluid state carries an enabled tag, emit a
  pseudo `SurfaceSelection.WorldBox` over the full cell footprint, `yMin = y`,
  `yMax = y + FluidState.getHeight(level, pos)` — `1.0` for a submerged or falling cell,
  `8/9 ≈ 0.889` for a source at the surface — tagged `WATER` or `LAVA`. Falling water is
  still swimmable fluid, so a waterfall cell emits the same way as a pond interior; the
  downward push is unmodelled (see Limitations), and the column of planes `1.0` apart is
  climbable under the ordinary reach test.
- **The `0.4` rule governs emission at the surface.** `LivingEntity` picks the liquid
  jump over the ground jump when the fluid height at the entity exceeds
  `getFluidJumpThreshold()` (`0.4` at any normal eye height), *even while on the ground*
  — so a mob on the bottom of a 1-deep pool (`0.889`) already swims, and no
  depth-of-pool wading rule is needed. At or below that threshold it jumps normally, so
  a cell whose fluid height is `<= 0.4` (water levels 1–3, Overworld lava's level-2 tail)
  emits **no box** and its solid top is the only surface there, standable with
  `profile.reach()`. Verify the threshold and the method names against the resolved jars.
- **Escape lives in the surface plane's height.** For the cell at the surface
  (`getHeight < 1`) the plane's `collisionTopY` is an **effective standing height**,
  seated so that the existing reach lands exactly on the measured escape:
  `collisionTopY = clamp(blockTop + escape - profile.reach(), y, y + fluidHeight)`.
  Water uses `escape = 0.875` (14/16 above the fluid block's top face, the in-game
  measurement), so at `R = 1.2522` the plane sits `0.6228` above the cell floor and
  `T + R = y + 1.875`: soul sand one level up is reachable, a full block at `y + 2.0`
  is not. Lava is far more viscous and gets its own constant, measured in-game during
  Step 3. `LazyFlood`, `assignOriginWave`, and the skirt passes stay unmodified, and
  fluids add no directed edges — escape stays encoded in the plane's height. Hole
  geometry is already the rim-line `FallColumn` from Milestone 8.6; fluids thread into
  its ledge gather via the same `levelColumnBoxes` boxes, without changing the
  classifier. The clamp keeps the plane inside its own cell for extreme custom reaches,
  which also keeps it at or above the plane one cell down, so the seated surface stays
  the column's highest node and submerged planes never extend escape.
- **Draw stays on the fluid surface.** `visualTopY = y + fluidHeight`, carried by the
  **existing raise** (`blockOutlineTop > blockCollisionTop`), so the paint sits on the
  visible surface while all walkability math uses the effective plane — exactly the
  split [docs/geometry.md](docs/geometry.md) already defines for soul sand. A fluid box
  is built with both block tops equal to its own top, so the raise-core mechanism passes
  over it by construction.
- **Fluids connect.** A fluid plane expands like any other surface, so the far bank of
  a river is reached and a pool whose rim is out of escape range reads as the trap it
  is. Radius is a per-node BFS hop count, so painting water costs paint and compute,
  never land coverage — but with a plane per cell the cost now tracks water **volume**
  inside the hop budget, which is why Step 1 gates on measurement. If open water proves
  expensive or noisy, the knob is a cap on consecutive fluid hops (a river crosses, a
  swim out to sea stops) — deferred, since it turns the plain BFS into a two-criteria
  search needing revisit logic.
- **Hazard tagging is per cell, not per plane.** Any enabled fluid in a cell tags the
  standable surface there: `levelColumnBoxes` tags the fluid box *and* every collision
  box whose top sits below the fluid surface. Without that, a `2/9` lava sheet over
  netherrack would paint as ordinary walkable stone — the most lethal false-safe this
  feature could produce.
- **Fluid identity through the merge.** `StandableRect` carries the tag plus plate vs
  solid, and the merge ownership class becomes
  `(radiusTier, hazardPriority, visualTopY)`, the extension
  [docs/geometry.md](docs/geometry.md) already predicts. `hazardPriority` keeps a water
  region and an abutting lava region as separate rects.
- **One fluid enum is the extension point.** `FluidKind { NONE, WATER, LAVA }` serves
  both roles — which fluid a plane came from (it owns that fluid's escape constant) and
  what taints a standable surface (it owns the color key and the merge priority). A
  later hazard kind is one enum constant plus its two Appearance options; kinds that
  never produce a plane simply have no escape constant. A surface carries **one** tag,
  so a magma block submerged in water reports the higher-priority kind; a set-valued
  tag is the growth path if that ever needs both.
- **Marking.** Hazard rects draw their fill in the fluid's hazard color, and the
  boundary of the fluid region (hazard-rect edges with no same-fluid rect across them)
  publishes `HazardSpan`s drawn as beams through the existing `BEAM`/`SKIRT` routing.

```mermaid
flowchart TD
  levelColumnBoxes["levelColumnBoxes (world read)"] -->|"collision boxes + fluid boxes + fluid tags"| index[WorldSurfaceIndex]
  index --> exposeBox["exposeBox: dilate, clip by volumes only, carry tag"]
  exposeBox --> flood["LazyFlood: unchanged, one climb test"]
  flood --> merge["mergeCoplanarSplitFrontier: (tier, hazard, visualTopY)"]
  merge --> skirts[computeDownSkirts / computeOccluders]
  merge --> holes["computeHoles (FallColumn)"]
  merge --> hazards[computeHazardSpans]
  hazards --> emit[SurfaceEmitter]
```

## Step 1 — Fluid surfaces stop occluding

Most of `500b3b7` stands: `FluidKind`, the `FluidPolicy` record threaded through
`select` → `LazyFlood` / `computeHoles` / `gatherLedges`, `fluidKind()`,
`FLUID_JUMP_THRESHOLD`, the `WorldBox` fluid fields with their convenience constructors,
box emission and the submerged-tag loop in `levelColumnBoxes`, the pure
`plateHeight(kind, fluidHeight, reach)` predicate, the Generic toggles in
[Configs.java](src/client/java/dev/kelianmao/mobwalk/client/config/Configs.java)
(`swimmableWater` on, `swimmableLava` off) with their re-flood callbacks and
`Configs.fluidPolicy()`, the three `select` call sites in
[CollisionSurfaceOverlay.java](src/client/java/dev/kelianmao/mobwalk/client/surface/CollisionSurfaceOverlay.java),
and the `comment.*` keys in
[en_us.json](src/client/resources/assets/mobwalk/lang/en_us.json).

The corrections:
1. The `if (other.fluidPlate())` branch in `exposeBox`
   ([SurfaceSelection.java](src/client/java/dev/kelianmao/mobwalk/client/surface/SurfaceSelection.java)):
   a fluid box contributes no clip rect at all, so that branch becomes a skip. Two tests
   invert — `FluidClipContractTest.solidTopDirectlyBeneathPlateIsRemoved` and
   `FluidPlaneTest.pondFloorBuriedByPlate` — and `FluidClipContractTest` gains the
   shore-clip complementarity assertion (contract 2 below).
2. Drop the top-layer / `fluidAbove` / `getHeight < 1` gate from `plateHeight` — every
   enabled fluid cell with height `> 0.4` emits, including submerged and falling cells.
   A pond / waterfall becomes a stack of planes; cost gates on a radius-30 ocean click
   vs land (fallback: deferred fluid-hop cap). `FluidPlaneTest` covers the column.

Keeps the plane **at the fluid surface** (`yMax = y + fluidHeight`, both block tops equal
to it), and Step 3 lowers it to the effective standing height, so escape here is the
plain jump reach from the surface, one notch more permissive than the final behavior. A
pond therefore paints multiple layers (surface, intermediates, floor).

In-game checklist:
1. Deep pond (3+ deep) with a **flush** shore, Player, click the shore → the water
   surface paints across the pond, the floor and intermediate planes are reached, and
   the rim draws a plain down-skirt; expected: no red hole beams on the rim.
2. Flowing sheet with adjacent `6/9` and `7/9` cells → each level paints at its own flat
   height, and the step between them shows no red beam (the floor beneath is the
   landing).
3. 1-deep puddle → the water plane paints, and the floor beneath paints as a second
   layer.
4. Waterline beach (land top level with a 1-deep pool's floor) → the land continues into
   the water at the floor's height; expected: no red beam at that edge.
5. Thin flowing sheet (water spread 3+ blocks from its source, or Overworld lava's last
   ring) → the paint stays on the stone beneath, at the stone's height.
6. Configure → General → `swimmableWater` Off → the selection re-floods immediately and
   the pond reads as hole beams again; On → water paints again.
7. Lava pool, `swimmableLava` Off → unchanged from today (hole beams); On → the lava
   surface paints.
8. Waterfall into a pool → the falling column paints and connects pool to source.
9. Ravager on the same pond → water paint set back ~1 block from the shore, land paint
   overhanging the water.
10. Ravager on a 2-wide, 1-deep puddle → the puddle interior paints (as floor) rather
    than reading as a void ringed by beams.
11. Waterlogged bottom slab in the pond → the water plane and the slab top both paint;
    waterlogged top slab → paint on the slab top alone.
12. A flowing stream → each flowing level paints at its own flat height (a stepped
    surface under vanilla's sloped render).
13. Radius-30 ocean click vs land → no material stutter; note dump timing.
14. Regression: a dry-terrain selection looks identical to before, including a floor
    under a low ceiling still dropping out for a tall profile; `/mobwalk dump` writes its
    `[flood-debug]` block; expected: no errors in the log, and none on world reload or
    window resize.
15. `./gradlew build` green.

## Step 2 — Carry fluid identity through the merge

`fluid` and `plate` fields on
[StandableRect.java](src/client/java/dev/kelianmao/mobwalk/client/surface/StandableRect.java)
(defaulted in the convenience constructors), set from the source box in `exposeBox`, and
`OwnershipClass` / `ownershipLayers` / `stripMergeEqualOwnership` in
[RectMath.java](src/client/java/dev/kelianmao/mobwalk/client/surface/RectMath.java)
extended to `(tier, hazardPriority, visualTopY)`, ordering by
`FluidKind.priority()` so a new kind slots in without touching the partition algorithm.
`logFloodDebug` prints the tag. Tests extend `MergeContractTest` / `PriorityPartitionTest`.

In-game checklist:
1. The Step 1 pond → the picture is unchanged; expected: no shifted paint and no new
   seams.
2. `/mobwalk dump` on that pond → merged lines carry `fluid=WATER` on the planes and on
   the floor beneath them, `fluid=NONE` on land.
3. `/mobwalk dump` on a thin lava sheet over netherrack → the stone rect carries
   `fluid=LAVA`.
4. Water abutting lava at the same surface height → the two stay separate rects in the
   dump.
5. Waterline beach → the submerged floor rect and the dry land rect at the same height
   stay separate in the dump.
6. Regression: dry-terrain dump identical apart from the new fields.
7. `./gradlew build` green.

## Step 3 — Seat the surface plane at the effective standing height

A pure helper `swimPlaneY(blockY, fluidHeight, kind, reach)` returns
`clamp(blockY + 1 + escape - reach, blockY, blockY + fluidHeight)`, used for the
`yMax` of a **surface** cell's box in `levelColumnBoxes` (a submerged cell keeps
`y + 1`), with `blockOutlineTop = blockY + fluidHeight` so the existing raise puts
`visualTopY` back on the visible surface. Escape constants (`WATER_ESCAPE = 0.875`, lava
measured below) live next to `EntityProfile.DEFAULT_JUMP_REACH`. New `SwimEscapeTest`
covers the seating arithmetic, the 14/16 pass, the full-block reject, both clamp ends,
`visualTopY` staying on the surface, and the seated plane staying at or above the plane
one cell down.

In-game checklist:
1. Pool ringed by soul sand one level above the water block → the soul sand and the
   terrain past it paint.
2. Same pool ringed by full blocks one level above the water block → the ring stays
   unpainted, and the rim carries a hole beam (the pool is no longer entered from
   there, so the drop reads as the trap it is).
3. Same pool with the shore flush at the water block's top face → shore paints.
4. Water paint is still drawn on the visible surface, at the same height as in Step 1
   (only reachability changed); expected: no paint sunk into the water.
5. `/mobwalk dump` on a flowing stream → water levels 6–8 share one `collisionTopY`
   (the datum is the block top, so the bands collapse) while their `visualTopY` values
   still differ per level; levels 4–5 sit at their own clamped surface. On screen the
   stream still reads as stepped paint.
6. Dry ground beside the pool: a 1-block step up still paints (jump reach intact).
7. Click the water inside the walled pool → the pool and its floor paint alone.
8. Point profile (reach `1.0`) on the same pool → the plane clamps just under the
   surface and the pool still paints as one region.
9. 3-deep pond → the seated surface plane still connects down the column, so the floor
   stays reached and the picture stays one layer.
10. Lava measurement (sets the lava constant): a Warden in a lava pool ringed at flush /
    soul-sand / full-block heights → note which rims it escapes, then set the constant
    and re-run items 1–3 against lava.
11. Regression: an unrelated dry cliff/stair selection is unchanged, and the soul-sand
    visible-face raise still behaves as before.
12. `./gradlew build` green.

## Step 4 — Hazard fill + perimeter beams

`SurfaceEmitter` colors hazard-tagged rects (and their skirts) from the fluid's hazard
color instead of `walkableColor`, so open water reads as water rather than as walkable
ground. The **color precedence** becomes explicit, highest first: frontier grey, Debug
`shadeByDepth` hue, hazard color, `walkableColor` — the first two already outrank
everything, so hazard slots in one level above the default. The emitter looks the color
up through a single `Configs.hazardColor(kind)`, so it never branches per kind.

A new `HazardSpan` record plus a pure `computeHazardSpans(mergedRects)` in
`SurfaceSelection` (a hazard-rect edge sub-span with no same-fluid rect across it,
frontier skipped) is published as another `volatile` snapshot by
`CollisionSurfaceOverlay`. In
[SurfaceEmitter.java](src/client/java/dev/kelianmao/mobwalk/client/surface/SurfaceEmitter.java),
`emitHoles` is factored into one beam emit (spans + color + toggle + the
`showBeamsThroughWalls` buffer choice) that holes and hazards both call — the
generalization done at its second use rather than in advance. Appearance
`showHazardBeams` (on), `waterHazardColor`, `lavaHazardColor`. `HazardSpanTest` covers
interior-seam suppression and perimeter coverage. Dump gains a `hazards=` block.

In-game checklist:
1. Pond → the water surface fills blue and a ring of blue beams follows the water
   perimeter, the pool interior clear of beams.
2. Lava pool with `swimmableLava` On → orange fill and orange perimeter beams.
3. Thin lava sheet over netherrack → the stone fills orange (the tag from Step 2
   reaching draw).
4. `showHazardBeams` Off → beams gone, hazard fill stays.
5. `showBeamsThroughWalls` Off → hazard beams are occluded by terrain; On → they read
   through it.
6. Small radius across a lake → expected: no hazard beams on the grey frontier ring,
   and frontier grey still wins over hazard color.
7. Debug `shadeByDepth` On → depth hue applies and hazard fill yields to it, matching
   how frontier grey and depth hue already compose.
8. `/mobwalk dump` → a `hazards=N` block with one line per span.
9. Regression: hole beams on a genuine dry cliff are unchanged; a radius-30 click on an
   ocean stays as smooth as a radius-30 click on land today.
10. `./gradlew build` green.

## Representation contracts (stated now, tested in the step that introduces them)

Each invariant gets a named paragraph in `docs/` plus a contract test, matching the
existing `MergeContractTest` / `LedgeExposureContractTest` / `TerrainEdgeContractTest`
pattern. The wording lands in the docs when that step's checks pass (docs come last
within a step); the concrete case tables are written during the step.

1. **Surface existence** (Step 1, `FluidPlaneTest`). A cell emits at most one fluid
   box, and none when its tag is disabled or when height is `<= 0.4`; otherwise its top
   is `y + getHeight` (including falling cells). The decision is the pure
   `plateHeight(...)` returning an optional height, so it is testable without a world.
2. **Fluid role** (Step 1, `FluidClipContractTest`). A fluid box supplies a standable top
   and clips nothing: a neighbouring lower solid top keeps its full dilated area under
   Ravager, a solid top directly beneath it keeps its full area, and a floor under it
   keeps its headroom. In the other direction a solid shore clips the fluid top by `W/2`,
   exactly as it dilates over the water, so the surviving fluid edge meets the shore's
   dilated rim — the complementarity that makes a flush pond rim a landing rather than a
   hole.
3. **Column continuity** (Step 1, `FluidPlaneTest`). Within a fluid column, consecutive
   standable surfaces differ by at most `1.0` — plane to plane exactly `1.0`, and the
   lowest plane one block above the floor — so every profile's reach connects the column
   top to bottom.
4. **Merge identity fidelity** (Step 2, `MergeContractTest`). `collisionTopY` stays the
   sole partition key and fluid identity is an ownership axis only, so the three durable
   merge invariants hold unchanged, plus: each output rect's tag equals those of the
   nodes it covers, and mixed-identity nodes at one collision height still emit disjoint
   rects.
5. **Tag coverage** (Step 2, `FluidPlaneTest`). Every standable surface in a cell holding
   an enabled fluid carries that tag — the fluid plane, and any solid top below the fluid
   surface; a solid top above the surface stays `NONE`.
6. **Effective-plane identity** (Step 3, `SwimEscapeTest`). Unclamped,
   `collisionTopY + profile.reach() == blockTop + escape(kind)`; always
   `collisionTopY <= visualTopY`, with `visualTopY` the physical fluid surface, and
   always at or above the plane one cell down. The second half is what the existing
   raise-direction assumption in the merge ownership order and the visual skirt pass
   rests on, and the clamp is its enforcement — so test both clamp ends (a huge custom
   reach, a tiny one).

Doc homes: a new "Fluid surfaces" section in [docs/geometry.md](docs/geometry.md) for
1–3 and 6, its merge-contract paragraph for 4–5, and the color precedence in
[docs/rendering.md](docs/rendering.md). The non-occluding support surface primitive is
named in [docs/geometry.md](docs/geometry.md) alongside contract 2, since scaffolding and
climbables inherit it.

## Settled decisions

- **A fluid is a surface, not a volume.** Occlusion belongs to collision volumes, so a
  fluid box offers a top and every solid top under a fluid survives.
- **The column ladder is a consequence of that**, not a feature with its own rule: with
  nothing occluding, every cell's top survives and the climb test does the rest.
- **Edge marking stays with the occluder pass.** An edge with no volume facing it is a
  drop, and the drop classifier's landing test decides it — fluids get no coverage rule
  of their own.
- **Falling fluid emits like still fluid.** Downward push unmodelled.
- **Manual toggles, no roster field.** One Generic toggle per fluid (`swimmableWater`
  on, `swimmableLava` off) rather than a `lavaImmune` column on the profile roster.
  Warden is the one builtin that is fire/lava immune, so the lava toggle is the switch
  you flip when that is the profile you care about.
- **Escape reaches are constants**, one per fluid, documented next to
  `EntityProfile.DEFAULT_JUMP_REACH`. Promoting them to settings stays a one-liner.
- **One climb test, no directed edges.** The escape is encoded in where the surface plane
  sits rather than in a special flood rule, so `LazyFlood`, the origin wave, and the
  skirt passes keep working unmodified. Two surfaces connect when the lower can climb to
  the higher; a descent is described by hole classification.
- **Vanilla water and lava only**; other modded fluids keep today's behavior.
- **Fluids connect** across rivers rather than terminating the flood.
- **Wading is not modelled** as a depth rule; the vanilla `0.4` fluid-jump threshold
  covers the one case where a mob jumps from the floor instead of swimming.

## Limitations to record in the docs

- One escape constant per fluid covers every profile, and models physical capability
  rather than AI: vanilla wardens avoid pathing into lava despite their immunity
  (MC-249415), so an enabled lava plane says "it can cross", not "it will".
- A fluid rect's `collisionTopY` at the surface is an **effective standing height**
  derived from the profile reach; `visualTopY` holds the physical surface. So fluid
  geometry depends on `reach` as well as on width and height, and the docs' "collision
  top" wording needs that caveat.
- A pool whose rim sits more than `reach` above the effective plane is outside the
  connected region entirely, so it stays unpainted and its rim reads as a hole beam.
  That is the accurate verdict for a mob that falls in, at the cost of the "the trap is
  water" cue. Coloring such a beam by the fluid found under the fall column is a
  cheap follow-up if it matters in practice.
- The reached set now includes the water volume and the seafloor under it, so a click
  near open water spends its hop budget on water where it used to spend it on land. The
  cap on consecutive fluid hops is the knob if that trade goes the wrong way.
- Flow push is not modelled — a stream carries a mob downstream, and a waterfall pushes
  them down, possibly somewhere the geometry says they chose to go.
- Fluid planes are flat per cell, matching the physics (fluid height is a per-block
  scalar); vanilla renders flowing cells as an interpolated slope, so the paint can sit
  up to `1/9` above or below the visible surface at a slope. Modelling the slope would
  break the axis-aligned-rect invariant for a sub-block cosmetic gain.
- Drowning is not modelled; a water plane is traversable, not survivable.

# M9 hazards

- exists: holes
- generalize visual marker (beam for all hazards?)
  - generalize settings. enable + color for all
- magma
- soul sand
  - need to how collision works. does only part of the hitbox need to be touching to be slowed? in which case, need to use full dilated rect
  - extension: effects through 0.5 blocks
- water, lava
  - walkable water
- (maybe) fall damage
- not in scope: non-collision hazards (powdered snow, berry bushes, fire)

## Ideas / backlog
- Chunked / multi-tick flood so it doesn't stutter.
- Auto update (eg flood from feet every N ticks)
- Hazards
- Settings tooltip UX pass (tone/length).
- Probably out of scope:
  - ladders/vines
  - scaffolding
  - non-collision hazards (eg berry bushes)
  - fall damage
- Definitely out of scope:
  - horizontal velocity when jumping (ie parkour)
  - pathfinding
