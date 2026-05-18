package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Stage 2: mechanical world events automatically appended to villager
 * episodic logs. Zero LLM cost — these are dirt-cheap breadcrumbs that
 * give nightly compaction substantive context.
 *
 * Sources:
 *   - LivingDamageEvent on a villager → "I was hurt by X"
 *   - LivingDeathEvent within radius of a villager → "X died nearby"
 *   - Weather transition (cleared/started raining/thunder) → emitted to every
 *     loaded villager in that level once per transition
 *   - Time-of-day phase transitions (dawn/noon/dusk/midnight) → likewise
 *   - Sleep wake (detected via Villager.isSleeping → false transition tracked)
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class WorldEventListener {

   private static final double DEATH_NOTICE_RADIUS = 24.0;
   private static final double GIFT_RADIUS = 6.0;
   private static final int POLL_TICKS = 40;            // 2 s
   private static final Map<UUID, Boolean> WAS_SLEEPING = new ConcurrentHashMap<>();
   private static final Map<String, Boolean> WAS_RAINING_BY_DIM = new ConcurrentHashMap<>();
   private static final Map<String, Boolean> WAS_THUNDERING_BY_DIM = new ConcurrentHashMap<>();
   private static final Map<String, String> LAST_PHASE_BY_DIM = new ConcurrentHashMap<>();
   private static final Map<UUID, Map<net.minecraft.world.item.Item, Integer>> INVENTORY_SNAP = new ConcurrentHashMap<>();

   // ----- Damage / death -----

   @SubscribeEvent
   public static void onDamage(LivingDamageEvent.Post event) {
      if (!(event.getEntity() instanceof Villager v)) return;
      if (!(v.level() instanceof ServerLevel level)) return;
      LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
      if (comp.townSquarePos() == 0L) return;
      String attacker = describeAttacker(event.getSource());
      String text = "I was hurt by " + attacker + " (" + (int) Math.ceil(event.getNewDamage()) + " dmg)";
      appendEvent(level, v, comp, text);
   }

   @SubscribeEvent
   public static void onDeath(LivingDeathEvent event) {
      LivingEntity dying = event.getEntity();
      if (!(dying.level() instanceof ServerLevel level)) return;
      String typeName = dying.getType().getDescription().getString();
      String who = dying.hasCustomName() ? dying.getCustomName().getString() : typeName;

      // If the dying entity is an LLM villager, broadcast TOWN-WIDE so every
      // resident mourns (not just those within shouting distance), and mark
      // them dead in TownData so the roster reflects it.
      if (dying instanceof Villager dyingVillager) {
         LlmVillagerComponent dyingComp = dyingVillager.getData(ModRegistries.LLM_VILLAGER.get());
         if (dyingComp.townSquarePos() != 0L) {
            BlockPos townPos = BlockPos.of(dyingComp.townSquarePos());
            if (level.getBlockEntity(townPos) instanceof TownSquareBlockEntity town) {
               town.getTown().findVillager(dying.getUUID()).ifPresent(entry -> {
                  entry.markDead();
                  town.setChanged();
               });
               String mourned = who + " has died.";
               for (VillagerEntry e : town.getTown().villagers()) {
                  if (e.uuid().equals(dying.getUUID())) continue;
                  if (!e.alive()) continue;
                  if (!(level.getEntity(e.uuid()) instanceof Villager other)) continue;
                  LlmVillagerComponent comp = other.getData(ModRegistries.LLM_VILLAGER.get());
                  appendEvent(level, other, comp, mourned);
               }
               town.getTown().log().add(level.getGameTime(), TownLog.Level.WARN,
                  who + " (townsfolk) has died — mourned town-wide");
               VerboseLog.write("DEATH_TOWN", "deceased=" + who + " town=" + town.getTown().townName(), "");
               return;   // skip the radius notice — town-wide already covers it
            }
         }
      }

      // Non-LLM death OR LLM villager without a town: fall back to radius notice.
      AABB box = dying.getBoundingBox().inflate(DEATH_NOTICE_RADIUS);
      for (Villager v : level.getEntitiesOfClass(Villager.class, box)) {
         if (v == dying) continue;
         LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
         if (comp.townSquarePos() == 0L) continue;
         appendEvent(level, v, comp, who + " died nearby");
      }
   }

   private static String describeAttacker(DamageSource src) {
      Entity direct = src.getEntity();
      if (direct == null) return src.getMsgId();   // e.g. "fall", "drown"
      if (direct.hasCustomName()) return direct.getCustomName().getString();
      return direct.getType().getDescription().getString();
   }

   // ----- Periodic poll: sleep, weather, phase -----

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % POLL_TICKS != 0L) return;
      String dimKey = level.dimension().location().toString();

      // Weather transitions.
      boolean raining = level.isRaining();
      boolean thundering = level.isThundering();
      Boolean wasR = WAS_RAINING_BY_DIM.put(dimKey, raining);
      Boolean wasT = WAS_THUNDERING_BY_DIM.put(dimKey, thundering);
      if (wasR != null && wasR != raining) {
         broadcastToVillagers(level, raining ? "It started raining." : "The rain stopped.");
      }
      if (wasT != null && wasT != thundering) {
         broadcastToVillagers(level, thundering ? "A thunderstorm rolled in." : "The thunder eased off.");
      }

      // Phase transitions (narrative time of day, via dayTime).
      long t = level.getDayTime() % 24000L;
      String phase;
      if (t < 1000) phase = "dawn";
      else if (t < 6000) phase = "morning";
      else if (t < 9000) phase = "midday";
      else if (t < 12000) phase = "afternoon";
      else if (t < 13500) phase = "dusk";
      else if (t < 18000) phase = "night";
      else phase = "late night";
      String lastPhase = LAST_PHASE_BY_DIM.put(dimKey, phase);
      if (lastPhase != null && !lastPhase.equals(phase)) {
         broadcastToVillagers(level, "The " + phase + " came on.");
      }

      // Sleep wake + inventory diff (gift detection) — iterate town rosters,
      // not world-wide AABB (Sable compat).
      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.townSquarePos() == 0L) continue;

            boolean sleeping = v.isSleeping();
            Boolean prev = WAS_SLEEPING.put(v.getUUID(), sleeping);
            if (prev != null) {
               if (prev && !sleeping) appendEvent(level, v, comp, "I woke from sleep");
               else if (!prev && sleeping) appendEvent(level, v, comp, "I went to sleep");
            }

            Map<net.minecraft.world.item.Item, Integer> currentInv = inventoryMap(v);
            Map<net.minecraft.world.item.Item, Integer> previousInv = INVENTORY_SNAP.put(v.getUUID(), currentInv);
            if (previousInv != null) {
               Player nearbyPlayer = level.getNearestPlayer(v, GIFT_RADIUS);
               for (var e : currentInv.entrySet()) {
                  int delta = e.getValue() - previousInv.getOrDefault(e.getKey(), 0);
                  if (delta <= 0) continue;
                  if (nearbyPlayer == null) continue;
                  String giver = nearbyPlayer.getName().getString();
                  String item = e.getKey().getDescription().getString();
                  String narrative = giver + " gave me " + delta + "× " + item;
                  appendEvent(level, v, comp, narrative);
                  VerboseLog.write("GIFT_RECEIVED",
                     "villager=" + (v.hasCustomName() ? v.getCustomName().getString() : "?")
                        + " player=" + giver + " count=" + delta + " item=" + item, "");
               }
            }
         }
      }
   }

   private static Map<net.minecraft.world.item.Item, Integer> inventoryMap(Villager v) {
      var inv = v.getInventory();
      Map<net.minecraft.world.item.Item, Integer> map = new HashMap<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var stack = inv.getItem(i);
         if (stack.isEmpty()) continue;
         map.merge(stack.getItem(), stack.getCount(), Integer::sum);
      }
      return map;
   }

   private static void broadcastToVillagers(ServerLevel level, String text) {
      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         TownData data = town.getTown();
         for (VillagerEntry e : data.villagers()) {
            if (!e.alive()) continue;
            Entity ent = level.getEntity(e.uuid());
            if (!(ent instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            appendEvent(level, v, comp, text);
         }
         data.log().add(level.getGameTime(), TownLog.Level.INFO, "world: " + text);
      }
   }

   private static void appendEvent(ServerLevel level, Villager v, LlmVillagerComponent comp, String text) {
      long day = level.getGameTime() / 24000L;
      com.yucareux.townfolk.villager.MemoryStore.write(v, "world", day, text);
      VerboseLog.write("WORLD_EVENT",
         "villager=" + (v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString()) + " day=" + day,
         text);
      BlockPos pos = BlockPos.of(comp.townSquarePos());
      if (level.getBlockEntity(pos) instanceof TownSquareBlockEntity town) {
         String who = v.hasCustomName() ? v.getCustomName().getString() : "?";
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, who + ": " + text);
      }
   }

   private WorldEventListener() {}
}
