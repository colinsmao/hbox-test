package dev.kelianmao.mobwalk.client.surface;

/**
 * One edge span drawn as a through-walls vertical beam from {@code visualBaseY}.
 * Same interval shape as a skirt's rim piece ({@code alongX}/{@code line}/{@code [lo,hi]}),
 * without skirt payload. {@link #hazard()} selects Appearance color/toggle at emit
 * ({@link HazardClass#HOLE} → hole beam settings; {@code WATER}/{@code LAVA}/
 * {@code SOUL_SAND}/{@code MAGMA} → hazard show+color pairs).
 *
 * @param alongX      {@code true} when the edge runs along X at fixed Z
 *                    ({@code line} is Z, {@code [lo,hi]} is X); else along Z.
 * @param line        fixed coordinate of the edge (Z if {@code alongX}, else X).
 * @param lo          start of the sub-span on the varying axis.
 * @param hi          end of the sub-span on the varying axis.
 * @param visualBaseY foot of the beam (source rect's visible-face top).
 * @param hazard      beam kind for color/toggle ({@link HazardClass#HOLE}, a fluid,
 *                    or a solid hazard).
 */
public record BeamSpan(boolean alongX, double line, double lo, double hi, double visualBaseY,
    HazardClass hazard) {
}
