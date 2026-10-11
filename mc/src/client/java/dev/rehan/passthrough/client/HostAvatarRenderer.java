package dev.rehan.passthrough.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.Config;
import dev.rehan.passthrough.Passthrough;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The player in a layer of their own (third person): left out of the world pass, then drawn alone (skin, armour, cape,
 * what they hold: vanilla's own renderer) into cleared colour and depth, and handed to the host as layers 3 and 4. The
 * host shows that layer as drawn, camera-relative, instead of re-projecting it with the world: re-projection is what tore
 * the head and left copies behind while walking. Adapted from EldenCraft's HostAvatarRenderer (MIT, Copyright (c) 2026
 * Adam Grzegorzewski, github.com/SocketByte/EldenCraft).
 */
public final class HostAvatarRenderer {
	private static final Vector4f TRANSPARENT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	private static ProjectionMatrixBuffer projection;
	private static boolean failed;
	/** Decided once per frame (renderLevel HEAD), so the world pass and the avatar pass never disagree. */
	private static boolean isolated;

	private HostAvatarRenderer() {
	}

	/** GameRenderer.renderLevel HEAD: whether the player goes into the avatar layer this frame. */
	public static void beginFrame() {
		Minecraft client = Minecraft.getInstance();
		HostState.Pose pose = HostState.frame();
		isolated = !failed
			&& Config.avatarLayer
			&& !Boolean.getBoolean("passthrough.noAvatarLayer")
			&& Passthrough.active
			&& FrameExporter.exporting()
			// GPU frames from an older host carry no avatar planes: the player stays in the world layer then
			&& FrameExporter.avatarPossible()
			&& pose != null
			&& !pose.firstPerson()
			&& client.options.getCameraType() != CameraType.FIRST_PERSON
			&& client.player != null
			&& client.player.isAlive()
			&& !client.player.isSpectator();
	}

	public static boolean isolated() {
		return isolated;
	}

	/** The local player's render state while it belongs to the avatar layer: left out of the world pass. */
	public static boolean belongsToIsolatedLayer(final EntityRenderState state) {
		var player = Minecraft.getInstance().player;
		return isolated && player != null && state instanceof AvatarRenderState avatar && avatar.id == player.getId();
	}

	/** Right after the world layer was captured (colour already cleared): the player alone, then copied as the avatar layers. */
	public static void render(final RenderTarget target) {
		if (!isolated || !FrameExporter.capturing()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		var renderer = client.gameRenderer;
		var previousProjection = RenderSystem.getProjectionMatrixBuffer();
		var previousType = RenderSystem.getProjectionType();
		var modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		boolean captured = false;
		try {
			var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
			var dispatcher = client.getEntityRenderDispatcher();
			// explicit extraction: the world pass's frustum and distance selection don't apply to the player's own layer
			var state = dispatcher.extractEntity(client.player, camera.cameraEntityPartialTicks);
			if (!(state instanceof AvatarRenderState avatar)) {
				return;
			}

			// a blob shadow in a layer of its own would float over the host's ground; no name over one's own head either
			avatar.nameTag = null;
			avatar.scoreText = null;
			avatar.shadowRadius = 0.0F;
			avatar.shadowPieces.clear();
			var storage = new SubmitNodeStorage();
			dispatcher.submit(state, camera, state.x - camera.pos.x, state.y - camera.pos.y, state.z - camera.pos.z, new PoseStack(), storage);
			if (projection == null) {
				projection = new ProjectionMatrixBuffer("passthrough avatar projection");
			}

			// the world pass's own camera and lens: the layer lines up with the world layer pixel for pixel
			modelView.set(camera.viewRotationMatrix);
			RenderSystem.setProjectionMatrix(projection.getBuffer(new Matrix4f(camera.projectionMatrix)), ProjectionType.PERSPECTIVE);
			try (var prepared = renderer.featureRenderDispatcher().prepareFrame(storage)) {
				var encoder = RenderSystem.getDevice().createCommandEncoder();
				encoder.clearDepthTexture(target.getDepthTexture(), 0.0); // reversed Z: 0 is far
				try (var pass = encoder.createRenderPass(() -> "passthrough avatar", target.getColorTextureView(), Optional.empty(),
					target.getDepthTextureView(), OptionalDouble.empty())) {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, prepared);
				}
			}

			captured = FrameExporter.captureAvatar(target);
		} catch (RuntimeException failure) {
			failed = true;
			Passthrough.LOG.error("avatar layer disabled (the player stays in the world layer from the next frame)", failure);
		} finally {
			RenderSystem.setProjectionMatrix(previousProjection, previousType);
			modelView.popMatrix();
			// the hand and HUD that follow must never hold a second copy of the player
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.clearColorTexture(target.getColorTexture(), TRANSPARENT);
			encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
			if (!captured) {
				// the world layer was drawn without the player: never publish it without the avatar layer
				FrameExporter.abandon();
			}
		}
	}

	public static void close() {
		if (projection != null) {
			projection.close();
			projection = null;
		}

		failed = false;
		isolated = false;
	}
}
