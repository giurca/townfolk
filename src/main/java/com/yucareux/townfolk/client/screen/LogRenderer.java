package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Activity-log tab renderer — extracted from {@code TownAdminScreen}
 * in stage 15b.4.a. The log is read-only so this is the simplest
 * tab to split out; serves as the template for the rest of 15b.4.
 *
 * <p>Newest entries first. Entry tags ([exchange], [compact],
 * [dialogue], [warn]) drive the colour palette; message bodies wrap
 * across multiple lines rather than getting truncated.
 */
final class LogRenderer {

   private LogRenderer() {}

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int contentTop = top + 10;
      UiText.heading(graphics, font, "Activity log (latest first)", innerL, contentTop);
      List<TownStateUpdatePayload.LogEntry> entries = screen.state.log();
      UiText.rightFaint(graphics, font,
         entries.size() + " entries", innerR, contentTop);
      if (entries.isEmpty()) {
         UiText.faint(graphics, font,
            "(no activity yet — exchanges, dialogue, compaction will appear here)",
            innerL, contentTop + 14);
         return;
      }
      int y = contentTop + 16;
      int maxBottom = bottom - 4;
      for (int i = entries.size() - 1; i >= 0; i--) {
         if (y > maxBottom) break;
         TownStateUpdatePayload.LogEntry e = entries.get(i);
         int color = switch (e.level()) {
            case "EXCHANGE" -> 0xFFB0D0FF;
            case "COMPACT"  -> UiTheme.RESOLVED;
            case "DIALOGUE" -> UiTheme.HEADING;
            case "WARN"     -> UiTheme.ERROR;
            default          -> UiTheme.MUTED;
         };
         String tag = "[" + e.level().toLowerCase(Locale.ROOT) + "]";
         graphics.drawString(font, tag, innerL, y, color, true);
         int tagW = font.width(tag) + 4;
         List<FormattedCharSequence> wrapped =
            font.split(Component.literal(e.message()), innerR - innerL - tagW);
         int textX = innerL + tagW;
         for (FormattedCharSequence line : wrapped) {
            if (y > maxBottom) break;
            graphics.drawString(font, line, textX, y, UiTheme.BODY, true);
            y += font.lineHeight + 1;
         }
         y += 1;
      }
   }
}
