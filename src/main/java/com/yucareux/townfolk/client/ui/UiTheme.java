package com.yucareux.townfolk.client.ui;

/**
 * Semantic colour + layout constants for the Town Square admin UI.
 *
 * Use these names — not raw hex literals — everywhere a colour is drawn.
 * If we redesign the theme later (light/dark, high-contrast, etc.) only
 * this file changes. Hex values mirror the original coffee-toned palette
 * from {@code TownAdminScreen}.
 */
public final class UiTheme {

   // ── surfaces ──
   public static final int PANEL_BG     = 0xEE1A130E;   // outer panel fill
   public static final int PANEL_BORDER = 0xFF8C6E3D;   // outer panel + tab underline
   public static final int HEADER_BG    = 0xFF2C1F14;   // title strip
   public static final int TAB_ACTIVE   = 0xFF3D2D1E;
   public static final int ROW_BG       = 0xFF22180F;
   public static final int ROW_BG_HOVER = 0xFF3D2D1E;

   // ── text ──
   public static final int HEADING  = 0xFFFFD27A;   // accents, headings, hot values
   public static final int BODY     = 0xFFEDE0C2;   // primary text
   public static final int MUTED    = 0xFFB89B70;   // secondary text
   public static final int FAINT    = 0xFF7A6849;   // tertiary / placeholders
   public static final int OK       = 0xFF7AB46A;
   public static final int BAD      = 0xFFC76A50;

   // ── layout ──
   public static final int PADDING       = 14;
   public static final int GAP_SMALL     = 4;
   public static final int GAP_MEDIUM    = 6;
   public static final int GAP_LARGE     = 10;

   public static final int LINE_HEIGHT   = 10;
   public static final int ROW_HEIGHT_SM = 22;
   public static final int ROW_HEIGHT_MD = 38;

   public static final int CARD_HEIGHT   = 48;
   public static final int TAB_HEIGHT    = 14;
   public static final int TAB_GAP       = 4;

   private UiTheme() {}
}
