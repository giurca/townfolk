package com.yucareux.townfolk.world;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.VillagerEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Stage 1: live world-state description injected into every LLM prompt so
 * the villager actually knows what's around them. Pure read-only — no Brain
 * mutations, no side effects.
 *
 * The returned multi-line block lists: time-of-day phase, weather, biome,
 * distance/heading relative to the town square, item in hand, nearby
 * villagers, nearby players, current activity hint (idle/walking/sleeping).
 */
public final class WorldSense {

   private static final double NEARBY_RADIUS = 12.0;

   public static String describe(Entity entity, Level level, BlockPos townSquare, String selfName) {
      if (!(entity instanceof Villager v)) return "(no body data)";
      StringBuilder sb = new StringBuilder(512);

      // Time of day (uses dayTime for narrative phase only, not for compaction).
      long t = level.getDayTime() % 24000L;
      String phase;
      if (t < 1000) phase = "dawn";
      else if (t < 5000) phase = "morning";
      else if (t < 7000) phase = "midday";
      else if (t < 11000) phase = "afternoon";
      else if (t < 13000) phase = "dusk";
      else if (t < 18000) phase = "night";
      else phase = "late night";
      sb.append("Time of day: ").append(phase).append('\n');

      // Weather.
      String weather = level.isThundering() ? "thunderstorm"
                     : level.isRaining() ? "raining"
                     : "clear";
      sb.append("Weather: ").append(weather).append('\n');

      // Biome.
      String biome = level.getBiome(v.blockPosition())
         .unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
      sb.append("Biome: ").append(biome).append('\n');

      // Position relative to town square.
      BlockPos pos = v.blockPosition();
      int dx = pos.getX() - townSquare.getX();
      int dz = pos.getZ() - townSquare.getZ();
      int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
      String heading = headingFromDelta(dx, dz);
      sb.append("Location: ").append(dist).append(" blocks ").append(heading)
        .append(" of the town square\n");

      // Town boundary awareness — phrased from coverage, so once
      // auxiliary blocks (Trade Post etc.) extend the town, the LLM
      // gets a correct "inside / outside" summary without any special
      // casing. We still report the master-block radius as the
      // user-facing scalar; "inside or outside" comes from the full
      // coverage check.
      if (level instanceof net.minecraft.server.level.ServerLevel sl) {
         for (var be : com.yucareux.townfolk.blockentity.TownSquareBlockEntity.loadedIn(sl)) {
            if (be.getBlockPos().equals(townSquare)) {
               com.yucareux.townfolk.town.TownCoverage coverage = be.coverage();
               int rad = be.getTown().defaultRadius();
               boolean inside = coverage.contains(pos);
               String inOut;
               if (!inside) inOut = "OUTSIDE the town boundary";
               else if (dist > rad * 0.8) inOut = "near the edge of town";
               else inOut = "well inside the town";
               sb.append("Town boundary: ").append(rad).append(" blocks radius from the square. You are ")
                 .append(inOut).append(". You do not stray past the boundary unless escorted by the player.\n");
               break;
            }
         }
      }

      // Follow / activity. If actively following someone, that takes precedence in the
      // activity description so the LLM knows it's mid-commitment.
      var follow = FollowService.peek(v.getUUID());
      if (follow.isPresent()) {
         net.minecraft.world.entity.Entity target = level instanceof net.minecraft.server.level.ServerLevel sl
            ? sl.getEntity(follow.get().targetUuid()) : null;
         String tname = target == null ? "someone"
            : (target.hasCustomName() ? target.getCustomName().getString() : target.getName().getString());
         long remaining = Math.max(0, follow.get().expireGameTime() - level.getGameTime());
         int secs = (int) (remaining / 20L);
         double gap = target == null ? -1 : Math.sqrt(v.distanceToSqr(target));
         sb.append("Current activity: following ").append(tname)
           .append(" (").append(secs).append("s remaining");
         if (gap >= 0) sb.append(", ").append((int) Math.round(gap)).append(" blocks away");
         sb.append(")\n");
      } else {
         // Prefer the schedule's intent — gives the LLM "I'm heading home for
         // the night" rather than just "walking somewhere".
         String schedActivity = ScheduleService.activityOf(v.getUUID());
         String activity;
         if (v.isSleeping()) activity = "sleeping in my bed";
         else switch (schedActivity) {
            case "going_to_work" -> activity = "walking to my workstation";
            case "at_work"       -> activity = "at my workstation, working";
            case "going_home"    -> activity = "heading home";
            case "at_home"       -> activity = "at home, settling in";
            case "sleeping"      -> activity = "trying to get to bed";
            case "waking"        -> activity = "just waking up";
            default -> {
               if (v.getNavigation().isInProgress()) activity = "walking somewhere";
               else if (v.getDeltaMovement().horizontalDistanceSqr() > 0.01) activity = "moving";
               else activity = "standing idle";
            }
         }
         sb.append("Current activity: ").append(activity).append('\n');
      }

      // Current vanilla profession (may differ from the role they were spawned with).
      var profKey = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
         .getKey(v.getVillagerData().getProfession());
      String profPath = profKey == null ? "unknown" : profKey.getPath();
      sb.append("Current profession (live, vanilla): ").append(profPath).append('\n');

      // Holding.
      var held = v.getMainHandItem();
      if (!held.isEmpty()) {
         sb.append("Holding: ").append(held.getCount()).append("× ")
           .append(held.getHoverName().getString()).append('\n');
      }

      // Inventory (vanilla Villager has an 8-slot SimpleContainer). Stack-merge
      // by item so the LLM sees totals, not per-slot accounting.
      var inv = v.getInventory();
      java.util.LinkedHashMap<String, Integer> stacked = new java.util.LinkedHashMap<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (s.isEmpty()) continue;
         String name = s.getHoverName().getString();
         stacked.merge(name, s.getCount(), Integer::sum);
      }
      if (stacked.isEmpty()) {
         sb.append("Inventory: empty\n");
      } else {
         StringBuilder inv1 = new StringBuilder("Inventory: ");
         boolean first = true;
         for (var en : stacked.entrySet()) {
            if (!first) inv1.append(", ");
            inv1.append(en.getValue()).append("× ").append(en.getKey());
            first = false;
         }
         sb.append(inv1).append('\n');
      }

      // Nearby interactables — things the LLM can act on with the block-task
      // verbs. Cheap scan of an 8-block radius; keeps the prompt concrete.
      if (level instanceof net.minecraft.server.level.ServerLevel sl) {
         int ripeCrops = 0, emptyFarmland = 0, logsCount = 0;
         java.util.Set<String> cropKinds = new java.util.LinkedHashSet<>();
         net.minecraft.core.BlockPos.MutableBlockPos cur = new net.minecraft.core.BlockPos.MutableBlockPos();
         net.minecraft.core.BlockPos cp = v.blockPosition();
         int rr = 8;
         for (int ddx = -rr; ddx <= rr; ddx++) {
            for (int ddy = -2; ddy <= 2; ddy++) {
               for (int ddz = -rr; ddz <= rr; ddz++) {
                  cur.set(cp.getX() + ddx, cp.getY() + ddy, cp.getZ() + ddz);
                  var st = sl.getBlockState(cur);
                  var bl = st.getBlock();
                  if (bl instanceof net.minecraft.world.level.block.CropBlock crop) {
                     if (crop.isMaxAge(st)) {
                        ripeCrops++;
                        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bl);
                        if (id != null) cropKinds.add(id.getPath().replace("s", ""));   // wheats → wheat
                     }
                  } else if (st.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                          && sl.getBlockState(cur.above()).isAir()) {
                     emptyFarmland++;
                  } else if (st.is(net.minecraft.tags.BlockTags.LOGS)) {
                     logsCount++;
                  }
               }
            }
         }
         if (ripeCrops + emptyFarmland + logsCount > 0) {
            StringBuilder line = new StringBuilder("Nearby resources (within 8 blocks):");
            if (ripeCrops > 0) line.append(" ").append(ripeCrops).append(" ripe crop")
               .append(ripeCrops == 1 ? "" : "s")
               .append(cropKinds.isEmpty() ? "" : " (" + String.join("/", cropKinds) + ")");
            if (emptyFarmland > 0) line.append(", ").append(emptyFarmland).append(" empty farmland plot")
               .append(emptyFarmland == 1 ? "" : "s");
            if (logsCount > 0) line.append(", ").append(logsCount).append(" log")
               .append(logsCount == 1 ? "" : "s");
            sb.append(line).append(".\n");
         }
      }

      // Town-wide treasury — the LOGICAL pool of items across every known
      // container. The LLM uses this to reason about town-level supply
      // ("the baker can take wheat from any barrel without my help").
      if (level instanceof net.minecraft.server.level.ServerLevel slT) {
         String treasury = com.yucareux.townfolk.town.TownTreasury.summary(slT, 6);
         if (!treasury.isEmpty()) {
            sb.append("Town storage (shared, across all known barrels): ")
              .append(treasury).append('\n');
         }
      }

      // Nearby storage ledger (specific containers villagers have touched —
      // useful for routing decisions, complements the treasury summary above).
      String storage = level instanceof net.minecraft.server.level.ServerLevel sl3b
         ? com.yucareux.townfolk.town.StorageRegistry.summaryNear(sl3b, townSquare, 32, 6)
         : "";
      if (!storage.isEmpty()) {
         sb.append("Nearby storage:\n").append(storage);
      }

      // Reputation — emerges from the last week's work-memory tally.
      // Surfaces "a farmer who also keeps sheep" style identity that the
      // LLM can weave into dialogue.
      {
         var compForRep = v.getData(com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
         long day = level.getGameTime() / 24000L;
         var tally = com.yucareux.townfolk.villager.VillagerIdentity.tally(compForRep, day);
         String rep = com.yucareux.townfolk.villager.VillagerIdentity.reputation(tally);
         if (!rep.isEmpty()) sb.append("Reputation: known as ").append(rep).append(".\n");
      }

      // Your parcels — the land you own and tend.
      var comp = v.getData(com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      if (level instanceof net.minecraft.server.level.ServerLevel sl3 && !comp.parcels().isEmpty()) {
         sb.append("Your land:\n");
         for (var parcel : comp.parcels()) {
            int ripe = 0, emptyTilled = 0, animals = 0, untilledGround = 0;
            net.minecraft.core.BlockPos.MutableBlockPos pcur = new net.minecraft.core.BlockPos.MutableBlockPos();
            var mn = parcel.minCorner();
            var mx = parcel.maxCorner();
            for (int x = mn.getX(); x <= mx.getX(); x++)
            for (int y = mn.getY(); y <= mx.getY(); y++)
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               pcur.set(x, y, z);
               var st = sl3.getBlockState(pcur);
               if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
                  if (crop.isMaxAge(st)) ripe++;
               } else if (st.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                       && sl3.getBlockState(pcur.above()).isAir()) {
                  emptyTilled++;
               } else if ((st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                        || st.is(net.minecraft.world.level.block.Blocks.DIRT))
                       && sl3.getBlockState(pcur.above()).isAir()) {
                  untilledGround++;
               }
            }
            // Count animals inside the AABB — broken down by species so
            // a shepherd can see "3× sheep (2 unshorn), 2× cow, 1× pig"
            // and the LLM can chat about specifics rather than a flat
            // "n animals". Babies tagged in parens so breeding state is
            // visible to the prompt.
            var aabb = new net.minecraft.world.phys.AABB(
               mn.getX(), mn.getY(), mn.getZ(),
               mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
            java.util.Map<String, int[]> bySpecies = new java.util.LinkedHashMap<>();
            int unshornSheep = 0;
            for (var e : sl3.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, aabb,
                  ent -> ent.isAlive())) {
               animals++;
               String species = e.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
               int[] counts = bySpecies.computeIfAbsent(species, k -> new int[]{0, 0});
               counts[0]++;                              // total
               if (e.isBaby()) counts[1]++;              // babies
               if (e instanceof net.minecraft.world.entity.animal.Sheep sh
                   && !sh.isSheared() && !sh.isBaby()) unshornSheep++;
            }
            sb.append("  • ").append(parcel.shortLabel(townSquare)).append(":");
            boolean first = true;
            if (ripe > 0)            { sb.append(first ? " " : ", ").append(ripe).append(" ripe crops"); first = false; }
            if (emptyTilled > 0)     { sb.append(first ? " " : ", ").append(emptyTilled).append(" empty farmland"); first = false; }
            if (untilledGround > 0)  { sb.append(first ? " " : ", ").append(untilledGround).append(" tillable tiles"); first = false; }
            if (!bySpecies.isEmpty()) {
               StringBuilder sp = new StringBuilder();
               boolean firstSp = true;
               for (var entry : bySpecies.entrySet()) {
                  if (!firstSp) sp.append(", ");
                  int totalC = entry.getValue()[0];
                  int babyC  = entry.getValue()[1];
                  sp.append(totalC).append("× ").append(entry.getKey());
                  if (babyC > 0) sp.append(" (").append(babyC).append(" baby)");
                  firstSp = false;
               }
               if (unshornSheep > 0) sp.append("; ").append(unshornSheep).append(" unshorn");
               sb.append(first ? " " : ", ").append(sp);
               first = false;
            }
            if (first) sb.append(" empty (nothing actionable yet)");
            sb.append(".\n");
         }
      }

      // Town-wide awareness — pulled from TownRegistry view layer.
      if (level instanceof net.minecraft.server.level.ServerLevel sl2) {
         for (var be : com.yucareux.townfolk.blockentity.TownSquareBlockEntity.loadedIn(sl2)) {
            if (be.getBlockPos().equals(townSquare)) {
               String townSummary = com.yucareux.townfolk.town.TownRegistry.summaryFor(sl2, be, v.getUUID());
               if (!townSummary.isEmpty()) {
                  sb.append("Town at a glance:\n").append(townSummary);
               }
               break;
            }
         }
      }

      // Nearby living entities (villagers + players).
      AABB box = v.getBoundingBox().inflate(NEARBY_RADIUS);
      List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
         e -> e != v && !e.isDeadOrDying());
      if (!nearby.isEmpty()) {
         List<String> villagerNames = new ArrayList<>();
         List<String> playerNames = new ArrayList<>();
         List<String> mobs = new ArrayList<>();
         for (LivingEntity ent : nearby) {
            if (ent instanceof Villager other) {
               String n = other.hasCustomName() ? other.getCustomName().getString() : "a villager";
               villagerNames.add(n);
            } else if (ent instanceof Player p) {
               playerNames.add(p.getName().getString());
            } else {
               mobs.add(ent.getType().getDescription().getString());
            }
         }
         if (!villagerNames.isEmpty())
            sb.append("Nearby villagers: ").append(String.join(", ", villagerNames)).append('\n');
         if (!playerNames.isEmpty())
            sb.append("Nearby players: ").append(String.join(", ", playerNames)).append('\n');
         if (!mobs.isEmpty())
            sb.append("Nearby creatures: ").append(String.join(", ", mobs)).append('\n');
      }

      String result = sb.toString();
      VerboseLog.write("WORLD_SENSE", "villager=" + selfName + " phase=" + phase + " weather=" + weather, result);
      return result;
   }

   private static String headingFromDelta(int dx, int dz) {
      if (dx == 0 && dz == 0) return "at";
      double angle = Math.toDegrees(Math.atan2(-dz, dx));
      if (angle < 0) angle += 360;
      String[] dirs = { "east", "northeast", "north", "northwest", "west", "southwest", "south", "southeast" };
      int idx = (int) Math.round(angle / 45.0) % 8;
      return dirs[idx];
   }

   // Convenience overload for callers that have a VillagerEntry rather than a name.
   public static String describe(Entity entity, Level level, BlockPos townSquare, VillagerEntry self) {
      return describe(entity, level, townSquare, self.name());
   }

   private WorldSense() {}
}
