package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.SetStorageLabelPayload;
import com.yucareux.townfolk.town.StorageFilterMode;
import com.yucareux.townfolk.world.inventory.StorageConfigMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

   private Button toggleModeBtn;
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
      int hdr = StorageConfigMenu.HEADER_BAND_PX;

      // Optional label EditBox in the header band. Width spans the
      // panel minus the side padding; height = 14 fits the band.
      this.labelBox = new EditBox(this.font,
         this.leftPos + 8, this.topPos + 18,
         this.imageWidth - 16, 14,
         Component.literal("barrel label"));
      this.labelBox.setMaxLength(64);
      this.labelBox.setHint(Component.literal("optional — name this barrel"));
      this.labelBox.setBordered(true);
      // Pre-fill from the existing config if one is registered.
      String existingLabel = currentLabel();
      this.labelBox.setValue(existingLabel);
      this.lastSentLabel = existingLabel;
      this.addRenderableWidget(this.labelBox);

      // Toggle button sits in the gap between filter region and
      // inventory label, shifted down by the header band.
      this.toggleModeBtn = Button.builder(
            Component.literal(modeLabel()),
            b -> onToggleMode())
         .bounds(this.leftPos + 96, this.topPos + 62 + hdr, 72, 14)
         .build();
      this.addRenderableWidget(this.toggleModeBtn);
   }

   /** Current label, pre-filled from the {@link StorageConfigMenu#initialLabel()}
    *  seeded server-side when the menu opened. Falls back to "" if the
    *  container has no existing config. */
   private String currentLabel() {
      return this.menu.initialLabel();
   }

   private String modeLabel() {
      return this.menu.mode() == StorageFilterMode.WHITELIST
         ? "✓ Whitelist" : "✗ Blacklist";
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
      // Title — single line. The old subtitle ("Whitelist · 6/16 items")
      // is gone because the label EditBox sits in the same band; that
      // info is conveyed via the toggle button text + the filled slot
      // count rendered to the right of the title.
      g.drawString(this.font,
         Component.literal("Configure storage").withStyle(ChatFormatting.GOLD),
         8, this.titleLabelY, FG_ACCENT, true);
      int filled = countFilledFilterSlots();
      String count = filled + "/" + com.yucareux.townfolk.town.StorageConfig.FILTER_SLOTS + " filters";
      int countW = this.font.width(count);
      g.drawString(this.font, count,
         this.imageWidth - 8 - countW, this.titleLabelY, FG_DIM, true);

      // Inventory label.
      g.drawString(this.font,
         Component.literal("Your inventory").withStyle(ChatFormatting.GRAY),
         8, this.inventoryLabelY, FG_DIM, true);
   }

   @Override
   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
      // Refresh the toggle button label every frame so the DataSlot
      // sync that flips menu.mode() server→client is reflected
      // immediately. Otherwise the button text stays stale until the
      // player clicks it again.
      if (this.toggleModeBtn != null) {
         this.toggleModeBtn.setMessage(Component.literal(modeLabel()));
      }

      super.render(g, mouseX, mouseY, partialTicks);
      this.renderTooltip(g, mouseX, mouseY);
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
