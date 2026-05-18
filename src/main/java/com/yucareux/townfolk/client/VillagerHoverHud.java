package com.yucareux.townfolk.client;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Floating panel that renders the hovered townsfolk's full inventory.
 * Triggered when the player's crosshair targets an {@link LlmTownsfolk};
 * disappears as soon as they look away.
 *
 * Layout (top-right corner):
 *   ┌─ Anna's bag ─────────┐
 *   │ [hoe] [seed×32]      │
 *   │ [wheat×14] [bread×2] │
 *   └──────────────────────┘
 *
 * Item textures + counts use the standard {@link GuiGraphics#renderItem}
 * + {@link GuiGraphics#renderItemDecorations} pair so they look exactly
 * like inventory icons elsewhere. Mainhand + offhand prefix the bag so
 * the player can see what's currently equipped.
 */
@EventBusSubscriber(modid = Townfolk.MODID, value = Dist.CLIENT)
public final class VillagerHoverHud {

   private static final ResourceLocation LAYER_ID =
      ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "villager_hover_hud");

   /** 16 px slot + 2 px gap. */
   private static final int SLOT = 16;
   private static final int GAP = 2;
   private static final int SLOTS_PER_ROW = 5;
   private static final int PADDING = 6;
   private static final int PANEL_BG = 0xD0140C07;
   private static final int FG_ACCENT = 0xFFFFD27A;

   @SubscribeEvent
   public static void onRegisterLayers(RegisterGuiLayersEvent event) {
      event.registerAboveAll(LAYER_ID, VillagerHoverHud::render);
   }

   private static void render(GuiGraphics graphics, DeltaTracker delta) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.level == null) return;
      if (mc.options.hideGui) return;
      if (mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) return;

      // Only respond to a CROSSHAIR hit on a townsfolk — vanilla's pick
      // result, which is what the player is "looking at" right now.
      var hit = mc.crosshairPickEntity;
      Entity hovered = null;
      if (hit instanceof LlmTownsfolk t) hovered = t;
      else if (mc.hitResult instanceof EntityHitResult ehr
               && ehr.getEntity() instanceof LlmTownsfolk t) hovered = t;
      if (hovered == null) return;

      UUID uuid = hovered.getUUID();
      VillagerInventoryCache.requestIfDue(uuid);
      List<ItemStack> slots = VillagerInventoryCache.peek(uuid);
      if (slots == null) return;                  // nothing to show yet — first response is coming

      // Strip totally empty slots so the panel doesn't have a sea of blanks.
      // But preserve mainhand (idx 0) and offhand (idx 1) regardless so the
      // player can SEE that the villager has no hand items.
      java.util.List<ItemStack> compact = new java.util.ArrayList<>();
      compact.add(slots.get(0));                   // mainhand (may be empty)
      compact.add(slots.get(1));                   // offhand  (may be empty)
      for (int i = 2; i < slots.size(); i++) {
         ItemStack s = slots.get(i);
         if (!s.isEmpty()) compact.add(s);
      }

      String displayName = ((Villager) hovered).hasCustomName()
         ? ((Villager) hovered).getCustomName().getString()
         : "Townsfolk";
      String header = displayName + "'s bag";

      int rows = (int) Math.ceil(compact.size() / (double) SLOTS_PER_ROW);
      int contentW = SLOTS_PER_ROW * SLOT + (SLOTS_PER_ROW - 1) * GAP;
      int headerW = mc.font.width(header);
      int panelW = Math.max(headerW, contentW) + PADDING * 2;
      int panelH = mc.font.lineHeight + 4 + rows * (SLOT + GAP) + PADDING;

      int screenW = mc.getWindow().getGuiScaledWidth();
      int x = screenW - panelW - 10;
      int y = 10;

      // Panel background + accent stripe (matches TownfolkHud style).
      graphics.fill(x, y, x + panelW, y + panelH, PANEL_BG);
      graphics.fill(x, y, x + panelW, y + 1, FG_ACCENT);
      graphics.drawString(mc.font,
         Component.literal(header).withStyle(ChatFormatting.GOLD),
         x + PADDING, y + 4, FG_ACCENT, true);

      int gridY = y + 4 + mc.font.lineHeight + 4;
      int slotX = x + PADDING;
      int col = 0, row = 0;
      // Differentiate held items with a thin highlight under the first two
      // slots (mainhand + offhand) so the player can tell what's equipped
      // vs. what's in the bag.
      for (int i = 0; i < compact.size(); i++) {
         int cx = slotX + col * (SLOT + GAP);
         int cy = gridY + row * (SLOT + GAP);
         if (i < 2) {
            graphics.fill(cx - 1, cy - 1, cx + SLOT + 1, cy + SLOT + 1, 0x40FFD27A);
         }
         ItemStack s = compact.get(i);
         if (!s.isEmpty()) {
            graphics.renderItem(s, cx, cy);
            graphics.renderItemDecorations(mc.font, s, cx, cy);
         }
         col++;
         if (col >= SLOTS_PER_ROW) { col = 0; row++; }
      }
   }

   private VillagerHoverHud() {}
}
