package com.example.autotreefarm;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class AutoTreeFarmClient implements ClientModInitializer {
	public static final String MOD_ID = "autotreefarm";
	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MOD_ID, "controls"));
	private static KeyBinding toggleKey;
	private static final TreeFarmBot BOT = new TreeFarmBot();

	@Override
	public void onInitializeClient() {
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.autotreefarm.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				if (client.currentScreen == null && client.player != null) {
					if (BOT.isRunning()) BOT.stop(client, "Остановлено");
					else BOT.start(client);
				}
			}
			BOT.tick(client);
		});
	}

	static void msg(MinecraftClient c, String s) {
		if (c.player != null) c.player.sendMessage(Text.literal("[TreeFarm] " + s), false);
	}
}
