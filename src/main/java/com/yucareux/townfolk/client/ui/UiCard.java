package com.yucareux.townfolk.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Dashboard pulse card — small tile with a title strip, a big value line, and
 * a footer hint. Used on the Overview tab.
 *
 *   ┌──────────────────────────┐
 *   │ TITLE                    │  ← muted
 *   │ BIG VALUE                │  ← heading
 *   │ small footer hint        │  ← faint
 *   └──────────────────────────┘  ← bottom border
 */
public final class UiCard {

   public static void draw(GuiGraphics g, Font font, int x, int y, int w, int h,
                           String title, String big, String foot) {
      g.fill(x, y, x + w, y + h, UiTheme.ROW_BG);
      g.fill(x, y + h - 1, x + w, y + h, UiTheme.PANEL_BORDER);
      g.drawString(font, title, x + 6, y + 4,                   UiTheme.MUTED,   true);
      g.drawString(font, big,   x + 6, y + 16,                  UiTheme.HEADING, true);
      g.drawString(font, foot,  x + 6, y + Math.max(28, h - 16), UiTheme.FAINT,   true);
   }

   private UiCard() {}
}
