package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.OpenAnimalPlanPayload;
import com.yucareux.townfolk.network.SetAnimalPlanPayload;
import com.yucareux.townfolk.town.AnimalPlan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Stand-alone (non-container) screen for editing an ANIMAL parcel's
 * {@link AnimalPlan}. Opened by the server in response to a click on
 * an ANIMAL row in the Parcels tab.
 *
 * <p>Layout follows the project UX standard: species shown as a grid
 * of cards (one per species present on the parcel or with a saved
 * entry); per-card controls are an icon, current census, target
 * (− / + nudge), and a mode toggle (Breed-up / Hold).
 *
 * <pre>
 *  ┌──────────────────────────────────────────────────────────┐
 *  │  Animal plan                                             │
 *  │  Parcel xyz · 3 species                                  │
 *  │                                                          │
 *  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐         │
 *  │  │ 🐄 cow      │ │ 🐑 sheep    │ │ 🐔 chicken  │         │
 *  │  │ now: 4 (1)  │ │ now: 6 (0)  │ │ now: 8 (2)  │         │
 *  │  │ target: 6   │ │ target: 6   │ │ target: 12  │         │
 *  │  │ − [6] +     │ │ − [6] +     │ │ − [12] +    │         │
 *  │  │ [Breed-up]  │ │ [Hold]      │ │ [Breed-up]  │         │
 *  │  └─────────────┘ └─────────────┘ └─────────────┘         │
 *  │                                                          │
 *  │                                          [Cancel] [Save] │
 *  └──────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class AnimalPlanScreen extends Screen {

   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int CARD_BG      = 0x80000000;
   private static final int CARD_BORDER  = 0xFF6E5230;
   private static final int FG_PRIMARY   = 0xFFEDE0C2;
   private static final int FG_ACCENT    = 0xFFFFD27A;
   private static final int FG_DIM       = 0xFFB89B70;
   private static final int FG_FAINT     = 0xFF7A6849;

   private static final int CARD_W = 140;
   private static final int CARD_H = 116;
   private static final int CARD_GAP = 10;
   private static final int PANEL_W = 540;
   private static final int PANEL_H = 320;

   private final OpenAnimalPlanPayload payload;
   private final List<Row> rows = new ArrayList<>();
   private int panelLeft, panelTop;

   public AnimalPlanScreen(OpenAnimalPlanPayload payload) {
      super(Component.literal("Animal plan"));
      this.payload = payload;
      for (var s : payload.species()) {
         AnimalPlan.Mode mode = AnimalPlan.Mode.safeFromName(s.mode());
         if (mode == null) mode = AnimalPlan.Mode.HOLD;
         this.rows.add(new Row(s.speciesId(), s.currentCount(), s.currentBabies(),
            s.target(), mode));
      }
   }

   private static final class Row {
      final String speciesId;
      final int currentCount;
      final int currentBabies;
      int target;
      AnimalPlan.Mode mode;

      Row(String id, int now, int babies, int target, AnimalPlan.Mode mode) {
         this.speciesId = id;
         this.currentCount = now;
         this.currentBabies = babies;
         this.target = target;
         this.mode = mode;
      }
   }

   @Override
   protected void init() {
      this.panelLeft = (this.width - PANEL_W) / 2;
      this.panelTop  = (this.height - PANEL_H) / 2;

      int gridOriginX = this.panelLeft + 16;
      int gridOriginY = this.panelTop + 60;
      int cols = (PANEL_W - 32 + CARD_GAP) / (CARD_W + CARD_GAP);

      for (int i = 0; i < this.rows.size(); i++) {
         final int idx = i;
         int col = i % cols;
         int rowIdx = i / cols;
         int cardX = gridOriginX + col * (CARD_W + CARD_GAP);
         int cardY = gridOriginY + rowIdx * (CARD_H + CARD_GAP);

         // − target +
         addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            Row r = this.rows.get(idx);
            r.target = Math.max(0, r.target - 1);
         }).bounds(cardX + 10, cardY + 60, 20, 18).build());

         addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            Row r = this.rows.get(idx);
            r.target = Math.min(AnimalPlan.MAX_TARGET, r.target + 1);
         }).bounds(cardX + CARD_W - 10 - 20, cardY + 60, 20, 18).build());

         // Mode toggle. Single button cycling between BREED_UP and HOLD.
         Button modeBtn = Button.builder(Component.literal(modeLabel(this.rows.get(idx).mode)), b -> {
            Row r = this.rows.get(idx);
            r.mode = r.mode == AnimalPlan.Mode.BREED_UP ? AnimalPlan.Mode.HOLD
                                                        : AnimalPlan.Mode.BREED_UP;
            b.setMessage(Component.literal(modeLabel(r.mode)));
         }).bounds(cardX + 10, cardY + CARD_H - 26, CARD_W - 20, 18).build();
         addRenderableWidget(modeBtn);
      }

      // Cancel / Save bar.
      int btnY = this.panelTop + PANEL_H - 26;
      addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
         .bounds(this.panelLeft + PANEL_W - 16 - 60 - 8 - 60, btnY, 60, 18).build());
      addRenderableWidget(Button.builder(Component.literal("Save"), b -> commitAndClose())
         .bounds(this.panelLeft + PANEL_W - 16 - 60, btnY, 60, 18).build());
   }

   private static String modeLabel(AnimalPlan.Mode m) {
      return m == AnimalPlan.Mode.BREED_UP ? "Breed-up" : "Hold";
   }

   private void commitAndClose() {
      List<SetAnimalPlanPayload.Entry> entries = new ArrayList<>();
      for (Row r : this.rows) {
         entries.add(new SetAnimalPlanPayload.Entry(r.speciesId, r.target, r.mode.name()));
      }
      PacketDistributor.sendToServer(new SetAnimalPlanPayload(
         this.payload.parcelId(), this.payload.townSquarePos(), entries));
      this.onClose();
   }

   @Override
   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
      this.renderBackground(g, mouseX, mouseY, partialTicks);

      // Panel.
      int l = this.panelLeft, t = this.panelTop;
      int r = l + PANEL_W, b = t + PANEL_H;
      g.fill(l, t, r, b, PANEL_BG);
      g.fill(l - 1, t - 1, r + 1, t, PANEL_BORDER);
      g.fill(l - 1, b, r + 1, b + 1, PANEL_BORDER);
      g.fill(l - 1, t - 1, l, b + 1, PANEL_BORDER);
      g.fill(r, t - 1, r + 1, b + 1, PANEL_BORDER);

      // Header.
      g.drawString(this.font,
         Component.literal("Animal plan").withStyle(ChatFormatting.GOLD),
         l + 14, t + 12, FG_ACCENT, true);
      String sub = "Parcel " + this.payload.parcelId() + " · "
                 + this.rows.size() + (this.rows.size() == 1 ? " species" : " species");
      g.drawString(this.font, sub, l + 14, t + 28, FG_DIM, true);
      String hint = "Drag a target up/down. Toggle Breed-up to grow the herd; Hold to keep it as is.";
      g.drawString(this.font, hint, l + 14, t + 42, FG_FAINT, true);

      if (this.rows.isEmpty()) {
         g.drawString(this.font,
            "No animals on this parcel yet. Bring some adults inside the boundary first.",
            l + 16, t + 80, FG_FAINT, true);
      }

      // Cards.
      int gridOriginX = l + 16;
      int gridOriginY = t + 60;
      int cols = (PANEL_W - 32 + CARD_GAP) / (CARD_W + CARD_GAP);
      for (int i = 0; i < this.rows.size(); i++) {
         int col = i % cols;
         int rowIdx = i / cols;
         int cardX = gridOriginX + col * (CARD_W + CARD_GAP);
         int cardY = gridOriginY + rowIdx * (CARD_H + CARD_GAP);
         renderRow(g, this.rows.get(i), cardX, cardY);
      }

      super.render(g, mouseX, mouseY, partialTicks);
   }

   private void renderRow(GuiGraphics g, Row row, int x, int y) {
      // Card frame.
      g.fill(x, y, x + CARD_W, y + CARD_H, CARD_BG);
      g.fill(x, y, x + CARD_W, y + 1, CARD_BORDER);
      g.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, CARD_BORDER);
      g.fill(x, y, x + 1, y + CARD_H, CARD_BORDER);
      g.fill(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, CARD_BORDER);

      // Icon — try the matching spawn-egg item; fall back to a paper
      // sprite if we can't resolve one.
      ItemStack icon = iconForSpecies(row.speciesId);
      g.renderItem(icon, x + 8, y + 6);

      // Species name (short label).
      g.drawString(this.font, shortName(row.speciesId),
         x + 30, y + 10, FG_PRIMARY, true);

      // Census line.
      String census = "now: " + row.currentCount
                    + (row.currentBabies > 0 ? " (" + row.currentBabies + " baby"
                       + (row.currentBabies == 1 ? "" : "ies") + ")" : "");
      g.drawString(this.font, census, x + 8, y + 30, FG_DIM, true);

      // "target: N" + a big "[N]" centered between the − / + buttons.
      g.drawString(this.font, "target", x + 8, y + 48, FG_FAINT, true);
      String tgt = "[" + row.target + "]";
      int tgtW = this.font.width(tgt);
      g.drawString(this.font, tgt,
         x + (CARD_W - tgtW) / 2, y + 65,
         row.target > 0 ? FG_ACCENT : FG_FAINT, true);

      // Status / hint between the buttons row and the mode button.
      String stateHint = row.mode == AnimalPlan.Mode.BREED_UP
         ? (row.currentCount >= row.target ? "at target" : (row.target - row.currentCount) + " to go")
         : "no breeding";
      int hintW = this.font.width(stateHint);
      g.drawString(this.font, stateHint,
         x + (CARD_W - hintW) / 2, y + CARD_H - 40, FG_DIM, true);
   }

   /** Best-effort icon for a species. Tries the spawn-egg item; if
    *  that's missing or not a SpawnEggItem, falls back to paper. */
   private static ItemStack iconForSpecies(String speciesId) {
      ResourceLocation rl = ResourceLocation.tryParse(speciesId);
      if (rl == null) return new ItemStack(Items.PAPER);
      // Spawn-egg item id convention: namespace:species_spawn_egg.
      ResourceLocation eggId = ResourceLocation.fromNamespaceAndPath(rl.getNamespace(),
         rl.getPath() + "_spawn_egg");
      var item = BuiltInRegistries.ITEM.get(eggId);
      if (item instanceof SpawnEggItem) return new ItemStack(item);
      return new ItemStack(Items.PAPER);
   }

   private static String shortName(String speciesId) {
      int c = speciesId.indexOf(':');
      return (c < 0 ? speciesId : speciesId.substring(c + 1)).replace('_', ' ');
   }

   /** Make sure unused-import lint stays clean — placeholder kept for
    *  potential future use of the entity type registry. */
   @SuppressWarnings("unused")
   private static EntityType<?> entityType(String speciesId) {
      ResourceLocation rl = ResourceLocation.tryParse(speciesId);
      return rl == null ? null : BuiltInRegistries.ENTITY_TYPE.get(rl);
   }

   @Override
   public boolean isPauseScreen() { return false; }

   /**
    * Escape: treat as Cancel (close without saving). Without this
    * override, Mojang's base Screen.keyPressed routes Escape to
    * {@code Minecraft.setScreen(null)} which closes this screen AND
    * any underlying admin screen — surprising the player by dumping
    * them back into the world. Cancel-on-escape is the expected
    * modal-dismiss behaviour.
    */
   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      // GLFW.GLFW_KEY_ESCAPE = 256.
      if (keyCode == 256) {
         this.onClose();
         return true;
      }
      return super.keyPressed(keyCode, scanCode, modifiers);
   }
}
