package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.SelectParcelTypePayload;
import com.yucareux.townfolk.villager.FieldRegion;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Modal popup shown after the player captures both corners of a parcel
 * with the Surveyor's Stake. The player picks {@link FieldRegion.Type}
 * (Crops or Animals) and the parcel is committed server-side; closing
 * without picking (Escape) cancels the binding.
 *
 * Deliberately minimal: title, size readout, two big buttons. No
 * scrollable content, no tabs, no tooltips. New parcel types added
 * later (Lumber, Mine, …) get one more button each.
 */
public final class ParcelTypePopupScreen extends Screen {

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

   @Override
   protected void init() {
      int cx = this.width / 2;
      int cy = this.height / 2;
      int buttonW = 160;
      int buttonH = 30;
      int gap = 8;

      // Two-button row, centred under the title.
      addRenderableWidget(Button.builder(
         Component.literal("🌾  Crops"),     // 🌾
         b -> choose(FieldRegion.Type.PLANT))
         .bounds(cx - buttonW - gap / 2, cy, buttonW, buttonH)
         .build());

      addRenderableWidget(Button.builder(
         Component.literal("🐑  Animals"),   // 🐑
         b -> choose(FieldRegion.Type.ANIMAL))
         .bounds(cx + gap / 2, cy, buttonW, buttonH)
         .build());

      // Cancel — explicit, in addition to the Escape key.
      addRenderableWidget(Button.builder(
         Component.literal("Cancel"),
         b -> cancel())
         .bounds(cx - 50, cy + buttonH + gap + 10, 100, 20)
         .build());
   }

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
      // Escape / outside click — only send the cancel if a button
      // wasn't already handled.
      if (!chosen) {
         PacketDistributor.sendToServer(new SelectParcelTypePayload(SelectParcelTypePayload.CANCEL));
      }
      super.onClose();
   }

   @Override
   public void render(GuiGraphics g, int mx, int my, float pt) {
      this.renderBackground(g, mx, my, pt);
      int cx = this.width / 2;
      int cy = this.height / 2;
      // Title
      g.drawCenteredString(this.font,
         Component.literal("What kind of parcel?").withStyle(ChatFormatting.GOLD),
         cx, cy - 50, 0xFFFFD27A);
      // Size readout
      g.drawCenteredString(this.font,
         Component.literal(parcelSizeX + " × " + parcelSizeZ + " blocks").withStyle(ChatFormatting.GRAY),
         cx, cy - 32, 0xFFB89B70);
      // Sub-hint
      g.drawCenteredString(this.font,
         Component.literal("Press Escape to cancel.").withStyle(ChatFormatting.DARK_GRAY),
         cx, cy + 80, 0xFF7A7A7A);
      super.render(g, mx, my, pt);
   }

   @Override
   public boolean isPauseScreen() { return false; }
}
