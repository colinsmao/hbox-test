# MobWalk

A client-side Fabric mod that shows where a chosen mob can safely stand and walk.

Right-click a block with the wand and MobWalk paints every surface the chosen mob
could reach from there, and marks inescapable holes and hazards such as soul sand.

<!-- Screenshots: add them here as ![alt](https://raw.githubusercontent.com/kelianmao/mobwalk/main/docs/images/FILE.png) so they render on Modrinth too. -->

- **Green** - surfaces the mob can stand on, with a band hanging down the side of
  a drop and rising up against a wall.
- **Red beams** - holes: drops that a mob walking off that edge could not climb
  back out of.
- **Blue** and **orange** - water and lava, painted as swimmable and marked as
  hazards.
- **Brown** and **gold** - soul sand and magma blocks.

Every color, and whether each hazard is marked at all, is configurable.

## Requirements

- Minecraft `26.1.2` with [Fabric Loader](https://fabricmc.net/use/installer/)
- [Fabric API](https://modrinth.com/mod/fabric-api), [MaLiLib](https://modrinth.com/mod/malilib), and [ModMenu](https://modrinth.com/mod/modmenu)

MobWalk is purely client-side.

## Controls

The wand is a stick by default, and you can set it to any item.

- **Right-click a block** with the wand: paint the surfaces around it.
- **Right-click air**: clear the selection.
- **Sneak + scroll** while holding the wand: grow or shrink how far it checks.
- **Sneak + right-click air**: switch to the next mob.
- **Sneak**: see the painted surfaces through walls.

Five mobs are built in (Player, Ravager, Warden, Zombie/Witch, and
Skeleton), and you can add your own with a width, height, and vertical reach.
Surfaces show while you hold the wand, or can be set to always show.

More details of each setting may be found in the menu tooltips.

## AI usage

The code was written with Cursor, primarily using Opus and Grok. Mainly because
it saves me having to learn the Fabric API. I also used this project to learn how to
use coding agents. Planning artefacts etc can be found in git history.

90%+ of the actual design and scope is mine: the walkability geometry and logic,
what gets drawn and how, all the settings options, etc. After all, that's the fun part.
Also, even Opus still sucks at abstract planning.

## Building and contributing

`./gradlew build` puts the jar in `build/libs/`. [`AGENTS.md`](AGENTS.md) holds the
working rules and [`docs/project.md`](docs/project.md) the project facts.

Released under CC0-1.0.
