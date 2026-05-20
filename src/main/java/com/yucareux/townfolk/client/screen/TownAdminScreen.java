package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.client.ui.UiBar;
import com.yucareux.townfolk.client.ui.UiCard;
import com.yucareux.townfolk.client.ui.UiList;
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

   // ── Theme aliases — single source of truth is UiTheme. ──
   // These per-screen names are kept so the rest of this file's call
   // sites need no churn; their values now flow through UiTheme so a
   // future palette tweak lands in one place.
   private static final int PANEL_BG       = UiTheme.PANEL_BG;
   private static final int PANEL_BORDER   = UiTheme.PANEL_BORDER;
   private static final int HEADER_BG      = UiTheme.HEADER_BG;
   private static final int TAB_ACTIVE_BG  = UiTheme.TAB_ACTIVE;
   private static final int ROW_BG         = UiTheme.ROW_BG;
   private static final int ROW_BG_HOVER   = UiTheme.ROW_BG_HOVER;
   private static final int FG_PRIMARY     = UiTheme.BODY;
   private static final int FG_ACCENT      = UiTheme.HEADING;
   private static final int FG_DIM         = UiTheme.MUTED;
   private static final int FG_FAINT       = UiTheme.FAINT;
   private static final int FG_ERROR       = UiTheme.ERROR;
   private static final int FG_RESOLVED    = UiTheme.RESOLVED;

   private static final int PADDING = UiTheme.PADDING;

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

   private enum Tab { OVERVIEW, VILLAGERS, RESOURCES, PARCELS, TRADE, TASKS, LOG }
   private enum Mode { NORMAL, SPAWN_FORM, VILLAGER_DETAIL, MEMORIES, BELIEFS_FULL, VILLAGER_LOG }

   /** Package-private from Stage 15b.2 so extracted section renderers
    *  in this package can read the latest state snapshot. */
   TownStateUpdatePayload state;
   private Tab tab = Tab.OVERVIEW;

   /** Toolkit-backed lists. Lazily reconstructed in {@link #ensureListsForBounds}
    *  so resizing the screen rebinds bounds without losing scroll position
    *  between frames. */
   /** Scroll offset (rows) for the Villagers grid. Mirrors
    *  {@link #resourcesGridScrollRows}. */
   /** Row scroll for the Villagers grid. Pkg-private — mutated by
    *  {@link VillagersRenderer} (clamping) and the scroll handler. */
   int villagersGridScrollRows = 0;

   /** Profession id → cached ItemStack used as that profession's tile
    *  icon. Built lazily on first lookup; reused across frames. Falls
    *  back to villager_spawn_egg for unknown / "none" professions. */
   private final java.util.Map<String, net.minecraft.world.item.ItemStack> villagerIconByProfession =
      new java.util.HashMap<>();
   // ── New for redesigned tabs ──
   /** Row-level scroll offset for the Resources grid. Driven by the
    *  mouse wheel; clamped at render time once the row count is known. */
   /** Pkg-private — {@link ResourcesRenderer} reads + clamps. */
   int resourcesGridScrollRows = 0;
   private UiList<TownStateUpdatePayload.ResourceLoc> resourcesDrillList;
   /** Scroll offset (rows) for the Parcels grid. Pkg-private — mutated
    *  by both {@link ParcelsRenderer} (clamping) and the click/scroll
    *  handlers on this class. */
   int parcelsGridScrollRows = 0;

   /** Selected parcel id (popup detail open). Null = no popup. */
   String selectedParcelId = null;
   /** When set, the Resources tab renders a floating popup listing every
    *  barrel that holds this item. */
   String selectedResourceItem;

   /** Resource-grid cell wrapper. Combines a wire-format ItemCount with
    *  a flag indicating whether the row is a town-treasury entry
    *  (claimable payout) vs. a regular stockpile row. Lets the same
    *  grid render both, with distinct styling per cell type and a
    *  branched click handler (treasury → withdraw, stockpile →
    *  production-cap popup). */
   // ResCell record + Resources tab render live in {@link ResourcesRenderer} (15b.4.g).
   /** Search filter on the Resources grid — matches against item id /
    *  pretty name. */
   EditBox resourceSearchBox;
   /** Sort key for the Resources grid: "count" (desc) / "name" (a-z) /
    *  "recent" (max container lastUpdatedTick across holders). */
   String resourceSort = "count";
   /** Cached "recent activity" tick per item id, recomputed once per
    *  render. Lets the "recent" sort sit in O(n) without a second walk
    *  over containers per comparison. */
   final java.util.Map<String, Long> resourceRecentByItem = new java.util.HashMap<>();

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
   /** Pkg-private — read by {@link VillagersRenderer} for filtering. */
   EditBox villagerSearchBox;
   /** "all" / "farmer" / "shepherd" / "butcher" / "mason" / "none".
    *  Filter chip click toggles into / out of "all". */
   String villagerProfFilter = "all";
   /** "all" / "working" / "idle" / "sleeping" — filter chip on activity. */
   String villagerStatusFilter = "all";
   private Mode mode = Mode.NORMAL;

   /** Sub-tabs inside VILLAGER_DETAIL mode. Each renders its own body
    *  and registers its own widgets in {@link #initVillagerDetailWidgets}.
    *  Persistent across rebuilds so the player stays on the tab they
    *  picked when state updates arrive.
    *
    *  <p>Package-private from Stage 15b.2 so the extracted section
    *  renderers in this package can read the active tab. */
   enum DetailTab { OVERVIEW, BACKSTORY, MEMORIES, TODOS, PARCELS, RELATIONSHIPS, ACTIONS }
   DetailTab detailTab = DetailTab.OVERVIEW;

   /** Scroll offset (px) for tab bodies that need it (Backstory text,
    *  Memories list, Relationships table). Reset on tab switch.
    *  Package-private from Stage 15b.2. */
   int detailTabScroll = 0;
   /** Package-private from Stage 15b.2. */
   UUID selectedVillager;
   private int scrollOffset;
   private int memoryScrollOffset;
   private int beliefsScrollOffset;
   /** Grid scroll for the Tasks tab (rows). Replaces the old
    *  list-pixel tasksScrollOffset field that became dead when the
    *  Tasks tab moved from list rows to grid tiles. */
   /** Pkg-private — {@link TasksRenderer} reads + clamps. */
   int tasksGridScrollRows = 0;

   /** Selected (owner-uuid, todo-id) for the popup. Encoded as
    *  "uuid|todoId" since we need both to send TODO_COMPLETE /
    *  TODO_ABANDON. Null = no popup. */
   String selectedTodoKey = null;
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

   /** Font accessor for sibling-package section renderers. The
    *  inherited {@code font} field is {@code protected} (declared on
    *  net.minecraft Screen), which means a class in our package that
    *  is NOT a subclass of Screen can't reach it. This getter is the
    *  single bridge. Package-private. */
   net.minecraft.client.gui.Font font() { return this.font; }

   /** Minecraft instance — needed by sections that read mouse coords
    *  via {@code minecraft.mouseHandler.xpos()}. */
   net.minecraft.client.Minecraft minecraft() { return this.minecraft; }

   /** Package-private from Stage 15b.2 so section renderers in the
    *  same package can resolve a villager UUID without reaching
    *  through reflection. */
   Optional<TownStateUpdatePayload.VillagerSummary> findVillager(UUID uuid) {
      for (var v : this.state.villagers()) if (v.uuid().equals(uuid)) return Optional.of(v);
      return Optional.empty();
   }

   long townPos() { return this.state.townSquarePos(); }

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
               case TRADE -> {} // render-only (placeholder until Stage 3)
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
      // Back goes to the VILLAGER_DETAIL Memories tab — that's where
      // the Expand-beliefs button lives now. The old standalone
      // MEMORIES mode is unreachable post-Stage 13 refactor; this
      // routing keeps the navigation consistent.
      addRenderableWidget(Button.builder(Component.literal("← Back"), b -> {
         this.mode = Mode.VILLAGER_DETAIL;
         this.detailTab = DetailTab.MEMORIES;
         this.beliefsScrollOffset = 0;
         rebuildAdminWidgets();
      }).bounds(innerR - 60, top, 60, 18).build());
   }

   // ----- Overview tab -----

   // OV_* layout constants now live on {@link OverviewRenderer} so the
   // render and init paths share one source of truth (stage 15b.4.b).

   private void initOverviewTabWidgets(int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int width = innerR - innerL;
      // Align init Y coords with render-side labels via the shared
      // offset constant (audit item 7.1 — Overview was the last tab
      // still floating 4 px above its labels).
      int t = top + INIT_TO_RENDER_TOP;

      this.townNameBox = new EditBox(this.font, innerL, t + OverviewRenderer.OV_NAME_BOX_Y, width - 80, 20,
         Component.literal("town name"));
      this.townNameBox.setMaxLength(60);
      this.townNameBox.setValue(this.state.townName());
      addRenderableWidget(this.townNameBox);

      addRenderableWidget(Button.builder(Component.literal("Rename"), b -> {
         String name = this.townNameBox.getValue().trim();
         if (!name.isEmpty()) {
            PacketDistributor.sendToServer(AdminActionPayload.renameTown(townPos(), name));
         }
      }).bounds(innerR - 74, t + OverviewRenderer.OV_NAME_BOX_Y, 74, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Refresh"), b ->
         PacketDistributor.sendToServer(AdminActionPayload.refreshSpend(townPos()))
      ).bounds(innerR - 74, t + OverviewRenderer.OV_REFRESH_Y - 2, 74, 20).build());

      int factsListTop = t + OverviewRenderer.OV_FACTS_Y + 14;
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

   /** Pkg-private — {@link TasksRenderer} consumes these rows. */
   record TaskRow(TownStateUpdatePayload.VillagerSummary owner,
                  TownStateUpdatePayload.TodoSummary todo) {}

   /** All open todos across all villagers, owner-grouped order, newest day first inside each. */
   List<TaskRow> collectOpenTasks() {
      List<TaskRow> rows = new java.util.ArrayList<>();
      for (var v : this.state.villagers()) {
         for (var t : v.todos()) {
            if ("open".equals(t.status())) rows.add(new TaskRow(v, t));
         }
      }
      return rows;
   }

   private void initTasksTabWidgets(int paneL, int paneR, int top, int bottom) {
      // Empty by design. The Tasks tab is a grid of custom-drawn tiles
      // with a click-modal pattern (renderTaskCell + renderTaskPopup
      // + hitTestTaskPopupButton). The "Mark done" action is now a
      // chip inside the modal — no per-row vanilla Button widgets to
      // pre-register here.
   }

   // ───────── Trade tab ─────────
   //
   // Render lives in {@link TradeRenderer} (stage 15b.4.c).
   // State below mutates from click + scroll handlers on this class.

   /** Row offset for grid scroll. Reset on tab switch. Package-private
    *  because {@link TradeRenderer} both reads and clamps it. */
   int tradeGridScrollRows = 0;

   /** Currently-selected offer id (popup open). null = grid mode. */
   String selectedTradeOfferId = null;

   // Trade tab render + hit-test live in {@link TradeRenderer} (15b.4.c).


   // Tasks tab render + popup + grid hit-test live in {@link TasksRenderer} (15b.4.f).

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
      int[] bounds = ResourcesRenderer.resourcePopupBounds(this, paneL_eff, paneR_eff, renderTop, renderBottom);
      int popX = bounds[0], popY = bounds[1], popW = bounds[2];

      // Target row inside the popup: 28px tall, starts at popY+32.
      int rowY = popY + 32;
      var target = ResourcesRenderer.findTargetFor(this, this.selectedResourceItem);

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
      // Spawn form lives inside a centred modal card. Compute the
      // card rect (matches renderSpawnForm) and place inputs inside.
      int[] card = spawnFormCardRect(paneL, paneR, top, bottom);
      int cardL = card[0], cardT = card[1], cardW = card[2], cardH = card[3];
      int innerL = cardL + 12;
      int innerR = cardL + cardW - 12;
      int innerW = innerR - innerL;

      // Name input under the title.
      this.spawnNameBox = new EditBox(this.font, innerL, cardT + 36, innerW, 20,
         Component.literal("name (blank = LLM picks)"));
      this.spawnNameBox.setMaxLength(40);
      addRenderableWidget(this.spawnNameBox);

      // Persona seed input below.
      this.spawnSeedBox = new EditBox(this.font, innerL, cardT + 80, innerW, 80,
         Component.literal("persona seed (optional — 1-3 sentences)"));
      this.spawnSeedBox.setMaxLength(500);
      addRenderableWidget(this.spawnSeedBox);

      // Custom-drawn Cancel + Spawn chips — handled in mouseClicked.
      // No vanilla Button widgets here, matching the modal language of
      // BuildingPermit, ParcelTypePopup, and the new villager-detail
      // modal.
      this.setInitialFocus(this.spawnNameBox);
   }

   /** Geometry of the spawn-form modal card. Drawn + hit-tested
    *  consistently from one source of truth. */
   private int[] spawnFormCardRect(int paneL, int paneR, int top, int bottom) {
      int cardW = Math.min(420, paneR - paneL - 60);
      int cardH = 220;
      int cardL = (paneL + paneR - cardW) / 2;
      int cardT = (top + bottom - cardH) / 2;
      return new int[]{cardL, cardT, cardW, cardH};
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
      int width  = innerR - innerL;

      // Body / footer Y coordinates derived from the reserved bands.
      int tabBodyTop = top + VillagerDetailRenderer.DETAIL_HEADER_H
                          + VillagerDetailRenderer.DETAIL_TAB_STRIP_H + 4;
      int tabBodyBottom = bottom - VillagerDetailRenderer.DETAIL_FOOTER_H;

      // ── Footer (always visible) ───────────────────────────────────
      // Left: Back button (returns to Villagers grid).
      // Middle: Open Dialogue (legacy modal; player ↔ villager chat).
      // Right: Remove (kill villager; with confirmation via single click for now).
      addRenderableWidget(Button.builder(Component.literal("← Back"), b -> {
         this.mode = Mode.NORMAL;
         this.tab = Tab.VILLAGERS;
         this.selectedVillager = null;
         this.detailTab = DetailTab.OVERVIEW;
         this.detailTabScroll = 0;
         rebuildAdminWidgets();
      }).bounds(innerL, bottom - 22, 60, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Open Dialogue"), b -> {
         // /townfolk talk <uuid> — server-side command routes to
         // DialogueService.openWithVillager which pushes an
         // OpenDialoguePayload back to the client. Same path
         // NeedsService.maybeHail builds clickable chat links for.
         if (this.minecraft != null && this.minecraft.player != null
             && this.minecraft.player.connection != null) {
            this.minecraft.player.connection.sendCommand("townfolk talk " + v.uuid());
         }
         this.onClose();      // hand the screen back so dialogue can take over
      }).bounds(innerL + 64, bottom - 22, 96, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
         PacketDistributor.sendToServer(AdminActionPayload.remove(townPos(), v.uuid()));
         this.mode = Mode.NORMAL;
         this.tab = Tab.VILLAGERS;
         this.selectedVillager = null;
         this.detailTab = DetailTab.OVERVIEW;
         rebuildAdminWidgets();
      }).bounds(innerR - 64, bottom - 22, 64, 20).build());

      // ── Per-tab widgets ───────────────────────────────────────────
      switch (this.detailTab) {
         case OVERVIEW -> { /* read-only — no widgets */ }
         case BACKSTORY -> initDetailBackstoryWidgets(v, innerL, innerR, tabBodyTop, tabBodyBottom);
         case MEMORIES  -> initDetailMemoriesWidgets(v, innerL, innerR, tabBodyTop, tabBodyBottom);
         case TODOS     -> initDetailTodosWidgets(v, innerL, innerR, tabBodyTop, tabBodyBottom);
         case PARCELS   -> { /* read-only for now — popup driven from clicks */ }
         case RELATIONSHIPS -> { /* read-only */ }
         case ACTIONS   -> initDetailActionsWidgets(v, innerL, innerR, tabBodyTop, tabBodyBottom);
      }
   }

   private void initDetailBackstoryWidgets(TownStateUpdatePayload.VillagerSummary v,
                                            int innerL, int innerR, int top, int bottom) {
      int width = innerR - innerL;
      // Persona seed editor — first widget in the Backstory tab.
      this.detailSeedBox = new EditBox(this.font, innerL, top + 16, width, 60,
         Component.literal("persona seed"));
      this.detailSeedBox.setMaxLength(500);
      this.detailSeedBox.setValue(v.personaSeed());
      addRenderableWidget(this.detailSeedBox);

      addRenderableWidget(Button.builder(Component.literal("Save persona"), b -> {
         String seed = this.detailSeedBox.getValue().trim();
         PacketDistributor.sendToServer(AdminActionPayload.editPersona(townPos(), v.uuid(), seed));
      }).bounds(innerR - 200, top + 80, 96, 20).build());

      addRenderableWidget(Button.builder(Component.literal("Regen story"), b ->
         PacketDistributor.sendToServer(AdminActionPayload.regenerateBackstory(townPos(), v.uuid()))
      ).bounds(innerR - 100, top + 80, 100, 20).build());
   }

   private void initDetailMemoriesWidgets(TownStateUpdatePayload.VillagerSummary v,
                                           int innerL, int innerR, int top, int bottom) {
      int width = innerR - innerL;
      // Add-fact row, anchored to the bottom of the tab body.
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

      // "Expand beliefs" button — opens the full-page belief view.
      addRenderableWidget(Button.builder(Component.literal("Expand beliefs"), b -> {
         this.mode = Mode.BELIEFS_FULL;
         this.beliefsScrollOffset = 0;
         rebuildAdminWidgets();
      }).bounds(innerR - 100, top, 100, 14).build());

      // Per-pin chip buttons (✓ resolve / ✕ delete). Lined up against
      // the rendered pin rows in renderDetailMemoriesBody — Y math must
      // match. The render draws a "Pinned facts (N/100)" header line
      // before the first pin row, so we skip 12 px to match.
      int listTop = top + VillagerDetailRenderer.DETAIL_MEMORIES_LIST_TOP;
      int rowH = 22;
      int y = listTop - this.detailTabScroll;
      y += 12;                  // skip "Pinned facts (N/100)" section header
      for (TownStateUpdatePayload.PinSummary p : v.pinnedFacts()) {
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > bottom - 40)    break;
         int btnX = innerR - 44;
         if (!"resolved".equals(p.status())) {
            addRenderableWidget(Button.builder(Component.literal("✓"), b ->
               PacketDistributor.sendToServer(
                  AdminActionPayload.pinResolvePersonal(townPos(), v.uuid(), p.id()))
            ).bounds(btnX, y + 2, 18, 18).build());
         }
         addRenderableWidget(Button.builder(Component.literal("✕"), b ->
            PacketDistributor.sendToServer(
               AdminActionPayload.pinRemovePersonal(townPos(), v.uuid(), p.id()))
         ).bounds(btnX + 22, y + 2, 18, 18).build());
         y += rowH;
      }
   }

   private void initDetailTodosWidgets(TownStateUpdatePayload.VillagerSummary v,
                                        int innerL, int innerR, int top, int bottom) {
      int listTop = top + 14;
      int rowH = 22;
      int y = listTop - this.detailTabScroll;
      for (var t : v.todos()) {
         if (!"open".equals(t.status())) continue;
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > bottom - 6)     break;
         int btnX = innerR - 44;
         addRenderableWidget(Button.builder(Component.literal("✓"), b ->
            PacketDistributor.sendToServer(
               AdminActionPayload.todoComplete(townPos(), v.uuid(), t.id()))
         ).bounds(btnX, y + 2, 18, 18).build());
         addRenderableWidget(Button.builder(Component.literal("✕"), b ->
            PacketDistributor.sendToServer(
               AdminActionPayload.todoAbandon(townPos(), v.uuid(), t.id()))
         ).bounds(btnX + 22, y + 2, 18, 18).build());
         y += rowH;
      }
   }

   private void initDetailActionsWidgets(TownStateUpdatePayload.VillagerSummary v,
                                          int innerL, int innerR, int top, int bottom) {
      // Open log — VILLAGER_LOG mode still exists, accessed from here.
      addRenderableWidget(Button.builder(Component.literal("Open log"), b -> {
         this.mode = Mode.VILLAGER_LOG;
         rebuildAdminWidgets();
      }).bounds(innerL, top + 14, 120, 20).build());

      // Refresh state — re-emit TownStateUpdatePayload so any
      // background changes (e.g. inventory) reflect immediately.
      addRenderableWidget(Button.builder(Component.literal("Refresh state"), b ->
         PacketDistributor.sendToServer(AdminActionPayload.open(townPos()))
      ).bounds(innerL + 128, top + 14, 120, 20).build());
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
            this.tradeGridScrollRows = 0;
            this.selectedTradeOfferId = null;
            rebuildAdminWidgets();
            return true;
         }
         if (this.tab == Tab.VILLAGERS) {
            // Filter chips intercept before the list row hit. The chips
            // are rendered using render-side contentTop (paneT + 22 +
            // 16 = paneT + 38), so the hit-test uses the same.
            String[] chipHit = VillagersRenderer.hitTestChips(this, mouseX, mouseY, paneL, paneT + 22 + 16);
            if (chipHit != null) {
               if ("prof".equals(chipHit[0]))   this.villagerProfFilter = chipHit[1];
               if ("status".equals(chipHit[0])) this.villagerStatusFilter = chipHit[1];
               this.villagersGridScrollRows = 0;
               rebuildAdminWidgets();
               return true;
            }
            UUID clicked = VillagersRenderer.villagerAtPoint(this, mouseX, mouseY,
               panelLeft(), panelRight(), panelTop() + 34, panelBottom() - PADDING);
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
               int[] bounds = ResourcesRenderer.resourcePopupBounds(this, paneL, paneR, contentTop, contentBottom);
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
            String sortHit = ResourcesRenderer.hitTestSortChips(this, mouseX, mouseY, paneL, contentTop);
            if (sortHit != null) {
               this.resourceSort = sortHit;
               return true;
            }
            // Cell click. Branch on whether this is a stockpile or
            // treasury cell:
            //  - Stockpile → open the production-cap popup (existing flow).
            //  - Treasury  → withdraw immediately. Plain click takes
            //    everything; shift-click takes 1.
            ResourcesRenderer.ResCell cellHit = ResourcesRenderer.hitTestCell(this, mouseX, mouseY, paneL, paneR, contentTop, contentBottom);
            if (cellHit != null) {
               if (cellHit.treasury()) {
                  boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
                  int requested = shift ? 1 : 0;
                  PacketDistributor.sendToServer(
                     new com.yucareux.townfolk.network.WithdrawTreasuryPayload(
                        townPos(), cellHit.ic().itemId(), requested));
               } else {
                  this.selectedResourceItem = cellHit.ic().itemId();
                  rebuildAdminWidgets();
               }
               return true;
            }
         }
         if (this.tab == Tab.PARCELS) {
            int paneB2 = paneT + panelHeight();
            int paneR2 = paneL + panelWidth();
            int contentTop = paneT + 22 + 16;
            int contentBottom = paneB2 - PADDING;

            // Popup open → button hit-test first, else outside-click closes.
            if (this.selectedParcelId != null) {
               String btn = ParcelsRenderer.hitTestPopupButton(mouseX, mouseY, paneL, paneR2,
                  contentTop, contentBottom);
               var p = findParcel(this.selectedParcelId);
               if ("open".equals(btn) && p != null) {
                  if ("PLANT".equals(p.type())) {
                     PacketDistributor.sendToServer(
                        AdminActionPayload.openParcelEditor(townPos(), p.id()));
                  } else {
                     PacketDistributor.sendToServer(
                        AdminActionPayload.openAnimalPlan(townPos(), p.id()));
                  }
                  this.selectedParcelId = null;
                  return true;
               }
               if ("close".equals(btn)) {
                  this.selectedParcelId = null;
                  return true;
               }
               // Click outside popup closes.
               this.selectedParcelId = null;
               return true;
            }

            // No popup → grid hit-test.
            var hit = ParcelsRenderer.parcelAtPoint(this, mouseX, mouseY,
               paneL, paneR2, contentTop, contentBottom);
            if (hit != null) {
               this.selectedParcelId = hit.id();
               return true;
            }
         }
         if (this.tab == Tab.TASKS) {
            int paneB2 = paneT + panelHeight();
            int paneR2 = paneL + panelWidth();
            int contentTop = paneT + 22 + 16;
            int contentBottom = paneB2 - PADDING;

            if (this.selectedTodoKey != null) {
               String btn = TasksRenderer.hitTestPopupButton(mouseX, mouseY, paneL, paneR2,
                  contentTop, contentBottom);
               var r = TasksRenderer.findTodoByKey(this, this.selectedTodoKey);
               if ("done".equals(btn) && r != null) {
                  PacketDistributor.sendToServer(
                     AdminActionPayload.todoComplete(townPos(), r.owner().uuid(), r.todo().id()));
                  this.selectedTodoKey = null;
                  return true;
               }
               if ("close".equals(btn)) {
                  this.selectedTodoKey = null;
                  return true;
               }
               this.selectedTodoKey = null;
               return true;
            }

            var hit = TasksRenderer.taskAtPoint(this, mouseX, mouseY,
               panelLeft(), panelRight(), panelTop() + 34, panelBottom() - PADDING);
            if (hit != null) {
               this.selectedTodoKey = hit.owner().uuid() + "|" + hit.todo().id();
               return true;
            }
         }
         if (this.tab == Tab.TRADE) {
            int paneR = panelRight();
            int paneB = panelBottom();
            int contentTop = paneT + 22 + 16;
            int contentBottom = paneB - PADDING;

            // Popup open → button hit-test first, else outside-click closes.
            if (this.selectedTradeOfferId != null) {
               String btn = TradeRenderer.hitTestPopupButton(mouseX, mouseY, paneL, paneR,
                  contentTop, contentBottom);
               if ("deliver".equals(btn)) {
                  PacketDistributor.sendToServer(new com.yucareux.townfolk.network.FulfillTradePayload(
                     townPos(), this.selectedTradeOfferId));
                  this.selectedTradeOfferId = null;
                  return true;
               }
               if ("close".equals(btn)) {
                  this.selectedTradeOfferId = null;
                  return true;
               }
               // Click outside popup also closes.
               this.selectedTradeOfferId = null;
               return true;
            }
            // Grid mode: click a cell → open popup.
            String hit = TradeRenderer.hitTestCell(this, mouseX, mouseY, paneL, paneR, contentTop, contentBottom);
            if (hit != null) {
               this.selectedTradeOfferId = hit;
               return true;
            }
         }
      }

      // ── Spawn form chip hit-test (Cancel / Spawn). ──
      if (this.mode == Mode.SPAWN_FORM) {
         int paneR = paneL + panelWidth();
         int top = paneT + 22 + 16;
         int bottom = paneT + panelHeight() - PADDING;
         int[] card = spawnFormCardRect(paneL, paneR, top, bottom);
         int cardL = card[0], cardT = card[1], cardW = card[2], cardH = card[3];
         int chipY = cardT + cardH - 26;
         int cancelX = cardL + 12;
         int spawnX  = cardL + cardW - 12 - 96;
         if (mouseY >= chipY && mouseY < chipY + 18) {
            if (mouseX >= cancelX && mouseX < cancelX + 76) {
               this.mode = Mode.NORMAL;
               rebuildAdminWidgets();
               return true;
            }
            if (mouseX >= spawnX && mouseX < spawnX + 96) {
               String name = this.spawnNameBox == null ? "" : this.spawnNameBox.getValue().trim();
               String seed = this.spawnSeedBox == null ? "" : this.spawnSeedBox.getValue().trim();
               PacketDistributor.sendToServer(AdminActionPayload.spawn(townPos(), name, "", seed));
               this.mode = Mode.NORMAL;
               this.tab = Tab.VILLAGERS;
               rebuildAdminWidgets();
               return true;
            }
         }
      }

      // ── Villager detail: tab-strip click switches tabs. ──
      if (this.mode == Mode.VILLAGER_DETAIL) {
         DetailTab clickedTab = VillagerDetailRenderer.hitTestTabs(mouseX, mouseY);
         if (clickedTab != null && clickedTab != this.detailTab) {
            this.detailTab = clickedTab;
            this.detailTabScroll = 0;
            rebuildAdminWidgets();
            return true;
         }
         // Detail Parcels tab — click a row to open its planner.
         if (this.detailTab == DetailTab.PARCELS) {
            String parcelId = VillagerDetailRenderer.hitTestParcelRow(mouseX, mouseY);
            if (parcelId != null) {
               var p = findParcel(parcelId);
               if (p != null) {
                  if ("PLANT".equals(p.type())) {
                     PacketDistributor.sendToServer(
                        AdminActionPayload.openParcelEditor(townPos(), parcelId));
                  } else {
                     PacketDistributor.sendToServer(
                        AdminActionPayload.openAnimalPlan(townPos(), parcelId));
                  }
                  return true;
               }
            }
         }
      }

      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      // Convert physical → logical for hit-test against widget / list bounds.
      mouseX *= INV_CONTENT_SCALE;
      mouseY *= INV_CONTENT_SCALE;
      if (this.mode == Mode.NORMAL && this.tab == Tab.VILLAGERS) {
         int paneL = panelLeft();
         int paneR = panelRight();
         int top   = panelTop() + 34;
         int bottom = panelBottom() - PADDING;
         int innerL = paneL + UiTheme.PADDING;
         int innerR = paneR - UiTheme.PADDING;
         int gridTop = top + VillagersRenderer.VILL_GRID_TOP;
         int gridBot = bottom - 6;
         if (mouseX >= innerL && mouseX <= innerR
             && mouseY >= gridTop && mouseY <= gridBot) {
            this.villagersGridScrollRows = Math.max(0,
               this.villagersGridScrollRows - (int) Math.signum(scrollY));
            return true;
         }
      }
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
            int gridTop = top + ResourcesRenderer.RES_GRID_TOP;
            int gridBot = bottom - 6;
            if (mouseX >= innerL && mouseX <= innerR
                && mouseY >= gridTop && mouseY <= gridBot) {
               this.resourcesGridScrollRows = Math.max(0,
                  this.resourcesGridScrollRows - (int) Math.signum(scrollY));
               return true;
            }
         }
      }
      if (this.mode == Mode.NORMAL && this.tab == Tab.PARCELS && this.selectedParcelId == null) {
         int paneL = panelLeft();
         int paneR = panelRight();
         int top   = panelTop() + 34;
         int bottom = panelBottom() - PADDING;
         int innerL = paneL + UiTheme.PADDING;
         int innerR = paneR - UiTheme.PADDING;
         int gridTop = top + ParcelsRenderer.PARCEL_GRID_TOP;
         int gridBot = bottom - 6;
         if (mouseX >= innerL && mouseX <= innerR
             && mouseY >= gridTop && mouseY <= gridBot) {
            this.parcelsGridScrollRows = Math.max(0,
               this.parcelsGridScrollRows - (int) Math.signum(scrollY));
            return true;
         }
      }
      if (this.mode == Mode.NORMAL && this.tab == Tab.TRADE && this.selectedTradeOfferId == null) {
         int paneL = panelLeft();
         int paneR = panelRight();
         int top   = panelTop() + 34;
         int bottom = panelBottom() - PADDING;
         int innerL = paneL + UiTheme.PADDING;
         int innerR = paneR - UiTheme.PADDING;
         int gridTop = top + TradeRenderer.TRADE_GRID_TOP;
         int gridBot = bottom - 6;
         if (mouseX >= innerL && mouseX <= innerR && mouseY >= gridTop && mouseY <= gridBot) {
            this.tradeGridScrollRows = Math.max(0,
               this.tradeGridScrollRows - (int) Math.signum(scrollY));
            return true;
         }
      }
      if (this.mode == Mode.NORMAL && this.tab == Tab.TASKS && this.selectedTodoKey == null) {
         this.tasksGridScrollRows = Math.max(0,
            this.tasksGridScrollRows - (int) Math.signum(scrollY));
         return true;
      }
      if (this.mode == Mode.MEMORIES) {
         this.memoryScrollOffset = Math.max(0, this.memoryScrollOffset - (int) (scrollY * 18));
         rebuildAdminWidgets();
         return true;
      }
      if (this.mode == Mode.VILLAGER_DETAIL
          && (this.detailTab == DetailTab.MEMORIES
              || this.detailTab == DetailTab.TODOS
              || this.detailTab == DetailTab.BACKSTORY)) {
         this.detailTabScroll = Math.max(0, this.detailTabScroll - (int) (scrollY * 18));
         rebuildAdminWidgets();          // chip buttons re-bind to new Y positions
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
            // Beliefs is opened from the Memories tab inside the
            // detail modal — back goes there.
            this.mode = Mode.VILLAGER_DETAIL;
            this.detailTab = DetailTab.MEMORIES;
            rebuildAdminWidgets();
            return true;
         }
         if (this.mode == Mode.MEMORIES) {
            // Legacy standalone Memories mode is no longer reachable
            // post-Stage 13 refactor, but if anything sets it the
            // ESC path drops back to the detail modal cleanly.
            this.mode = Mode.VILLAGER_DETAIL;
            this.detailTab = DetailTab.MEMORIES;
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
               case OVERVIEW -> OverviewRenderer.render(this, graphics, l, r, contentTop, contentBottom);
               case VILLAGERS -> VillagersRenderer.render(this, graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case RESOURCES -> ResourcesRenderer.render(this, graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case PARCELS -> ParcelsRenderer.render(this, graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case TRADE -> TradeRenderer.render(this, graphics, l, r, contentTop, contentBottom);
               case TASKS -> TasksRenderer.render(this, graphics, l, r, contentTop, contentBottom, mouseX, mouseY);
               case LOG -> LogRenderer.render(this, graphics, l, r, contentTop, contentBottom);
            }
         }
         case SPAWN_FORM -> renderSpawnForm(graphics, l, r, t + headerH + 16, b - PADDING, mouseX, mouseY);
         case VILLAGER_DETAIL -> VillagerDetailRenderer.render(this, graphics, l, r, t + headerH + 16, b - PADDING, mouseX, mouseY);
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
      java.util.List<UiTabs.Tab<Tab>> built = new java.util.ArrayList<>();
      built.add(UiTabs.tab(Tab.OVERVIEW,  "Overview", 64));
      built.add(UiTabs.tab(Tab.VILLAGERS, "Villagers (" + this.state.populationAlive() + ")", 92));
      built.add(UiTabs.tab(Tab.RESOURCES, "Resources (" + this.state.aggregateResources().size() + ")", 96));
      built.add(UiTabs.tab(Tab.PARCELS,   "Parcels (" + this.state.parcels().size() + ")", 80));
      // Trade tab is gated on the town having at least one Trade Post —
      // matches the "Trade Post unlocks the feature" design.
      if (this.state.tradePostCount() > 0) {
         built.add(UiTabs.tab(Tab.TRADE, "Trade", 52));
      }
      built.add(UiTabs.tab(Tab.TASKS,     "Tasks (" + openTodoCount + ")", 64));
      built.add(UiTabs.tab(Tab.LOG,       "Activity", 58));
      return built;
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

   // Overview tab render lives in {@link OverviewRenderer} (stage 15b.4.b).

   /** Strip the {@code namespace:} prefix and turn underscores into
    *  spaces. Used by Resources/Trade cells for short labels. */
   static String shortItemName(String id) {
      int colon = id.indexOf(':');
      return (colon < 0 ? id : id.substring(colon + 1)).replace('_', ' ');
   }
   // Resources tab render + popup + grid hit-test live in {@link ResourcesRenderer} (15b.4.g).

   /** Cache for {@link ItemStack}s built from item-ids; populated
    *  lazily, reused across frames. Saves one
    *  {@code BuiltInRegistries.ITEM.get(...)} per cell per frame. */
   private final java.util.Map<String, net.minecraft.world.item.ItemStack> resourceStackCache =
      new java.util.HashMap<>();

   net.minecraft.world.item.ItemStack stackForItemId(String id) {
      return this.resourceStackCache.computeIfAbsent(id, k -> {
         var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse(k));
         return new net.minecraft.world.item.ItemStack(item);
      });
   }

   // Parcels tab render + popup + grid hit-test live in {@link ParcelsRenderer} (15b.4.d).

   /** Build the human-readable content snapshot for a parcel — same
    *  string the popup uses, abbreviated by truncation when tile-bound. */
   static String parcelSnapshotText(TownStateUpdatePayload.ParcelSummary p) {
      StringBuilder sb = new StringBuilder();
      if ("PLANT".equals(p.type())) {
         if (p.ripeCrops() > 0)     sb.append(p.ripeCrops()).append(" ripe");
         if (p.emptyFarmland() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.emptyFarmland()).append(" empty");
         }
         if (p.tillableTiles() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.tillableTiles()).append(" tillable");
         }
         if (sb.length() == 0) sb.append("clear");
      } else {
         if (p.animalCount() == 0) sb.append("no animals");
         else {
            sb.append(p.animalCount()).append(" animals");
            if (p.animalBabies() > 0) sb.append(" (").append(p.animalBabies()).append(" baby)");
            if (p.unshornSheep() > 0) sb.append(", ").append(p.unshornSheep()).append(" unshorn");
         }
      }
      return sb.toString();
   }

   TownStateUpdatePayload.ParcelSummary findParcel(String id) {
      if (id == null) return null;
      for (var p : this.state.parcels()) if (id.equals(p.id())) return p;
      return null;
   }



   // Villagers tab render + chip-row + grid hit-test live in {@link VillagersRenderer} (15b.4.e).

   /** Map a profession name to a tile icon. Defers to the
    *  {@link com.yucareux.townfolk.villager.ProfessionTraits}
    *  registry — the single source of truth for per-profession
    *  metadata. Stack form cached per profession so renderItem
    *  isn't paying a registry lookup every frame. */
   net.minecraft.world.item.ItemStack iconForProfession(String prof) {
      String key = prof == null ? "" : prof.toLowerCase(Locale.ROOT);
      return this.villagerIconByProfession.computeIfAbsent(key, k ->
         com.yucareux.townfolk.villager.ProfessionTraits.find(k).tileIconStack());
   }


   /** Schedule activity → friendlier label for inline display. */
   static String prettifyActivity(String a) {
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

   private void renderSpawnForm(GuiGraphics g, int paneL, int paneR, int top, int bottom,
                                 int mouseX, int mouseY) {
      // Modal card + dim backdrop so the form reads as a focused
      // task, not bare text on the admin panel.
      g.fill(paneL, top, paneR, bottom, 0x90000000);
      int[] card = spawnFormCardRect(paneL, paneR, top, bottom);
      int cardL = card[0], cardT = card[1], cardW = card[2], cardH = card[3];
      g.fill(cardL, cardT, cardL + cardW, cardT + cardH, PANEL_BG);
      g.fill(cardL - 1, cardT - 1, cardL + cardW + 1, cardT, PANEL_BORDER);
      g.fill(cardL - 1, cardT + cardH, cardL + cardW + 1, cardT + cardH + 1, PANEL_BORDER);
      g.fill(cardL - 1, cardT, cardL, cardT + cardH, PANEL_BORDER);
      g.fill(cardL + cardW, cardT, cardL + cardW + 1, cardT + cardH, PANEL_BORDER);

      int textL = cardL + 12;
      // Title + subtitle.
      g.drawString(this.font,
         Component.literal("Spawn a new villager").withStyle(ChatFormatting.GOLD),
         textL, cardT + 10, FG_ACCENT, true);
      g.drawString(this.font, "The LLM will write the rest of who they are.",
         textL, cardT + 22, FG_FAINT, true);

      // Field labels.
      g.drawString(this.font, "Name (leave blank for the LLM to choose)",
         textL, cardT + 60 + 4, FG_DIM, true);          // just below the name EditBox
      g.drawString(this.font, "Persona seed — informs the auto-generated backstory",
         textL, cardT + 162, FG_DIM, true);              // below the seed EditBox

      // Footer chip row — Cancel (neutral) on the left, Spawn (primary) on the right.
      int chipY = cardT + cardH - 26;
      int cancelX = cardL + 12;
      int spawnX  = cardL + cardW - 12 - 96;
      drawSpawnChip(g, cancelX, chipY, 76, 18, "Cancel", false, mouseX, mouseY);
      drawSpawnChip(g, spawnX,  chipY, 96, 18, "+ Spawn",  true,  mouseX, mouseY);
   }

   /** Chip used by the spawn modal — primary green or neutral grey. */
   private void drawSpawnChip(GuiGraphics g, int x, int y, int w, int h,
                               String label, boolean primary, int mx, int my) {
      boolean hovered = mx >= x && mx < x + w && my >= y && my < y + h;
      int bg = primary
         ? (hovered ? 0xFF4D7330 : 0xFF3C5A22)
         : (hovered ? 0xFF382820 : 0xFF2A2018);
      int border = primary ? 0xFF6FA445 : 0xFFB89B70;
      int fg     = primary ? 0xFFE8FFD2 : 0xFFEDE0C2;
      g.fill(x, y, x + w, y + h, bg);
      g.fill(x, y + h, x + w, y + h + 1, border);
      int lw = this.font.width(label);
      g.drawString(this.font, label, x + (w - lw) / 2, y + 5, fg, true);
   }

   // VILLAGER_DETAIL render pipeline lives in {@link VillagerDetailRenderer}
   // (extracted in stage 15b.3). Init code (footer + per-tab widgets)
   // still lives above in {@link #initVillagerDetailWidgets}.

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

   // Log tab render pipeline lives in {@link LogRenderer} (stage 15b.4.a).

   String truncate(String text, int maxPx) {
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
