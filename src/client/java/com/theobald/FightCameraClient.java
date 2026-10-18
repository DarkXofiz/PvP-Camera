package com.theobald;

import com.theobald.command.FightCamCommand;
import com.theobald.gui.RecordingsScreen;
import com.theobald.recording.RecordingHud;
import com.theobald.recording.VideoRecorder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.lwjgl.glfw.GLFW;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class FightCameraClient implements ClientModInitializer {
	public static MinecraftClient client;
	private static boolean flashbackLoaded = false;
	public boolean keybindPressed = false;
	public static boolean toggled = false;
	public static boolean active = false;
	public static boolean trackPearls = false;
	public static boolean drawLines = false;
	/** true = camera follows YOU (chase camera). false = side camera on two named players (/fightcam players ...) */
	public static boolean autoSelf = true;
	/** true while the camera is currently in "own fight" mode */
	public static boolean selfMode = false;
	private static boolean wasActive = false;
	private static PlayerEntity lastOpponent = null;
	private static final double ENGAGE_RANGE = 12.0;
	private static final double RELEASE_RANGE = 20.0;
	public static AnchorMode anchor = AnchorMode.AVERAGE;
	public static HeightMode heightMode = HeightMode.AVERAGE;
	public static DistanceMode distanceMode = DistanceMode.AUTO	;
	public static String[] playerStrings = {"", ""};
	public static PlayerEntity[] players;
	public static float[] groundHeights = {60, 60};
	public static float smoothFactor = 0.7f;
	public static float distance = 3f;
	public static Vec3d lastTargetPos = Vec3d.ZERO;
	public static Vec3d currentTargetPos = Vec3d.ZERO;
	public static float lastTargetYaw = 0f;
	public static float currentTargetYaw = 0f;
	public static float height = 1.5f;
	public static float xoffset = 0;
	public static float aspectRatio;
	public static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	//public static final Identifier WHITE_PIXEL = new Identifier("modid", "textures/misc/white_pixel.png");
	DecimalFormat df = new DecimalFormat();

	public enum AnchorMode {
		P1,
		P2,
		AVERAGE
	}

	public enum HeightMode {
		AVERAGE,
		GROUND
	}

	public enum DistanceMode {
		STATIC,
		AUTO,
	}

	@Override
	public void onInitializeClient() {
		client = MinecraftClient.getInstance();
		df.setMaximumFractionDigits(1);

		var toggleBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"Toggle PvP Camera",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_SEMICOLON,
				"PvP Camera"
		));

		// Video recording: press once to start, press again to stop
		var recordBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"Kaydı Başlat / Durdur",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_APOSTROPHE,
				"PvP Camera"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			onUpdate();
			if (toggleBind.isPressed()) {
				if (!keybindPressed) {
					toggle();
					keybindPressed = true;
				}
			}
			else {
				keybindPressed = false;
			}

			while (recordBind.wasPressed()) {
				if (client.world != null) VideoRecorder.toggle();
			}
			VideoRecorder.tick();
		});
		WorldRenderEvents.START.register(context -> onRender(context.tickDelta()));

		HudRenderCallback.EVENT.register(RecordingHud::render);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
				.register(c -> VideoRecorder.shutdown());

		// Small square button on the right of the pause menu -> opens the recordings list
		ScreenEvents.AFTER_INIT.register((mc, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen) {
				ButtonWidget button = ButtonWidget.builder(
								Text.literal("●").formatted(Formatting.RED),
								b -> mc.setScreen(new RecordingsScreen(screen)))
						.dimensions(scaledWidth / 2 + 102 + 4, scaledHeight / 4 + 8, 20, 20)
						.tooltip(Tooltip.of(Text.literal("Kayıtlar")))
						.build();
				Screens.getButtons(screen).add(button);
			}
		});

		FightCamCommand.register();
		if (FabricLoader.getInstance().isModLoaded("flashback")) { flashbackLoaded = true; }
	}

	private void onUpdate() {
		if (client.player != null) {
			if (toggled) {
				active = updatePlayers();
			} else {
				active = false;
			}

			if (active && !wasActive) {
				initTargets();
			}
			wasActive = active;

			if (active) {
				Window window = client.getWindow();
				aspectRatio = (float) window.getFramebufferWidth() / window.getFramebufferHeight();
				if (selfMode) return; // follow-me mode: the camera is a chase camera, nothing to calculate
				updateTarget(CalculateCameraPos());
				updateYawTarget(CalculateCameraYaw());
				updateGroundHeights();

				if (!selfMode) {
				if (client.options.jumpKey.isPressed()) {
					height += client.options.sprintKey.isPressed() ? .2f : .1f;
					client.player.sendMessage(Text.literal("Height: " + df.format(height)), true);
				}
				if (client.options.sneakKey.isPressed()) {
					height -= client.options.sprintKey.isPressed() ? .2f : .1f;
					client.player.sendMessage(Text.literal("Height: " + df.format(height)), true);
				}
				if (client.options.forwardKey.isPressed()) {
					distance -= client.options.sprintKey.isPressed() ? .2f : .1f;
					client.player.sendMessage(Text.literal("Distance: " + df.format(distance)), true);
				}
				if (client.options.backKey.isPressed()) {
					distance += client.options.sprintKey.isPressed() ? .2f : .1f;
					client.player.sendMessage(Text.literal("Distance: " + df.format(distance)), true);
				}
				if (anchor != AnchorMode.AVERAGE) {
					float input = .1f;
					input *= anchor == AnchorMode.P1 ? 1 : -1;
					input *= client.options.sprintKey.isPressed() ? 2 : 1;

					if (client.options.rightKey.isPressed()) {
						xoffset += input;
						client.player.sendMessage(Text.literal("Offset: " + df.format(xoffset)), true);
					}
					if (client.options.leftKey.isPressed() && anchor != AnchorMode.AVERAGE) {
						xoffset -= input;
						client.player.sendMessage(Text.literal("Offset: " + df.format(xoffset)), true);
					}
				}
				}
			}
		}
	}

	private void onRender(float tickDelta) {
		if (selfMode) return; // in own-fight mode the camera is placed by CameraMixin
		Camera camera = client.gameRenderer.getCamera();
		if (camera != null && active) {
			//frame interpolation
			Vec3d newPos = getInterpolated(tickDelta);
			float newYaw = lerpAngle(lastTargetYaw,currentTargetYaw,tickDelta);

			camera.setPos(newPos);
			client.player.setYaw(newYaw);

			if (client.player.isSpectator()) {
				Vec3d spectatorPos = lastTargetPos.lerp(currentTargetPos, tickDelta);
				client.player.setPos(spectatorPos.x, spectatorPos.y, spectatorPos.z);
			}
		}
	}

	public static Vec3d CalculateCameraPos() {
		Vec3d newTargetPos;

		Vec3d player1Pos;
		Vec3d player2Pos;

		if (trackPearls) {
			player1Pos = getPosIncludingPearls(players[0]);
			player2Pos = getPosIncludingPearls(players[1]);
		} else {
			player1Pos = players[0].getPos();
			player2Pos = players[1].getPos();
		}

		Vec3d avg = Util.Average(player1Pos,player2Pos);
		Vec3d distanceVec = player1Pos.subtract(player2Pos);
		Vec3d hDistanceVec = new Vec3d(distanceVec.x, 0, distanceVec.z);
		Vec3d orthVec = Util.Orthoganal(distanceVec).normalize();
		Vec3d offset = orthVec.multiply(distance).add(new Vec3d(0, height, 0));

		//in case last pos is super far away
		if (currentTargetPos.subtract(lastTargetPos).length() > 500) { return avg; }

		//anchor xz position
		switch (anchor) {
			case P1 -> {
				newTargetPos = players[0].getPos();
				offset = offset.add(hDistanceVec.normalize().multiply(-xoffset));
			}
			case P2 -> {
				newTargetPos = players[1].getPos();
				offset = offset.add(hDistanceVec.normalize().multiply(xoffset));
			}
			default -> {
				newTargetPos = avg;
			}
		}

		//anchor height
		switch (heightMode) {
			case AVERAGE -> {

			}
			case GROUND -> {
				float avgGroundHeight = (groundHeights[0] + groundHeights[1]) / 2;
				newTargetPos = new Vec3d(newTargetPos.x, avgGroundHeight, newTargetPos.z);
				//System.out.println(avgGroundHeight);
			}
		}

		//auto distance
        if (Objects.requireNonNull(distanceMode) == DistanceMode.AUTO) {
            int fov = client.options.getFov().getValue();
            float autoDist = Util.FindAutoDistance(distanceVec, fov);
            offset = offset.add(orthVec.multiply(autoDist));
        }

		newTargetPos = newTargetPos.add(offset);
		return Util.SmoothStep(currentTargetPos, newTargetPos, smoothFactor * ((smoothFactor == 1) ? 1 : Util.DistanceSmoothingCoeff((float) distanceVec.length())));
	}

	public static float CalculateCameraYaw() {
		Vec3d distanceVec;
		if (trackPearls) {
			distanceVec = getPosIncludingPearls(players[0]).subtract(getPosIncludingPearls(players[1]));
		} else {
			distanceVec = players[0].getPos().subtract(players[1].getPos());
		}

		Vec3d orthVec = distanceVec.normalize();
		float newTargetYaw = (float) Math.toDegrees((float) Math.atan2(orthVec.z, orthVec.x));
		return lerpAngle(currentTargetYaw, newTargetYaw, smoothFactor * ((smoothFactor == 1) ? 1 : Util.DistanceSmoothingCoeff((float) distanceVec.length())));
	}

	public static void toggle() {
		toggled = !toggled;
		if (toggled) {
			if (client.player != null && updatePlayers()) {
				sendMessage(selfMode ? "PvP Camera: kamera seni takip ediyor" : "PvP Camera enabled!");
				initTargets();
			}
			else if (autoSelf) {
				active = false;
				sendMessage("PvP Camera: kamera seni takip ediyor");
			}
			else {
				active = false;
				sendMessage("PvP Camera disabled!");
			}
		}
		else {
			assert client.player != null;
			client.player.sendMessage(Text.literal("PvP Camera disabled!"), true);
		}

	}

	/** Switch to "own fight" mode: camera follows you and the nearest opponent. */
	public static void enableSelfMode() {
		autoSelf = true;
		lastOpponent = null;
		sendMessage("PvP Camera: kamera seni takip ediyor");
		if (!toggled) toggle();
	}

	private static void initTargets() {
		if (selfMode) return;
		if (players == null || players[0] == null || players[1] == null) return;
		Vec3d start = Util.Average(players[0].getPos(), players[1].getPos());
		Vec3d dir = players[0].getPos().subtract(players[1].getPos()).normalize();
		float yaw = (float) Math.toDegrees(Math.atan2(dir.z, dir.x));
		currentTargetPos = start;
		lastTargetPos = start;
		currentTargetYaw = yaw;
		lastTargetYaw = yaw;
		Window window = client.getWindow();
		aspectRatio = (float) window.getFramebufferWidth() / window.getFramebufferHeight();
		updateGroundHeights();
	}

	/** Pitch that makes the camera look at the middle of the two fighters. */
	public static float computePitch(Vec3d camPos) {
		if (players == null || players[0] == null || players[1] == null) return 15f;
		Vec3d look = Util.Average(players[0].getPos(), players[1].getPos()).add(0, 1.0, 0);
		double dx = camPos.x - look.x;
		double dz = camPos.z - look.z;
		double horizontal = Math.max(Math.sqrt(dx * dx + dz * dz), 0.1);
		float pitch = (float) Math.toDegrees(Math.atan2(camPos.y - look.y, horizontal));
		return Util.clamp(pitch, -30f, 60f);
	}

	public static boolean getActive() {
		return active;
	}

	public static void setPlayers(PlayerEntity[] p) {
		players = p;
	}

	public static boolean checkPlayers() {
		return (playerStrings[0] != null && playerStrings[1] != null);
	}

	public static void updateTarget(Vec3d newPos) {
		lastTargetPos = currentTargetPos;
		currentTargetPos = newPos;
	}

	public static void updateYawTarget(float newYaw) {
		lastTargetYaw = currentTargetYaw;
		currentTargetYaw = newYaw;
	}

	public static float lerpAngle(float a, float b, float t) {
		float delta = ((b - a + 180f) % 360f + 360f) % 360f - 180f;
		return a + delta * t;
	}

	public static Vec3d getInterpolated(double tickDelta) {
		return lastTargetPos.lerp(currentTargetPos, tickDelta);
	}

	public static void setDistanceMode(String s) {
		try {
			var f = Float.parseFloat(s);
			distance = f;
			distanceMode = DistanceMode.STATIC;
			sendMessage("Distance set to " + f);
		}
		catch (NumberFormatException e) {
			if (Objects.equals(s, "auto")) {
				distanceMode = DistanceMode.AUTO;
				anchor = AnchorMode.AVERAGE;
				sendMessage("Distance set to " + s);
			}
			else {
				sendMessage("Distance set to " + distanceMode.toString());
			}
		}
	}

	public static void setAnchorMode(String s) {
		switch (s) {
			case "avg" -> {
				anchor = AnchorMode.AVERAGE;
				sendMessage("Anchor set to AVERAGE");
			}
			case "p1" -> {
				anchor = AnchorMode.P1;
				distanceMode = DistanceMode.STATIC;
				sendMessage("Anchor set to player 1");
			}
			case "p2" -> {
				anchor = AnchorMode.P2;
				distanceMode = DistanceMode.STATIC;
				sendMessage("Anchor set to player 2");
			}
			default -> {
				sendMessage("Anchor set to " + anchor.toString());
			}
		}
	}

	public static void setHeightMode(String s) {
		try {
			var f = Float.parseFloat(s);
			height = f;
			sendMessage("Height set to " + f);
		}
		catch (NumberFormatException e) {
			switch (s) {
				case "avg" -> {
					heightMode = HeightMode.AVERAGE;
					sendMessage("Height mode set to AVERAGE");
				}
				case "ground" -> {
					heightMode = HeightMode.GROUND;
					sendMessage("Height mode set to GROUND");
				}
				default -> {
					sendMessage("Height mode set to " + heightMode.toString());
				}
			}
		}
	}

	public static void setSmoothFactor(float smoothFactor) {
		FightCameraClient.smoothFactor = smoothFactor;
	}

	private static void sendMessage(String message) {
		PlayerEntity player = MinecraftClient.getInstance().player;
		if (player != null) {
			player.sendMessage(Text.literal(message), false);
		}
	}

	public static boolean updatePlayers()
	{
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return false;

		if (autoSelf) return updateSelfPlayers(mc);
		selfMode = false;

		if (!checkPlayers()) return false;

		PlayerEntity p1 = mc.world.getPlayers()
				.stream().filter(p -> p.getName().getString().equals(playerStrings[0])).findFirst().orElse(null);
		PlayerEntity p2 = mc.world.getPlayers()
				.stream().filter(p -> p.getName().getString().equals(playerStrings[1])).findFirst().orElse(null);

		if (p1 == null || p2 == null) {
			if (active) sendMessage("One or both players not found.");
			active = false;
			return false;
		}

		FightCameraClient.setPlayers(new PlayerEntity[]{p1, p2});
		return true;
	}

	/** Follow-me mode: the camera follows YOU (chase camera behind you). No opponent needed. */
	private static boolean updateSelfPlayers(MinecraftClient mc) {
		selfMode = true;
		return true;
	}

	/** Camera distance behind you in follow-me mode (/fightcam distance <n> changes it). */
	public static double followDistance() {
		return distanceMode == DistanceMode.STATIC ? Math.max(1.0, distance) : 4.0;
	}

	public static void updateGroundHeights() {
		for (int i = 0; i < 2; i++) {
			PlayerEntity player = players[i];
			BlockPos blockPos = player.getBlockPos().down();
			BlockState state = player.getWorld().getBlockState(blockPos);
			VoxelShape shape = state.getCollisionShape(client.world, blockPos);

			if (!shape.isEmpty()) {
				groundHeights[i] = blockPos.getY() + 1;
			}
		}
	}

	public static Vec3d getPosIncludingPearls(PlayerEntity player) {
		Vec3d playerPos = player.getPos();
		List<Vec3d> playerPearls = Util.GetPlayerPearlsPos(player.getWorld(), player);
		if (playerPearls.isEmpty()) {
			return playerPos;
		}

		return Util.Average(playerPos, Util.Average(playerPearls));
	}
}
