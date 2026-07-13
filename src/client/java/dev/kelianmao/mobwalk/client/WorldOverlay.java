package dev.kelianmao.mobwalk.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.world.entity.player.Player;

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
	 * Drawing phase: append quads to the shared buffers.
	 *
	 * <p>{@link WorldOverlayManager} maintains two {@code POSITION_COLOR} /
	 * {@code QUADS} layers with different depth state: {@code fillBuffer} is
	 * <b>depth-disabled</b> (draws through walls — a debug aid) and
	 * {@code skirtBuffer} is <b>depth-tested</b> (occluded by world geometry).
	 * A widget puts flat "always visible" geometry in the fill buffer and
	 * world-occluded geometry (e.g. vertical skirts) in the skirt buffer.
	 *
	 * @param positionMatrix already translated so world coordinates are
	 *                       camera-relative; pass absolute world coords.
	 * @param fillBuffer     depth-disabled layer (draws through walls).
	 * @param skirtBuffer    depth-tested layer (occluded by world geometry).
	 */
	void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer, BufferBuilder skirtBuffer);

	default boolean isVisible() {
		return true;
	}

	/**
	 * Called on the rising edge of the use key (right-click). The widget picks
	 * which hand to act on itself (e.g. main-first with an off-hand fallback), so
	 * the manager stays agnostic to what item a widget cares about.
	 */
	default void onUseItem(Player player) {
	}
}
