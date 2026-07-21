# PLAN - Frontier flag greying + occluded-seed flood

## Step 1 - Cutoff grey uses the frontier ownership flag

Promote merge `RadiusTier` onto published geometry as `frontier`, and key cutoff
greying / ring suppression on that flag (not `depth >= limit` / half-grey at
`limit−1`).

### In-game checklist

1. Flat floor, Flood Radius ≥ 3, Debug `showCutoffRing` on → inner fill is
   uniform walkable color (no inward grey wash); only the frontier-flagged band
   is fully grey.
2. Debug `showCutoffRing` off → frontier hidden; inner remains continuous and
   fully colored.
3. Run `gradlew.bat test` → all pure-logic tests pass.
4. Run `gradlew.bat build` → compilation, tests, resource processing, and jar
   production pass.

## Step 2 - Click origin is the first flood step (non-emitted probe)

When the clicked block's tops are fully occluded, the click still defines a
non-emitted flood origin; the first expansion enters the exposed-top graph.

### In-game checklist

1. Ravager, soul sand beside full blocks, Flood Radius ≥ 3 → right-click paints
   the surrounding standable floor; occluded soul-sand cell stays unpainted.
2. Player / Point on open soul sand → seeds the raised top at depth 0.
3. Path lip beside soul sand with `drawOnVisibleFace` → raised/flush paint
   correct.
4. `gradlew.bat test` / `gradlew.bat build` pass.

## Ideas / backlog

- Chunked / multi-tick flood so it doesn't stutter.
- Auto update (eg flood from feet every N ticks)
- Hazards
- Settings tooltip UX pass (tone/length).
