package com.yucareux.townfolk.client.ui;

/**
 * Semantic colour + layout constants for the Townfolk UI.
 *
 * <p>Use these names — not raw hex literals — everywhere a colour is
 * drawn. If we redesign the theme later (light/dark, high-contrast,
 * accessibility tweak) only this file changes. Hex values mirror the
 * original coffee-toned palette from {@code TownAdminScreen}.
 *
 * <p>Stage 15b consolidation: prior to this pass, {@code TownAdminScreen}
 * held its own private copy of every colour constant. They're now
 * aliased here; the per-screen names continue to work but resolve
 * through this file.
 */
public final class UiTheme {

   // ── Surfaces ──
   public static final int PANEL_BG     = 0xEE1A130E;   // outer panel fill
   public static final int PANEL_BORDER = 0xFF8C6E3D;   // outer panel + tab underline
   public static final int HEADER_BG    = 0xFF2C1F14;   // title strip
   public static final int TAB_ACTIVE   = 0xFF3D2D1E;   // active tab chip
   public static final int ROW_BG       = 0xFF22180F;
   public static final int ROW_BG_HOVER = 0xFF3D2D1E;

   // ── Text ──
   public static final int HEADING  = 0xFFFFD27A;   // gold accent — headings, hot values
   public static final int BODY     = 0xFFEDE0C2;   // primary text
   public static final int MUTED    = 0xFFB89B70;   // secondary text
   public static final int FAINT    = 0xFF7A6849;   // tertiary / placeholders
   public static final int OK       = 0xFF7AB46A;
   public static final int BAD      = 0xFFC76A50;
   /** Validation / error text. Alias for BAD with a distinct name so
    *  call sites that mean "something went wrong" don't reuse the
    *  livestock-task BAD constant. */
   public static final int ERROR    = 0xFFC76A50;
   /** "Resolved" — pinned-fact / closed-todo styling. */
   public static final int RESOLVED = 0xFF7AB46A;

   // ── Chip palette (trade-popup-style buttons) ──
   //
   // Primary (green) for the affirmative action — Save, Apply, Deliver.
   // Neutral (warm grey) for cancel / dismiss / secondary action.
   // FG names are the text colour drawn on top of the BG.
   public static final int CHIP_PRIMARY_BG     = 0xFF3C5A22;
   public static final int CHIP_PRIMARY_BORDER = 0xFF6FA445;
   public static final int CHIP_PRIMARY_FG     = 0xFFE8FFD2;
   public static final int CHIP_NEUTRAL_BG     = 0xFF2A2018;
   public static final int CHIP_NEUTRAL_BORDER = 0xFFB89B70;
   public static final int CHIP_NEUTRAL_FG     = 0xFFEDE0C2;

   // ── Treasury cells (Resources tab) ──
   public static final int TREASURY_CELL_BG     = 0x40FFC847;
   public static final int TREASURY_CELL_BORDER = 0xFFFFC847;

   // ── Layout ──
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
