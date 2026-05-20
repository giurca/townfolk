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

   private enum Tab { OVERVIEW, VILLAGERS, RESOURCES, PARCELS, TRADE, TASKS, LOG }
   private enum Mode { NORMAL, SPAWN_FORM, VILLAGER_DETAIL, MEMORIES, BELIEFS_FULL, VILLAGER_LOG }

   private TownStateUpdatePayload state;
   private Tab tab = Tab.OVERVIEW;

   /** Toolkit-backed lists. Lazily reconstructed in {@link #ensureListsForBounds}
    *  so resizing the screen rebinds bounds without losing scroll position
    *  between frames. */
   /** Scroll offset (rows) for the Villagers grid. Mirrors
    *  {@link #resourcesGridScrollRows}. */
   private int villagersGridScrollRows = 0;

   /** Profession id → cached ItemStack used as that profession's tile
    *  icon. Built lazily on first lookup; reused across frames. Falls
    *  back to villager_spawn_egg for unknown / "none" professions. */
   private final java.util.Map<String, net.minecraft.world.item.ItemStack> villagerIconByProfession =
      new java.util.HashMap<>();
   // ── New for redesigned tabs ──
   /** Row-level scroll offset for the Resources grid. Driven by the
    *  mouse wheel; clamped at render time once the row count is known. */
   private int resourcesGridScrollRows = 0;
   private UiList<TownStateUpdatePayload.ResourceLoc> resourcesDrillList;
   /** Scroll offset (rows) for the Parcels grid. */
   private int parcelsGridScrollRows = 0;

   /** Selected parcel id (popup detail open). Null = no popup. */
   private String selectedParcelId = null;
   /** When set, the Resources tab renders a floating popup listing every
    *  barrel that holds this item. */
   private String selectedResourceItem;

   /** Resource-grid cell wrapper. Combines a wire-format ItemCount with
    *  a flag indicating whether the row is a town-treasury entry
    *  (claimable payout) vs. a regular stockpile row. Lets the same
    *  grid render both, with distinct styling per cell type and a
    *  branched click handler (treasury → withdraw, stockpile →
    *  production-cap popup). */
   private record ResCell(TownStateUpdatePayload.ItemCount ic, boolean treasury) {}
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

   /** Sub-tabs inside VILLAGER_DETAIL mode. Each renders its own body
    *  and registers its own widgets in {@link #initVillagerDetailWidgets}.
    *  Persistent across rebuilds so the player stays on the tab they
    *  picked when state updates arrive. */
   private enum DetailTab { OVERVIEW, BACKSTORY, MEMORIES, TODOS, PARCELS, RELATIONSHIPS, ACTIONS }
   private DetailTab detailTab = DetailTab.OVERVIEW;

   /** Scroll offset (px) for tab bodies that need it (Backstory text,
    *  Memories list, Relationships table). Reset on tab switch. */
   private int detailTabScroll = 0;
   private UUID selectedVillager;
   private int scrollOffset;
   private int memoryScrollOffset;
   private int beliefsScrollOffset;
   /** Grid scroll for the Tasks tab (rows). Replaces the old
    *  list-pixel tasksScrollOffset field that became dead when the
    *  Tasks tab moved from list rows to grid tiles. */
   private int tasksGridScrollRows = 0;

   /** Selected (owner-uuid, todo-id) for the popup. Encoded as
    *  "uuid|todoId" since we need both to send TODO_COMPLETE /
    *  TODO_ABANDON. Null = no popup. */
   private String selectedTodoKey = null;
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

   /** Y offsets used by BOTH initOverviewTabWidgets and renderOverviewTab so
    *  widgets and labels can't drift. All measured down from the tab's content
    *  top. Tweak in one place if the layout changes. */
   private static final int OV_PULSE_Y      = 4;
   /** One row of pulse cards (counters only). */
   private static final int OV_PULSE_H      = UiTheme.CARD_HEIGHT;
   /** Town-status summary lives between the pulse strip and the Town
    *  Name input. Two right-aligned lines (cap, trade) need ~22 px of
    *  vertical room. The label moves down by that amount so the stats
    *  never overlap the pulse cards above. */
   private static final int OV_NAME_LABEL_Y = OV_PULSE_Y + OV_PULSE_H + 28;
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
      // Empty by design. The Tasks tab is a grid of custom-drawn tiles
      // with a click-modal pattern (renderTaskCell + renderTaskPopup
      // + hitTestTaskPopupButton). The "Mark done" action is now a
      // chip inside the modal — no per-row vanilla Button widgets to
      // pre-register here.
   }

   // ───────── Trade tab (icon grid + popup detail) ─────────

   /** Grid cell metrics for the Trade tab. Cell height accommodates:
    *   y+4  tier chip (top-right)
    *   y+6  archetype name (truncated)
    *   y+22 request item icon (16×16, centered)
    *   y+42 "×N" count line
    *   y+54 payment line ("12 emeralds")
    *   y+66 expiry footnote ("4 days left") */
   private static final int TRADE_CELL_W = 96;
   private static final int TRADE_CELL_H = 80;
   private static final int TRADE_CELL_GAP = 8;
   /** Vertical band reserved for the header line. Offer grid begins
    *  below. Treasury rendering moved out of the Trade tab — it lives
    *  in the Resources grid now as a styled-distinct cell type. */
   private static final int TRADE_GRID_TOP = 26;

   /** Row offset for grid scroll. Reset on tab switch. */
   private int tradeGridScrollRows = 0;

   /** Currently-selected offer id (popup open). null = grid mode. */
   private String selectedTradeOfferId = null;

   private void renderTradeTab(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;

      // Header: prestige + Trade Post count, right-aligned.
      UiText.rightFaint(graphics, this.font,
         "Trade Posts: " + this.state.tradePostCount()
            + "  ·  Prestige: " + this.state.prestige() + " / "
            + com.yucareux.townfolk.town.TownData.MAX_PRESTIGE,
         innerR, top + 10);

      var offers = this.state.tradeOffers();
      if (offers.isEmpty()) {
         UiText.faint(graphics, this.font,
            "No active offers. A new traveller posts an offer roughly once a day —",
            innerL, top + TRADE_GRID_TOP + 10);
         UiText.faint(graphics, this.font,
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

      // Clamp scroll.
      int totalRows = (offers.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + TRADE_CELL_GAP) / (TRADE_CELL_H + TRADE_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (this.tradeGridScrollRows > maxScrollRow) this.tradeGridScrollRows = maxScrollRow;
      int scrollPx = this.tradeGridScrollRows * (TRADE_CELL_H + TRADE_CELL_GAP);

      scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      for (int i = 0; i < offers.size(); i++) {
         int row = i / cols, col = i % cols;
         int cx = gridX0 + col * (TRADE_CELL_W + TRADE_CELL_GAP);
         int cy = gridTop + row * (TRADE_CELL_H + TRADE_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + TRADE_CELL_H < gridTop) continue;
         renderTradeCell(graphics, offers.get(i), cx, cy);
      }
      graphics.disableScissor();

      // Popup on top if an offer is selected.
      if (this.selectedTradeOfferId != null) {
         renderTradeOfferPopup(graphics, paneL, paneR, top, bottom);
      }
   }

   private void renderTradeCell(GuiGraphics g, TownStateUpdatePayload.TradeOfferView o,
                                int x, int y) {
      // Card background.
      g.fill(x, y, x + TRADE_CELL_W, y + TRADE_CELL_H, ROW_BG);
      g.fill(x, y + TRADE_CELL_H, x + TRADE_CELL_W, y + TRADE_CELL_H + 1, PANEL_BORDER);

      // Tier chip (top-right): COMMON gray, NOTABLE green, PREMIUM gold.
      int chipColor = switch (o.tierName()) {
         case "NOTABLE" -> 0xFF4FB04F;
         case "PREMIUM" -> 0xFFFFC847;
         default        -> 0xFFB89B70;   // COMMON
      };
      String chipText = switch (o.tierName()) {
         case "NOTABLE" -> "Notable";
         case "PREMIUM" -> "Premium";
         default        -> "Common";
      };
      int chipW = this.font.width(chipText) + 8;
      g.fill(x + TRADE_CELL_W - chipW - 4, y + 4,
             x + TRADE_CELL_W - 4, y + 14, chipColor & 0x80FFFFFF);
      g.drawString(this.font, chipText,
         x + TRADE_CELL_W - chipW - 2, y + 5, 0xFF111111, false);

      // Archetype name (truncated to leave room for chip).
      String archetype = UiText.truncate(this.font, o.archetypeName(),
         TRADE_CELL_W - chipW - 12);
      g.drawString(this.font, archetype, x + 4, y + 5, FG_PRIMARY, true);

      // Request item icon, centered.
      var stack = stackForItemId(o.requestItemId());
      int iconX = x + (TRADE_CELL_W - 16) / 2;
      g.renderItem(stack, iconX, y + 22);

      // Count line.
      String count = "× " + o.requestCount();
      int countW = this.font.width(count);
      g.drawString(this.font, count, x + (TRADE_CELL_W - countW) / 2, y + 42,
         FG_ACCENT, true);

      // Payment line.
      String pay = o.paymentEmeralds() + " emeralds";
      int payW = this.font.width(pay);
      g.drawString(this.font, pay, x + (TRADE_CELL_W - payW) / 2, y + 54,
         FG_PRIMARY, true);

      // Expiry footnote.
      String exp = o.daysRemaining() <= 0 ? "expires today"
                 : o.daysRemaining() == 1 ? "1 day left"
                 : o.daysRemaining() + " days left";
      int expW = this.font.width(exp);
      g.drawString(this.font, exp, x + (TRADE_CELL_W - expW) / 2, y + 66,
         o.daysRemaining() <= 2 ? FG_ERROR : FG_FAINT, true);
   }

   private void renderTradeOfferPopup(GuiGraphics g, int paneL, int paneR,
                                       int top, int bottom) {
      var offer = findTradeOffer(this.selectedTradeOfferId);
      if (offer == null) { this.selectedTradeOfferId = null; return; }

      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
      int popH = 180;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;

      // Backdrop dim.
      g.fill(paneL, top, paneR, bottom, 0xC0000000);
      // Popup background + border.
      g.fill(popX, popY, popX + popW, popY + popH, PANEL_BG);
      g.fill(popX - 1, popY - 1, popX + popW + 1, popY, PANEL_BORDER);
      g.fill(popX - 1, popY + popH, popX + popW + 1, popY + popH + 1, PANEL_BORDER);
      g.fill(popX - 1, popY, popX, popY + popH, PANEL_BORDER);
      g.fill(popX + popW, popY, popX + popW + 1, popY + popH, PANEL_BORDER);

      int x = popX + 14;
      int y = popY + 12;

      UiText.heading(g, this.font,
         offer.archetypeName() + " — " + offer.tierName().toLowerCase(java.util.Locale.ROOT),
         x, y);
      y += 14;

      var stack = stackForItemId(offer.requestItemId());
      g.renderItem(stack, x, y);
      g.drawString(this.font,
         offer.requestCount() + "× " + shortItemName(offer.requestItemId()),
         x + 22, y + 4, FG_PRIMARY, true);
      y += 24;

      g.drawString(this.font,
         "Payment: " + offer.paymentEmeralds() + " emeralds",
         x, y, FG_ACCENT, true);
      y += 12;

      String exp = offer.daysRemaining() <= 0 ? "expires today"
                 : offer.daysRemaining() == 1 ? "1 day left to deliver"
                 : offer.daysRemaining() + " days left to deliver";
      g.drawString(this.font, exp, x, y,
         offer.daysRemaining() <= 2 ? FG_ERROR : FG_DIM, true);
      y += 12;

      // Source / destination hint — make the new flow legible: items
      // come from the town's barrels, emeralds land in the treasury
      // (top of this tab) until the player withdraws them.
      g.drawString(this.font, "Drawn from town storage. Pays into treasury.",
         x, y, FG_FAINT, true);
      y += 14;

      // Flavor blurb, wrapped.
      String blurb = offer.flavorBlurb();
      if (blurb != null && !blurb.isBlank()) {
         for (var line : this.font.split(Component.literal(blurb), popW - 28)) {
            if (y > popY + popH - 40) break;
            g.drawString(this.font, line, x, y, FG_FAINT, true);
            y += 10;
         }
      }

      // Buttons — actual interaction handled in mouseClicked via the
      // same rect arithmetic.
      int btnY = popY + popH - 24;
      drawTradeButton(g, popX + 14, btnY, 110, "Deliver", true);
      drawTradeButton(g, popX + popW - 14 - 80, btnY, 80, "Close", false);
   }

   private void drawTradeButton(GuiGraphics g, int x, int y, int w, String label,
                                boolean primary) {
      g.fill(x, y, x + w, y + 18, primary ? 0xFF3C5A22 : 0xFF2A2018);
      g.fill(x, y + 18, x + w, y + 19, primary ? 0xFF6FA445 : PANEL_BORDER);
      int lw = this.font.width(label);
      g.drawString(this.font, label, x + (w - lw) / 2, y + 5,
         primary ? 0xFFE8FFD2 : FG_PRIMARY, true);
   }

   /** Hit-test the trade-offer popup's buttons. Returns "deliver",
    *  "close", or null. Coordinates are LOGICAL (post-scaledMouse). */
   private String hitTestTradePopupButton(double mouseX, double mouseY,
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
   private String hitTestTradeCell(double mouseX, double mouseY,
                                    int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + TRADE_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW = innerR - innerL;
      int cols = Math.max(1, (gridW + TRADE_CELL_GAP) / (TRADE_CELL_W + TRADE_CELL_GAP));
      int gridUsed = cols * TRADE_CELL_W + (cols - 1) * TRADE_CELL_GAP;
      int gridX0 = innerL + (gridW - gridUsed) / 2;
      int scrollPx = this.tradeGridScrollRows * (TRADE_CELL_H + TRADE_CELL_GAP);
      var offers = this.state.tradeOffers();
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

   private TownStateUpdatePayload.TradeOfferView findTradeOffer(String id) {
      if (id == null) return null;
      for (var o : this.state.tradeOffers()) if (id.equals(o.id())) return o;
      return null;
   }


   private static final int TASK_CELL_W = 110;
   private static final int TASK_CELL_H = 72;
   private static final int TASK_CELL_GAP = 8;
   /** Standardised across grid-style tabs (Resources / Parcels / Trade /
    *  Tasks) so the visual rhythm — header on one line, generous gap,
    *  then grid — stays consistent. */
   private static final int TASK_GRID_TOP = 26;

   /** Cached fallback icon for tasks that don't parse to a known need. */
   private net.minecraft.world.item.ItemStack taskFallbackIcon;

   private void renderTasksTab(GuiGraphics graphics, int paneL, int paneR,
                                int top, int bottom, int mouseX, int mouseY) {
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;

      List<TaskRow> rows = collectOpenTasks();
      int contentTop = top + 10;
      UiText.heading(graphics, this.font,
         "Open tasks across the town (" + rows.size() + ")",
         innerL, contentTop);
      if (rows.isEmpty()) {
         UiText.faint(graphics, this.font,
            "(no open commitments — nobody owes anybody anything)",
            innerL, contentTop + 14);
         return;
      }

      int gridTop = top + TASK_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + TASK_CELL_GAP) / (TASK_CELL_W + TASK_CELL_GAP));
      int gridUsed = cols * TASK_CELL_W + (cols - 1) * TASK_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      int totalRows = (rows.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + TASK_CELL_GAP) / (TASK_CELL_H + TASK_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (this.tasksGridScrollRows > maxScrollRow) this.tasksGridScrollRows = maxScrollRow;
      int scrollPx = this.tasksGridScrollRows * (TASK_CELL_H + TASK_CELL_GAP);

      scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      int i = 0;
      for (TaskRow r : rows) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (TASK_CELL_W + TASK_CELL_GAP);
         int cy = gridTop + row * (TASK_CELL_H + TASK_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + TASK_CELL_H < gridTop) { i++; continue; }
         renderTaskCell(graphics, r, cx, cy, mouseX, mouseY);
         i++;
      }
      graphics.disableScissor();

      if (this.selectedTodoKey != null) {
         renderTaskPopup(graphics, paneL, paneR, top, bottom);
      }
   }

   /** Render one open todo as a tile. Tile content:
    *  <ul>
    *    <li>top-left: owner name (truncated, accent)
    *    <li>top-right: day created (faint)
    *    <li>centre: derived icon (need-tag → item lookup, else paper)
    *    <li>centred under icon: 2 lines of todo text, truncated
    *  </ul> */
   private void renderTaskCell(GuiGraphics g, TaskRow r, int x, int y, int mouseX, int mouseY) {
      boolean hovered = mouseX >= x && mouseX < x + TASK_CELL_W
                     && mouseY >= y && mouseY < y + TASK_CELL_H;
      String key = r.owner().uuid() + "|" + r.todo().id();
      boolean selected = key.equals(this.selectedTodoKey);
      int bg = (hovered || selected) ? TAB_ACTIVE_BG : ROW_BG;
      g.fill(x, y, x + TASK_CELL_W, y + TASK_CELL_H, bg);
      g.fill(x, y + TASK_CELL_H, x + TASK_CELL_W, y + TASK_CELL_H + 1, PANEL_BORDER);

      // Owner top-left.
      String ownerClip = UiText.truncate(this.font, r.owner().name(), TASK_CELL_W - 30);
      g.drawString(this.font, ownerClip, x + 4, y + 3, FG_ACCENT, true);

      // Day top-right.
      String day = "d" + r.todo().createdDay();
      int dw = this.font.width(day);
      g.drawString(this.font, day, x + TASK_CELL_W - dw - 4, y + 3, FG_FAINT, true);

      // Icon derived from need-tag prefix; falls back to paper.
      var icon = iconForTodo(r.todo().text());
      g.renderItem(icon, x + (TASK_CELL_W - 16) / 2, y + 14);

      // Two-line truncated body — strip the [need:X] prefix for readability.
      String body = stripNeedPrefix(r.todo().text());
      var lines = this.font.split(Component.literal(body), TASK_CELL_W - 8);
      int bodyY = y + 34;
      for (int li = 0; li < Math.min(3, lines.size()); li++) {
         var line = lines.get(li);
         int lw = this.font.width(line);
         g.drawString(this.font, line,
            x + (TASK_CELL_W - lw) / 2, bodyY + li * 10, UiTheme.BODY, true);
      }
   }

   private static String stripNeedPrefix(String text) {
      if (text == null) return "";
      if (text.startsWith("[need:")) {
         int end = text.indexOf("] ");
         if (end >= 0) return text.substring(end + 2);
      }
      return text;
   }

   /** Map a todo's [need:X] prefix to an item icon. Returns a paper
    *  stack as the universal fallback so every tile gets an icon. */
   private net.minecraft.world.item.ItemStack iconForTodo(String text) {
      if (this.taskFallbackIcon == null) {
         this.taskFallbackIcon = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.PAPER);
      }
      if (text == null || !text.startsWith("[need:")) return this.taskFallbackIcon;
      int end = text.indexOf("] ");
      if (end < 0) return this.taskFallbackIcon;
      String need = text.substring(6, end);
      // Strip a "barrel_for_" prefix → leave the item id.
      if (need.startsWith("barrel_for_")) need = need.substring("barrel_for_".length());
      // Map task-id "tool" suffixes to their tool items.
      String itemId = switch (need) {
         case "hoe", "seeds_for_till"            -> "minecraft:wooden_hoe";
         case "seeds"                             -> "minecraft:wheat_seeds";
         case "shears", "shear_sheep_tool"       -> "minecraft:shears";
         case "milk_cow_tool"                     -> "minecraft:bucket";
         default                                  -> need.contains(":") ? need : "minecraft:" + need;
      };
      return stackForItemId(itemId);
   }

   private TaskRow findTodoByKey(String key) {
      if (key == null) return null;
      int bar = key.indexOf('|');
      if (bar < 0) return null;
      UUID ownerId; try { ownerId = UUID.fromString(key.substring(0, bar)); }
      catch (IllegalArgumentException ex) { return null; }
      String todoId = key.substring(bar + 1);
      for (TaskRow r : collectOpenTasks()) {
         if (r.owner().uuid().equals(ownerId) && todoId.equals(r.todo().id())) return r;
      }
      return null;
   }

   private TaskRow taskAtPoint(double mouseX, double mouseY) {
      int paneL = panelLeft();
      int paneR = panelRight();
      int top   = panelTop() + 34;
      int bottom = panelBottom() - PADDING;
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + TASK_GRID_TOP;
      int gridBot = bottom - 6;
      if (mouseX < innerL || mouseX > innerR || mouseY < gridTop || mouseY > gridBot) return null;

      var rows = collectOpenTasks();
      if (rows.isEmpty()) return null;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + TASK_CELL_GAP) / (TASK_CELL_W + TASK_CELL_GAP));
      int gridUsed = cols * TASK_CELL_W + (cols - 1) * TASK_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;
      int scrollPx = this.tasksGridScrollRows * (TASK_CELL_H + TASK_CELL_GAP);
      for (int i = 0; i < rows.size(); i++) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (TASK_CELL_W + TASK_CELL_GAP);
         int cy = gridTop + row * (TASK_CELL_H + TASK_CELL_GAP) - scrollPx;
         if (mouseX >= cx && mouseX < cx + TASK_CELL_W
             && mouseY >= cy && mouseY < cy + TASK_CELL_H) {
            return rows.get(i);
         }
      }
      return null;
   }

   private void renderTaskPopup(GuiGraphics g, int paneL, int paneR, int top, int bottom) {
      var r = findTodoByKey(this.selectedTodoKey);
      if (r == null) { this.selectedTodoKey = null; return; }

      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(380, innerR - innerL - 40);
      int popH = 180;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;

      g.fill(paneL, top, paneR, bottom, 0xC0000000);
      g.fill(popX, popY, popX + popW, popY + popH, PANEL_BG);
      g.fill(popX - 1, popY - 1, popX + popW + 1, popY, PANEL_BORDER);
      g.fill(popX - 1, popY + popH, popX + popW + 1, popY + popH + 1, PANEL_BORDER);
      g.fill(popX - 1, popY, popX, popY + popH, PANEL_BORDER);
      g.fill(popX + popW, popY, popX + popW + 1, popY + popH, PANEL_BORDER);

      int x = popX + 14;
      int y = popY + 12;

      UiText.heading(g, this.font,
         r.owner().name() + " · day " + r.todo().createdDay(), x, y);
      y += 14;

      // Icon + counterparty.
      g.renderItem(iconForTodo(r.todo().text()), x, y);
      String cp = r.todo().counterparty();
      g.drawString(this.font, "Owed to: " + (cp == null || cp.isBlank() ? "—" : cp),
         x + 22, y + 4, FG_PRIMARY, true);
      y += 24;

      // Full body, wrapped.
      String body = stripNeedPrefix(r.todo().text());
      for (var line : this.font.split(Component.literal(body), popW - 28)) {
         if (y > popY + popH - 40) break;
         g.drawString(this.font, line, x, y, FG_PRIMARY, true);
         y += 10;
      }

      int btnY = popY + popH - 24;
      drawTradeButton(g, popX + 14, btnY, 110, "Mark done", true);
      drawTradeButton(g, popX + popW - 14 - 80, btnY, 80, "Close", false);
   }

   private String hitTestTaskPopupButton(double mouseX, double mouseY,
                                          int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(380, innerR - innerL - 40);
      int popH = 180;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;
      int btnY = popY + popH - 24;
      if (mouseY < btnY || mouseY >= btnY + 18) return null;
      int doneX = popX + 14;
      if (mouseX >= doneX && mouseX < doneX + 110) return "done";
      int closeX = popX + popW - 14 - 80;
      if (mouseX >= closeX && mouseX < closeX + 80) return "close";
      return null;
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

   /** Reserved Y bands inside VILLAGER_DETAIL mode. The header always
    *  occupies the first DETAIL_HEADER_H pixels of the content area;
    *  the tab strip sits just under it; tab body fills the rest above
    *  the footer button row. */
   private static final int DETAIL_HEADER_H = 56;
   private static final int DETAIL_TAB_STRIP_H = 18;
   private static final int DETAIL_FOOTER_H = 26;

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
      int tabBodyTop = top + DETAIL_HEADER_H + DETAIL_TAB_STRIP_H + 4;
      int tabBodyBottom = bottom - DETAIL_FOOTER_H;

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
      int listTop = top + DETAIL_MEMORIES_LIST_TOP;
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

   /** Y offset inside the Memories tab where the scrollable list
    *  starts. Header band (beliefs preview + stats line) takes the
    *  first DETAIL_MEMORIES_LIST_TOP pixels. */
   private static final int DETAIL_MEMORIES_LIST_TOP = 60;

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
            String[] chipHit = hitTestVillagerChips(mouseX, mouseY, paneL, paneT + 22 + 16);
            if (chipHit != null) {
               if ("prof".equals(chipHit[0]))   this.villagerProfFilter = chipHit[1];
               if ("status".equals(chipHit[0])) this.villagerStatusFilter = chipHit[1];
               this.villagersGridScrollRows = 0;
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
            // Cell click. Branch on whether this is a stockpile or
            // treasury cell:
            //  - Stockpile → open the production-cap popup (existing flow).
            //  - Treasury  → withdraw immediately. Plain click takes
            //    everything; shift-click takes 1.
            ResCell cellHit = hitTestResourceCell(mouseX, mouseY, paneL, paneR, contentTop, contentBottom);
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
               String btn = hitTestParcelPopupButton(mouseX, mouseY, paneL, paneR2,
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
            var hit = parcelAtPoint(mouseX, mouseY);
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
               String btn = hitTestTaskPopupButton(mouseX, mouseY, paneL, paneR2,
                  contentTop, contentBottom);
               var r = findTodoByKey(this.selectedTodoKey);
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

            var hit = taskAtPoint(mouseX, mouseY);
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
               String btn = hitTestTradePopupButton(mouseX, mouseY, paneL, paneR,
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
            String hit = hitTestTradeCell(mouseX, mouseY, paneL, paneR, contentTop, contentBottom);
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
         DetailTab clickedTab = hitTestDetailTabs(mouseX, mouseY);
         if (clickedTab != null && clickedTab != this.detailTab) {
            this.detailTab = clickedTab;
            this.detailTabScroll = 0;
            rebuildAdminWidgets();
            return true;
         }
         // Detail Parcels tab — click a row to open its planner.
         if (this.detailTab == DetailTab.PARCELS) {
            String parcelId = hitTestDetailParcelRow(mouseX, mouseY);
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
         int gridTop = top + VILL_GRID_TOP;
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
      if (this.mode == Mode.NORMAL && this.tab == Tab.PARCELS && this.selectedParcelId == null) {
         int paneL = panelLeft();
         int paneR = panelRight();
         int top   = panelTop() + 34;
         int bottom = panelBottom() - PADDING;
         int innerL = paneL + UiTheme.PADDING;
         int innerR = paneR - UiTheme.PADDING;
         int gridTop = top + PARCEL_GRID_TOP;
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
         int gridTop = top + TRADE_GRID_TOP;
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
               case OVERVIEW -> renderOverviewTab(graphics, l, r, contentTop, contentBottom);
               case VILLAGERS -> renderVillagersTab(graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case RESOURCES -> renderResourcesTab(graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case PARCELS -> renderParcelsTab(graphics, l, r, contentTop, contentBottom, lmX, lmY);
               case TRADE -> renderTradeTab(graphics, l, r, contentTop, contentBottom);
               case TASKS -> renderTasksTab(graphics, l, r, contentTop, contentBottom, mouseX, mouseY);
               case LOG -> renderLogTab(graphics, l, r, contentTop, contentBottom);
            }
         }
         case SPAWN_FORM -> renderSpawnForm(graphics, l, r, t + headerH + 16, b - PADDING, mouseX, mouseY);
         case VILLAGER_DETAIL -> renderVillagerDetail(graphics, l, r, t + headerH + 16, b - PADDING, mouseX, mouseY);
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

   private void renderOverviewTab(GuiGraphics graphics, int paneL, int paneR, int top, int bottom) {
      renderOverviewPulse(graphics, paneL, paneR, top + OV_PULSE_Y);
      renderOverviewBody(graphics, paneL, paneR, top, bottom);
   }

   /** Pulse strip: a row of 6 small cards across the top of Overview,
    *  built for at-a-glance scale to 50+ villagers — population +
    *  activity breakdown + day-over-day deltas.
    *
    *  All counters come from server-side tallies in
    *  {@link TownStateUpdatePayload} so the UI doesn't have to walk the
    *  villager list each frame. The Resources tab handles per-item
    *  stockpile readout; we don't duplicate that here. */
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
         "Residents", alive + " / " + tot,
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

      // Town status header — right-aligned under the pulse strip.
      // Each block of scalars sits on its own line so the player can
      // scan the three primary indicators (capacity, trade, prestige)
      // at a glance without the line truncating.
      int tradePosts = this.state.tradePostCount();
      int prestige   = this.state.prestige();
      int homes      = this.state.homeCount();
      boolean townHall = this.state.hasTownHall();
      int taverns    = this.state.tavernCount();
      int popCap     = homes + (townHall ? 4 : 0);
      int popAlive   = this.state.populationAlive();
      int hungry = 0;
      for (var vs : this.state.villagers()) {
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
      // Two stat lines sit in the 28-px gap between the pulse strip and
      // the Town Name label. Each line is ~10 px tall.
      UiText.rightFaint(graphics, this.font, capLine,
         innerR, top + OV_PULSE_Y + OV_PULSE_H + 4);
      UiText.rightFaint(graphics, this.font, tradeLine,
         innerR, top + OV_PULSE_Y + OV_PULSE_H + 14);

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
      // Treasury items (claimable payouts) are interleaved with normal
      // stockpile items — same grid, styled differently per cell.
      String q = this.resourceSearchBox == null ? "" :
         this.resourceSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
      List<ResCell> items = new java.util.ArrayList<>();
      for (var ic : this.state.aggregateResources()) {
         if (q.isEmpty() || shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) {
            items.add(new ResCell(ic, false));
         }
      }
      for (var ic : this.state.treasury()) {
         if (q.isEmpty() || shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) {
            items.add(new ResCell(ic, true));
         }
      }
      // Treasury (CLAIM) cells ALWAYS come first regardless of sort
      // mode — they're claimable payouts and the player wants to see
      // them before the regular stockpile. Within each partition the
      // user-chosen sort key applies.
      java.util.Comparator<ResCell> within = switch (this.resourceSort) {
         case "name"   -> (a, b) -> shortItemName(a.ic().itemId()).compareToIgnoreCase(shortItemName(b.ic().itemId()));
         case "recent" -> (a, b) -> Long.compare(
            this.resourceRecentByItem.getOrDefault(b.ic().itemId(), 0L),
            this.resourceRecentByItem.getOrDefault(a.ic().itemId(), 0L));
         default        -> (a, b) -> Integer.compare(b.ic().count(), a.ic().count());
      };
      items.sort(java.util.Comparator.<ResCell, Boolean>comparing(c -> !c.treasury())
         .thenComparing(within));

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
         for (var cell : items) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX0 + col * (RES_CELL_W + RES_CELL_GAP);
            int cy = gridTop + row * (RES_CELL_H + RES_CELL_GAP) - scrollPx;
            if (cy > gridBot) break;
            if (cy + RES_CELL_H < gridTop) { i++; continue; } // scrolled off top
            renderResourceCell(graphics, cell, cx, cy, mouseX, mouseY);
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

   /** Gold-ish hue for treasury cell backgrounds — distinguishes
    *  claimable payouts from regular stockpile at a glance. */
   private static final int TREASURY_CELL_BG     = 0x40FFC847;
   private static final int TREASURY_CELL_BORDER = 0xFFFFC847;

   /** One cell of the resource grid. Renders BOTH normal stockpile
    *  entries and town-treasury entries (claimable payouts) — the
    *  {@link ResCell#treasury} flag switches the styling and tag.
    *
    *  <p>Vertical layout (y offsets):
    *  <ul>
    *    <li>+2  badge strip — "min/max" cap tag (stockpile) OR
    *            "CLAIM" tag (treasury)
    *    <li>+12 item icon (16×16, centred)
    *    <li>+30 count line — ↑/↓ trend + count + "+N" in-flight (stockpile only)
    *    <li>+42 name line (truncated, centred)
    *  </ul> */
   private void renderResourceCell(GuiGraphics g, ResCell cell,
                                   int x, int y, int mouseX, int mouseY) {
      var ic = cell.ic();
      boolean treasury = cell.treasury();
      boolean hovered = mouseX >= x && mouseX < x + RES_CELL_W
                     && mouseY >= y && mouseY < y + RES_CELL_H;
      // Treasury entries aren't selectable for the production-cap
      // popup — they only respond to click-to-withdraw — so selection
      // highlight is stockpile-only.
      boolean selected = !treasury && ic.itemId().equals(this.selectedResourceItem);

      int bg = treasury ? TREASURY_CELL_BG
            : (hovered || selected ? TAB_ACTIVE_BG : ROW_BG);
      g.fill(x, y, x + RES_CELL_W, y + RES_CELL_H, bg);

      if (treasury) {
         // Full gold border on all four sides — strong visual cue that
         // these are different from stockpile cells.
         g.fill(x, y, x + RES_CELL_W, y + 1, TREASURY_CELL_BORDER);
         g.fill(x, y + RES_CELL_H, x + RES_CELL_W, y + RES_CELL_H + 1, TREASURY_CELL_BORDER);
         g.fill(x, y, x + 1, y + RES_CELL_H, TREASURY_CELL_BORDER);
         g.fill(x + RES_CELL_W - 1, y, x + RES_CELL_W, y + RES_CELL_H, TREASURY_CELL_BORDER);
      } else {
         g.fill(x, y + RES_CELL_H, x + RES_CELL_W, y + RES_CELL_H + 1,
            selected ? FG_ACCENT : PANEL_BORDER);
      }

      // Badge strip (top).
      if (treasury) {
         // "CLAIM" tag on the right; small gold pip on the left to
         // mirror the production-cap status dot's location.
         String tag = "CLAIM";
         int tagW = this.font.width(tag);
         g.drawString(this.font, tag,
            x + RES_CELL_W - tagW - 4, y + 2,
            TREASURY_CELL_BORDER, true);
         g.fill(x + 3, y + 3, x + 6, y + 6, TREASURY_CELL_BORDER);
      } else {
         var target = findTargetFor(ic.itemId());
         if (target != null) {
            String tag = target.min() + "/" + target.max();
            int tagW = this.font.width(tag);
            int tagX = x + RES_CELL_W - tagW - 4;
            int tagY = y + 2;
            g.drawString(this.font, tag, tagX, tagY,
               target.active() ? FG_DIM : FG_ERROR, true);
            int dotColor = target.active() ? UiTheme.OK : UiTheme.BAD;
            g.fill(x + 3, y + 3, x + 6, y + 6, dotColor);
         }
      }

      // Item icon — fixed band y+12 .. y+28.
      int iconX = x + (RES_CELL_W - 16) / 2;
      int iconY = y + 12;
      var stack = stackForItemId(ic.itemId());
      g.renderItem(stack, iconX, iconY);

      // Count line — y+32.
      // For STOCKPILE cells: ↑/↓ trend arrow + count + "+N" in-flight.
      // For TREASURY cells: just the count, in the treasury gold tint
      //   (no trend / in-flight — treasury is a one-way pool).
      if (treasury) {
         String countText = String.valueOf(ic.count());
         int cw = this.font.width(countText);
         g.drawString(this.font, countText,
            x + (RES_CELL_W - cw) / 2, y + 32,
            TREASURY_CELL_BORDER, true);
      } else {
         int inFlight = inFlightFor(ic.itemId());
         String arrow = ic.trend() > 0 ? "↑ " : ic.trend() < 0 ? "↓ " : "";
         int arrowColor = ic.trend() > 0 ? UiTheme.OK
                        : ic.trend() < 0 ? UiTheme.BAD
                        : FG_FAINT;
         String countText = String.valueOf(ic.count());
         String suffix = inFlight > 0 ? "  +" + inFlight : "";
         int arrowW = this.font.width(arrow);
         int countW = this.font.width(countText);
         int suffixW = this.font.width(suffix);
         int totalW = arrowW + countW + suffixW;
         int drawX = x + (RES_CELL_W - totalW) / 2;
         if (!arrow.isEmpty()) {
            g.drawString(this.font, arrow, drawX, y + 32, arrowColor, true);
            drawX += arrowW;
         }
         g.drawString(this.font, countText, drawX, y + 32, FG_ACCENT, true);
         drawX += countW;
         if (!suffix.isEmpty()) {
            g.drawString(this.font, suffix, drawX, y + 32, FG_FAINT, true);
         }
      }

      // Name line — y+44, truncated to cell width.
      String name = shortItemName(ic.itemId());
      String shown = name;
      if (this.font.width(shown) > RES_CELL_W - 6) {
         shown = this.font.plainSubstrByWidth(shown, RES_CELL_W - 10) + "…";
      }
      int nw = this.font.width(shown);
      g.drawString(this.font, shown, x + (RES_CELL_W - nw) / 2, y + 44,
         treasury ? TREASURY_CELL_BORDER : FG_DIM, true);
   }

   /** Sum the count of {@code itemId} across every villager's
    *  inventory in the current state snapshot. Used by the Resources
    *  grid to surface "+N in flight" when the stockpile is about to
    *  grow.
    *
    *  <p>Recomputed per cell per frame; cheap because villager count
    *  and inventory size are both small. If profiling ever shows it
    *  matters, cache once per state-update tick. */
   private int inFlightFor(String itemId) {
      int sum = 0;
      for (var v : this.state.villagers()) {
         for (var ic : v.inventory()) {
            if (ic.itemId().equals(itemId)) sum += ic.count();
         }
      }
      return sum;
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
   private ResCell hitTestResourceCell(double mouseX, double mouseY, int paneL, int paneR,
                                       int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + RES_GRID_TOP;
      int gridBot = bottom - 6;
      int gridW   = innerR - innerL;
      int cols    = Math.max(1, (gridW + RES_CELL_GAP) / (RES_CELL_W + RES_CELL_GAP));
      int gridUsed = cols * RES_CELL_W + (cols - 1) * RES_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      // Mirror the search/sort + interleave logic from
      // renderResourcesTab so click coordinates always match what the
      // player sees.
      String q = this.resourceSearchBox == null ? "" :
         this.resourceSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
      List<ResCell> items = new java.util.ArrayList<>();
      for (var ic : this.state.aggregateResources()) {
         if (q.isEmpty() || shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) items.add(new ResCell(ic, false));
      }
      for (var ic : this.state.treasury()) {
         if (q.isEmpty() || shortItemName(ic.itemId()).toLowerCase(Locale.ROOT).contains(q)
             || ic.itemId().contains(q)) items.add(new ResCell(ic, true));
      }
      // Treasury (CLAIM) cells ALWAYS come first regardless of sort
      // mode — they're claimable payouts and the player wants to see
      // them before the regular stockpile. Within each partition the
      // user-chosen sort key applies.
      java.util.Comparator<ResCell> within = switch (this.resourceSort) {
         case "name"   -> (a, b) -> shortItemName(a.ic().itemId()).compareToIgnoreCase(shortItemName(b.ic().itemId()));
         case "recent" -> (a, b) -> Long.compare(
            this.resourceRecentByItem.getOrDefault(b.ic().itemId(), 0L),
            this.resourceRecentByItem.getOrDefault(a.ic().itemId(), 0L));
         default        -> (a, b) -> Integer.compare(b.ic().count(), a.ic().count());
      };
      items.sort(java.util.Comparator.<ResCell, Boolean>comparing(c -> !c.treasury())
         .thenComparing(within));
      int scrollPx = this.resourcesGridScrollRows * (RES_CELL_H + RES_CELL_GAP);
      int i = 0;
      for (var cell : items) {
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
            return cell;
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

      // Header row: count summary on the left, right-aligned hint on the right.
      UiText.heading(graphics, this.font,
         "Owned land: " + this.state.parcels().size() + " parcels",
         innerL, contentTop);
      int plant = 0, animal = 0;
      for (var p : this.state.parcels()) {
         if ("PLANT".equals(p.type())) plant++; else if ("ANIMAL".equals(p.type())) animal++;
      }
      UiText.rightFaint(graphics, this.font,
         plant + " plant · " + animal + " animal",
         innerR, contentTop);

      int gridTop = top + PARCEL_GRID_TOP;
      int gridBot = bottom - 6;
      var parcels = this.state.parcels();
      if (parcels.isEmpty()) {
         UiText.faint(graphics, this.font,
            "no parcels yet — give a villager a Surveyor's Stake and mark some land",
            innerL, gridTop + 10);
         return;
      }

      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + PARCEL_CELL_GAP) / (PARCEL_CELL_W + PARCEL_CELL_GAP));
      int gridUsed = cols * PARCEL_CELL_W + (cols - 1) * PARCEL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      int totalRows = (parcels.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + PARCEL_CELL_GAP) / (PARCEL_CELL_H + PARCEL_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (this.parcelsGridScrollRows > maxScrollRow) this.parcelsGridScrollRows = maxScrollRow;
      int scrollPx = this.parcelsGridScrollRows * (PARCEL_CELL_H + PARCEL_CELL_GAP);

      scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      int i = 0;
      for (var p : parcels) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (PARCEL_CELL_W + PARCEL_CELL_GAP);
         int cy = gridTop + row * (PARCEL_CELL_H + PARCEL_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + PARCEL_CELL_H < gridTop) { i++; continue; }
         renderParcelCell(graphics, p, cx, cy, mouseX, mouseY);
         i++;
      }
      graphics.disableScissor();

      if (this.selectedParcelId != null) {
         renderParcelPopup(graphics, paneL, paneR, top, bottom);
      }
   }

   private static final int PARCEL_CELL_W = 96;
   private static final int PARCEL_CELL_H = 72;
   private static final int PARCEL_CELL_GAP = 8;
   private static final int PARCEL_GRID_TOP = 26;

   /** Stable per-type icon for the parcel grid: wheat for PLANT,
    *  cow spawn egg for ANIMAL. Built lazily on first render. */
   private net.minecraft.world.item.ItemStack parcelPlantIcon;
   private net.minecraft.world.item.ItemStack parcelAnimalIcon;

   private void renderParcelCell(GuiGraphics g,
                                  TownStateUpdatePayload.ParcelSummary p,
                                  int x, int y, int mouseX, int mouseY) {
      boolean hovered = mouseX >= x && mouseX < x + PARCEL_CELL_W
                     && mouseY >= y && mouseY < y + PARCEL_CELL_H;
      boolean selected = p.id().equals(this.selectedParcelId);
      int bg = (hovered || selected) ? TAB_ACTIVE_BG : ROW_BG;
      g.fill(x, y, x + PARCEL_CELL_W, y + PARCEL_CELL_H, bg);
      g.fill(x, y + PARCEL_CELL_H, x + PARCEL_CELL_W, y + PARCEL_CELL_H + 1, PANEL_BORDER);

      // Top-left: type chip.
      boolean isPlant = "PLANT".equals(p.type());
      String typeTag = isPlant ? "PLANT" : "ANIMAL";
      int typeColor = isPlant ? 0xFF6FA445 : 0xFFD2966B;
      g.drawString(this.font, typeTag, x + 4, y + 3, typeColor, true);

      // Top-right: size badge.
      String size = p.sizeX() + "×" + p.sizeZ();
      int sw = this.font.width(size);
      g.drawString(this.font, size, x + PARCEL_CELL_W - sw - 4, y + 3, FG_DIM, true);

      // Icon, centred.
      if (isPlant) {
         if (this.parcelPlantIcon == null) {
            this.parcelPlantIcon = new net.minecraft.world.item.ItemStack(
               net.minecraft.world.item.Items.WHEAT);
         }
         g.renderItem(this.parcelPlantIcon, x + (PARCEL_CELL_W - 16) / 2, y + 14);
      } else {
         if (this.parcelAnimalIcon == null) {
            this.parcelAnimalIcon = new net.minecraft.world.item.ItemStack(
               net.minecraft.world.item.Items.COW_SPAWN_EGG);
         }
         g.renderItem(this.parcelAnimalIcon, x + (PARCEL_CELL_W - 16) / 2, y + 14);
      }

      // Owner name centred under icon.
      String ownerClip = UiText.truncate(this.font, p.ownerName(), PARCEL_CELL_W - 6);
      int ow = this.font.width(ownerClip);
      g.drawString(this.font, ownerClip,
         x + (PARCEL_CELL_W - ow) / 2, y + 34, UiTheme.BODY, true);

      // Content snapshot, faint, centred + truncated.
      String snapshot = parcelSnapshotText(p);
      String snapClip = UiText.truncate(this.font, snapshot, PARCEL_CELL_W - 6);
      int snw = this.font.width(snapClip);
      g.drawString(this.font, snapClip,
         x + (PARCEL_CELL_W - snw) / 2, y + 46, UiTheme.FAINT, true);

      // Position right-aligned bottom — a 3-coord hint for the player.
      net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
      String pos = centre.getX() + " · " + centre.getY() + " · " + centre.getZ();
      String posClip = UiText.truncate(this.font, pos, PARCEL_CELL_W - 6);
      int pw = this.font.width(posClip);
      g.drawString(this.font, posClip,
         x + (PARCEL_CELL_W - pw) / 2, y + 58, UiTheme.FAINT, true);
   }

   /** Build the human-readable content snapshot for a parcel — same
    *  string the popup uses, abbreviated by truncation when tile-bound. */
   private static String parcelSnapshotText(TownStateUpdatePayload.ParcelSummary p) {
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

   private TownStateUpdatePayload.ParcelSummary findParcel(String id) {
      if (id == null) return null;
      for (var p : this.state.parcels()) if (id.equals(p.id())) return p;
      return null;
   }

   /** Grid hit-test for the Parcels tab. Mirrors the cell rects laid
    *  out in {@link #renderParcelsBody}. */
   private TownStateUpdatePayload.ParcelSummary parcelAtPoint(double mouseX, double mouseY) {
      int paneL = panelLeft();
      int paneR = panelRight();
      int top   = panelTop() + 34;
      int bottom = panelBottom() - PADDING;
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + PARCEL_GRID_TOP;
      int gridBot = bottom - 6;
      if (mouseX < innerL || mouseX > innerR || mouseY < gridTop || mouseY > gridBot) return null;

      var parcels = this.state.parcels();
      if (parcels.isEmpty()) return null;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + PARCEL_CELL_GAP) / (PARCEL_CELL_W + PARCEL_CELL_GAP));
      int gridUsed = cols * PARCEL_CELL_W + (cols - 1) * PARCEL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;
      int scrollPx = this.parcelsGridScrollRows * (PARCEL_CELL_H + PARCEL_CELL_GAP);
      for (int i = 0; i < parcels.size(); i++) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (PARCEL_CELL_W + PARCEL_CELL_GAP);
         int cy = gridTop + row * (PARCEL_CELL_H + PARCEL_CELL_GAP) - scrollPx;
         if (mouseX >= cx && mouseX < cx + PARCEL_CELL_W
             && mouseY >= cy && mouseY < cy + PARCEL_CELL_H) {
            return parcels.get(i);
         }
      }
      return null;
   }

   /** Parcel detail popup — same shape as the Trade-offer popup
    *  (modal gold standard). Title row, content lines, button row at
    *  the bottom. Buttons: green "Open plan" → fires the existing
    *  openParcelEditor/openAnimalPlan verb; dark "Close" → dismiss. */
   private void renderParcelPopup(GuiGraphics g, int paneL, int paneR, int top, int bottom) {
      var p = findParcel(this.selectedParcelId);
      if (p == null) { this.selectedParcelId = null; return; }

      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
      int popH = 170;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;

      // Backdrop + frame.
      g.fill(paneL, top, paneR, bottom, 0xC0000000);
      g.fill(popX, popY, popX + popW, popY + popH, PANEL_BG);
      g.fill(popX - 1, popY - 1, popX + popW + 1, popY, PANEL_BORDER);
      g.fill(popX - 1, popY + popH, popX + popW + 1, popY + popH + 1, PANEL_BORDER);
      g.fill(popX - 1, popY, popX, popY + popH, PANEL_BORDER);
      g.fill(popX + popW, popY, popX + popW + 1, popY + popH, PANEL_BORDER);

      int x = popX + 14;
      int y = popY + 12;

      UiText.heading(g, this.font,
         p.ownerName() + "'s " + p.type().toLowerCase(java.util.Locale.ROOT)
            + " parcel — " + p.sizeX() + "×" + p.sizeZ(),
         x, y);
      y += 14;

      // Icon + content snapshot row.
      var icon = "PLANT".equals(p.type()) ? this.parcelPlantIcon : this.parcelAnimalIcon;
      if (icon != null) g.renderItem(icon, x, y);
      g.drawString(this.font, parcelSnapshotText(p),
         x + 22, y + 4, FG_PRIMARY, true);
      y += 24;

      // Position.
      net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
      g.drawString(this.font, "Centre: " + centre.toShortString(), x, y, FG_ACCENT, true);
      y += 12;

      // Created day.
      g.drawString(this.font, "Marked on day " + p.createdDay(), x, y, FG_DIM, true);
      y += 14;

      // Hint line.
      String hint = "PLANT".equals(p.type())
         ? "Open plan to edit the crop schedule."
         : "Open plan to edit breed/harvest targets.";
      g.drawString(this.font, hint, x, y, FG_FAINT, true);

      // Buttons.
      int btnY = popY + popH - 24;
      drawTradeButton(g, popX + 14, btnY, 110, "Open plan", true);
      drawTradeButton(g, popX + popW - 14 - 80, btnY, 80, "Close", false);
   }

   /** Hit-test for the parcel popup's buttons. Returns "open",
    *  "close", or null. Coords are LOGICAL. */
   private String hitTestParcelPopupButton(double mouseX, double mouseY,
                                            int paneL, int paneR, int top, int bottom) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int popW = Math.min(360, innerR - innerL - 40);
      int popH = 170;
      int popX = (innerL + innerR - popW) / 2;
      int popY = (top + bottom - popH) / 2;
      int btnY = popY + popH - 24;
      if (mouseY < btnY || mouseY >= btnY + 18) return null;
      int openX = popX + 14;
      if (mouseX >= openX && mouseX < openX + 110) return "open";
      int closeX = popX + popW - 14 - 80;
      if (mouseX >= closeX && mouseX < closeX + 80) return "close";
      return null;
   }

   private void renderVillagersTab(GuiGraphics graphics, int paneL, int paneR,
                                   int top, int bottom, int mouseX, int mouseY) {
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int spawnBtnH = 26;
      // Padding under the tab strip so content doesn't smash against the underline.
      int contentTop = top + 10;
      int listBottom = bottom - spawnBtnH;

      // Row 1: search box (already placed at top+4 by
      // initVillagersTabWidgets, innerL..innerL+240) and a right-aligned
      // count summary mirroring the Resources tab gold standard.
      int alive  = this.state.populationAlive();
      int total  = this.state.villagers().size();
      String totalLabel = total + " villagers"
         + (alive == total ? "" : " · " + alive + " alive");
      UiText.rightFaint(graphics, this.font, totalLabel, innerR, top + 8);

      // Row 2: filter chips on their own line below the search box.
      // 10 chips (6 prof + 4 status) plus two row labels won't share a
      // line with a 240-wide search box, so stack them.
      int chipsX = innerL;
      int chipsY = top + 26;
      renderVillagerFilterChips(graphics, chipsX, chipsY, mouseX, mouseY);

      // Grid layout mirroring the Resources tab (gold standard).
      // VILL_GRID_TOP corresponds to "below the chip row" — same y the
      // old list used.
      int gridTop = top + VILL_GRID_TOP;
      int gridBot = listBottom - 6;
      List<TownStateUpdatePayload.VillagerSummary> villagers = filteredVillagers();
      if (villagers.isEmpty()) {
         if (this.state.villagers().isEmpty()) {
            UiText.faint(graphics, this.font,
               "no villagers yet — click \"+ Spawn villager\"",
               innerL, gridTop + 10);
         } else {
            UiText.faint(graphics, this.font,
               "no villagers match the current filter",
               innerL, gridTop + 10);
         }
         return;
      }
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + VILL_CELL_GAP) / (VILL_CELL_W + VILL_CELL_GAP));
      int gridUsed = cols * VILL_CELL_W + (cols - 1) * VILL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;

      int totalRows = (villagers.size() + cols - 1) / cols;
      int visibleRows = Math.max(1, (gridBot - gridTop + VILL_CELL_GAP) / (VILL_CELL_H + VILL_CELL_GAP));
      int maxScrollRow = Math.max(0, totalRows - visibleRows);
      if (this.villagersGridScrollRows > maxScrollRow) this.villagersGridScrollRows = maxScrollRow;
      int scrollPx = this.villagersGridScrollRows * (VILL_CELL_H + VILL_CELL_GAP);

      scaledScissor(graphics, innerL, gridTop, innerR, gridBot);
      int i = 0;
      for (var v : villagers) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (VILL_CELL_W + VILL_CELL_GAP);
         int cy = gridTop + row * (VILL_CELL_H + VILL_CELL_GAP) - scrollPx;
         if (cy > gridBot) break;
         if (cy + VILL_CELL_H < gridTop) { i++; continue; }
         renderVillagerCell(graphics, v, cx, cy, mouseX, mouseY);
         i++;
      }
      graphics.disableScissor();
   }

   /** Villager grid cell geometry. Larger than the Resources cell
    *  because each tile carries more text — name, activity, HP. */
   private static final int VILL_CELL_W = 84;
   private static final int VILL_CELL_H = 72;
   private static final int VILL_CELL_GAP = 8;
   /** Y offset (from tab content top) at which the villager grid begins —
    *  leaves room for the search box (row 1) and chip row (row 2). */
   private static final int VILL_GRID_TOP = 48;

   /** Render one villager as a tile. Mirrors {@link #renderResourceCell}'s
    *  visual language: badge strip on top, icon centred, two text lines
    *  below, optional indicator at the bottom. Click anywhere on the
    *  tile opens the existing villager-detail modal — same flow as the
    *  old list rows, just hit-tested against the grid instead. */
   private void renderVillagerCell(GuiGraphics g,
                                    TownStateUpdatePayload.VillagerSummary v,
                                    int x, int y, int mouseX, int mouseY) {
      boolean hovered = mouseX >= x && mouseX < x + VILL_CELL_W
                     && mouseY >= y && mouseY < y + VILL_CELL_H;
      int bg = hovered ? TAB_ACTIVE_BG : ROW_BG;
      g.fill(x, y, x + VILL_CELL_W, y + VILL_CELL_H, bg);
      g.fill(x, y + VILL_CELL_H, x + VILL_CELL_W, y + VILL_CELL_H + 1, PANEL_BORDER);

      // Status pip top-left. Green = working, faint gray = idle, blue = sleeping.
      int pip;
      String activity = v.activity();
      if ("sleeping".equals(activity))      pip = 0xFF6E8FE0;
      else if ("idle".equals(activity))     pip = UiTheme.FAINT;
      else                                  pip = UiTheme.OK;
      g.fill(x + 3, y + 3, x + 7, y + 7, pip);

      // Anchor icons top-right.
      String anchor = (v.playerSetHome() && v.playerSetJob()) ? "⌂⚒"
                    : v.playerSetHome() ? "⌂" : v.playerSetJob() ? "⚒" : "";
      if (!anchor.isEmpty()) {
         int aw = this.font.width(anchor);
         g.drawString(this.font, anchor, x + VILL_CELL_W - aw - 4, y + 2, FG_DIM, true);
      }

      // Profession-themed icon, centred. Each profession maps to a
      // recognisable tool/output item; "none"/unknown falls back to a
      // villager spawn egg.
      int iconX = x + (VILL_CELL_W - 16) / 2;
      int iconY = y + 12;
      g.renderItem(iconForProfession(v.profession()), iconX, iconY);

      // Name centred — truncated to fit. Dim if dead.
      String name = v.name() + (v.alive() ? "" : " ✝");
      String nameClip = UiText.truncate(this.font, name, VILL_CELL_W - 6);
      int nw = this.font.width(nameClip);
      g.drawString(this.font, nameClip,
         x + (VILL_CELL_W - nw) / 2, y + 32,
         v.alive() ? UiTheme.BODY : UiTheme.FAINT, true);

      // Activity, faint, centred.
      String act = prettifyActivity(activity);
      String actClip = UiText.truncate(this.font, act, VILL_CELL_W - 6);
      int aw2 = this.font.width(actClip);
      g.drawString(this.font, actClip,
         x + (VILL_CELL_W - aw2) / 2, y + 44, UiTheme.FAINT, true);

      // HP bar near the bottom, centred 60 wide.
      int hpBarW = 60, hpBarH = 4;
      int hpX = x + (VILL_CELL_W - hpBarW) / 2;
      UiBar.draw(g, hpX, y + 58, hpBarW, hpBarH, v.health(), v.maxHealth());
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

   /** Hit-test for the chip row. Returns the (group, value) selected, or null.
    *  Must mirror the (x, y) used by {@link #renderVillagersBody} / the
    *  chipsX, chipsY locals there. */
   private String[] hitTestVillagerChips(double mouseX, double mouseY, int paneL, int top) {
      int x = paneL + UiTheme.PADDING;
      int y = top + 26;
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

   /** Map a profession name to a tile icon. Lazily caches each stack
    *  so renderItem isn't paying a registry lookup every frame. */
   private net.minecraft.world.item.ItemStack iconForProfession(String prof) {
      String key = prof == null ? "" : prof.toLowerCase(Locale.ROOT);
      return this.villagerIconByProfession.computeIfAbsent(key, k -> {
         net.minecraft.world.item.Item item = switch (k) {
            case "farmer"        -> net.minecraft.world.item.Items.WHEAT;
            case "shepherd"      -> net.minecraft.world.item.Items.SHEARS;
            case "butcher"       -> net.minecraft.world.item.Items.COOKED_BEEF;
            case "mason"         -> net.minecraft.world.item.Items.SMOOTH_STONE;
            case "librarian"     -> net.minecraft.world.item.Items.ENCHANTED_BOOK;
            case "cartographer"  -> net.minecraft.world.item.Items.FILLED_MAP;
            case "fisherman"     -> net.minecraft.world.item.Items.FISHING_ROD;
            case "fletcher"      -> net.minecraft.world.item.Items.BOW;
            case "toolsmith"     -> net.minecraft.world.item.Items.IRON_PICKAXE;
            case "weaponsmith"   -> net.minecraft.world.item.Items.IRON_SWORD;
            case "armorer"       -> net.minecraft.world.item.Items.IRON_CHESTPLATE;
            case "leatherworker" -> net.minecraft.world.item.Items.LEATHER;
            case "cleric"        -> net.minecraft.world.item.Items.BREWING_STAND;
            case "nitwit"        -> net.minecraft.world.item.Items.POPPY;
            default               -> net.minecraft.world.item.Items.VILLAGER_SPAWN_EGG;
         };
         return new net.minecraft.world.item.ItemStack(item);
      });
   }

   /** Hit-test the villager grid. Mirrors the layout used by
    *  {@link #renderVillagersBody} — recompute the same cell rects
    *  and find which one contains the cursor. Returns the villager's
    *  UUID, or null if the click missed every cell. */
   private UUID villagerAtPoint(double mouseX, double mouseY) {
      int paneL = panelLeft();
      int paneR = panelRight();
      int top   = panelTop() + 34;
      int bottom = panelBottom() - PADDING;
      int spawnBtnH = 26;
      int innerL = paneL + UiTheme.PADDING;
      int innerR = paneR - UiTheme.PADDING;
      int gridTop = top + VILL_GRID_TOP;
      int gridBot = bottom - spawnBtnH - 6;
      if (mouseX < innerL || mouseX > innerR || mouseY < gridTop || mouseY > gridBot) return null;

      var villagers = filteredVillagers();
      if (villagers.isEmpty()) return null;
      int gridW = innerR - innerL;
      int cols  = Math.max(1, (gridW + VILL_CELL_GAP) / (VILL_CELL_W + VILL_CELL_GAP));
      int gridUsed = cols * VILL_CELL_W + (cols - 1) * VILL_CELL_GAP;
      int gridX0   = innerL + (gridW - gridUsed) / 2;
      int scrollPx = this.villagersGridScrollRows * (VILL_CELL_H + VILL_CELL_GAP);
      for (int i = 0; i < villagers.size(); i++) {
         int row = i / cols;
         int col = i % cols;
         int cx = gridX0 + col * (VILL_CELL_W + VILL_CELL_GAP);
         int cy = gridTop + row * (VILL_CELL_H + VILL_CELL_GAP) - scrollPx;
         if (mouseX >= cx && mouseX < cx + VILL_CELL_W
             && mouseY >= cy && mouseY < cy + VILL_CELL_H) {
            return villagers.get(i).uuid();
         }
      }
      return null;
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

   private void renderVillagerDetail(GuiGraphics graphics, int paneL, int paneR, int top, int bottom,
                                      int mouseX, int mouseY) {
      Optional<TownStateUpdatePayload.VillagerSummary> match = findVillager(this.selectedVillager);
      if (match.isEmpty()) return;
      TownStateUpdatePayload.VillagerSummary v = match.get();
      int innerL = paneL + PADDING;
      int innerR = paneR - PADDING;

      // ── 1. Persistent header (always visible) ──
      renderDetailHeader(graphics, v, innerL, innerR, top);

      // ── 2. Tab strip ──
      int tabsY = top + DETAIL_HEADER_H;
      renderDetailTabStrip(graphics, innerL, innerR, tabsY, mouseX, mouseY);

      // ── 3. Tab body ──
      int bodyTop = tabsY + DETAIL_TAB_STRIP_H + 4;
      int bodyBottom = bottom - DETAIL_FOOTER_H;
      switch (this.detailTab) {
         case OVERVIEW      -> renderDetailOverviewBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case BACKSTORY     -> renderDetailBackstoryBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case MEMORIES      -> renderDetailMemoriesBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case TODOS         -> renderDetailTodosBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case PARCELS       -> renderDetailParcelsBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case RELATIONSHIPS -> renderDetailRelationshipsBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
         case ACTIONS       -> renderDetailActionsBody(graphics, v, innerL, innerR, bodyTop, bodyBottom);
      }
   }

   /** Persistent header: profession icon + name, status pills,
    *  HP + Hunger bars, LLM cost summary on the right. */
   private void renderDetailHeader(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                    int innerL, int innerR, int top) {
      // Profession icon (16×16) on the left, name beside it.
      g.renderItem(iconForProfession(v.profession()), innerL, top);
      String name = v.name() + (v.alive() ? "" : " ✝");
      g.drawString(this.font,
         Component.literal(name).withStyle(ChatFormatting.GOLD),
         innerL + 22, top + 2, FG_ACCENT, true);
      String profLine = (v.profession() == null || v.profession().isBlank() || "none".equals(v.profession())
                       ? "(unemployed)" : v.profession())
                     + (v.role() == null || v.role().isBlank() || "resident".equals(v.role())
                        ? "" : " · " + v.role());
      g.drawString(this.font, profLine, innerL + 22, top + 12, FG_DIM, true);

      // Status pills row at top + 22 — short, dense.
      int pillX = innerL + 22;
      pillX = drawStatusPill(g, pillX, top + 22, prettifyActivity(v.activity()), UiTheme.FAINT);
      if (v.playerSetHome()) pillX = drawStatusPill(g, pillX, top + 22, "⌂ home", FG_DIM);
      if (v.playerSetJob())  pillX = drawStatusPill(g, pillX, top + 22, "⚒ job",  FG_DIM);
      if (v.hunger() < 50)   pillX = drawStatusPill(g, pillX, top + 22, "hungry", 0xFFD55050);

      // Vitals on the right — HP bar, hunger bar, label stacked vertically.
      int vBarW = 80;
      int vBarX = innerR - vBarW;
      int hpY = top + 2;
      int hgY = top + 12;
      UiBar.draw(g, vBarX, hpY, vBarW, 5, v.health(), v.maxHealth());
      g.drawString(this.font, String.format(Locale.ROOT, "HP %.0f/%.0f", v.health(), v.maxHealth()),
         vBarX - this.font.width("HP 99/99") - 6, hpY - 1, FG_FAINT, true);
      int hungerFill = (int) Math.round(vBarW * (v.hunger() / 100.0));
      int hungerColor = v.hunger() >= 70 ? 0xFF6FA445
                       : v.hunger() >= 40 ? 0xFFE0B040
                       :                    0xFFD55050;
      g.fill(vBarX, hgY, vBarX + vBarW, hgY + 5, 0xFF2A1F15);
      g.fill(vBarX, hgY, vBarX + hungerFill, hgY + 5, hungerColor);
      g.drawString(this.font, "Hunger " + v.hunger() + "/100",
         vBarX - this.font.width("Hunger 100/100") - 6, hgY - 1, FG_FAINT, true);
      String metric = String.format(Locale.ROOT,
         "%d calls • %d+%d tok • $%.4f", v.llmCalls(),
         v.inputTokens(), v.outputTokens(), v.estCostUsd());
      g.drawString(this.font, metric, innerR - this.font.width(metric), top + 22, FG_FAINT, true);

      // Underline under the header band.
      g.fill(innerL, top + DETAIL_HEADER_H - 4, innerR, top + DETAIL_HEADER_H - 3, PANEL_BORDER);
   }

   /** Status pill — a tiny labelled chip used by the header. Returns
    *  the next X cursor so callers can chain pills horizontally. */
   private int drawStatusPill(GuiGraphics g, int x, int y, String text, int fg) {
      int w = this.font.width(text) + 8;
      g.fill(x, y, x + w, y + 11, 0x802A1F15);
      g.drawString(this.font, text, x + 4, y + 2, fg, true);
      return x + w + 4;
   }

   /** Draw + hit-test the tab strip. Stores per-tab x rects so
    *  mouseClicked can map clicks back to the right tab. */
   private final java.util.EnumMap<DetailTab, int[]> detailTabRects = new java.util.EnumMap<>(DetailTab.class);
   private void renderDetailTabStrip(GuiGraphics g, int innerL, int innerR, int y,
                                      int mouseX, int mouseY) {
      DetailTab[] tabs = DetailTab.values();
      int cx = innerL;
      for (DetailTab t : tabs) {
         String label = tabLabel(t);
         int w = this.font.width(label) + 12;
         boolean active = t == this.detailTab;
         boolean hovered = mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + DETAIL_TAB_STRIP_H;
         int bg = active ? TAB_ACTIVE_BG : (hovered ? ROW_BG : 0x00000000);
         if (bg != 0) g.fill(cx, y, cx + w, y + DETAIL_TAB_STRIP_H, bg);
         int textColor = active ? FG_ACCENT : (hovered ? FG_PRIMARY : FG_DIM);
         g.drawString(this.font, label, cx + 6, y + 5, textColor, true);
         if (active) g.fill(cx, y + DETAIL_TAB_STRIP_H, cx + w, y + DETAIL_TAB_STRIP_H + 1, FG_ACCENT);
         detailTabRects.put(t, new int[]{cx, y, w, DETAIL_TAB_STRIP_H});
         cx += w + 2;
      }
   }

   private static String tabLabel(DetailTab t) {
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

   /** Hit-test the tab strip. Returns the clicked tab or null. */
   private DetailTab hitTestDetailTabs(double mouseX, double mouseY) {
      for (var e : detailTabRects.entrySet()) {
         int[] r = e.getValue();
         if (mouseX >= r[0] && mouseX < r[0] + r[2]
             && mouseY >= r[1] && mouseY < r[1] + r[3]) {
            return e.getKey();
         }
      }
      return null;
   }

   // ──────────────────── Tab bodies ────────────────────

   private void renderDetailOverviewBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                          int innerL, int innerR, int top, int bottom) {
      int y = top;
      // Quick-look: parcels owned + todos open + memories + last day's
      // schedule keyed off the activity status.
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      int plantParcels = 0, animalParcels = 0;
      // We don't carry per-villager parcel ids in VillagerSummary — pull
      // from the top-level parcels list and match by owner.
      for (var p : this.state.parcels()) {
         if (v.uuid().equals(p.ownerUuid())) {
            if ("PLANT".equals(p.type())) plantParcels++;
            else if ("ANIMAL".equals(p.type())) animalParcels++;
         }
      }

      g.drawString(this.font, "At a glance", innerL, y, FG_DIM, true);
      y += 14;
      g.drawString(this.font,
         "• Parcels: " + (plantParcels + animalParcels)
            + " (" + plantParcels + " plant · " + animalParcels + " animal)",
         innerL, y, FG_PRIMARY, true); y += 11;
      g.drawString(this.font,
         "• Open todos: " + openTodos,
         innerL, y, openTodos > 0 ? FG_ERROR : FG_PRIMARY, true); y += 11;
      g.drawString(this.font,
         "• Memories: " + v.memoryCount()
            + " (last compacted day " + v.lastCompactedDay() + ")",
         innerL, y, FG_PRIMARY, true); y += 11;
      g.drawString(this.font,
         "• Pinned facts: " + v.pinnedFacts().size() + " / 100",
         innerL, y, FG_PRIMARY, true); y += 11;
      g.drawString(this.font,
         "• Inventory: " + v.inventory().size() + " stacks",
         innerL, y, FG_PRIMARY, true); y += 14;

      // Inventory preview — first few stacks rendered as item icons + counts.
      g.drawString(this.font, "Carrying", innerL, y, FG_DIM, true);
      y += 12;
      if (v.inventory().isEmpty()) {
         g.drawString(this.font, "(empty bag)", innerL, y, FG_FAINT, true);
      } else {
         int slotX = innerL;
         int rendered = 0;
         for (var ic : v.inventory()) {
            if (rendered >= 12) break;
            var stack = stackForItemId(ic.itemId());
            g.renderItem(stack, slotX, y);
            String count = String.valueOf(ic.count());
            g.drawString(this.font, count, slotX + 18 - this.font.width(count), y + 8, FG_FAINT, true);
            slotX += 26;
            rendered++;
         }
         if (v.inventory().size() > 12) {
            g.drawString(this.font, "+" + (v.inventory().size() - 12) + " more",
               slotX, y + 4, FG_FAINT, true);
         }
      }
   }

   private void renderDetailBackstoryBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                           int innerL, int innerR, int top, int bottom) {
      // Persona seed label sits above the EditBox placed in init.
      g.drawString(this.font, "Persona seed (informs the LLM-generated backstory below)",
         innerL, top, FG_DIM, true);
      int storyTop = top + 110;       // below the seed editor + save/regen row
      g.drawString(this.font, "Backstory", innerL, storyTop, FG_DIM, true);
      String bs = v.backstory() == null || v.backstory().isBlank() ? "(none yet)" : v.backstory();
      List<FormattedCharSequence> wrapped = this.font.split(Component.literal(bs), innerR - innerL);
      int by = storyTop + 12;
      for (FormattedCharSequence seq : wrapped) {
         if (by + this.font.lineHeight > bottom) break;
         g.drawString(this.font, seq, innerL, by, FG_PRIMARY, true);
         by += this.font.lineHeight + 2;
      }
   }

   private void renderDetailMemoriesBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                          int innerL, int innerR, int top, int bottom) {
      // Beliefs preview (2 lines max).
      g.drawString(this.font, "Beliefs (auto-updated nightly)", innerL, top, FG_DIM, true);
      String beliefs = v.beliefs() == null || v.beliefs().isBlank() ? "(none yet)" : v.beliefs();
      List<FormattedCharSequence> beliefLines = this.font.split(Component.literal(beliefs), innerR - innerL - 110);
      int by = top + 12;
      int beliefsMax = 2;
      for (int i = 0; i < beliefLines.size() && i < beliefsMax; i++) {
         g.drawString(this.font, beliefLines.get(i), innerL, by, FG_PRIMARY, true);
         by += this.font.lineHeight + 1;
      }
      if (beliefLines.size() > beliefsMax) {
         g.drawString(this.font, "… (" + (beliefLines.size() - beliefsMax) + " more lines — Expand)",
            innerL, by, FG_FAINT, true);
      }

      // Stats line.
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      String stats = String.format(Locale.ROOT,
         "memories: %d • compacted day %d • todos: %d open • pins: %d/100",
         v.memoryCount(), v.lastCompactedDay(), openTodos, v.pinnedFacts().size());
      g.drawString(this.font, stats, innerL, top + 48, FG_FAINT, true);

      // Pinned facts list (scrollable).
      int listTop = top + DETAIL_MEMORIES_LIST_TOP;
      int listBottom = bottom - 30;
      int rowH = 22;
      scaledScissor(g, innerL, listTop, innerR, listBottom);
      int y = listTop - this.detailTabScroll;
      g.drawString(this.font,
         "Pinned facts (" + v.pinnedFacts().size() + "/100)", innerL, y, FG_DIM, true);
      y += 12;
      if (v.pinnedFacts().isEmpty()) {
         g.drawString(this.font, "(none — add below to lock in long-term memories)",
            innerL, y, FG_FAINT, true);
      } else {
         for (TownStateUpdatePayload.PinSummary p : v.pinnedFacts()) {
            if (y + rowH < listTop) { y += rowH; continue; }
            if (y > listBottom)     break;
            g.fill(innerL, y, innerR - 50, y + rowH - 2, ROW_BG);
            int textFg = "resolved".equals(p.status()) ? FG_RESOLVED : FG_PRIMARY;
            String shown = truncate(p.text(), (innerR - 50) - innerL - 8);
            g.drawString(this.font, shown, innerL + 4, y + 3, textFg, true);
            String tag = "resolved".equals(p.status()) ? "resolved" : ("day " + p.createdDay());
            g.drawString(this.font, tag, innerL + 4, y + 13, FG_FAINT, true);
            y += rowH;
         }
      }
      g.disableScissor();
   }

   private void renderDetailTodosBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                       int innerL, int innerR, int top, int bottom) {
      int openTodos = (int) v.todos().stream().filter(t -> "open".equals(t.status())).count();
      g.drawString(this.font, "Open commitments (" + openTodos + ")", innerL, top, FG_DIM, true);
      if (openTodos == 0) {
         g.drawString(this.font, "(nothing on their plate)", innerL, top + 14, FG_FAINT, true);
         return;
      }
      int listTop = top + 14;
      int rowH = 22;
      scaledScissor(g, innerL, listTop, innerR, bottom);
      int y = listTop - this.detailTabScroll;
      for (var t : v.todos()) {
         if (!"open".equals(t.status())) continue;
         if (y + rowH < listTop) { y += rowH; continue; }
         if (y > bottom)         break;
         g.fill(innerL, y, innerR - 50, y + rowH - 2, ROW_BG);
         String txt = truncate(t.text(), innerR - 50 - innerL - 8);
         g.drawString(this.font, txt, innerL + 4, y + 3, FG_PRIMARY, true);
         g.drawString(this.font, "day " + t.createdDay(), innerL + 4, y + 13, FG_FAINT, true);
         y += rowH;
      }
      g.disableScissor();
   }

   /** Y bounds of each rendered parcel row inside the Detail Parcels
    *  tab. Populated by {@link #renderDetailParcelsBody}, consumed by
    *  the click handler so clicking a row opens the appropriate
    *  planner (CropPlan for PLANT, AnimalPlan for ANIMAL). */
   private final java.util.LinkedHashMap<String, int[]> detailParcelRowRects =
      new java.util.LinkedHashMap<>();

   private void renderDetailParcelsBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                         int innerL, int innerR, int top, int bottom) {
      detailParcelRowRects.clear();
      g.drawString(this.font, "Owned parcels — click a row to open the planner",
         innerL, top, FG_DIM, true);
      int y = top + 14;
      int count = 0;
      int rowH = 22;
      // Read mouse position via the Minecraft handle so hover state
      // colors the row the player's about to click. Same trick the
      // Trade-cell renderer uses.
      double mxd = this.minecraft.mouseHandler.xpos()
         * (double) this.minecraft.getWindow().getGuiScaledWidth()
         / (double) this.minecraft.getWindow().getScreenWidth();
      double myd = this.minecraft.mouseHandler.ypos()
         * (double) this.minecraft.getWindow().getGuiScaledHeight()
         / (double) this.minecraft.getWindow().getScreenHeight();
      for (var p : this.state.parcels()) {
         if (!v.uuid().equals(p.ownerUuid())) continue;
         if (y + rowH > bottom) break;
         count++;
         boolean hovered = mxd >= innerL && mxd < innerR && myd >= y && myd < y + rowH - 2;
         g.fill(innerL, y, innerR, y + rowH - 2, hovered ? TAB_ACTIVE_BG : ROW_BG);
         net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.of(p.centerPos());
         String head = p.type() + " · " + p.sizeX() + "×" + p.sizeZ() + " @ " + centre.toShortString();
         g.drawString(this.font, head, innerL + 4, y + 3, FG_PRIMARY, true);
         g.drawString(this.font, parcelSnapshotText(p), innerL + 4, y + 13, FG_FAINT, true);
         // Right-side hint chip.
         String hint = "PLANT".equals(p.type()) ? "Open crop plan ▸" : "Open animal plan ▸";
         g.drawString(this.font, hint, innerR - this.font.width(hint) - 4, y + 8, FG_ACCENT, true);
         detailParcelRowRects.put(p.id(), new int[]{innerL, y, innerR - innerL, rowH - 2});
         y += rowH;
      }
      if (count == 0) {
         g.drawString(this.font, "(no parcels owned — use the Surveyor's Stake to mark land)",
            innerL, y, FG_FAINT, true);
      }
   }

   /** Hit-test the Detail Parcels tab. Returns the parcel id under
    *  the cursor, or null if none. */
   private String hitTestDetailParcelRow(double mouseX, double mouseY) {
      for (var e : detailParcelRowRects.entrySet()) {
         int[] r = e.getValue();
         if (mouseX >= r[0] && mouseX < r[0] + r[2]
             && mouseY >= r[1] && mouseY < r[1] + r[3]) {
            return e.getKey();
         }
      }
      return null;
   }

   private void renderDetailRelationshipsBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                               int innerL, int innerR, int top, int bottom) {
      g.drawString(this.font, "Relationships (derived from tavern conversations + dialogue)",
         innerL, top, FG_DIM, true);
      int y = top + 14;
      // Memory counts by tagged chat partner are server-side data we
      // don't currently ship in VillagerSummary — render a placeholder
      // for now, and a TODO note explaining what the next step is. The
      // Stage 11e chat:<uuid> memory entries DO accumulate; surfacing
      // them is a future stage.
      g.drawString(this.font, "Relationship counters arrive in a future stage.",
         innerL, y, FG_FAINT, true);
      y += 12;
      g.drawString(this.font, "Today the data exists (chat:<uuid> memories per banter)", innerL, y, FG_FAINT, true);
      y += 11;
      g.drawString(this.font, "but isn't aggregated yet — see Stage 11e for the writers.", innerL, y, FG_FAINT, true);
   }

   private void renderDetailActionsBody(GuiGraphics g, TownStateUpdatePayload.VillagerSummary v,
                                         int innerL, int innerR, int top, int bottom) {
      g.drawString(this.font, "Admin actions", innerL, top, FG_DIM, true);
      g.drawString(this.font,
         "Use these levers carefully — most are server-authoritative and instant.",
         innerL, top + 50, FG_FAINT, true);
      g.drawString(this.font,
         "More levers (rename, clear home/job, manual leisure override) coming as server actions are added.",
         innerL, top + 62, FG_FAINT, true);
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
      int contentTop = top + 10;
      UiText.heading(graphics, this.font, "Activity log (latest first)", innerL, contentTop);
      List<TownStateUpdatePayload.LogEntry> entries = this.state.log();
      UiText.rightFaint(graphics, this.font,
         entries.size() + " entries", innerR, contentTop);
      if (entries.isEmpty()) {
         UiText.faint(graphics, this.font,
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
