package com.yucareux.townfolk.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Small text-drawing helpers that wrap the most common GuiGraphics patterns
 * with semantic names. Keeps tab renderers reading like declarative layout.
 */
public final class UiText {

   public static void heading(GuiGraphics g, Font f, String text, int x, int y) {
      g.drawString(f, text, x, y, UiTheme.HEADING, true);
   }

   public static void body(GuiGraphics g, Font f, String text, int x, int y) {
      g.drawString(f, text, x, y, UiTheme.BODY, true);
   }

   public static void muted(GuiGraphics g, Font f, String text, int x, int y) {
      g.drawString(f, text, x, y, UiTheme.MUTED, true);
   }

   public static void faint(GuiGraphics g, Font f, String text, int x, int y) {
      g.drawString(f, text, x, y, UiTheme.FAINT, true);
   }

   /** Right-aligned variant. */
   public static void rightMuted(GuiGraphics g, Font f, String text, int rightX, int y) {
      g.drawString(f, text, rightX - f.width(text), y, UiTheme.MUTED, true);
   }
   public static void rightFaint(GuiGraphics g, Font f, String text, int rightX, int y) {
      g.drawString(f, text, rightX - f.width(text), y, UiTheme.FAINT, true);
   }
   public static void rightHeading(GuiGraphics g, Font f, String text, int rightX, int y) {
      g.drawString(f, text, rightX - f.width(text), y, UiTheme.HEADING, true);
   }

   /** Truncate to fit width, appending an ellipsis if needed. */
   public static String truncate(Font f, String text, int maxWidth) {
      if (f.width(text) <= maxWidth) return text;
      String ellipsis = "…";
      int ew = f.width(ellipsis);
      int lo = 0, hi = text.length();
      while (lo < hi) {
         int mid = (lo + hi + 1) / 2;
         if (f.width(text.substring(0, mid)) + ew <= maxWidth) lo = mid; else hi = mid - 1;
      }
      return text.substring(0, lo) + ellipsis;
   }

   private UiText() {}
}
