package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.SelectParcelTypePayload;
import com.yucareux.townfolk.villager.FieldRegion;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Modal popup shown after the player captures both corners of a
 * parcel with the Surveyor's Stake. The player picks
 * {@link FieldRegion.Type} (Crops or Animals); closing without
 * picking (Escape / outside click) cancels the binding.
 *
 * <p>Modernised to match the Trade-popup modal language: dim full-
 * screen backdrop, dark panel with the trade-modal palette, custom-
 * drawn chip buttons (green for the two type choices, neutral for
 * Cancel) instead of vanilla {@code Button} chrome.
 */
public final class ParcelTypePopupScreen extends Screen {

   // ── Visual constants — matched to the rest of the admin modals. ──
   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int CHIP_PRIMARY_BG     = 0xFF3C5A22;
   private static final int CHIP_PRIMARY_BORDER = 0xFF6FA445;
   private static final int CHIP_PRIMARY_FG     = 0xFFE8FFD2;
   private static final int CHIP_NEUTRAL_BG     = 0xFF2A2018;
   private static final int CHIP_NEUTRAL_BORDER = 0xFFB89B70;
   private static final int CHIP_NEUTRAL_FG     = 0xFFEDE0C2;
   private static final int FG_ACCENT = 0xFFFFD27A;
   private static final int FG_DIM    = 0xFFB89B70;
   private static final int FG_FAINT  = 0xFF7A7A7A;

   // ── Modal geometry. ──
   private static final int PANEL_W = 340;
   private static final int PANEL_H = 168;
   private static final int CHIP_W  = 140;
   private static final int CHIP_H  = 26;
   private static final int CANCEL_W = 88;
   private static final int CANCEL_H = 18;

   private final int parcelSizeX;
   private final int parcelSizeZ;

   /** Set to true once a button is clicked so {@link #onClose} knows
    *  this isn't a cancel-by-Escape. */
   private boolean chosen = false;

   public ParcelTypePopupScreen(int sizeX, int sizeZ) {
      super(Component.literal("Choose parcel type"));
      this.parcelSizeX = sizeX;
      this.parcelSizeZ = sizeZ;
   }

   // ── No vanilla Button widgets — chips are custom-drawn and hit-
   //    tested in mouseClicked. init() is empty by design. ──

   private void choose(FieldRegion.Type type) {
      chosen = true;
      PacketDistributor.sendToServer(new SelectParcelTypePayload(type.name()));
      if (this.minecraft != null) this.minecraft.setScreen(null);
   }

   private void cancel() {
      chosen = true;     // mark handled so onClose doesn't double-send
      PacketDistributor.sendToServer(new SelectParcelTypePayload(SelectParcelTypePayload.CANCEL));
      if (this.minecraft != null) this.minecraft.setScreen(null);
   }

   @Override
   public void onClose() {
      if (!chosen) {
         PacketDistributor.sendToServer(new SelectParcelTypePayload(SelectParcelTypePayload.CANCEL));
      }
      super.onClose();
   }

   @Override
   public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
      // Uniform dim — no vanilla blur — same backdrop the BuildingPermit
      // and AnimalPlan modals use.
      g.fill(0, 0, this.width, this.height, 0xB0000000);
   }

   @Override
   public void render(GuiGraphics g, int mx, int my, float pt) {
      this.renderBackground(g, mx, my, pt);

      int panelX = (this.width - PANEL_W) / 2;
      int panelY = (this.height - PANEL_H) / 2;

      // Panel frame.
      g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
      g.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY, PANEL_BORDER);
      g.fill(panelX - 1, panelY + PANEL_H, panelX + PANEL_W + 1, panelY + PANEL_H + 1, PANEL_BORDER);
      g.fill(panelX - 1, panelY, panelX, panelY + PANEL_H, PANEL_BORDER);
      g.fill(panelX + PANEL_W, panelY, panelX + PANEL_W + 1, panelY + PANEL_H, PANEL_BORDER);

      // Title + size readout, centred.
      g.drawCenteredString(this.font,
         Component.literal("What kind of parcel?").withStyle(ChatFormatting.GOLD),
         this.width / 2, panelY + 14, FG_ACCENT);
      g.drawCenteredString(this.font,
         Component.literal(parcelSizeX + " × " + parcelSizeZ + " blocks").withStyle(ChatFormatting.GRAY),
         this.width / 2, panelY + 28, FG_DIM);

      // Two primary chips (Crops / Animals).
      int chipsY = panelY + 60;
      int gap = 12;
      int chipsTotalW = CHIP_W * 2 + gap;
      int chipsStartX = (this.width - chipsTotalW) / 2;
      drawPrimaryChip(g, chipsStartX, chipsY,
         "🌾 Crops", mx, my);
      drawPrimaryChip(g, chipsStartX + CHIP_W + gap, chipsY,
         "🐑 Animals", mx, my);

      // Cancel — neutral chip at the bottom.
      int cancelX = (this.width - CANCEL_W) / 2;
      int cancelY = panelY + PANEL_H - 30;
      drawNeutralChip(g, cancelX, cancelY, "Cancel", mx, my);

      g.drawCenteredString(this.font,
         Component.literal("Press Escape to cancel.").withStyle(ChatFormatting.DARK_GRAY),
         this.width / 2, panelY + PANEL_H - 12, FG_FAINT);

      super.render(g, mx, my, pt);
   }

   private void drawPrimaryChip(GuiGraphics g, int x, int y, String label, int mx, int my) {
      boolean hovered = mx >= x && mx < x + CHIP_W && my >= y && my < y + CHIP_H;
      int bg = hovered ? brighten(CHIP_PRIMARY_BG) : CHIP_PRIMARY_BG;
      g.fill(x, y, x + CHIP_W, y + CHIP_H, bg);
      g.fill(x, y + CHIP_H, x + CHIP_W, y + CHIP_H + 1, CHIP_PRIMARY_BORDER);
      int lw = this.font.width(label);
      g.drawString(this.font, label, x + (CHIP_W - lw) / 2, y + 9, CHIP_PRIMARY_FG, true);
   }

   private void drawNeutralChip(GuiGraphics g, int x, int y, String label, int mx, int my) {
      boolean hovered = mx >= x && mx < x + CANCEL_W && my >= y && my < y + CANCEL_H;
      int bg = hovered ? brighten(CHIP_NEUTRAL_BG) : CHIP_NEUTRAL_BG;
      g.fill(x, y, x + CANCEL_W, y + CANCEL_H, bg);
      g.fill(x, y + CANCEL_H, x + CANCEL_W, y + CANCEL_H + 1, CHIP_NEUTRAL_BORDER);
      int lw = this.font.width(label);
      g.drawString(this.font, label, x + (CANCEL_W - lw) / 2, y + 5, CHIP_NEUTRAL_FG, true);
   }

   private static int brighten(int argb) {
      int a = (argb >>> 24) & 0xFF;
      int r = Math.min(255, ((argb >>> 16) & 0xFF) + 20);
      int gC= Math.min(255, ((argb >>> 8) & 0xFF) + 20);
      int b = Math.min(255, (argb & 0xFF) + 20);
      return (a << 24) | (r << 16) | (gC << 8) | b;
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
      int panelX = (this.width - PANEL_W) / 2;
      int panelY = (this.height - PANEL_H) / 2;

      int chipsY = panelY + 60;
      int gap = 12;
      int chipsTotalW = CHIP_W * 2 + gap;
      int chipsStartX = (this.width - chipsTotalW) / 2;
      int cropsX  = chipsStartX;
      int animalsX = chipsStartX + CHIP_W + gap;
      if (mouseY >= chipsY && mouseY < chipsY + CHIP_H) {
         if (mouseX >= cropsX  && mouseX < cropsX  + CHIP_W) {
            choose(FieldRegion.Type.PLANT);
            return true;
         }
         if (mouseX >= animalsX && mouseX < animalsX + CHIP_W) {
            choose(FieldRegion.Type.ANIMAL);
            return true;
         }
      }

      int cancelX = (this.width - CANCEL_W) / 2;
      int cancelY = panelY + PANEL_H - 30;
      if (mouseY >= cancelY && mouseY < cancelY + CANCEL_H
          && mouseX >= cancelX && mouseX < cancelX + CANCEL_W) {
         cancel();
         return true;
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean isPauseScreen() { return false; }
}
