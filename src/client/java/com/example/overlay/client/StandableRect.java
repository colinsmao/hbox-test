package com.example.overlay.client;

/**
 * An axis-aligned, horizontal standable patch in absolute world coordinates.
 *
 * <p>Collision shapes are unions of axis-aligned cuboids, so every upward-facing
 * face is an axis-aligned rectangle over {@code [minX,maxX] x [minZ,maxZ]} at a
 * single {@code topY}. Stored in world-space doubles (the resolved
 * {@code BlockPos} already folded in); see {@code PLAN.md} for why we skip a
 * 1/16-pixel integer model.
 */
public record StandableRect(double minX, double minZ, double maxX, double maxZ, double topY) {
}
