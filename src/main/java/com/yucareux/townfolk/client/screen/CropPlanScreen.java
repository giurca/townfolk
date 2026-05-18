package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.town.CropPlan;
import com.yucareux.townfolk.world.inventory.CropPlanMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side popup for editing a parcel's {@link CropPlan}.
 *
 *   ┌───────────────────────────────────────┐
 *   │  Plant plan                           │
 *   │  ┌──┐  ×3  [+] [−]                    │  ← row 0 (wheat slot, weight 3)
 *   │  ┌──┐  ×2  [+] [−]                    │  ← row 1 (carrot, weight 2)
 *   │  ┌──┐  ×1  [+] [−]                    │  ← row 2
 *   │  ┌──┐  …                              │  ← row 3..5 empty
 *   │  ──── Your inventory ────             │
 *   │  ▒▒▒▒▒▒▒▒▒                            │
 *   │  ▒▒▒▒▒▒▒▒▒                            │
 *   │  ▒▒▒▒▒▒▒▒▒                            │
 *   │  ▒▒▒▒▒▒▒▒▒  (hotbar)                  │
 *   │  Preview: 50% wheat, 33% carrots …   │
 *   └───────────────────────────────────────┘
 *
 * Drag a seed from inventory (or JEI / EMI) onto a slot to set the crop
 * for that row; the row's weight auto-bumps to 1 if it was 0. Use +/−
 * to nudge the weight (0..10). Closing commits the plan.
 */
public final class CropPlanScreen extends AbstractContainerScreen<CropPlanMenu> {

   private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(
      "minecraft", "textures/gui/container/inventory.png");

   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int FG_PRIMARY   = 0xFFEDE0C2;
   private static final int FG_ACCENT    = 0xFFFFD27A;
   private static final int FG_DIM       = 0xFFB89B70;
   private static final int FG_FAINT     = 0xFF7A6849;

   private static final int ROW_H = 22;

   public CropPlanScreen(CropPlanMenu menu, Inventory inv, Component title) {
      super(menu, inv, title);
      this.imageWidth = 200;
      // Title (6) + 6 rows × 22 + inv label (~12) + 4 rows inv × 18 +
      // footer (preview line + Save button).
      // 28 + 132 + 12 + 76 + 24 = 272.
      this.imageHeight = 28 + (CropPlan.MAX_ENTRIES * ROW_H) + 12 + 76 + 24;
      this.titleLabelY = 6;
      this.inventoryLabelY = 28 + (CropPlan.MAX_ENTRIES * ROW_H);
   }

   @Override
   protected void init() {
      super.init();
      // +/− buttons per row, plus a clear-row button. Bound positions
      // match the menu's slot layout (slot at x=12, our buttons to the right).
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         final int row = i;
         int btnY = this.topPos + 28 + i * ROW_H + 1;
         addRenderableWidget(Button.builder(Component.literal("−"), b -> bumpWeight(row, false))
            .bounds(this.leftPos + 86, btnY, 18, 16).build());
         addRenderableWidget(Button.builder(Component.literal("+"), b -> bumpWeight(row, true))
            .bounds(this.leftPos + 108, btnY, 18, 16).build());
      }

      // Explicit "Save" button. Esc / E still commits via removed() —
      // the button exists so the action is unambiguous, with chat
      // feedback (sent by the server's removed() handler) so the
      // player sees confirmation. The preview line shares the footer
      // row to the left of the button.
      int saveY = this.topPos + this.imageHeight - 20;
      addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
         if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
         }
      }).bounds(this.leftPos + this.imageWidth - 56, saveY, 48, 16).build());
   }

   private void bumpWeight(int row, boolean up) {
      if (this.minecraft == null) return;
      int btn = up ? CropPlanMenu.BUTTON_BUMP_BASE + row
                   : CropPlanMenu.BUTTON_DROP_BASE + row;
      this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, btn);
   }

   @Override
   protected void renderBg(GuiGraphics g, float partialTicks, int mouseX, int mouseY) {
      int l = this.leftPos, t = this.topPos;
      int r = l + this.imageWidth, b = t + this.imageHeight;
      g.fill(l, t, r, b, PANEL_BG);
      g.fill(l - 1, t - 1, r + 1, t, PANEL_BORDER);
      g.fill(l - 1, b, r + 1, b + 1, PANEL_BORDER);
      g.fill(l - 1, t - 1, l, b + 1, PANEL_BORDER);
      g.fill(r, t - 1, r + 1, b + 1, PANEL_BORDER);

      // Slot backdrops for each plan row.
      int invX = 12, invY = 28;
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         g.fill(l + invX, t + invY + i * ROW_H, l + invX + 16, t + invY + i * ROW_H + 16, 0x40000000);
      }
      // Inventory + hotbar slot backdrops. Y matches the menu's
      // invOriginY (which is slotY0 + MAX_ENTRIES * rowH + 12, where
      // slotY0=28 and rowH=22). Off-by-2 alignment would leave item
      // icons hanging just outside their backdrop tiles.
      int playerInvY = t + 28 + (CropPlan.MAX_ENTRIES * ROW_H) + 12;
      for (int r0 = 0; r0 < 3; r0++) {
         for (int c0 = 0; c0 < 9; c0++) {
            int sx = l + 12 + c0 * 18;
            int sy = playerInvY + r0 * 18;
            g.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
         }
      }
      for (int c0 = 0; c0 < 9; c0++) {
         int sx = l + 12 + c0 * 18;
         int sy = playerInvY + 58;
         g.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
      }
   }

   @Override
   protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
      g.drawString(this.font,
         Component.literal("Plant plan").withStyle(ChatFormatting.GOLD),
         8, this.titleLabelY, FG_ACCENT, true);
      int totalW = computeTotalWeight();
      String hdrR = totalW > 0 ? (totalW + " total weight") : "set seed + weight";
      int hw = this.font.width(hdrR);
      g.drawString(this.font, hdrR, this.imageWidth - 8 - hw, this.titleLabelY, FG_DIM, true);

      // Per-row labels: weight value + crop name to the left of the buttons.
      // Empty rows just show the drag hint — no "×N" so the player isn't
      // confused by a weight value with no underlying seed.
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         int rowY = 28 + i * ROW_H;
         var stack = this.menu.ghostContainer().getItem(i);
         if (stack.isEmpty()) {
            g.drawString(this.font, "(drag a seed)", 36, rowY + 5, FG_FAINT, true);
            continue;
         }
         int w = this.menu.weight(i);
         int textColor = w > 0 ? FG_PRIMARY : FG_DIM;
         g.drawString(this.font, "×" + w, 36, rowY + 5, textColor, true);
         String name = shortItemName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
         String shown = name;
         int maxW = 46;
         if (this.font.width(shown) > maxW) {
            shown = this.font.plainSubstrByWidth(shown, maxW - 4) + "…";
         }
         g.drawString(this.font, shown, 56, rowY + 5, FG_DIM, true);
      }

      // Inventory label.
      g.drawString(this.font,
         Component.literal("Your inventory").withStyle(ChatFormatting.GRAY),
         8, this.inventoryLabelY, FG_DIM, true);

      // Preview line in the footer band, sharing the row with the
      // Save button (button is on the right; we get the left).
      int previewY = this.imageHeight - 16;
      String preview = buildPreview();
      // Truncate so we don't run into the Save button at imageWidth-56.
      int maxPreviewW = this.imageWidth - 56 - 8 - 8;
      if (this.font.width(preview) > maxPreviewW) {
         preview = this.font.plainSubstrByWidth(preview, maxPreviewW - 4) + "…";
      }
      g.drawString(this.font, preview, 8, previewY, FG_FAINT, true);
   }

   private int computeTotalWeight() {
      int sum = 0;
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         var stack = this.menu.ghostContainer().getItem(i);
         if (stack.isEmpty()) continue;
         sum += this.menu.weight(i);
      }
      return sum;
   }

   private String buildPreview() {
      int total = computeTotalWeight();
      if (total <= 0) return "Preview: (no active rows)";
      StringBuilder sb = new StringBuilder("Preview: ");
      boolean first = true;
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         ItemStack stack = this.menu.ghostContainer().getItem(i);
         int w = this.menu.weight(i);
         if (stack.isEmpty() || w <= 0) continue;
         if (!first) sb.append(", ");
         int pct = Math.round(w * 100f / total);
         sb.append(pct).append("% ").append(
            shortItemName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
         first = false;
      }
      return sb.toString();
   }

   private static String shortItemName(String id) {
      int colon = id.indexOf(':');
      return (colon < 0 ? id : id.substring(colon + 1)).replace('_', ' ');
   }

   @Override
   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
      super.render(g, mouseX, mouseY, partialTicks);
      this.renderTooltip(g, mouseX, mouseY);
   }
}
