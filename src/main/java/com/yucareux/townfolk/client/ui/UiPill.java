package com.yucareux.townfolk.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Small inline badge with a background swatch and tinted text. Used for
 * statuses ("Peaceful · combat off", "Radius 64", profession tags, etc.).
 *
 * The drawn pill returns its right-edge X so callers can chain pills along a
 * row without re-measuring each time.
 */
public final class UiPill {

   public static int draw(GuiGraphics g, Font font, int x, int y, String text, int fgColor) {
      return draw(g, font, x, y, text, fgColor, UiTheme.ROW_BG);
   }

   public static int draw(GuiGraphics g, Font font, int x, int y, String text, int fgColor, int bgColor) {
      int w = font.width(text) + 8;
      int h = font.lineHeight + 2;
      g.fill(x, y, x + w, y + h, bgColor);
      g.drawString(font, text, x + 4, y + 1, fgColor, true);
      return x + w;
   }

   private UiPill() {}
}
