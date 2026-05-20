package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Trade tab renderer — offer grid + offer-detail popup. Extracted
 * from {@code TownAdminScreen} in stage 15b.4.c.
 *
 * <p>State (scroll offset, selected offer id) still lives on the
 * host screen since the click + scroll handlers mutate it; the
 * renderer reads it via {@code screen.tradeGridScrollRows} and
 * {@code screen.selectedTradeOfferId}.
 */
final class TradeRenderer {

   /** Grid cell metrics for the Trade tab. Cell height accommodates:
    *   y+4  tier chip (top-right)
    *   y+6  archetype name (truncated)
    *   y+22 request item icon (16×16, centered)
    *   y+42 "×N" count line
    *   y+54 payment line
    *   y+66 expiry footnote */
   static final int TRADE_CELL_W = 96;
   static final int TRADE_CELL_H = 80;
   static final int TRADE_CELL_GAP = 8;
   /** Vertical band reserved for the header line. Offer grid begins
    *  below. Treasury rendering moved out of the Trade tab — it lives
    *  in the Resources grid now as a styled-distinct cell type. */
   static final int TRADE_GRID_TOP = 26;

   private TradeRenderer() {}

   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom) {
      Font font = screen.font();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      // Header: prestige + Trade Post count, right-aligned.
      UiText.rightFaint(graphics, font,
         "Trade Posts: " + screen.state.tradePostCount()
            + "  ·  Prestige: " + screen.state.prestige() + " / "
            + com.yucareux.townfolk.town.TownData.MAX_PRESTIGE,
         innerR, top + 10);

      var offers = screen.state.tradeOffers();
      if (offers.isEmpty()) {
         UiText.faint(graphics, font,
            "No active offers. A new traveller posts an offer roughly once a day —",
            innerL, top + TRADE_GRID_TOP + 10);
         UiText.faint(graphics, font,
            "make sure the town has items stockpiled in registered barrels so visitors know what you produce.",
            innerL, top + TRADE_GRID_TOP + 22);
         return;
      }

      int gridTop = top + TRADE_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW = innerR - innerL;
      int cols = Math.max(1, (gridW + TRADE_CELL_GAP) / (TRADE_CELL_W + TRADE_CELL_GAP));
      int gridUsed = cols * TRADE_CELL_W + (cols - 1) * TRADE_CELL_GAP;
      int gridX0 = innerL + (gridW - gridUsed) / 2;

      int totalRows = (offers.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + TRADE_CELL_GAP) / (TRADE_CELL_H + TRADE_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (screen.tradeGridScrollRows > maxScrollRow) screen.tradeGridScrollRows = maxScrollRow;
      int scrollPx = screen.tradeGridScrollRows * (TRADE_CELL_H + TRADE_CELL_GAP);

      TownAdminScreen.scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      for (int i = 0; i < offers.size(); i++) {
         int row = i / cols, col = i % cols;
         int cx = gridX0 + col * (TRADE_CELL_W + TRADE_CELL_GAP);
         int cy = gridTop + row * (TRADE_CELL_H + TRADE_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + TRADE_CELL_H < gridTop) continue;
         renderCell(screen, graphics, offers.get(i), cx, cy);
      }
      graphics.disableScissor();

      if (screen.selectedTradeOfferId != null) {
         renderOfferPopup(screen, graphics, paneL, paneR, top, bottom);
      }
   }

   private static void renderCell(TownAdminScreen screen, GuiGraphics g,
                                   TownStateUpdatePayload.TradeOfferView o, int x, int y) {
      Font font = screen.font();
      g.fill(x, y, x + TRADE_CELL_W, y + TRADE_CELL_H, UiTheme.ROW_BG);
      g.fill(x, y + TRADE_CELL_H, x + TRADE_CELL_W, y + TRADE_CELL_H + 1, UiTheme.PANEL_BORDER);

      int chipColor = switch (o.tierName()) {
         case "NOTABLE" -> 0xFF4FB04F;
         case "PREMIUM" -> 0xFFFFC847;
         default        -> 0xFFB89B70;
      };
      String chipText = switch (o.tierName()) {
         case "NOTABLE" -> "Notable";
         case "PREMIUM" -> "Premium";
         default        -> "Common";
      };
      int chipW = font.width(chipText) + 8;
      g.fill(x + TRADE_CELL_W - chipW - 4, y + 4,
             x + TRADE_CELL_W - 4, y + 14, chipColor & 0x80FFFFFF);
      g.drawString(font, chipText,
         x + TRADE_CELL_W - chipW - 2, y + 5, 0xFF111111, false);

      String archetype = UiText.truncate(font, o.archetypeName(),
         TRADE_CELL_W - chipW - 12);
      g.drawString(font, archetype, x + 4, y + 5, UiTheme.BODY, true);

      var stack = screen.stackForItemId(o.requestItemId());
      int iconX = x + (TRADE_CELL_W - 16) / 2;
      g.renderItem(stack, iconX, y + 22);

      String count = "× " + o.requestCount();
      int countW = font.width(count);
      g.drawString(font, count, x + (TRADE_CELL_W - countW) / 2, y + 42,
         UiTheme.HEADING, true);

      String pay = o.paymentEmeralds() + " emeralds";
      int payW = font.width(pay);
      g.drawString(font, pay, x + (TRADE_CELL_W - payW) / 2, y + 54,
         UiTheme.BODY, true);

      String exp = o.daysRemaining() <= 0 ? "expires today"
                 : o.daysRemaining() == 1 ? "1 day left"
                 : o.daysRemaining() + " days left";
      int expW = font.width(exp);
      g.drawString(font, exp, x + (TRADE_CELL_W - expW) / 2, y + 66,
         o.daysRemaining() <= 2 ? UiTheme.ERROR : UiTheme.FAINT, true);
   }

   private static void renderOfferPopup(TownAdminScreen screen, GuiGraphics g,
                                         int paneL, int paneR, int top, int bottom) {
      var offer = findTradeOffer(screen, screen.selectedTradeOfferId);
      if (offer == null) { screen.selectedTradeOfferId = null; return; }
      Font font = screen.font();

      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
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
         offer.archetypeName() + " — " + offer.tierName().toLowerCase(Locale.ROOT),
         x, y);
      y += 14;

      var stack = screen.stackForItemId(offer.requestItemId());
      g.renderItem(stack, x, y);
      g.drawString(font,
         offer.requestCount() + "× " + TownAdminScreen.shortItemName(offer.requestItemId()),
         x + 22, y + 4, UiTheme.BODY, true);
      y += 24;

      g.drawString(font,
         "Payment: " + offer.paymentEmeralds() + " emeralds",
         x, y, UiTheme.HEADING, true);
      y += 12;

      String exp = offer.daysRemaining() <= 0 ? "expires today"
                 : offer.daysRemaining() == 1 ? "1 day left to deliver"
                 : offer.daysRemaining() + " days left to deliver";
      g.drawString(font, exp, x, y,
         offer.daysRemaining() <= 2 ? UiTheme.ERROR : UiTheme.MUTED, true);
      y += 12;

      g.drawString(font, "Drawn from town storage. Pays into treasury.",
         x, y, UiTheme.FAINT, true);
      y += 14;

      String blurb = offer.flavorBlurb();
      if (blurb != null && !blurb.isBlank()) {
         for (var line : font.split(Component.literal(blurb), popW - 28)) {
            if (y > popY + popH - 40) break;
            g.drawString(font, line, x, y, UiTheme.FAINT, true);
            y += 10;
         }
      }

      int btnY = popY + popH - 24;
      drawButton(g, font, popX + 14, btnY, 110, "Deliver", true);
      drawButton(g, font, popX + popW - 14 - 80, btnY, 80, "Close", false);
   }

   /** Trade-modal chip button — green for the primary action,
    *  warm-grey for cancel/dismiss. Shared with other tabs' modal
    *  popups (Tasks, Parcels) so the visual language stays consistent. */
   static void drawButton(GuiGraphics g, Font font, int x, int y, int w,
                           String label, boolean primary) {
      g.fill(x, y, x + w, y + 18, primary ? UiTheme.CHIP_PRIMARY_BG : UiTheme.CHIP_NEUTRAL_BG);
      g.fill(x, y + 18, x + w, y + 19, primary ? UiTheme.CHIP_PRIMARY_BORDER : UiTheme.PANEL_BORDER);
      int lw = font.width(label);
      g.drawString(font, label, x + (w - lw) / 2, y + 5,
         primary ? UiTheme.CHIP_PRIMARY_FG : UiTheme.BODY, true);
   }

   /** Hit-test the trade-offer popup's buttons. Returns "deliver",
    *  "close", or null. Coordinates are LOGICAL (post-scaledMouse). */
   static String hitTestPopupButton(double mouseX, double mouseY,
                                     int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
      int popH = 180;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;
      int btnY = popY + popH - 24;
      if (mouseY < btnY || mouseY >= btnY + 18) return null;
      int deliverX = popX + 14;
      if (mouseX >= deliverX && mouseX < deliverX + 110) return "deliver";
      int closeX = popX + popW - 14 - 80;
      if (mouseX >= closeX && mouseX < closeX + 80) return "close";
      return null;
   }

   /** Hit-test the trade grid cells. Returns the offer id or null. */
   static String hitTestCell(TownAdminScreen screen, double mouseX, double mouseY,
                              int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + TRADE_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW = innerR - innerL;
      int cols = Math.max(1, (gridW + TRADE_CELL_GAP) / (TRADE_CELL_W + TRADE_CELL_GAP));
      int gridUsed = cols * TRADE_CELL_W + (cols - 1) * TRADE_CELL_GAP;
      int gridX0 = innerL + (gridW - gridUsed) / 2;
      int scrollPx = screen.tradeGridScrollRows * (TRADE_CELL_H + TRADE_CELL_GAP);
      var offers = screen.state.tradeOffers();
      for (int i = 0; i < offers.size(); i++) {
         int row = i / cols, col = i % cols;
         int cx = gridX0 + col * (TRADE_CELL_W + TRADE_CELL_GAP);
         int cy = gridTop + row * (TRADE_CELL_H + TRADE_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + TRADE_CELL_H < gridTop) continue;
         if (mouseX >= cx && mouseX < cx + TRADE_CELL_W
             && mouseY >= Math.max(cy, gridTop)
             && mouseY < Math.min(cy + TRADE_CELL_H, gridBot)) {
            return offers.get(i).id();
         }
      }
      return null;
   }

   static TownStateUpdatePayload.TradeOfferView findTradeOffer(TownAdminScreen screen, String id) {
      if (id == null) return null;
      for (var o : screen.state.tradeOffers()) if (id.equals(o.id())) return o;
      return null;
   }
}
