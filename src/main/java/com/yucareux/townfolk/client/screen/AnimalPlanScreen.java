package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.OpenAnimalPlanPayload;
import com.yucareux.townfolk.network.SetAnimalPlanPayload;
import com.yucareux.townfolk.town.AnimalPlan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * entry); each card holds an icon, current census, a numeric
 * target EditBox, and a Breed-up / Hold toggle styled as a chip.
 * No vanilla button chrome — chips/EditBoxes only — to match the
 * dark-panel aesthetic of the town admin screen.
 *
 * <pre>
 *  ┌──────────────────────────────────────────────────────────┐
 *  │  Animal plan                                             │
 *  │  Parcel xyz · 3 species                                  │
 *  │                                                          │
 *  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐         │
 *  │  │ 🐄 cow      │ │ 🐑 sheep    │ │ 🐔 chicken  │         │
 *  │  │ now: 4 (1)  │ │ now: 6 (0)  │ │ now: 8 (2)  │         │
 *  │  │ Target:     │ │ Target:     │ │ Target:     │         │
 *  │  │  [   6   ]  │ │  [   6   ]  │ │  [  12   ]  │         │
 *  │  │  ── chip ── │ │  ── chip ── │ │  ── chip ── │         │
 *  │  │  Breed-up   │ │   Hold      │ │  Breed-up   │         │
 *  │  └─────────────┘ └─────────────┘ └─────────────┘         │
 *  │                                                          │
 *  │                                          [Cancel] [Save] │
 *  └──────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class AnimalPlanScreen extends Screen {

   private static final int PANEL_BG     = 0xF01A130E;
   private static final int PANEL_BORDER = 0xFF8C6E3D;
   private static final int CARD_BG      = 0xC0140E07;   // mostly opaque so text underneath doesn't bleed
   private static final int CARD_BORDER  = 0xFF6E5230;
   private static final int CHIP_BG      = 0xFF2A2018;
   private static final int CHIP_BG_ACTIVE = 0xFF3C5A22; // green for Breed-up
   private static final int CHIP_BG_HOVER  = 0xFF38281A;
   private static final int CHIP_BORDER  = 0xFF8C6E3D;
   private static final int FG_PRIMARY   = 0xFFEDE0C2;
   private static final int FG_ACCENT    = 0xFFFFD27A;
   private static final int FG_DIM       = 0xFFB89B70;
   private static final int FG_FAINT     = 0xFF7A6849;
   private static final int FG_ON_GREEN  = 0xFFE8FFD2;

   private static final int CARD_W = 140;
   private static final int CARD_H = 132;     // taller to fit the EditBox cleanly
   private static final int CARD_GAP = 10;
   private static final int PANEL_W = 540;
   private static final int PANEL_H = 360;

   /** Bottom-bar action chip metrics. */
   private static final int FOOT_CHIP_W = 70;
   private static final int FOOT_CHIP_H = 22;

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
      /** Cached widget reference so we can read the box's text in
       *  commit and re-render the visible state. */
      EditBox targetBox;

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
         final Row r = this.rows.get(i);
         int col = i % cols;
         int rowIdx = i / cols;
         int cardX = gridOriginX + col * (CARD_W + CARD_GAP);
         int cardY = gridOriginY + rowIdx * (CARD_H + CARD_GAP);

         // Target EditBox — replaces the previous +/- pair. Centered
         // in the card, 40px wide, accepts digits, clamps to
         // [0, MAX_TARGET] on commit.
         int boxW = 56, boxH = 18;
         EditBox box = new EditBox(this.font,
            cardX + (CARD_W - boxW) / 2, cardY + 64,
            boxW, boxH, Component.literal("target"));
         box.setMaxLength(3);            // 3 digits is plenty (cap is 64)
         box.setBordered(true);
         box.setValue(String.valueOf(r.target));
         // Digit-only filter — Mojang's EditBox accepts any text by
         // default; this stops the player typing letters/symbols.
         box.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
         // Live commit on every keystroke so chip hints update.
         box.setResponder(s -> {
            int v = 0;
            try { v = s.isEmpty() ? 0 : Integer.parseInt(s); }
            catch (NumberFormatException ignored) { /* digit-filter prevents this */ }
            if (v < 0) v = 0;
            if (v > AnimalPlan.MAX_TARGET) v = AnimalPlan.MAX_TARGET;
            r.target = v;
         });
         r.targetBox = box;
         addRenderableWidget(box);
      }
   }

   private static String modeLabel(AnimalPlan.Mode m) {
      return m == AnimalPlan.Mode.BREED_UP ? "Breed-up" : "Hold";
   }

   private void commitAndClose() {
      // Force-flush any pending EditBox content (in case the user
      // types and immediately clicks Save without un-focusing).
      for (Row r : this.rows) {
         if (r.targetBox != null) {
            String s = r.targetBox.getValue();
            try {
               int v = s.isEmpty() ? 0 : Integer.parseInt(s);
               r.target = Math.max(0, Math.min(AnimalPlan.MAX_TARGET, v));
            } catch (NumberFormatException ignored) { /* responder filter usually catches */ }
         }
      }
      List<SetAnimalPlanPayload.Entry> entries = new ArrayList<>();
      for (Row r : this.rows) {
         entries.add(new SetAnimalPlanPayload.Entry(r.speciesId, r.target, r.mode.name()));
      }
      PacketDistributor.sendToServer(new SetAnimalPlanPayload(
         this.payload.parcelId(), this.payload.townSquarePos(), entries));
      this.onClose();
   }

   /**
    * Suppress the vanilla blurred-world backdrop. We paint our own
    * dim layer behind the panel so the panel's text doesn't read as
    * sitting behind a frosted-glass blur (the original Z-index
    * complaint).
    */
   @Override
   public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      // Single uniform dim so the world isn't distracting, no blur.
      g.fill(0, 0, this.width, this.height, 0xB0000000);
   }

   @Override
   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
      this.renderBackground(g, mouseX, mouseY, partialTicks);

      // Panel.
      int l = this.panelLeft, t = this.panelTop;
      int r = l + PANEL_W, b = t + PANEL_H;
      g.fill(l, t, r, b, PANEL_BG);
      drawBorderBox(g, l, t, r, b, PANEL_BORDER);

      // Header.
      g.drawString(this.font,
         Component.literal("Animal plan").withStyle(ChatFormatting.GOLD),
         l + 14, t + 12, FG_ACCENT, true);
      String sub = "Parcel " + this.payload.parcelId() + " · "
                 + this.rows.size() + " species";
      g.drawString(this.font, sub, l + 14, t + 28, FG_DIM, true);
      g.drawString(this.font,
         "Set a target count. Breed-up grows the herd toward it; Hold keeps the current size.",
         l + 14, t + 42, FG_FAINT, true);

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
         renderRow(g, this.rows.get(i), cardX, cardY, mouseX, mouseY);
      }

      // Bottom Cancel / Save chip row — custom-drawn to match the
      // card aesthetic rather than vanilla Button chrome.
      int footY = t + PANEL_H - FOOT_CHIP_H - 8;
      int saveX = r - 14 - FOOT_CHIP_W;
      int cancelX = saveX - 8 - FOOT_CHIP_W;
      renderFootChip(g, cancelX, footY, "Cancel", false, mouseX, mouseY);
      renderFootChip(g, saveX,   footY, "Save",   true,  mouseX, mouseY);

      // EditBoxes render via super.render — invoke LAST so they paint
      // on top of card backgrounds.
      super.render(g, mouseX, mouseY, partialTicks);
   }

   private void renderRow(GuiGraphics g, Row row, int x, int y, int mouseX, int mouseY) {
      // Card frame.
      g.fill(x, y, x + CARD_W, y + CARD_H, CARD_BG);
      drawBorderBox(g, x, y, x + CARD_W, y + CARD_H, CARD_BORDER);

      // Icon + species name.
      ItemStack icon = iconForSpecies(row.speciesId);
      g.renderItem(icon, x + 8, y + 6);
      g.drawString(this.font, shortName(row.speciesId),
         x + 30, y + 10, FG_PRIMARY, true);

      // Census line.
      String census = "now: " + row.currentCount
                    + (row.currentBabies > 0 ? " (" + row.currentBabies + " baby"
                       + (row.currentBabies == 1 ? "" : "ies") + ")" : "");
      g.drawString(this.font, census, x + 8, y + 30, FG_DIM, true);

      // "Target" label sits above the EditBox; the EditBox itself
      // renders via super.render() at the position fixed in init().
      g.drawString(this.font, "Target",
         x + (CARD_W - this.font.width("Target")) / 2,
         y + 50, FG_FAINT, true);

      // Mode chip at the bottom.
      int chipX = x + 10;
      int chipY = y + CARD_H - FOOT_CHIP_H - 8;
      int chipW = CARD_W - 20;
      boolean hovered = mouseX >= chipX && mouseX < chipX + chipW
                     && mouseY >= chipY && mouseY < chipY + FOOT_CHIP_H;
      boolean active = row.mode == AnimalPlan.Mode.BREED_UP;
      int bg = active ? CHIP_BG_ACTIVE : (hovered ? CHIP_BG_HOVER : CHIP_BG);
      g.fill(chipX, chipY, chipX + chipW, chipY + FOOT_CHIP_H, bg);
      drawBorderBox(g, chipX, chipY, chipX + chipW, chipY + FOOT_CHIP_H, CHIP_BORDER);
      String label = modeLabel(row.mode);
      int lw = this.font.width(label);
      g.drawString(this.font, label,
         chipX + (chipW - lw) / 2, chipY + (FOOT_CHIP_H - 8) / 2,
         active ? FG_ON_GREEN : FG_PRIMARY, true);

      // Status hint above the chip.
      String stateHint = row.mode == AnimalPlan.Mode.BREED_UP
         ? (row.currentCount >= row.target ? "at target"
            : (row.target - row.currentCount) + " to go")
         : "no breeding";
      int hintW = this.font.width(stateHint);
      g.drawString(this.font, stateHint,
         x + (CARD_W - hintW) / 2, chipY - 12,
         FG_DIM, true);
   }

   private void renderFootChip(GuiGraphics g, int x, int y, String label, boolean primary,
                                int mouseX, int mouseY) {
      boolean hovered = mouseX >= x && mouseX < x + FOOT_CHIP_W
                     && mouseY >= y && mouseY < y + FOOT_CHIP_H;
      int bg = primary
         ? (hovered ? 0xFF4A6C2A : CHIP_BG_ACTIVE)
         : (hovered ? CHIP_BG_HOVER : CHIP_BG);
      g.fill(x, y, x + FOOT_CHIP_W, y + FOOT_CHIP_H, bg);
      drawBorderBox(g, x, y, x + FOOT_CHIP_W, y + FOOT_CHIP_H, CHIP_BORDER);
      int lw = this.font.width(label);
      g.drawString(this.font, label,
         x + (FOOT_CHIP_W - lw) / 2, y + (FOOT_CHIP_H - 8) / 2,
         primary ? FG_ON_GREEN : FG_PRIMARY, true);
   }

   private static void drawBorderBox(GuiGraphics g, int l, int t, int r, int b, int color) {
      g.fill(l, t, r, t + 1, color);
      g.fill(l, b - 1, r, b, color);
      g.fill(l, t, l + 1, b, color);
      g.fill(r - 1, t, r, b, color);
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      // Foot row: Cancel / Save chips. These are custom-drawn so the
      // hit-test lives here rather than as Button widgets.
      int t = this.panelTop, b = t + PANEL_H;
      int r = this.panelLeft + PANEL_W;
      int footY = t + PANEL_H - FOOT_CHIP_H - 8;
      int saveX = r - 14 - FOOT_CHIP_W;
      int cancelX = saveX - 8 - FOOT_CHIP_W;
      if (mouseY >= footY && mouseY < footY + FOOT_CHIP_H) {
         if (mouseX >= cancelX && mouseX < cancelX + FOOT_CHIP_W) {
            this.onClose();
            return true;
         }
         if (mouseX >= saveX && mouseX < saveX + FOOT_CHIP_W) {
            commitAndClose();
            return true;
         }
      }

      // Per-card mode chip.
      int gridOriginX = this.panelLeft + 16;
      int gridOriginY = this.panelTop + 60;
      int cols = (PANEL_W - 32 + CARD_GAP) / (CARD_W + CARD_GAP);
      for (int i = 0; i < this.rows.size(); i++) {
         int col = i % cols;
         int rowIdx = i / cols;
         int cardX = gridOriginX + col * (CARD_W + CARD_GAP);
         int cardY = gridOriginY + rowIdx * (CARD_H + CARD_GAP);
         int chipX = cardX + 10;
         int chipY = cardY + CARD_H - FOOT_CHIP_H - 8;
         int chipW = CARD_W - 20;
         if (mouseX >= chipX && mouseX < chipX + chipW
             && mouseY >= chipY && mouseY < chipY + FOOT_CHIP_H) {
            Row r0 = this.rows.get(i);
            r0.mode = (r0.mode == AnimalPlan.Mode.BREED_UP)
               ? AnimalPlan.Mode.HOLD
               : AnimalPlan.Mode.BREED_UP;
            return true;
         }
      }

      return super.mouseClicked(mouseX, mouseY, button);
   }

   /** Best-effort icon for a species. Tries the matching spawn-egg
    *  item; falls back to paper if the egg isn't registered. */
   private static ItemStack iconForSpecies(String speciesId) {
      ResourceLocation rl = ResourceLocation.tryParse(speciesId);
      if (rl == null) return new ItemStack(Items.PAPER);
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

   @Override
   public boolean isPauseScreen() { return false; }

   /**
    * Escape: treat as Cancel (close without saving). Without this
    * override, Mojang's base Screen.keyPressed routes Escape to
    * {@code Minecraft.setScreen(null)} which closes this screen AND
    * any underlying admin screen.
    */
   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      // GLFW.GLFW_KEY_ESCAPE = 256. Don't intercept if a text field
      // is focused — let it handle escape (vanilla unfocuses).
      if (keyCode == 256 && this.getFocused() == null) {
         this.onClose();
         return true;
      }
      return super.keyPressed(keyCode, scanCode, modifiers);
   }
}
