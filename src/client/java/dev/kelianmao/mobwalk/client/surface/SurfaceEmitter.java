package dev.kelianmao.mobwalk.client.surface;

import dev.kelianmao.mobwalk.client.config.Configs;

import java.util.List;


import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import fi.dy.masa.malilib.util.data.Color4f;

/**
 * Turns published surface snapshots into buffer geometry: tops, borders,
 * downward/upward skirts, and hole beams. Color derivation lives in the nested
 * {@link Palette} helper. Reads only the immutable snapshots + render-time
 * {@link Configs} flags — never {@code SurfaceSelection}'s live lists.
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
  // Skirts are shaded darker than the top for legibility.
  private static final float SKIRT_SHADE = 0.55f;
  private static final float SKIRT_ALPHA = 0.6f;
  // Tiny uniform outward push of the (square) skirt edges — NOT a dilation: just
  // enough to lift the skirt off the coplanar terrain side face so it doesn't
  // z-fight. Kept tiny so the gap from the top edge stays invisible and skirts
  // don't visibly overlap neighbours.
  private static final double SKIRT_OFFSET = 0.002;

  // Upward skirts: drawn where a surface edge borders a wall/ceiling (compute-side
  // SkirtSpan UP), solid at the surface top and fading to transparent at the marker
  // top. A lighter shade than the (darker) downward drop skirts so the two read
  // distinctly. Draw height from Appearance upwardSkirtHeight (0 skips draw),
  // clamped to span.maxExtent (wall top above the surface).
  private static final float UP_SKIRT_SHADE = 0.85f;
  private static final float UP_SKIRT_ALPHA = 0.7f;

  // Hole beam: a vertical marker rising from the cliff-edge top of a hole span.
  // Routed to the depth-off BEAM layer or depth-tested SKIRT layer by Appearance
  // showBeamsThroughWalls. Color/opacity from holeBeamColor.
  private static final float BEAM_HEIGHT = 4.0f;

  private SurfaceEmitter() {
  }

  /**
   * Emit tops (+ crouch borders), then skirts (up and down), and hole beams from
   * the published snapshots.
   */
  public static void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer,
      BufferBuilder skirtBuffer, BufferBuilder beamBuffer,
      List<StandableRect> rects, List<SkirtSpan> occluders,
      List<SkirtSpan> downSkirts, List<HoleSpan> holes,
      int depthLimit, boolean crouching) {
    if (rects.isEmpty()) {
      return;
    }

    for (StandableRect rect : rects) {
      if (suppressCutoffRing(rect.depth(), depthLimit)) {
        continue;
      }
      float minX = (float) rect.minX();
      float minZ = (float) rect.minZ();
      float maxX = (float) rect.maxX();
      float maxZ = (float) rect.maxZ();
      float y = (float) rect.visualTopY() + (float) Y_OFFSET;

      float[] rgb = Palette.colorAtDepth(rect.depth(), depthLimit);
      float r = rgb[0];
      float g = rgb[1];
      float b = rgb[2];
      float fillAlpha = Configs.walkableColor().a;

      BufferBuilder topBuffer = crouching ? fillBuffer : skirtBuffer;
      quad(topBuffer, positionMatrix, minX, maxX, minZ, maxZ, y, r, g, b, fillAlpha);

      if (crouching) {
        float bx = Math.min(BORDER_THICKNESS, (maxX - minX) * 0.5f);
        float bz = Math.min(BORDER_THICKNESS, (maxZ - minZ) * 0.5f);
        quad(fillBuffer, positionMatrix, minX, maxX, minZ, minZ + bz, y, r, g, b, BORDER_ALPHA);
        quad(fillBuffer, positionMatrix, minX, maxX, maxZ - bz, maxZ, y, r, g, b, BORDER_ALPHA);
        quad(fillBuffer, positionMatrix, minX, minX + bx, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
        quad(fillBuffer, positionMatrix, maxX - bx, maxX, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
      }

    }

    emitSkirts(skirtBuffer, positionMatrix, downSkirts, depthLimit);
    emitSkirts(skirtBuffer, positionMatrix, occluders, depthLimit);

    BufferBuilder holeBuffer = Configs.showBeamsThroughWalls() ? beamBuffer : skirtBuffer;
    emitHoles(holeBuffer, positionMatrix, holes);
  }

  // Draw a vertical beam rising from each hole span's rim (baseY), clamped to a
  // fixed world height, at holeBeamColor opacity. Caller picks beam vs skirt
  // buffer (Appearance showBeamsThroughWalls).
  private static void emitHoles(BufferBuilder holeBuffer, Matrix4fc positionMatrix,
      List<HoleSpan> spans) {
    if (!Configs.showHoleBeams()) {
      return;
    }
    if (spans.isEmpty()) {
      return;
    }
    Color4f beam = Configs.holeBeamColor();
    float r = beam.r;
    float g = beam.g;
    float b = beam.b;
    float a = beam.a;
    // HoleSpan doesn't carry a flood-depth, so holes at the cutoff edge can't
    // be depth-suppressed — that's acceptable: depth-limit artifacts are rare
    // at cliff edges (the flood stops mid-surface, not at a drop).
    for (HoleSpan h : spans) {
      // Rise from the rim's render height (visualBaseY).
      float base = (float) h.visualBaseY();
      float top = base + BEAM_HEIGHT;
      float xa;
      float za;
      float xb;
      float zb;
      if (h.alongX()) {
        xa = (float) h.lo();
        xb = (float) h.hi();
        za = (float) h.line();
        zb = (float) h.line();
      } else {
        za = (float) h.lo();
        zb = (float) h.hi();
        xa = (float) h.line();
        xb = (float) h.line();
      }
      vQuad(holeBuffer, positionMatrix, xa, za, xb, zb, top, base,
        r, g, b, a, a);
    }
  }

  // Draw published skirts (UP and DOWN) into the depth-tested layer. Length is
  // min(Appearance height for the direction, span.maxExtent). Two independent
  // skips: frontier spans (depth >= depthLimit) are never drawn; when
  // showCutoffRing is off, the cutoff-ring band is also skipped.
  private static void emitSkirts(BufferBuilder skirtBuffer, Matrix4fc positionMatrix,
      List<SkirtSpan> spans, int depthLimit) {
    if (spans.isEmpty()) {
      return;
    }
    float o = (float) SKIRT_OFFSET;
    for (SkirtSpan sp : spans) {
      if (sp.depth() >= depthLimit) {
        continue;
      }
      if (suppressCutoffRing(sp.depth(), depthLimit)) {
        continue;
      }
      if (sp.isDown()) {
        float downHeight = (float) Configs.downSkirtHeight();
        if (downHeight <= 0.0f) {
          continue;
        }
        float extent = (float) Math.min(downHeight, sp.maxExtent());
        if (!(extent > 0.0f)) {
          continue;
        }
        float[] rgb = Palette.colorAtDepth(sp.depth(), depthLimit);
        float sr = rgb[0] * SKIRT_SHADE;
        float sg = rgb[1] * SKIRT_SHADE;
        float sb = rgb[2] * SKIRT_SHADE;
        float yTop = (float) sp.visualBaseY() + (float) Y_OFFSET;
        float yBot = yTop - extent;
        // Exterior push to prevent z-fighting.
        float push = sp.maxSide() ? o : -o;
        if (sp.alongX()) {
          float z = (float) sp.line() + push;
          fadedSkirt(skirtBuffer, positionMatrix,
            (float) sp.lo() - o, z, (float) sp.hi() + o, z, yTop, yBot, sr, sg, sb);
        } else {
          float x = (float) sp.line() + push;
          fadedSkirt(skirtBuffer, positionMatrix,
            x, (float) sp.lo() - o, x, (float) sp.hi() + o, yTop, yBot, sr, sg, sb);
        }
      } else {
        float upHeight = (float) Configs.upwardSkirtHeight();
        if (upHeight <= 0.0f) {
          continue;
        }
        float available = (float) sp.maxExtent();
        if (available <= 0.0f) {
          continue;
        }
        float markerHeight = Math.min(upHeight, available);
        float base = (float) sp.visualBaseY();
        float yTopMarker = base + markerHeight;
        float[] rgb = Palette.colorAtDepth(sp.depth(), depthLimit);
        float r = rgb[0] * UP_SKIRT_SHADE;
        float g = rgb[1] * UP_SKIRT_SHADE;
        float b = rgb[2] * UP_SKIRT_SHADE;
        // Interior nudge to prevent z-fighting.
        float shift = sp.maxSide() ? -o : o;
        float line = (float) sp.line() + shift;
        float xa;
        float za;
        float xb;
        float zb;
        if (sp.alongX()) {
          xa = (float) sp.lo();
          xb = (float) sp.hi();
          za = line;
          zb = line;
        } else {
          za = (float) sp.lo();
          zb = (float) sp.hi();
          xa = line;
          xb = line;
        }
        vQuad(skirtBuffer, positionMatrix, xa, za, xb, zb, yTopMarker, base,
          r, g, b, 0.0f, UP_SKIRT_ALPHA);
      }
    }
  }

  // When showCutoffRing is off, skip drawing depths in the cutoff-ring band.
  private static boolean suppressCutoffRing(int depth, int depthLimit) {
    return !Configs.showCutoffRing() && Palette.inCutoffRing(depth, depthLimit);
  }

  // One flat axis-aligned quad over [x0,x1] x [z0,z1] at height y, emitted with
  // both windings (a zero-thickness quad would otherwise be culled from one
  // side). Grey blending (depth-based) is applied per-rect before calling this,
  // so the quad is uniform color.
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

  // A vertical skirt that is solid over its top half and fades to transparent
  // over its bottom half, so a drop deeper than the skirt doesn't read as a hard
  // floating wall. Two stacked double-winding segments between the horizontal
  // endpoints (xa,za)-(xb,zb).
  private static void fadedSkirt(BufferBuilder buffer, Matrix4fc matrix,
      float xa, float za, float xb, float zb, float yTop, float yBot,
      float r, float g, float b) {
    float yMid = (yTop + yBot) * 0.5f;
    vQuad(buffer, matrix, xa, za, xb, zb, yTop, yMid, r, g, b, SKIRT_ALPHA, SKIRT_ALPHA);
    vQuad(buffer, matrix, xa, za, xb, zb, yMid, yBot, r, g, b, SKIRT_ALPHA, 0.0f);
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
   * Draw-color derivation for tops and skirts: Appearance {@code walkableColor}
   * (or Debug {@code shadeByDepth} hue), then cutoff-ring greying toward grey at
   * the outermost two BFS depth rings.
   */
  static final class Palette {
    // Debug depth coloring (gated by Configs.shadeByDepth): tops and their skirts
    // are colored by flood BFS distance from the seed (0 = seed), NOT by height. The
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
    // Unknown flood depth and cutoff-ring greying target share the same mid-grey.
    private static final float[] GREY_RGB = {0.5f, 0.5f, 0.5f};

    private Palette() {
    }

    /**
     * Final draw RGB for a surface/skirt at the given flood depth, including
     * cutoff-ring greying. Base color is Appearance walkable
     * color, or the cyclic depth-hue band when {@code shadeByDepth}.
     */
    static float[] colorAtDepth(int depth, int limit) {
      float[] rgb;
      if (Configs.shadeByDepth()) {
        rgb = depthColor(depth);
      } else {
        Color4f c = Configs.walkableColor();
        rgb = new float[] {c.r, c.g, c.b};
      }
      return greyBlend(rgb, depth, limit);
    }

    // True for depths in the cutoff-ring band (partial/full grey when shown):
    // depth == limit-1 → half grey; depth >= limit → full grey.
    static boolean inCutoffRing(int depth, int limit) {
      return depth >= 0 && depth > limit - 2;
    }

    // Blend a base color toward GREY_RGB by how close the rect's BFS depth is to
    // the flood limit (the depth-based replacement of the old spatial ring greying).
    // depth <= limit-2: no grey; depth == limit-1: half grey; depth >= limit: full.
    private static float[] greyBlend(float[] rgb, int depth, int limit) {
      if (!inCutoffRing(depth, limit)) {
        return rgb;
      }
      float t = Math.max(0.0f, Math.min(1.0f, (depth - (limit - 2)) * 0.5f));
      return new float[] {
        rgb[0] + (GREY_RGB[0] - rgb[0]) * t,
        rgb[1] + (GREY_RGB[1] - rgb[1]) * t,
        rgb[2] + (GREY_RGB[2] - rgb[2]) * t,
      };
    }

    // Map a flood BFS depth (distance from the seed) to RGB: the hue advances a small
    // step (1/DEPTH_CYCLE) per ring and wraps, so it is smooth between neighbouring
    // rings (readable as neighbours) but completes a full cycle every ~DEPTH_CYCLE
    // rings so large floods stay legible; a discontinuity breaks the local gradient.
    // Depth -1 ("no flood depth") is drawn grey. See the DEPTH_* constants.
    private static float[] depthColor(int depth) {
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
