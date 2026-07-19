# PLAN - Currently empty

## Ideas / backlog

- Chunked / multi-tick flood so one raised block doesn't repay a full-flood scan
(deferred with the general flood-perf frame-slicing below).
- Hazards
- Settings tooltip UX pass (tone/length).

## Step 4 boundary audit (Pass A — record only)

Cross-package edges after the `config` / `overlay` / `surface` split. No reorganization
in this step; notes for a later cleanup pass.

### Real edges (kept)

| From | To | Why |
|---|---|---|
| `client` (MobWalkClient) | config, overlay, surface | bootstrap: managers, dump, scroll, save |
| `client` (InitHandler) | config | MaLiLib register `Configs` / `GuiConfigs` |
| `config.Configs` | `overlay.WorldOverlayManager` | live-apply → `collisionSurface()` |
| `config.Configs` | `surface.CollisionSurfaceOverlay` | concrete live-apply API |
| `config.{ProfileRoster,CustomProfileTableRows,RosterProfileOption,Configs}` | `surface.EntityProfile` | roster geometry type |
| `overlay.WorldOverlayManager` | `surface.CollisionSurfaceOverlay` | owns/registers the world widget |
| `surface.CollisionSurfaceOverlay` | `config.Configs` | wand/mode/radius/profile live reads |
| `surface.CollisionSurfaceOverlay` | `overlay.{WorldOverlay,OverlayManager}` | implements world hook; HUD profile toast |
| `surface.SurfaceEmitter` | `config.Configs` | appearance/debug draw flags + colors |

### Coupling notes (future cleanup candidates)

- **`EntityProfile` lives in `surface` but is mostly a config/roster concept.** Config reaches into surface for the type; surface compute needs it too. A shared `client.model` (or moving the record next to roster) would cut the config→surface type dependency.
- **`CollisionSurfaceOverlay` straddles surface + overlay.** It is the surface input/lifecycle driver *and* a `WorldOverlay` that pokes `OverlayManager` for HUD toasts. Splitting “selection driver” from “world-overlay adapter” would let `overlay` depend on a thinner surface API.
- **`Configs` → concrete `CollisionSurfaceOverlay`.** Live-apply callbacks know the widget type, not an interface. An apply-hook / listener on the overlay side would invert that edge.
- **`surface` → `config` is bidirectional with the live-apply edge above.** Emitter/overlay reading `Configs` for draw flags is expected; the reverse (config driving surface) is the heavier structural smell.
- **Heuristic import rewrite** initially pulled javadoc-only / comment-name matches (`Overlay`, `MobWalkClient`, `SurfaceSelection`, `ProfileRoster`). Those unused imports were removed in Pass A.

### Visibility widenings forced by the split

- Pass A: `Configs()` constructor: package-private → `public` (`InitHandler` in `client` constructs it).
- Pass B (tests): none — tests moved into the same packages as their package-private owners.
