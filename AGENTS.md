# AGENTS.md

Guidance for AI agents working in this repository.

## What this project is

A minimal, **client-side graphics overlay** mod for Minecraft, built on the
[Fabric](https://fabricmc.net/) toolchain. Milestone 1 is intentionally small:
load under Fabric and draw a simple element on the in-game HUD, with a small
framework so more overlay widgets are easy to add.

Read **[PLAN.md](PLAN.md)** first — it is the source of truth for scope,
architecture, versions, and the build/test plan. Keep `PLAN.md` updated when
plans change.

## Current status

Planning stage. As of this writing the repo contains only `README.md`,
`PLAN.md`, and this file. **No Gradle project or mod code exists yet.** When you
scaffold it, follow the structure in `PLAN.md` §4 and generate from the official
`FabricMC/fabric-example-mod` template (or <https://fabricmc.net/develop>).

## Target versions (confirm before building)

Versions move fast for Fabric. Always re-check <https://fabricmc.net/develop>
and the latest `FabricMC/fabric-example-mod` tag before building.

| Component     | Version          |
| ------------- | ---------------- |
| Minecraft     | `26.1.2`         |
| Fabric Loader | `0.19.2`         |
| Fabric Loom   | `1.16-SNAPSHOT`  |
| Fabric API    | `0.149.1+26.1.2` |
| JDK           | `25`             |

> Mismatched JDK is the most common setup failure — verify `java -version` is 25
> before debugging build issues.

## Build & run (once the Gradle project exists)

```bash
./gradlew build        # compile + produce build/libs/*.jar (CI gate)
./gradlew runClient    # launch a dev client with the mod loaded
```

Use the Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows); do not assume
a system Gradle is installed.

## Key technical constraints

- **Client-only.** The mod must never be required on a server. Use
  `"environment": "client"` in `fabric.mod.json`, declare only a `client`
  entrypoint, and put client code in the Loom `client` source set
  (`splitEnvironmentSourceSets()`).
- **HUD rendering API:** use the current **`HudElementRegistry`** API. The
  legacy `HudRenderCallback` is **deprecated (Fabric API 0.116+) — do not use
  it.** Attach relative to `VanillaHudElements` (e.g. `attachElementBefore(...,
  VanillaHudElements.CHAT, ...)`) so the overlay inherits the vanilla "hide HUD"
  (F1) render condition.
- **Stay high-level:** draw via `GuiGraphics` (`fill`, `drawTextWithShadow`).
  Avoid low-level `RenderSystem` calls — Mojang is migrating to an
  extract/draw rendering pipeline and low-level APIs are churning.
- **Extension framework:** new overlays implement the `Overlay` interface and
  register via `OverlayManager`; keep all Fabric-API contact inside
  `OverlayManager` so widgets stay decoupled. See `PLAN.md` §4.

## Conventions

- Package base: `com.example.overlay` (mod id `overlay`). If the user provides a
  real maven group / mod id / author, update `PLAN.md` and code consistently.
- Don't add code comments that merely narrate what the code does.
- Don't introduce third-party rendering libraries for Milestone 1.

## Git / workflow

- Branch naming for agent work: `cursor/<descriptive-name>-3c2f` (lowercase),
  branched off `main`.
- Commit logical changes separately with clear messages; do not force-push or
  amend unless asked.
- After pushing, open/update a PR against `main`.
- Verify `./gradlew build` passes before considering a code change complete.
