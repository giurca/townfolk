package com.yucareux.townfolk.diag;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Dumps a one-shot full-world snapshot to {@link VerboseLog} the FIRST
 * server tick of a session — every loaded townsfolk, their parcels,
 * anchors, inventory, and the storage ledger. Lets us replay decisions
 * after the fact: "what was Ursula carrying when the routine ran?",
 * "did the ledger know about that barrel yet?".
 *
 * Fires per-level so different dimensions get their own snapshot. The
 * snapshot is gated on a "have I run yet for this level" flag so a
 * server-restart inside the same JVM dumps fresh state.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class WorldSnapshotLogger {

   private static final java.util.Set<net.minecraft.resources.ResourceKey<?>> SNAPSHOT_DONE =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

   @SubscribeEvent
   public static void onLevelTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (SNAPSHOT_DONE.contains(level.dimension())) return;
      // Wait until at least one town is loaded — otherwise the snapshot
      // is empty noise.
      var towns = TownSquareBlockEntity.loadedIn(level);
      if (towns.isEmpty()) return;
      SNAPSHOT_DONE.add(level.dimension());
      dump(level, towns);
   }

   private static void dump(ServerLevel level, java.util.Collection<TownSquareBlockEntity> towns) {
      StringBuilder sb = new StringBuilder();
      long gameTime = level.getGameTime();
      long dayTime = level.getDayTime() % 24000L;
      sb.append("dimension=").append(level.dimension().location()).append('\n');
      sb.append("gameTime=").append(gameTime).append(" dayTime=").append(dayTime).append('\n');
      sb.append("towns=").append(towns.size()).append('\n');
      for (TownSquareBlockEntity town : towns) {
         var data = town.getTown();
         sb.append("\n[TOWN ").append(data.townName()).append(" @ ")
           .append(town.getBlockPos().toShortString())
           .append(" radius=").append(data.defaultRadius())
           .append(" villagers=").append(data.villagers().size())
           .append("]\n");
         for (var entry : data.villagers()) {
            sb.append("  - ").append(entry.name())
              .append(" alive=").append(entry.alive())
              .append(" role=").append(entry.role());
            var ent = level.getEntity(entry.uuid());
            if (ent instanceof Villager v) {
               LlmVillagerComponent c = v.getData(ModRegistries.LLM_VILLAGER.get());
               sb.append(" pos=").append(v.blockPosition().toShortString())
                 .append(" parcels=").append(c.parcels().size())
                 .append(" home=").append(c.playerSetHome())
                 .append(" job=").append(c.playerSetJob())
                 .append(" inv=").append(invSummary(v));
               for (var p : c.parcels()) {
                  sb.append("\n      parcel ").append(p.id())
                    .append(" type=").append(p.type())
                    .append(" ").append(p.sizeX()).append("x").append(p.sizeZ())
                    .append(" at ").append(p.minCorner().toShortString())
                    .append("-").append(p.maxCorner().toShortString());
               }
            } else {
               sb.append(" [UNLOADED]");
            }
            sb.append('\n');
         }
      }
      int registrySize = com.yucareux.townfolk.town.StorageRegistry.entries(level).size();
      sb.append("\nstorage_registry=").append(registrySize).append(" entries\n");
      VerboseLog.write("WORLD_SNAPSHOT", "dim=" + level.dimension().location(), sb.toString());
   }

   private static String invSummary(Villager v) {
      var inv = v.getInventory();
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      int free = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) { free++; continue; }
         if (!first) sb.append(", ");
         first = false;
         var id = BuiltInRegistries.ITEM.getKey(s.getItem());
         sb.append(id == null ? "?" : id.getPath());
         if (s.getCount() > 1) sb.append("×").append(s.getCount());
      }
      sb.append("] free=").append(free).append("/").append(inv.getContainerSize());
      return sb.toString();
   }

   private WorldSnapshotLogger() {}
}
