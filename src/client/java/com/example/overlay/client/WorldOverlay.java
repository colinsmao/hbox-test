package com.example.overlay.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * A drawable element rendered in the world (not the HUD).
 *
 * <p>Rendering in {@code 26.1.2} is split into an "extraction" phase (gather
 * immutable, thread-safe state) and a "drawing" phase (emit geometry). Widgets
 * read mutable game state in {@link #extract} and stash whatever they need,
 * then emit vertices in {@link #emit}. {@link WorldOverlayManager} owns the
 * shared render pipeline and GPU plumbing so widgets only describe geometry.
 */
public interface WorldOverlay {
	String id();

	/** Extraction phase: read game state and store an immutable snapshot. */
	void extract(LevelExtractionContext context);

	/**
	 * Drawing phase: append quads to the shared buffer.
	 *
	 * @param positionMatrix already translated so world coordinates are
	 *                       camera-relative; pass absolute world coords.
	 * @param buffer         shared {@code POSITION_COLOR} / {@code QUADS} buffer.
	 */
	void emit(Matrix4fc positionMatrix, BufferBuilder buffer);

	default boolean isVisible() {
		return true;
	}
}
