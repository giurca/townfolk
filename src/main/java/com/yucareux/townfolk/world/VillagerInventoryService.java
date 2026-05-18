package com.yucareux.townfolk.world;

import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.network.VillagerInventoryPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side handler for {@link com.yucareux.townfolk.network.RequestVillagerInventoryPayload}.
 * Builds an inventory snapshot from the targeted villager (mainhand,
 * offhand, then every SimpleContainer slot) and ships it back to the
 * requester only — other clients have no business seeing it.
 *
 * Lookup walks every loaded level the player can see; if the villager is
 * unloaded or not actually an LlmTownsfolk, we just don't reply. The
 * client's cache treats absent data as "no panel".
 */
public final class VillagerInventoryService {

   /** Permissive range check — the request itself is rate-limited by the
    *  client; we just refuse to leak inventories of villagers very far
    *  away. 64 blocks is the vanilla mob tracking range. */
   private static final double MAX_PEEK_RANGE_SQ = 64.0 * 64.0;

   /** Server-side floor for how often a single player can ask for a
    *  villager inventory snapshot. Client side already rate-limits to
    *  ~0.5 s per villager, but a malicious / desynced client could spam
    *  — this enforces a 5-tick minimum gap regardless. */
   private static final long MIN_REQUEST_GAP_TICKS = 5L;

   /** Last server-tick a given player made a request, by player UUID. */
   private static final java.util.Map<UUID, Long> LAST_REQUEST =
      new java.util.concurrent.ConcurrentHashMap<>();

   public static void handleRequest(net.minecraft.world.entity.player.Player rawPlayer, UUID target) {
      if (!(rawPlayer instanceof ServerPlayer player)) return;
      var server = player.getServer();
      if (server == null) return;

      long now = server.overworld().getGameTime();
      Long last = LAST_REQUEST.get(player.getUUID());
      if (last != null && (now - last) < MIN_REQUEST_GAP_TICKS) return;
      LAST_REQUEST.put(player.getUUID(), now);

      for (var level : server.getAllLevels()) {
         var ent = level.getEntity(target);
         if (!(ent instanceof Villager v)) continue;
         if (!(ent instanceof LlmTownsfolk)) continue;        // only our townsfolk
         if (v.distanceToSqr(player) > MAX_PEEK_RANGE_SQ) return;

         List<ItemStack> slots = new ArrayList<>();
         slots.add(v.getMainHandItem().copy());
         slots.add(v.getOffhandItem().copy());
         var inv = v.getInventory();
         for (int i = 0; i < inv.getContainerSize(); i++) {
            slots.add(inv.getItem(i).copy());
         }
         PacketDistributor.sendToPlayer(player, new VillagerInventoryPayload(target, slots));
         return;
      }
   }

   private VillagerInventoryService() {}
}
