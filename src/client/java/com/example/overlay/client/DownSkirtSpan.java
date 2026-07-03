package com.example.overlay.client;

/**
 * One <b>downward</b> drop-skirt span: a sub-span of a standable surface edge that
 * is a genuine drop — the edge minus the parts shared with an equal-height merge
 * seam (a continuation, not a drop) and minus the wall/ceiling parts (which get an
 * upward {@link OccluderSpan} instead). Drawn as a fading vertical skirt hanging
 * from the surface top.
 *
 * <p>Computed on the client/extraction thread (it reads the merged reached set and
 * the occluder spans — see {@code SurfaceSelection.computeDownSkirts}) once per
 * selection, replacing the old per-frame {@code openSpans} scan that was O(n²) over
 * the merged rects each frame; published in the snapshot alongside the
 * {@link StandableRect}s and {@link OccluderSpan}s. It is also the substrate the
 * Milestone 5 hole classifier plugs into (a drop span is a hole candidate).
 *
 * @param alongX  {@code true} when the edge runs along X at a fixed Z ({@code line}
 *                is the Z coordinate, {@code [lo,hi]} the X interval); {@code false}
 *                when it runs along Z at a fixed X.
 * @param maxSide {@code true} when this is the {@code +axis} edge of the rect (so
 *                the skirt is pushed toward {@code +axis} and {@code line} is the
 *                rect's max coordinate); {@code false} for the {@code -axis} edge.
 * @param line    the fixed coordinate of the edge (Z if {@code alongX}, else X), in
 *                true rect bounds (the tiny render-side offset is applied at draw).
 * @param lo      start of the drop sub-span on its varying axis (true coords).
 * @param hi      end of the drop sub-span on its varying axis (true coords).
 * @param baseY   the surface top {@code T} (the skirt's solid top; the fade and the
 *                skirt depth are render details derived from this and the profile).
 */
public record DownSkirtSpan(boolean alongX, boolean maxSide, double line,
		double lo, double hi, double baseY) {
}
