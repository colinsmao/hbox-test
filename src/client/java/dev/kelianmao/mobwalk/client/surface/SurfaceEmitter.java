package dev.kelianmao.mobwalk.client.surface;

import dev.kelianmao.mobwalk.client.config.Configs;

import java.util.List;


import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import fi.dy.masa.malilib.util.data.Color4f;

/**
 * Turns a published {@link SelectionSnapshot} into buffer geometry: tops, borders,
 * downward/upward skirts, and hole beams. Color derivation lives in the nested
 * {@link Palette} helper. Reads only the immutable snapshot + render-time
 * {@link Configs} flags — never {@code SurfaceSelection}'s live state.
 */
public final class SurfaceEmitter {
  // Lifts the quads just above the block face to avoid z-fighting with the top
  // surface. Kept as small as possible so the top hugs the real surface.
  private static final double Y_OFFSET = 0.002;
  // An opaque outline drawn around each rect so adjacent surfaces (and the
  // sub-rects of a single block) stay visually separable through the fill.
  private static final float BORDER_ALPHA = 1.0f;
  private static final float BORDER_THICKNESS = 0.045f;

  // Vertical skirts dropped from each surface edge so the selection reads as a
  // 3D mesh. Draw height comes from Appearance downSkirtHeight (0 skips draw).
  // Vanilla direction-dependent face brightness (same factors as block shade):
  // N/S (±Z) 0.8, E/W (±X) 0.6. Tops stay undimmed (Y+ = 1.0).
  private static final float SHADE_NORTH_SOUTH = 0.8f;
  private static final float SHADE_EAST_WEST = 0.6f;
  // Tiny uniform outward push of the (square) skirt edges — NOT a dilation: just
  // enough to lift the skirt off the coplanar terrain side face so it doesn't
  // z-fight. Kept tiny so the gap from the top edge stays invisible and skirts
  // don't visibly overlap neighbours.
  private static final double SKIRT_OFFSET = 0.002;

  // Upward skirts: drawn where a surface edge borders a wall/ceiling (compute-side
  // SkirtSpan UP). Draw height from Appearance upwardSkirtHeight (0 skips draw),
  // clamped to span.maxExtent (wall top above the surface). Peak alpha follows
  // the same fill resolve as tops (walkable / hazard).

  // Hole beam: a vertical marker rising from the cliff-edge top of a hole span.
  // Routed to the depth-off BEAM layer or depth-tested SKIRT layer by Appearance
  // showBeamsThroughWalls. Color/opacity from holeBeamColor.
  private static final float BEAM_HEIGHT = 4.0f;

  private SurfaceEmitter() {
  }

  /**
   * Emit tops (+ crouch borders), then skirts (up and down), and hole beams from
   * the published snapshot.
   */
  public static void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer,
      BufferBuilder skirtBuffer, BufferBuilder beamBuffer,
      SelectionSnapshot snapshot, boolean crouching) {
    if (snapshot.isEmpty()) {
      return;
    }

    boolean showCutoff = Configs.showCutoffRing();
    boolean shadeByDepth = Configs.shadeByDepth();
    // One fill decision for the frame: tops and skirts share this palette.
    Palette.FillColors colors = new Palette.FillColors(
      Configs.walkableColor(),
      Configs.showWaterHazard(), Configs.waterHazardColor(),
      Configs.showLavaHazard(), Configs.lavaHazardColor(),
      Configs.showSoulSandHazard(), Configs.soulSandHazardColor(),
      Configs.showMagmaHazard(), Configs.magmaHazardColor(),
      Configs.showHoleBeams(), Configs.holeBeamColor());

    for (StandableRect rect : snapshot.rects()) {
      if (!showCutoff && rect.frontier()) {
        continue;
      }
      float minX = (float) rect.minX();
      float minZ = (float) rect.minZ();
      float maxX = (float) rect.maxX();
      float maxZ = (float) rect.maxZ();
      float y = (float) rect.visualTopY() + (float) Y_OFFSET;

      Palette.Resolved fill = colors.resolve(
        rect.frontier(), shadeByDepth, rect.depth(), rect.hazard());
      float r = fill.r;
      float g = fill.g;
      float b = fill.b;
      float a = fill.a;

      BufferBuilder topBuffer = crouching ? fillBuffer : skirtBuffer;
      quad(topBuffer, positionMatrix, minX, maxX, minZ, maxZ, y, r, g, b, a);

      if (crouching) {
        float bx = Math.min(BORDER_THICKNESS, (maxX - minX) * 0.5f);
        float bz = Math.min(BORDER_THICKNESS, (maxZ - minZ) * 0.5f);
        quad(fillBuffer, positionMatrix, minX, maxX, minZ, minZ + bz, y, r, g, b, BORDER_ALPHA);
        quad(fillBuffer, positionMatrix, minX, maxX, maxZ - bz, maxZ, y, r, g, b, BORDER_ALPHA);
        quad(fillBuffer, positionMatrix, minX, minX + bx, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
        quad(fillBuffer, positionMatrix, maxX - bx, maxX, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
      }

    }

    emitSkirts(skirtBuffer, positionMatrix, snapshot.downSkirts(), shadeByDepth, colors);
    emitSkirts(skirtBuffer, positionMatrix, snapshot.occluders(), shadeByDepth, colors);

    BufferBuilder beamTarget = Configs.showBeamsThroughWalls() ? beamBuffer : skirtBuffer;
    emitBeams(beamTarget, positionMatrix, snapshot.holes(), colors);
    emitBeams(beamTarget, positionMatrix, snapshot.hazards(), colors);
  }

  // Per-span Appearance color/toggle from BeamSpan.hazard (HOLE → hole settings;
  // WATER/LAVA → hazard show+color; show off skips that kind).
  private static void emitBeams(BufferBuilder buffer, Matrix4fc positionMatrix,
      List<BeamSpan> spans, Palette.FillColors colors) {
    if (spans.isEmpty()) {
      return;
    }
    for (BeamSpan s : spans) {
      Color4f beam = colors.beamColor(s.hazard());
      if (beam == null) {
        continue;
      }
      emitBeam(buffer, positionMatrix, s.alongX(), s.line(), s.lo(), s.hi(),
        s.visualBaseY(), beam);
    }
  }

  // Shared vertical beam drawer. Caller picks buffer (Appearance showBeamsThroughWalls)
  // and color; rise is fixed BEAM_HEIGHT from visualBaseY.
  private static void emitBeam(BufferBuilder buffer, Matrix4fc positionMatrix,
      boolean alongX, double line, double lo, double hi, double visualBaseY, Color4f color) {
    float base = (float) visualBaseY;
    float top = base + BEAM_HEIGHT;
    float xa;
    float za;
    float xb;
    float zb;
    if (alongX) {
      xa = (float) lo;
      xb = (float) hi;
      za = (float) line;
      zb = (float) line;
    } else {
      za = (float) lo;
      zb = (float) hi;
      xa = (float) line;
      xb = (float) line;
    }
    vQuad(buffer, positionMatrix, xa, za, xb, zb, top, base,
      color.r, color.g, color.b, color.a, color.a);
  }

  // Draw published skirts (UP and DOWN) into the depth-tested layer. Length is
  // min(Appearance height for the direction, span.maxExtent). Fade is over the
  // Appearance height: a shorter clamp samples the same curve (tip keeps residual
  // alpha). Frontier spans are never drawn (cutoff artifacts). Fill color comes
  // from the frame palette shared with tops (not re-read from Configs).
  private static void emitSkirts(BufferBuilder skirtBuffer, Matrix4fc positionMatrix,
      List<SkirtSpan> spans, boolean shadeByDepth, Palette.FillColors colors) {
    if (spans.isEmpty()) {
      return;
    }
    float downHeight = (float) Configs.downSkirtHeight();
    float upHeight = (float) Configs.upwardSkirtHeight();
    float o = (float) SKIRT_OFFSET;
    for (SkirtSpan sp : spans) {
      if (sp.frontier()) {
        continue;
      }
      boolean down = sp.isDown();
      float configHeight = down ? downHeight : upHeight;
      if (configHeight <= 0.0f) {
        continue;
      }
      float extent = (float) Math.min(configHeight, sp.maxExtent());
      if (!(extent > 0.0f)) {
        continue;
      }
      Palette.Resolved fill = colors.resolve(
        false, shadeByDepth, sp.depth(), sp.hazard());
      float fillAlpha = fill.a;
      // Fade is parameterized by Appearance height; a maxExtent clamp shortens
      // the quad but samples the same curve (clipped tip keeps residual alpha).
      float tipAlpha = fillAlpha * (1.0f - extent / configHeight);

      // alongX edge → face normal ±Z (N/S); else ±X (E/W).
      float shade = sp.alongX() ? SHADE_NORTH_SOUTH : SHADE_EAST_WEST;
      float r = fill.r * shade;
      float g = fill.g * shade;
      float b = fill.b * shade;

      float baseY = (float) sp.visualBaseY();
      float yTop;
      float yBot;
      float aTop;
      float aBot;
      float linePush;
      if (down) {
        yTop = baseY + (float) Y_OFFSET;
        yBot = yTop - extent;
        aTop = fillAlpha;
        aBot = tipAlpha;
        // Exterior push to prevent z-fighting with terrain side faces.
        linePush = sp.maxSide() ? o : -o;
      } else {
        yBot = baseY;
        yTop = baseY + extent;
        aTop = tipAlpha;
        aBot = fillAlpha;
        // Interior nudge to prevent z-fighting with the wall.
        linePush = sp.maxSide() ? -o : o;
      }

      float line = (float) sp.line() + linePush;
      float xa;
      float za;
      float xb;
      float zb;
      if (sp.alongX()) {
        xa = (float) sp.lo() - o;
        xb = (float) sp.hi() + o;
        za = line;
        zb = line;
      } else {
        za = (float) sp.lo() - o;
        zb = (float) sp.hi() + o;
        xa = line;
        xb = line;
      }
      vQuad(skirtBuffer, positionMatrix, xa, za, xb, zb, yTop, yBot,
        r, g, b, aTop, aBot);
    }
  }

  // One flat axis-aligned quad over [x0,x1] x [z0,z1] at height y, emitted with
  // both windings (a zero-thickness quad would otherwise be culled from one
  // side). Color is applied per-rect before calling this, so the quad is uniform.
  private static void quad(BufferBuilder buffer, Matrix4fc matrix,
      float x0, float x1, float z0, float z1, float y,
      float r, float g, float b, float a) {
    buffer.addVertex(matrix, x0, y, z0).setColor(r, g, b, a);
    buffer.addVertex(matrix, x0, y, z1).setColor(r, g, b, a);
    buffer.addVertex(matrix, x1, y, z1).setColor(r, g, b, a);
    buffer.addVertex(matrix, x1, y, z0).setColor(r, g, b, a);

    buffer.addVertex(matrix, x1, y, z0).setColor(r, g, b, a);
    buffer.addVertex(matrix, x1, y, z1).setColor(r, g, b, a);
    buffer.addVertex(matrix, x0, y, z1).setColor(r, g, b, a);
    buffer.addVertex(matrix, x0, y, z0).setColor(r, g, b, a);
  }

  // One vertical quad spanning [yBot, yTop] between horizontal endpoints
  // (xa,za)-(xb,zb), with separate alpha at the top and bottom edges (linearly
  // interpolated), emitted with both windings so it's visible from both sides
  // (occlusion is handled by the depth-tested pipeline).
  private static void vQuad(BufferBuilder buffer, Matrix4fc matrix,
      float xa, float za, float xb, float zb, float yTop, float yBot,
      float r, float g, float b, float aTop, float aBot) {
    buffer.addVertex(matrix, xa, yBot, za).setColor(r, g, b, aBot);
    buffer.addVertex(matrix, xa, yTop, za).setColor(r, g, b, aTop);
    buffer.addVertex(matrix, xb, yTop, zb).setColor(r, g, b, aTop);
    buffer.addVertex(matrix, xb, yBot, zb).setColor(r, g, b, aBot);

    buffer.addVertex(matrix, xb, yBot, zb).setColor(r, g, b, aBot);
    buffer.addVertex(matrix, xb, yTop, zb).setColor(r, g, b, aTop);
    buffer.addVertex(matrix, xa, yTop, za).setColor(r, g, b, aTop);
    buffer.addVertex(matrix, xa, yBot, za).setColor(r, g, b, aBot);
  }

  /**
   * Draw-color derivation for tops and skirts. Callers hoist Appearance /
   * Debug options and pass them in; frontier greying uses {@link #GREY_RGB}.
   */
  static final class Palette {
    // Debug depth coloring (gated by shadeByDepth): tops and their skirts are
    // colored by flood BFS distance from the seed (0 = seed), NOT by height. The
    // hue advances a small fixed step per depth ring and WRAPS, so it is a smooth
    // gradient locally (neighbouring rings are near-identical colors — you can read
    // which rects are neighbours) yet still resolves large radii, and a continuity
    // bug reads as a patch whose color breaks the spatial gradient. The step is sized
    // so a full hue cycle spans DEPTH_CYCLE rings (~20): finer than this and adjacent
    // rings were indistinguishable, coarser and it didn't cycle enough for big
    // floods. Depth -1 ("no flood depth") draws grey.
    private static final float DEPTH_HUE_START = 0.66f; // depth 0 hue (blue)
    private static final int DEPTH_CYCLE = 20;          // rings per full hue cycle
    private static final float DEPTH_HUE_STEP = 1.0f / DEPTH_CYCLE;
    private static final float SATURATION = 0.9f;
    private static final float VALUE = 1.0f;
    // Unknown flood depth and frontier greying target share the same mid-grey.
    static final float[] GREY_RGB = {0.5f, 0.5f, 0.5f};

    private Palette() {
    }

    /** Resolved fill RGBA for one top or skirt after precedence. */
    record Resolved(float r, float g, float b, float a) {
    }

    /**
     * Frame fill palette: walkable + per-hazard show/color + hole beam show/color.
     * Hoisted once in {@code emit} and shared with skirts and beams. Fill precedence:
     * frontier grey → depth hue → hazard color (if shown) → walkable. Beam colors
     * come from {@link #beamColor} (show off returns null).
     */
    static final class FillColors {
      private final Color4f walkable;
      private final boolean showWater;
      private final Color4f water;
      private final boolean showLava;
      private final Color4f lava;
      private final boolean showSoulSand;
      private final Color4f soulSand;
      private final boolean showMagma;
      private final Color4f magma;
      private final boolean showHole;
      private final Color4f hole;

      FillColors(Color4f walkable, boolean showWater, Color4f water,
          boolean showLava, Color4f lava,
          boolean showSoulSand, Color4f soulSand,
          boolean showMagma, Color4f magma,
          boolean showHole, Color4f hole) {
        this.walkable = walkable;
        this.showWater = showWater;
        this.water = water;
        this.showLava = showLava;
        this.lava = lava;
        this.showSoulSand = showSoulSand;
        this.soulSand = soulSand;
        this.showMagma = showMagma;
        this.magma = magma;
        this.showHole = showHole;
        this.hole = hole;
      }

      Resolved resolve(boolean frontier, boolean shadeByDepth, int depth,
          HazardClass hazard) {
        if (frontier) {
          return new Resolved(GREY_RGB[0], GREY_RGB[1], GREY_RGB[2], walkable.a);
        }
        if (shadeByDepth) {
          float[] rgb = depthColor(depth);
          return new Resolved(rgb[0], rgb[1], rgb[2], walkable.a);
        }
        Color4f base = switch (hazard) {
          case WATER -> showWater ? water : walkable;
          case LAVA -> showLava ? lava : walkable;
          case SOUL_SAND -> showSoulSand ? soulSand : walkable;
          case MAGMA -> showMagma ? magma : walkable;
          case NONE, HOLE -> walkable;
        };
        return new Resolved(base.r, base.g, base.b, base.a);
      }

      /** Beam RGBA for {@code hazard}, or {@code null} when that kind's show is off. */
      Color4f beamColor(HazardClass hazard) {
        return switch (hazard) {
          case HOLE -> showHole ? hole : null;
          case WATER -> showWater ? water : null;
          case LAVA -> showLava ? lava : null;
          case SOUL_SAND -> showSoulSand ? soulSand : null;
          case MAGMA -> showMagma ? magma : null;
          case NONE -> null;
        };
      }
    }

    // Map a flood BFS depth (distance from the seed) to RGB: the hue advances a small
    // step (1/DEPTH_CYCLE) per ring and wraps, so it is smooth between neighbouring
    // rings (readable as neighbours) but completes a full cycle every ~DEPTH_CYCLE
    // rings so large floods stay legible; a discontinuity breaks the local gradient.
    // Depth -1 ("no flood depth") is drawn grey. See the DEPTH_* constants.
    static float[] depthColor(int depth) {
      if (depth < 0) {
        return GREY_RGB;
      }
      float hue = DEPTH_HUE_START + DEPTH_HUE_STEP * depth;
      hue -= (float) Math.floor(hue);
      return hsvToRgb(hue, SATURATION, VALUE);
    }

    private static float[] hsvToRgb(float h, float s, float v) {
      int sector = (int) (h * 6.0f) % 6;
      float f = h * 6.0f - (float) Math.floor(h * 6.0f);
      float p = v * (1.0f - s);
      float q = v * (1.0f - f * s);
      float t = v * (1.0f - (1.0f - f) * s);
      return switch (sector) {
        case 0 -> new float[] {v, t, p};
        case 1 -> new float[] {q, v, p};
        case 2 -> new float[] {p, v, t};
        case 3 -> new float[] {p, q, v};
        case 4 -> new float[] {t, p, v};
        default -> new float[] {v, p, q};
      };
    }
  }
}
