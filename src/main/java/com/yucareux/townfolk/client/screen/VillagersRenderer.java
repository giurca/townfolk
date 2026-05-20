package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiBar;
import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Villagers tab renderer — search box header + filter chip row + grid
 * of villager tiles. Extracted from {@code TownAdminScreen} in stage
 * 15b.4.e.
 *
 * <p>The search EditBox is a vanilla widget so it lives on the host
 * screen (only init code can call {@code addRenderableWidget}). State
 * fields (scroll offset, prof/status filters) also stay on the host
 * because click + scroll handlers mutate them.
 */
final class VillagersRenderer {

   /** Villager grid cell geometry. Larger than the Resources cell
    *  because each tile carries more text — name, activity, HP. */
   static final int VILL_CELL_W = 84;
   static final int VILL_CELL_H = 72;
   static final int VILL_CELL_GAP = 8;
   /** Y offset (from tab content top) at which the villager grid begins —
    *  leaves room for the search box (row 1) and chip row (row 2). */
   static final int VILL_GRID_TOP = 48;

   private static final String[] PROFS  = {"all", "farmer", "shepherd", "butcher", "mason", "none"};
   private static final String[] STATS  = {"all", "working", "idle", "sleeping"};

   private VillagersRenderer() {}

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom,
                      int mouseX, int mouseY) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int spawnBtnH = 26;
      int listBottom = bottom - spawnBtnH;

      int alive  = screen.state.populationAlive();
      int total  = screen.state.villagers().size();
      String totalLabel = total + " villagers"
         + (alive == total ? "" : " · " + alive + " alive");
      UiText.rightFaint(graphics, font, totalLabel, innerR, top + 8);

      int chipsX = innerL;
      int chipsY = top + 26;
      renderFilterChips(screen, graphics, chipsX, chipsY, mouseX, mouseY);

      int gridTop = top + VILL_GRID_TOP;
      int gridBot = listBottom - 6;
      List<TownStateUpdatePayload.VillagerSummary> villagers = filteredVillagers(screen);
      if (villagers.isEmpty()) {
         if (screen.state.villagers().isEmpty()) {
            UiText.faint(graphics, font,
               "no villagers yet — click \"+ Spawn villager\"",
               innerL, gridTop + 10);
         } else {
            UiText.faint(graphics, font,
               "no villagers match the current filter",
               innerL, gridTop + 10);
         }
         return;
      }
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + VILL_CELL_GAP) / (VILL_CELL_W + VILL_CELL_GAP));
      int gridUsed = cols * VILL_CELL_W + (cols - 1) * VILL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      int totalRows = (villagers.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + VILL_CELL_GAP) / (VILL_CELL_H + VILL_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (screen.villagersGridScrollRows > maxScrollRow) screen.villagersGridScrollRows = maxScrollRow;
      int scrollPx = screen.villagersGridScrollRows * (VILL_CELL_H + VILL_CELL_GAP);

      TownAdminScreen.scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      int i = 0;
      for (var v : villagers) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (VILL_CELL_W + VILL_CELL_GAP);
         int cy = gridTop + row * (VILL_CELL_H + VILL_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + VILL_CELL_H < gridTop) { i++; continue; }
         renderCell(screen, graphics, v, cx, cy, mouseX, mouseY);
         i++;
      }
      graphics.disableScissor();
   }

   private static void renderCell(TownAdminScreen screen, GuiGraphics g,
                                   TownStateUpdatePayload.VillagerSummary v,
                                   int x, int y, int mouseX, int mouseY) {
      Font font = screen.font();
      boolean hovered = mouseX >= x && mouseX < x + VILL_CELL_W
                     && mouseY >= y && mouseY < y + VILL_CELL_H;
      int bg = hovered ? UiTheme.TAB_ACTIVE : UiTheme.ROW_BG;
      g.fill(x, y, x + VILL_CELL_W, y + VILL_CELL_H, bg);
      g.fill(x, y + VILL_CELL_H, x + VILL_CELL_W, y + VILL_CELL_H + 1, UiTheme.PANEL_BORDER);

      int pip;
      String activity = v.activity();
      if ("sleeping".equals(activity))      pip = 0xFF6E8FE0;
      else if ("idle".equals(activity))     pip = UiTheme.FAINT;
      else                                  pip = UiTheme.OK;
      g.fill(x + 3, y + 3, x + 7, y + 7, pip);

      String anchor = (v.playerSetHome() && v.playerSetJob()) ? "⌂⚒"
                    : v.playerSetHome() ? "⌂" : v.playerSetJob() ? "⚒" : "";
      if (!anchor.isEmpty()) {
         int aw = font.width(anchor);
         g.drawString(font, anchor, x + VILL_CELL_W - aw - 4, y + 2, UiTheme.MUTED, true);
      }

      int iconX = x + (VILL_CELL_W - 16) / 2;
      int iconY = y + 12;
      g.renderItem(screen.iconForProfession(v.profession()), iconX, iconY);

      String name = v.name() + (v.alive() ? "" : " ✝");
      String nameClip = UiText.truncate(font, name, VILL_CELL_W - 6);
      int nw = font.width(nameClip);
      g.drawString(font, nameClip,
         x + (VILL_CELL_W - nw) / 2, y + 32,
         v.alive() ? UiTheme.BODY : UiTheme.FAINT, true);

      String act = TownAdminScreen.prettifyActivity(activity);
      String actClip = UiText.truncate(font, act, VILL_CELL_W - 6);
      int aw2 = font.width(actClip);
      g.drawString(font, actClip,
         x + (VILL_CELL_W - aw2) / 2, y + 44, UiTheme.FAINT, true);

      int hpBarW = 60, hpBarH = 4;
      int hpX = x + (VILL_CELL_W - hpBarW) / 2;
      UiBar.draw(g, hpX, y + 58, hpBarW, hpBarH, v.health(), v.maxHealth());
   }

   static List<TownStateUpdatePayload.VillagerSummary> filteredVillagers(TownAdminScreen screen) {
      String q = screen.villagerSearchBox == null ? "" :
         screen.villagerSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
      String prof = screen.villagerProfFilter;
      String status = screen.villagerStatusFilter;
      List<TownStateUpdatePayload.VillagerSummary> out = new java.util.ArrayList<>();
      for (var v : screen.state.villagers()) {
         if (!q.isEmpty() && !v.name().toLowerCase(Locale.ROOT).contains(q)
             && !v.profession().toLowerCase(Locale.ROOT).contains(q)
             && !v.activity().toLowerCase(Locale.ROOT).contains(q)) continue;
         if (!"all".equals(prof) && !v.profession().equals(prof)) continue;
         if (!"all".equals(status)) {
            boolean sleeping = "sleeping".equals(v.activity());
            boolean idle = "idle".equals(v.activity());
            switch (status) {
               case "working" -> { if (sleeping || idle) continue; }
               case "idle"    -> { if (!idle) continue; }
               case "sleeping"-> { if (!sleeping) continue; }
            }
         }
         out.add(v);
      }
      return out;
   }

   private static void renderFilterChips(TownAdminScreen screen, GuiGraphics graphics,
                                          int x, int y, int mouseX, int mouseY) {
      int cursor = x;
      cursor = drawChipRow(screen, graphics, cursor, y, "Prof:", PROFS, screen.villagerProfFilter);
      cursor += 12;
      drawChipRow(screen, graphics, cursor, y, "Status:", STATS, screen.villagerStatusFilter);
   }

   /** Shared chip-row drawer — Resources tab sort chips reuse this. */
   static int drawChipRow(TownAdminScreen screen, GuiGraphics graphics, int x, int y,
                           String label, String[] options, String active) {
      Font font = screen.font();
      graphics.drawString(font, label, x, y + 4, UiTheme.MUTED, true);
      int cx = x + font.width(label) + 4;
      for (String o : options) {
         int w = font.width(o) + 8;
         boolean isActive = o.equals(active);
         int bg = isActive ? UiTheme.TAB_ACTIVE : UiTheme.ROW_BG;
         int fg = isActive ? UiTheme.HEADING    : UiTheme.MUTED;
         graphics.fill(cx, y, cx + w, y + 16, bg);
         graphics.fill(cx, y + 16, cx + w, y + 17, isActive ? UiTheme.HEADING : UiTheme.FAINT);
         graphics.drawString(font, o, cx + 4, y + 4, fg, true);
         cx += w + 3;
      }
      return cx;
   }

   /** Hit-test the chip row. Returns the (group, value) pair selected, or null. */
   static String[] hitTestChips(TownAdminScreen screen, double mouseX, double mouseY,
                                 int paneL, int top) {
      Font font = screen.font();
      int x = paneL + UiTheme.PADDING;
      int y = top + 26;
      int cx = x + font.width("Prof:") + 4;
      for (String o : PROFS) {
         int w = font.width(o) + 8;
         if (mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + 17) {
            return new String[]{"prof", o};
         }
         cx += w + 3;
      }
      cx += 12;
      cx += font.width("Status:") + 4;
      for (String o : STATS) {
         int w = font.width(o) + 8;
         if (mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + 17) {
            return new String[]{"status", o};
         }
         cx += w + 3;
      }
      return null;
   }

   /** Grid hit-test. Returns the villager UUID under the cursor or null. */
   static UUID villagerAtPoint(TownAdminScreen screen, double mouseX, double mouseY,
                                int paneL, int paneR, int top, int bottom) {
      int spawnBtnH = 26;
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + VILL_GRID_TOP;
      int gridBot = bottom - spawnBtnH - 6;
      if (mouseX < innerL || mouseX > innerR || mouseY < gridTop || mouseY > gridBot) return null;

      var villagers = filteredVillagers(screen);
      if (villagers.isEmpty()) return null;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + VILL_CELL_GAP) / (VILL_CELL_W + VILL_CELL_GAP));
      int gridUsed = cols * VILL_CELL_W + (cols - 1) * VILL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;
      int scrollPx = screen.villagersGridScrollRows * (VILL_CELL_H + VILL_CELL_GAP);
      for (int i = 0; i < villagers.size(); i++) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (VILL_CELL_W + VILL_CELL_GAP);
         int cy = gridTop + row * (VILL_CELL_H + VILL_CELL_GAP) - scrollPx;
         if (mouseX >= cx && mouseX < cx + VILL_CELL_W
             && mouseY >= cy && mouseY < cy + VILL_CELL_H) {
            return villagers.get(i).uuid();
         }
      }
      return null;
   }
}
