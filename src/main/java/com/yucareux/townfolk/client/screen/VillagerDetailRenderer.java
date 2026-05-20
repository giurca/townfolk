package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiBar;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * VILLAGER_DETAIL render pipeline — extracted from {@code TownAdminScreen}
 * in Stage 15b.3 so the host screen's monolithic file isn't carrying
 * 370+ lines of tab-renderer code. Static methods only; the host
 * screen passes itself in as the first argument and the renderer
 * reaches back through package-private accessors for state.
 *
 * <p>Init code (widget creation via {@code addRenderableWidget}) stays
 * on {@code TownAdminScreen} because that path needs the protected
 * Screen API. Render-only is what moved here.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>Header band — {@link #DETAIL_HEADER_H} px. Profession icon,
 *       name, status pills, HP + Hunger bars, LLM cost.
 *   <li>Tab strip — {@link #DETAIL_TAB_STRIP_H} px. 7 chip-styled
 *       tabs with hover and active states. Rects cached in
 *       {@link #DETAIL_TAB_RECTS} for click hit-testing.
 *   <li>Tab body — varies by active tab; fills the rest above the
 *       footer.
 *   <li>Footer — {@link #DETAIL_FOOTER_H} px reserved for the back /
 *       open-dialogue / remove buttons (still owned by the host
 *       screen's init code).
 * </ul>
 */
final class VillagerDetailRenderer {

   public static final int DETAIL_HEADER_H = 56;
   public static final int DETAIL_TAB_STRIP_H = 18;
   public static final int DETAIL_FOOTER_H = 26;

   /** Y offset inside the Memories tab where the scrollable list
    *  starts. Header band (beliefs preview + stats line) takes the
    *  first DETAIL_MEMORIES_LIST_TOP pixels. */
   public static final int DETAIL_MEMORIES_LIST_TOP = 60;

   /** Per-tab x rects populated during {@link #renderTabStrip} so
    *  {@link #hitTestTabs} can map clicks back. Static so the host
    *  screen's mouseClicked can reach it without an instance. */
   private static final java.util.EnumMap<TownAdminScreen.DetailTab, int[]> DETAIL_TAB_RECTS =
      new java.util.EnumMap<>(TownAdminScreen.DetailTab.class);

   /** Y bounds of each rendered parcel row inside the Detail Parcels
    *  tab. Populated by {@link #renderParcelsBody}, consumed by
    *  {@link #hitTestParcelRow}. */
   private static final LinkedHashMap<String, int[]> DETAIL_PARCEL_ROW_RECTS =
      new LinkedHashMap<>();

   private VillagerDetailRenderer() {}

   // ─────────────────────── Entry point ───────────────────────

   /** Render the full villager-detail body — header, tab strip,
    *  active tab content. Called from {@code TownAdminScreen}'s
    *  render dispatch for {@code Mode.VILLAGER_DETAIL}. */
   static void render(TownAdminScreen screen, GuiGraphics graphics,
                      int paneL, int paneR, int top, int bottom,
                      int mouseX, int mouseY) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = screen.findVillager(screen.selectedVillager);
      if (match.isEmpty()) return;
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      // ── 1. Persistent header. ──
      renderHeader(screen, graphics, v, innerL, innerR, top);

      // ── 2. Tab strip. ──
      int tabsY = top + DETAIL_HEADER_H;
      renderTabStrip(screen, graphics, innerL, innerR, tabsY, mouseX, mouseY);

      // ── 3. Tab body. ──
      int bodyTop = tabsY + DETAIL_TAB_STRIP_H + 4;
      int bodyBottom = bottom - DETAIL_FOOTER_H;
      switch (screen.detailTab) {
         case OVERVIEW      -> renderOverviewBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case BACKSTORY     -> renderBackstoryBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case MEMORIES      -> renderMemoriesBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case TODOS         -> renderTodosBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case PARCELS       -> renderParcelsBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case RELATIONSHIPS -> renderRelationshipsBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case ACTIONS       -> renderActionsBody(screen, graphics, v, innerL, innerR, bodyTop, bodyBottom);
      }
   }

   // ─────────────────────── Header ───────────────────────

   private static void renderHeader(TownAdminScreen screen, GuiGraphics g,
                                     TownStateUpdatePayload.VillagerSummary v,
                                     int innerL, int innerR, int top) {
      Font font = screen.font();

      // Profession icon (16×16) on the left, name beside it.
      g.renderItem(screen.iconForProfession(v.profession()), innerL, top);
      // Stage 18a: name gains a gender glyph prefix and age-category
      // suffix when the villager isn't a default-adult. Glyph carries
      // a colour hint so the strip reads at-a-glance.
      var gender = com.yucareux.townfolk.villager.Gender.fromWire(v.gender());
      var ageCat = com.yucareux.townfolk.villager.AgeCategory.fromDays(v.ageDays());
      int genderColor = gender == com.yucareux.townfolk.villager.Gender.FEMALE
         ? 0xFFE89AC0 : 0xFF7AB0E0;
      g.drawString(font, gender.glyph(), innerL + 22, top + 2, genderColor, true);
      int nameX = innerL + 22 + font.width(gender.glyph()) + 4;
      String name = v.name() + (v.alive() ? "" : " ✝");
      g.drawString(font,
         Component.literal(name).withStyle(ChatFormatting.GOLD),
         nameX, top + 2, UiTheme.HEADING, true);
      String profLine = (v.profession() == null || v.profession().isBlank() || "none".equals(v.profession())
                       ? "(unemployed)" : v.profession())
                     + (v.role() == null || v.role().isBlank() || "resident".equals(v.role())
                        ? "" : " · " + v.role())
                     + " · " + ageCat.displayLabel() + " (" + v.ageDays() + "d)";
      g.drawString(font, profLine, innerL + 22, top + 12, UiTheme.MUTED, true);

      // Status pills row.
      int pillX = innerL + 22;
      pillX = drawStatusPill(font, g, pillX, top + 22, TownAdminScreen.prettifyActivity(v.activity()), UiTheme.FAINT);
      if (v.playerSetHome()) pillX = drawStatusPill(font, g, pillX, top + 22, "⌂ home", UiTheme.MUTED);
      if (v.playerSetJob())  pillX = drawStatusPill(font, g, pillX, top + 22, "⚒ job",  UiTheme.MUTED);
      if (v.hunger() < 50)   pillX = drawStatusPill(font, g, pillX, top + 22, "hungry", 0xFFD55050);

      // Vitals on the right — HP bar, hunger bar, label stacked vertically.
      int vBarW = 80;
      int vBarX = innerR - vBarW;
      int hpY = top + 2;
      int hgY = top + 12;
      UiBar.draw(g, vBarX, hpY, vBarW, 5, v.health(), v.maxHealth());
      g.drawString(font, String.format(Locale.ROOT, "HP %.0f/%.0f", v.health(), v.maxHealth()),
         vBarX - font.width("HP 99/99") - 6, hpY - 1, UiTheme.FAINT, true);
      int hungerFill = (int) Math.round(vBarW * (v.hunger() / 100.0));
      int hungerColor = v.hunger() >= 70 ? 0xFF6FA445
                       : v.hunger() >= 40 ? 0xFFE0B040
                       :                    0xFFD55050;
      g.fill(vBarX, hgY, vBarX + vBarW, hgY + 5, 0xFF2A1F15);
      g.fill(vBarX, hgY, vBarX + hungerFill, hgY + 5, hungerColor);
      g.drawString(font, "Hunger " + v.hunger() + "/100",
         vBarX - font.width("Hunger 100/100") - 6, hgY - 1, UiTheme.FAINT, true);
      String metric = String.format(Locale.ROOT,
         "%d calls • %d+%d tok • $%.4f", v.llmCalls(),
         v.inputTokens(), v.outputTokens(), v.estCostUsd());
      g.drawString(font, metric, innerR - font.width(metric), top + 22, UiTheme.FAINT, true);

      // Underline under the header band.
      g.fill(innerL, top + DETAIL_HEADER_H - 4, innerR, top + DETAIL_HEADER_H - 3, UiTheme.PANEL_BORDER);
   }

   private static int drawStatusPill(Font font, GuiGraphics g, int x, int y, String text, int fg) {
      int w = font.width(text) + 8;
      g.fill(x, y, x + w, y + 11, 0x802A1F15);
      g.drawString(font, text, x + 4, y + 2, fg, true);
      return x + w + 4;
   }

   // ─────────────────────── Tab strip ───────────────────────

   private static void renderTabStrip(TownAdminScreen screen, GuiGraphics g,
                                       int innerL, int innerR, int y,
                                       int mouseX, int mouseY) {
      Font font = screen.font();
      TownAdminScreen.DetailTab[] tabs = TownAdminScreen.DetailTab.values();
      int cx = innerL;
      for (TownAdminScreen.DetailTab t : tabs) {
         String label = tabLabel(t);
         int w = font.width(label) + 12;
         boolean active = t == screen.detailTab;
         boolean hovered = mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + DETAIL_TAB_STRIP_H;
         int bg = active ? UiTheme.TAB_ACTIVE : (hovered ? UiTheme.ROW_BG : 0x00000000);
         if (bg != 0) g.fill(cx, y, cx + w, y + DETAIL_TAB_STRIP_H, bg);
         int textColor = active ? UiTheme.HEADING : (hovered ? UiTheme.BODY : UiTheme.MUTED);
         g.drawString(font, label, cx + 6, y + 5, textColor, true);
         if (active) g.fill(cx, y + DETAIL_TAB_STRIP_H, cx + w, y + DETAIL_TAB_STRIP_H + 1, UiTheme.HEADING);
         DETAIL_TAB_RECTS.put(t, new int[]{cx, y, w, DETAIL_TAB_STRIP_H});
         cx += w + 2;
      }
   }

   static String tabLabel(TownAdminScreen.DetailTab t) {
      return switch (t) {
         case OVERVIEW      -> "Overview";
         case BACKSTORY     -> "Backstory";
         case MEMORIES      -> "Memories";
         case TODOS         -> "Todos";
         case PARCELS       -> "Parcels";
         case RELATIONSHIPS -> "Relations";
         case ACTIONS       -> "Actions";
      };
   }

   /** Returns the tab under the cursor, or null. Called from
    *  {@code TownAdminScreen}'s mouseClicked. */
   static TownAdminScreen.DetailTab hitTestTabs(double mouseX, double mouseY) {
      for (var e : DETAIL_TAB_RECTS.entrySet()) {
         int[] r = e.getValue();
         if (mouseX >= r[0] && mouseX < r[0] + r[2]
             && mouseY >= r[1] && mouseY < r[1] + r[3]) {
            return e.getKey();
         }
      }
      return null;
   }

   /** Returns the parcel id under the cursor on the Parcels tab, or
    *  null. Called from {@code TownAdminScreen}'s mouseClicked. */
   static String hitTestParcelRow(double mouseX, double mouseY) {
      for (var e : DETAIL_PARCEL_ROW_RECTS.entrySet()) {
         int[] r = e.getValue();
         if (mouseX >= r[0] && mouseX < r[0] + r[2]
             && mouseY >= r[1] && mouseY < r[1] + r[3]) {
            return e.getKey();
         }
      }
      return null;
   }

   // ─────────────────────── Tab bodies ───────────────────────

   private static void renderOverviewBody(TownAdminScreen screen, GuiGraphics g,
                                           TownStateUpdatePayload.VillagerSummary v,
                                           int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      int y = top;
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      int plantParcels = 0, animalParcels = 0;
      for (var p : screen.state.parcels()) {
         if (v.uuid().equals(p.ownerUuid())) {
            if ("PLANT".equals(p.type())) plantParcels++;
            else if ("ANIMAL".equals(p.type())) animalParcels++;
         }
      }

      g.drawString(font, "At a glance", innerL, y, UiTheme.MUTED, true);
      y += 14;
      g.drawString(font,
         "• Parcels: " + (plantParcels + animalParcels)
            + " (" + plantParcels + " plant · " + animalParcels + " animal)",
         innerL, y, UiTheme.BODY, true); y += 11;
      g.drawString(font,
         "• Open todos: " + openTodos,
         innerL, y, openTodos > 0 ? UiTheme.ERROR : UiTheme.BODY, true); y += 11;
      g.drawString(font,
         "• Memories: " + v.memoryCount()
            + " (last compacted day " + v.lastCompactedDay() + ")",
         innerL, y, UiTheme.BODY, true); y += 11;
      g.drawString(font,
         "• Pinned facts: " + v.pinnedFacts().size() + " / 100",
         innerL, y, UiTheme.BODY, true); y += 11;
      g.drawString(font,
         "• Inventory: " + v.inventory().size() + " stacks",
         innerL, y, UiTheme.BODY, true); y += 14;

      g.drawString(font, "Carrying", innerL, y, UiTheme.MUTED, true);
      y += 12;
      if (v.inventory().isEmpty()) {
         g.drawString(font, "(empty bag)", innerL, y, UiTheme.FAINT, true);
      } else {
         int slotX = innerL;
         int rendered = 0;
         for (var ic : v.inventory()) {
            if (rendered >= 12) break;
            var stack = screen.stackForItemId(ic.itemId());
            g.renderItem(stack, slotX, y);
            String count = String.valueOf(ic.count());
            g.drawString(font, count, slotX + 18 - font.width(count), y + 8, UiTheme.FAINT, true);
            slotX += 26;
            rendered++;
         }
         if (v.inventory().size() > 12) {
            g.drawString(font, "+" + (v.inventory().size() - 12) + " more",
               slotX, y + 4, UiTheme.FAINT, true);
         }
      }
   }

   private static void renderBackstoryBody(TownAdminScreen screen, GuiGraphics g,
                                            TownStateUpdatePayload.VillagerSummary v,
                                            int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      g.drawString(font, "Persona seed (informs the LLM-generated backstory below)",
         innerL, top, UiTheme.MUTED, true);
      int storyTop = top + 110;
      g.drawString(font, "Backstory", innerL, storyTop, UiTheme.MUTED, true);
      String bs = v.backstory() == null || v.backstory().isBlank() ? "(none yet)" : v.backstory();
      List<FormattedCharSequence> wrapped = font.split(Component.literal(bs), innerR - innerL);
      int by = storyTop + 12;
      for (FormattedCharSequence seq : wrapped) {
         if (by + font.lineHeight > bottom) break;
         g.drawString(font, seq, innerL, by, UiTheme.BODY, true);
         by += font.lineHeight + 2;
      }
   }

   private static void renderMemoriesBody(TownAdminScreen screen, GuiGraphics g,
                                           TownStateUpdatePayload.VillagerSummary v,
                                           int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      g.drawString(font, "Beliefs (auto-updated nightly)", innerL, top, UiTheme.MUTED, true);
      String beliefs = v.beliefs() == null || v.beliefs().isBlank() ? "(none yet)" : v.beliefs();
      List<FormattedCharSequence> beliefLines = font.split(Component.literal(beliefs), innerR - innerL - 110);
      int by = top + 12;
      int beliefsMax = 2;
      for (int i = 0; i < beliefLines.size() && i < beliefsMax; i++) {
         g.drawString(font, beliefLines.get(i), innerL, by, UiTheme.BODY, true);
         by += font.lineHeight + 1;
      }
      if (beliefLines.size() > beliefsMax) {
         g.drawString(font, "… (" + (beliefLines.size() - beliefsMax) + " more lines — Expand)",
            innerL, by, UiTheme.FAINT, true);
      }

      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      String stats = String.format(Locale.ROOT,
         "memories: %d • compacted day %d • todos: %d open • pins: %d/100",
         v.memoryCount(), v.lastCompactedDay(), openTodos, v.pinnedFacts().size());
      g.drawString(font, stats, innerL, top + 48, UiTheme.FAINT, true);

      int listTop = top + DETAIL_MEMORIES_LIST_TOP;
      int listBottom = bottom - 30;
      int rowH = 22;
      TownAdminScreen.scaledScissor(g, innerL, listTop, innerR, listBottom);
      int y = listTop - screen.detailTabScroll;
      g.drawString(font,
         "Pinned facts (" + v.pinnedFacts().size() + "/100)", innerL, y, UiTheme.MUTED, true);
      y += 12;
      if (v.pinnedFacts().isEmpty()) {
         g.drawString(font, "(none — add below to lock in long-term memories)",
            innerL, y, UiTheme.FAINT, true);
      } else {
         for (TownStateUpdatePayload.PinSummary p : v.pinnedFacts()) {
            if (y + rowH < listTop) { y += rowH; continue; }
            if (y > listBottom)     break;
            g.fill(innerL, y, innerR - 50, y + rowH - 2, UiTheme.ROW_BG);
            int textFg = "resolved".equals(p.status()) ? UiTheme.RESOLVED : UiTheme.BODY;
            String shown = screen.truncate(p.text(), (innerR - 50) - innerL - 8);
            g.drawString(font, shown, innerL + 4, y + 3, textFg, true);
            String tag = "resolved".equals(p.status()) ? "resolved" : ("day " + p.createdDay());
            g.drawString(font, tag, innerL + 4, y + 13, UiTheme.FAINT, true);
            y += rowH;
         }
      }
      g.disableScissor();
   }

   private static void renderTodosBody(TownAdminScreen screen, GuiGraphics g,
                                        TownStateUpdatePayload.VillagerSummary v,
                                        int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      g.drawString(font, "Open commitments (" + openTodos + ")", innerL, top, UiTheme.MUTED, true);
      if (openTodos == 0) {
         g.drawString(font, "(nothing on their plate)", innerL, top + 14, UiTheme.FAINT, true);
         return;
      }
      int listTop = top + 14;
      int rowH = 22;
      TownAdminScreen.scaledScissor(g, innerL, listTop, innerR, bottom);
      int y = listTop - screen.detailTabScroll;
      for (var t : v.todos()) {
         if (!"open".equals(t.status())) continue;
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > bottom)         break;
         g.fill(innerL, y, innerR - 50, y + rowH - 2, UiTheme.ROW_BG);
         String txt = screen.truncate(t.text(), innerR - 50 - innerL - 8);
         g.drawString(font, txt, innerL + 4, y + 3, UiTheme.BODY, true);
         g.drawString(font, "day " + t.createdDay(), innerL + 4, y + 13, UiTheme.FAINT, true);
         y += rowH;
      }
      g.disableScissor();
   }

   private static void renderParcelsBody(TownAdminScreen screen, GuiGraphics g,
                                          TownStateUpdatePayload.VillagerSummary v,
                                          int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      DETAIL_PARCEL_ROW_RECTS.clear();
      g.drawString(font, "Owned parcels — click a row to open the planner",
         innerL, top, UiTheme.MUTED, true);
      int y = top + 14;
      int count = 0;
      int rowH = 22;
      var mc = screen.minecraft();
      double mxd = mc.mouseHandler.xpos()
         * (double) mc.getWindow().getGuiScaledWidth()
         / (double) mc.getWindow().getScreenWidth();
      double myd = mc.mouseHandler.ypos()
         * (double) mc.getWindow().getGuiScaledHeight()
         / (double) mc.getWindow().getScreenHeight();
      for (var p : screen.state.parcels()) {
         if (!v.uuid().equals(p.ownerUuid())) continue;
         if (y + rowH > bottom) break;
         count++;
         boolean hovered = mxd >= innerL && mxd < innerR && myd >= y && myd < y + rowH - 2;
         g.fill(innerL, y, innerR, y + rowH - 2, hovered ? UiTheme.TAB_ACTIVE : UiTheme.ROW_BG);
         net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
         String head = p.type() + " · " + p.sizeX() + "×" + p.sizeZ() + " @ " + centre.toShortString();
         g.drawString(font, head, innerL + 4, y + 3, UiTheme.BODY, true);
         g.drawString(font, TownAdminScreen.parcelSnapshotText(p), innerL + 4, y + 13, UiTheme.FAINT, true);
         String hint = "PLANT".equals(p.type()) ? "Open crop plan ▸" : "Open animal plan ▸";
         g.drawString(font, hint, innerR - font.width(hint) - 4, y + 8, UiTheme.HEADING, true);
         DETAIL_PARCEL_ROW_RECTS.put(p.id(), new int[]{innerL, y, innerR - innerL, rowH - 2});
         y += rowH;
      }
      if (count == 0) {
         g.drawString(font, "(no parcels owned — use the Surveyor's Stake to mark land)",
            innerL, y, UiTheme.FAINT, true);
      }
   }

   private static void renderRelationshipsBody(TownAdminScreen screen, GuiGraphics g,
                                                TownStateUpdatePayload.VillagerSummary v,
                                                int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      g.drawString(font, "Relationships (built from tavern banter)",
         innerL, top, UiTheme.MUTED, true);

      var rels = v.relationships();
      if (rels == null || rels.isEmpty()) {
         g.drawString(font, "(no relationships yet — visit a tavern to start mingling)",
            innerL, top + 16, UiTheme.FAINT, true);
         return;
      }

      int y = top + 16;
      int rowH = 22;
      long today = screen.minecraft().level == null ? 0L
         : screen.minecraft().level.getGameTime() / 24000L;
      for (var rel : rels) {
         if (y + rowH > bottom) break;
         g.fill(innerL, y, innerR - 4, y + rowH - 2, UiTheme.ROW_BG);
         // Tier chip (left): coloured pill + label.
         String tierLabel = tierDisplayLabel(rel.tier());
         int chipW = font.width(tierLabel) + 10;
         int chipColor = tierColor(rel.tier());
         g.fill(innerL + 4, y + 4, innerL + 4 + chipW, y + rowH - 6, chipColor & 0x80FFFFFF);
         g.drawString(font, tierLabel, innerL + 8, y + 6, chipColor, true);
         // Name + banter count.
         int nameX = innerL + chipW + 14;
         g.drawString(font, rel.otherName(), nameX, y + 3, UiTheme.BODY, true);
         g.drawString(font, rel.banterCount() + " banter"
                                + (rel.banterCount() == 1 ? "" : "s"),
            nameX, y + 13, UiTheme.FAINT, true);
         // Right-side: last-day pill.
         long daysAgo = today - rel.lastBanterDay();
         String when = daysAgo < 0 ? "today"
                     : daysAgo == 0 ? "today"
                     : daysAgo == 1 ? "1 day ago"
                     : daysAgo + " days ago";
         int whenW = font.width(when);
         g.drawString(font, when, innerR - whenW - 8, y + 8, UiTheme.MUTED, true);
         y += rowH;
      }
   }

   /** Tier-name (wire string) → display label. */
   private static String tierDisplayLabel(String tier) {
      return switch (tier) {
         case "CLOSE"        -> "close";
         case "FRIEND"       -> "friend";
         case "ACQUAINTANCE" -> "acquaintance";
         case "RIVAL"        -> "rival";
         default              -> "stranger";
      };
   }

   /** Tier-name (wire string) → colour hint. Matches AffinityRecord.Tier.color
    *  on the server side — duplicated here because the wire format
    *  ships the tier name not the enum. */
   private static int tierColor(String tier) {
      return switch (tier) {
         case "CLOSE"        -> 0xFFFFD27A;     // gold
         case "FRIEND"       -> 0xFF7AB46A;     // green
         case "ACQUAINTANCE" -> 0xFFB89B70;     // muted
         case "RIVAL"        -> 0xFFC76A50;     // red
         default              -> 0xFF7A6849;     // faint
      };
   }

   private static void renderActionsBody(TownAdminScreen screen, GuiGraphics g,
                                          TownStateUpdatePayload.VillagerSummary v,
                                          int innerL, int innerR, int top, int bottom) {
      Font font = screen.font();
      g.drawString(font, "Admin actions", innerL, top, UiTheme.MUTED, true);
      g.drawString(font,
         "Use these levers carefully — most are server-authoritative and instant.",
         innerL, top + 50, UiTheme.FAINT, true);
      g.drawString(font,
         "More levers (rename, clear home/job, manual leisure override) coming as server actions are added.",
         innerL, top + 62, UiTheme.FAINT, true);
   }
}
