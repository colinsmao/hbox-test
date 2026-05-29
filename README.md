# Graphics Overlay (Fabric Mod)

A minimal, client-side graphics overlay mod for Minecraft built on the
[Fabric](https://fabricmc.net/) toolchain. The first milestone is a mod that
loads under Fabric and draws a simple element on the in-game HUD, with a small
framework that makes adding more overlay widgets easy.

## Status

Planning. See **[PLAN.md](PLAN.md)** for the full implementation plan, including
target versions, project structure, the overlay framework design, and build /
install / test instructions.

## Quick start (once implemented)

```bash
./gradlew build        # build the mod jar
./gradlew runClient    # launch a dev client with the mod loaded
```

Requires a JDK 25 installation. See [PLAN.md](PLAN.md) for details.
