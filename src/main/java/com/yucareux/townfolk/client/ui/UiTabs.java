package com.yucareux.townfolk.client.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Horizontal tab strip. Rendering and click hit-testing share the same layout
 * data so they cannot drift — that was the most error-prone pattern in the
 * previous hand-rolled approach.
 */
public final class UiTabs<E> {

   public record Tab<E>(E value, String label, int width) {}

   public static <E> Tab<E> tab(E v, String label, int width) { return new Tab<>(v, label, width); }

   public static <E> void render(GuiGraphics g, Font font, int x, int y,
                                 List<Tab<E>> tabs, E active) {
      int cursor = x;
      for (Tab<E> t : tabs) {
         drawOne(g, font, cursor, y, t.width, UiTheme.TAB_HEIGHT, t.label, t.value.equals(active));
         cursor += t.width + UiTheme.TAB_GAP;
      }
   }

   private static void drawOne(GuiGraphics g, Font font, int x, int y, int w, int h,
                               String label, boolean active) {
      g.fill(x, y, x + w, y + h, active ? UiTheme.TAB_ACTIVE : 0);
      g.fill(x, y + h, x + w, y + h + 1, active ? UiTheme.HEADING : UiTheme.PANEL_BORDER);
      g.drawString(font, label, x + 8, y + 3, active ? UiTheme.HEADING : UiTheme.MUTED, true);
   }

   /** Hit-test: returns the tab value under the mouse, or null. */
   public static <E> E hitTest(int stripX, int stripY, List<Tab<E>> tabs,
                               double mouseX, double mouseY) {
      if (mouseY < stripY || mouseY > stripY + UiTheme.TAB_HEIGHT) return null;
      int cursor = stripX;
      for (Tab<E> t : tabs) {
         if (mouseX >= cursor && mouseX <= cursor + t.width) return t.value;
         cursor += t.width + UiTheme.TAB_GAP;
      }
      return null;
   }

   private UiTabs() {}
}
