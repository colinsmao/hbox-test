# MobWalk (Fabric Mod)

A client-side overlay mod for Minecraft built on the
[Fabric](https://fabricmc.net/) toolchain. It draws both on the in-game HUD and
in the 3D world, with small frameworks that make adding more overlay widgets
easy.

## Status

Milestones 1 and 2 done: the mod builds and runs. `./gradlew build` produces
`build/libs/mobwalk-1.0.0.jar`, and `./gradlew runClient` launches a dev
client that draws:

- **HUD (Milestone 1):** a demo overlay (a box + "MobWalk" label) in
  the top-left of the HUD, which hides when you press F1.
- **In-world (Milestone 2):** a ring (annulus) flat on the top face of the block
  under your crosshair, shown only while holding a stick; right-clicking the
  stick cycles the ring's color (and swings your arm).

See **[PLAN.md](PLAN.md)** for the full design: target versions, project
structure, the overlay framework, and build / install / test instructions.

## Quick start

```bash
./gradlew build        # build the mod jar
./gradlew runClient    # launch a dev client with the mod loaded
```

Requires a JDK 25 installation (`java -version` should report 25). See
[PLAN.md](PLAN.md) for details.
