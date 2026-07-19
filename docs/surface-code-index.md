# Surface / overlay code index

Dense header-file style map of the client surface/overlay code: what each file,
type, and method does. Detail lives in [`geometry.md`](geometry.md),
[`rendering.md`](rendering.md), and [`settings.md`](settings.md).

Thread contract: compute and input run on the client (extraction) thread; the
render thread reads published immutable snapshots via `volatile` fields.

```
MobWalkClient / Configs / scroll / /mobwalk dump
        │
        ▼
CollisionSurfaceOverlay  ←→  OverlayManager / RadiusIndicatorOverlay (HUD)
        │ select / clear
        ▼
SurfaceSelection
        │ StandableRect, OccluderSpan, DownSkirtSpan, HoleSpan
        ▼
WorldOverlayManager (FILLED / SKIRT / BEAM) → GPU
```

---

## File inventory

```
// main
MobWalk.java                                   // MOD_ID + LOGGER (shared main/client)

// client — bootstrap / settings
MobWalkClient.java                             // ClientModInitializer: managers, scroll, command, HUD root
InitHandler.java                               // MaLiLib IInitializationHandler
Configs.java                                   // IConfigHandler + live helpers (options in settings.md)
GuiConfigs.java                                // MaLiLib GuiConfigsBase + wand / table widgets
MobWalkModMenuIntegration.java                 // ModMenu Configure → GuiConfigs
WandItem.java                                  // wand item-id parse / resolve
ProfileRoster.java                             // builtin + custom profile roster
RosterProfileOption.java                       // MaLiLib list entry over roster ids
BuiltinProfilesTableEdit.java                  // GuiTableEdit for builtin table
CustomProfilesTableEdit.java                   // GuiTableEdit for custom table
ProfilesTableEdit.java                         // shared table editor → ProfilesTableEditEntry
ProfilesTableEditEntry.java                    // per-row RESET always disabled
CustomProfileTableRows.java                    // custom row builders / ADD seed

// client — HUD framework
Overlay.java                                   // HUD widget interface
OverlayManager.java                            // HUD registry + render loop
widgets/RadiusIndicatorOverlay.java            // transient crosshair text (radius / profile / toggles)

// client — world framework
WorldOverlay.java                              // in-world widget interface (extract / emit / onUseItem)
WorldOverlayManager.java                       // world registry, use-key edge, three GPU layers

// client — surface data records
EntityProfile.java                             // width / height / reach + legacy Option list
StandableRect.java                             // reached standable patch
OccluderSpan.java                              // upward (wall/ceiling) skirt span
DownSkirtSpan.java                             // downward drop-skirt span
HoleSpan.java                                  // hole-beam span

// client — surface compute + widget
SurfaceSelection.java (~1901)                  // flood, skirts, holes; holds last-select lists
widgets/CollisionSurfaceOverlay.java (~668)    // wand input, publish snapshots, emit geometry

// test (pure logic against SurfaceSelection / EntityProfile / roster / records)
EntityProfileTest.java
ProfileRosterTest.java                         // sanitize / cycle / unique names
WandItemTest.java                              // resolve / stick fallback
CustomProfileTableRowsTest.java                // ADD seed index helper
SurfaceGeometryTest.java                       // subtract / union / merge / flood / frontier / depth
HeadroomTest.java                              // exposeBox headroom / burial
VisualTopTest.java                             // visualTopY raise / merge carry
OccluderClassificationTest.java                // occluderSpansForRect / mergeOccluderSpans
DownSkirtComputeTest.java                      // computeDownSkirts
DropClassificationTest.java                    // classifyDrop
HoleFootprintTest.java                         // fallFootprint
HoleSubSpanTest.java                           // holeSubSpans
```

---

## MobWalk.java

```
String MOD_ID = "mobwalk"
Logger LOGGER
```

---

## MobWalkClient.java

```
onInitializeClient()
  OverlayManager.bootstrap()
  WorldOverlayManager.bootstrap()
  InitializationHandler ← InitHandler
  /mobwalk dump → dumpFloodDebug + chat summary
  ClientHotbarScrollEvents.ALLOW → wantsRadiusScroll / adjustRadius + HUD radius
  HudElementRegistry.attachElementBefore(CHAT, OverlayManager::render)
```

---

## Settings stack

Option inventories and JSON shape live in [`settings.md`](settings.md).

```
// InitHandler.java
registerModHandlers()
  ConfigManager.registerConfigHandler(MOD_ID, Configs)
  Registry.CONFIG_SCREEN ← ModInfo → GuiConfigs::new
  Configs.initCallbacks()

// MobWalkModMenuIntegration.java
getModConfigScreenFactory()                    // parent → new GuiConfigs (setParent)

// GuiConfigs.java
initGui()                                      // refreshDisplayNames; tab buttons
getAllConfigs() / getConfigs()                 // options for All / General / Appearance / Debug
useAllTab()
enum ConfigGuiTab { ALL, GENERAL, APPEARANCE, DEBUG }
record ButtonListener(tab, parent)             // switches tab and rebuilds list
ItemIdConfigOption                             // wand text field + WandItem.applyInvalidTooltip
ConfirmResetConfigOption                       // table RESET confirm → Builtin/CustomProfilesTableEdit

// Configs.java
enum ShowSurfaces { NEVER, WHILE_HOLDING_WAND, ALWAYS }
class Generic / Profiles / Appearance / Debug  // option holders (see settings.md)
refreshDisplayNames()
initCallbacks()                                // wand / radius / profile / drawOnVisibleFace /
                                               // profile-table live apply
syncAfterProfilesTableReset()
configTableIsModified(table)
roster() / hasEnabledProfile()
showSurfaces() / wandItem() / floodRadius()
profileDisplayLabel(id) / mobProfile() / activeProfileId() / cycleMobProfile()
walkableColor() / drawOnVisibleFace()
showBeamsThroughWalls() / showHoleBeams() / holeBeamColor()
downSkirtHeight() / upwardSkirtHeight()
crouchScrollRadius() / crouchSeeThroughWalls() / crouchCycleProfile()
shadeByDepth() / showCutoffRing()
load() / save() → config/mobwalk.json

// WandItem.java
isValid(text) / resolve(text) / applyInvalidTooltip(field)

// ProfileRoster.java
record BuiltinSeed / Entry / RawBuiltinRow / RawCustomRow / SanitizeResult
defaults() / playerDefaultCustomProfile()
builtins() / customs() / allEntries() / enabledEntries()
hasEnabledProfile() / findById(id)
profileIfEnabled(id) / resolveActiveId(activeId)
cycle(currentId) / cycle(currentId, forward)
displayLabel(id) / fallbackActiveId()
sanitize(...) / builtinIdForName(name) / customId(index)

// RosterProfileOption.java
id() / player()
getStringValue() / getDisplayName() / cycle(forward) / fromString(value)

// ProfilesTableEdit / BuiltinProfilesTableEdit / CustomProfilesTableEdit
// ProfilesTableEditEntry / CustomProfileTableRows
  // table UI + customRow / copyCustomRow / seedNewCustomRows
```

---

## HUD framework

```
// Overlay.java
String id()
void render(graphics, delta)
boolean isVisible()                            // default true

// OverlayManager.java
bootstrap()                                    // register RadiusIndicatorOverlay
radiusIndicator()                              // accessor for scroll / keys / profile pings
register(overlay)
render(graphics, delta)                        // iterates visible overlays

// widgets/RadiusIndicatorOverlay.java
show(radius)                                   // "Flood radius: N"; resets 1.5s timer
showProfile(name)                              // "Profile: …" / other short messages
showMessage(message)                           // shared writer for text + expiresAt
id() / isVisible()                             // visible while now < expiresAt
render(graphics, delta)                        // centered under crosshair; fades last 0.5s
```

---

## World overlay framework

```
// WorldOverlay.java
String id()
void extract(LevelExtractionContext)
void emit(matrix, fillBuffer, skirtBuffer, beamBuffer)
boolean isVisible()                            // default true
void onUseItem(Player)                         // default no-op; rising-edge from manager

// WorldOverlayManager.java
RenderPipeline FILLED                          // depth-off POSITION_COLOR quads
RenderPipeline SKIRT                           // depth-on (DEBUG_FILLED_SNIPPET depth)
Layer fillLayer / skirtLayer / beamLayer       // beam reuses FILLED pipeline, drawn last

bootstrap()
  new CollisionSurfaceOverlay; register
  END_EXTRACTION → extract
  AFTER_TRANSLUCENT_TERRAIN → draw
  CLIENT_STOPPING → close
  END_CLIENT_TICK → onClientTick (use-key rising edge → onUseItem)
register(overlay)
collisionSurface()                             // accessor for MobWalkClient / Configs
extract(ctx)                                   // for each overlay.extract
draw(ctx)                                      // camera translate; emit into three buffers; drawLayer×3
onClientTick(client)                           // edge-detect options.keyUse
drawLayer / upload / execute                   // MeshData → MappableRingBuffer → render pass
close()                                        // free layer GPU resources
class Layer                                    // per-pipeline BufferAllocator + ring buffer
```

---

## Shared data records

```
// EntityProfile.java
record EntityProfile(name, width, height, reach)
  // width — horizontal dilation; height — headroom column; reach — step / skirt base
  DEFAULT_JUMP_REACH
  POINT / PLAYER / RAVAGER / WARDEN / ZOMBIE_WITCH / SKELETON
  next()                                       // Point → Player → Ravager → Point (legacy)
  enum Option implements IConfigOptionListEntry
    profile() / getStringValue() / getDisplayName()
    cycle(forward) / fromString(value) / of(profile)
  // Live settings cycle is RosterProfileOption + ProfileRoster

// StandableRect.java
record StandableRect(minX,minZ,maxX,maxZ, topY, visualTopY, depth)
  // topY — collision top (walkability); visualTopY — draw height; depth — BFS hops (−1 unset)
  // overloads default visualTopY=topY and/or depth=-1

// OccluderSpan.java
record OccluderSpan(alongX, positiveSide, line, lo, hi, baseY, topY, visualBaseY, depth)
  // Upward skirt geometry: edge on line with [lo,hi]; baseY=T; topY=occluder yMax
  // overloads default visualBaseY=baseY and/or depth=-1

// DownSkirtSpan.java
record DownSkirtSpan(alongX, maxSide, line, lo, hi, baseY, visualBaseY, depth)
  // Downward skirt geometry after seam/occluder subtraction
  // overloads default visualBaseY=baseY and/or depth=-1

// HoleSpan.java
record HoleSpan(alongX, maxSide, line, lo, hi, baseY, fallDistance, visualBaseY)
  // Hole beam geometry; fallDistance = T − landY (0 if void)
  // overload defaults visualBaseY=baseY
```

---

## SurfaceSelection.java (~1901 lines)

Holds the lists from the last `select`: `result`, `occluders`, `downSkirts`,
`holes`. Each `select` replaces them wholesale; the overlay copies them into
`volatile` fields for the render thread.

### Internal types

```
record Rect(minX,minZ,maxX,maxZ)                // XZ rectangle for clip / merge work
record WorldBox(bx,by,bz, minX,minZ,maxX,maxZ, yMin,yMax, blockCollisionTop, blockOutlineTop)
                                               // one collision sub-box; bx/by/bz =
                                               // source block; block*Top = whole-shape tops
record CellSurface(rect, cx, cz)               // dilated top tagged with its source cell
record ColKey(x, z)                            // block-column key for the box index
record SpanKey(a, b)                           // quantized double pair for merge hashing
record SpanGroupKey(alongX, positiveSide, line, baseY)
                                               // key for merging abutting OccluderSpans
enum DropClass { HOLE, BENIGN }
record DropClassification(kind, fallDistance)  // return type of classifyDrop
```

### Entry points

```
void select(level, start, radius, profile, computeVisualTop)
  // Runs LazyFlood, then computeOccluders → computeDownSkirts → computeHoles;
  // optional debug dump
void clear()                                   // empties the four result lists
List<StandableRect>  allRects()
List<OccluderSpan>   allOccluders()
List<DownSkirtSpan>  allDownSkirts()
List<HoleSpan>       allHoles()
void requestDebugDump()                        // next select logs a [flood-debug] block
```

### Flood

```
static flood(rects, seeds, reach)              // BFS over a StandableRect list by adjacency
LazyFlood                                      // nested class: surface BFS with on-demand expose
  run()                                        // seed → BFS → mergeCoplanarSplitFrontier
  collectSeedBlock()                           // tops whose source block Y equals seed Y
  collect(cx, cz, topLo, topHi)                // tops in a column inside a height window
  tops(box)                                    // memoized exposeBox for one WorldBox
  ensureRows(cx, cz, a, b)                     // query collision shapes for rows [a,b] once
  preMergeReached()                            // raw BFS set for /mobwalk dump
```

### Expose (dilation + occlusion + visual top)

```
static exposeBox(target, index, halfW, height, out)
  // Grow footprint by W/2; subtract dilated occluders that bury the top or cut
  // headroom; set visualTopY; append surviving StandableRects
static wallOccluder(box, topY, height)         // yMax > T && (yMin <= T || yMin < T+H)
static occluderColumns(box, halfW) → int[4]    // column window that can overlap a top
static subtractRects(base, occluders)          // guillotine XZ subtract → 0..N Rects
static subtractOne(piece, occluder, out)       // one guillotine cut
static visibleTop(level, pos, state, collTop, compute)
  // Per-BlockState outline-top memo (OUTLINE_TOP_REL); skipped when compute is false
```

### Merge and adjacency

```
static footprintAdjacent(a, b)                 // shared edge or area overlap (ε-tolerant)
static mergeCoplanar(input)                    // group by topY → union → strip-merge
static mergeCoplanarSplitFrontier(nodes, depths, limit)
  // Merge depth < limit and depth >= limit as separate sets; subtract inner area
  // from frontier so the two tile
static union(rects)                            // X sweep, merge overlapping Z intervals
static stripMerge / mergeAlong                 // merge equal-span abutting Rects to a fixpoint
static depthForMerged(merged, raw, rawDepths)  // min raw depth among nodes a merged rect covers
static withDepth(r, depth)                     // StandableRect copy with a depth tag
static coversAnySeed(rect, seeds)
static spanKey / minDepth
```

### Occluder spans

```
computeOccluders(level, rects, profile)        // world scan around each rect → OccluderSpans
static occluderSpansForRect(r, candidates, halfW, height, out)
                                               // project wall/ceiling boxes onto r's edges
static mergeOccluderSpans(spans)               // coalesce abutting spans with the same key
```

### Down-skirt spans

```
static computeDownSkirts(rects, occluders)     // collision-keyed (visual=false)
static computeDownSkirts(rects, occluders, visual)
                                               // visual=true keys edges on visualTopY
static edgeDownSpans(...)                      // one edge minus equal-height abutters minus occluders
static subtractIntervals(lo, hi, covered)      // 1-D interval difference
```

### Hole spans

```
static classifyDrop(fallFootprint, topY, reached, ledges)
  // BENIGN if a reached floor lies under the footprint and no ledge sits in
  // (landY, T); otherwise HOLE (with fall distance when a landing exists)
computeHoles(level, rects, downSkirts, profile, depthLimit)
  // For each drop span below the frontier: fallFootprint → gatherLedges → holeSubSpans
static holeSubSpans(sp, band, reached, ledges, out)
  // Cut the edge at reached-rect boundaries; classify each piece; emit HoleSpans
static fallFootprint(sp)                       // one-block band just beyond the rim
static gatherLedges(level, cursor, fp, landY, topY, halfW, height, out)
  // Collision boxes with tops in (landY, T), run through exposeBox;
  // occluder index starts at floor(landY) − 1
static spanBreakpoints / addCut / fixedAxisOverlaps / subBand
```

### Debug dump

```
logFloodDebug / logFloodDebugRects             // [flood-debug] logger output
```

---

## CollisionSurfaceOverlay.java (~668 lines)

`WorldOverlay` that drives `SurfaceSelection`, publishes snapshots, and emits geometry.

### WorldOverlay hooks and publish

```
id()                                           // "collision_surface"
extract(ctx)                                   // samples wand / crouch / showSurfaces; clears on level change
isVisible()                                    // published visibility flag
publish()                                      // copies cache lists into the volatile snapshots
```

### Input

```
onUseItem(player)                              // wand in main (or empty-main off-hand);
                                               // resolveDownward → select, or clear / sneak-cycle
resolveDownward(level, start)                  // walk down through empty collision shapes
applyFloodRadius(radius)                       // clamp and re-flood (settings callback)
reselectWithMobProfile()                       // re-flood with Configs.mobProfile()
clearSelectionForSoftDisable()                 // drop selection when roster has no enabled profile
wantsRadiusScroll()                            // wand held && sneaking && crouchScrollRadius
adjustRadius(delta)                            // ±1 to 10, then ±2; clamp [0,30]; re-flood; HUD
dumpFloodDebug() → FloodDebugCounts            // arms dump, re-selects, returns list sizes
record FloodDebugCounts(merged, occluders, skirts, holes)
```

### Emit

```
emit(matrix, fill, skirt, beam)                // top fills (+ crouch borders), then
                                               // emitDownSkirts, emitOccluders, emitHoles
emitDownSkirts(skirt, matrix)                  // DownSkirtSpans → faded vertical quads
emitOccluders(skirt, matrix)                   // OccluderSpans → upward quads
emitHoles(beamOrSkirt, matrix)                 // HoleSpans → beams of height BEAM_HEIGHT
```

### Emit helpers

```
quad(...)                                      // double-sided horizontal quad (top / border)
fadedSkirt(...) / vQuad(...)                   // vertical quad, alpha solid then fade
surfaceRgb(depth)                              // walkableColor, or depthColor when shadeByDepth
depthColor(depth) / hsvToRgb(...)              // hue cycles every DEPTH_CYCLE (20) rings
inCutoffRing(depth, limit)                     // depth > limit − 2
greyBlend(rgb, depth, limit)                   // blend toward grey at limit−1 and limit
```

---

## Unit tests (`src/test/...`)

```
EntityProfileTest          // shipped sizes/reach; next(); Option cycle / fromString / of
ProfileRosterTest          // defaults, resolve/cycle, unique names, sanitize
WandItemTest               // valid / bare / unknown → stick
CustomProfileTableRowsTest // ADD seed source index
SurfaceGeometryTest        // subtractRects, union, mergeCoplanar, footprintAdjacent,
                           // flood reach cases, depthForMerged, mergeCoplanarSplitFrontier,
                           // DownSkirtSpan depth inheritance
HeadroomTest               // exposeBox burial / headroom / partial overhang / Point parity
VisualTopTest              // visualTopY raise rules; merge max visual; ctor defaults
OccluderClassificationTest // occluderSpansForRect cases; mergeOccluderSpans
DownSkirtComputeTest       // computeDownSkirts full / seam / partial / occluder subtract
DropClassificationTest     // classifyDrop HOLE vs BENIGN cases
HoleFootprintTest          // fallFootprint band per edge side
HoleSubSpanTest            // holeSubSpans split / coalesce / ledge cases
```
