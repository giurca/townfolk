package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.building.PopulationCap;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.AgeCategory;
import com.yucareux.townfolk.villager.Gender;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Stage 18e — villager breeding + birth lifecycle.
 *
 * <h2>Pair-bond detection</h2>
 * Every game day at dawn (single tick per day, gated by the existing
 * {@link ScheduleService} cadence), this service scans each town for
 * pair-bond candidates: two villagers
 * <ul>
 *   <li>both {@link AgeCategory#ADULT}
 *   <li>opposite {@link Gender}
 *   <li>both {@code playerSetHome == true}
 *   <li>whose HOME bed positions are within {@link #PAIR_BOND_RADIUS}
 *       blocks of each other (i.e. sharing a home structure)
 * </ul>
 * If their pair-key (the UUID-ordered string-pair) is new, it's
 * registered in {@link #PAIRS} with the current game day as
 * {@code formedDay}. After {@link #GESTATION_DAYS} consecutive game
 * days the pair triggers a birth — a fresh villager at age 0
 * spawned at the female parent's HOME bed.
 *
 * <h2>Population cap</h2>
 * Births refuse to fire (and defer) when the town's living population
 * would exceed {@link PopulationCap#effectiveCap}. The defer surfaces
 * as a soft memory line on both parents so the LLM can roleplay the
 * waiting period. The pair-bond is preserved across deferrals — once
 * a new home opens up the birth fires on the next dawn.
 *
 * <h2>No save format</h2>
 * Pair state is transient ({@link ConcurrentHashMap}) — restarts reset
 * gestation. Acceptable per the "test worlds disposable" stance;
 * future hardening can persist {@link PairRecord} to TownData.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class BirthService {

   /** Blocks between two HOME beds for the villagers to be considered
    *  "sharing a home." Roughly the diameter of a small building. */
   public static final int PAIR_BOND_RADIUS = 8;

   /** In-game days between pair-bond formation and birth. Set deliberately
    *  long (a full game week) so breeding isn't spammy. */
   public static final int GESTATION_DAYS = 7;

   /** Transient pair-state, keyed by ordered UUID pair string. Cleared
    *  on server restart — test worlds disposable. */
   private static final Map<String, PairRecord> PAIRS = new ConcurrentHashMap<>();

   /** Records the formation + last-seen day for a candidate pair. The
    *  {@code lastSeenDay} resets the bond if the pair stops meeting
    *  the criteria for a day (e.g. one moves out, dies, breaks home). */
   public record PairRecord(UUID a, UUID b, long formedDay, long lastSeenDay) {
      static String key(UUID x, UUID y) {
         return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
      }
   }

   private BirthService() {}

   /** Last game day we ran the scan, per level dimension key. Prevents
    *  the dawn-phase poll window (multiple ticks at dawn) from running
    *  the scan more than once per day. */
   private static final Map<String, Long> LAST_SCAN_DAY = new ConcurrentHashMap<>();

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      // Run at most once per game day, at dawn.
      long today = level.getGameTime() / 24000L;
      String dimKey = level.dimension().location().toString();
      Long last = LAST_SCAN_DAY.get(dimKey);
      long dayTime = level.getDayTime() % 24000L;
      if (dayTime >= 1000) return;            // only dawn phase
      if (last != null && last == today) return;
      LAST_SCAN_DAY.put(dimKey, today);

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         scanTown(level, town, today);
      }
   }

   private static void scanTown(ServerLevel level, TownSquareBlockEntity town, long today) {
      var villagers = town.getTown().villagers();
      // Build candidate index: alive adult villagers with playerSetHome,
      // tagged with their HOME bed pos + gender for the inner pairing loop.
      Map<UUID, Candidate> byId = new HashMap<>();
      for (VillagerEntry e : villagers) {
         if (!e.alive()) continue;
         var ent = level.getEntity(e.uuid());
         if (!(ent instanceof Villager v)) continue;
         var comp = v.getData(ModRegistries.LLM_VILLAGER.get());
         if (!comp.playerSetHome()) continue;
         if (comp.ageCategory() != AgeCategory.ADULT) continue;
         var homeMem = v.getBrain().getMemory(
            net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
         if (homeMem.isEmpty()) continue;
         byId.put(e.uuid(), new Candidate(e, v, comp, homeMem.get().pos()));
      }
      if (byId.size() < 2) return;

      // Pair every female with every nearby male. Track newly-discovered
      // pairs vs. re-confirmed pairs.
      var list = new java.util.ArrayList<>(byId.values());
      java.util.Set<String> seenToday = new java.util.HashSet<>();
      for (int i = 0; i < list.size(); i++) {
         Candidate a = list.get(i);
         for (int j = i + 1; j < list.size(); j++) {
            Candidate b = list.get(j);
            if (a.comp.gender() == b.comp.gender()) continue;
            if (a.homePos.distSqr(b.homePos) > PAIR_BOND_RADIUS * PAIR_BOND_RADIUS) continue;
            String key = PairRecord.key(a.entry.uuid(), b.entry.uuid());
            seenToday.add(key);
            PairRecord prev = PAIRS.get(key);
            if (prev == null) {
               PAIRS.put(key, new PairRecord(a.entry.uuid(), b.entry.uuid(), today, today));
               VerboseLog.write("BIRTH_PAIR_FORMED",
                  "town=" + town.getBlockPos().toShortString()
                     + " a=" + a.entry.name() + " b=" + b.entry.name()
                     + " day=" + today, "");
               town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
                  a.entry.name() + " and " + b.entry.name() + " have settled in together.");
            } else {
               PAIRS.put(key, new PairRecord(prev.a, prev.b, prev.formedDay, today));
               long gestated = today - prev.formedDay;
               if (gestated >= GESTATION_DAYS) {
                  if (tryBirth(level, town, a, b, today)) {
                     // Reset the pair so they don't immediately re-birth
                     // tomorrow — clears to today as the new formedDay.
                     PAIRS.put(key, new PairRecord(prev.a, prev.b, today, today));
                  }
               }
            }
         }
      }

      // Drop pairs that didn't reconfirm today — they're broken (moved
      // out, died, etc). Keeps the map bounded by current active pairs.
      PAIRS.entrySet().removeIf(e -> !seenToday.contains(e.getKey())
         && e.getValue().lastSeenDay < today - 1);
   }

   /** Per-pair tracker for the defer log so we only warn once per
    *  defer streak, not every dawn. */
   private static final Map<String, Long> LAST_DEFER_LOG_DAY = new ConcurrentHashMap<>();

   private static boolean tryBirth(ServerLevel level, TownSquareBlockEntity town,
                                    Candidate a, Candidate b, long today) {
      int cap = PopulationCap.effectiveCap(level);
      // Stage 18 audit P0 fix: count alive villagers ACROSS the whole
      // level (every town), not just this town. PopulationCap is
      // level-wide so the comparison must be too — otherwise a 2-town
      // world with one home-rich town and one home-poor town would
      // birth into the poor town indefinitely.
      long alive = 0;
      for (TownSquareBlockEntity t : TownSquareBlockEntity.loadedIn(level)) {
         for (var e : t.getTown().villagers()) if (e.alive()) alive++;
      }
      if (alive >= cap) {
         String pairKey = PairRecord.key(a.entry.uuid(), b.entry.uuid());
         Long lastLogged = LAST_DEFER_LOG_DAY.get(pairKey);
         // Audit P1 fix: only log the "no room" line once per defer
         // streak. The check writes the streak's first-day, then
         // suppresses re-logs until the pair stops deferring (which
         // clears the entry on a successful birth path).
         if (lastLogged == null || lastLogged != today - 1) {
            VerboseLog.write("BIRTH_DEFERRED",
               "town=" + town.getBlockPos().toShortString()
                  + " parents=" + a.entry.name() + "+" + b.entry.name()
                  + " alive=" + alive + " cap=" + cap, "");
            town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
               a.entry.name() + " and " + b.entry.name()
                  + " want to start a family, but there's no room — build another home.");
         }
         LAST_DEFER_LOG_DAY.put(pairKey, today);
         return false;
      }
      // Clear any defer-log throttle now that the cap check passed —
      // future re-defers (after this birth) will log a fresh streak.
      LAST_DEFER_LOG_DAY.remove(PairRecord.key(a.entry.uuid(), b.entry.uuid()));

      // Find the female parent's HOME for the spawn position.
      Candidate mother = a.comp.gender() == Gender.FEMALE ? a : b;
      Candidate father = mother == a ? b : a;
      // Audit P1 fix: homePos is the FOOT of the bed — scan upward
      // for the first non-solid tile so the child doesn't suffocate
      // in a ceiling. Walk up 4 blocks max; if no air, fall back to
      // homePos.above() and accept whatever happens (test world).
      BlockPos spawnPos = findSafeSpawn(level, mother.homePos);

      Villager child;
      try {
         child = ModRegistries.LLM_TOWNSFOLK.get().create(level);
      } catch (Throwable t) {
         VerboseLog.write("BIRTH_FAIL", "stage=create", "exception: " + t);
         return false;
      }
      if (child == null) return false;

      String childName = pickChildName(mother.entry, father.entry, level, town);
      child.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0F, 0F);
      child.setCustomName(Component.literal(childName));
      child.setCustomNameVisible(true);
      if (!level.addFreshEntity(child)) {
         VerboseLog.write("BIRTH_FAIL", "stage=addFreshEntity name=" + childName, "");
         return false;
      }

      // Persona seed inherits a hint from both parents — keeps the LLM
      // consistent ("daughter of X and Y, raised in this town"). The
      // backstory itself is generated lazily by PersonaGenerator the
      // first time the child is interacted with.
      String seed = "Born in " + town.getTown().townName()
         + " to " + mother.entry.name() + " and " + father.entry.name() + ".";
      Gender childGender = Gender.fromUuid(child.getUUID());
      LlmVillagerComponent.Anchors anchors = LlmVillagerComponent.Anchors.NONE
         .withGender(childGender)
         .withAgeDays(0);
      LlmVillagerComponent comp = new LlmVillagerComponent(
         seed, "", child.getUUID().toString(), town.getBlockPos().asLong(),
         java.util.List.of(), 0, 0L, 0L,
         java.util.List.of(), "", java.util.List.of(), today,
         java.util.List.of(), anchors, java.util.List.of(), java.util.List.of());
      child.setData(ModRegistries.LLM_VILLAGER.get(), comp);

      VillagerEntry childEntry = new VillagerEntry(
         child.getUUID(), childName, "resident", seed, spawnPos);
      town.getTown().addVillager(childEntry);
      town.setChanged();

      String msg = mother.entry.name() + " and " + father.entry.name()
         + " welcomed a child: " + childName
         + " (" + childGender.glyph() + ").";
      VerboseLog.write("BIRTH_OK",
         "child=" + childName + " uuid=" + child.getUUID()
            + " mother=" + mother.entry.name() + " father=" + father.entry.name()
            + " gender=" + childGender.wireKey(), "");
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, msg);

      // Record a parent-side memory so the LLM has narrative continuity.
      com.yucareux.townfolk.villager.MemoryStore.write(mother.villager, "family", today,
         "I gave birth to a child named " + childName + " with " + father.entry.name() + ".");
      com.yucareux.townfolk.villager.MemoryStore.write(father.villager, "family", today,
         "My partner " + mother.entry.name() + " gave birth to our child, " + childName + ".");
      return true;
   }

   private static final String[] CHILD_NAME_POOL = {
      "Eira", "Maren", "Tova", "Liesel", "Sigrid", "Anya", "Brenna", "Cara",
      "Bren", "Cael", "Doran", "Erik", "Finn", "Garet", "Holt", "Ivar"
   };

   /** Pick a child name that doesn't collide with any living
    *  townsfolk in this town. Falls back to a numeric suffix if the
    *  entire pool is exhausted (rare). */
   private static String pickChildName(VillagerEntry mother, VillagerEntry father,
                                        ServerLevel level, TownSquareBlockEntity town) {
      java.util.Set<String> taken = new java.util.HashSet<>();
      for (var e : town.getTown().villagers()) {
         if (e.alive()) taken.add(e.name().toLowerCase(Locale.ROOT));
      }
      int seed = (int) Math.floorMod(level.getGameTime() + mother.uuid().getLeastSignificantBits(),
                                      CHILD_NAME_POOL.length);
      for (int i = 0; i < CHILD_NAME_POOL.length; i++) {
         String candidate = CHILD_NAME_POOL[(seed + i) % CHILD_NAME_POOL.length];
         if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
      }
      // Exhausted — append a counter.
      int suffix = 2;
      while (suffix < 99) {
         String candidate = CHILD_NAME_POOL[0] + " " + suffix;
         if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
         suffix++;
      }
      return CHILD_NAME_POOL[0] + " (lost count)";
   }

   /** Walk up to 4 blocks above {@code homePos} looking for the first
    *  air block. Falls back to {@code homePos.above()} unconditionally
    *  if everything's solid. */
   private static BlockPos findSafeSpawn(ServerLevel level, BlockPos homePos) {
      for (int dy = 1; dy <= 4; dy++) {
         BlockPos candidate = homePos.above(dy);
         if (level.getBlockState(candidate).isAir()) return candidate;
      }
      return homePos.above();
   }

   /** Bundle holding the per-villager state used during the pair scan. */
   private record Candidate(VillagerEntry entry, Villager villager,
                             LlmVillagerComponent comp, BlockPos homePos) {}
}
