# PLAN

The current plan, committed so it can be handed between agents and machines through git
(cloud agent to local dev agent). Work runs one step at a time: each step carries its own
enumerated in-game checklist, is validated in-game, and is its own commit (see
[`AGENTS.md`](AGENTS.md) → Stage-gating). The **Status** lines say where the work stands.
Durable knowledge lives in `docs/`, so this file is cleared once the plan lands.

# M9 hazards — water and lava as swimmable hazard surfaces

Give water and lava standable fluid surfaces (behind a manual toggle per fluid) so pools
stop reading as false holes, seated at an effective standing height so the existing
reach reproduces the measured escape, with hazard-colored fill plus perimeter beams
marking it.

## The model

A fluid cell contributes **a standable surface and no collision volume**; hazard
identity lives on that fluid surface alone. Everything else follows from the existing
rect/flood machinery.

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
  `FluidTags.LAVA` tags, one shared `swimmableFluids` toggle. Fluids outside those tags keep today's
  behavior, so modded fluids are out of scope (a modded fluid that opts into a vanilla
  tag comes along for free).
- **One box per fluid cell.** Enabled tag → full-footprint fluid surface, `yMin = y`,
  `yMax = y + fluidSurfaceHeight` — `getHeight` when above `0.4`, else `0`; tagged `WATER`
  or `LAVA`; `occludes=false`. Falling included.
- **The `0.4` rule seats thin fluid at the cell floor.** Above `0.4` the fluid surface
  sits at `getHeight`; at or below it the fluid surface sits at relative height `0`
  (coplanar with the solid underfoot). Hazard priority over that solid at the same
  `collisionTopY` is Step 2.
- **Hazard identity lives on the fluid surface.** Only the fluid box carries `FluidKind`;
  solids stay `NONE`. Thin sheets get a floor-height fluid surface so they carry the
  fluid hazard over the solid underfoot.
- **Escape lives in the surface plane's height.** For a surface cell the plane's
  `collisionTopY` is an **effective standing height** (Step 3):
  `clamp(blockTop + escape - reach, y, y + fluidHeight)`. Water `escape = 0.875`.
- **Draw stays on the fluid surface.** `visualTopY = y + fluidHeight` via the existing
  raise when seating lowers `collisionTopY` (Step 3). Thin fluid surfaces at `0` keep
  both tops at the cell floor until seating.
- **Fluids connect** across rivers. Cost tracks water volume inside the hop budget —
  Step 1 gates on measurement; deferred knob is a fluid-hop cap.
- **Fluid identity through the merge.** Ownership `(radiusTier, hazardPriority, visualTopY)`
  — hazard priority separates water/lava and lets a thin fluid surface claim over solid.
- **One fluid enum is the extension point.** `FluidKind { NONE, WATER, LAVA }` owns
  escape, color, and merge priority.
- **Marking.** Hazard fill color + perimeter `HazardSpan` beams.

```mermaid
flowchart TD
  levelColumnBoxes["levelColumnBoxes (world read)"] -->|"collision boxes + fluid surfaces"| index[WorldSurfaceIndex]
  index --> exposeBox["exposeBox: dilate, clip by occluding volumes only"]
  exposeBox --> flood["LazyFlood: unchanged, one climb test"]
  flood --> merge["mergeCoplanarSplitFrontier: (tier, hazard, visualTopY)"]
  merge --> skirts[computeDownSkirts / computeOccluders]
  merge --> holes["computeHoles (FallColumn)"]
  merge --> hazards[computeHazardSpans]
  hazards --> emit[SurfaceEmitter]
```

## Step 1 — Fluid surfaces stop occluding

Most of `500b3b7` stands: `FluidKind`, `boolean swimmableFluids` threaded through
`select` → `LazyFlood` / `computeHoles` / `gatherLedges`, `fluidKind()`,
`FLUID_JUMP_THRESHOLD`, the `WorldBox` fluid fields with their convenience constructors,
box emission in `WorldGeometry.levelColumnBoxes`, the pure
`fluidSurfaceHeight(kind, fluidHeight, reach)` predicate, the Generic toggle in
[Configs.java](src/client/java/dev/kelianmao/mobwalk/client/config/Configs.java)
(`swimmableFluids` on) with its re-flood callback and
`Configs.swimmableFluids()`, the three `select` call sites in
[CollisionSurfaceOverlay.java](src/client/java/dev/kelianmao/mobwalk/client/surface/CollisionSurfaceOverlay.java),
and the `comment.*` keys in
[en_us.json](src/client/resources/assets/mobwalk/lang/en_us.json).

The corrections:
1. `WorldBox.occludes` (renamed from fluidPlate): solids set `occludes=true`; fluid
   surfaces set `occludes=false`. `exposeBox` skips clip when `!occludes`.
   Zero-thickness alone still headroom-occludes.
2. Drop solid-under-fluid tainting — only the fluid surface carries `FluidKind`.
3. Thin fluid (`<= 0.4`) emits a fluid surface at relative height `0`.
4. Invert clip-contract tests that assumed burial; add shore complementarity; thin-at-0
   fluid-surface tests.

Keeps the plane **at the fluid surface** for tall fluid (`yMax = y + fluidHeight`), or at
the cell floor for thin fluid; Step 3 seats escape. A pond paints multiple layers
(surface, intermediates, floor).

In-game checklist (unit tests cover fluid-surface heights, clip role, column continuity):
1. Deep pond with a flush shore, click the shore → water surface paints across the pond,
   floor/intermediate planes are reached, rim is a plain down-skirt (no red hole beams).
2. Thin flowing sheet (water far from source, or Overworld lava's last ring) → a
   floor-height fluid surface paints coplanar with the stone underfoot.
3. Configure → General → `swimmableFluids` Off → pond re-floods as hole beams; On → water
   paints again.
4. Dry-terrain selection unchanged from before this step.
5. `./gradlew build` green.

## Step 2 — Carry fluid identity through the merge

a `fluid` field on
[StandableRect.java](src/client/java/dev/kelianmao/mobwalk/client/surface/StandableRect.java)
(defaulted in the convenience constructors), set from the source box in `exposeBox`, and
`OwnershipClass` / `ownershipLayers` / `stripMergeEqualOwnership` in
[RectMath.java](src/client/java/dev/kelianmao/mobwalk/client/surface/RectMath.java)
extended to `(tier, hazardPriority, visualTopY)`, ordering by
`FluidKind.priority()` so a new kind slots in without touching the partition algorithm.
`logFloodDebug` prints the tag. Tests extend `MergeContractTest` / `PriorityPartitionTest`.

In-game checklist (unit tests cover merge ownership / partition invariants):
1. Step 1 pond picture unchanged (no new seams).
2. `/mobwalk dump` on that pond → `fluid=WATER` on water planes only; solids stay
   `fluid=NONE`.
3. Water abutting lava at the same height → two separate regions in the dump.
4. `./gradlew build` green.

## Step 3 — Seat the surface plane at the effective standing height

A pure helper `fluidSurfaceY(blockY, fluidHeight, kind, reach)` returns
`clamp(blockY + 1 + escape - reach, blockY, blockY + fluidHeight)`, used for the
`yMax` of a **surface** cell's box in `WorldGeometry.levelColumnBoxes` (a submerged cell
keeps `y + 1`), with `blockOutlineTop = blockY + fluidHeight` so the existing raise puts
`visualTopY` back on the visible surface. Escape constants (`WATER_ESCAPE = 0.875`, lava
measured below) live next to `EntityProfile.DEFAULT_JUMP_REACH`. New `FluidEscapeTest`
covers the seating arithmetic, the 14/16 pass, the full-block reject, both clamp ends,
`visualTopY` staying on the surface, and the seated plane staying at or above the plane
one cell down.

In-game checklist (unit tests cover seating arithmetic and clamp ends):
1. Pool ringed by soul sand one level above the water → soul sand and terrain past it
   paint.
2. Same pool ringed by full blocks one level above → ring unpainted, rim carries a hole
   beam.
3. Flush shore still paints; water paint stays on the visible surface (not sunk).
4. Dry cliff/stair selection unchanged.
5. `./gradlew build` green.

Lava escape constant: measure with a Warden (flush / soul-sand / full-block rims), set
the constant, then re-check items 1–3 on lava before treating this step done.

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

In-game checklist (unit tests cover perimeter span geometry):
1. Pond → blue fill and blue perimeter beams; pool interior clear of beams.
2. Lava pool → orange fill and orange perimeter beams.
3. `showHazardBeams` Off → beams gone, hazard fill stays.
4. Dry-cliff hole beams unchanged.
5. `./gradlew build` green.

## Representation contracts (stated now, tested in the step that introduces them)

Each invariant gets a named paragraph in `docs/` plus a contract test, matching the
existing `MergeContractTest` / `LedgeExposureContractTest` / `TerrainEdgeContractTest`
pattern. The wording lands in the docs when that step's checks pass (docs come last
within a step); the concrete case tables are written during the step.

1. **Surface existence** (Step 1, `FluidPlaneTest`). A cell emits at most one fluid
   box when its kind is enabled; top is `y + getHeight` when height `> 0.4`, else `y + 0`
   (thin sheet). Pure `fluidSurfaceHeight(...)`.
2. **Fluid role** (Step 1, `FluidClipContractTest`). A fluid box supplies a standable top
   and clips nothing (`occludes=false`): neighbouring lower solid keeps full dilated area
   under Ravager, solid beneath keeps full area, floor keeps headroom. Solid shore clips
   the fluid top by `W/2` — complementarity at a flush pond rim.
3. **Column continuity** (Step 1, `FluidPlaneTest`). Within a fluid column, consecutive
   standable surfaces differ by at most `1.0` — plane to plane exactly `1.0`, and the
   lowest plane one block above the floor — so every profile's reach connects the column
   top to bottom.
4. **Merge identity fidelity** (Step 2, `MergeContractTest`). `collisionTopY` stays the
   sole partition key and fluid identity is an ownership axis only, so the three durable
   merge invariants hold unchanged, plus: each output rect's tag equals those of the
   nodes it covers, and mixed-identity nodes at one collision height still emit disjoint
   rects.
5. **Tag coverage** (Step 2, `FluidPlaneTest`). The fluid surface carries the kind; solids
   stay `NONE`. Thin sheets use a height-0 fluid surface (hazard priority claims over
   solid in Step 2).
6. **Effective-plane identity** (Step 3, `FluidEscapeTest`). Unclamped,
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
- **Manual toggle, no roster field.** One Generic `swimmableFluids` (on by default) for
  vanilla water and lava, rather than a `lavaImmune` column on the profile roster.
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
