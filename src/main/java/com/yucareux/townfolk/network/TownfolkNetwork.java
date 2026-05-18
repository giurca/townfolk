package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.client.ClientHooks;
import com.yucareux.townfolk.dialogue.DialogueService;
import com.yucareux.townfolk.dialogue.TownAdminService;
import com.yucareux.townfolk.dialogue.TownLogSubscribers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = Townfolk.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TownfolkNetwork {

   private static final String PROTOCOL_VERSION = "1";

   @SubscribeEvent
   public static void register(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

      // Server -> client
      registrar.playToClient(
         OpenDialoguePayload.TYPE,
         OpenDialoguePayload.STREAM_CODEC,
         TownfolkNetwork::onOpenDialogue
      );
      registrar.playToClient(
         VillagerReplyPayload.TYPE,
         VillagerReplyPayload.STREAM_CODEC,
         TownfolkNetwork::onVillagerReply
      );

      // Client -> server
      registrar.playToServer(
         PlayerSpeaksPayload.TYPE,
         PlayerSpeaksPayload.STREAM_CODEC,
         TownfolkNetwork::onPlayerSpeaks
      );
      registrar.playToServer(
         AdminActionPayload.TYPE,
         AdminActionPayload.STREAM_CODEC,
         TownfolkNetwork::onAdminAction
      );

      // Server -> client (admin state)
      registrar.playToClient(
         TownStateUpdatePayload.TYPE,
         TownStateUpdatePayload.STREAM_CODEC,
         TownfolkNetwork::onTownStateUpdate
      );
      registrar.playToClient(
         TownLogPushPayload.TYPE,
         TownLogPushPayload.STREAM_CODEC,
         TownfolkNetwork::onTownLogPush
      );
      registrar.playToClient(
         OpenVillagerDetailPayload.TYPE,
         OpenVillagerDetailPayload.STREAM_CODEC,
         TownfolkNetwork::onOpenVillagerDetail
      );
      registrar.playToServer(
         LogSubscribePayload.TYPE,
         LogSubscribePayload.STREAM_CODEC,
         TownfolkNetwork::onLogSubscribe
      );
      registrar.playToServer(
         AssignBlockPayload.TYPE,
         AssignBlockPayload.STREAM_CODEC,
         TownfolkNetwork::onAssignBlock
      );

      // Hover-inventory: client asks for a snapshot of a specific villager's
      // bag; server replies with a one-shot payload.
      registrar.playToServer(
         RequestVillagerInventoryPayload.TYPE,
         RequestVillagerInventoryPayload.STREAM_CODEC,
         TownfolkNetwork::onRequestVillagerInventory
      );
      registrar.playToClient(
         VillagerInventoryPayload.TYPE,
         VillagerInventoryPayload.STREAM_CODEC,
         TownfolkNetwork::onVillagerInventory
      );

      // Parcel-type popup: server tells client to open the picker after
      // corner B is captured; client tells server which type was chosen.
      registrar.playToClient(
         OpenParcelTypePopupPayload.TYPE,
         OpenParcelTypePopupPayload.STREAM_CODEC,
         TownfolkNetwork::onOpenParcelTypePopup
      );
      registrar.playToServer(
         SelectParcelTypePayload.TYPE,
         SelectParcelTypePayload.STREAM_CODEC,
         TownfolkNetwork::onSelectParcelType
      );

      // Recipe-viewer ghost drop: JEI / EMI plugins call this when the
      // player drags an item out of the sidebar onto a filter slot.
      registrar.playToServer(
         SetGhostSlotPayload.TYPE,
         SetGhostSlotPayload.STREAM_CODEC,
         TownfolkNetwork::onSetGhostSlot
      );

      // Optional barrel name — set from the StorageConfigScreen label
      // EditBox. Persists into the StorageConfig in the registry.
      registrar.playToServer(
         SetStorageLabelPayload.TYPE,
         SetStorageLabelPayload.STREAM_CODEC,
         TownfolkNetwork::onSetStorageLabel
      );

      // Per-item production targets — Resources tab → item popup sliders.
      registrar.playToServer(
         SetProductionTargetPayload.TYPE,
         SetProductionTargetPayload.STREAM_CODEC,
         TownfolkNetwork::onSetProductionTarget
      );
   }

   private static void onSetProductionTarget(SetProductionTargetPayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
         var level = sp.serverLevel();
         String itemId = payload.itemId();
         if (itemId == null || itemId.isBlank()) return;
         // Validate the town exists and the player is permitted to edit it.
         net.minecraft.core.BlockPos townPos = net.minecraft.core.BlockPos.of(payload.townSquarePos());
         if (!isAuthorisedTownAdmin(sp, level, townPos)) {
            com.yucareux.townfolk.diag.VerboseLog.write("PRODUCTION_TARGET_REJECT",
               "player=" + sp.getName().getString() + " townPos=" + townPos.toShortString()
                  + " reason=not_authorised", "");
            return;
         }
         if (!payload.enabled()) {
            com.yucareux.townfolk.town.ProductionTargets.forget(level, townPos, itemId);
            com.yucareux.townfolk.diag.VerboseLog.write("PRODUCTION_TARGET_PLAYER_DISABLE",
               "player=" + sp.getName().getString() + " town=" + townPos.toShortString()
                  + " item=" + itemId, "");
            return;
         }
         int min = Math.max(0, Math.min(9999, payload.min()));
         int max = Math.max(min, Math.min(9999, payload.max()));
         com.yucareux.townfolk.town.ProductionTargets.setBounds(level, townPos, itemId, min, max);
         com.yucareux.townfolk.diag.VerboseLog.write("PRODUCTION_TARGET_PLAYER_SET",
            "player=" + sp.getName().getString() + " town=" + townPos.toShortString()
               + " item=" + itemId + " min=" + min + " max=" + max, "");
      });
   }

   /** Common admin-permission gate for town-scoped C→S payloads.
    *  Either the player is an op (perm level 2+), OR the server is
    *  running in singleplayer (host is implicit admin), OR the player
    *  is within the town's working radius. This is intentionally
    *  lenient — multiplayer townmates can edit "their" town without
    *  asking the host, but a rando from a distant town can't. */
   static boolean isAuthorisedTownAdmin(net.minecraft.server.level.ServerPlayer sp,
                                         net.minecraft.server.level.ServerLevel level,
                                         net.minecraft.core.BlockPos townPos) {
      var be = level.getBlockEntity(townPos);
      if (!(be instanceof com.yucareux.townfolk.blockentity.TownSquareBlockEntity town)) return false;
      if (sp.hasPermissions(2)) return true;
      if (level.getServer() != null && level.getServer().isSingleplayer()) return true;
      // Proximity gate: within the town's working radius.
      int r = town.getTown().defaultRadius();
      double dx = sp.getX() - townPos.getX();
      double dz = sp.getZ() - townPos.getZ();
      return dx * dx + dz * dz <= (double) r * r;
   }

   private static void onSetStorageLabel(SetStorageLabelPayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
         var menu = sp.containerMenu;
         if (!(menu instanceof com.yucareux.townfolk.world.inventory.StorageConfigMenu m)) {
            com.yucareux.townfolk.diag.VerboseLog.write("STORAGE_LABEL_REJECT",
               "player=" + sp.getName().getString()
                  + " reason=wrong-menu menu=" + (menu == null ? "null" : menu.getClass().getSimpleName()),
               "");
            return;
         }
         var level = sp.serverLevel();
         var pos = m.containerPos();
         // Proximity gate: the menu's stillValid would catch a moved
         // player vanilla-style, but the menu doesn't yet implement
         // it. Belt + braces.
         if (!sp.hasPermissions(2)
             && !(level.getServer() != null && level.getServer().isSingleplayer())
             && sp.blockPosition().distSqr(pos) > 64) {
            com.yucareux.townfolk.diag.VerboseLog.write("STORAGE_LABEL_REJECT",
               "player=" + sp.getName().getString()
                  + " pos=" + pos.toShortString() + " reason=too_far", "");
            return;
         }
         var cfg = com.yucareux.townfolk.town.StorageRegistry.find(level, pos);
         // Clamp + null-coerce + strip control chars so the label can't
         // contain newlines that would wreck a row render.
         String clean = payload.label() == null ? ""
            : payload.label().replaceAll("[\\n\\r\\t]", " ").trim();
         if (clean.length() > com.yucareux.townfolk.network.SetStorageLabelPayload.MAX_LABEL_CHARS) {
            clean = clean.substring(0, com.yucareux.townfolk.network.SetStorageLabelPayload.MAX_LABEL_CHARS);
         }
         if (cfg == null) {
            // Container isn't registered (player closed the menu before
            // committing? edge case). Lazily seed a default config with
            // the label so the change isn't lost.
            java.util.List<net.minecraft.world.item.ItemStack> slots =
               new java.util.ArrayList<>(com.yucareux.townfolk.town.StorageConfig.FILTER_SLOTS);
            for (int i = 0; i < com.yucareux.townfolk.town.StorageConfig.FILTER_SLOTS; i++) {
               slots.add(net.minecraft.world.item.ItemStack.EMPTY);
            }
            cfg = new com.yucareux.townfolk.town.StorageConfig(
               com.yucareux.townfolk.town.StorageFilterMode.WHITELIST,
               slots, clean, sp.getUUID(), level.getGameTime());
         } else {
            cfg.setLabel(clean);
         }
         com.yucareux.townfolk.town.StorageRegistry.put(level, pos, cfg);
         com.yucareux.townfolk.diag.VerboseLog.write("STORAGE_LABEL_SET",
            "player=" + sp.getName().getString()
               + " pos=" + pos.toShortString() + " label=\"" + clean + "\"", "");
      });
   }

   private static void onSetGhostSlot(SetGhostSlotPayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
         var menu = sp.containerMenu;
         net.minecraft.world.SimpleContainer target = null;
         int regionSize = 0;
         if (menu instanceof com.yucareux.townfolk.world.inventory.StorageConfigMenu sm) {
            target = sm.filterContainer();
            regionSize = com.yucareux.townfolk.world.inventory.StorageConfigMenu.filterRegionSize();
         } else if (menu instanceof com.yucareux.townfolk.world.inventory.CropPlanMenu cm) {
            // Crop plan: refuse non-seed items at the network edge so
            // bad data from compromised clients can't slip past the
            // local menu's guard.
            if (!payload.stack().isEmpty()
                && !com.yucareux.townfolk.town.CropPlan.isPlantableSeed(payload.stack().getItem())) {
               com.yucareux.townfolk.diag.VerboseLog.write("GHOST_SLOT_REJECT",
                  "player=" + sp.getName().getString()
                     + " reason=not-a-seed item="
                     + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(payload.stack().getItem()),
                  "");
               return;
            }
            target = cm.ghostContainer();
            regionSize = com.yucareux.townfolk.world.inventory.CropPlanMenu.filterRegionSize();
         } else {
            com.yucareux.townfolk.diag.VerboseLog.write("GHOST_SLOT_REJECT",
               "player=" + sp.getName().getString()
                  + " reason=wrong-menu menu=" + (menu == null ? "null" : menu.getClass().getSimpleName()),
               "");
            return;
         }
         if (payload.slotIndex() < 0 || payload.slotIndex() >= regionSize) {
            com.yucareux.townfolk.diag.VerboseLog.write("GHOST_SLOT_REJECT",
               "player=" + sp.getName().getString()
                  + " reason=oob slotIndex=" + payload.slotIndex(), "");
            return;
         }
         net.minecraft.world.item.ItemStack ghost = payload.stack().isEmpty()
            ? net.minecraft.world.item.ItemStack.EMPTY
            : payload.stack().copyWithCount(1);
         target.setItem(payload.slotIndex(), ghost);
         menu.broadcastChanges();
         com.yucareux.townfolk.diag.VerboseLog.write("GHOST_SLOT_SET",
            "player=" + sp.getName().getString()
               + " menu=" + menu.getClass().getSimpleName()
               + " slot=" + payload.slotIndex()
               + " item=" + (ghost.isEmpty() ? "(cleared)"
                  : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ghost.getItem())),
            "");
      });
   }

   private static void onOpenParcelTypePopup(OpenParcelTypePopupPayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> com.yucareux.townfolk.client.ClientHooks.openParcelTypePopup(payload));
   }

   private static void onSelectParcelType(SelectParcelTypePayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
         if (SelectParcelTypePayload.CANCEL.equalsIgnoreCase(payload.chosen())) {
            com.yucareux.townfolk.world.PendingFieldBinding.cancelTypeSelection(sp);
         } else {
            com.yucareux.townfolk.world.PendingFieldBinding.selectType(sp,
               com.yucareux.townfolk.villager.FieldRegion.Type.fromString(payload.chosen()));
         }
      });
   }

   private static void onRequestVillagerInventory(RequestVillagerInventoryPayload payload,
                                                   IPayloadContext ctx) {
      ctx.enqueueWork(() -> com.yucareux.townfolk.world.VillagerInventoryService
         .handleRequest(ctx.player(), payload.villager()));
   }

   private static void onVillagerInventory(VillagerInventoryPayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> com.yucareux.townfolk.client.VillagerInventoryCache.deliver(payload));
   }

   private static void onAssignBlock(AssignBlockPayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> com.yucareux.townfolk.world.NeedsService.handleAssign(ctx.player(), payload));
   }

   private static void onTownLogPush(TownLogPushPayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> ClientHooks.deliverLogPush(payload));
   }

   private static void onOpenVillagerDetail(OpenVillagerDetailPayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> ClientHooks.openVillagerDetail(payload));
   }

   private static void onLogSubscribe(LogSubscribePayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> TownLogSubscribers.handle(ctx.player(), payload));
   }

   private static void onAdminAction(AdminActionPayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
         net.minecraft.server.level.ServerLevel level = sp.serverLevel();
         net.minecraft.core.BlockPos townPos = net.minecraft.core.BlockPos.of(payload.townSquarePos());
         if (!isAuthorisedTownAdmin(sp, level, townPos)) {
            com.yucareux.townfolk.diag.VerboseLog.write("ADMIN_ACTION_REJECT",
               "player=" + sp.getName().getString()
                  + " reason=unauthorised town=" + townPos.toShortString()
                  + " action=" + payload.action(), "");
            return;
         }
         TownAdminService.handle(sp, payload);
      });
   }

   private static void onTownStateUpdate(TownStateUpdatePayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> ClientHooks.deliverTownState(payload));
   }

   private static void onOpenDialogue(OpenDialoguePayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> ClientHooks.openDialogueScreen(payload));
   }

   private static void onVillagerReply(VillagerReplyPayload payload, IPayloadContext ctx) {
      if (FMLEnvironment.dist != Dist.CLIENT) return;
      ctx.enqueueWork(() -> ClientHooks.deliverVillagerReply(payload));
   }

   private static void onPlayerSpeaks(PlayerSpeaksPayload payload, IPayloadContext ctx) {
      ctx.enqueueWork(() -> DialogueService.handlePlayerSpeech(ctx.player(), payload));
   }

   private TownfolkNetwork() {
   }
}
