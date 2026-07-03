# PLAN

Short-term working plan only. Empty between tasks — durable knowledge lives in
[`docs/project.md`](docs/project.md) and the subsystem guides under [`docs/`](docs/)
([`geometry.md`](docs/geometry.md), [`rendering.md`](docs/rendering.md)), not here.

## Milestone 5 — reachability / hole detection

Split the "drop" edges of the standable selection by whether the surface an entity
would fall onto is reachable, and mark true holes with a through-walls vertical beam
at the cliff edge, plus a lighter warning marker for tall-but-safe drops. It only
classifies and visualizes the existing reachability flood; it does not change how
reachability is computed.

### Two constraints that shape the design

- **Draw at the cliff edge, never at the far-away hole floor.** No new surfaces are
  created or managed down in the hole. This matches what exists: today's down-skirt is
  drawn at the edge and dropped a fixed depth; it never reconstructs the hole floor.
  The beam rises from the rim.
- **The mob lands on the topmost surface below, and escapability is decided against the
  whole flood — not a local `reach` probe.** A mob leaving a drop edge falls onto the
  *topmost* collision surface below the fall footprint (down is free). That landing is a
  hole iff it is *not* in the reached set (the flood output) — the void (no landing at
  all), or a surface from which the mob cannot climb back. A landing that *is* reached —
  possibly deep, possibly escapable only via a roundabout path (e.g. stairs elsewhere) —
  is benign; the flood already encodes that roundabout escapability, so a bounded "within
  one step" probe would wrongly flag a deep-but-escapable drop as a hole. Escapability is
  decided by the **topmost** landing, not "any reached surface somewhere below": if the
  mob lands on an unescapable ledge that happens to sit above a reached floor, it is
  stuck on the ledge — a hole. Finding the landing is a read-only downward scan; it
  creates no surfaces and nothing is drawn down there (first constraint still holds).

### What exists, and how the classifier relates to it

`SurfaceSelection` computes reachable, dilated, occlusion+headroom-aware
`StandableRect`s via the lazy flood, and classifies wall/ceiling edges compute-side
into `OccluderSpan`s (`computeOccluders` → `occluderSpansForRect` → `wallOccluder` →
`mergeOccluderSpans`). The render widget currently derives drop edges as `openSpans`
(edge minus equal-height merge seams) minus the occluder intervals, in
`downSpans`/`openSpans`/`subtractSpans` (`CollisionSurfaceOverlay`), and every remaining
sub-span gets the same fading downward skirt.

The hole classifier is a new predicate on that output, not the existing edge op. Each
rect edge is already split three ways — an equal-height merge seam (suppressed in
`openSpans`), a wall/ceiling (upward skirt), and the leftover downward drop sub-spans.
The classifier consumes that drop bucket and splits it again, mirroring `wallOccluder`
(a per-box predicate) with a per-drop-span predicate ("does the mob land — topmost
surface below the fall spot — on a reached surface?"), reusing the same interval
machinery — a sibling stage on the same substrate. `computeOccluders` is already the
compute-side, per-merged-rect edge pass this all belongs in (see Cost and structure).

### Classification of a drop sub-span

- **Benign (a) — reversible step**: a within-reach step off the rim (`fall <= reach`,
  raw collision), reversible so never a trap *even onto unreached ground* (flat ground /
  gentle steps, incl. just past the radius).
- **Benign (b) — reachable below**: otherwise, a **reached** surface below the rim under
  the fall footprint (the mob falls onto reachable ground — a roundabout escape the flood
  already found). Split by fall distance `T − landing.topY`:
  - **tall** → a lighter warning marker;
  - **minor** → the down-skirt, nothing special.
- **Hole** — neither: no reversible step and nothing reachable below (the *void*, or a
  drop onto unreached ground). Rendered as a through-walls beam.

> **Design change (Step 3 validation).** Two bugs drove a taxonomy revision:
> **(1)** a single drop edge can span several landings (part a safe step, part a trap),
> so the classifier is applied to **homogeneous sub-spans** — `holeSubSpans` subdivides
> each edge at landing-box / reached-rect boundaries, classifies each piece, and beams
> only the trap pieces. **(2)** The old **Cutoff** class (suppress any beam at the radius
> boundary) was dropped: it only caught the outward edge (perpendicular test), leaving
> sideways corner beams drawn red, and suppressing entirely hid real border drops.
> Instead border uncertainty is a **render** concern — a hole near the ring is drawn
> **grey** by the existing distance blend (`vertex`), signalling "raise the radius". The
> new **within-reach-is-benign** rule is what stops flat/gentle borders from beaming at
> all, so grey beams appear only for genuinely hole-like border drops.
>
> **Design change (bugs 3 + 4: false cliffs, then missing pits — reachability *is* the
> flood).** Bug 3: landing on the topmost **raw** box gave **false cliffs** for a wide
> hitbox, which falls *past* narrow / buried / low-headroom tops it can't stand on. A
> first fix re-derived the topmost **standable** surface by re-running `exposeBox`
> against a locally-scanned occluder index (`gatherStandableBelow`/`exposeStandable`) —
> but that **reinvented what the flood already computes** and (bug 4) regressed basic
> **2-deep pits**. Both are dropped. The insight: *"can the mob get back?" is exactly
> membership in the flood's reached set*, which already accounts for width/occlusion/
> reach. `classifyDrop` now takes only the shallow **within-reach step boxes** (raw, for
> the reversible-step rule (a)) and the **reached set** (for rule (b)); `gatherWithinReach`
> replaces the deep standable scan. Trade-off: an *unreached ledge above a reached floor*
> now reads benign (a reached floor is below it) — a niche trap we accept in exchange for
> correctly detecting ordinary pits and never false-flagging a reachable drop. Rule (a)
> (a flat same-level continuation is a zero-fall reversible step) is what keeps flat
> borders from beaming.

### The hole marker: a through-walls vertical beam

A hole is marked by a tall vertical beam rising from the cliff-edge top `T`, drawn
through walls so it reads even when the rim is occluded by terrain:

- It lives in the depth-off `FILLED` pipeline (`withDepthStencilState(Optional.empty())`),
  the same through-walls route the crouch-gated tops use.
- It rises upward from `T`, clamped to a fixed world height, so all geometry stays at
  the rim.
- One beam per hole region (Step 4), not one per raw edge-span, to avoid a picket fence
  along a long rim.

### Cost and structure: one compute-side edge pass

Skirts, occluder up-skirts, and holes all live on the **drop edges** of the merged
rects (`openSpans` — edges that are not equal-height merge seams). That set is **every
elevation change**, not just the outer boundary: each step, terrace lip, and riser. Its
size ranges from perimeter (flat pens) up toward area-scale (bumpy terrain, where the
merge collapses little so nearly every cell edge is a drop edge). It is therefore not
automatically cheap, and two things follow:

- **Compute the edge decorations once, compute-side — not per frame.** Today `emit`
  recomputes `openSpans` every frame, and `openSpans` scans all `n` merged rects per
  edge (O(n²) per frame) — a real stutter on large/bumpy selections. Computing the drop
  spans once per selection and publishing them fixes that, and is also required for hole
  classification (which needs the drop spans compute-side). So it is done first, as its
  own behavior-preserving step (Step 2).
- **One pass for all edge decorations.** Down-skirts, occluder up-skirts, and holes are
  the same shape — per drop edge, look at the rect's neighborhood — so they share one
  edge computation and one collision gather (extended downward for the hole landing)
  rather than three separate sweeps. The landing scan stops at the first collision below:
  shallow for benign steps (the common case), deep only over a true void (bounded at the
  world floor). Because this pass runs over drop edges once per selection, its cost is
  the elevation-change edge count, and it stays well under the flood (which is area-scale
  and does the heavier per-cell work).

## Steps

Detection is one pure predicate over the reached set (Step 1). Step 2 makes the edge
decorations a single compute-side pass (a behavior-preserving refactor that also removes
the per-frame O(n²)); Step 3 extends that pass with the hole landing scan + beam; Steps 4
and 5 refine the rendering. Each step is one commit with its own tests; validate the
in-game checklist via `./gradlew runClient` before committing (`AGENTS.md` →
Stage-gating).

### Step 1 — The classifier (all detection)

Add one pure classifier to `SurfaceSelection`. Given a **homogeneous** drop sub-span
(edge line, `[lo,hi]`, base `T`), the `reach`, the **within-reach step boxes** below the
fall footprint (raw collision with top in `[T − reach, T]`, pre-gathered so the
classifier stays pure — mirroring how `occluderSpansForRect` takes pre-gathered candidate
boxes), and the full `List<StandableRect>` reached set, return `HOLE` or `BENIGN` plus the
fall distance. Logic: **(a)** a within-reach step box under the footprint → `BENIGN`
(reversible, safe even onto unreached ground); else **(b)** a **reached** surface below
`T` under the footprint → `BENIGN` (roundabout escape), fall distance to the topmost such;
else → `HOLE` (void or a drop onto unreached ground). Reachability is *reached-set
membership*, not re-derived. Unit tests cover: a within-reach step onto an unreached
surface → BENIGN; a same-level continuation → BENIGN (fall 0); a deep-but-roundabout-
reachable drop → BENIGN (assert fall distance); a void / deep-isolated drop → HOLE; a
2-deep isolated pit → HOLE (bug-4 regression guard); and an unreached ledge above a
reached floor → BENIGN (the accepted simplification — see the design-change note). Border
uncertainty is handled render-side (grey), not by the classifier. Document the taxonomy in
[`docs/geometry.md`](docs/geometry.md).

- Commit: classifier + tests + `geometry.md` taxonomy. Tests: code only — the classifier
  is pure (the downward world scan is the caller's job in Step 3), so it has zero in-world
  effect (pure-logic exemption per `AGENTS.md`); gate on `./gradlew test`. No in-game
  checklist needed for this commit.

### Step 2 — Unify the edge decorations compute-side (behavior-preserving)

Generalize `computeOccluders` into a single compute-side edge pass: per merged rect,
compute the drop edges (`openSpans`) once and emit both the down-skirt spans and the
occluder up-skirt spans into the published snapshot. `emit` then draws published spans
only and no longer computes `openSpans` per frame. Nothing changes visually — down-skirts
and occluder markers must be pixel-identical — but the per-frame O(n²) is gone and the
shared pass that holes plug into (Step 3) now exists.

- Commit: the compute-side move + snapshot/`emit` rewrite. Tests: code — the compute-side
  drop-span extraction reproduces the old render-side spans on synthetic rects; in-game —
  the checklist below.
- In-game checklist (`./gradlew runClient`; cross-cutting every item: no log errors;
  server no-op; no errors on window resize / world change; selection clears on leaving
  the world):
  - [ ] Down-skirts and occluder up-skirts look exactly as before across flat, terraced,
    and walled terrain.
  - [ ] A large/bumpy selection is no worse, ideally smoother, frame-to-frame — no
    per-frame hitch when re-looking at it.
  - [ ] Regression: profile cycle, radius scroll, crouch through-walls tops/borders, and
    the grey cutoff ring all unchanged.

### Step 3 — Holes: computeHoles + HoleSpan + through-walls beam (first visible)

Extend the Step 2 pass: for each drop edge, do a **shallow** read-only scan below the
fall footprint for within-reach step boxes (`gatherWithinReach`), classify via Step 1
(reversible step, else reached surface below, else hole), and publish HOLE spans as
`List<HoleSpan>` next to the down-skirt/occluder spans (carrying the fall distance for
Step 5). In `emit`, draw the through-walls beam from each hole edge; benign edges keep the
Step 2 down-skirt. A single edge that spans several verdicts is **subdivided**
(`holeSubSpans`) and beamed only over its trap pieces. Border behaviour (grey, not
suppressed) is validated here.

- Commit: `HoleSpan` + hole classification folded into the unified pass (incl. the
  shallow within-reach scan + per-verdict subdivision) + beam draw. Tests: code — the pure
  classifier (`classifyDrop`) and the subdivision (`holeSubSpans`); in-game — below.
- In-game checklist (same cross-cutting as Step 2):
  - [ ] Edge over the void → **beam at the rim**, visible **through an intervening wall**.
  - [ ] A **basic 2-deep isolated pit** (floor unreached, no way down within reach) →
    **beam** (bug-4 regression guard).
  - [ ] A deep (`>= 2`) roundabout-reachable drop (a lower floor reached via stairs
    elsewhere) → **no beam** (benign).
  - [ ] A shallow reached trench / flat ground / a one-block step down → **no beam** (a
    within-reach step is reversible).
  - [ ] **Heterogeneous edge** (part over a safe step, part over a pit) → beam over the
    **pit portion only**, not the whole edge nor none of it (bug 1).
  - [ ] A selection stopped by the **radius**: a genuine deep drop at the boundary → a
    **grey** beam (uncertain), *not* suppressed and *not* confident red; flat/gentle
    ground at the boundary → **no beam**; raising the radius until reachable ground below
    is reached removes any transient beam (bug 2).
  - [ ] **Non-point hitbox** (Player / Ravager): walking varied but reachable terrain
    (steps under walls, dilation-cut ledges, roundabout lower floors) shows **no false
    cliffs** — beams only over genuine unescapable drops, same as Point (bug 3); and basic
    pits are still beamed for wide hitboxes too (bug 4).
  - [ ] Point / Player / Ravager all behave; no double-marking with occluder up-skirts.

### Step 4 — One beam per hole region

Coalesce the per-edge `HoleSpan`s bounding the same gap into a hole region, so a long
rim raises one beam at a representative point, not a picket fence. Tune beam
height/width/color.

- Commit: span→region coalescing + one-beam-per-region draw. Tests: code — the
  coalescing (adjacent/collinear hole spans → one region); in-game — the checklist below.
- In-game checklist:
  - [ ] A long straight cliff → a small number of beams (not one per merged rect edge).
  - [ ] An L-shaped / wrapping hole rim → one beam per hole.
  - [ ] Beam height is a sensible fixed world height (not scene-dependent).
  - [ ] Regression: Step 3 hole / benign split (incl. grey border beams) unchanged.

### Step 5 — Warning marker for tall benign drops

Using the fall distance from Step 1, render benign drop edges whose fall exceeds a
threshold with a lighter warning look, distinct from the hole beam (e.g. a
shorter/dimmer beam or a tinted rim). Minor drops stay as the down-skirt.

- Commit: tall-benign selection by fall distance + warning marker draw. Tests: code —
  the threshold picks the right spans given fall distances; in-game — the checklist below.
- In-game checklist:
  - [ ] A benign tall drop (reached landing, `>= N` blocks) → warning marker, **not** a
    hole beam.
  - [ ] A benign minor drop → **no** warning.
  - [ ] A hole → still the full beam, never the warning.
  - [ ] Threshold reads sensibly; regression: Steps 3–4 split and beams unchanged.

## Key files

- `SurfaceSelection.java` — generalize `computeOccluders` into the single compute-side
  edge pass (down-skirts + occluder up-skirts + holes); add the pure classifier over the
  reached set, mirroring the `wallOccluder` predicate.
- New `HoleSpan.java` — analogous to `OccluderSpan.java`.
- `widgets/CollisionSurfaceOverlay.java` — `emit` draws published down-skirt / occluder /
  hole spans (no per-frame `openSpans`); draw the beam via the depth-off `FILLED` pipeline
  (same route as the crouch-gated tops).
- Tests under `src/test/java/com/example/overlay/client/`.
- Docs: [`docs/geometry.md`](docs/geometry.md) (taxonomy/model),
  [`docs/rendering.md`](docs/rendering.md) (edge pass + hole draw),
  [`docs/project.md`](docs/project.md) (status).
