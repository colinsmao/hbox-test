package com.example.overlay.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.example.overlay.OverlayMod;
import com.example.overlay.client.widgets.CollisionSurfaceOverlay;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Registry and render dispatch for {@link WorldOverlay} widgets.
 *
 * <p>Keeps all Fabric-API and low-level rendering contact in one place: it owns
 * a single filled {@code POSITION_COLOR}/{@code QUADS} pipeline and the GPU
 * buffer plumbing, batches every visible overlay into one draw, and releases
 * GPU resources on client shutdown. Widgets only implement {@link WorldOverlay}.
 */
public final class WorldOverlayManager {
	private static final List<WorldOverlay> OVERLAYS = new ArrayList<>();

	// Reuses vanilla's filled debug pipeline (QUADS + POSITION_COLOR). Depth test
	// is disabled (withDepthStencilState empty) so overlay surfaces draw THROUGH
	// solid geometry — a v1.5 debug aid that reveals surfaces buried inside blocks
	// (e.g. occlusion bugs). v2 keeps this; final rendering may re-enable depth.
	private static final RenderPipeline FILLED = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(OverlayMod.MOD_ID, "pipeline/world_overlay_filled"))
			.withDepthStencilState(Optional.empty())
			.build()
	);

	private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private static BufferBuilder buffer;
	private static MappableRingBuffer vertexBuffer;

	private static boolean usePressedLastTick = false;

	private WorldOverlayManager() {
	}

	public static void bootstrap() {
		register(new CollisionSurfaceOverlay());

		LevelRenderEvents.END_EXTRACTION.register(WorldOverlayManager::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(WorldOverlayManager::draw);
		// Free GPU resources at shutdown. We use CLIENT_STOPPING rather than a
		// GameRenderer#close mixin to avoid mixin plumbing; trade-off: buffers
		// are freed at shutdown, not on a mid-session renderer reload.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
		ClientTickEvents.END_CLIENT_TICK.register(WorldOverlayManager::onClientTick);
	}

	// Dispatch a use only on the rising edge of the "use item" key, so holding the
	// button does not spam. Deliberately not Fabric's UseItemCallback: that
	// re-fires every tick while held for items with no use cooldown (e.g. a stick).
	private static void onClientTick(Minecraft client) {
		boolean down = client.screen == null && client.options.keyUse.isDown();
		if (down && !usePressedLastTick && client.player != null) {
			for (WorldOverlay overlay : OVERLAYS) {
				overlay.onUseItem(client.player, InteractionHand.MAIN_HAND);
			}
		}
		usePressedLastTick = down;
	}

	public static void register(WorldOverlay overlay) {
		OVERLAYS.add(overlay);
	}

	private static void extract(LevelExtractionContext context) {
		for (WorldOverlay overlay : OVERLAYS) {
			overlay.extract(context);
		}
	}

	private static void draw(LevelRenderContext context) {
		List<WorldOverlay> visible = new ArrayList<>();
		for (WorldOverlay overlay : OVERLAYS) {
			if (overlay.isVisible()) {
				visible.add(overlay);
			}
		}
		if (visible.isEmpty()) {
			return;
		}

		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		// Overlays emit vertices in absolute world coords; translate by -camera
		// to make them camera-relative, matching the world renderer.
		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		if (buffer == null) {
			buffer = new BufferBuilder(ALLOCATOR, FILLED.getVertexFormatMode(), FILLED.getVertexFormat());
		}

		Matrix4fc pose = matrices.last().pose();
		for (WorldOverlay overlay : visible) {
			overlay.emit(pose, buffer);
		}

		matrices.popPose();

		drawBuffer(Minecraft.getInstance());
	}

	private static void drawBuffer(Minecraft client) {
		MeshData builtBuffer = buffer.buildOrThrow();
		MeshData.DrawState drawParameters = builtBuffer.drawState();
		VertexFormat format = drawParameters.format();

		GpuBuffer vertices = upload(drawParameters, format, builtBuffer);

		execute(client, FILLED, builtBuffer, drawParameters, vertices, format);

		vertexBuffer.rotate();
		buffer = null;
	}

	private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
		int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

		if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
			if (vertexBuffer != null) {
				vertexBuffer.close();
			}

			vertexBuffer = new MappableRingBuffer(
				() -> OverlayMod.MOD_ID + " world overlay pipeline",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
				vertexBufferSize
			);
		}

		CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

		try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
				vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
			MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
		}

		return vertexBuffer.currentBuffer();
	}

	private static void execute(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
			MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
		GpuBuffer indices;
		VertexFormat.IndexType indexType;

		if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
			indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
			indexType = builtBuffer.drawState().indexType();
		} else {
			RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
			indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
			indexType = shapeIndexBuffer.type();
		}

		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> OverlayMod.MOD_ID + " world overlay rendering",
					client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
					client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);

			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);

			renderPass.setVertexBuffer(0, vertices);
			renderPass.setIndexBuffer(indices, indexType);

			renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
		}

		builtBuffer.close();
	}

	private static void close() {
		ALLOCATOR.close();

		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
	}
}
