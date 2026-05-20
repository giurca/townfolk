package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Parcels tab renderer — grid of land-claim tiles + parcel-detail
 * popup. Extracted from {@code TownAdminScreen} in stage 15b.4.d.
 *
 * <p>State (scroll offset + selected parcel id) stays on the host
 * screen since click + scroll handlers mutate it. Per-type icon
 * caches are static here.
 */
final class ParcelsRenderer {

   static final int PARCEL_CELL_W = 96;
   static final int PARCEL_CELL_H = 72;
   static final int PARCEL_CELL_GAP = 8;
   static final int PARCEL_GRID_TOP = 26;

   /** Stable per-type icons. Lazily built — no Items.* lookups per frame. */
   private static ItemStack PLANT_ICON;
   private static ItemStack ANIMAL_ICON;

   private ParcelsRenderer() {}

   static ItemStack plantIcon() {
      if (PLANT_ICON == null) PLANT_ICON = new ItemStack(Items.WHEAT);
      return PLANT_ICON;
   }
   static ItemStack animalIcon() {
      if (ANIMAL_ICON == null) ANIMAL_ICON = new ItemStack(Items.COW_SPAWN_EGG);
      return ANIMAL_ICON;
   }

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom,
                      int mouseX, int mouseY) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int contentTop = top + 10;

      UiText.heading(graphics, font,
         "Owned land: " + screen.state.parcels().size() + " parcels",
         innerL, contentTop);
      int plant = 0, animal = 0;
      for (var p : screen.state.parcels()) {
         if ("PLANT".equals(p.type())) plant++; else if ("ANIMAL".equals(p.type())) animal++;
      }
      UiText.rightFaint(graphics, font,
         plant + " plant · " + animal + " animal",
         innerR, contentTop);

      int gridTop = top + PARCEL_GRID_TOP;
      int gridBot = bottom - 6;
      var parcels = screen.state.parcels();
      if (parcels.isEmpty()) {
         UiText.faint(graphics, font,
            "no parcels yet — give a villager a Surveyor's Stake and mark some land",
            innerL, gridTop + 10);
         return;
      }

      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + PARCEL_CELL_GAP) / (PARCEL_CELL_W + PARCEL_CELL_GAP));
      int gridUsed = cols * PARCEL_CELL_W + (cols - 1) * PARCEL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      int totalRows = (parcels.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + PARCEL_CELL_GAP) / (PARCEL_CELL_H + PARCEL_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (screen.parcelsGridScrollRows > maxScrollRow) screen.parcelsGridScrollRows = maxScrollRow;
      int scrollPx = screen.parcelsGridScrollRows * (PARCEL_CELL_H + PARCEL_CELL_GAP);

      TownAdminScreen.scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      int i = 0;
      for (var p : parcels) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (PARCEL_CELL_W + PARCEL_CELL_GAP);
         int cy = gridTop + row * (PARCEL_CELL_H + PARCEL_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + PARCEL_CELL_H < gridTop) { i++; continue; }
         renderCell(screen, graphics, p, cx, cy, mouseX, mouseY);
         i++;
      }
      graphics.disableScissor();

      if (screen.selectedParcelId != null) {
         renderPopup(screen, graphics, paneL, paneR, top, bottom);
      }
   }

   private static void renderCell(TownAdminScreen screen, GuiGraphics g,
                                   TownStateUpdatePayload.ParcelSummary p,
                                   int x, int y, int mouseX, int mouseY) {
      Font font = screen.font();
      boolean hovered = mouseX >= x && mouseX < x + PARCEL_CELL_W
                     && mouseY >= y && mouseY < y + PARCEL_CELL_H;
      boolean selected = p.id().equals(screen.selectedParcelId);
      int bg = (hovered || selected) ? UiTheme.TAB_ACTIVE : UiTheme.ROW_BG;
      g.fill(x, y, x + PARCEL_CELL_W, y + PARCEL_CELL_H, bg);
      g.fill(x, y + PARCEL_CELL_H, x + PARCEL_CELL_W, y + PARCEL_CELL_H + 1, UiTheme.PANEL_BORDER);

      boolean isPlant = "PLANT".equals(p.type());
      String typeTag = isPlant ? "PLANT" : "ANIMAL";
      int typeColor = isPlant ? 0xFF6FA445 : 0xFFD2966B;
      g.drawString(font, typeTag, x + 4, y + 3, typeColor, true);

      String size = p.sizeX() + "×" + p.sizeZ();
      int sw = font.width(size);
      g.drawString(font, size, x + PARCEL_CELL_W - sw - 4, y + 3, UiTheme.MUTED, true);

      ItemStack icon = isPlant ? plantIcon() : animalIcon();
      g.renderItem(icon, x + (PARCEL_CELL_W - 16) / 2, y + 14);

      String ownerClip = UiText.truncate(font, p.ownerName(), PARCEL_CELL_W - 6);
      int ow = font.width(ownerClip);
      g.drawString(font, ownerClip,
         x + (PARCEL_CELL_W - ow) / 2, y + 34, UiTheme.BODY, true);

      String snapshot = TownAdminScreen.parcelSnapshotText(p);
      String snapClip = UiText.truncate(font, snapshot, PARCEL_CELL_W - 6);
      int snw = font.width(snapClip);
      g.drawString(font, snapClip,
         x + (PARCEL_CELL_W - snw) / 2, y + 46, UiTheme.FAINT, true);

      net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
      String pos = centre.getX() + " · " + centre.getY() + " · " + centre.getZ();
      String posClip = UiText.truncate(font, pos, PARCEL_CELL_W - 6);
      int pw = font.width(posClip);
      g.drawString(font, posClip,
         x + (PARCEL_CELL_W - pw) / 2, y + 58, UiTheme.FAINT, true);
   }

   /** Parcel detail popup — Trade-modal style. */
   private static void renderPopup(TownAdminScreen screen, GuiGraphics g,
                                    int paneL, int paneR, int top, int bottom) {
      var p = screen.findParcel(screen.selectedParcelId);
      if (p == null) { screen.selectedParcelId = null; return; }
      Font font = screen.font();

      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
      int popH = 170;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;

      g.fill(paneL, top, paneR, bottom, 0xC0000000);
      g.fill(popX, popY, popX + popW, popY + popH, UiTheme.PANEL_BG);
      g.fill(popX - 1, popY - 1, popX + popW + 1, popY, UiTheme.PANEL_BORDER);
      g.fill(popX - 1, popY + popH, popX + popW + 1, popY + popH + 1, UiTheme.PANEL_BORDER);
      g.fill(popX - 1, popY, popX, popY + popH, UiTheme.PANEL_BORDER);
      g.fill(popX + popW, popY, popX + popW + 1, popY + popH, UiTheme.PANEL_BORDER);

      int x = popX + 14;
      int y = popY + 12;

      UiText.heading(g, font,
         p.ownerName() + "'s " + p.type().toLowerCase(Locale.ROOT)
            + " parcel — " + p.sizeX() + "×" + p.sizeZ(),
         x, y);
      y += 14;

      ItemStack icon = "PLANT".equals(p.type()) ? plantIcon() : animalIcon();
      g.renderItem(icon, x, y);
      g.drawString(font, TownAdminScreen.parcelSnapshotText(p),
         x + 22, y + 4, UiTheme.BODY, true);
      y += 24;

      net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
      g.drawString(font, "Centre: " + centre.toShortString(), x, y, UiTheme.HEADING, true);
      y += 12;

      g.drawString(font, "Marked on day " + p.createdDay(), x, y, UiTheme.MUTED, true);
      y += 14;

      String hint = "PLANT".equals(p.type())
         ? "Open plan to edit the crop schedule."
         : "Open plan to edit breed/harvest targets.";
      g.drawString(font, hint, x, y, UiTheme.FAINT, true);

      int btnY = popY + popH - 24;
      TradeRenderer.drawButton(g, font, popX + 14, btnY, 110, "Open plan", true);
      TradeRenderer.drawButton(g, font, popX + popW - 14 - 80, btnY, 80, "Close", false);
   }

   /** Hit-test the popup's buttons. Returns "open", "close", or null. */
   static String hitTestPopupButton(double mouseX, double mouseY,
                                     int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
      int popH = 170;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;
      int btnY = popY + popH - 24;
      if (mouseY < btnY || mouseY >= btnY + 18) return null;
      int openX = popX + 14;
      if (mouseX >= openX && mouseX < openX + 110) return "open";
      int closeX = popX + popW - 14 - 80;
      if (mouseX >= closeX && mouseX < closeX + 80) return "close";
      return null;
   }

   /** Grid hit-test. Returns the parcel under the cursor or null. */
   static TownStateUpdatePayload.ParcelSummary parcelAtPoint(TownAdminScreen screen,
                                                              double mouseX, double mouseY,
                                                              int paneL, int paneR,
                                                              int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + PARCEL_GRID_TOP;
      int gridBot = bottom - 6;
      if (mouseX < innerL || mouseX > innerR || mouseY < gridTop || mouseY > gridBot) return null;

      var parcels = screen.state.parcels();
      if (parcels.isEmpty()) return null;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + PARCEL_CELL_GAP) / (PARCEL_CELL_W + PARCEL_CELL_GAP));
      int gridUsed = cols * PARCEL_CELL_W + (cols - 1) * PARCEL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;
      int scrollPx = screen.parcelsGridScrollRows * (PARCEL_CELL_H + PARCEL_CELL_GAP);
      for (int i = 0; i < parcels.size(); i++) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (PARCEL_CELL_W + PARCEL_CELL_GAP);
         int cy = gridTop + row * (PARCEL_CELL_H + PARCEL_CELL_GAP) - scrollPx;
         if (mouseX >= cx && mouseX < cx + PARCEL_CELL_W
             && mouseY >= cy && mouseY < cy + PARCEL_CELL_H) {
            return parcels.get(i);
         }
      }
      return null;
   }
}
