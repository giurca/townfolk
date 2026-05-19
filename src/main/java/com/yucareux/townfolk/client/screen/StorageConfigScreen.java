package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.SetStorageLabelPayload;
import com.yucareux.townfolk.town.StorageFilterMode;
import com.yucareux.townfolk.world.inventory.StorageConfigMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side popup for configuring a registered storage container.
 * Standard {@link AbstractContainerScreen} so vanilla handles cursor
 * follow, slot rendering, drag/drop polish — we only add the title bar,
 * the mode-toggle button, and a subtle category-count badge.
 *
 *   ┌────────────────────────────────┐
 *   │  Configure storage             │
 *   │  Whitelist · 6/16 items        │
 *   │  ┌──┬──┬──┬──┬──┬──┬──┬──┐     │
 *   │  │  │  │  │  │  │  │  │  │     │  ← 16 filter ghost slots
 *   │  │  │  │  │  │  │  │  │  │     │     2 rows × 8 cols
 *   │  └──┴──┴──┴──┴──┴──┴──┴──┘     │
 *   │  [ Whitelist  ] [ Blacklist ]  │
 *   │  ─── Player inventory ───      │
 *   │  ▒▒▒▒▒▒▒▒▒                     │
 *   │  ▒▒▒▒▒▒▒▒▒                     │
 *   │  ▒▒▒▒▒▒▒▒▒                     │
 *   │  ▒▒▒▒▒▒▒▒▒  (hotbar)           │
 *   └────────────────────────────────┘
 *
 * Drag from player inventory → filter slot to add an entry. Click an
 * occupied filter slot with empty cursor to clear it. Shift-click also
 * clears. Closing the screen commits the configuration.
 */
public final class StorageConfigScreen extends AbstractContainerScreen<StorageConfigMenu> {

   private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(
      "minecraft", "textures/gui/container/inventory.png");

   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int FG_PRIMARY   = 0xFFEDE0C2;
   private static final int FG_ACCENT    = 0xFFFFD27A;
   private static final int FG_DIM       = 0xFFB89B70;

   private EditBox labelBox;
   /** Last label value we sent to the server — used to coalesce edits so
    *  we don't fire a packet per keystroke. */
   private String lastSentLabel = "";
   /** Frame countdown until next label commit (debounce). */
   private int labelDirtyTicks = -1;

   public StorageConfigScreen(StorageConfigMenu menu, Inventory inv, Component title) {
      super(menu, inv, title);
      // Vanilla AbstractContainerScreen sets imageWidth/Height in init —
      // we override here so it matches our slot positions (set in the
      // menu). HEADER_BAND_PX adds room at the top for the label
      // EditBox; the menu shifts all slot Y coords by the same amount.
      this.imageWidth = 176;
      this.imageHeight = 166 + StorageConfigMenu.HEADER_BAND_PX;
      // Title pushed up into the header band.
      this.titleLabelY = 6;
      this.inventoryLabelY = 73 + StorageConfigMenu.HEADER_BAND_PX;
   }

   @Override
   protected void init() {
      super.init();

      // Optional label EditBox in the header band. Narrower than before
      // so the custom-drawn mode chip fits to its right on the same row.
      this.labelBox = new EditBox(this.font,
         this.leftPos + 8, this.topPos + 18,
         this.imageWidth - 16 - MODE_CHIP_W - 6, 14,
         Component.literal("barrel label"));
      this.labelBox.setMaxLength(64);
      this.labelBox.setHint(Component.literal("optional — name this barrel"));
      this.labelBox.setBordered(true);
      // Pre-fill from the existing config if one is registered.
      String existingLabel = currentLabel();
      this.labelBox.setValue(existingLabel);
      this.lastSentLabel = existingLabel;
      this.addRenderableWidget(this.labelBox);
      // No vanilla toggle Button widget — the mode toggle is now a
      // custom-drawn chip (see renderModeChip + mouseClicked), matching
      // the Trade-popup gold-standard button language.
   }

   /** Mode-toggle chip geometry. Drawn instead of using a vanilla
    *  Button widget so the visual language matches the Trade-popup
    *  modals elsewhere in the admin UI. */
   private static final int MODE_CHIP_W = 84;
   private static final int MODE_CHIP_H = 14;

   /** Current label, pre-filled from the {@link StorageConfigMenu#initialLabel()}
    *  seeded server-side when the menu opened. Falls back to "" if the
    *  container has no existing config. */
   private String currentLabel() {
      return this.menu.initialLabel();
   }

   private void onToggleMode() {
      if (this.minecraft != null) {
         this.minecraft.gameMode.handleInventoryButtonClick(
            this.menu.containerId, StorageConfigMenu.BUTTON_TOGGLE_MODE);
      }
      // No optimistic local update — render() refreshes the button
      // label every frame from menu.mode(), which reads the synced
      // DataSlot. The server's reply arrives within a tick or two.
   }

   @Override
   protected void renderBg(GuiGraphics g, float partialTicks, int mouseX, int mouseY) {
      // Dark panel background — replaces the vanilla inventory texture.
      int l = this.leftPos, t = this.topPos;
      int r = l + this.imageWidth, b = t + this.imageHeight;
      g.fill(l, t, r, b, PANEL_BG);
      g.fill(l - 1, t - 1, r + 1, t, PANEL_BORDER);
      g.fill(l - 1, b, r + 1, b + 1, PANEL_BORDER);
      g.fill(l - 1, t - 1, l, b + 1, PANEL_BORDER);
      g.fill(r, t - 1, r + 1, b + 1, PANEL_BORDER);

      // Slot backdrops (16 filter + 27 inv + 9 hotbar). Vanilla slot
      // rendering layers items on top, but we draw a subtle background
      // tint so they're visible against the panel.
      drawSlotGrid(g, l + 25, t + 23, 8, 2, 18, 0x40000000);
      drawSlotGrid(g, l + 7,  t + 83, 9, 3, 18, 0x40000000);
      drawSlotGrid(g, l + 7,  t + 141, 9, 1, 18, 0x40000000);
   }

   private void drawSlotGrid(GuiGraphics g, int x0, int y0, int cols, int rows, int spacing, int color) {
      for (int row = 0; row < rows; row++) {
         for (int col = 0; col < cols; col++) {
            int x = x0 + col * spacing;
            int y = y0 + row * spacing;
            g.fill(x, y, x + 16, y + 16, color);
         }
      }
   }

   @Override
   protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
      // Title — left side of the header row. Filter-count is now a
      // subtitle under the title (cleaner than competing with the
      // toggle chip for the right edge of the header).
      g.drawString(this.font,
         Component.literal("Configure storage").withStyle(ChatFormatting.GOLD),
         8, this.titleLabelY, FG_ACCENT, true);

      // Inventory label.
      g.drawString(this.font,
         Component.literal("Your inventory").withStyle(ChatFormatting.GRAY),
         8, this.inventoryLabelY, FG_DIM, true);
   }

   @Override
   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
      super.render(g, mouseX, mouseY, partialTicks);

      // Mode chip — drawn AFTER super.render so it sits on top of the
      // panel's slot grid background, but BEFORE the tooltip so hover
      // tooltips on inventory slots still work as expected.
      drawModeChip(g, mouseX, mouseY);

      this.renderTooltip(g, mouseX, mouseY);
   }

   /** Render the custom mode-toggle chip. Geometry matches
    *  {@link #modeChipBounds()}; visual language matches the Trade-
    *  popup buttons (green = primary action, neutral = secondary). */
   private void drawModeChip(GuiGraphics g, int mouseX, int mouseY) {
      int[] b = modeChipBounds();
      int x = b[0], y = b[1], w = b[2], h = b[3];
      boolean isWhite = this.menu.mode() == StorageFilterMode.WHITELIST;
      boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
      int bg     = isWhite ? 0xFF3C5A22 : 0xFF6B2A2A;     // green / red
      int border = isWhite ? 0xFF6FA445 : 0xFFB85C5C;
      int fg     = isWhite ? 0xFFE8FFD2 : 0xFFFFD8D8;
      if (hovered) bg = brighten(bg);
      g.fill(x, y, x + w, y + h, bg);
      g.fill(x, y + h, x + w, y + h + 1, border);
      String label = isWhite ? "Whitelist mode" : "Blacklist mode";
      int lw = this.font.width(label);
      g.drawString(this.font, label, x + (w - lw) / 2, y + 3, fg, true);

      // Filter count — small line under the chip, right-aligned so it
      // mirrors the chip's edge and stays out of the EditBox row.
      int filled = countFilledFilterSlots();
      String count = filled + "/"
         + com.yucareux.townfolk.town.StorageConfig.FILTER_SLOTS + " filters";
      int cw = this.font.width(count);
      g.drawString(this.font, count, x + w - cw, y + h + 4, FG_DIM, true);
   }

   /** Bounds of the mode-toggle chip in screen-space coords (post
    *  {@code leftPos / topPos} application). Single source of truth so
    *  draw + hit-test agree. */
   private int[] modeChipBounds() {
      int x = this.leftPos + this.imageWidth - MODE_CHIP_W - 8;
      int y = this.topPos + 18;
      return new int[]{x, y, MODE_CHIP_W, MODE_CHIP_H};
   }

   private static int brighten(int argb) {
      int a = (argb >>> 24) & 0xFF;
      int r = Math.min(255, ((argb >>> 16) & 0xFF) + 24);
      int gC= Math.min(255, ((argb >>> 8) & 0xFF) + 24);
      int b = Math.min(255, (argb & 0xFF) + 24);
      return (a << 24) | (r << 16) | (gC << 8) | b;
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      // Mode chip hit-test BEFORE super so the slot under it doesn't
      // eat the click. The chip never overlaps real slots (it lives in
      // the header band above the filter grid), but defence in depth.
      int[] b = modeChipBounds();
      if (button == 0
          && mouseX >= b[0] && mouseX < b[0] + b[2]
          && mouseY >= b[1] && mouseY < b[1] + b[3]) {
         onToggleMode();
         return true;
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   /** True 20Hz cadence (AbstractContainerScreen routes game ticks here)
    *  — render() was framerate-dependent and made the debounce window
    *  vary from 50ms at 240fps to 400ms at 30fps. */
   @Override
   protected void containerTick() {
      super.containerTick();
      // Debounced label commit. 12 ticks ≈ 0.6s of idle typing.
      if (this.labelBox != null) {
         String cur = this.labelBox.getValue();
         if (!cur.equals(this.lastSentLabel)) {
            this.labelDirtyTicks = 12;
            this.lastSentLabel = cur;
         } else if (this.labelDirtyTicks > 0) {
            this.labelDirtyTicks--;
         } else if (this.labelDirtyTicks == 0) {
            PacketDistributor.sendToServer(new SetStorageLabelPayload(cur));
            this.labelDirtyTicks = -1;
         }
      }
   }

   @Override
   public void removed() {
      // Force-commit any pending edits before the screen closes so the
      // user doesn't lose the rename if they tab out without waiting
      // for the debounce.
      if (this.labelBox != null && !this.lastSentLabel.equals(this.labelBox.getValue())) {
         PacketDistributor.sendToServer(new SetStorageLabelPayload(this.labelBox.getValue()));
      } else if (this.labelDirtyTicks >= 0 && this.labelBox != null) {
         PacketDistributor.sendToServer(new SetStorageLabelPayload(this.labelBox.getValue()));
      }
      super.removed();
   }

   private int countFilledFilterSlots() {
      int n = 0;
      var inv = this.menu.filterContainer();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (!inv.getItem(i).isEmpty()) n++;
      }
      return n;
   }
}
