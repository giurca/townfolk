package com.yucareux.townfolk.town;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only view layer over a town's living villagers. Holds zero state of
 * its own — every method iterates the canonical {@link TownData#villagers()}
 * list and projects whatever the caller asked for.
 *
 * Why this exists: with 50 villagers, direct iteration is free, but the
 * codebase is starting to grow ad-hoc one-off loops in WorldSense, the admin
 * screen, ScheduleService, etc. that all want the same kinds of summaries
 * ("who has wheat?", "how much food does the town have total?", "is anyone
 * hungry?"). Concentrating those queries here:
 *
 *   - avoids subtle bugs from slightly-different loop bodies in each call site
 *   - gives the LLM a single coherent "town awareness" prompt block
 *   - is the natural place to plug in caching later if profiling demands it
 *
 * Critically this is NOT a source of truth. The per-villager state lives on
 * the entity (inventory, health, position). If a method here ever holds a
 * cached copy, it must be invalidated on every read — easier to just
 * recompute, which we do.
 */
public final class TownRegistry {

   public record VillagerSnapshot(
      VillagerEntry entry,
      Villager entity,
      BlockPos pos,
      float health,
      float maxHealth,
      Map<ResourceLocation, Integer> inventory,
      String activity,
      LlmVillagerComponent component) {}

   /** Iterate every alive villager of a given town and produce a snapshot.
    *  Entities not currently loaded are skipped silently — caller decides
    *  whether to treat that as missing or treat the {@link VillagerEntry}
    *  alone as enough. */
   public static List<VillagerSnapshot> snapshots(ServerLevel level, TownSquareBlockEntity town) {
      List<VillagerSnapshot> out = new ArrayList<>();
      for (VillagerEntry entry : town.getTown().villagers()) {
         if (!entry.alive()) continue;
         if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
         LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
         Map<ResourceLocation, Integer> inv = mergedInventory(v);
         String act = com.yucareux.townfolk.world.ScheduleService.activityOf(v.getUUID());
         out.add(new VillagerSnapshot(entry, v, v.blockPosition(),
            v.getHealth(), v.getMaxHealth(), inv, act, comp));
      }
      return out;
   }

   /** Town-wide totals by item id, summed across every villager's inventory. */
   public static Map<ResourceLocation, Integer> totalInventory(ServerLevel level, TownSquareBlockEntity town) {
      Map<ResourceLocation, Integer> totals = new LinkedHashMap<>();
      for (VillagerSnapshot s : snapshots(level, town)) {
         s.inventory.forEach((id, n) -> totals.merge(id, n, Integer::sum));
      }
      return totals;
   }

   /** Villager who currently holds the most of a given item, if any. */
   public static Optional<VillagerSnapshot> villagerWithMost(ServerLevel level, TownSquareBlockEntity town,
                                                              ResourceLocation item) {
      VillagerSnapshot best = null;
      int bestN = 0;
      for (VillagerSnapshot s : snapshots(level, town)) {
         int n = s.inventory.getOrDefault(item, 0);
         if (n > bestN) { bestN = n; best = s; }
      }
      return Optional.ofNullable(best);
   }

   /** All villagers carrying at least one of the given item. */
   public static List<VillagerSnapshot> villagersWith(ServerLevel level, TownSquareBlockEntity town,
                                                       ResourceLocation item) {
      List<VillagerSnapshot> out = new ArrayList<>();
      for (VillagerSnapshot s : snapshots(level, town)) {
         if (s.inventory.getOrDefault(item, 0) > 0) out.add(s);
      }
      return out;
   }

   public static int aliveCount(TownSquareBlockEntity town) {
      return town.getTown().aliveVillagerCount();
   }

   /** Average health as a 0–1 fraction, or 1.0 when nobody's loaded. */
   public static double averageHealthFrac(ServerLevel level, TownSquareBlockEntity town) {
      double sum = 0; int n = 0;
      for (VillagerSnapshot s : snapshots(level, town)) {
         sum += s.health / s.maxHealth;
         n++;
      }
      return n == 0 ? 1.0 : sum / n;
   }

   /** Count of villagers reporting a given schedule activity. */
   public static int countByActivity(ServerLevel level, TownSquareBlockEntity town, String activity) {
      int n = 0;
      for (VillagerSnapshot s : snapshots(level, town)) {
         if (activity.equals(s.activity)) n++;
      }
      return n;
   }

   /** Distance-bounded set: villagers within {@code radius} blocks of {@code centre}. */
   public static List<VillagerSnapshot> within(ServerLevel level, TownSquareBlockEntity town,
                                                BlockPos centre, int radius) {
      List<VillagerSnapshot> out = new ArrayList<>();
      long r2 = (long) radius * radius;
      for (VillagerSnapshot s : snapshots(level, town)) {
         long dx = s.pos.getX() - centre.getX();
         long dz = s.pos.getZ() - centre.getZ();
         if (dx * dx + dz * dz <= r2) out.add(s);
      }
      return out;
   }

   /** Find a snapshot by UUID. */
   public static Optional<VillagerSnapshot> findByUuid(ServerLevel level, TownSquareBlockEntity town, UUID uuid) {
      for (VillagerSnapshot s : snapshots(level, town))
         if (s.entry.uuid().equals(uuid)) return Optional.of(s);
      return Optional.empty();
   }

   /** Render a one-paragraph prompt-friendly town-awareness summary, intended
    *  to be injected into the asking villager's WorldSense block. Concise on
    *  purpose — too much detail crowds the context window. */
   public static String summaryFor(ServerLevel level, TownSquareBlockEntity town, UUID askerUuid) {
      List<VillagerSnapshot> all = snapshots(level, town);
      if (all.isEmpty()) return "";
      int alive = all.size();
      int atWork = 0, atHome = 0, sleeping = 0;
      for (VillagerSnapshot s : all) {
         com.yucareux.townfolk.world.Activity a =
            com.yucareux.townfolk.world.Activity.fromWire(s.activity);
         if (a.isWorking())       atWork++;
         else if (a.isAtHome())   atHome++;
         else if (a.isSleeping()) sleeping++;
      }
      // Top-3 most-abundant items in town total.
      Map<ResourceLocation, Integer> totals = totalInventory(level, town);
      List<Map.Entry<ResourceLocation, Integer>> top = new ArrayList<>(totals.entrySet());
      top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
      StringBuilder topItems = new StringBuilder();
      for (int i = 0; i < Math.min(3, top.size()); i++) {
         if (i > 0) topItems.append(", ");
         topItems.append(top.get(i).getValue()).append("× ").append(top.get(i).getKey().getPath());
      }
      if (top.isEmpty()) topItems.append("nothing notable");

      StringBuilder sb = new StringBuilder();
      sb.append("Townmates: ").append(alive).append(" alive");
      if (atWork > 0)   sb.append(" (").append(atWork).append(" working");
      if (atHome > 0)   sb.append(atWork > 0 ? ", " : " (").append(atHome).append(" home");
      if (sleeping > 0) sb.append((atWork > 0 || atHome > 0) ? ", " : " (").append(sleeping).append(" sleeping");
      if (atWork > 0 || atHome > 0 || sleeping > 0) sb.append(")");
      sb.append(".\n");
      sb.append("Town stockpile (across all residents): ").append(topItems).append(".\n");
      return sb.toString();
   }

   private static Map<ResourceLocation, Integer> mergedInventory(Villager v) {
      Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
      var inv = v.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) continue;
         ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
         out.merge(id, s.getCount(), Integer::sum);
      }
      return out;
   }

   private TownRegistry() {}
}
