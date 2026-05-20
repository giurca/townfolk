package com.yucareux.townfolk.world;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.AffinityRecord;
import com.yucareux.townfolk.town.TownLog;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stage 22b — affinity aggregation. Hooks called from
 * {@link com.yucareux.townfolk.world.leisure.TavernBanter} after each
 * exchange increment per-pair scores, log tier transitions, and persist
 * via {@link TownSquareBlockEntity#setChanged} so the saved-data round
 * trips cleanly.
 *
 * <p>The tone parameter is currently passed as
 * {@link AffinityRecord.Tone#NEUTRAL} from the only call site
 * (no LLM tone tagging shipped yet — that's the planned 22b extension
 * to the banter prompt). Once {@code TavernBanter} adds {@code a_tone}/
 * {@code b_tone} to its JSON output, the parser will forward those
 * through and warm/cold/hostile deltas will start mattering.
 */
public final class RelationshipService {

   private RelationshipService() {}

   /** Resolve the town for a villager by UUID — used by the banter
    *  caller to find the right {@code TownData} affinity map. Returns
    *  null if the villager isn't in any loaded town. */
   public static TownSquareBlockEntity townFor(ServerLevel level, UUID villagerUuid) {
      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (var e : town.getTown().villagers()) {
            if (e.uuid().equals(villagerUuid)) return town;
         }
      }
      return null;
   }

   /** Increment affinity for the (a, b) pair after a tavern banter
    *  exchange. Tone drives the score delta; banterCount + lastDay
    *  update unconditionally.
    *
    *  <p>If the new tier differs from the old, writes a log line +
    *  per-villager memory entry so the LLM has narrative continuity
    *  ("Mira and Beatrix have become close"). */
   public static void onBanter(ServerLevel level, UUID a, UUID b,
                                AffinityRecord.Tone tone) {
      TownSquareBlockEntity town = townFor(level, a);
      if (town == null) town = townFor(level, b);
      if (town == null) return;       // pair isn't in any tracked town

      var data = town.getTown();
      AffinityRecord rec = data.affinityFor(a, b);
      AffinityRecord.Tier oldTier = rec.tier();
      long day = level.getGameTime() / 24000L;
      rec.recordBanter(tone, day);
      data.putAffinity(rec);
      town.setChanged();

      AffinityRecord.Tier newTier = rec.tier();
      VerboseLog.write("AFFINITY_TICK",
         "town=" + town.getBlockPos().toShortString()
            + " pair=" + AffinityRecord.key(a, b)
            + " tone=" + tone.name()
            + " score=" + rec.score() + " count=" + rec.banterCount()
            + " tier=" + newTier.name()
            + (oldTier != newTier ? " (was " + oldTier.name() + ")" : ""),
         "");
      if (oldTier != newTier) {
         String aName = nameOf(data, a);
         String bName = nameOf(data, b);
         String transition = switch (newTier) {
            case ACQUAINTANCE -> aName + " and " + bName + " seem to know each other now.";
            case FRIEND       -> aName + " and " + bName + " have become friends.";
            case CLOSE        -> aName + " and " + bName + " have grown close.";
            case RIVAL        -> "Things turn frosty between " + aName + " and " + bName + ".";
            case STRANGER     -> "";       // shouldn't happen post-banter
         };
         if (!transition.isEmpty()) {
            data.log().add(level.getGameTime(), TownLog.Level.DIALOGUE, transition);
            // Mirror as a memory on both sides so the LLM has it for
            // future dialogue beats.
            var aEnt = level.getEntity(a);
            var bEnt = level.getEntity(b);
            if (aEnt instanceof net.minecraft.world.entity.npc.Villager av) {
               com.yucareux.townfolk.villager.MemoryStore.write(av,
                  "relationship", day,
                  "I've grown " + newTier.displayLabel() + " with " + bName + ".");
            }
            if (bEnt instanceof net.minecraft.world.entity.npc.Villager bv) {
               com.yucareux.townfolk.villager.MemoryStore.write(bv,
                  "relationship", day,
                  "I've grown " + newTier.displayLabel() + " with " + aName + ".");
            }
         }
      }
   }

   private static String nameOf(com.yucareux.townfolk.town.TownData data, UUID uuid) {
      for (var e : data.villagers()) {
         if (e.uuid().equals(uuid)) return e.name();
      }
      return uuid.toString().substring(0, 8);
   }
}
