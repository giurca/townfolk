package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Stage 2 expansion: poll every loaded villager's {@link Brain} state on a
 * 2-second tick, diff against the previous snapshot, and emit narrative
 * episodic events for every transition. This is how the LLM learns its
 * body is panicking, working, has spotted a zombie, heard the bell, etc.
 *
 *   Polled state (per villager):
 *     - Active non-core Activity (IDLE/WORK/MEET/REST/PANIC/RAID/HIDE/PRE_RAID)
 *     - Profession (registry key; transitions when a job site is claimed/lost)
 *     - Held item resource id
 *     - Breeding flag (BREED_TARGET memory present)
 *     - Bell-just-heard flag (HEARD_BELL_TIME memory present)
 *     - Hostile-spotted flag (NEAREST_HOSTILE memory present)
 *     - Golem-spotted flag (GOLEM_DETECTED_RECENTLY memory present)
 *     - Active raid flag (RAID memory present)
 *
 * Each diff emits a single short past-tense first-person sentence appended
 * to the villager's episodic log. Per-villager throttling prevents flapping.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class VillagerBrainListener {

   private static final int POLL_TICKS = 40;            // 2 s
   private static final int HEARTBEAT_TICKS = 200;       // 10 s
   private static final long EMIT_DEBOUNCE_TICKS = 100;  // 5 s per (villager, key)

   private record Snapshot(
      String activity,
      String profession,
      String held,
      boolean breeding,
      boolean bell,
      boolean hostile,
      boolean golem,
      boolean raid
   ) {}

   private static final Map<UUID, Snapshot> PREV = new ConcurrentHashMap<>();
   private static final Map<String, Long> LAST_EMIT = new ConcurrentHashMap<>();

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % POLL_TICKS != 0L) return;

      // Iterate via TownData roster — much cheaper than world-wide AABB and
      // friendly to mods like Sable that abort large entity-region queries.
      for (com.yucareux.townfolk.blockentity.TownSquareBlockEntity town :
            com.yucareux.townfolk.blockentity.TownSquareBlockEntity.loadedIn(level)) {
         for (com.yucareux.townfolk.town.VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.townSquarePos() == 0L) continue;

            Snapshot now = snapshot(v);
            Snapshot prev = PREV.put(v.getUUID(), now);
            if (prev == null) {
               heartbeat(level, v, now, "initial");
               continue;
            }
            diff(level, v, comp, prev, now);

            if (level.getGameTime() % HEARTBEAT_TICKS == 0L) {
               heartbeat(level, v, now, "tick");
            }
         }
      }
   }

   private static void heartbeat(ServerLevel level, Villager v, Snapshot s, String reason) {
      String who = v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString().substring(0, 8);
      VerboseLog.write("BRAIN_HEARTBEAT",
         "villager=" + who + " reason=" + reason
            + " activity=" + s.activity()
            + " profession=" + s.profession()
            + " held=" + (s.held().isEmpty() ? "-" : s.held())
            + " bell=" + s.bell() + " hostile=" + s.hostile()
            + " golem=" + s.golem() + " breeding=" + s.breeding(),
         "");
   }

   // ----- Snapshot construction -----

   private static Snapshot snapshot(Villager v) {
      Brain<Villager> brain = v.getBrain();
      String activity = brain.getActiveNonCoreActivity().map(Activity::getName).orElse("idle");
      String profession = professionId(v);
      ItemStack heldStack = v.getMainHandItem();
      String held = heldStack.isEmpty() ? ""
         : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(heldStack.getItem()).toString();
      boolean breeding = brain.hasMemoryValue(MemoryModuleType.BREED_TARGET);
      boolean bell = brain.hasMemoryValue(MemoryModuleType.HEARD_BELL_TIME);
      boolean hostile = brain.hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE);
      boolean golem = brain.hasMemoryValue(MemoryModuleType.GOLEM_DETECTED_RECENTLY);
      boolean raid = brain.hasMemoryValue(MemoryModuleType.HIDING_PLACE);
      return new Snapshot(activity, profession, held, breeding, bell, hostile, golem, raid);
   }

   private static String professionId(Villager v) {
      VillagerProfession p = v.getVillagerData().getProfession();
      var key = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(p);
      // Returns the unqualified path of the registry key — "fisherman", "farmer", "none", etc.
      return key == null ? "unknown" : key.getPath();
   }

   // ----- Diff -> event emission -----

   private static void diff(ServerLevel level, Villager v, LlmVillagerComponent comp,
                            Snapshot prev, Snapshot now) {
      if (!prev.activity().equals(now.activity())) {
         emit(level, v, comp, "activity:" + now.activity(), activityNarrative(prev.activity(), now.activity()));
      }
      if (!prev.profession().equals(now.profession())) {
         emit(level, v, comp, "profession:" + now.profession(),
            "none".equals(now.profession())
               ? "I gave up my profession."
               : "I took up the profession of " + humanise(now.profession()) + ".");
      }
      if (!prev.held().equals(now.held())) {
         if (prev.held().isEmpty() && !now.held().isEmpty()) {
            emit(level, v, comp, "held:" + now.held(),
               "I picked up some " + humaniseItem(now.held()) + ".");
         } else if (!prev.held().isEmpty() && now.held().isEmpty()) {
            emit(level, v, comp, "held:none",
               "I put away the " + humaniseItem(prev.held()) + " I was holding.");
         } else {
            emit(level, v, comp, "held:" + now.held(),
               "I started holding " + humaniseItem(now.held()) + ".");
         }
      }
      if (prev.breeding() != now.breeding() && now.breeding()) {
         emit(level, v, comp, "breeding", "I felt drawn to start a family.");
      }
      if (prev.bell() != now.bell() && now.bell()) {
         emit(level, v, comp, "bell", "The town bell rang.");
      }
      if (prev.hostile() != now.hostile()) {
         emit(level, v, comp, "hostile:" + now.hostile(),
            now.hostile() ? "Something hostile is nearby — I'm on edge."
                          : "The danger has passed; I'm calmer now.");
      }
      if (prev.golem() != now.golem() && now.golem()) {
         emit(level, v, comp, "golem", "An iron golem is keeping watch nearby.");
      }
      if (prev.raid() != now.raid()) {
         emit(level, v, comp, "raid:" + now.raid(),
            now.raid() ? "A raid is upon the town!" : "The raid is over.");
      }
   }

   private static String activityNarrative(String from, String to) {
      return switch (to.toLowerCase()) {
         case "work" -> "I went off to my work.";
         case "meet" -> "I headed toward the town meeting spot.";
         case "rest" -> "I'm winding down for the day.";
         case "panic" -> "I broke into a panic.";
         case "raid" -> "I'm caught up in the raid.";
         case "pre_raid" -> "I sense a raid coming — I'm uneasy.";
         case "hide" -> "I'm hiding from danger.";
         case "idle" -> "I finished what I was doing and have some idle time now.";
         case "play" -> "I'm at play with the other children.";
         default -> "My day's rhythm shifted (" + from + " → " + to + ").";
      };
   }

   private static String humanise(String s) {
      String stripped = s.contains(":") ? s.substring(s.indexOf(':') + 1) : s;
      return stripped.replace('_', ' ');
   }

   private static String humaniseItem(String id) {
      int colon = id.indexOf(':');
      String name = colon < 0 ? id : id.substring(colon + 1);
      return name.replace('_', ' ');
   }

   // ----- Emission with debounce -----

   private static void emit(ServerLevel level, Villager v, LlmVillagerComponent comp,
                            String dedupKey, String narrative) {
      String key = v.getUUID() + "|" + dedupKey;
      long now = level.getGameTime();
      Long lastTick = LAST_EMIT.put(key, now);
      if (lastTick != null && now - lastTick < EMIT_DEBOUNCE_TICKS) return;

      long day = now / 24000L;
      com.yucareux.townfolk.villager.MemoryStore.write(v, "brain", day, narrative);
      String who = v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString().substring(0, 8);
      VerboseLog.write("BRAIN_EVENT", "villager=" + who + " key=" + dedupKey, narrative);
      BlockPos pos = BlockPos.of(comp.townSquarePos());
      if (level.getBlockEntity(pos) instanceof TownSquareBlockEntity town) {
         town.getTown().log().add(now, TownLog.Level.INFO, who + ": " + narrative);
      }
   }

   private VillagerBrainListener() {}
}
