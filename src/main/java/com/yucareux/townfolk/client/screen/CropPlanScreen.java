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
 * Client-side editor for a parcel's {@link CropPlan}.
 *
 * <p>Layout (Stage 4 redesign — 2×3 card grid):
 * <pre>
 *  ┌─────────────────────────────────────────┐
 *  │  Plant plan                  3 total    │
 *  │  ┌─────────────┐  ┌─────────────┐       │
 *  │  │ ▒  wheat    │  │ ▒  carrot   │       │
 *  │  │ − [×3] +    │  │ − [×2] +    │       │
 *  │  └─────────────┘  └─────────────┘       │
 *  │  ┌─────────────┐  ┌─────────────┐       │
 *  │  │ ▒  beetroot │  │ (drag seed) │       │
 *  │  │ − [×1] +    │  │ − [×0] +    │       │
 *  │  └─────────────┘  └─────────────┘       │
 *  │  ┌─────────────┐  ┌─────────────┐       │
 *  │  │ (drag seed) │  │ (drag seed) │       │
 *  │  │ − [×0] +    │  │ − [×0] +    │       │
 *  │  └─────────────┘  └─────────────┘       │
 *  │  ──── Your inventory ────               │
 *  │  ▒▒▒▒▒▒▒▒▒                              │
 *  │  ▒▒▒▒▒▒▒▒▒                              │
 *  │  ▒▒▒▒▒▒▒▒▒                              │
 *  │  ▒▒▒▒▒▒▒▒▒  (hotbar)                    │
 *  │  Preview: 50% wheat, 33% carrots  [Save]│
 *  └─────────────────────────────────────────┘
 * </pre>
 *
 * <p>Drag a seed onto a card's slot to set the crop for that row;
 * weight bumps to 1 automatically if it was 0. ± nudges the weight
 * (0..10). Closing commits the plan.
 *
 * <p>Card constants must mirror {@link CropPlanMenu}'s slot placement.
 */
public final class CropPlanScreen extends AbstractContainerScreen<CropPlanMenu> {

   private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(
      "minecraft", "textures/gui/container/inventory.png");

   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int CARD_BG      = 0x80000000;
   private static final int CARD_BORDER  = 0xFF6E5230;
   private static final int FG_PRIMARY   = 0xFFEDE0C2;
   private static final int FG_ACCENT    = 0xFFFFD27A;
   private static final int FG_DIM       = 0xFFB89B70;
   private static final int FG_FAINT     = 0xFF7A6849;

   // Card grid constants — KEEP IN SYNC with CropPlanMenu.
   private static final int GRID_COLS = 2;
   private static final int CARD_W = 116;
   private static final int CARD_H = 54;
   private static final int CARD_GAP = 8;
   private static final int GRID_ORIGIN_X = 14;
   private static final int GRID_ORIGIN_Y = 28;
   private static final int GRID_ROWS = (CropPlan.MAX_ENTRIES + GRID_COLS - 1) / GRID_COLS;
   private static final int INV_ORIGIN_X = 30;
   private static final int INV_ORIGIN_Y =
      GRID_ORIGIN_Y + GRID_ROWS * CARD_H + (GRID_ROWS - 1) * CARD_GAP + 12;

   public CropPlanScreen(CropPlanMenu menu, Inventory inv, Component title) {
      super(menu, inv, title);
      // 2 cols × 116 + gap + side margins = 14 + 116 + 8 + 116 + 14 = 268.
      this.imageWidth = 268;
      // Title (~22) + cards (3 rows × 54 + 2 gaps × 8) = 178, +12 padding
      // + 12 inv label + 76 inv (4 rows × 18 + 4 gap) + 24 footer.
      // 22 + 178 + 12 + 12 + 76 + 24 = 324.
      this.imageHeight = 22 + 178 + 12 + 12 + 76 + 24;
      this.titleLabelY = 6;
      this.inventoryLabelY = INV_ORIGIN_Y - 12;
   }

   @Override
   protected void init() {
      super.init();
      // − [weight] + buttons live inside each card, bottom-left strip.
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         final int row = i;
         int col = i % GRID_COLS;
         int rowIdx = i / GRID_COLS;
         int cardX = this.leftPos + GRID_ORIGIN_X + col * (CARD_W + CARD_GAP);
         int cardY = this.topPos + GRID_ORIGIN_Y + rowIdx * (CARD_H + CARD_GAP);
         int btnY = cardY + CARD_H - 22;
         addRenderableWidget(Button.builder(Component.literal("−"),
               b -> bumpWeight(row, false))
            .bounds(cardX + 6, btnY, 16, 18).build());
         addRenderableWidget(Button.builder(Component.literal("+"),
               b -> bumpWeight(row, true))
            .bounds(cardX + CARD_W - 6 - 16, btnY, 16, 18).build());
      }

      // Save button (Esc / E still commit via removed; this gives an
      // explicit click with chat-feedback path).
      int saveY = this.topPos + this.imageHeight - 22;
      addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
         if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
         }
      }).bounds(this.leftPos + this.imageWidth - 56, saveY, 48, 18).build());
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

      // Panel frame.
      g.fill(l, t, r, b, PANEL_BG);
      g.fill(l - 1, t - 1, r + 1, t, PANEL_BORDER);
      g.fill(l - 1, b, r + 1, b + 1, PANEL_BORDER);
      g.fill(l - 1, t - 1, l, b + 1, PANEL_BORDER);
      g.fill(r, t - 1, r + 1, b + 1, PANEL_BORDER);

      // Card backgrounds + borders. Each card holds:
      //   slot at (cardX + 4, cardY + 4) — drawn by vanilla via the menu
      //   adornments drawn in renderLabels
      //   − / + buttons (added via init())
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         int col = i % GRID_COLS;
         int rowIdx = i / GRID_COLS;
         int cardX = l + GRID_ORIGIN_X + col * (CARD_W + CARD_GAP);
         int cardY = t + GRID_ORIGIN_Y + rowIdx * (CARD_H + CARD_GAP);
         g.fill(cardX, cardY, cardX + CARD_W, cardY + CARD_H, CARD_BG);
         g.fill(cardX, cardY, cardX + CARD_W, cardY + 1, CARD_BORDER);
         g.fill(cardX, cardY + CARD_H - 1, cardX + CARD_W, cardY + CARD_H, CARD_BORDER);
         g.fill(cardX, cardY, cardX + 1, cardY + CARD_H, CARD_BORDER);
         g.fill(cardX + CARD_W - 1, cardY, cardX + CARD_W, cardY + CARD_H, CARD_BORDER);
         // Slot well — a darker recess behind the 16×16 slot.
         g.fill(cardX + 3, cardY + 3, cardX + 21, cardY + 21, 0x60000000);
      }

      // Player inventory + hotbar slot backdrops.
      for (int r0 = 0; r0 < 3; r0++) {
         for (int c0 = 0; c0 < 9; c0++) {
            int sx = l + INV_ORIGIN_X + c0 * 18;
            int sy = t + INV_ORIGIN_Y + r0 * 18;
            g.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
         }
      }
      for (int c0 = 0; c0 < 9; c0++) {
         int sx = l + INV_ORIGIN_X + c0 * 18;
         int sy = t + INV_ORIGIN_Y + 58;
         g.fill(sx, sy, sx + 16, sy + 16, 0x40000000);
      }
   }

   @Override
   protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
      // Title + total-weight callout.
      g.drawString(this.font,
         Component.literal("Plant plan").withStyle(ChatFormatting.GOLD),
         8, this.titleLabelY, FG_ACCENT, true);
      int totalW = computeTotalWeight();
      String hdrR = totalW > 0 ? (totalW + " total weight") : "drag a seed onto a card";
      int hw = this.font.width(hdrR);
      g.drawString(this.font, hdrR, this.imageWidth - 8 - hw, this.titleLabelY, FG_DIM, true);

      // Per-card adornments: name (right of slot), weight (centered above
      // − / + buttons), empty-state hint when no seed is set.
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         int col = i % GRID_COLS;
         int rowIdx = i / GRID_COLS;
         int cardX = GRID_ORIGIN_X + col * (CARD_W + CARD_GAP);
         int cardY = GRID_ORIGIN_Y + rowIdx * (CARD_H + CARD_GAP);
         ItemStack stack = this.menu.ghostContainer().getItem(i);

         if (stack.isEmpty()) {
            // No seed → just a faint hint inside the card.
            g.drawString(this.font, "(drag a seed)",
               cardX + 28, cardY + 8, FG_FAINT, true);
         } else {
            // Name to the right of the slot, truncated if long.
            String name = shortItemName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            int maxW = CARD_W - 28 - 6;
            String shown = name;
            if (this.font.width(shown) > maxW) {
               shown = this.font.plainSubstrByWidth(shown, maxW - 4) + "…";
            }
            g.drawString(this.font, shown, cardX + 28, cardY + 8, FG_PRIMARY, true);

            // Brief weight + percent line under the name.
            int w = this.menu.weight(i);
            int pct = totalW > 0 && w > 0 ? Math.round(w * 100f / totalW) : 0;
            String sub = w > 0 ? ("weight " + w + "  (" + pct + "%)") : "weight 0";
            g.drawString(this.font, sub, cardX + 28, cardY + 20,
               w > 0 ? FG_DIM : FG_FAINT, true);
         }

         // Big "×N" centered between the buttons at the bottom strip.
         int w = this.menu.weight(i);
         String wText = "×" + w;
         int wTextW = this.font.width(wText);
         int btnY = cardY + CARD_H - 22;
         g.drawString(this.font, wText,
            cardX + (CARD_W - wTextW) / 2, btnY + 5,
            stack.isEmpty() ? FG_FAINT : FG_ACCENT, true);
      }

      // Inventory label.
      g.drawString(this.font,
         Component.literal("Your inventory").withStyle(ChatFormatting.GRAY),
         8, this.inventoryLabelY, FG_DIM, true);

      // Preview line in the footer band, left of the Save button.
      int previewY = this.imageHeight - 16;
      String preview = buildPreview();
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
