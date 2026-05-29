package com.example.overlay.client.widgets;

import com.example.overlay.client.WorldOverlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * Draws a flat red annulus (ring) on the top face of the block the player is
 * looking at. Extracts the targeted block each frame; emits the ring as a fan
 * of quads in the horizontal plane just above the block's top face.
 */
public final class BlockTopAnnulusOverlay implements WorldOverlay {
	private static final int SEGMENTS = 64;
	private static final double INNER_RADIUS = 0.30;
	private static final double OUTER_RADIUS = 0.45;
	private static final double Y_OFFSET = 0.01;

	private static final float RED = 1.0f;
	private static final float GREEN = 0.0f;
	private static final float BLUE = 0.0f;
	private static final float ALPHA = 0.85f;

	private volatile BlockPos target;

	@Override
	public String id() {
		return "block_top_annulus";
	}

	@Override
	public void extract(LevelExtractionContext context) {
		HitResult hit = Minecraft.getInstance().hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
			target = blockHit.getBlockPos();
		} else {
			target = null;
		}
	}

	@Override
	public boolean isVisible() {
		return target != null;
	}

	@Override
	public void emit(Matrix4fc positionMatrix, BufferBuilder buffer) {
		BlockPos pos = target;
		if (pos == null) {
			return;
		}

		double centerX = pos.getX() + 0.5;
		double centerZ = pos.getZ() + 0.5;
		float y = (float) (pos.getY() + 1.0 + Y_OFFSET);

		for (int i = 0; i < SEGMENTS; i++) {
			double a0 = (2.0 * Math.PI * i) / SEGMENTS;
			double a1 = (2.0 * Math.PI * (i + 1)) / SEGMENTS;

			double cos0 = Math.cos(a0);
			double sin0 = Math.sin(a0);
			double cos1 = Math.cos(a1);
			double sin1 = Math.sin(a1);

			float outerX0 = (float) (centerX + OUTER_RADIUS * cos0);
			float outerZ0 = (float) (centerZ + OUTER_RADIUS * sin0);
			float outerX1 = (float) (centerX + OUTER_RADIUS * cos1);
			float outerZ1 = (float) (centerZ + OUTER_RADIUS * sin1);
			float innerX0 = (float) (centerX + INNER_RADIUS * cos0);
			float innerZ0 = (float) (centerZ + INNER_RADIUS * sin0);
			float innerX1 = (float) (centerX + INNER_RADIUS * cos1);
			float innerZ1 = (float) (centerZ + INNER_RADIUS * sin1);

			// Top-facing winding (visible from above).
			vertex(buffer, positionMatrix, outerX0, y, outerZ0);
			vertex(buffer, positionMatrix, outerX1, y, outerZ1);
			vertex(buffer, positionMatrix, innerX1, y, innerZ1);
			vertex(buffer, positionMatrix, innerX0, y, innerZ0);

			// Reverse winding so the ring is also visible from below.
			vertex(buffer, positionMatrix, innerX0, y, innerZ0);
			vertex(buffer, positionMatrix, innerX1, y, innerZ1);
			vertex(buffer, positionMatrix, outerX1, y, outerZ1);
			vertex(buffer, positionMatrix, outerX0, y, outerZ0);
		}
	}

	private static void vertex(BufferBuilder buffer, Matrix4fc matrix, float x, float y, float z) {
		buffer.addVertex(matrix, x, y, z).setColor(RED, GREEN, BLUE, ALPHA);
	}
}
