package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.RectMath.ClassifiedRect;
import dev.kelianmao.mobwalk.client.surface.RectMath.PriorityLayer;
import dev.kelianmao.mobwalk.client.surface.RectMath.Rect;

/**
 * Contract for the class-agnostic priority partition used inside each collision
 * band. Layers arrive highest-first. Each class unions its own geometry, then
 * receives only the area not already claimed by a higher class.
 */
final class PriorityPartitionTest {
  private static final double EPS = 1.0e-9;

  @Test
  void highestClassClaimsOverlapAndSameClassPiecesCoalesce() {
    List<PriorityLayer<String>> layers = List.of(
      new PriorityLayer<>("high", List.of(
        new Rect(2, 0, 4, 1),
        new Rect(4, 0, 5, 1))),
      new PriorityLayer<>("middle", List.of(
        new Rect(1, 0, 3, 1))),
      new PriorityLayer<>("low", List.of(
        new Rect(0, 0, 4, 1))));

    List<ClassifiedRect<String>> out = RectMath.priorityPartition(layers);

    assertNonOverlapping(out);
    assertEquals("low", soleOwner(out, 0.5, 0.5).priorityClass());
    assertEquals("middle", soleOwner(out, 1.5, 0.5).priorityClass());
    assertEquals("high", soleOwner(out, 2.5, 0.5).priorityClass());
    assertEquals("high", soleOwner(out, 4.5, 0.5).priorityClass());

    long highRuns = out.stream()
      .filter(r -> r.priorityClass().equals("high"))
      .count();
    assertEquals(1, highRuns,
      "abutting geometry in one priority class should coalesce");
    assertTrue(out.stream().allMatch(r -> r.rect().minX() >= -EPS
      && r.rect().maxX() <= 5.0 + EPS
      && r.rect().minZ() >= -EPS
      && r.rect().maxZ() <= 1.0 + EPS));
  }

  private static <C> ClassifiedRect<C> soleOwner(
      List<ClassifiedRect<C>> rects, double x, double z) {
    List<ClassifiedRect<C>> owners = rects.stream()
      .filter(r -> contains(r.rect(), x, z))
      .toList();
    assertEquals(1, owners.size(),
      "expected one owner at " + x + "," + z + " but found " + owners);
    return owners.get(0);
  }

  private static <C> void assertNonOverlapping(List<ClassifiedRect<C>> rects) {
    for (int i = 0; i < rects.size(); i++) {
      for (int j = i + 1; j < rects.size(); j++) {
        Rect a = rects.get(i).rect();
        Rect b = rects.get(j).rect();
        double overlapX = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
        double overlapZ = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
        assertTrue(overlapX <= EPS || overlapZ <= EPS,
          "priority output overlaps: " + rects.get(i) + " and " + rects.get(j));
      }
    }
  }

  private static boolean contains(Rect rect, double x, double z) {
    return x > rect.minX() && x < rect.maxX()
      && z > rect.minZ() && z < rect.maxZ();
  }
}
