package com.yucareux.townfolk.event;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.dialogue.DialogueService;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Intercepts right-click on entities that carry the LlmVillagerComponent and
 * routes the interaction into our dialogue service instead of letting the
 * vanilla villager trade UI open. Untouched villagers (those not registered
 * with a town) behave normally.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class InteractHandler {

   @SubscribeEvent
   public static void onInteractWithEntity(PlayerInteractEvent.EntityInteract event) {
      Entity target = event.getTarget();
      LlmVillagerComponent component = target.getData(ModRegistries.LLM_VILLAGER.get());
      if (component == LlmVillagerComponent.EMPTY || component.townSquarePos() == 0L) {
         return;
      }
      if (event.getLevel().isClientSide()) {
         // Client side cancellation suppresses the trade UI; server side actually opens our dialogue.
         event.setCanceled(true);
         event.setCancellationResult(InteractionResult.SUCCESS);
         return;
      }
      if (!(event.getEntity() instanceof ServerPlayer server)) {
         return;
      }

      ItemStack held = server.getItemInHand(event.getHand());

      // Surveyor's Stake routing — claims priority over dialogue / admin /
      // gift paths so the player's intent with the stake is unambiguous.
      if (held.getItem() == com.yucareux.townfolk.registry.ModRegistries.SURVEYOR_STAKE.get()
          && target instanceof Villager v
          && event.getLevel() instanceof ServerLevel sl) {
         if (server.isShiftKeyDown()) {
            com.yucareux.townfolk.world.PendingFieldBinding.unbindParcelAt(
               server, v, server.blockPosition());
         } else {
            com.yucareux.townfolk.world.PendingFieldBinding.beginBinding(server, sl, v);
         }
         event.setCanceled(true);
         event.setCancellationResult(InteractionResult.CONSUME);
         return;
      }

      // Sneak + EMPTY hand → open the admin panel focused on this villager.
      // Saves running back to the Town Square block whenever the player wants
      // to inspect/edit one specific resident.
      if (server.isShiftKeyDown() && held.isEmpty()
          && target instanceof Villager v
          && event.getLevel() instanceof ServerLevel sl) {
         var comp = v.getData(ModRegistries.LLM_VILLAGER.get());
         if (comp.townSquarePos() != 0L) {
            var be = sl.getBlockEntity(net.minecraft.core.BlockPos.of(comp.townSquarePos()));
            if (be instanceof com.yucareux.townfolk.blockentity.TownSquareBlockEntity town) {
               com.yucareux.townfolk.dialogue.TownAdminService.openAdminPanelFocused(
                  server, sl, town, v.getUUID());
               event.setCanceled(true);
               event.setCancellationResult(InteractionResult.CONSUME);
               return;
            }
         }
      }

      // Sneak + held item → gift the stack into the villager's vanilla 8-slot
      // inventory instead of opening dialogue. The villager remembers the
      // gift as an episodic memory so the LLM can refer to it later.
      if (server.isShiftKeyDown() && !held.isEmpty()
          && target instanceof Villager v
          && event.getLevel() instanceof ServerLevel sl) {
         int givenCount = giftToVillager(v, held);
         if (givenCount > 0) {
            ItemStack remaining = held.copy();
            remaining.shrink(givenCount);
            server.setItemInHand(event.getHand(), remaining);
            long day = sl.getGameTime() / 24000L;
            String itemName = held.getHoverName().getString();
            MemoryStore.write(v, "gift_received", day,
               server.getName().getString() + " gave me " + givenCount + "× " + itemName + ".");
            server.displayClientMessage(
               Component.literal("Gave " + givenCount + "× " + itemName
                  + " to " + (v.hasCustomName() ? v.getCustomName().getString() : "the villager")),
               true);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            return;
         }
         // No room → fall through to dialogue.
      }

      DialogueService.openConversation(server, target);
      event.setCanceled(true);
      event.setCancellationResult(InteractionResult.CONSUME);
   }

   /** Try to fit as much of {@code held} into the villager's vanilla 8-slot
    *  inventory as possible. Returns the count actually accepted. */
   /** Sneak + empty-hand right-click on a Barrel / Chest / Shulker
    *  opens the {@link com.yucareux.townfolk.world.inventory.StorageConfigMenu}
    *  popup so the player can configure the filter (whitelist / blacklist
    *  + up to 16 item types). Cancels the vanilla "open chest UI"
    *  interaction so the two flows don't fight.
    *
    *  Empty hand requirement: a held item could be a placeable block, and
    *  we don't want to register a chest by accident while trying to stack
    *  cobblestone onto it. Sneak narrows it further to "deliberate
    *  configuration action".
    */
   @SubscribeEvent
   public static void onInteractWithBlock(PlayerInteractEvent.RightClickBlock event) {
      if (event.getLevel().isClientSide()) return;
      if (!(event.getEntity() instanceof ServerPlayer player)) return;
      if (!player.isShiftKeyDown()) return;
      ItemStack held = event.getItemStack();
      if (!held.isEmpty()) return;
      if (!(event.getLevel() instanceof ServerLevel level)) return;

      var be = level.getBlockEntity(event.getPos());
      if (!com.yucareux.townfolk.town.StorageIndex.isStorage(be)) return;

      net.minecraft.core.BlockPos pos = event.getPos();

      // SCOPE GATE: Townfolk UI only fires inside a town. A chest in
      // the wilderness behaves as vanilla. We don't want the player's
      // off-grid private storage to be hijacked by our popup just
      // because they sneak-clicked it with an empty hand.
      if (!isInsideAnyTown(level, pos)) {
         com.yucareux.townfolk.diag.VerboseLog.write("STORAGE_OUT_OF_TOWN",
            "player=" + player.getName().getString()
               + " pos=" + pos.toShortString()
               + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                  .getKey(level.getBlockState(pos).getBlock()).getPath(),
            "no town claims this position — falling through to vanilla");
         return;
      }

      com.yucareux.townfolk.diag.VerboseLog.write("STORAGE_OPEN_CONFIG",
         "player=" + player.getName().getString()
            + " pos=" + pos.toShortString()
            + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
               .getKey(level.getBlockState(pos).getBlock()).getPath(), "");

      // Send the existing label (if any) through the menu-open buf so
      // the client-side EditBox can pre-fill with the current name.
      var existingCfg = com.yucareux.townfolk.town.StorageRegistry.find(level, pos);
      final String existingLabel = existingCfg == null ? "" : existingCfg.label();
      player.openMenu(
         new net.minecraft.world.SimpleMenuProvider(
            (id, inv, p) -> new com.yucareux.townfolk.world.inventory.StorageConfigMenu(id, inv, pos),
            Component.literal("Configure storage")),
         buf -> { buf.writeBlockPos(pos); buf.writeUtf(existingLabel, 64); });

      event.setCanceled(true);
      event.setCancellationResult(InteractionResult.CONSUME);
   }

   /** True if {@code pos} sits within {@link com.yucareux.townfolk.town.TownData#defaultRadius()}
    *  of any loaded town square in this level. The radius is interpreted
    *  on the XZ plane only — Y is unconstrained so cellars and roof
    *  storage still count. Used as the master scope gate for ALL
    *  Townfolk UI: no town → vanilla behaviour, no exceptions. */
   private static boolean isInsideAnyTown(ServerLevel level, net.minecraft.core.BlockPos pos) {
      for (var town : com.yucareux.townfolk.blockentity.TownSquareBlockEntity.loadedIn(level)) {
         net.minecraft.core.BlockPos sq = town.getBlockPos();
         long dx = (long) pos.getX() - sq.getX();
         long dz = (long) pos.getZ() - sq.getZ();
         long distSq = dx * dx + dz * dz;
         long r = town.getTown().defaultRadius();
         if (distSq <= r * r) return true;
      }
      return false;
   }

   private static int giftToVillager(Villager v, ItemStack held) {
      var inv = v.getInventory();
      ItemStack copy = held.copy();
      int before = copy.getCount();
      ItemStack leftover = inv.addItem(copy);
      int leftoverCount = leftover == null ? 0 : leftover.getCount();
      return before - leftoverCount;
   }

   private InteractHandler() {
   }
}
