package com.yucareux.townfolk.client;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * In-world HUD overlay rendering the last N log entries from the active
 * town. Visible while {@link ClientHudState#isHudVisible()} is true, gated
 * on having a town to display.
 *
 * Anchored bottom-left so it doesn't fight the hotbar (anchored bottom-
 * center) or the F3 debug screen (top-left).
 */
@EventBusSubscriber(modid = Townfolk.MODID, value = Dist.CLIENT)
public final class TownfolkHud {

   private static final ResourceLocation LAYER_ID =
      ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "town_log_hud");

   private static final int MAX_LINES = 10;
   private static final int PANEL_BG = 0xC0140C07;
   private static final int FG_PRIMARY = 0xFFEDE0C2;
   private static final int FG_DIM = 0xFFB89B70;
   private static final int FG_ACCENT = 0xFFFFD27A;
   private static final int FG_RESOLVED = 0xFF7AB46A;
   private static final int FG_ERROR = 0xFFC76A50;

   @SubscribeEvent
   public static void onRegisterLayers(RegisterGuiLayersEvent event) {
      event.registerAboveAll(LAYER_ID, TownfolkHud::render);
   }

   private static void render(GuiGraphics graphics, DeltaTracker delta) {
      if (!ClientHudState.isHudVisible()) return;
      Minecraft mc = Minecraft.getInstance();
      if (mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) return;
      if (mc.player == null) return;

      List<TownStateUpdatePayload.LogEntry> log = ClientHudState.log();
      int width = 420;
      int lineHeight = mc.font.lineHeight + 1;
      int x = 6;

      // Two-pass: first compute total height by walking from newest backward,
      // wrapping each message, until we run out of MAX_LINES worth of vertical
      // budget. Then render top-down.
      int maxVerticalLines = MAX_LINES;
      int linesUsed = 0;
      // Collect entries to render, newest-first then we'll reverse.
      java.util.ArrayList<Object[]> rendered = new java.util.ArrayList<>();   // {tag, color, msgLines}
      for (int i = log.size() - 1; i >= 0 && linesUsed < maxVerticalLines; i--) {
         TownStateUpdatePayload.LogEntry e = log.get(i);
         int color = switch (e.level()) {
            case "EXCHANGE" -> 0xFFB0D0FF;
            case "COMPACT"  -> FG_RESOLVED;
            case "DIALOGUE" -> FG_ACCENT;
            case "WARN"     -> FG_ERROR;
            default          -> FG_DIM;
         };
         String tag = "[" + e.level().toLowerCase(Locale.ROOT) + "]";
         int tagW = mc.font.width(tag) + 4;
         List<net.minecraft.util.FormattedCharSequence> wrapped =
            mc.font.split(Component.literal(e.message()), width - 12 - tagW);
         if (wrapped.isEmpty()) wrapped = mc.font.split(Component.literal(" "), 1);
         rendered.add(new Object[]{tag, color, tagW, wrapped});
         linesUsed += wrapped.size();
      }

      int contentH = (linesUsed + 1) * lineHeight + 6;
      int y = mc.getWindow().getGuiScaledHeight() - contentH - 6;

      graphics.fill(x, y, x + width, y + contentH, PANEL_BG);
      graphics.fill(x, y, x + width, y + 1, FG_ACCENT);

      String header = "Townfolk log — F8 to hide";
      graphics.drawString(mc.font, Component.literal(header).withStyle(ChatFormatting.GOLD),
         x + 6, y + 4, FG_ACCENT, true);

      if (rendered.isEmpty()) {
         graphics.drawString(mc.font, "(no activity yet)", x + 6, y + 4 + lineHeight, FG_DIM, true);
         return;
      }
      int row = y + 4 + lineHeight;
      // Walk in chronological order so newest lands at the bottom.
      for (int idx = rendered.size() - 1; idx >= 0; idx--) {
         Object[] r = rendered.get(idx);
         String tag = (String) r[0];
         int color = (int) r[1];
         int tagW = (int) r[2];
         @SuppressWarnings("unchecked")
         List<net.minecraft.util.FormattedCharSequence> wrapped =
            (List<net.minecraft.util.FormattedCharSequence>) r[3];
         graphics.drawString(mc.font, tag, x + 6, row, color, true);
         int textX = x + 6 + tagW;
         for (net.minecraft.util.FormattedCharSequence line : wrapped) {
            graphics.drawString(mc.font, line, textX, row, FG_PRIMARY, true);
            row += lineHeight;
         }
      }
   }

   private TownfolkHud() {}
}
