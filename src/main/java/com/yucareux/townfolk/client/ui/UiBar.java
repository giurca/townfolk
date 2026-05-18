package com.yucareux.townfolk.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Progress bar: fixed-width track with a fill proportional to {@code value/max}.
 * Colour shifts by fraction — bad / warn / ok.
 */
public final class UiBar {

   public static void draw(GuiGraphics g, int x, int y, int w, int h, float value, float max) {
      draw(g, x, y, w, h, value, max, /* customFg */ -1);
   }

   /** Pass {@code customFg = -1} to colour by fraction; any non-negative value
    *  pins the fill colour (use for cosmetic / categorical bars like Economy). */
   public static void draw(GuiGraphics g, int x, int y, int w, int h, float value, float max, int customFg) {
      g.fill(x, y, x + w, y + h, UiTheme.ROW_BG);
      if (max <= 0 || value <= 0) return;
      float frac = Math.min(1.0f, value / max);
      int fillW = Math.max(1, Math.round(w * frac));
      int fg;
      if (customFg >= 0) {
         fg = customFg;
      } else if (frac < 0.3f) fg = UiTheme.BAD;
      else if (frac < 0.7f)   fg = UiTheme.HEADING;
      else                    fg = UiTheme.OK;
      g.fill(x, y, x + fillW, y + h, fg);
   }

   private UiBar() {}
}
