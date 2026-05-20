package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tasks tab renderer — grid of open-todo tiles + task-detail popup
 * with a Mark-done chip. Extracted from {@code TownAdminScreen} in
 * stage 15b.4.f.
 */
final class TasksRenderer {

   static final int TASK_CELL_W = 110;
   static final int TASK_CELL_H = 72;
   static final int TASK_CELL_GAP = 8;
   /** Standardised across grid-style tabs. */
   static final int TASK_GRID_TOP = 26;

   /** Lazy paper-fallback icon shared by every task tile whose
    *  [need:X] prefix doesn't parse to a registered item. */
   private static ItemStack FALLBACK_ICON;

   private TasksRenderer() {}

   private static ItemStack fallbackIcon() {
      if (FALLBACK_ICON == null) FALLBACK_ICON = new ItemStack(Items.PAPER);
      return FALLBACK_ICON;
   }

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom,
                      int mouseX, int mouseY) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      List<TownAdminScreen.TaskRow> rows = screen.collectOpenTasks();
      int contentTop = top + 10;
      UiText.heading(graphics, font,
         "Open tasks across the town (" + rows.size() + ")",
         innerL, contentTop);
      if (rows.isEmpty()) {
         UiText.faint(graphics, font,
            "(no open commitments — nobody owes anybody anything)",
            innerL, contentTop + 14);
         return;
      }

      int gridTop = top + TASK_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + TASK_CELL_GAP) / (TASK_CELL_W + TASK_CELL_GAP));
      int gridUsed = cols * TASK_CELL_W + (cols - 1) * TASK_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      int totalRows = (rows.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + TASK_CELL_GAP) / (TASK_CELL_H + TASK_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (screen.tasksGridScrollRows > maxScrollRow) screen.tasksGridScrollRows = maxScrollRow;
      int scrollPx = screen.tasksGridScrollRows * (TASK_CELL_H + TASK_CELL_GAP);

      TownAdminScreen.scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      int i = 0;
      for (var r : rows) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (TASK_CELL_W + TASK_CELL_GAP);
         int cy = gridTop + row * (TASK_CELL_H + TASK_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + TASK_CELL_H < gridTop) { i++; continue; }
         renderCell(screen, graphics, r, cx, cy, mouseX, mouseY);
         i++;
      }
      graphics.disableScissor();

      if (screen.selectedTodoKey != null) {
         renderPopup(screen, graphics, paneL, paneR, top, bottom);
      }
   }

   private static void renderCell(TownAdminScreen screen, GuiGraphics g,
                                   TownAdminScreen.TaskRow r,
                                   int x, int y, int mouseX, int mouseY) {
      Font font = screen.font();
      boolean hovered = mouseX >= x && mouseX < x + TASK_CELL_W
                     && mouseY >= y && mouseY < y + TASK_CELL_H;
      String key = r.owner().uuid() + "|" + r.todo().id();
      boolean selected = key.equals(screen.selectedTodoKey);
      int bg = (hovered || selected) ? UiTheme.TAB_ACTIVE : UiTheme.ROW_BG;
      g.fill(x, y, x + TASK_CELL_W, y + TASK_CELL_H, bg);
      g.fill(x, y + TASK_CELL_H, x + TASK_CELL_W, y + TASK_CELL_H + 1, UiTheme.PANEL_BORDER);

      String ownerClip = UiText.truncate(font, r.owner().name(), TASK_CELL_W - 30);
      g.drawString(font, ownerClip, x + 4, y + 3, UiTheme.HEADING, true);

      String day = "d" + r.todo().createdDay();
      int dw = font.width(day);
      g.drawString(font, day, x + TASK_CELL_W - dw - 4, y + 3, UiTheme.FAINT, true);

      ItemStack icon = iconForTodo(screen, r.todo().text());
      g.renderItem(icon, x + (TASK_CELL_W - 16) / 2, y + 14);

      String body = stripNeedPrefix(r.todo().text());
      var lines = font.split(Component.literal(body), TASK_CELL_W - 8);
      int bodyY = y + 34;
      for (int li = 0; li < Math.min(3, lines.size()); li++) {
         var line = lines.get(li);
         int lw = font.width(line);
         g.drawString(font, line,
            x + (TASK_CELL_W - lw) / 2, bodyY + li * 10, UiTheme.BODY, true);
      }
   }

   private static String stripNeedPrefix(String text) {
      if (text == null) return "";
      if (text.startsWith("[need:")) {
         int end = text.indexOf("] ");
         if (end >= 0) return text.substring(end + 2);
      }
      return text;
   }

   /** Map a todo's {@code [need:X]} prefix to an item icon. Returns
    *  the paper fallback if the prefix is missing or unrecognised. */
   private static ItemStack iconForTodo(TownAdminScreen screen, String text) {
      if (text == null || !text.startsWith("[need:")) return fallbackIcon();
      int end = text.indexOf("] ");
      if (end < 0) return fallbackIcon();
      String need = text.substring(6, end);
      if (need.startsWith("barrel_for_")) need = need.substring("barrel_for_".length());
      String itemId = switch (need) {
         case "hoe", "seeds_for_till"            -> "minecraft:wooden_hoe";
         case "seeds"                             -> "minecraft:wheat_seeds";
         case "shears", "shear_sheep_tool"       -> "minecraft:shears";
         case "milk_cow_tool"                     -> "minecraft:bucket";
         default                                  -> need.contains(":") ? need : "minecraft:" + need;
      };
      return screen.stackForItemId(itemId);
   }

   static TownAdminScreen.TaskRow findTodoByKey(TownAdminScreen screen, String key) {
      if (key == null) return null;
      int bar = key.indexOf('|');
      if (bar < 0) return null;
      UUID ownerId;
      try { ownerId = UUID.fromString(key.substring(0, bar)); }
      catch (IllegalArgumentException ex) { return null; }
      String todoId = key.substring(bar + 1);
      for (var r : screen.collectOpenTasks()) {
         if (r.owner().uuid().equals(ownerId) && todoId.equals(r.todo().id())) return r;
      }
      return null;
   }

   /** Grid hit-test. Returns the row under the cursor or null. */
   static TownAdminScreen.TaskRow taskAtPoint(TownAdminScreen screen,
                                               double mouseX, double mouseY,
                                               int paneL, int paneR,
                                               int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + TASK_GRID_TOP;
      int gridBot = bottom - 6;
      if (mouseX < innerL || mouseX > innerR || mouseY < gridTop || mouseY > gridBot) return null;

      var rows = screen.collectOpenTasks();
      if (rows.isEmpty()) return null;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + TASK_CELL_GAP) / (TASK_CELL_W + TASK_CELL_GAP));
      int gridUsed = cols * TASK_CELL_W + (cols - 1) * TASK_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;
      int scrollPx = screen.tasksGridScrollRows * (TASK_CELL_H + TASK_CELL_GAP);
      for (int i = 0; i < rows.size(); i++) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (TASK_CELL_W + TASK_CELL_GAP);
         int cy = gridTop + row * (TASK_CELL_H + TASK_CELL_GAP) - scrollPx;
         if (mouseX >= cx && mouseX < cx + TASK_CELL_W
             && mouseY >= cy && mouseY < cy + TASK_CELL_H) {
            return rows.get(i);
         }
      }
      return null;
   }

   private static void renderPopup(TownAdminScreen screen, GuiGraphics g,
                                    int paneL, int paneR, int top, int bottom) {
      var r = findTodoByKey(screen, screen.selectedTodoKey);
      if (r == null) { screen.selectedTodoKey = null; return; }
      Font font = screen.font();

      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(380, innerR - innerL - 40);
      int popH = 180;
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
         r.owner().name() + " · day " + r.todo().createdDay(), x, y);
      y += 14;

      g.renderItem(iconForTodo(screen, r.todo().text()), x, y);
      String cp = r.todo().counterparty();
      g.drawString(font, "Owed to: " + (cp == null || cp.isBlank() ? "—" : cp),
         x + 22, y + 4, UiTheme.BODY, true);
      y += 24;

      String body = stripNeedPrefix(r.todo().text());
      for (var line : font.split(Component.literal(body), popW - 28)) {
         if (y > popY + popH - 40) break;
         g.drawString(font, line, x, y, UiTheme.BODY, true);
         y += 10;
      }

      int btnY = popY + popH - 24;
      TradeRenderer.drawButton(g, font, popX + 14, btnY, 110, "Mark done", true);
      TradeRenderer.drawButton(g, font, popX + popW - 14 - 80, btnY, 80, "Close", false);
   }

   /** Hit-test the popup's chip row. Returns "done", "close", or null. */
   static String hitTestPopupButton(double mouseX, double mouseY,
                                     int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(380, innerR - innerL - 40);
      int popH = 180;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;
      int btnY = popY + popH - 24;
      if (mouseY < btnY || mouseY >= btnY + 18) return null;
      int doneX = popX + 14;
      if (mouseX >= doneX && mouseX < doneX + 110) return "done";
      int closeX = popX + popW - 14 - 80;
      if (mouseX >= closeX && mouseX < closeX + 80) return "close";
      return null;
   }
}
