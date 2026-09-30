package dev.everhost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "everhost", value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class LagGuardClient {
	private static volatile boolean notices;
	private static volatile int stage;
	private static volatile int mspt;
	private static volatile int ping;
	private static volatile int view;
	private static volatile int simulation;
	private static volatile String reason = "";
	private static volatile long lastHeartbeat;

	private LagGuardClient() {
	}

	public static void update(boolean showNotices, int nextStage, int nextMspt, int nextPing, int nextView, int nextSimulation, String nextReason) {
		notices = showNotices;
		stage = nextStage;
		mspt = nextMspt;
		ping = nextPing;
		view = nextView;
		simulation = nextSimulation;
		reason = nextReason;
		lastHeartbeat = System.currentTimeMillis();
	}

	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END && Minecraft.getInstance().level == null) {
			lastHeartbeat = 0L;
			stage = 0;
		}
	}

	@SubscribeEvent
	public static void onRender(RenderGuiEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!notices || minecraft.level == null || lastHeartbeat == 0L || minecraft.options.hideGui) return;
		long delayed = System.currentTimeMillis() - lastHeartbeat;
		String title;
		String detail;
		int color;
		if (delayed > 3500L) {
			title = "Server updates delayed - please wait";
			detail = "Waiting " + Math.max(1L, delayed / 1000L) + "s for the server";
			color = 0xFFFF707C;
		} else if (stage > 0) {
			title = "Server under load - reducing chunk work";
			detail = mspt + " ms/tick  |  " + ping + " ms ping  |  view " + view + "  |  sim " + simulation + "  |  " + reason;
			color = 0xFFF0B45C;
		} else {
			return;
		}
		GuiGraphics graphics = event.getGuiGraphics();
		int textWidth = Math.max(minecraft.font.width(title), minecraft.font.width(detail));
		int boxWidth = Math.min(event.getWindow().getGuiScaledWidth() - 16, textWidth + 18);
		int x = (event.getWindow().getGuiScaledWidth() - boxWidth) / 2;
		int y = 34;
		graphics.fill(x, y, x + boxWidth, y + 29, 0xD9111419);
		graphics.fill(x, y, x + boxWidth, y + 2, color);
		graphics.drawCenteredString(minecraft.font, title, event.getWindow().getGuiScaledWidth() / 2, y + 7, 0xFFF1F4F7);
		graphics.drawCenteredString(minecraft.font, minecraft.font.plainSubstrByWidth(detail, boxWidth - 10), event.getWindow().getGuiScaledWidth() / 2, y + 18, 0xFFB9C2CE);
	}
}
