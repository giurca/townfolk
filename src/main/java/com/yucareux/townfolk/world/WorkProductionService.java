package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Tick-driven dispatcher for the parcels-driven work routine. The actual
 * scan / decide logic lives in {@link ParcelRoutine}; this file just owns
 * the cadence and the event subscription.
 *
 * Two trigger paths:
 *   1. Polling tick — every {@link #PRODUCTION_INTERVAL_TICKS}, scan every
 *      loaded townsfolk and let {@link ParcelRoutine} pick a task.
 *   2. Chain-on-completion — a {@link BlockTaskQueue.CompletionListener}
 *      fires the routine immediately after a successful block task, so
 *      harvest → plant / harvest → harvest chains happen at walk-pace
 *      instead of polling-pace.
 *
 * Profession is no longer consulted — what a villager does emerges from
 * what's on their parcels and what's in their bag. The vanilla profession
 * field becomes a cosmetic re-skin driven by observed activity (handled
 * elsewhere). Villagers with no parcels do nothing autonomous; the LLM and
 * dialogue still drive them.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class WorkProductionService {

   /** 15 seconds — fast enough that the loop feels alive, slow enough that a
    *  busy town of 50 villagers doesn't tax the server. */
   private static final int PRODUCTION_INTERVAL_TICKS = 20 * 15;

   /** Context passed into routine methods. Frees the routine from having to
    *  re-pull every value out of the same handful of objects. */
   public record Ctx(ServerLevel level, TownSquareBlockEntity town,
                     Villager actor, VillagerEntry entry, LlmVillagerComponent comp) {}

   /** Chain-on-completion: fire the routine again the instant any block
    *  OR entity task lands for a villager whose parcels have more work
    *  to do. Block tasks chain harvest → plant → harvest, etc. Entity
    *  tasks chain shear → next-sheep without waiting for the 15 s
    *  polling tick. */
   static {
      BlockTaskQueue.addCompletionListener((level, actor, task) ->
         retryRoutine(level, actor, "block:" + task.verb()));
      EntityTaskQueue.addCompletionListener((level, actor, task, target) ->
         retryRoutine(level, actor, "entity:" + task.verb()));
   }

   private static void retryRoutine(ServerLevel level, Villager actor, String cause) {
      TownSquareBlockEntity town = findTownFor(level, actor);
      if (town == null) return;
      VillagerEntry entry = town.getTown().findVillager(actor.getUUID()).orElse(null);
      if (entry == null) return;
      // Recursion guard: if the chain just enqueued another block task
      // (e.g. harvest → plant), don't fire the routine AGAIN until that
      // new task lands. The polling tick already guards on this; doing
      // the same here prevents entity→block→entity recursive stacking
      // through the listener chain.
      if (BlockTaskQueue.hasPending(actor.getUUID())) {
         com.yucareux.townfolk.diag.VerboseLog.write("CHAIN_SKIP",
            "actor=" + entry.name() + " cause=" + cause,
            "skipping retry — block task already pending");
         return;
      }
      LlmVillagerComponent c = actor.getData(ModRegistries.LLM_VILLAGER.get());
      com.yucareux.townfolk.diag.VerboseLog.write("CHAIN_FIRE",
         "actor=" + entry.name() + " cause=" + cause, "");
      ParcelRoutine.tryTick(new Ctx(level, town, actor, entry, c));
   }

   private static TownSquareBlockEntity findTownFor(ServerLevel level, Villager actor) {
      LlmVillagerComponent c = actor.getData(ModRegistries.LLM_VILLAGER.get());
      if (c.townSquarePos() == 0L) return null;
      var be = level.getBlockEntity(net.minecraft.core.BlockPos.of(c.townSquarePos()));
      return be instanceof TownSquareBlockEntity t ? t : null;
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % PRODUCTION_INTERVAL_TICKS != 0L) return;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent c = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (c.townSquarePos() == 0L) continue;
            // Don't pile work on top of an in-flight block task — wait for it.
            if (BlockTaskQueue.hasPending(v.getUUID())) continue;
            // Parcels-driven only. No parcels → no autonomous work.
            if (c.parcels().isEmpty()) continue;
            ParcelRoutine.tryTick(new Ctx(level, town, v, entry, c));
         }
      }
   }

   private WorkProductionService() {}
}
