package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Resources tab renderer — icon grid (treasury + stockpile interleaved)
 * + per-item drill-down popup with production-cap controls. Extracted
 * from {@code TownAdminScreen} in stage 15b.4.g — the final 15b.4
 * slice. Production-cap EditBox + button widgets stay on the host
 * screen because their lifecycle needs {@code addRenderableWidget}.
 */
final class ResourcesRenderer {

   /** Cell metrics for the icon grid. */
   static final int RES_CELL_W = 66;
   static final int RES_CELL_H = 58;
   static final int RES_CELL_GAP = 8;
   /** Y offset (from tab content top) where the grid begins. */
   static final int RES_GRID_TOP = 26;

   /** One grid cell — either a stockpile item or a treasury payout
    *  (claimable). The {@code treasury} flag flips the styling. */
   record ResCell(TownStateUpdatePayload.ItemCount ic, boolean treasury) {}

   private ResourcesRenderer() {}

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom,
                      int mouseX, int mouseY) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int contentTop = top + 10;

      int totalDistinct = screen.state.aggregateResources().size();
      int totalItems = 0;
      for (var ic : screen.state.aggregateResources()) totalItems += ic.count();
      String totalLabel = totalDistinct + " kinds · " + totalItems + " items";
      UiText.rightFaint(graphics, font, totalLabel, innerR, contentTop);

      int chipsX = innerL + 206;
      VillagersRenderer.drawChipRow(screen, graphics, chipsX, top + 4, "Sort:",
         new String[]{"count", "name", "recent"}, screen.resourceSort);

      // Refresh recency cache for the "recent" sort.
      screen.resourceRecentByItem.clear();
      for (var rl : screen.state.resourceLocations()) {
         long tick = 0L;
         for (var se : screen.state.storage()) {
            if (se.packedPos() == rl.packedPos()) { tick = se.lastUpdatedTick(); break; }
         }
         screen.resourceRecentByItem.merge(rl.itemId(), tick, Math::max);
      }

      List<ResCell> items = collectCells(screen);

      int gridTop = top + RES_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW   = innerR - innerL;
      int cols    = Math.max(1, (gridW + RES_CELL_GAP) / (RES_CELL_W + RES_CELL_GAP));
      int gridUsed = cols * RES_CELL_W + (cols - 1) * RES_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      String q = searchQuery(screen);
      if (items.isEmpty()) {
         UiText.faint(graphics, font,
            q.isEmpty() ? "no resources stocked — register a barrel and have a villager deposit something"
                         : "no items match \"" + q + "\"",
            innerL, gridTop + 10);
      } else {
         int totalRows = (items.size() + cols - 1) / cols;
         int visibleRows = Math.max(1, (gridBot - gridTop + RES_CELL_GAP) / (RES_CELL_H + RES_CELL_GAP));
         int maxScrollRow = Math.max(0, totalRows - visibleRows);
         if (screen.resourcesGridScrollRows > maxScrollRow) screen.resourcesGridScrollRows = maxScrollRow;
         int scrollPx = screen.resourcesGridScrollRows * (RES_CELL_H + RES_CELL_GAP);

         TownAdminScreen.scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
         int i = 0;
         for (var cell : items) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX0 + col * (RES_CELL_W + RES_CELL_GAP);
            int cy = gridTop + row * (RES_CELL_H + RES_CELL_GAP) - scrollPx;
            if (cy > gridBot) break;
            if (cy + RES_CELL_H < gridTop) { i++; continue; }
            renderCell(screen, graphics, cell, cx, cy, mouseX, mouseY);
            i++;
         }
         graphics.disableScissor();
      }

      if (screen.selectedResourceItem != null) {
         renderPopup(screen, graphics, paneL, paneR, top, bottom, mouseX, mouseY);
      }
   }

   private static String searchQuery(TownAdminScreen screen) {
      return screen.resourceSearchBox == null ? "" :
         screen.resourceSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
   }

   /** Build the sorted, filtered, treasury-first cell list. Shared by
    *  render and hit-test so click coordinates match what's drawn. */
   private static List<ResCell> collectCells(TownAdminScreen screen) {
      String q = searchQuery(screen);
      List<ResCell> items = new java.util.ArrayList<>();
      for (var ic : screen.state.aggregateResources()) {
         if (q.isEmpty() || TownAdminScreen.shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) {
            items.add(new ResCell(ic, false));
         }
      }
      for (var ic : screen.state.treasury()) {
         if (q.isEmpty() || TownAdminScreen.shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) {
            items.add(new ResCell(ic, true));
         }
      }
      java.util.Comparator<ResCell> within = switch (screen.resourceSort) {
         case "name"   -> (a, b) -> TownAdminScreen.shortItemName(a.ic().itemId())
                                       .compareToIgnoreCase(TownAdminScreen.shortItemName(b.ic().itemId()));
         case "recent" -> (a, b) -> Long.compare(
            screen.resourceRecentByItem.getOrDefault(b.ic().itemId(), 0L),
            screen.resourceRecentByItem.getOrDefault(a.ic().itemId(), 0L));
         default        -> (a, b) -> Integer.compare(b.ic().count(), a.ic().count());
      };
      items.sort(java.util.Comparator.<ResCell, Boolean>comparing(c -> !c.treasury())
         .thenComparing(within));
      return items;
   }

   private static void renderCell(TownAdminScreen screen, GuiGraphics g, ResCell cell,
                                   int x, int y, int mouseX, int mouseY) {
      Font font = screen.font();
      var ic = cell.ic();
      boolean treasury = cell.treasury();
      boolean hovered = mouseX >= x && mouseX < x + RES_CELL_W
                     && mouseY >= y && mouseY < y + RES_CELL_H;
      boolean selected = !treasury && ic.itemId().equals(screen.selectedResourceItem);

      int bg = treasury ? UiTheme.TREASURY_CELL_BG
            : (hovered || selected ? UiTheme.TAB_ACTIVE : UiTheme.ROW_BG);
      g.fill(x, y, x + RES_CELL_W, y + RES_CELL_H, bg);

      if (treasury) {
         g.fill(x, y, x + RES_CELL_W, y + 1, UiTheme.TREASURY_CELL_BORDER);
         g.fill(x, y + RES_CELL_H, x + RES_CELL_W, y + RES_CELL_H + 1, UiTheme.TREASURY_CELL_BORDER);
         g.fill(x, y, x + 1, y + RES_CELL_H, UiTheme.TREASURY_CELL_BORDER);
         g.fill(x + RES_CELL_W - 1, y, x + RES_CELL_W, y + RES_CELL_H, UiTheme.TREASURY_CELL_BORDER);
      } else {
         g.fill(x, y + RES_CELL_H, x + RES_CELL_W, y + RES_CELL_H + 1,
            selected ? UiTheme.HEADING : UiTheme.PANEL_BORDER);
      }

      if (treasury) {
         String tag = "CLAIM";
         int tagW = font.width(tag);
         g.drawString(font, tag,
            x + RES_CELL_W - tagW - 4, y + 2,
            UiTheme.TREASURY_CELL_BORDER, true);
         g.fill(x + 3, y + 3, x + 6, y + 6, UiTheme.TREASURY_CELL_BORDER);
      } else {
         var target = findTargetFor(screen, ic.itemId());
         if (target != null) {
            String tag = target.min() + "/" + target.max();
            int tagW = font.width(tag);
            int tagX = x + RES_CELL_W - tagW - 4;
            int tagY = y + 2;
            g.drawString(font, tag, tagX, tagY,
               target.active() ? UiTheme.MUTED : UiTheme.ERROR, true);
            int dotColor = target.active() ? UiTheme.OK : UiTheme.BAD;
            g.fill(x + 3, y + 3, x + 6, y + 6, dotColor);
         }
      }

      int iconX = x + (RES_CELL_W - 16) / 2;
      int iconY = y + 12;
      g.renderItem(screen.stackForItemId(ic.itemId()), iconX, iconY);

      if (treasury) {
         String countText = String.valueOf(ic.count());
         int cw = font.width(countText);
         g.drawString(font, countText,
            x + (RES_CELL_W - cw) / 2, y + 32,
            UiTheme.TREASURY_CELL_BORDER, true);
      } else {
         int inFlight = inFlightFor(screen, ic.itemId());
         String arrow = ic.trend() > 0 ? "↑ " : ic.trend() < 0 ? "↓ " : "";
         int arrowColor = ic.trend() > 0 ? UiTheme.OK
                        : ic.trend() < 0 ? UiTheme.BAD
                        : UiTheme.FAINT;
         String countText = String.valueOf(ic.count());
         String suffix = inFlight > 0 ? "  +" + inFlight : "";
         int arrowW = font.width(arrow);
         int countW = font.width(countText);
         int suffixW = font.width(suffix);
         int totalW = arrowW + countW + suffixW;
         int drawX = x + (RES_CELL_W - totalW) / 2;
         if (!arrow.isEmpty()) {
            g.drawString(font, arrow, drawX, y + 32, arrowColor, true);
            drawX += arrowW;
         }
         g.drawString(font, countText, drawX, y + 32, UiTheme.HEADING, true);
         drawX += countW;
         if (!suffix.isEmpty()) {
            g.drawString(font, suffix, drawX, y + 32, UiTheme.FAINT, true);
         }
      }

      String name = TownAdminScreen.shortItemName(ic.itemId());
      String shown = name;
      if (font.width(shown) > RES_CELL_W - 6) {
         shown = font.plainSubstrByWidth(shown, RES_CELL_W - 10) + "…";
      }
      int nw = font.width(shown);
      g.drawString(font, shown, x + (RES_CELL_W - nw) / 2, y + 44,
         treasury ? UiTheme.TREASURY_CELL_BORDER : UiTheme.MUTED, true);
   }

   static int inFlightFor(TownAdminScreen screen, String itemId) {
      int sum = 0;
      for (var v : screen.state.villagers()) {
         for (var ic : v.inventory()) {
            if (ic.itemId().equals(itemId)) sum += ic.count();
         }
      }
      return sum;
   }

   static TownStateUpdatePayload.ProductionTarget findTargetFor(TownAdminScreen screen, String itemId) {
      if (screen.state.productionTargets() == null) return null;
      for (var t : screen.state.productionTargets()) {
         if (t.itemId().equals(itemId)) return t;
      }
      return null;
   }

   /** Draws labels + status line around the Cap / min / max EditBoxes.
    *  The EditBoxes themselves are vanilla widgets owned by the host
    *  screen — coordinates here MUST match {@code initResourcePopupWidgets}
    *  or the labels won't line up. */
   static void renderTargetControls(TownAdminScreen screen, GuiGraphics g,
                                     int popX, int rowY, int popW,
                                     TownStateUpdatePayload.ProductionTarget target) {
      Font font = screen.font();
      int innerL = popX + 12;
      g.drawString(font, "Cap", innerL, rowY + 5, UiTheme.MUTED, true);

      int minLabelX = innerL + 42;
      g.drawString(font, "min:", minLabelX, rowY + 5, UiTheme.MUTED, true);

      int maxLabelX = minLabelX + 88;
      g.drawString(font, "max:", maxLabelX, rowY + 5, UiTheme.MUTED, true);

      String status = target == null
         ? "(no cap — set min/max and Apply to enable)"
         : (target.active() ? "● producing" : "● paused (at cap)");
      int statusColor = target == null ? UiTheme.FAINT
                                       : (target.active() ? UiTheme.OK : UiTheme.BAD);
      g.drawString(font, status, innerL, rowY + 19, statusColor, true);

      g.fill(popX + 8, rowY + 26, popX + popW - 8, rowY + 27, UiTheme.PANEL_BORDER);
   }

   /** Geometry of the drill-down popup. Shared with the click handler
    *  so outside-click hit-tests use the same rect that's rendered. */
   static int[] resourcePopupBounds(TownAdminScreen screen,
                                     int paneL, int paneR, int top, int bottom) {
      int drillCount = 0;
      if (screen.selectedResourceItem != null) {
         for (var rl : screen.state.resourceLocations()) {
            if (rl.itemId().equals(screen.selectedResourceItem)) drillCount++;
         }
      }
      int popW = 380;
      int targetRowH = screen.selectedResourceItem != null ? 28 : 0;
      int popH = Math.min(360, 60 + targetRowH + Math.max(2, drillCount) * 22 + 16);
      int popX = paneL + ((paneR - paneL) - popW) / 2;
      int popY = top + ((bottom - top) - popH) / 2;
      return new int[]{ popX, popY, popW, popH };
   }

   private static void renderPopup(TownAdminScreen screen, GuiGraphics g,
                                    int paneL, int paneR, int top, int bottom,
                                    int mouseX, int mouseY) {
      Font font = screen.font();
      String item = screen.selectedResourceItem;
      List<TownStateUpdatePayload.ResourceLoc> drill = new java.util.ArrayList<>();
      for (var rl : screen.state.resourceLocations()) {
         if (rl.itemId().equals(item)) drill.add(rl);
      }
      int[] bounds = resourcePopupBounds(screen, paneL, paneR, top, bottom);
      int popX = bounds[0], popY = bounds[1], popW = bounds[2], popH = bounds[3];

      g.fill(paneL, top, paneR, bottom, 0xC0000000);
      g.fill(popX, popY, popX + popW, popY + popH, UiTheme.PANEL_BG);
      g.fill(popX - 1, popY - 1, popX + popW + 1, popY, UiTheme.HEADING);
      g.fill(popX - 1, popY + popH, popX + popW + 1, popY + popH + 1, UiTheme.HEADING);
      g.fill(popX - 1, popY - 1, popX, popY + popH + 1, UiTheme.HEADING);
      g.fill(popX + popW, popY - 1, popX + popW + 1, popY + popH + 1, UiTheme.HEADING);

      int hx = popX + 10, hy = popY + 8;
      g.renderItem(screen.stackForItemId(item), hx, hy);
      int total = 0;
      for (var rl : drill) total += rl.count();
      g.drawString(font, TownAdminScreen.shortItemName(item) + " · " + total + " across "
         + drill.size() + " " + (drill.size() == 1 ? "container" : "containers"),
         hx + 22, hy + 4, UiTheme.HEADING, true);

      String hint = "click outside or press ESC to close";
      g.drawString(font, hint, popX + popW - font.width(hint) - 10,
         popY + popH - 12, UiTheme.FAINT, true);

      int rowY = popY + 32;
      var target = findTargetFor(screen, item);
      renderTargetControls(screen, g, popX, rowY, popW, target);
      rowY += 28;

      int rowH = 22;
      TownAdminScreen.scaledScissor(g, popX, rowY, popX + popW, popY + popH - 16);
      if (drill.isEmpty()) {
         g.drawString(font, "(this item has been emptied since the page was last refreshed)",
            popX + 12, rowY + 4, UiTheme.FAINT, true);
      } else {
         for (var rl : drill) {
            if (rowY + rowH > popY + popH - 16) break;
            String label = null;
            String kind = "barrel";
            for (var se : screen.state.storage()) {
               if (se.packedPos() == rl.packedPos()) {
                  label = se.label();
                  kind  = se.blockKind();
                  break;
               }
            }
            net.minecraft.core.BlockPos cp = net.minecraft.core.BlockPos.of(rl.packedPos());
            g.fill(popX + 8, rowY, popX + popW - 8, rowY + rowH - 2, UiTheme.ROW_BG);
            String left = (label == null || label.isBlank())
               ? (kind + " at " + cp.toShortString())
               : ("\"" + label + "\"  · " + kind + " at " + cp.toShortString());
            g.drawString(font, UiText.truncate(font, left, popW - 80),
               popX + 14, rowY + 6, UiTheme.BODY, true);
            String right = rl.count() + "×";
            g.drawString(font, right,
               popX + popW - 14 - font.width(right), rowY + 6, UiTheme.HEADING, true);
            rowY += rowH;
         }
      }
      g.disableScissor();
   }

   /** Grid hit-test. Returns the cell under the cursor, or null. */
   static ResCell hitTestCell(TownAdminScreen screen, double mouseX, double mouseY,
                               int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + RES_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW   = innerR - innerL;
      int cols    = Math.max(1, (gridW + RES_CELL_GAP) / (RES_CELL_W + RES_CELL_GAP));
      int gridUsed = cols * RES_CELL_W + (cols - 1) * RES_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      List<ResCell> items = collectCells(screen);
      int scrollPx = screen.resourcesGridScrollRows * (RES_CELL_H + RES_CELL_GAP);
      int i = 0;
      for (var cell : items) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (RES_CELL_W + RES_CELL_GAP);
         int cy = gridTop + row * (RES_CELL_H + RES_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + RES_CELL_H < gridTop) { i++; continue; }
         if (mouseX >= cx && mouseX < cx + RES_CELL_W
             && mouseY >= cy && mouseY < Math.min(cy + RES_CELL_H, gridBot)
             && mouseY >= Math.max(cy, gridTop)) {
            return cell;
         }
         i++;
      }
      return null;
   }

   /** Hit-test the sort chip row. Returns the new sort key or null. */
   static String hitTestSortChips(TownAdminScreen screen, double mouseX, double mouseY,
                                   int paneL, int top) {
      Font font = screen.font();
      int x = paneL + UiTheme.PADDING + 206;
      int y = top + 4;
      int cx = x + font.width("Sort:") + 4;
      String[] opts = {"count", "name", "recent"};
      for (String o : opts) {
         int w = font.width(o) + 8;
         if (mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + 17) return o;
         cx += w + 3;
      }
      return null;
   }
}
