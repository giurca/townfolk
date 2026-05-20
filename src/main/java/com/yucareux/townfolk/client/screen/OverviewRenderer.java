package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiCard;
import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Overview tab renderer — pulse strip + town status + OpenRouter
 * status line + town-shared facts. Extracted from {@code TownAdminScreen}
 * in stage 15b.4.b.
 *
 * <p>Shared Y-offset constants (the {@code OV_*} fields) live here
 * so the init-side widget placement and the render-side label/list
 * placement can't drift out of sync. {@code TownAdminScreen}'s init
 * code references them through this class.
 */
final class OverviewRenderer {

   /** Y offsets used by BOTH initOverviewTabWidgets and renderOverviewTab so
    *  widgets and labels can't drift. All measured down from the tab's content
    *  top. Tweak in one place if the layout changes. */
   static final int OV_PULSE_Y      = 4;
   /** One row of pulse cards (counters only). */
   static final int OV_PULSE_H      = UiTheme.CARD_HEIGHT;
   /** Town-status summary lives between the pulse strip and the Town
    *  Name input. Two right-aligned lines (cap, trade) need ~22 px of
    *  vertical room. */
   static final int OV_NAME_LABEL_Y = OV_PULSE_Y + OV_PULSE_H + 28;
   static final int OV_NAME_BOX_Y   = OV_NAME_LABEL_Y + 12;
   static final int OV_STATUS_Y     = OV_NAME_BOX_Y + 30;
   static final int OV_REFRESH_Y    = OV_STATUS_Y;
   static final int OV_FACTS_Y      = OV_STATUS_Y + 30;

   private OverviewRenderer() {}

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom) {
      renderPulse(screen, graphics, paneL, paneR, top + OV_PULSE_Y);
      renderBody(screen, graphics, paneL, paneR, top, bottom);
   }

   /** Pulse strip: a row of 6 small cards across the top — population
    *  + activity breakdown + day-over-day deltas. All counters come
    *  from server-side tallies so the UI doesn't walk the villager
    *  list each frame. */
   private static void renderPulse(TownAdminScreen screen, GuiGraphics graphics,
                                    int paneL, int paneR, int top) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int total = innerR - innerL;
      int gap = UiTheme.GAP_MEDIUM;
      int cardW = (total - 5 * gap) / 6;
      int cardH = UiTheme.CARD_HEIGHT;

      int alive  = screen.state.populationAlive();
      int tot    = screen.state.populationTotal();
      int work   = screen.state.workingCount();
      int idle   = screen.state.idleCount();
      int sleep  = screen.state.sleepingCount();
      int births = screen.state.birthsToday();
      int deaths = screen.state.deathsToday();

      int x = innerL;
      UiCard.draw(graphics, font, x, top, cardW, cardH,
         "Residents", alive + " / " + tot,
         (tot - alive) == 0 ? "all alive" : (tot - alive) + " gone");
      x += cardW + gap;
      UiCard.draw(graphics, font, x, top, cardW, cardH,
         "Working", String.valueOf(work),
         alive == 0 ? "—" : pct(work, alive) + "% busy");
      x += cardW + gap;
      UiCard.draw(graphics, font, x, top, cardW, cardH,
         "Idle", String.valueOf(idle),
         alive == 0 ? "—" : pct(idle, alive) + "% idle");
      x += cardW + gap;
      UiCard.draw(graphics, font, x, top, cardW, cardH,
         "Sleeping", String.valueOf(sleep),
         alive == 0 ? "—" : pct(sleep, alive) + "% in bed");
      x += cardW + gap;
      UiCard.draw(graphics, font, x, top, cardW, cardH,
         "Births today", String.valueOf(births), births == 0 ? "—" : "+" + births);
      x += cardW + gap;
      UiCard.draw(graphics, font, x, top, cardW, cardH,
         "Deaths today", String.valueOf(deaths), deaths == 0 ? "—" : "-" + deaths);
   }

   private static String pct(int part, int whole) {
      if (whole <= 0) return "0";
      return String.valueOf(Math.round(part * 100.0f / whole));
   }

   private static void renderBody(TownAdminScreen screen, GuiGraphics graphics,
                                   int paneL, int paneR, int top, int bottom) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      // Town status header — right-aligned under the pulse strip.
      int tradePosts = screen.state.tradePostCount();
      int prestige   = screen.state.prestige();
      int homes      = screen.state.homeCount();
      boolean townHall = screen.state.hasTownHall();
      int taverns    = screen.state.tavernCount();
      int popCap     = homes + (townHall ? 4 : 0);
      int popAlive   = screen.state.populationAlive();
      int hungry = 0;
      for (var vs : screen.state.villagers()) {
         if (vs.alive() && vs.hunger() < 50) hungry++;
      }
      String capLine = "Pop: " + popAlive + " / " + popCap
                     + "  ·  Homes: " + homes
                     + (townHall ? "  ·  ⛨ Town Hall (+4)" : "")
                     + (taverns > 0 ? "  ·  Tavern × " + taverns : "")
                     + (hungry > 0 ? "  ·  " + hungry + " hungry" : "");
      String tradeLine = "Trade Posts: " + tradePosts
                       + "  ·  Prestige: " + prestige + " / "
                       + com.yucareux.townfolk.town.TownData.MAX_PRESTIGE;
      UiText.rightFaint(graphics, font, capLine,
         innerR, top + OV_PULSE_Y + OV_PULSE_H + 4);
      UiText.rightFaint(graphics, font, tradeLine,
         innerR, top + OV_PULSE_Y + OV_PULSE_H + 14);

      // Town name label sits just above the (already-placed) EditBox.
      UiText.muted(graphics, font, "Town name", innerL, top + OV_NAME_LABEL_Y);

      // OpenRouter status line.
      int blockY = top + OV_STATUS_Y;
      UiText.muted(graphics, font, "OpenRouter", innerL, blockY);
      String status = screen.state.openrouterStatus();
      String statusLine;
      int statusFg = UiTheme.BODY;
      if ("disabled".equals(status)) {
         statusLine = "disabled (no API key)";
         statusFg = UiTheme.FAINT;
      } else if ("loading".equals(status)) {
         statusLine = "fetching...";
         statusFg = UiTheme.MUTED;
      } else if (status.startsWith("error")) {
         statusLine = status;
         statusFg = UiTheme.ERROR;
      } else if (screen.state.usage().isPresent() || screen.state.limit().isPresent()) {
         double usage = screen.state.usage().orElse(0.0);
         double limit = screen.state.limit().orElse(0.0);
         if (screen.state.limit().isPresent()) {
            statusLine = String.format(Locale.ROOT, "spent $%.4f / credits $%.2f", usage, limit);
         } else {
            statusLine = String.format(Locale.ROOT, "spent $%.4f", usage);
         }
      } else {
         statusLine = "(refresh to fetch)";
         statusFg = UiTheme.FAINT;
      }
      graphics.drawString(font, statusLine, innerL, blockY + 12, statusFg, true);

      // Town pinned facts — placement matches OV_FACTS_Y used by initOverviewTabWidgets.
      int factsTop = top + OV_FACTS_Y;
      UiText.muted(graphics, font,
         "Town-shared facts (known to every resident)",
         innerL, factsTop);
      int rowY = factsTop + 14;
      int rowH = 22;
      int listBottom = bottom - 30;
      List<TownStateUpdatePayload.PinSummary> facts = screen.state.townFacts();
      if (facts.isEmpty()) {
         graphics.drawString(font, "(none yet — add one below)",
            innerL, rowY + 4, UiTheme.FAINT, true);
      } else {
         for (TownStateUpdatePayload.PinSummary p : facts) {
            if (rowY + rowH > listBottom) break;
            graphics.fill(innerL, rowY, innerR - 50, rowY + rowH - 2, UiTheme.ROW_BG);
            int textFg = "resolved".equals(p.status()) ? UiTheme.RESOLVED : UiTheme.BODY;
            String text = p.text();
            int maxW = (innerR - 50) - innerL - 8;
            String shown = screen.truncate(text, maxW);
            graphics.drawString(font, shown, innerL + 4, rowY + 6, textFg, true);
            rowY += rowH;
         }
      }
   }
}
