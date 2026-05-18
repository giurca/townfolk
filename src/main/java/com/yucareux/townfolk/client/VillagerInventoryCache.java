package com.yucareux.townfolk.client;

import com.yucareux.townfolk.network.RequestVillagerInventoryPayload;
import com.yucareux.townfolk.network.VillagerInventoryPayload;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side store for hover-inventory snapshots. Two responsibilities:
 *   1. When the player crosshairs a villager, ask the server for that
 *      villager's bag (rate-limited).
 *   2. Hold the most recent snapshot so the HUD layer can render it
 *      without re-requesting every frame.
 *
 * Snapshots are keyed by UUID and expire after a few seconds of no
 * refresh so we don't show stale data forever if the player walks away
 * and the server reply never came back.
 */
public final class VillagerInventoryCache {

   private record Snapshot(List<ItemStack> slots, long deliveredAt) {}

   private static final ConcurrentHashMap<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

   /** How often we re-request the same villager while still hovered. 10
    *  ticks = 0.5 s, snappy enough that the panel reflects deposits /
    *  pickups quickly. */
   private static final long REFRESH_INTERVAL_TICKS = 10;

   /** Snapshots older than this are dropped on read. 60 ticks = 3 s. */
   private static final long STALE_TICKS = 60;

   private static UUID lastRequested = null;
   private static long lastRequestTick = Long.MIN_VALUE;

   /** Called by the network handler when a payload arrives. */
   public static void deliver(VillagerInventoryPayload payload) {
      long now = clientGameTime();
      SNAPSHOTS.put(payload.villager(), new Snapshot(payload.slots(), now));
   }

   /** Ask the server for {@code uuid}'s inventory if we haven't asked
    *  recently. Safe to call every render frame — internal rate-limit
    *  takes care of the actual send cadence. */
   public static void requestIfDue(UUID uuid) {
      long now = clientGameTime();
      if (uuid.equals(lastRequested) && (now - lastRequestTick) < REFRESH_INTERVAL_TICKS) return;
      lastRequested = uuid;
      lastRequestTick = now;
      PacketDistributor.sendToServer(new RequestVillagerInventoryPayload(uuid));
   }

   /** Returns the most recent snapshot for the given villager, or null
    *  if we've never gotten one or the last one is too old. */
   public static List<ItemStack> peek(UUID uuid) {
      Snapshot s = SNAPSHOTS.get(uuid);
      if (s == null) return null;
      long now = clientGameTime();
      if (now - s.deliveredAt > STALE_TICKS) {
         SNAPSHOTS.remove(uuid);
         return null;
      }
      return s.slots;
   }

   private static long clientGameTime() {
      var lvl = Minecraft.getInstance().level;
      return lvl == null ? 0 : lvl.getGameTime();
   }

   private VillagerInventoryCache() {}
}
