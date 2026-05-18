package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiBar;
import com.yucareux.townfolk.client.ui.UiCard;
import com.yucareux.townfolk.client.ui.UiList;
import com.yucareux.townfolk.client.ui.UiPill;
import com.yucareux.townfolk.client.ui.UiTabs;
import com.yucareux.townfolk.client.ui.UiText;
import com.yucareux.townfolk.client.ui.UiTheme;
import com.yucareux.townfolk.network.AdminActionPayload;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Right-click-the-Town-Square admin dashboard.
 *
 *   Tabs:    Town  /  Villagers
 *   Modes:   NORMAL, SPAWN_FORM, VILLAGER_DETAIL, MEMORIES
 *
 * The Memories mode shows a villager's pinned facts, beliefs blob, and
 * recent-event count. The Town tab also lists town-shared pinned facts.
 */
public final class TownAdminScreen extends Screen {

   private static final int PANEL_BG       = 0xEE1A130E;
   private static final int PANEL_BORDER   = 0xFF8C6E3D;
   private static final int HEADER_BG      = 0xFF2C1F14;
   private static final int TAB_ACTIVE_BG  = 0xFF3D2D1E;
   private static final int ROW_BG         = 0xFF22180F;
   private static final int ROW_BG_HOVER   = 0xFF3D2D1E;
   private static final int FG_PRIMARY     = 0xFFEDE0C2;
   private static final int FG_ACCENT      = 0xFFFFD27A;
   private static final int FG_DIM         = 0xFFB89B70;
   private static final int FG_FAINT       = 0xFF7A6849;
   private static final int FG_ERROR       = 0xFFC76A50;
   private static final int FG_RESOLVED    = 0xFF7AB46A;

   private static final int PADDING = 14;

   /** Render the whole admin panel at this scale relative to vanilla GUI
    *  units. 0.66 effectively gives us ~1.5× more logical room without
    *  changing the panel's physical footprint on screen — text/charts
    *  shrink, density doubles. Vanilla widgets (Button, EditBox) render
    *  through {@code super.render} inside the scaled pose too so their
    *  positions track the rest of the layout; mouse handlers convert
    *  physical → logical so hit-testing still matches widget bounds. */
   public static final float CONTENT_SCALE = 0.66f;
   public static final float INV_CONTENT_SCALE = 1.0f / CONTENT_SCALE;

   /** Apply scissor in the CURRENT scaled coordinate frame.
    *
    *  Root cause this exists to fix: {@link GuiGraphics#enableScissor}
    *  drives the GL scissor directly, in raw framebuffer pixels. It
    *  ignores the current PoseStack. So if the rest of the UI renders
    *  through a {@code pose.scale(0.66, 0.66, 1.0)} stack (which it
    *  does — see {@link #render}), passing logical coords to
    *  enableScissor gets you a scissor box ~50% larger than the
    *  content it's supposed to clip. The visible symptom is content
    *  clipped along its top edge, with only the bottom slivers
    *  surviving inside the (un-scaled) scissor rect.
    *
    *  This helper multiplies the logical coords by {@link #CONTENT_SCALE}
    *  so the GL scissor lands on exactly the framebuffer pixels the
    *  scaled content occupies. */
   public static void scaledScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
      // Transform the corners through the live pose stack so the
      // scissor lands on the actual physical pixels the scaled
      // content occupies — works for any scale / translate, not just
      // our 0.66 case.
      var m = g.pose().last().pose();
      org.joml.Vector4f tl = new org.joml.Vector4f(x1, y1, 0f, 1f).mul(m);
      org.joml.Vector4f br = new org.joml.Vector4f(x2, y2, 0f, 1f).mul(m);
      g.enableScissor(
         Math.round(Math.min(tl.x, br.x)),
         Math.round(Math.min(tl.y, br.y)),
         Math.round(Math.max(tl.x, br.x)),
         Math.round(Math.max(tl.y, br.y)));
   }

   private enum Tab { OVERVIEW, VILLAGERS, RESOURCES, PARCELS, TASKS, LOG }
   private enum Mode { NORMAL, SPAWN_FORM, VILLAGER_DETAIL, MEMORIES, BELIEFS_FULL, VILLAGER_LOG }

   private TownStateUpdatePayload state;
   private Tab tab = Tab.OVERVIEW;

   /** Toolkit-backed lists. Lazily reconstructed in {@link #ensureListsForBounds}
    *  so resizing the screen rebinds bounds without losing scroll position
    *  between frames. */
   private UiList<TownStateUpdatePayload.VillagerSummary> villagersList;
   // ── New for redesigned tabs ──
   /** Row-level scroll offset for the Resources grid. Driven by the
    *  mouse wheel; clamped at render time once the row count is known. */
   private int resourcesGridScrollRows = 0;
   private UiList<TownStateUpdatePayload.ResourceLoc> resourcesDrillList;
   private UiList<TownStateUpdatePayload.ParcelSummary> parcelsList;
   /** When set, the Resources tab renders a floating popup listing every
    *  barrel that holds this item. */
   private String selectedResourceItem;
   /** Search filter on the Resources grid — matches against item id /
    *  pretty name. */
   private EditBox resourceSearchBox;
   /** Sort key for the Resources grid: "count" (desc) / "name" (a-z) /
    *  "recent" (max container lastUpdatedTick across holders). */
   private String resourceSort = "count";
   /** Cached "recent activity" tick per item id, recomputed once per
    *  render. Lets the "recent" sort sit in O(n) without a second walk
    *  over containers per comparison. */
   private final java.util.Map<String, Long> resourceRecentByItem = new java.util.HashMap<>();

   /** EditBoxes for the production-cap min/max values, shown inside the
    *  resource popup when an item is selected. Lifecycle: created in
    *  {@link #initResourcesTabWidgets} whenever {@link #selectedResourceItem}
    *  is non-null; cleared by the normal widget-rebuild on tab/mode
    *  change. */
   private EditBox resTargetMinBox;
   private EditBox resTargetMaxBox;
   /** Tracks which item the EditBoxes are bound to. If the user picks a
    *  different cell, we rebuild the boxes pre-filled with the new
    *  item's min/max. */
   private String resTargetBoundItem;
   /** Free-text search filter for the Villagers tab — matches against
    *  name, profession, or activity. */
   private EditBox villagerSearchBox;
   /** "all" / "farmer" / "shepherd" / "butcher" / "mason" / "none".
    *  Filter chip click toggles into / out of "all". */
   private String villagerProfFilter = "all";
   /** "all" / "working" / "idle" / "sleeping" — filter chip on activity. */
   private String villagerStatusFilter = "all";
   private Mode mode = Mode.NORMAL;
   private UUID selectedVillager;
   private int scrollOffset;
   private int memoryScrollOffset;
   private int beliefsScrollOffset;
   private int tasksScrollOffset;
   private int refreshTimer;

   private EditBox spawnNameBox;
   private EditBox spawnRoleBox;
   private EditBox spawnSeedBox;
   private EditBox detailSeedBox;
   private EditBox townNameBox;
   private EditBox newTownFactBox;
   private EditBox newPersonalFactBox;

   public TownAdminScreen(TownStateUpdatePayload state) {
      super(Component.literal("Town Square"));
      this.state = state;
   }

   /** Jump directly to the per-villager detail view. Called from the server
    *  side via {@link com.yucareux.townfolk.network.OpenVillagerDetailPayload}
    *  when the player sneak-right-clicks a villager — saves the trip back to
    *  the Town Square. No-op if the villager isn't in the current state. */
   public void focusVillager(UUID uuid) {
      if (findVillager(uuid).isEmpty()) return;
      this.selectedVillager = uuid;
      this.mode = Mode.VILLAGER_DETAIL;
      rebuildAdminWidgets();
   }

   public void updateState(TownStateUpdatePayload state) {
      this.state = state;
      if (this.selectedVillager != null && findVillager(this.selectedVillager).isEmpty()) {
         this.selectedVillager = null;
         this.mode = Mode.NORMAL;
         this.tab = Tab.VILLAGERS;
      }
      rebuildAdminWidgets();
   }

   private Optional<TownStateUpdatePayload.VillagerSummary> findVillager(UUID uuid) {
      for (var v : this.state.villagers()) if (v.uuid().equals(uuid)) return Optional.of(v);
      return Optional.empty();
   }

   private long townPos() { return this.state.townSquarePos(); }

   /** Logical canvas dimensions — the screen rendered through the
    *  {@link #CONTENT_SCALE} pose sees this much room. Bigger numbers
    *  here ⇒ more content fits at the same physical size. */
   private int canvasWidth()  { return (int) (this.width  * INV_CONTENT_SCALE); }
   private int canvasHeight() { return (int) (this.height * INV_CONTENT_SCALE); }

   private int panelWidth() {
      // Physical footprint ~80 % of the GUI width; in logical space that
      // becomes 80 % × INV_SCALE ≈ 121 % wider than the canvas, but the
      // min/max caps tighten it to a comfortable range.
      int target = (int) (canvasWidth() * 0.80);
      return Math.max(640, Math.min(target, 980));
   }
   private int panelHeight() {
      int target = (int) (canvasHeight() * 0.85);
      return Math.max(500, Math.min(target, 720));
   }
   private int panelTop()    { return (canvasHeight() - panelHeight()) / 2; }
   private int panelBottom() { return panelTop() + panelHeight(); }
   private int panelLeft()   { return (canvasWidth()  - panelWidth())  / 2; }
   private int panelRight()  { return panelLeft() + panelWidth(); }

   @Override
   protected void init() {
      super.init();
      rebuildAdminWidgets();
   }

   private void rebuildAdminWidgets() {
      this.clearWidgets();
      int paneL = panelLeft();
      int paneR = panelRight();
      int contentTop = panelTop() + 34;
      int contentBottom = panelBottom() - PADDING;

      switch (this.mode) {
         case NORMAL -> {
            switch (this.tab) {
               case OVERVIEW -> initOverviewTabWidgets(paneL, paneR, contentTop, contentBottom);
               case VILLAGERS -> initVillagersTabWidgets(paneL, paneR, contentTop, contentBottom);
               case RESOURCES -> initResourcesTabWidgets(paneL, paneR, contentTop, contentBottom);
               case PARCELS -> {} // render-only
               case TASKS -> initTasksTabWidgets(paneL, paneR, contentTop, contentBottom);
               case LOG -> {} // no widgets, render only
            }
         }
         case SPAWN_FORM -> initSpawnFormWidgets(paneL, paneR, contentTop, contentBottom);
         case VILLAGER_DETAIL -> initVillagerDetailWidgets(paneL, paneR, contentTop, contentBottom);
         case VILLAGER_LOG -> initVillagerLogWidgets(paneL, paneR, contentTop, contentBottom);
         case MEMORIES -> initMemoriesWidgets(paneL, paneR, contentTop, contentBottom);
         case BELIEFS_FULL -> initBeliefsFullWidgets(paneL, paneR, contentTop, contentBottom);
      }
   }

   private void initBeliefsFullWidgets(int paneL, int paneR, int top, int bottom) {
      int innerR = paneR - PADDING;
      addRenderableWidget(Button.builder(Component.literal("← Back"), b -> {
         this.mode = Mode.MEMORIES;
         this.beliefsScrollOffset = 0;
         rebuildAdminWidgets();
      }).bounds(innerR - 60, top, 60, 18).build());
   }

   // ----- Overview tab -----

   /** Y offsets used by BOTH initOverviewTabWidgets and renderOverviewTab so
    *  widgets and labels can't drift. All measured down from the tab's content
    *  top. Tweak in one place if the layout changes. */
   private static final int OV_PULSE_Y      = 4;
   /** Two rows of pulse cards now (counters + top resources), separated by
    *  GAP_MEDIUM. Keep this in sync with {@link #renderOverviewPulse}. */
   private static final int OV_PULSE_H      = (UiTheme.CARD_HEIGHT * 2) + UiTheme.GAP_MEDIUM;
   private static final int OV_PILLS_Y      = OV_PULSE_Y + OV_PULSE_H + 10;
   private static final int OV_NAME_LABEL_Y = OV_PILLS_Y + 18;
   private static final int OV_NAME_BOX_Y   = OV_NAME_LABEL_Y + 12;
   private static final int OV_STATUS_Y     = OV_NAME_BOX_Y + 30;
   private static final int OV_REFRESH_Y    = OV_STATUS_Y;
   private static final int OV_FACTS_Y      = OV_STATUS_Y + 30;

   private void initOverviewTabWidgets(int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int width = innerR - innerL;
      // Align init Y coords with render-side labels via the shared
      // offset constant (audit item 7.1 — Overview was the last tab
      // still floating 4 px above its labels).
      int t = top + INIT_TO_RENDER_TOP;

      this.townNameBox = new EditBox(this.font, innerL, t + OV_NAME_BOX_Y, width - 80, 20,
         Component.literal("town name"));
      this.townNameBox.setMaxLength(60);
      this.townNameBox.setValue(this.state.townName());
      addRenderableWidget(this.townNameBox);

      addRenderableWidget(Button.builder(Component.literal("Rename"), b -> {
         String name = this.townNameBox.getValue().trim();
         if (!name.isEmpty()) {
            PacketDistributor.sendToServer(AdminActionPayload.renameTown(townPos(), name));
         }
      }).bounds(innerR - 74, t + OV_NAME_BOX_Y, 74, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Refresh"), b ->
         PacketDistributor.sendToServer(AdminActionPayload.refreshSpend(townPos()))
      ).bounds(innerR - 74, t + OV_REFRESH_Y - 2, 74, 20).build());

      int factsListTop = t + OV_FACTS_Y + 14;
      this.newTownFactBox = new EditBox(this.font, innerL, bottom - 22, width - 56, 20,
         Component.literal("new town fact"));
      this.newTownFactBox.setMaxLength(240);
      addRenderableWidget(this.newTownFactBox);
      addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
         String text = this.newTownFactBox.getValue().trim();
         if (text.isEmpty()) return;
         PacketDistributor.sendToServer(AdminActionPayload.pinAddTown(townPos(), text));
         this.newTownFactBox.setValue("");
      }).bounds(innerR - 52, bottom - 22, 52, 20).build());

      // Per-fact action buttons.
      int rowY = factsListTop;
      int rowH = 22;
      int listBottom = bottom - 30;
      for (TownStateUpdatePayload.PinSummary p : this.state.townFacts()) {
         if (rowY + rowH > listBottom) break;
         int btnX = innerR - 44;
         if (!"resolved".equals(p.status())) {
            addRenderableWidget(Button.builder(Component.literal("✓"), b ->
               PacketDistributor.sendToServer(AdminActionPayload.pinResolveTown(townPos(), p.id()))
            ).bounds(btnX, rowY + 1, 18, 18).build());
         }
         addRenderableWidget(Button.builder(Component.literal("✕"), b ->
            PacketDistributor.sendToServer(AdminActionPayload.pinRemoveTown(townPos(), p.id()))
         ).bounds(btnX + 22, rowY + 1, 18, 18).build());
         rowY += rowH;
      }
   }

   // ----- Villagers tab -----

   private record TaskRow(TownStateUpdatePayload.VillagerSummary owner,
                          TownStateUpdatePayload.TodoSummary todo) {}

   /** All open todos across all villagers, owner-grouped order, newest day first inside each. */
   private List<TaskRow> collectOpenTasks() {
      List<TaskRow> rows = new java.util.ArrayList<>();
      for (var v : this.state.villagers()) {
         for (var t : v.todos()) {
            if ("open".equals(t.status())) rows.add(new TaskRow(v, t));
         }
      }
      return rows;
   }

   private void initTasksTabWidgets(int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;
      int listTop = top + 16;
      int listBottom = bottom - 6;
      int rowH = 24;

      List<TaskRow> rows = collectOpenTasks();
      int y = listTop - this.tasksScrollOffset;
      for (TaskRow r : rows) {
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > listBottom) break;
         int btnX = innerR - 44;
         addRenderableWidget(Button.builder(Component.literal("✓"), b ->
            PacketDistributor.sendToServer(AdminActionPayload.todoComplete(
               townPos(), r.owner().uuid(), r.todo().id()))
         ).bounds(btnX, y + 2, 18, 18).build());
         addRenderableWidget(Button.builder(Component.literal("✕"), b ->
            PacketDistributor.sendToServer(AdminActionPayload.todoAbandon(
               townPos(), r.owner().uuid(), r.todo().id()))
         ).bounds(btnX + 22, y + 2, 18, 18).build());
         y += rowH;
      }
   }

   private void renderTasksTab(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;

      List<TaskRow> rows = collectOpenTasks();
      String header = "Open tasks across the town (" + rows.size() + ")";
      graphics.drawString(this.font, header, innerL, top, FG_DIM, true);
      if (rows.isEmpty()) {
         graphics.drawString(this.font, "(no open commitments — nobody owes anybody anything)",
            innerL, top + 14, FG_FAINT, true);
         return;
      }
      int listTop = top + 16;
      int listBottom = bottom - 6;
      int rowH = 24;
      scaledScissor(graphics, innerL, listTop, innerR, listBottom);
      int y = listTop - this.tasksScrollOffset;
      for (TaskRow r : rows) {
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > listBottom) break;
         graphics.fill(innerL, y, innerR - 50, y + rowH - 2, ROW_BG);
         // Top line: owner name + day stamp
         String prefix = r.owner().name() + " · day " + r.todo().createdDay();
         graphics.drawString(this.font, prefix, innerL + 4, y + 3, FG_ACCENT, true);
         // Second line: todo text (truncated to fit)
         String text = truncate(r.todo().text(), (innerR - 50) - innerL - 8);
         graphics.drawString(this.font, text, innerL + 4, y + 13, FG_PRIMARY, true);
         y += rowH;
      }
      graphics.disableScissor();
   }

   /** init() and render() use slightly different {@code top} reference
    *  values — init's is {@code panelTop+34}, render's is
    *  {@code panelTop+38}. Adding this offset to an init-side widget Y
    *  produces the same on-screen position as render-side {@code top+0},
    *  so a widget placed at {@code top + N + INIT_TO_RENDER_TOP} lines
    *  up with a label drawn at {@code top + N}. */
   private static final int INIT_TO_RENDER_TOP = 4;

   private void initVillagersTabWidgets(int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;

      // Search box anchored top-left of the tab content. 240 wide is
      // enough for any sensible name search; profession / status chips
      // live to its right and are drawn (not vanilla widgets) at the
      // same on-screen Y.
      this.villagerSearchBox = new EditBox(this.font, innerL, top + 4 + INIT_TO_RENDER_TOP, 240, 18,
         Component.literal("filter villagers"));
      this.villagerSearchBox.setMaxLength(60);
      this.villagerSearchBox.setHint(Component.literal("search by name…"));
      addRenderableWidget(this.villagerSearchBox);

      addRenderableWidget(Button.builder(Component.literal("+ Spawn villager"), b -> {
         this.mode = Mode.SPAWN_FORM;
         rebuildAdminWidgets();
      }).bounds(innerR - 132, bottom - 22, 132, 20).build());
   }

   // ───────── Resources tab (new) ─────────

   private void initResourcesTabWidgets(int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      this.resourceSearchBox = new EditBox(this.font, innerL, top + 4 + INIT_TO_RENDER_TOP, 200, 18,
         Component.literal("filter resources"));
      this.resourceSearchBox.setMaxLength(48);
      this.resourceSearchBox.setHint(Component.literal("search items…"));
      addRenderableWidget(this.resourceSearchBox);

      // Popup widgets — only when a cell is selected.
      if (this.selectedResourceItem != null) {
         initResourcePopupWidgets(paneL, paneR, top, bottom);
      } else {
         this.resTargetMinBox = null;
         this.resTargetMaxBox = null;
         this.resTargetBoundItem = null;
      }
   }

   /** Build the in-popup widgets (min/max EditBoxes + Enable/Disable
    *  button). Called from {@link #initResourcesTabWidgets} when a cell
    *  is selected, and re-called whenever the selection changes so the
    *  EditBoxes are repositioned + repopulated for the new item. */
   private void initResourcePopupWidgets(int paneL, int paneR, int top, int bottom) {
      // Match the geometry used by renderResourcePopup. init's `top`
      // is `panelTop+34`; render uses `panelTop+38`. The popup helper
      // uses RENDER-frame coords so we have to translate.
      int paneR_eff = paneR;
      int paneL_eff = paneL;
      int renderTop = panelTop() + 22 + 16;
      int renderBottom = panelBottom() - PADDING;
      int[] bounds = resourcePopupBounds(paneL_eff, paneR_eff, renderTop, renderBottom);
      int popX = bounds[0], popY = bounds[1], popW = bounds[2];

      // Target row inside the popup: 28px tall, starts at popY+32.
      int rowY = popY + 32;
      var target = findTargetFor(this.selectedResourceItem);

      // Layout the row: "min: [_____]   max: [_____]   [Enable|Disable]"
      int innerL = popX + 12;
      int minLabelX = innerL + 42;
      int maxLabelX = minLabelX + 88;
      // The EditBoxes themselves: 38px wide, 14px tall.
      int boxW = 38, boxH = 14;

      this.resTargetMinBox = new EditBox(this.font,
         minLabelX + 24, rowY + 3, boxW, boxH, Component.literal("min"));
      this.resTargetMinBox.setMaxLength(4);
      this.resTargetMinBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
      this.resTargetMinBox.setValue(target == null ? "" : String.valueOf(target.min()));
      this.resTargetMinBox.setResponder(s -> {});      // commit on focus loss / Enter, not per keystroke
      addRenderableWidget(this.resTargetMinBox);

      this.resTargetMaxBox = new EditBox(this.font,
         maxLabelX + 24, rowY + 3, boxW, boxH, Component.literal("max"));
      this.resTargetMaxBox.setMaxLength(4);
      this.resTargetMaxBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
      this.resTargetMaxBox.setValue(target == null ? "" : String.valueOf(target.max()));
      this.resTargetMaxBox.setResponder(s -> {});
      addRenderableWidget(this.resTargetMaxBox);

      // Apply / Enable button — commits the EditBox contents. When the
      // boxes are empty (fresh "Enable cap" click on an uncapped item),
      // seed sensible defaults derived from the current stock instead
      // of sending a no-op disable.
      final String item = this.selectedResourceItem;
      final int currentStock = currentStockFor(item);
      Component applyLabel = Component.literal(target == null ? "Enable cap" : "Apply");
      addRenderableWidget(Button.builder(applyLabel, b -> {
         String minText = this.resTargetMinBox.getValue().trim();
         String maxText = this.resTargetMaxBox.getValue().trim();
         int min, max;
         if (minText.isEmpty() && maxText.isEmpty()) {
            // Fresh enable with no typed values — default to a band
            // bracketing current stock so the cap engages predictably
            // (or 8/32 if stock is zero).
            int base = currentStock > 0 ? currentStock : 32;
            min = Math.max(1, base / 2);
            max = Math.max(min + 1, base);
            this.resTargetMinBox.setValue(String.valueOf(min));
            this.resTargetMaxBox.setValue(String.valueOf(max));
         } else {
            min = parseIntOr(minText, 0);
            max = parseIntOr(maxText, 0);
         }
         // After defaults are filled in, both can't be 0 unless the
         // user genuinely typed zeros — treat that as a disable.
         final long townPos = townPos();
         if (min <= 0 && max <= 0) {
            PacketDistributor.sendToServer(
               com.yucareux.townfolk.network.SetProductionTargetPayload.disable(townPos, item));
            com.yucareux.townfolk.diag.VerboseLog.write("CLIENT_TARGET_CLICK",
               "item=" + item + " action=disable", "");
         } else {
            if (max < min) max = min;
            PacketDistributor.sendToServer(
               com.yucareux.townfolk.network.SetProductionTargetPayload.setBounds(townPos, item, min, max));
            com.yucareux.townfolk.diag.VerboseLog.write("CLIENT_TARGET_CLICK",
               "item=" + item + " action=enable min=" + min + " max=" + max, "");
         }
      }).bounds(popX + popW - 152, rowY + 3, 70, 14).build());

      // Disable button — only when a cap exists. Removes the cap.
      if (target != null) {
         addRenderableWidget(Button.builder(Component.literal("Disable"), b -> {
            PacketDistributor.sendToServer(
               com.yucareux.townfolk.network.SetProductionTargetPayload.disable(townPos(), item));
         }).bounds(popX + popW - 78, rowY + 3, 66, 14).build());
      }

      this.resTargetBoundItem = this.selectedResourceItem;
   }

   private static int parseIntOr(String s, int fallback) {
      if (s == null || s.isBlank()) return fallback;
      try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
   }

   /** Convenience: total of an item across the town from the last-pushed
    *  state snapshot. Used by the popup to seed sensible defaults for
    *  freshly enabled caps. */
   private int currentStockFor(String itemId) {
      if (itemId == null || this.state == null) return 0;
      for (var ic : this.state.aggregateResources()) {
         if (itemId.equals(ic.itemId())) return ic.count();
      }
      return 0;
   }

   private void initSpawnFormWidgets(int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;
      int width = innerR - innerL;

      // Name — leave blank to have the LLM pick a fitting Alpine name.
      this.spawnNameBox = new EditBox(this.font, innerL, top + 22, width, 20,
         Component.literal("name (blank = LLM picks)"));
      this.spawnNameBox.setMaxLength(40);
      addRenderableWidget(this.spawnNameBox);

      // Persona seed — drives the backstory. Role field is gone; villagers
      // build their own role from the seed + their in-world actions.
      this.spawnSeedBox = new EditBox(this.font, innerL, top + 64, width, 80,
         Component.literal("persona seed (optional — 1-3 sentences)"));
      this.spawnSeedBox.setMaxLength(500);
      addRenderableWidget(this.spawnSeedBox);

      addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
         this.mode = Mode.NORMAL;
         rebuildAdminWidgets();
      }).bounds(innerL, bottom - 22, 80, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Spawn"), b -> {
         String name = this.spawnNameBox.getValue().trim();
         String seed = this.spawnSeedBox.getValue().trim();
         // Empty name is fine — server asks the LLM for one.
         PacketDistributor.sendToServer(AdminActionPayload.spawn(townPos(), name, "", seed));
         this.mode = Mode.NORMAL;
         this.tab = Tab.VILLAGERS;
         rebuildAdminWidgets();
      }).bounds(innerR - 80, bottom - 22, 80, 20).build());

      this.setInitialFocus(this.spawnNameBox);
   }

   private void initVillagerDetailWidgets(int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) {
         this.mode = Mode.NORMAL;
         rebuildAdminWidgets();
         return;
      }
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;
      int width = innerR - innerL;

      this.detailSeedBox = new EditBox(this.font, innerL, top + 28, width, 60, Component.literal("persona seed"));
      this.detailSeedBox.setMaxLength(500);
      this.detailSeedBox.setValue(v.personaSeed());
      addRenderableWidget(this.detailSeedBox);

      addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
         this.mode = Mode.NORMAL;
         this.tab = Tab.VILLAGERS;
         this.selectedVillager = null;
         rebuildAdminWidgets();
      }).bounds(innerL, bottom - 22, 50, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Save persona"), b -> {
         String seed = this.detailSeedBox.getValue().trim();
         PacketDistributor.sendToServer(AdminActionPayload.editPersona(townPos(), v.uuid(), seed));
      }).bounds(innerL + 54, bottom - 22, 86, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Memories"), b -> {
         this.mode = Mode.MEMORIES;
         this.memoryScrollOffset = 0;
         rebuildAdminWidgets();
      }).bounds(innerL + 144, bottom - 22, 74, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Log"), b -> {
         this.mode = Mode.VILLAGER_LOG;
         rebuildAdminWidgets();
      }).bounds(innerL + 222, bottom - 22, 40, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Regen story"), b ->
         PacketDistributor.sendToServer(AdminActionPayload.regenerateBackstory(townPos(), v.uuid()))
      ).bounds(innerL + 266, bottom - 22, 78, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
         PacketDistributor.sendToServer(AdminActionPayload.remove(townPos(), v.uuid()));
         this.mode = Mode.NORMAL;
         this.tab = Tab.VILLAGERS;
         this.selectedVillager = null;
         rebuildAdminWidgets();
      }).bounds(innerR - 64, bottom - 22, 64, 20).build());
   }

   // ----- Memories view -----

   private void initMemoriesWidgets(int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) {
         this.mode = Mode.NORMAL;
         rebuildAdminWidgets();
         return;
      }
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;
      int width = innerR - innerL;

      // Add-fact row, anchored to bottom
      this.newPersonalFactBox = new EditBox(this.font, innerL, bottom - 22, width - 56, 20,
         Component.literal("new pinned fact"));
      this.newPersonalFactBox.setMaxLength(240);
      addRenderableWidget(this.newPersonalFactBox);
      addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
         String text = this.newPersonalFactBox.getValue().trim();
         if (text.isEmpty()) return;
         PacketDistributor.sendToServer(AdminActionPayload.pinAddPersonal(townPos(), v.uuid(), text));
         this.newPersonalFactBox.setValue("");
      }).bounds(innerR - 52, bottom - 22, 52, 20).build());

      // Back button anchored top-right
      addRenderableWidget(Button.builder(Component.literal("← Back"), b -> {
         this.mode = Mode.VILLAGER_DETAIL;
         rebuildAdminWidgets();
      }).bounds(innerR - 60, top, 60, 18).build());

      // "Expand beliefs" button — opens a full-panel scrollable view.
      addRenderableWidget(Button.builder(Component.literal("Expand"), b -> {
         this.mode = Mode.BELIEFS_FULL;
         this.beliefsScrollOffset = 0;
         rebuildAdminWidgets();
      }).bounds(innerR - 128, top + 12, 60, 14).build());

      // Buttons sit in the scrollable list area, which starts after the
      // beliefs + stats header block (see renderMemories layout). Pinned
      // facts are listed AFTER any open todos.
      int listTop = MEMORIES_LIST_TOP_OFFSET + top;
      int listBottom = bottom - 30;
      int rowH = 22;
      int y = listTop - this.memoryScrollOffset;

      // Skip space taken by the "Open commitments" section so pin button Ys
      // align with the rendered pin rows.
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      if (openTodos > 0) {
         y += 12;                  // section header
         y += rowH * openTodos;
         y += 10;                  // section gap
      }
      y += 12;                     // "Pinned facts" section header

      for (TownStateUpdatePayload.PinSummary p : v.pinnedFacts()) {
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > listBottom) break;
         int btnX = innerR - 44;
         if (!"resolved".equals(p.status())) {
            addRenderableWidget(Button.builder(Component.literal("✓"), b ->
               PacketDistributor.sendToServer(AdminActionPayload.pinResolvePersonal(townPos(), v.uuid(), p.id()))
            ).bounds(btnX, y + 1, 18, 18).build());
         }
         addRenderableWidget(Button.builder(Component.literal("✕"), b ->
            PacketDistributor.sendToServer(AdminActionPayload.pinRemovePersonal(townPos(), v.uuid(), p.id()))
         ).bounds(btnX + 22, y + 1, 18, 18).build());
         y += rowH;
      }
   }

   /** Y offset from the panel content top at which the Memories scrollable list begins. */
   private static final int MEMORIES_LIST_TOP_OFFSET = 78;

   /** Internal click handler, called with LOGICAL (already scaled) mouse
    *  coords by the {@link #mouseClicked} override above. All widget
    *  bounds and custom hit-test regions live in the same logical
    *  space, so this method can compare directly. */
   private boolean doMouseClicked(double mouseX, double mouseY, int button) {
      int paneL = panelLeft();
      int paneT = panelTop();
      if (this.mode == Mode.NORMAL) {
         int tabY1 = paneT + 24;
         Tab clickedTab = UiTabs.hitTest(paneL + TAB_STRIP_PADDING_LEFT, tabY1,
            tabs(), mouseX, mouseY);
         if (clickedTab != null) {
            this.tab = clickedTab;
            this.scrollOffset = 0;
            this.resourcesGridScrollRows = 0;
            rebuildAdminWidgets();
            return true;
         }
         if (this.tab == Tab.VILLAGERS) {
            // Filter chips intercept before the list row hit. The chips
            // are rendered using render-side contentTop (paneT + 22 +
            // 16 = paneT + 38), so the hit-test uses the same.
            String[] chipHit = hitTestVillagerChips(mouseX, mouseY, paneL, paneT + 22 + 16);
            if (chipHit != null) {
               if ("prof".equals(chipHit[0]))   this.villagerProfFilter = chipHit[1];
               if ("status".equals(chipHit[0])) this.villagerStatusFilter = chipHit[1];
               if (this.villagersList != null) this.villagersList.resetScroll();
               rebuildAdminWidgets();
               return true;
            }
            UUID clicked = villagerAtPoint(mouseX, mouseY);
            if (clicked != null) {
               this.selectedVillager = clicked;
               this.mode = Mode.VILLAGER_DETAIL;
               rebuildAdminWidgets();
               return true;
            }
         }
         if (this.tab == Tab.RESOURCES) {
            int paneB = paneT + panelHeight();
            int paneR = paneL + panelWidth();
            int contentTop = paneT + 22 + 16;
            int contentBottom = paneB - PADDING;

            // If the popup is open, clicks outside its frame close it;
            // clicks inside (including the EditBoxes / buttons we added
            // via initResourcePopupWidgets) propagate to super so vanilla
            // widget hit-testing handles them.
            if (this.selectedResourceItem != null) {
               int[] bounds = resourcePopupBounds(paneL, paneR, contentTop, contentBottom);
               int popX = bounds[0], popY = bounds[1], popW = bounds[2], popH = bounds[3];
               boolean inside = mouseX >= popX && mouseX < popX + popW
                             && mouseY >= popY && mouseY < popY + popH;
               if (!inside) {
                  this.selectedResourceItem = null;
                  rebuildAdminWidgets();           // drop the popup's EditBoxes
                  return true;
               }
               // Fall through to super so the EditBoxes / Apply / Disable
               // buttons receive the click.
               return super.mouseClicked(mouseX, mouseY, button);
            }

            // Sort chips first.
            String sortHit = hitTestResourceSortChips(mouseX, mouseY, paneL, contentTop);
            if (sortHit != null) {
               this.resourceSort = sortHit;
               return true;
            }
            // Cell click → open popup (and rebuild widgets so the
            // popup's EditBoxes / buttons appear).
            String cellHit = hitTestResourceCell(mouseX, mouseY, paneL, paneR, contentTop, contentBottom);
            if (cellHit != null) {
               this.selectedResourceItem = cellHit;
               rebuildAdminWidgets();
               return true;
            }
         }
         if (this.tab == Tab.PARCELS && this.parcelsList != null) {
            // Click a parcel row → ask the server to open the crop-plan
            // editor for that parcel id. PLANT parcels only — ANIMAL
            // parcels have no plan UI yet, so just swallow the click.
            var hit = this.parcelsList.itemAt(mouseX, mouseY);
            if (hit != null && "PLANT".equals(hit.type())) {
               PacketDistributor.sendToServer(
                  AdminActionPayload.openParcelEditor(townPos(), hit.id()));
               return true;
            }
            if (hit != null) return true;
         }
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      // Convert physical → logical for hit-test against widget / list bounds.
      mouseX *= INV_CONTENT_SCALE;
      mouseY *= INV_CONTENT_SCALE;
      if (this.mode == Mode.NORMAL && this.tab == Tab.VILLAGERS && this.villagersList != null
          && this.villagersList.onMouseScrolled(mouseX, mouseY, scrollY)) return true;
      if (this.mode == Mode.NORMAL && this.tab == Tab.RESOURCES) {
         if (this.resourcesDrillList != null && this.resourcesDrillList.onMouseScrolled(mouseX, mouseY, scrollY)) return true;
         // Only scroll the grid when the popup isn't covering it and the
         // wheel event actually happened inside the grid rectangle. Otherwise
         // fall through so e.g. the search box or chips can handle it.
         if (this.selectedResourceItem == null) {
            int paneL = panelLeft();
            int paneR = panelRight();
            int top   = panelTop() + 34;
            int bottom = panelBottom() - PADDING;
            int innerL = paneL + UiTheme.PADDING;
            int innerR = paneR - UiTheme.PADDING;
            int gridTop = top + RES_GRID_TOP;
            int gridBot = bottom - 6;
            if (mouseX >= innerL && mouseX <= innerR
                && mouseY >= gridTop && mouseY <= gridBot) {
               this.resourcesGridScrollRows = Math.max(0,
                  this.resourcesGridScrollRows - (int) Math.signum(scrollY));
               return true;
            }
         }
      }
      if (this.mode == Mode.NORMAL && this.tab == Tab.PARCELS && this.parcelsList != null
          && this.parcelsList.onMouseScrolled(mouseX, mouseY, scrollY)) return true;
      if (this.mode == Mode.NORMAL && this.tab == Tab.TASKS) {
         this.tasksScrollOffset = Math.max(0, this.tasksScrollOffset - (int) (scrollY * 18));
         rebuildAdminWidgets();
         return true;
      }
      if (this.mode == Mode.MEMORIES) {
         this.memoryScrollOffset = Math.max(0, this.memoryScrollOffset - (int) (scrollY * 18));
         rebuildAdminWidgets();
         return true;
      }
      if (this.mode == Mode.BELIEFS_FULL) {
         this.beliefsScrollOffset = Math.max(0, this.beliefsScrollOffset - (int) (scrollY * 18));
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
         if (this.mode == Mode.BELIEFS_FULL) {
            this.mode = Mode.MEMORIES;
            rebuildAdminWidgets();
            return true;
         }
         if (this.mode == Mode.MEMORIES) {
            this.mode = Mode.VILLAGER_DETAIL;
            rebuildAdminWidgets();
            return true;
         }
         if (this.mode == Mode.VILLAGER_LOG) {
            this.mode = Mode.VILLAGER_DETAIL;
            rebuildAdminWidgets();
            return true;
         }
         if (this.mode == Mode.NORMAL && this.tab == Tab.RESOURCES
             && this.selectedResourceItem != null) {
            this.selectedResourceItem = null;
            rebuildAdminWidgets();
            return true;
         }
         if (this.mode != Mode.NORMAL) {
            this.mode = Mode.NORMAL;
            this.selectedVillager = null;
            rebuildAdminWidgets();
            return true;
         }
      }
      return super.keyPressed(keyCode, scanCode, modifiers);
   }

   @Override
   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      // No blur.
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      // Push the dense-render scale ONCE around everything (panel chrome,
      // tabs, content, vanilla widgets). Widgets are positioned in
      // logical coords via panelLeft/Top/etc; mouse handlers below
      // convert physical→logical so widget hit-tests still match.
      var pose = graphics.pose();
      pose.pushPose();
      pose.scale(CONTENT_SCALE, CONTENT_SCALE, 1.0f);
      int lmX = (int) (mouseX * INV_CONTENT_SCALE);
      int lmY = (int) (mouseY * INV_CONTENT_SCALE);

      int l = panelLeft(), r = panelRight(), t = panelTop(), b = panelBottom();

      graphics.fill(l, t, r, b, PANEL_BG);
      graphics.fill(l - 1, t - 1, r + 1, t, PANEL_BORDER);
      graphics.fill(l - 1, b, r + 1, b + 1, PANEL_BORDER);
      graphics.fill(l - 1, t - 1, l, b + 1, PANEL_BORDER);
      graphics.fill(r, t - 1, r + 1, b + 1, PANEL_BORDER);

      int headerH = 22;
      graphics.fill(l, t, r, t + headerH, HEADER_BG);
      graphics.fill(l, t + headerH, r, t + headerH + 1, PANEL_BORDER);
      graphics.drawString(this.font, Component.literal(this.state.townName()).withStyle(ChatFormatting.GOLD),
         l + PADDING, t + 7, FG_ACCENT, true);

      String totalCost = "$" + String.format(Locale.ROOT, "%.4f", this.state.totalTownCostUsd());
      int totalCostW = this.font.width(totalCost);
      graphics.drawString(this.font, totalCost, r - PADDING - totalCostW, t + 7, FG_DIM, true);

      switch (this.mode) {
         case NORMAL -> {
            renderTabs(graphics, l, t + headerH);
            int contentTop = t + headerH + 16;
            int contentBottom = b - PADDING;
            switch (this.tab) {
               case OVERVIEW -> renderOverviewTab(graphics, l, r, contentTop, contentBottom);
               case VILLAGERS -> renderVillagersTab(graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case RESOURCES -> renderResourcesTab(graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case PARCELS -> renderParcelsTab(graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case TASKS -> renderTasksTab(graphics, l, r, contentTop, contentBottom);
               case LOG -> renderLogTab(graphics, l, r, contentTop, contentBottom);
            }
         }
         case SPAWN_FORM -> renderSpawnForm(graphics, l, r, t + headerH + 16, b - PADDING);
         case VILLAGER_DETAIL -> renderVillagerDetail(graphics, l, r, t + headerH + 16, b - PADDING);
         case MEMORIES -> renderMemories(graphics, l, r, t + headerH + 16, b - PADDING);
         case BELIEFS_FULL -> renderBeliefsFull(graphics, l, r, t + headerH + 16, b - PADDING);
         case VILLAGER_LOG -> renderVillagerLog(graphics, l, r, t + headerH + 16, b - PADDING);
      }

      // Vanilla widgets render via super.render — pass them the
      // SCALED-down mouse coords so hover detection matches widget
      // bounds (which are now in logical coords).
      super.render(graphics, lmX, lmY, partialTick);

      pose.popPose();
   }

   // ───── Mouse handlers: convert physical → logical coords ─────
   // Without these, vanilla widget hit-testing compares physical clicks
   // against logical bounds and misses every time.

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      return doMouseClicked(mouseX * INV_CONTENT_SCALE, mouseY * INV_CONTENT_SCALE, button);
   }

   @Override
   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      return super.mouseReleased(mouseX * INV_CONTENT_SCALE, mouseY * INV_CONTENT_SCALE, button);
   }

   @Override
   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      return super.mouseDragged(mouseX * INV_CONTENT_SCALE, mouseY * INV_CONTENT_SCALE,
         button, dragX * INV_CONTENT_SCALE, dragY * INV_CONTENT_SCALE);
   }

   private static final int TAB_STRIP_PADDING_LEFT = 16;

   /** Build the tab list with live counts baked into the labels. Called by
    *  both render and hit-test paths so they cannot drift apart. */
   private List<UiTabs.Tab<Tab>> tabs() {
      int openTodoCount = 0;
      for (var vs : this.state.villagers()) {
         for (var t : vs.todos()) if ("open".equals(t.status())) openTodoCount++;
      }
      return java.util.List.of(
         UiTabs.tab(Tab.OVERVIEW,  "Overview", 64),
         UiTabs.tab(Tab.VILLAGERS, "Villagers (" + this.state.populationAlive() + ")", 92),
         UiTabs.tab(Tab.RESOURCES, "Resources (" + this.state.aggregateResources().size() + ")", 96),
         UiTabs.tab(Tab.PARCELS,   "Parcels (" + this.state.parcels().size() + ")", 80),
         UiTabs.tab(Tab.TASKS,     "Tasks (" + openTodoCount + ")", 64),
         UiTabs.tab(Tab.LOG,       "Activity", 58)
      );
   }

   private void renderTabs(GuiGraphics graphics, int paneL, int headerBottom) {
      UiTabs.render(graphics, this.font, paneL + TAB_STRIP_PADDING_LEFT, headerBottom + 2,
         tabs(), this.tab);
   }

   private void drawTab(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean active) {
      graphics.fill(x, y, x + w, y + h, active ? TAB_ACTIVE_BG : 0);
      graphics.fill(x, y + h, x + w, y + h + 1, active ? FG_ACCENT : PANEL_BORDER);
      graphics.drawString(this.font, label, x + 8, y + 3, active ? FG_ACCENT : FG_DIM, true);
   }

   private void renderOverviewTab(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      renderOverviewPulse(graphics, paneL, paneR, top + OV_PULSE_Y);
      renderOverviewBody(graphics, paneL, paneR, top, bottom);
   }

   /** Pulse strip: a row of 6 small cards across the top of Overview,
    *  built for at-a-glance scale to 50+ villagers — population +
    *  activity breakdown + day-over-day deltas.
    *
    *  All counters come from server-side tallies in
    *  {@link TownStateUpdatePayload} (the {@code populationAlive},
    *  {@code idleCount}, etc. fields) so the UI doesn't have to walk the
    *  villager list each frame. The second row of cards holds top
    *  stockpile resources so the player gets "30 wheat, 12 wool, 4 milk"
    *  without leaving Overview. */
   private void renderOverviewPulse(GuiGraphics graphics, int paneL, int paneR, int top) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int total = innerR - innerL;
      int gap = UiTheme.GAP_MEDIUM;
      int cardW = (total - 5 * gap) / 6;
      int cardH = UiTheme.CARD_HEIGHT;

      int alive = this.state.populationAlive();
      int tot   = this.state.populationTotal();
      int work  = this.state.workingCount();
      int idle  = this.state.idleCount();
      int sleep = this.state.sleepingCount();
      int births = this.state.birthsToday();
      int deaths = this.state.deathsToday();

      int x = innerL;
      UiCard.draw(graphics, this.font, x, top, cardW, cardH,
         "Population", alive + " / " + tot,
         (tot - alive) == 0 ? "all alive" : (tot - alive) + " gone");
      x += cardW + gap;
      UiCard.draw(graphics, this.font, x, top, cardW, cardH,
         "Working", String.valueOf(work),
         alive == 0 ? "—" : pct(work, alive) + "% busy");
      x += cardW + gap;
      UiCard.draw(graphics, this.font, x, top, cardW, cardH,
         "Idle", String.valueOf(idle),
         alive == 0 ? "—" : pct(idle, alive) + "% idle");
      x += cardW + gap;
      UiCard.draw(graphics, this.font, x, top, cardW, cardH,
         "Sleeping", String.valueOf(sleep),
         alive == 0 ? "—" : pct(sleep, alive) + "% in bed");
      x += cardW + gap;
      UiCard.draw(graphics, this.font, x, top, cardW, cardH,
         "Births today", String.valueOf(births), births == 0 ? "—" : "+" + births);
      x += cardW + gap;
      UiCard.draw(graphics, this.font, x, top, cardW, cardH,
         "Deaths today", String.valueOf(deaths), deaths == 0 ? "—" : "-" + deaths);

      // Second row: top resources from the aggregate (jump to Resources
      // tab for full list).
      int row2Y = top + cardH + gap;
      int row2CardW = (total - 3 * gap) / 4;
      var agg = this.state.aggregateResources();
      for (int i = 0; i < 4; i++) {
         int rx = innerL + i * (row2CardW + gap);
         if (i < agg.size()) {
            var ic = agg.get(i);
            UiCard.draw(graphics, this.font, rx, row2Y, row2CardW, cardH,
               shortItemName(ic.itemId()),
               String.valueOf(ic.count()),
               "in town stockpile");
         } else {
            UiCard.draw(graphics, this.font, rx, row2Y, row2CardW, cardH,
               "(no item)", "—", "open Resources tab");
         }
      }
   }

   private static String pct(int part, int whole) {
      if (whole <= 0) return "0";
      return String.valueOf(Math.round(part * 100.0f / whole));
   }

   private static String shortItemName(String id) {
      int colon = id.indexOf(':');
      return (colon < 0 ? id : id.substring(colon + 1)).replace('_', ' ');
   }

   private void renderOverviewBody(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      // Status pills, right-aligned, sitting cleanly under the pulse strip.
      String radiusText = "Radius " + this.state.radius() + " blocks";
      String peaceText  = this.state.peaceful() ? "Peaceful · combat off" : "Combat enabled";
      int radiusW = this.font.width(radiusText) + 8;
      int peaceW  = this.font.width(peaceText)  + 8;
      int pillY = top + OV_PILLS_Y;
      int radiusX = innerR - radiusW;
      int peaceX  = radiusX - UiTheme.GAP_SMALL - peaceW;
      UiPill.draw(graphics, this.font, radiusX, pillY, radiusText, UiTheme.MUTED);
      UiPill.draw(graphics, this.font, peaceX,  pillY, peaceText,
         this.state.peaceful() ? UiTheme.OK : UiTheme.BAD);

      // Town name label sits just above the (already-placed) EditBox.
      UiText.muted(graphics, this.font, "Town name", innerL, top + OV_NAME_LABEL_Y);

      // OpenRouter status line.
      int blockY = top + OV_STATUS_Y;
      UiText.muted(graphics, this.font, "OpenRouter", innerL, blockY);
      String status = this.state.openrouterStatus();
      String statusLine;
      int statusFg = FG_PRIMARY;
      if ("disabled".equals(status)) {
         statusLine = "disabled (no API key)";
         statusFg = FG_FAINT;
      } else if ("loading".equals(status)) {
         statusLine = "fetching...";
         statusFg = FG_DIM;
      } else if (status.startsWith("error")) {
         statusLine = status;
         statusFg = FG_ERROR;
      } else if (this.state.usage().isPresent() || this.state.limit().isPresent()) {
         double usage = this.state.usage().orElse(0.0);
         double limit = this.state.limit().orElse(0.0);
         if (this.state.limit().isPresent()) {
            statusLine = String.format(Locale.ROOT, "spent $%.4f / credits $%.2f", usage, limit);
         } else {
            statusLine = String.format(Locale.ROOT, "spent $%.4f", usage);
         }
      } else {
         statusLine = "(refresh to fetch)";
         statusFg = FG_FAINT;
      }
      graphics.drawString(this.font, statusLine, innerL, blockY + 12, statusFg, true);

      // Town pinned facts — placement matches OV_FACTS_Y used by initOverviewTabWidgets.
      int factsTop = top + OV_FACTS_Y;
      UiText.muted(graphics, this.font,
         "Town-shared facts (known to every resident)",
         innerL, factsTop);
      int rowY = factsTop + 14;
      int rowH = 22;
      int listBottom = bottom - 30;
      List<TownStateUpdatePayload.PinSummary> facts = this.state.townFacts();
      if (facts.isEmpty()) {
         graphics.drawString(this.font, "(none yet — add one below)",
            innerL, rowY + 4, FG_FAINT, true);
      } else {
         for (TownStateUpdatePayload.PinSummary p : facts) {
            if (rowY + rowH > listBottom) break;
            graphics.fill(innerL, rowY, innerR - 50, rowY + rowH - 2, ROW_BG);
            int textFg = "resolved".equals(p.status()) ? FG_RESOLVED : FG_PRIMARY;
            String text = p.text();
            int maxW = (innerR - 50) - innerL - 8;
            String shown = truncate(text, maxW);
            graphics.drawString(this.font, shown, innerL + 4, rowY + 6, textFg, true);
            rowY += rowH;
         }
      }
   }

   // ───────── Resources tab (icon grid + popup drill-down) ─────────

   /** Cell metrics for the icon grid.
    *
    *  Layout: a 10px top strip for the status dot + production-cap
    *  badge (so they never collide with the icon), then the 16px item
    *  icon centered, then the count line, then the truncated name.
    *  Width is bumped to 66 to comfortably fit a badge like "16/64". */
   private static final int RES_CELL_W = 66;
   private static final int RES_CELL_H = 58;
   private static final int RES_CELL_GAP = 8;
   /** Vertical Y offset (from tab top) at which the grid begins —
    *  leaves room for the search/sort row. */
   private static final int RES_GRID_TOP = 26;

   /** Cache for {@link ItemStack}s built from item-ids; populated
    *  lazily, reused across frames. Saves one
    *  {@code BuiltInRegistries.ITEM.get(...)} per cell per frame. */
   private final java.util.Map<String, net.minecraft.world.item.ItemStack> resourceStackCache =
      new java.util.HashMap<>();

   private net.minecraft.world.item.ItemStack stackForItemId(String id) {
      return this.resourceStackCache.computeIfAbsent(id, k -> {
         var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse(k));
         return new net.minecraft.world.item.ItemStack(item);
      });
   }

   /** Grid view of every resource the town stockpiles. Each cell is an
    *  icon + count. Search filters by name; sort chips reorder. Click a
    *  cell to open the popup showing which barrels hold the item. */
   private void renderResourcesTab(GuiGraphics graphics, int paneL, int paneR,
                                   int top, int bottom, int mouseX, int mouseY) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int contentTop = top + 10;

      // Header / total (right side — search box lives on the left and
      // was placed by initResourcesTabWidgets at innerL, top+4).
      int totalDistinct = this.state.aggregateResources().size();
      int totalItems = 0;
      for (var ic : this.state.aggregateResources()) totalItems += ic.count();
      String totalLabel = totalDistinct + " kinds · " + totalItems + " items";
      UiText.rightFaint(graphics, this.font, totalLabel, innerR, contentTop);

      // Sort chips to the right of the search box.
      int chipsX = innerL + 206;
      drawChipRow(graphics, chipsX, top + 4, "Sort:",
         new String[]{"count", "name", "recent"}, this.resourceSort);

      // Refresh recency cache for the "recent" sort.
      this.resourceRecentByItem.clear();
      for (var rl : this.state.resourceLocations()) {
         long tick = 0L;
         for (var se : this.state.storage()) {
            if (se.packedPos() == rl.packedPos()) { tick = se.lastUpdatedTick(); break; }
         }
         this.resourceRecentByItem.merge(rl.itemId(), tick, Math::max);
      }

      // Apply search + sort to produce the rendered grid list.
      String q = this.resourceSearchBox == null ? "" :
         this.resourceSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
      List<TownStateUpdatePayload.ItemCount> items = new java.util.ArrayList<>();
      for (var ic : this.state.aggregateResources()) {
         if (q.isEmpty() || shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) {
            items.add(ic);
         }
      }
      switch (this.resourceSort) {
         case "name"   -> items.sort((a, b) -> shortItemName(a.itemId()).compareToIgnoreCase(shortItemName(b.itemId())));
         case "recent" -> items.sort((a, b) -> Long.compare(
                                this.resourceRecentByItem.getOrDefault(b.itemId(), 0L),
                                this.resourceRecentByItem.getOrDefault(a.itemId(), 0L)));
         default        -> items.sort((a, b) -> Integer.compare(b.count(), a.count()));
      }

      // Render grid.
      int gridTop = top + RES_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW   = innerR - innerL;
      int cols    = Math.max(1, (gridW + RES_CELL_GAP) / (RES_CELL_W + RES_CELL_GAP));
      // Centre the grid horizontally.
      int gridUsed = cols * RES_CELL_W + (cols - 1) * RES_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      if (items.isEmpty()) {
         UiText.faint(graphics, this.font,
            q.isEmpty() ? "no resources stocked — register a barrel and have a villager deposit something"
                         : "no items match \"" + q + "\"",
            innerL, gridTop + 10);
      } else {
         // Scissor so cells past the bottom are clipped instead of
         // bleeding into the panel border.
         // Clamp scroll: never leave the grid blank by scrolling past the end.
         int totalRows = (items.size() + cols - 1) / cols;
         int visibleRows = Math.max(1, (gridBot - gridTop + RES_CELL_GAP) / (RES_CELL_H + RES_CELL_GAP));
         int maxScrollRow = Math.max(0, totalRows - visibleRows);
         if (this.resourcesGridScrollRows > maxScrollRow) this.resourcesGridScrollRows = maxScrollRow;
         int scrollPx = this.resourcesGridScrollRows * (RES_CELL_H + RES_CELL_GAP);

         // Scissor handles clean clipping at gridBot — keep the break loose
         // (`cy > gridBot`) so partial rows still render and just get clipped
         // visually instead of disappearing.
         scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
         int i = 0;
         for (var ic : items) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX0 + col * (RES_CELL_W + RES_CELL_GAP);
            int cy = gridTop + row * (RES_CELL_H + RES_CELL_GAP) - scrollPx;
            if (cy > gridBot) break;
            if (cy + RES_CELL_H < gridTop) { i++; continue; } // scrolled off top
            renderResourceCell(graphics, ic, cx, cy, mouseX, mouseY);
            i++;
         }
         graphics.disableScissor();
      }

      // Floating popup — drawn on top of the grid when an item is
      // selected. Render LAST so it occludes the cells underneath.
      if (this.selectedResourceItem != null) {
         renderResourcePopup(graphics, paneL, paneR, top, bottom, mouseX, mouseY);
      }
   }

   /** One cell of the resource grid.
    *
    *  Vertical layout (y offsets):
    *    +2  badge strip  (status dot + "min/max" tag, only when a cap is set)
    *    +12 item icon    (16×16, horizontally centred)
    *    +30 count line   (e.g. "174", FG_ACCENT, centred)
    *    +42 name line    (truncated, FG_DIM, centred)
    *
    *  All elements live in distinct vertical bands so the badge can
    *  never overlap the icon or count — the original cramped layout
    *  put the badge in the icon's row and it bled into the digits. */
   private void renderResourceCell(GuiGraphics g, TownStateUpdatePayload.ItemCount ic,
                                   int x, int y, int mouseX, int mouseY) {
      boolean hovered = mouseX >= x && mouseX < x + RES_CELL_W
                     && mouseY >= y && mouseY < y + RES_CELL_H;
      boolean selected = ic.itemId().equals(this.selectedResourceItem);
      int bg = hovered || selected ? TAB_ACTIVE_BG : ROW_BG;
      g.fill(x, y, x + RES_CELL_W, y + RES_CELL_H, bg);
      g.fill(x, y + RES_CELL_H, x + RES_CELL_W, y + RES_CELL_H + 1,
         selected ? FG_ACCENT : PANEL_BORDER);

      // Badge strip (top) — only when a cap exists.
      var target = findTargetFor(ic.itemId());
      if (target != null) {
         String tag = target.min() + "/" + target.max();
         int tagW = this.font.width(tag);
         int tagX = x + RES_CELL_W - tagW - 4;
         int tagY = y + 2;
         g.drawString(this.font, tag, tagX, tagY,
            target.active() ? FG_DIM : FG_ERROR, true);
         // 3-pixel status dot on the LEFT of the strip.
         int dotColor = target.active() ? UiTheme.OK : UiTheme.BAD;
         g.fill(x + 3, y + 3, x + 6, y + 6, dotColor);
      }

      // Item icon — fixed band y+12 .. y+28.
      int iconX = x + (RES_CELL_W - 16) / 2;
      int iconY = y + 12;
      var stack = stackForItemId(ic.itemId());
      g.renderItem(stack, iconX, iconY);

      // Count line — y+32.
      String countText = String.valueOf(ic.count());
      int tw = this.font.width(countText);
      g.drawString(this.font, countText, x + (RES_CELL_W - tw) / 2, y + 32,
         FG_ACCENT, true);

      // Name line — y+44, truncated to cell width.
      String name = shortItemName(ic.itemId());
      String shown = name;
      if (this.font.width(shown) > RES_CELL_W - 6) {
         shown = this.font.plainSubstrByWidth(shown, RES_CELL_W - 10) + "…";
      }
      int nw = this.font.width(shown);
      g.drawString(this.font, shown, x + (RES_CELL_W - nw) / 2, y + 44, FG_DIM, true);
   }

   /** Look up the production target for an item id (if any). Returns
    *  null when no target exists in the current state snapshot. */
   private TownStateUpdatePayload.ProductionTarget findTargetFor(String itemId) {
      if (this.state.productionTargets() == null) return null;
      for (var t : this.state.productionTargets()) {
         if (t.itemId().equals(itemId)) return t;
      }
      return null;
   }

   // ───── Production target controls (inside the resource popup) ─────

   /** Render the labels and status indicator for the target row inside
    *  the resource popup. The EditBoxes + Enable / Disable / Apply
    *  buttons are vanilla widgets — they're added in
    *  {@link #initResourcePopupWidgets} and render themselves; this
    *  method just paints the text decorations around them.
    *
    *  Layout coordinates MUST match initResourcePopupWidgets or the
    *  labels won't line up with the inputs. */
   private void renderTargetControls(GuiGraphics g, int popX, int rowY, int popW,
                                     TownStateUpdatePayload.ProductionTarget target) {
      int innerL = popX + 12;
      g.drawString(this.font, "Cap", innerL, rowY + 5, FG_DIM, true);

      int minLabelX = innerL + 42;
      g.drawString(this.font, "min:", minLabelX, rowY + 5, FG_DIM, true);
      // (the min EditBox renders itself at minLabelX+24)

      int maxLabelX = minLabelX + 88;
      g.drawString(this.font, "max:", maxLabelX, rowY + 5, FG_DIM, true);
      // (the max EditBox renders itself at maxLabelX+24)

      // Status indicator under the row.
      String status = target == null
         ? "(no cap — set min/max and Apply to enable)"
         : (target.active() ? "● producing" : "● paused (at cap)");
      int statusColor = target == null ? FG_FAINT
                                       : (target.active() ? UiTheme.OK : UiTheme.BAD);
      g.drawString(this.font, status, innerL, rowY + 19, statusColor, true);

      // Bottom divider.
      g.fill(popX + 8, rowY + 26, popX + popW - 8, rowY + 27, PANEL_BORDER);
   }

   /** Single source of truth for the resource-popup geometry, used by
    *  both render and the outside-click hit-test. Returns
    *  {@code [x, y, w, h]} in panel-relative coords.
    *
    *  The popup always reserves a 28px target-controls row when an
    *  item is selected — so the player can enable a cap on any item,
    *  not just ones already capped. */
   private int[] resourcePopupBounds(int paneL, int paneR, int top, int bottom) {
      int drillCount = 0;
      if (this.selectedResourceItem != null) {
         for (var rl : this.state.resourceLocations()) {
            if (rl.itemId().equals(this.selectedResourceItem)) drillCount++;
         }
      }
      int popW = 380;
      int targetRowH = this.selectedResourceItem != null ? 28 : 0;
      int popH = Math.min(360, 60 + targetRowH + Math.max(2, drillCount) * 22 + 16);
      int popX = paneL + ((paneR - paneL) - popW) / 2;
      int popY = top + ((bottom - top) - popH) / 2;
      return new int[]{ popX, popY, popW, popH };
   }

   /** Floating popup overlay listing every barrel holding the selected
    *  item. Drawn centred on the panel; click outside or press ESC to
    *  dismiss (handled in the click + key handlers). */
   private void renderResourcePopup(GuiGraphics g, int paneL, int paneR,
                                    int top, int bottom, int mouseX, int mouseY) {
      String item = this.selectedResourceItem;
      List<TownStateUpdatePayload.ResourceLoc> drill = new java.util.ArrayList<>();
      for (var rl : this.state.resourceLocations()) {
         if (rl.itemId().equals(item)) drill.add(rl);
      }
      int[] bounds = resourcePopupBounds(paneL, paneR, top, bottom);
      int popX = bounds[0], popY = bounds[1], popW = bounds[2], popH = bounds[3];

      // Dim background.
      g.fill(paneL, top, paneR, bottom, 0xC0000000);

      // Frame.
      g.fill(popX, popY, popX + popW, popY + popH, PANEL_BG);
      g.fill(popX - 1, popY - 1, popX + popW + 1, popY, FG_ACCENT);
      g.fill(popX - 1, popY + popH, popX + popW + 1, popY + popH + 1, FG_ACCENT);
      g.fill(popX - 1, popY - 1, popX, popY + popH + 1, FG_ACCENT);
      g.fill(popX + popW, popY - 1, popX + popW + 1, popY + popH + 1, FG_ACCENT);

      // Header: item icon + name + total
      int hx = popX + 10, hy = popY + 8;
      var stack = stackForItemId(item);
      g.renderItem(stack, hx, hy);
      int total = 0;
      for (var rl : drill) total += rl.count();
      g.drawString(this.font, shortItemName(item) + " · " + total + " across "
         + drill.size() + " " + (drill.size() == 1 ? "container" : "containers"),
         hx + 22, hy + 4, FG_ACCENT, true);

      String hint = "click outside or press ESC to close";
      g.drawString(this.font, hint, popX + popW - this.font.width(hint) - 10,
         popY + popH - 12, FG_FAINT, true);

      // Production-cap controls row — always visible so the player can
      // enable a cap on any item, not just ones that already have one.
      int rowY = popY + 32;
      var target = findTargetFor(item);
      renderTargetControls(g, popX, rowY, popW, target);
      rowY += 28;

      // Container rows.
      int rowH = 22;
      scaledScissor(g, popX, rowY, popX + popW, popY + popH - 16);
      if (drill.isEmpty()) {
         g.drawString(this.font, "(this item has been emptied since the page was last refreshed)",
            popX + 12, rowY + 4, FG_FAINT, true);
      } else {
         for (var rl : drill) {
            if (rowY + rowH > popY + popH - 16) break;
            // Find label / kind.
            String label = null;
            String kind = "barrel";
            for (var se : this.state.storage()) {
               if (se.packedPos() == rl.packedPos()) {
                  label = se.label();
                  kind  = se.blockKind();
                  break;
               }
            }
            net.minecraft.core.BlockPos cp = net.minecraft.core.BlockPos.of(rl.packedPos());
            g.fill(popX + 8, rowY, popX + popW - 8, rowY + rowH - 2, ROW_BG);
            String left = (label == null || label.isBlank())
               ? (kind + " at " + cp.toShortString())
               : ("\"" + label + "\"  · " + kind + " at " + cp.toShortString());
            g.drawString(this.font, UiText.truncate(this.font, left, popW - 80),
               popX + 14, rowY + 6, FG_PRIMARY, true);
            String right = rl.count() + "×";
            g.drawString(this.font, right,
               popX + popW - 14 - this.font.width(right), rowY + 6, FG_ACCENT, true);
            rowY += rowH;
         }
      }
      g.disableScissor();
   }

   /** Hit-test for the resource grid. Returns the item id of the clicked
    *  cell, or {@code null} if the click was outside the grid. Uses the
    *  same geometry as {@link #renderResourcesTab}. */
   private String hitTestResourceCell(double mouseX, double mouseY, int paneL, int paneR,
                                       int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + RES_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW   = innerR - innerL;
      int cols    = Math.max(1, (gridW + RES_CELL_GAP) / (RES_CELL_W + RES_CELL_GAP));
      int gridUsed = cols * RES_CELL_W + (cols - 1) * RES_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      // Need to apply the same search/sort as render — duplicate the
      // logic (cheap, runs once per click).
      String q = this.resourceSearchBox == null ? "" :
         this.resourceSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
      List<TownStateUpdatePayload.ItemCount> items = new java.util.ArrayList<>();
      for (var ic : this.state.aggregateResources()) {
         if (q.isEmpty() || shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) items.add(ic);
      }
      switch (this.resourceSort) {
         case "name"   -> items.sort((a, b) -> shortItemName(a.itemId()).compareToIgnoreCase(shortItemName(b.itemId())));
         case "recent" -> items.sort((a, b) -> Long.compare(
                                this.resourceRecentByItem.getOrDefault(b.itemId(), 0L),
                                this.resourceRecentByItem.getOrDefault(a.itemId(), 0L)));
         default        -> items.sort((a, b) -> Integer.compare(b.count(), a.count()));
      }
      int scrollPx = this.resourcesGridScrollRows * (RES_CELL_H + RES_CELL_GAP);
      int i = 0;
      for (var ic : items) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (RES_CELL_W + RES_CELL_GAP);
         int cy = gridTop + row * (RES_CELL_H + RES_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + RES_CELL_H < gridTop) { i++; continue; }
         // Reject clicks below gridBot even if the cell technically starts
         // above — the visible (un-clipped) area is the only hit-region.
         if (mouseX >= cx && mouseX < cx + RES_CELL_W
             && mouseY >= cy && mouseY < Math.min(cy + RES_CELL_H, gridBot)
             && mouseY >= Math.max(cy, gridTop)) {
            return ic.itemId();
         }
         i++;
      }
      return null;
   }

   /** Hit-test for the resource-sort chip row. Returns the new sort or
    *  {@code null} for no hit. */
   private String hitTestResourceSortChips(double mouseX, double mouseY, int paneL, int top) {
      int x = paneL + UiTheme.PADDING + 206;
      int y = top + 4;
      int cx = x + this.font.width("Sort:") + 4;
      String[] opts = {"count", "name", "recent"};
      for (String o : opts) {
         int w = this.font.width(o) + 8;
         if (mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + 17) return o;
         cx += w + 3;
      }
      return null;
   }

   // ───────── Parcels tab (new) ─────────

   private void renderParcelsTab(GuiGraphics graphics, int paneL, int paneR,
                                 int top, int bottom, int mouseX, int mouseY) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int contentTop = top + 10;

      UiText.heading(graphics, this.font,
         "Owned land: " + this.state.parcels().size() + " parcels",
         innerL, contentTop);
      UiText.rightFaint(graphics, this.font,
         "type · size · content snapshot",
         innerR, contentTop);

      int listTop = contentTop + 16;
      int listBottom = bottom - 6;
      if (this.state.parcels().isEmpty()) {
         UiText.faint(graphics, this.font,
            "no parcels yet — give a villager a Surveyor's Stake and mark some land",
            innerL, listTop + 10);
         return;
      }
      if (this.parcelsList == null) {
         this.parcelsList = new UiList<>(innerL, listTop, innerR - innerL,
            listBottom - listTop, 30, this::renderParcelRow);
      } else {
         this.parcelsList.setBounds(innerL, listTop, innerR - innerL, listBottom - listTop);
      }
      this.parcelsList.setItems(this.state.parcels());
      this.parcelsList.render(graphics, mouseX, mouseY);
   }

   private void renderParcelRow(UiList.Row<TownStateUpdatePayload.ParcelSummary> row) {
      var p = row.item();
      var g = row.g();
      int x = row.x(), y = row.y(), w = row.w();
      net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
      String headline = p.ownerName() + " · " + p.type() + " · "
         + p.sizeX() + "×" + p.sizeZ() + " @ " + centre.toShortString();
      UiText.heading(g, this.font, headline, x + 6, y + 4);

      StringBuilder content = new StringBuilder();
      if ("PLANT".equals(p.type())) {
         if (p.ripeCrops() > 0)     content.append(p.ripeCrops()).append(" ripe");
         if (p.emptyFarmland() > 0) {
            if (content.length() > 0) content.append(", ");
            content.append(p.emptyFarmland()).append(" empty farmland");
         }
         if (p.tillableTiles() > 0) {
            if (content.length() > 0) content.append(", ");
            content.append(p.tillableTiles()).append(" tillable");
         }
         if (content.length() == 0) content.append("clear");
      } else {
         // ANIMAL — show species count + maturity. unshornSheep folded in.
         if (p.animalCount() == 0) {
            content.append("no animals");
         } else {
            content.append(p.animalCount()).append(" animals");
            if (p.animalBabies() > 0) content.append(" (").append(p.animalBabies()).append(" baby)");
            if (p.unshornSheep() > 0) content.append(", ").append(p.unshornSheep()).append(" unshorn");
         }
      }
      UiText.faint(g, this.font, content.toString(), x + 6, y + 16);
   }

   private void renderVillagersTab(GuiGraphics graphics, int paneL, int paneR,
                                   int top, int bottom, int mouseX, int mouseY) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int spawnBtnH = 26;
      // Padding under the tab strip so content doesn't smash against the underline.
      int contentTop = top + 10;
      int listBottom = bottom - spawnBtnH;

      // Top row: search box label + filter chip strip (drawn). The
      // EditBox itself was placed by initVillagersTabWidgets at top+4
      // (innerL .. innerL+240).
      int chipsX = innerL + 246;
      int chipsY = top + 4;
      renderVillagerFilterChips(graphics, chipsX, chipsY, mouseX, mouseY);

      // Right-aligned column hint pinned alongside the filter row.
      UiText.rightFaint(graphics, this.font, "calls / tokens / cost", innerR, chipsY + 4);

      int listTop = top + 28;             // below the filter row
      List<TownStateUpdatePayload.VillagerSummary> villagers = filteredVillagers();
      if (villagers.isEmpty()) {
         if (this.state.villagers().isEmpty()) {
            UiText.faint(graphics, this.font,
               "no villagers yet — click \"+ Spawn villager\"",
               innerL, listTop + 10);
         } else {
            UiText.faint(graphics, this.font,
               "no villagers match the current filter",
               innerL, listTop + 10);
         }
         return;
      }
      if (this.villagersList == null) {
         this.villagersList = new UiList<>(innerL, listTop, innerR - innerL, listBottom - listTop,
            UiTheme.ROW_HEIGHT_MD, this::renderVillagerRow);
      } else {
         this.villagersList.setBounds(innerL, listTop, innerR - innerL, listBottom - listTop);
      }
      this.villagersList.setItems(villagers);
      this.villagersList.render(graphics, mouseX, mouseY);
   }

   /** Apply the search box + filter chips to the villager roster. */
   private List<TownStateUpdatePayload.VillagerSummary> filteredVillagers() {
      String q = this.villagerSearchBox == null ? "" :
         this.villagerSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
      String prof = this.villagerProfFilter;
      String status = this.villagerStatusFilter;
      List<TownStateUpdatePayload.VillagerSummary> out = new java.util.ArrayList<>();
      for (var v : this.state.villagers()) {
         if (!q.isEmpty() && !v.name().toLowerCase(Locale.ROOT).contains(q)
             && !v.profession().toLowerCase(Locale.ROOT).contains(q)
             && !v.activity().toLowerCase(Locale.ROOT).contains(q)) continue;
         if (!"all".equals(prof) && !v.profession().equals(prof)) continue;
         if (!"all".equals(status)) {
            boolean sleeping = "sleeping".equals(v.activity());
            boolean idle = "idle".equals(v.activity());
            switch (status) {
               case "working" -> { if (sleeping || idle) continue; }
               case "idle"    -> { if (!idle) continue; }
               case "sleeping"-> { if (!sleeping) continue; }
            }
         }
         out.add(v);
      }
      return out;
   }

   /** Filter-chip layout for Villagers tab. Rendered (not vanilla widgets)
    *  so we can wedge them into the dense row strip. Clicks dispatched
    *  via {@link #handleVillagerFilterChipClick}. */
   private void renderVillagerFilterChips(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
      String[] profs = {"all", "farmer", "shepherd", "butcher", "mason", "none"};
      String[] stats = {"all", "working", "idle", "sleeping"};
      int cursor = x;
      cursor = drawChipRow(graphics, cursor, y, "Prof:", profs, this.villagerProfFilter);
      cursor += 12;
      drawChipRow(graphics, cursor, y, "Status:", stats, this.villagerStatusFilter);
   }

   /** Returns the rightmost x after the row, so chip groups can be chained. */
   private int drawChipRow(GuiGraphics graphics, int x, int y, String label,
                            String[] options, String active) {
      graphics.drawString(this.font, label, x, y + 4, FG_DIM, true);
      int cx = x + this.font.width(label) + 4;
      for (String o : options) {
         int w = this.font.width(o) + 8;
         boolean isActive = o.equals(active);
         int bg = isActive ? TAB_ACTIVE_BG : ROW_BG;
         int fg = isActive ? FG_ACCENT    : FG_DIM;
         graphics.fill(cx, y, cx + w, y + 16, bg);
         graphics.fill(cx, y + 16, cx + w, y + 17, isActive ? FG_ACCENT : FG_FAINT);
         graphics.drawString(this.font, o, cx + 4, y + 4, fg, true);
         cx += w + 3;
      }
      return cx;
   }

   /** Hit-test for the chip row. Returns the (group, value) selected, or null. */
   private String[] hitTestVillagerChips(double mouseX, double mouseY, int paneL, int top) {
      int x = paneL + UiTheme.PADDING + 246;
      int y = top + 4;
      String[] profs = {"all", "farmer", "shepherd", "butcher", "mason", "none"};
      String[] stats = {"all", "working", "idle", "sleeping"};
      int cx = x + this.font.width("Prof:") + 4;
      for (String o : profs) {
         int w = this.font.width(o) + 8;
         if (mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + 17) {
            return new String[]{"prof", o};
         }
         cx += w + 3;
      }
      cx += 12;
      cx += this.font.width("Status:") + 4;
      for (String o : stats) {
         int w = this.font.width(o) + 8;
         if (mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + 17) {
            return new String[]{"status", o};
         }
         cx += w + 3;
      }
      return null;
   }

   private void renderVillagerRow(UiList.Row<TownStateUpdatePayload.VillagerSummary> row) {
      var v = row.item();
      var g = row.g();
      int x = row.x(), y = row.y(), w = row.w();

      String label = v.name() + (v.alive() ? "" : " (dead)");
      g.drawString(this.font, label, x + 6, y + 4,
         v.alive() ? UiTheme.BODY : UiTheme.FAINT, true);
      UiText.heading(g, this.font, prettifyActivity(v.activity()), x + 6, y + 14);

      // Inventory line (top-3 + overflow count).
      StringBuilder inv = new StringBuilder();
      var items = v.inventory();
      if (items.isEmpty()) {
         inv.append("inventory: empty");
      } else {
         inv.append("inv: ");
         int n = Math.min(3, items.size());
         for (int i = 0; i < n; i++) {
            if (i > 0) inv.append(", ");
            inv.append(items.get(i).count()).append("× ").append(shortItemName(items.get(i).itemId()));
         }
         if (items.size() > n) inv.append(", +").append(items.size() - n).append(" more");
      }
      int invMaxW = w - 12 - 120;
      UiText.faint(g, this.font, UiText.truncate(this.font, inv.toString(), invMaxW),
         x + 6, y + 24);

      // Right side: HP bar, anchor icons, calls/tokens/cost.
      int hpBarW = 60, hpBarH = 4;
      int hpX = x + w - 6 - hpBarW;
      UiBar.draw(g, hpX, y + 6, hpBarW, hpBarH, v.health(), v.maxHealth());

      String anchor = (v.playerSetHome() && v.playerSetJob()) ? "⌂⚒"
                    : v.playerSetHome() ? "⌂" : v.playerSetJob() ? "⚒" : "—";
      UiText.rightMuted(g, this.font, anchor, x + w - 6, y + 14);

      String metric = String.format(Locale.ROOT,
         "%d / %d / $%.4f", v.llmCalls(), v.inputTokens() + v.outputTokens(), v.estCostUsd());
      UiText.rightFaint(g, this.font, metric, x + w - 6, y + 24);
   }

   private UUID villagerAtPoint(double mouseX, double mouseY) {
      if (this.villagersList == null) return null;
      var hit = this.villagersList.itemAt(mouseX, mouseY);
      return hit == null ? null : hit.uuid();
   }

   /** Schedule activity → friendlier label for inline display. */
   private static String prettifyActivity(String a) {
      if (a == null || a.isEmpty()) return "idle";
      return switch (a) {
         case "waking"        -> "waking up";
         case "going_to_work" -> "heading to work";
         case "at_work"       -> "at work";
         case "going_home"    -> "heading home";
         case "at_home"       -> "at home";
         case "sleeping"      -> "sleeping";
         case "following"     -> "following";
         case "idle"          -> "idle";
         default              -> a;
      };
   }

   private void renderSpawnForm(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + PADDING;
      graphics.drawString(this.font, "Spawn a new villager", innerL, top, FG_ACCENT, true);
      graphics.drawString(this.font, "Name (leave blank for the LLM to choose)",
         innerL, top + 14, FG_DIM, true);
      graphics.drawString(this.font, "Persona seed — informs the auto-generated backstory",
         innerL, top + 56, FG_DIM, true);
   }

   private void renderVillagerDetail(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) return;
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;

      graphics.drawString(this.font,
         Component.literal(v.name()).withStyle(ChatFormatting.GOLD),
         innerL, top, FG_ACCENT, true);
      String act = prettifyActivity(v.activity());
      graphics.drawString(this.font, act, innerR - this.font.width(act), top, FG_DIM, true);

      graphics.drawString(this.font, "Persona seed", innerL, top + 14, FG_DIM, true);

      int backstoryTop = top + 100;
      graphics.drawString(this.font, "Backstory", innerL, backstoryTop, FG_DIM, true);
      String bs = v.backstory() == null || v.backstory().isBlank() ? "(none yet)" : v.backstory();
      List<FormattedCharSequence> wrapped = this.font.split(Component.literal(bs), innerR - innerL);
      int by = backstoryTop + 12;
      int max = bottom - 50;
      for (FormattedCharSequence seq : wrapped) {
         if (by + this.font.lineHeight > max) break;
         graphics.drawString(this.font, seq, innerL, by, FG_PRIMARY, true);
         by += this.font.lineHeight + 2;
      }

      String metric = String.format(Locale.ROOT,
         "%d calls • %d+%d tok • $%.4f • %d pins • beliefs %s",
         v.llmCalls(), v.inputTokens(), v.outputTokens(), v.estCostUsd(),
         v.pinnedFacts().size(),
         (v.beliefs() == null || v.beliefs().isBlank()) ? "—" : "✓");
      graphics.drawString(this.font, metric, innerL, bottom - 36, FG_FAINT, true);
   }

   private void renderMemories(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) return;
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;
      int rowH = 22;

      // ----- Fixed header block (above the scrollable list) -----
      graphics.drawString(this.font,
         Component.literal(v.name() + " — Memories").withStyle(ChatFormatting.GOLD),
         innerL, top, FG_ACCENT, true);

      // Beliefs blob — 2 lines max so the section is compact.
      graphics.drawString(this.font, "Beliefs (auto-updated nightly)", innerL, top + 14, FG_DIM, true);
      String beliefs = v.beliefs() == null || v.beliefs().isBlank() ? "(none yet)" : v.beliefs();
      List<FormattedCharSequence> beliefLines = this.font.split(Component.literal(beliefs), innerR - innerL);
      int by = top + 26;
      int beliefsMax = 2;
      for (int i = 0; i < beliefLines.size() && i < beliefsMax; i++) {
         graphics.drawString(this.font, beliefLines.get(i), innerL, by, FG_PRIMARY, true);
         by += this.font.lineHeight + 1;
      }
      if (beliefLines.size() > beliefsMax) {
         graphics.drawString(this.font, "… (" + (beliefLines.size() - beliefsMax) + " more lines)",
            innerL, by, FG_FAINT, true);
      }

      // Stats line, just above the scroll region.
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      String stats = String.format(Locale.ROOT,
         "memories: %d • compacted day %d • todos: %d open • pins: %d/100",
         v.memoryCount(), v.lastCompactedDay(), openTodos, v.pinnedFacts().size());
      graphics.drawString(this.font, stats, innerL, top + 64, FG_FAINT, true);

      // ----- Scrollable list (both sections share the scroll) -----
      int listTop = top + MEMORIES_LIST_TOP_OFFSET;
      int listBottom = bottom - 30;
      scaledScissor(graphics, innerL, listTop, innerR, listBottom);
      int y = listTop - this.memoryScrollOffset;

      // Section: open commitments
      if (openTodos > 0) {
         graphics.drawString(this.font,
            "Open commitments (" + openTodos + ")", innerL, y, FG_DIM, true);
         y += 12;
         for (var t : v.todos()) {
            if (!"open".equals(t.status())) continue;
            graphics.fill(innerL, y, innerR, y + rowH - 2, ROW_BG);
            String txt = truncate(t.text(), innerR - innerL - 8);
            graphics.drawString(this.font, txt, innerL + 4, y + 3, FG_PRIMARY, true);
            graphics.drawString(this.font, "day " + t.createdDay(), innerL + 4, y + 13, FG_FAINT, true);
            y += rowH;
         }
         y += 10;   // section gap
      }

      // Section: pinned facts
      graphics.drawString(this.font,
         "Pinned facts (" + v.pinnedFacts().size() + "/100)", innerL, y, FG_DIM, true);
      y += 12;
      if (v.pinnedFacts().isEmpty()) {
         graphics.drawString(this.font, "(none — add below to lock-in long-term memories)",
            innerL, y, FG_FAINT, true);
      } else {
         for (TownStateUpdatePayload.PinSummary p : v.pinnedFacts()) {
            graphics.fill(innerL, y, innerR - 50, y + rowH - 2, ROW_BG);
            int textFg = "resolved".equals(p.status()) ? FG_RESOLVED : FG_PRIMARY;
            String shown = truncate(p.text(), (innerR - 50) - innerL - 8);
            graphics.drawString(this.font, shown, innerL + 4, y + 3, textFg, true);
            String tag = "resolved".equals(p.status()) ? "resolved" : ("day " + p.createdDay());
            graphics.drawString(this.font, tag, innerL + 4, y + 13, FG_FAINT, true);
            y += rowH;
         }
      }
      graphics.disableScissor();
   }

   private void renderBeliefsFull(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) return;
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;

      graphics.drawString(this.font,
         Component.literal(v.name() + " — Beliefs").withStyle(ChatFormatting.GOLD),
         innerL, top, FG_ACCENT, true);
      graphics.drawString(this.font, "auto-updated nightly • " + v.beliefs().length() + " chars",
         innerL, top + 12, FG_FAINT, true);

      int contentTop = top + 26;
      int contentBottom = bottom - 4;
      scaledScissor(graphics, innerL, contentTop, innerR, contentBottom);
      String beliefs = v.beliefs() == null || v.beliefs().isBlank() ? "(none yet)" : v.beliefs();
      List<FormattedCharSequence> wrapped = this.font.split(Component.literal(beliefs), innerR - innerL);
      int y = contentTop - this.beliefsScrollOffset;
      for (FormattedCharSequence seq : wrapped) {
         if (y + this.font.lineHeight < contentTop) { y += this.font.lineHeight + 2; continue; }
         if (y > contentBottom) break;
         graphics.drawString(this.font, seq, innerL, y, FG_PRIMARY, true);
         y += this.font.lineHeight + 2;
      }
      graphics.disableScissor();
   }

   // ───── per-villager log view (filtered to entries mentioning this villager) ─────

   private void initVillagerLogWidgets(int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) {
         this.mode = Mode.VILLAGER_DETAIL;
         rebuildAdminWidgets();
         return;
      }
      int innerR = paneR - UiTheme.PADDING;
      addRenderableWidget(Button.builder(Component.literal("← Back"), b -> {
         this.mode = Mode.VILLAGER_DETAIL;
         rebuildAdminWidgets();
      }).bounds(innerR - 60, top, 60, 18).build());
   }

   private void renderVillagerLog(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) return;
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      // Header: villager name + scope hint.
      graphics.drawString(this.font, Component.literal(v.name()).withStyle(ChatFormatting.GOLD),
         innerL, top, UiTheme.HEADING, true);
      UiText.muted(graphics, this.font, "Activity log (entries mentioning this villager)",
         innerL, top + 12);

      // Filter the global town log to lines containing this villager's name.
      // Case-insensitive substring; cheap because the log itself is capped.
      String needle = v.name().toLowerCase(Locale.ROOT);
      List<TownStateUpdatePayload.LogEntry> filtered = new java.util.ArrayList<>();
      for (var e : this.state.log()) {
         if (e.message().toLowerCase(Locale.ROOT).contains(needle)) filtered.add(e);
      }
      if (filtered.isEmpty()) {
         UiText.faint(graphics, this.font,
            "(no entries yet — actions, exchanges, reflexes will show up here)",
            innerL, top + 30);
         return;
      }
      int y = top + 30;
      int maxBottom = bottom - 4;
      for (int i = filtered.size() - 1; i >= 0; i--) {
         if (y > maxBottom) break;
         var e = filtered.get(i);
         int color = switch (e.level()) {
            case "EXCHANGE" -> 0xFFB0D0FF;
            case "COMPACT"  -> UiTheme.OK;
            case "DIALOGUE" -> UiTheme.HEADING;
            case "WARN"     -> UiTheme.BAD;
            default          -> UiTheme.MUTED;
         };
         String tag = "[" + e.level().toLowerCase(Locale.ROOT) + "]";
         graphics.drawString(this.font, tag, innerL, y, color, true);
         int tagW = this.font.width(tag) + 4;
         List<FormattedCharSequence> wrapped =
            this.font.split(Component.literal(e.message()), innerR - innerL - tagW);
         int textX = innerL + tagW;
         for (FormattedCharSequence line : wrapped) {
            if (y > maxBottom) break;
            graphics.drawString(this.font, line, textX, y, UiTheme.BODY, true);
            y += this.font.lineHeight + 1;
         }
         y += 1;
      }
   }

   private void renderLogTab(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;
      graphics.drawString(this.font, "Activity log (latest first)", innerL, top, FG_DIM, true);
      List<TownStateUpdatePayload.LogEntry> entries = this.state.log();
      if (entries.isEmpty()) {
         graphics.drawString(this.font, "(no activity yet — exchanges, dialogue, compaction will appear here)",
            innerL, top + 14, FG_FAINT, true);
         return;
      }
      int y = top + 14;
      int maxBottom = bottom - 4;
      for (int i = entries.size() - 1; i >= 0; i--) {
         if (y > maxBottom) break;
         TownStateUpdatePayload.LogEntry e = entries.get(i);
         int color = switch (e.level()) {
            case "EXCHANGE" -> 0xFFB0D0FF;
            case "COMPACT"  -> FG_RESOLVED;
            case "DIALOGUE" -> FG_ACCENT;
            case "WARN"     -> FG_ERROR;
            default          -> FG_DIM;
         };
         String tag = "[" + e.level().toLowerCase(Locale.ROOT) + "]";
         graphics.drawString(this.font, tag, innerL, y, color, true);
         int tagW = this.font.width(tag) + 4;
         // Wrap the message body across multiple lines instead of truncating.
         List<FormattedCharSequence> wrapped =
            this.font.split(Component.literal(e.message()), innerR - innerL - tagW);
         int textX = innerL + tagW;
         for (FormattedCharSequence line : wrapped) {
            if (y > maxBottom) break;
            graphics.drawString(this.font, line, textX, y, FG_PRIMARY, true);
            y += this.font.lineHeight + 1;
         }
         y += 1;   // small gap between entries
      }
   }

   private String truncate(String text, int maxPx) {
      if (this.font.width(text) <= maxPx) return text;
      while (text.length() > 0 && this.font.width(text + "…") > maxPx) {
         text = text.substring(0, text.length() - 1);
      }
      return text + "…";
   }

   @Override
   public void tick() {
      super.tick();
      // While on either log view (town-wide or per-villager filtered), poll
      // the server every ~2s so activity surfaces live.
      boolean townLogOpen = this.tab == Tab.LOG && this.mode == Mode.NORMAL;
      boolean perVillagerLogOpen = this.mode == Mode.VILLAGER_LOG;
      if (townLogOpen || perVillagerLogOpen) {
         if (++this.refreshTimer >= 40) {
            this.refreshTimer = 0;
            PacketDistributor.sendToServer(AdminActionPayload.open(townPos()));
         }
      } else {
         this.refreshTimer = 0;
      }
   }

   @Override
   public boolean isPauseScreen() { return false; }
}
