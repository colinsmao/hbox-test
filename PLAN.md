# PLAN

The current plan, committed so it can be handed between agents and machines through git
(cloud agent to local dev agent). Work runs one step at a time: each step carries its own
enumerated in-game checklist, is validated in-game, and is its own commit (see
[`AGENTS.md`](AGENTS.md) → Stage-gating). The **Status** lines say where the work stands.
Durable knowledge lives in `docs/`, so this file is cleared once the plan lands.


# *(No active plan)*


# Hazards

- exists: holes
- generalize visual marker (beam for all hazards?)
  - generalize settings. enable + color for all
- magma
- soul sand
  - need to how collision works. does only part of the hitbox need to be touching to be slowed? in which case, need to use full dilated rect
  - extension: effects through 0.5 blocks
- water, lava
  - walkable water
- (maybe) fall damage
- not in scope: non-collision hazards (powdered snow, berry bushes, fire)

## Ideas / backlog
- Chunked / multi-tick flood so it doesn't stutter.
- Auto update (eg flood from feet every N ticks)
- Hazards
- Settings tooltip UX pass (tone/length).
- Probably out of scope:
  - ladders/vines
  - scaffolding
  - non-collision hazards (eg berry bushes)
  - fall damage
- Definitely out of scope:
  - horizontal velocity when jumping (ie parkour)
  - pathfinding
