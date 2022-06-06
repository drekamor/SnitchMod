package gjum.minecraft.civ.snitchmod.common;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import gjum.minecraft.civ.snitchmod.common.model.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static gjum.minecraft.civ.snitchmod.common.model.SnitchCreatedChatParser.getSnitchCreationFromChat;

public abstract class SnitchMod {
	private final static Minecraft mc = Minecraft.getInstance();

	private static final KeyMapping openGuiKey = new KeyMapping(
			"key.snitchmod.openGui",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_L,
			"category.snitchmod"
	);

	private static final KeyMapping toggleOverlayKey = new KeyMapping(
			"key.snitchmod.toggleOverlay",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_O,
			"category.snitchmod"
	);

	private static final KeyMapping togglePlacementKey = new KeyMapping(
			"key.snitchmod.togglePlacement",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_P,
			"category.snitchmod"
	);

	private static SnitchMod INSTANCE;

	public boolean rangeOverlayVisible = false;
	public boolean placementHelperVisible = false;

	@Nullable
	private SnitchesStore store;

	public static SnitchMod getMod() {
		return INSTANCE;
	}

	public SnitchMod() {
		if (INSTANCE != null) throw new IllegalStateException("Constructor called twice");
		INSTANCE = this;
	}

	public void init() {
		registerKeyBinding(openGuiKey);
		registerKeyBinding(toggleOverlayKey);
		registerKeyBinding(togglePlacementKey);
	}

	public abstract void registerKeyBinding(KeyMapping mapping);

	public @Nullable String getCurrentServer() {
		final ServerData currentServer = mc.getCurrentServer();
		if (currentServer == null) return null;
		return currentServer.ip;
	}

	public String getCurrentWorld() {
		if (mc.level == null) return null;
		return mc.level.dimension().location().getPath();
	}

	public UUID getClientUuid() {
		if (mc.player == null) return null;
		return mc.player.getUUID();
	}

	public @Nullable SnitchesStore getStore() {
		String server = getCurrentServer();
		if (store != null && !store.server.equals(server)) {
			store.close();
			store = null;
		}
		if (store == null && server != null) {
			store = new SnitchesStore(server);
		}
		return store;
	}

	public void handleConnectedToServer() {
		getStore();
	}

	public void handleDisconnectedFromServer() {
		if (store != null) store.close();
		store = null;
	}

	public void handleTick() {
		while (openGuiKey.consumeClick()) {
			// TODO open gui
		}
		while (toggleOverlayKey.consumeClick()) {
			rangeOverlayVisible = !rangeOverlayVisible;
			mc.gui.getChat().addMessage(new TextComponent(
					"Range overlay " + (rangeOverlayVisible ? "visible" : "off")));
		}
		while (togglePlacementKey.consumeClick()) {
			placementHelperVisible = !placementHelperVisible;
			mc.gui.getChat().addMessage(new TextComponent(
					"Placement helper " + (placementHelperVisible ? "visible" : "off")));
		}
		// TODO if block pos changed -> if pos inside snitch range not in before -> send jainfo -> mark refreshed
	}

	/**
	 * Returns true when the packet should be dropped
	 */
	public boolean handleChat(Component message) {
		getStore();
		if (store == null) return false;

		SnitchAlert snitchAlert = SnitchAlert.fromChat(message, store.server);
		if (snitchAlert != null) store.updateSnitchFromAlert(snitchAlert);

		Snitch snitchCreated = getSnitchCreationFromChat(message, store.server, getCurrentWorld(), getClientUuid());
		if (snitchCreated != null) store.updateSnitchFromCreation(snitchCreated);

		long now = System.currentTimeMillis();
		if (lastBrokenBlockTs > now - 1000 && lastBrokenBlockPos != null) {
			SnitchBroken snitchBroken = SnitchBroken.fromChat(message, lastBrokenBlockPos, store.server, getCurrentWorld());
			if (snitchBroken != null) store.updateSnitchBroken(snitchBroken);
		}

		// TODO if chat is jainfo and can refresh group -> mark refreshed

		return false;
	}

	private BlockPos lastBrokenBlockPos;
	private long lastBrokenBlockTs;

	public void handleBlockUpdate(BlockPos pos, BlockState blockState) {
		if (blockState == Blocks.AIR.defaultBlockState()) {
			lastBrokenBlockPos = pos;
			lastBrokenBlockTs = System.currentTimeMillis();
		}
	}

	public void handleSetSlot(ItemStack stack) {
		getStore();
		if (store == null) return;
		JalistEntry jalistEntry = JalistEntry.fromStack(stack, store.server);
		if (jalistEntry != null) store.updateSnitchFromJalist(jalistEntry);
	}

	public void handleRenderBlockOverlay(PoseStack matrices, float partialTicks) {
		Renderer.renderOverlays(matrices, partialTicks);
	}

	public Stream<Snitch> streamNearbySnitches(BlockPos playerPos, int distance) {
		getStore();
		if (store == null) return Stream.empty();
		AABB aabb = new AABB(playerPos).inflate(distance);
		return store.getAllSnitches().stream()
				.filter(s -> aabb.contains(s.getX(), s.getY(), s.getZ()))
				.sorted(Comparator.comparing(playerPos::distSqr));
	}
}
