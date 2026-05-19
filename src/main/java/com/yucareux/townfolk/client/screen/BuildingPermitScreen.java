package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.ApplyBuildingPermitPayload;
import com.yucareux.townfolk.network.OpenBuildingPermitPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Building Permit modal — per-building override editor for the
 * volume / height caps. Opened by the server in response to a
 * right-click on a building marker block while holding a permit.
 *
 * <p>Same visual language as {@link AnimalPlanScreen}: dark dim
 * backdrop (no vanilla blur), single panel, EditBox inputs, custom
 * chip buttons.
 *
 * <pre>
 *  ┌────────────────────────────────────────────────────────┐
 *  │  Building Permit                    Home               │
 *  │                                                        │
 *  │  Current measurements                                  │
 *  │    Volume: 187 / 1024 air blocks                       │
 *  │    Height: 4 / 12 blocks tall                          │
 *  │                                                        │
 *  │  Override limits  (blank = use template default)       │
 *  │                                                        │
 *  │    Max volume   [ 1024 ]      (default 1024)           │
 *  │    Max height   [   12 ]      (default 12)             │
 *  │                                                        │
 *  │  Status: Valid                                         │
 *  │                                                        │
 *  │  Permit consumed on Apply.                             │
 *  │                                                        │
 *  │                          [ Cancel ]  [ Apply (-1 ▒) ]  │
 *  └────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class BuildingPermitScreen extends Screen {

   private static final int PANEL_W = 480;
   private static final int PANEL_H = 270;

   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int CHIP_BG          = 0xFF2A2018;
   private static final int CHIP_BG_HOVER    = 0xFF38281A;
   private static final int CHIP_BG_PRIMARY  = 0xFF3C5A22;
   private static final int CHIP_BG_PRIMARY_HOVER = 0xFF4A6C2A;
   private static final int CHIP_BORDER  = 0xFF8C6E3D;
   private static final int FG_PRIMARY   = 0xFFEDE0C2;
   private static final int FG_ACCENT    = 0xFFFFD27A;
   private static final int FG_DIM       = 0xFFB89B70;
   private static final int FG_FAINT     = 0xFF7A6849;
   private static final int FG_OK        = 0xFF8FC97A;
   private static final int FG_BAD       = 0xFFE08C72;
   private static final int FG_ON_PRIMARY = 0xFFE8FFD2;

   private static final int CHIP_W = 110;
   private static final int CHIP_H = 22;

   /** Hard caps on permit overrides — matches the design budget from
    *  the conversation: 8× the default volume, 4× the default height.
    *  Beyond this is silly. */
   private static final int OVERRIDE_VOLUME_MAX = 8192;
   private static final int OVERRIDE_HEIGHT_MAX = 48;

   private final OpenBuildingPermitPayload payload;
   private int panelLeft, panelTop;
   private EditBox volumeBox;
   private EditBox heightBox;

   public BuildingPermitScreen(OpenBuildingPermitPayload payload) {
      super(Component.literal("Building Permit"));
      this.payload = payload;
   }

   @Override
   protected void init() {
      this.panelLeft = (this.width  - PANEL_W) / 2;
      this.panelTop  = (this.height - PANEL_H) / 2;

      int boxW = 64, boxH = 18;
      int boxX = this.panelLeft + 180;

      this.volumeBox = new EditBox(this.font, boxX,
         this.panelTop + 116, boxW, boxH,
         Component.literal("max volume"));
      this.volumeBox.setMaxLength(5);
      this.volumeBox.setBordered(true);
      // Pre-fill with the override if set, else the template default.
      this.volumeBox.setValue(this.payload.overrideMaxVolume() > 0
         ? String.valueOf(this.payload.overrideMaxVolume())
         : String.valueOf(this.payload.templateMaxVolume()));
      this.volumeBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
      addRenderableWidget(this.volumeBox);

      this.heightBox = new EditBox(this.font, boxX,
         this.panelTop + 144, boxW, boxH,
         Component.literal("max height"));
      this.heightBox.setMaxLength(3);
      this.heightBox.setBordered(true);
      this.heightBox.setValue(this.payload.overrideMaxHeight() > 0
         ? String.valueOf(this.payload.overrideMaxHeight())
         : String.valueOf(this.payload.templateMaxHeight()));
      this.heightBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
      addRenderableWidget(this.heightBox);
   }

   @Override
   public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      // Uniform dim, no blur. Matches AnimalPlanScreen.
      g.fill(0, 0, this.width, this.height, 0xB0000000);
   }

   @Override
   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(g, mouseX, mouseY, partialTick);

      int l = this.panelLeft, t = this.panelTop;
      int r = l + PANEL_W, b = t + PANEL_H;
      g.fill(l, t, r, b, PANEL_BG);
      drawBorderBox(g, l, t, r, b, PANEL_BORDER);

      // Header — title + template name right-aligned.
      g.drawString(this.font,
         Component.literal("Building Permit").withStyle(ChatFormatting.GOLD),
         l + 14, t + 12, FG_ACCENT, true);
      String name = prettyName(this.payload.templateId());
      int nameW = this.font.width(name);
      g.drawString(this.font, name, r - 14 - nameW, t + 12, FG_DIM, true);

      // Current measurements block.
      int y = t + 38;
      g.drawString(this.font, "Current measurements",
         l + 14, y, FG_DIM, true);
      y += 12;
      String volLine = "Volume: " + this.payload.measuredVolume()
                     + " / " + this.payload.effectiveMaxVolume() + " air blocks";
      g.drawString(this.font, volLine, l + 20, y, FG_PRIMARY, true);
      y += 10;
      String hgtLine = "Height: " + this.payload.measuredHeight()
                     + " / " + this.payload.effectiveMaxHeight() + " blocks tall";
      g.drawString(this.font, hgtLine, l + 20, y, FG_PRIMARY, true);

      // Override section.
      y = t + 96;
      g.drawString(this.font,
         "Override limits  (clear to use template default)",
         l + 14, y, FG_DIM, true);

      g.drawString(this.font, "Max volume",
         l + 100, t + 122, FG_PRIMARY, true);
      g.drawString(this.font, "default " + this.payload.templateMaxVolume(),
         l + 252, t + 122, FG_FAINT, true);

      g.drawString(this.font, "Max height",
         l + 100, t + 150, FG_PRIMARY, true);
      g.drawString(this.font, "default " + this.payload.templateMaxHeight(),
         l + 252, t + 150, FG_FAINT, true);

      // Status indicator.
      y = t + 180;
      String statusLabel = this.payload.recognitionValid() ? "Status: Valid" : "Status: Invalid";
      int statusColor = this.payload.recognitionValid() ? FG_OK : FG_BAD;
      g.drawString(this.font, statusLabel, l + 14, y, statusColor, true);
      if (!this.payload.recognitionValid() && !this.payload.failureReason().isEmpty()) {
         y += 10;
         g.drawString(this.font, "  " + this.payload.failureReason(),
            l + 14, y, FG_DIM, true);
      }

      // Footer hint.
      g.drawString(this.font,
         "One permit is consumed on Apply. Cancel returns it untouched.",
         l + 14, t + PANEL_H - 50, FG_FAINT, true);

      // Custom Cancel + Apply chips at bottom-right.
      int chipY = t + PANEL_H - CHIP_H - 10;
      int applyX = r - 14 - CHIP_W;
      int cancelX = applyX - 8 - CHIP_W;
      renderChip(g, cancelX, chipY, "Cancel", false, mouseX, mouseY);
      renderChip(g, applyX,   chipY, "Apply  −1 ▒", true,  mouseX, mouseY);

      // EditBoxes paint via super.render.
      super.render(g, mouseX, mouseY, partialTick);
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int t = this.panelTop, b = t + PANEL_H;
      int r = this.panelLeft + PANEL_W;
      int chipY = t + PANEL_H - CHIP_H - 10;
      int applyX = r - 14 - CHIP_W;
      int cancelX = applyX - 8 - CHIP_W;

      if (mouseY >= chipY && mouseY < chipY + CHIP_H) {
         if (mouseX >= cancelX && mouseX < cancelX + CHIP_W) {
            this.onClose();
            return true;
         }
         if (mouseX >= applyX && mouseX < applyX + CHIP_W) {
            applyAndClose();
            return true;
         }
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   private void applyAndClose() {
      int vol = parseClamped(this.volumeBox.getValue(),
         this.payload.templateMaxVolume(), OVERRIDE_VOLUME_MAX);
      int hgt = parseClamped(this.heightBox.getValue(),
         this.payload.templateMaxHeight(), OVERRIDE_HEIGHT_MAX);
      // If the player set the input EQUAL to the template default,
      // store -1 (= "clear override") so the entry doesn't carry a
      // redundant override forever.
      if (vol == this.payload.templateMaxVolume()) vol = -1;
      if (hgt == this.payload.templateMaxHeight()) hgt = -1;
      PacketDistributor.sendToServer(
         new ApplyBuildingPermitPayload(this.payload.markerPos(), vol, hgt));
      this.onClose();
   }

   /** Parse a digit-string into a clamped int. Empty / unparseable
    *  falls back to {@code fallback}. Clamped to [1, hardMax]. */
   private static int parseClamped(String s, int fallback, int hardMax) {
      if (s == null || s.isEmpty()) return fallback;
      int v;
      try { v = Integer.parseInt(s); }
      catch (NumberFormatException e) { return fallback; }
      if (v < 1) return 1;
      if (v > hardMax) return hardMax;
      return v;
   }

   private void renderChip(GuiGraphics g, int x, int y, String label, boolean primary,
                            int mouseX, int mouseY) {
      boolean hovered = mouseX >= x && mouseX < x + CHIP_W
                     && mouseY >= y && mouseY < y + CHIP_H;
      int bg = primary
         ? (hovered ? CHIP_BG_PRIMARY_HOVER : CHIP_BG_PRIMARY)
         : (hovered ? CHIP_BG_HOVER : CHIP_BG);
      g.fill(x, y, x + CHIP_W, y + CHIP_H, bg);
      drawBorderBox(g, x, y, x + CHIP_W, y + CHIP_H, CHIP_BORDER);
      int lw = this.font.width(label);
      g.drawString(this.font, label,
         x + (CHIP_W - lw) / 2, y + (CHIP_H - 8) / 2,
         primary ? FG_ON_PRIMARY : FG_PRIMARY, true);
   }

   private static void drawBorderBox(GuiGraphics g, int l, int t, int r, int b, int color) {
      g.fill(l, t, r, t + 1, color);
      g.fill(l, b - 1, r, b, color);
      g.fill(l, t, l + 1, b, color);
      g.fill(r - 1, t, r, b, color);
   }

   private static String prettyName(String id) {
      StringBuilder out = new StringBuilder(id.length());
      boolean cap = true;
      for (char c : id.toCharArray()) {
         if (c == '_') { out.append(' '); cap = true; }
         else if (cap) { out.append(Character.toUpperCase(c)); cap = false; }
         else out.append(c);
      }
      return out.toString();
   }

   @Override public boolean isPauseScreen() { return false; }

   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      // Escape unfocuses an EditBox if one is focused; otherwise closes.
      if (keyCode == 256 && this.getFocused() == null) {
         this.onClose();
         return true;
      }
      return super.keyPressed(keyCode, scanCode, modifiers);
   }
}
