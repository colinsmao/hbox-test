package dev.kelianmao.mobwalk.client;

/**
 * One <b>upward</b> (occluder) skirt span: a sub-span of a standable surface edge
 * where a collision box rises above the surface (a wall) or hangs within the
 * entity's headroom (a ceiling/overhang). Drawn as a vertical wall face at the
 * surface's <b>dilated</b> edge (pulled {@code ~W/2} off the real block face),
 * rising from the surface top, in contrast to the downward drop skirts.
 *
 * <p>Computed on the client/extraction thread (it needs collision-box data the
 * render thread may not query — see {@code SurfaceSelection.computeOccluders})
 * and published in the snapshot alongside the {@link StandableRect}s, so
 * {@code emit} draws upward skirts from it and removes those sub-spans from the
 * downward skirt set (no double-skirting).
 *
 * @param alongX       {@code true} when the edge runs along X at a fixed Z
 *                     ({@code line} is the Z coordinate, {@code [lo,hi]} the X
 *                     interval); {@code false} when it runs along Z at a fixed X.
 * @param positiveSide {@code true} when the occluder sits on the +axis side of the
 *                     edge (so the surface interior is on the -axis side). Lets the
 *                     renderer nudge the skirt toward the interior to dodge z-fighting
 *                     the wall face, and keeps opposite-side edges at one coordinate
 *                     from being merged together.
 * @param line         the fixed coordinate of the edge (Z if {@code alongX}, else X).
 * @param lo           start of the edge sub-span on its varying axis.
 * @param hi           end of the edge sub-span on its varying axis.
 * @param baseY        the surface top {@code T} (the skirt's solid base).
 * @param topY         the occluder's top ({@code box.yMax}); the skirt fades out
 *                     toward it (clamped by the render style so a tall wall isn't a
 *                     curtain).
 * @param visualBaseY  the source rect's visible-face top (draw-only): where the
 *                     upward skirt rises from when the renderer draws on the visible
 *                     face; equals {@code baseY} except for render-taller-than-collide
 *                     blocks. See {@link StandableRect}.
 * @param depth        the source rect's debug flood-distance tag (see
 *                     {@link StandableRect}); an occluder skirt inherits its surface's
 *                     depth so the two share a color band. {@code -1} = "no flood
 *                     depth" (rendered grey).
 */
public record OccluderSpan(boolean alongX, boolean positiveSide, double line,
		double lo, double hi, double baseY, double topY, double visualBaseY, int depth) {
	/** A span with an explicit visible base but no flood depth ({@code depth = -1}). */
	public OccluderSpan(boolean alongX, boolean positiveSide, double line,
			double lo, double hi, double baseY, double topY, double visualBaseY) {
		this(alongX, positiveSide, line, lo, hi, baseY, topY, visualBaseY, -1);
	}

	/** A span whose visible base coincides with its collision base (the common case). */
	public OccluderSpan(boolean alongX, boolean positiveSide, double line,
			double lo, double hi, double baseY, double topY) {
		this(alongX, positiveSide, line, lo, hi, baseY, topY, baseY, -1);
	}
}
