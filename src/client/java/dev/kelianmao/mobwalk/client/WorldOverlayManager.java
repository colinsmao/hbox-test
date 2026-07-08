package dev.kelianmao.mobwalk.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import dev.kelianmao.mobwalk.MobWalk;
import dev.kelianmao.mobwalk.client.widgets.CollisionSurfaceOverlay;

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
	// is disabled (withDepthStencilState empty) so the flat tops/borders draw
	// THROUGH solid geometry — a debug aid that reveals surfaces buried inside
	// blocks (e.g. occlusion bugs).
	private static final RenderPipeline FILLED = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(MobWalk.MOD_ID, "pipeline/world_overlay_filled"))
			.withDepthStencilState(Optional.empty())
			.build()
	);

	// Same snippet but with its DEFAULT depth state (depth test kept, not
	// disabled), used for the vertical skirts: a skirt buried behind solid
	// geometry (a step riser, an interior floor edge) is occluded, while a skirt
	// over an open drop stays visible, so the selection reads as a 3D mesh.
	private static final RenderPipeline SKIRT = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(MobWalk.MOD_ID, "pipeline/world_overlay_skirt"))
			.build()
	);

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	// One GPU layer (own allocator + ring buffer) per pipeline. The depth-off
	// FILLED layer carries the flat tops/borders; the depth-tested SKIRT layer
	// carries the vertical edge walls.
	private static final Layer fillLayer = new Layer(FILLED);
	private static final Layer skirtLayer = new Layer(SKIRT);

	private static boolean usePressedLastTick = false;

	// The collision-surface widget instance, exposed so MobWalkClient's scroll
	// handler can adjust its flood radius.
	private static CollisionSurfaceOverlay collisionSurface;

	private WorldOverlayManager() {
	}

	public static void bootstrap() {
		collisionSurface = new CollisionSurfaceOverlay();
		register(collisionSurface);

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

	public static CollisionSurfaceOverlay collisionSurface() {
		return collisionSurface;
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

		Matrix4fc pose = matrices.last().pose();
		BufferBuilder fill = fillLayer.begin();
		BufferBuilder skirt = skirtLayer.begin();
		for (WorldOverlay overlay : visible) {
			overlay.emit(pose, fill, skirt);
		}

		matrices.popPose();

		// Fill first (depth-off, drawn through walls), then skirts (depth-tested):
		// tops never occlude skirts, and skirts are occluded only by world geometry.
		Minecraft client = Minecraft.getInstance();
		drawLayer(client, fillLayer);
		drawLayer(client, skirtLayer);
	}

	private static void drawLayer(Minecraft client, Layer layer) {
		// build() returns null when nothing was emitted into this layer's buffer;
		// reset the builder regardless so next frame starts fresh.
		MeshData builtBuffer = layer.buffer.build();
		layer.buffer = null;
		if (builtBuffer == null) {
			return;
		}

		MeshData.DrawState drawParameters = builtBuffer.drawState();
		VertexFormat format = drawParameters.format();

		GpuBuffer vertices = upload(layer, drawParameters, format, builtBuffer);

		execute(client, layer, builtBuffer, drawParameters, vertices, format);

		layer.vertexBuffer.rotate();
	}

	private static GpuBuffer upload(Layer layer, MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
		int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

		if (layer.vertexBuffer == null || layer.vertexBuffer.size() < vertexBufferSize) {
			if (layer.vertexBuffer != null) {
				layer.vertexBuffer.close();
			}

			layer.vertexBuffer = new MappableRingBuffer(
				() -> MobWalk.MOD_ID + " world overlay pipeline",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
				vertexBufferSize
			);
		}

		CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

		try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
				layer.vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
			MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
		}

		return layer.vertexBuffer.currentBuffer();
	}

	private static void execute(Minecraft client, Layer layer, MeshData builtBuffer,
			MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
		RenderPipeline pipeline = layer.pipeline;
		GpuBuffer indices;
		VertexFormat.IndexType indexType;

		if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			builtBuffer.sortQuads(layer.allocator, RenderSystem.getProjectionType().vertexSorting());
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
					() -> MobWalk.MOD_ID + " world overlay rendering",
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
		fillLayer.close();
		skirtLayer.close();
	}

	// A single GPU draw layer: its pipeline, a private vertex allocator/builder,
	// and a ring buffer sized to its largest batch. The builder is rebuilt each
	// frame (see drawLayer); the allocator and ring buffer are reused.
	private static final class Layer {
		final RenderPipeline pipeline;
		final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
		BufferBuilder buffer;
		MappableRingBuffer vertexBuffer;

		Layer(RenderPipeline pipeline) {
			this.pipeline = pipeline;
		}

		BufferBuilder begin() {
			if (buffer == null) {
				buffer = new BufferBuilder(allocator, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
			}
			return buffer;
		}

		void close() {
			allocator.close();
			if (vertexBuffer != null) {
				vertexBuffer.close();
				vertexBuffer = null;
			}
		}
	}
}
