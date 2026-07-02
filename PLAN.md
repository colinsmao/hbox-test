# PLAN

Short-term working plan only. Empty between tasks — durable knowledge lives in
[`docs/project.md`](docs/project.md) and the subsystem guides under [`docs/`](docs/)
([`geometry.md`](docs/geometry.md), [`rendering.md`](docs/rendering.md)), not here.

## Milestone 4.5 — occluder-aware skirts + entity-height headroom

Code complete; **unit-test + build gates green** (`./gradlew test`, `./gradlew build`).
Durable docs updated. Stages and what landed:

- **Stage 0 — test harness:** `fabric-loader-junit` + `useJUnitPlatform`; the test
  source set extends the `client` + Minecraft classpaths (split sources, loom#1060).
- **Stage A1/A2 — occluder-aware skirts:** compute-side wall/ceiling classification
  (`computeOccluders` / `occluderSpansForRect` / `wallOccluder` / `mergeOccluderSpans`),
  published as `OccluderSpan`; render-side draws upward skirts (`emitOccluders`) and
  subtracts them from the downward skirts (`downSpans`/`upIntervalsOnEdge`). Standalone
  debug key **K** cycles the marker style (`cycleOccluderStyle`).
- **Stage B1/B2 — headroom:** `EntityProfile` gains `height` (Point 0 / Player 1.8 /
  Ravager 2.2); `exposeBox` occludes a top iff a box rises above `T` AND is buried
  (`yMin <= T`) or a headroom ceiling (`yMin < T+H`); `height` threaded through
  `selectEager` + `LazyFlood` (tops() shell rows extended to `floor(yMax+H)+1`).

### Bug fixes during verification

- **Embedded/stacked tops all painted (FIXED).** The first headroom predicate
  (`yMax > T && yMin < T+H`) dropped the buried case: at `H = 0` (the default Point
  profile) a box resting directly on a surface has `yMin == T` ≮ `T`, so it did not
  occlude and every embedded top leaked. Fixed by unioning the buried term
  (`yMin <= T`) back in; added unit tests `directlyOnTopBoxBuriesAtPointHeight` /
  `embeddedTopBuriedForEveryProfileHeight` (the missing intermediate check).

## In-game validation checklist (`./gradlew runClient`)

> Stage-gating (`AGENTS.md`): a green build proves logic + compile only;
> skirt/headroom rendering and flood parity are runtime-only. Each item is a concrete
> action → expected on-screen result. Cross-cutting (every item): no errors in the log;
> server no-op; no errors on window resize / world change; selection clears on leaving
> the world.

### Pre-check (regression that prompted this)

- [ ] **No embedded tops.** Point profile (default), right-click a solid floor with a
  block stack / a buried floor under a full block → the buried/embedded lower tops are
  **not** painted; only genuinely exposed tops draw. (This is what was broken.)

### Stage A2 — occluder-aware skirts

- [ ] **Wall edge → upward skirt.** Floor next to a block rising above it → that edge
  shows an **upward** marker rising from the surface, **not** a downward drop skirt.
- [ ] **Drop edge → downward skirt.** Floor next to a deep drop / the void → **downward**
  skirt (as before).
- [ ] **Step edge.** Floor next to a within-`reach` lower floor → downward riser (as
  before); an equal-height continuation → **no** skirt (no false interior wall).
- [ ] **Placement per profile.** Player/Ravager (`W>0`) → the upward marker sits `~W/2`
  off the wall (set-back edge, mid-block); Point (`W=0`) → at the wall face.
- [ ] **No double-skirt.** A wall edge gets only the upward marker, never also a
  downward skirt over the same span.
- [ ] **Debug key K.** Each press increments tiny → half-block → full → bold-line
  (wrapping), live; the HUD pings the style name; skirt styles are solid at the base and
  fade to transparent at the top; `full` is clamped (a tall wall is not a giant curtain).
  Compare the four and **settle on one** (then drop or keep the toggle as debug-only).

### Stage B2 — entity-height headroom

- [ ] **Oracle parity.** Set `PROFILE_FLOOD = true` in `SurfaceSelection`; for
  Point/Player/Ravager across radii 0..20 the log shows `match=true` (lazy == eager).
  Flip it back **off** before committing.
- [ ] **Point unchanged.** Point selection is identical to Milestone 4 everywhere
  (A/B baseline).
- [ ] **Player (H=1.8), 1-block gap.** Floor with a solid block exactly 1 above → **not**
  painted; remove the ceiling → it paints. The lost headroom shows as the Stage A2
  upward skirt marking the ceiling.
- [ ] **Player, 2-high tunnel.** Floor in a 2-tall tunnel → **painted**; a slab ceiling
  (clearance `< 1.8`) → **not** painted.
- [ ] **Ravager (H=2.2).** 2-high tunnel → **not** painted; 3-tall → painted.
- [ ] **Partial overhang.** A half-covered floor → only the uncovered half painted;
  Point → Player → Ravager shrinks the painted area by `~W/2` more each.
- [ ] **Covering box's own top.** Still painted where it has its own headroom.

Run each item before merging the PR (`AGENTS.md` → Git/workflow).

## Likely next

**Milestone 5 — hole detection / classification**, built on the drop-classified
edges (the down-skirt edges left by Part A are the hole candidates).
