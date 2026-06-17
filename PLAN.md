# PLAN: Milestone 4.5 — occluder-aware skirts + entity-height headroom

Two bundled changes to the standable-surface overlay, done in this order:

1. **Occluder-aware skirts (Part A)** — fix a skirt bug and add **upward skirts**
   where a surface sits against a wall/occluder (today it wrongly drops a
   downward skirt there). Done **first**, as a visualization aid.
2. **Entity-height headroom (Part B)** — make the surface compute height-aware
   (a top survives only where the entity's body column above it is clear).

Skirts go first on purpose: rendering the occluders that border a surface lets
you *see* the geometry headroom then reasons about, so Part B is verifiable by
eye. Part A's per-edge wall-vs-drop classification is also the **prerequisite
for Milestone 5 (holes)** — the drop-classified edges are the hole candidates
(see `plan2.md`).

Builds on Milestone 4 (config-space dilation + occlusion-aware tops + lazy
flood). Durable knowledge lives in [`AGENTS.md`](AGENTS.md) and
[`docs/geometry.md`](docs/geometry.md)/[`docs/rendering.md`](docs/rendering.md);
this file is the transient working plan.

---

## Part A — occluder-aware skirts

### The bug

`openSpans`
([`CollisionSurfaceOverlay.java:385`](src/client/java/com/example/overlay/client/widgets/CollisionSurfaceOverlay.java))
suppresses a skirt only where an edge is shared with an **equal-height**
neighbour (`|nb.topY - r.topY()| > SKIRT_EPS` → skip). So an edge **against a
wall** (a collision box rising above `T` in the adjacent cell) drops a
**downward** skirt — reading as a drop when it is actually an occluder going
**up**.

### The fix: classify each open edge

Per surface edge at height `T`, look at what is across it:

| Across the edge | Skirt |
| --- | --- |
| equal-height reachable neighbour | none (today) |
| reachable neighbour within `reach` (a step) | downward (riser, today) |
| **box rises above `T` (a wall/occluder)** | **upward (NEW)** — wall face |
| open/lower or void (a drop) | downward (today; M5 hole candidate) |

### Where it lives (architecture)

Detecting "a box rises above `T` across the edge" needs collision-box data, and
`emit` (render thread) may only read the immutable snapshot — it cannot query the
`Level` (threading contract; today's `openSpans` is render-side only because it
compares rects to rects). So:

- Compute the occluder classification on the **client/extraction thread**, in
  or beside `SurfaceSelection` (it already holds the per-column box index and the
  `occluderColumns` window). For each reached surface edge, find adjacent boxes
  whose span rises above `T` and record the **occluder skirt spans** (edge
  sub-spans + the occluder top height).
- **Publish** them in the snapshot alongside the rects. Minimal approach: keep
  the existing render-side `openSpans` for the *downward* skirts, and add a
  published list of *upward* occluder spans; `emit` draws upward skirts for those
  and removes them from the downward set so an edge isn't double-skirted. (Cleaner
  end-state: publish a single `SurfaceMesh { tops; downSkirts; upSkirts }` and
  move all skirt-diffing to the compute side — note it, but the minimal path is
  fine for 4.5.)

### Rendering

Draw the occluder marker at the surface's **dilated edge** — pulled `~W/2` off
the wall, toward the middle of the block — **not** at the wall face. (Point
`W = 0`: the edge sits at the wall.) It is an **upward** skirt (`yBot = T`,
`yTop = T + height`), double-winding, in the depth-tested `SKIRT` layer, a
distinct shade from the downward drop skirts. Like the downward skirts (solid at
the surface, fading away from it), the upward skirt is **solid at its base `T`
and fades to transparent at its top** — every height fades out at the top.

To settle the final look, add a **debug keybind** (Fabric `KeyBindingHelper`,
client-only) — a **standalone key**, registered in `OverlayClient`, whose single
press **increments** the occluder-marker style (wrapping). It is **not** tied to
the scroll handler. Styles to A/B:

1. **tiny** — a few px tall (default candidate);
2. **half-block** — `0.5` tall;
3. **full** — up to the occluder top, clamped to `~reach + SKIRT_MARGIN` so a
   tall wall isn't a giant curtain;
4. **bold line** — no skirt, just a thick edge outline (no fade).

Compare in-game, then **settle on exactly one** and drop the toggle (or keep it
as a debug-only option). The style is a render-thread choice (a `volatile`
index the key increments), so it doesn't touch the published occluder spans.

### Decided / open

- **Style:** tiny upward skirt by default; a single debug key increments through
  tiny / half-block / full / bold-line to pick the final one. All upward
  (skirt) styles fade to transparent at the top.
- **Placement:** at the dilated (set-back) surface edge, mid-block off the wall;
  occluder detection uses the same dilated `occluderColumns` window `exposeBox`
  uses, so the wall is found even when the surface is set back. **(Confirmed.)**
- **Open:** snapshot shape — minimal (render-side `openSpans` for downward skirts
  + published upward spans) vs. a unified `SurfaceMesh`; leaning minimal for 4.5.

---

## Part B — entity-height headroom

Make the surface compute **height-aware**: a box top at height `T` is standable
only where the entity's body column `(T, T+H]` is clear of collision boxes
(`H` = entity height). This generalizes the occlusion already in the codebase —
the spans-above/buried test is exactly the `H = 0` case — reusing the dilation +
guillotine-subtract machinery, not a new subsystem. It only changes *which tops
survive* `exposeBox`; reach/flood/merge are untouched.

The whole occlusion pass is one predicate in `exposeBox`
([`SurfaceSelection.java`](src/client/java/com/example/overlay/client/SurfaceSelection.java)):
today a box buries the top at `T` where it spans that level
(`other.yMin <= T < other.yMax`). Headroom widens the window from *the single
level `T`* to *the standing column `(T, T+H]`*:

```
// occluder iff it intersects (T, T+H]
other.yMax() > T + EPS  &&  other.yMin() < T + H - EPS
```

- `H = 0` reproduces today's buried test exactly, so **Point stays identical**
  (A/B baseline + oracle parity preserved).
- The box you stand on (`yMax == T`) is excluded by the strict `yMax > T`.
- Occluder footprints are **already dilated by `W/2`**, so a wide entity's
  headroom is correctly eaten by overhangs near it — for free.
- Partial headroom (overhang over half a floor) yields a **partial surface** via
  the existing `subtractRects` guillotine.
- Boundary: a box bottom exactly at `T+H` does **not** block (just-enough
  clearance) — hence `< T + H - EPS`.

Direction note: this is *not* "extend occluders upward" — a ceiling robs headroom
from the floor **below** it (the removal zone extends downward by `H`).

### Where it plugs in (files)

- **`SurfaceSelection.exposeBox`**: add a `double height` param; change the
  predicate to the headroom interval. `occluderColumns` is XZ-only, unchanged.
- **`selectEager`**: pass `profile.height()` into its two `exposeBox` calls. **No
  window change** — it already gathers the full `[yLo,yHi]` band per column.
- **`LazyFlood`**: hold `height`; pass through `tops()` → `exposeBox`. **One
  bounded change**: `tops()` exposes the occluder shell only at `row-1..row+1`
  around the box's own top; extend the upper bound to `floor(box.yMax + height)
  + 1` so headroom occluders above the top are scanned before `exposeBox`.
  `collect`/`ensureRows` for *candidate* tops are unchanged.

---

## Profiles

Add `height` to
[`EntityProfile`](src/client/java/com/example/overlay/client/EntityProfile.java)
(the record already notes height is "reserved for later"):

| Profile  | width | height | reach |
| -------- | ----- | ------ | ----- |
| Point    | 0.0   | 0.0    | 1.0   |
| Player   | 0.6   | 1.8    | 1.0   |
| Ravager  | 1.95  | 2.2    | 1.0   |

`Point` keeps `height 0` so it stays the pure point-walker and the oracle
baseline. (Heights are the vanilla hitbox heights; doubles are fine — no
quantization, per the rect-space model.) `height` feeds Part B; `reach` already
sets the (downward) skirt depth and now the upward-skirt clamp.

---

## Testing strategy

Three gates, matched to the kind of work (see `AGENTS.md` → Testing / Stage-gating):

- **Unit tests (`fabric-loader-junit`)** for **pure logic** — the new occluder-edge
  classification and the headroom predicate, plus backfill for the existing rect ops
  (`subtractRects` / `union` / `mergeCoplanar` / `footprintAdjacent`). These operate on
  plain records (`WorldBox` / `Rect` / `StandableRect`), so tests build **synthetic**
  inputs — no world, no game loop. This is the first unit test in the repo; wiring is a
  one-time infra add (`testImplementation "net.fabricmc:fabric-loader-junit:${loader_version}"`
  + `test { useJUnitPlatform() }`), reused by M5.
- **`PROFILE_FLOOD` oracle** (already exists) for the **flood** — eager-vs-lazy coverage
  parity, the gate for the headroom change (both paths share `exposeBox`).
- **In-game checklist** (`runClient`) for everything **visual** (skirt rendering, the
  debug keybind) and the world-reading boundary (`collect` / `ensureRows` /
  `getCollisionShape`).

To make the pure logic testable, **factor it out of the world-reading code**: e.g. a
classification function `(surface rect, neighbour WorldBoxes, halfW, reach) -> {down
spans, up spans}` with no `Level` access, called by `SurfaceSelection`. (A real
mini-world **GameTest** of the full `select()` against placed blocks is possible but
heavier — out of scope for 4.5; keep the oracle + in-game for the world boundary.)

## Stages

> Each stage closes on a concrete gate — a unit test, the oracle, or an in-game
> check — never "looks right" (see `AGENTS.md` → Stage-gating). Pure-logic stages
> gate on `./gradlew test`; visual stages on `runClient`; all on `./gradlew build`.

### Stage 0 — test harness (infra, no behavior change)

Wire `fabric-loader-junit` (`testImplementation` + `useJUnitPlatform`) and add a few
sanity unit tests over the **existing** pure ops (`subtractRects`, `union`,
`mergeCoplanar`, `footprintAdjacent`) with synthetic rects — proves the harness and pins
current behavior. **Gate:** `./gradlew test` runs and passes; `./gradlew build`.

### Stage A1 — occluder classification (pure logic)

Factor the per-edge wall/drop/step classification into a pure function over
`WorldBox`/`Rect` (no `Level`), producing the down-skirt and up-skirt (occluder) spans.
Unit-test with synthetic boxes:

- wall rising above `T` across the edge → an **up** span;
- deep drop / void across → a **down** span, no up span;
- within-`reach` lower neighbour (step) → down riser, not an up span;
- equal-height neighbour → neither (suppressed);
- dilation (`W > 0`): a wall one cell beyond the set-back edge is still found (via the
  `occluderColumns` window) and the up span sits at the set-back edge.

**Gate:** unit tests + `./gradlew build`. **No rendering yet.**

### Stage A2 — occluder rendering (visual)

Publish the occluder spans in the snapshot (minimal: keep render-side `openSpans` for
down skirts, subtract the up spans to avoid double-skirting); draw the **upward skirt**
(tiny default, solid at `T` → fading out at top) in the depth-tested layer; add the
standalone **debug key** that increments the style (tiny / half-block / full / bold-line).

In-game checklist:

- **Wall edge:** floor next to a block rising above it → that edge shows an **upward**
  marker, **not** a downward skirt.
- **Drop edge:** floor next to a deep drop / the void → **downward** skirt (as today).
- **Step edge:** floor next to a within-`reach` lower floor → downward riser (as today);
  equal-height continuation → **no** skirt (no regression).
- **Placement / profiles:** Player/Ravager (`W > 0`) → upward marker `~W/2` off the wall
  (mid-block, set-back edge); Point (`W = 0`) → at the wall.
- **Debug key:** single press increments tiny / half-block / full / bold-line live; skirt
  styles fade out at the top. Compare and note which to keep.
- **Cross-cutting + gate:** server no-op; no errors on resize/world change;
  `./gradlew build`.

### Stage B1 — `EntityProfile.height` (scaffold, no behavior change)

Add the `height` field + accessor and the three values (Point 0 / Player 1.8 / Ravager
2.2); nothing consumes it yet. **Gate:** `./gradlew build` + a trivial profile-values
unit test.

### Stage B2 — headroom logic

`exposeBox` predicate → headroom interval, `LazyFlood.tops()` shell-row extension, and
`profile.height()` wired through `selectEager`/`LazyFlood` — they land together (shared
`exposeBox`, else lazy under-exposes occluders and mismatches eager). The Stage A2
upward skirts now visualize the occluders being tested.

- **Unit test:** the headroom predicate — a candidate top + synthetic occluder boxes at
  various heights → expected survival / partials.
- **Oracle:** flip `PROFILE_FLOOD = true`, confirm `match=true` for Point/Player/Ravager
  across radii (0..20) in the scenes below, flip off for the commit.

In-game checklist:

- **Point (H=0):** identical to pre-Stage-B2 everywhere (A/B baseline). Oracle
  `match=true`.
- **Player (H=1.8), 1-block gap:** floor with a solid block exactly 1 above → **not**
  painted; remove the ceiling → it paints (the Stage A2 skirt marks the ceiling).
- **Player, 2-high tunnel:** floor → **painted**. **Slab ceiling (< 1.8):** → **not**.
- **Ravager (H=2.2), 2-high tunnel:** → **not** painted; 3-tall → painted.
- **Partial overhang:** half-covered floor → only the uncovered half painted;
  Point→Player→Ravager shrinks it by ~`W/2` more.
- **Covering box's own top:** still painted where it has its own headroom.
- **Cross-cutting + gate:** server no-op; no errors on resize/world change;
  `./gradlew build`; `./gradlew test`; `PROFILE_FLOOD` `match=true`.

### Stage C — docs

- `docs/rendering.md`: occluder-aware skirts (edge classification, upward vs downward
  skirts, compute-side occluder spans in the snapshot); note the wall-vs-drop
  classification is the M5 hole prerequisite.
- `docs/geometry.md`: the headroom rule (top at `T` survives only where `(T, T+H]` is
  clear of dilated boxes; partial → partial; `H=0` = current buried test), the `height`
  column, the lazy shell-row extension note.
- `AGENTS.md`: add **Milestone 4.5** to Current status; update the `EntityProfile`
  mention; refresh the in-world acceptance bullet; note unit tests + `./gradlew test`
  under **Testing** (now wired up).
- Code comments: the headroom predicate boundary (`yMax > T` strict, `< T+H`), the
  `tops()` shell-row extension, the occluder-span classification.
- Clear/refresh this `PLAN.md`.

---

## Risks / notes

- **Snapshot shape change (Part A):** the published snapshot gains occluder/upward
  skirt spans; keep the render-side `openSpans` for downward skirts and subtract
  the upward spans so edges aren't double-skirted (or adopt the unified
  `SurfaceMesh` end-state).
- **No render-thread world access:** occluder detection must be compute-side and
  published; do not query `Level` in `emit`.
- **Headroom lower bound strict** (`yMax > T + EPS`) or every surface
  self-occludes; coplanar neighbours would falsely cut each other.
- Heights are doubles, not `1/16`-aligned — consistent with the rect-space model.
- **Lazy cost (Part B)** rises modestly: ~`H` extra occluder-shell rows per
  exposed box (`rowsScanned` ticks up). Still output-sensitive.
- `boxSurfaces` memoization is per-box and `height` is fixed per `select`, so no
  cache-key change.
- Headroom is orthogonal to reach/flood/merge — a surface dropped for lack of
  headroom simply isn't a node; connectivity falls out unchanged.
