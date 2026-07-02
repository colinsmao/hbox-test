# PLAN

Short-term working plan only. Empty between tasks — durable knowledge lives in
[`docs/project.md`](docs/project.md) and the subsystem guides under [`docs/`](docs/)
([`geometry.md`](docs/geometry.md), [`rendering.md`](docs/rendering.md)), not here.

## Milestone 5 — reachability / hole detection

Split the "drop" edges of the standable selection by whether the surface an entity
would fall onto is reachable, and mark true holes with a through-walls vertical beam
at the cliff edge, plus a lighter warning marker for tall-but-safe drops. This only
classifies and visualizes existing reachability: the flood stays reversibly-reachable,
with no new traversal semantics (jump-across, one-way fall-through, and asymmetric
up/down reach are out of scope).

### Two constraints that shape the design

- **Draw at the cliff edge, never at the far-away hole floor.** No new surfaces are
  created or managed down in the hole. This matches what exists: today's down-skirt is
  drawn at the edge and dropped a fixed depth; it never reconstructs the hole floor.
  The beam rises from the rim.
- **Classification is a global reachability test, not a local probe.** A drop edge is
  a hole iff the surface an entity would fall onto is *not* in the reached set (the
  flood output) — the void, an isolated island, or a spot unreachable within the radius
  budget. A drop whose landing surface *is* reached — possibly deeper than `reach`, and
  possibly only via a roundabout path (e.g. stairs elsewhere) — is benign. The flood
  already encodes roundabout reachability, so the test consults the reached set rather
  than a bounded downward `reach` probe; a "within one step" probe would wrongly flag a
  deep-but-roundabout-reachable drop as a hole.

### What exists, and how the classifier relates to it

`SurfaceSelection` computes reachable, dilated, occlusion+headroom-aware
`StandableRect`s via the lazy flood, and classifies wall/ceiling edges compute-side
into `OccluderSpan`s (`computeOccluders` → `occluderSpansForRect` → `wallOccluder` →
`mergeOccluderSpans`). The render widget derives drop edges as `openSpans` (edge minus
equal-height merge seams) minus the occluder intervals, in
`downSpans`/`openSpans`/`subtractSpans` (`CollisionSurfaceOverlay`), and every remaining
sub-span gets the same fading downward skirt.

The hole classifier is a new predicate on that output, not the existing edge op. Each
rect edge is already split three ways — an equal-height merge seam (suppressed in
`openSpans`), a wall/ceiling (upward skirt), and the leftover downward drop sub-spans.
The classifier consumes that drop bucket and splits it again, mirroring `wallOccluder`
(a per-box predicate) with a per-drop-span predicate ("is there a reached surface below
the fall spot?"), reusing the same interval machinery — a sibling stage on the same
substrate.

### Classification of a drop sub-span

- **Hole** — no reached surface below the spot an entity leaving the edge would fall
  onto (void / isolated island / beyond the radius budget). Rendered as a through-walls
  beam.
- **Benign** — the landing surface is reached (however deep, including roundabout),
  split by fall distance `T − landing.topY`:
  - **tall** → a lighter warning marker;
  - **minor** → today's down-skirt, nothing special.
- **Cutoff** — the sub-span sits at the radius boundary where the selection is
  incomplete (already greyed by the ring); never a hole or warning.

### The hole marker: a through-walls vertical beam

A hole is marked by a tall vertical beam rising from the cliff-edge top `T`, drawn
through walls so it reads even when the rim is occluded by terrain:

- It lives in the depth-off `FILLED` pipeline (`withDepthStencilState(Optional.empty())`),
  the same through-walls route the crouch-gated tops use.
- It rises upward from `T`, clamped to a fixed world height, so all geometry stays at
  the rim.
- One beam per hole region (Step 3), not one per raw edge-span, to avoid a picket fence
  along a long rim.

## Steps

All classification — hole vs benign, fall distance, and cutoff — is one predicate over
the reached set (Step 1, wired in Step 2). Everything after is rendering: the beam
(Step 2), one-beam-per-hole regioning (Step 3), and the warning marker (Step 4). Each
step is one commit with its own tests; validate the in-game checklist via
`./gradlew runClient` before committing (`AGENTS.md` → Stage-gating).

### Step 1 — The classifier (all detection)

Add one pure classifier to `SurfaceSelection` over the reached set (post-flood). Given
a drop sub-span (edge line, `[lo,hi]`, base `T`), the full `List<StandableRect>` result,
and the ring/cutoff bounds (seed center + radius + `halfW`, how the grey ring is already
derived), return `CUTOFF`, `HOLE`, or `BENIGN` plus the landing height — the highest
reached `topY` strictly below `T` overlapping the fall footprint, giving the fall
distance for Step 4. No world probing and no `reach` cap on depth. Unit tests cover a
deep-but-roundabout-reachable drop → BENIGN (assert fall distance), a void/isolated drop
→ HOLE, and a boundary drop → CUTOFF. Document the taxonomy in
[`docs/geometry.md`](docs/geometry.md).

- Commit: classifier + tests + `geometry.md` taxonomy. Tests: code only — the function
  is unwired with zero in-world effect (pure-logic exemption per `AGENTS.md`); gate on
  `./gradlew test`. No in-game checklist needed for this commit.

### Step 2 — computeHoles + HoleSpan + through-walls beam (first visible)

Add `computeHoles` mirroring `computeOccluders` but taking the reached set as input:
per reached rect, take the drop sub-spans (`openSpans` minus occluder intervals,
compute-side), classify each via Step 1, and publish the HOLE spans as `List<HoleSpan>`
in the snapshot next to `occluders` (carrying the fall distance for Step 4). In `emit`,
draw the through-walls beam from each hole edge; benign and cutoff edges keep today's
down-skirt. Cutoff correctness is validated here.

- Commit: `HoleSpan` + `computeHoles` + publish + beam draw. Tests: code — the
  compute-side drop-span extraction if pure-testable; in-game — the checklist below.
- In-game checklist (`./gradlew runClient`; cross-cutting every item: no log errors;
  server no-op; no errors on window resize / world change; selection clears on leaving
  the world):
  - [ ] Edge over the void / an isolated pit floor → **beam at the rim**, visible
    **through an intervening wall**.
  - [ ] A deep (`>= 2`) roundabout-reachable drop (a lower floor reached via stairs
    elsewhere) → **no beam** (benign).
  - [ ] A shallow reached trench → **no beam**.
  - [ ] A selection stopped by the **radius** → grey ring, **no beam** at the cutoff;
    raising the radius until the real landing is reached removes any transient beam.
  - [ ] Point / Player / Ravager all behave; no double-marking with occluder up-skirts.

### Step 3 — One beam per hole region

Coalesce the per-edge `HoleSpan`s bounding the same gap into a hole region, so a long
rim raises one beam at a representative point, not a picket fence. Tune beam
height/width/color.

- Commit: span→region coalescing + one-beam-per-region draw. Tests: code — the
  coalescing (adjacent/collinear hole spans → one region); in-game — the checklist below.
- In-game checklist:
  - [ ] A long straight cliff → a small number of beams (not one per merged rect edge).
  - [ ] An L-shaped / wrapping hole rim → one beam per hole.
  - [ ] Beam height is a sensible fixed world height (not scene-dependent).
  - [ ] Regression: Step 2 hole / benign / cutoff split unchanged.

### Step 4 — Warning marker for tall benign drops

Using the fall distance from Step 1, render benign drop edges whose fall exceeds a
threshold with a lighter warning look, distinct from the hole beam (e.g. a
shorter/dimmer beam or a tinted rim). Minor drops stay as today's down-skirt.

- Commit: tall-benign selection by fall distance + warning marker draw. Tests: code —
  the threshold picks the right spans given fall distances; in-game — the checklist below.
- In-game checklist:
  - [ ] A benign tall drop (reached landing, `>= N` blocks) → warning marker, **not** a
    hole beam.
  - [ ] A benign minor drop → **no** warning.
  - [ ] A hole → still the full beam, never the warning.
  - [ ] Threshold reads sensibly; regression: Steps 2–3 split and beams unchanged.

## Key files

- `SurfaceSelection.java` — add `computeHoles` + the pure classifier over the reached
  set, mirroring the `computeOccluders`/`wallOccluder` split.
- New `HoleSpan.java` — analogous to `OccluderSpan.java`.
- `widgets/CollisionSurfaceOverlay.java` — consume `HoleSpan`s in `emit`; draw the beam
  via the depth-off `FILLED` pipeline (same route as the crouch-gated tops).
- Tests under `src/test/java/com/example/overlay/client/`.
- Docs: [`docs/geometry.md`](docs/geometry.md) (taxonomy/model),
  [`docs/rendering.md`](docs/rendering.md) (hole draw), [`docs/project.md`](docs/project.md)
  (status).
