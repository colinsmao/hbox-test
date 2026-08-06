package dev.kelianmao.mobwalk.client.surface;

/**
 * One edge span drawn as a through-walls vertical beam from {@code visualBaseY}.
 * Same interval shape as a skirt's rim piece ({@code alongX}/{@code line}/{@code [lo,hi]}),
 * without skirt payload. Produced by hole classification today ({@link HoleBeams});
 * hazard perimeters can publish the same type.
 *
 * @param alongX      {@code true} when the edge runs along X at fixed Z
 *                    ({@code line} is Z, {@code [lo,hi]} is X); else along Z.
 * @param line        fixed coordinate of the edge (Z if {@code alongX}, else X).
 * @param lo          start of the sub-span on the varying axis.
 * @param hi          end of the sub-span on the varying axis.
 * @param visualBaseY foot of the beam (source rect's visible-face top).
 */
public record BeamSpan(boolean alongX, double line, double lo, double hi, double visualBaseY) {
}
