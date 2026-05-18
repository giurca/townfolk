package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.world.entity.Entity;

/**
 * Self-healing town membership.
 *
 * Background: a villager's identity (backstory, beliefs, RAG memory, todos,
 * anchors) lives in the per-entity {@link LlmVillagerComponent} attachment and
 * persists with the entity in chunk NBT. The TOWN side of the relationship —
 * the roster, the shared town facts, the storage caps — lives on the Town
 * Square block entity's {@link TownData}. If a player breaks the Town Square
 * block (intentionally or by accident), that side is lost; the villagers
 * themselves are fine, but they've been orphaned from their town.
 *
 * This service quietly reconciles the two sides on a slow tick. Every
 * {@link #RECONCILE_INTERVAL_TICKS} game ticks, for every loaded
 * {@link LlmTownsfolk}:
 *
 *   1. If the villager points at a valid Town Square AND that town's roster
 *      already contains them — nothing to do.
 *   2. If the villager points at a valid Town Square but the roster has no
 *      entry — re-add them (the block was replaced; auto-adopt).
 *   3. If the villager points at no town (or a stale position) — find the
 *      nearest loaded Town Square within {@link #ADOPTION_RADIUS_BLOCKS} and
 *      adopt them into it, rebinding the component's {@code townSquarePos}.
 *
 * The recovered roster won't include backstories, beliefs, or memories — those
 * survived on the entity itself. Only the town-shared facts (which were on the
 * block) are lost. Day-to-day behaviour resumes immediately.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class TownAdoptionService {

   /** 5 seconds — fast enough that a replaced Town Square feels instant, slow
    *  enough that the entity-scan cost is negligible. */
   private static final int RECONCILE_INTERVAL_TICKS = 100;

   /** Hard cap on how far a Town Square will reach to adopt orphans. Larger
    *  than {@code defaultRadius} so an off-centre replacement still rebinds. */
   private static final int ADOPTION_RADIUS_BLOCKS = 128;

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % RECONCILE_INTERVAL_TICKS != 0L) return;

      for (Entity ent : level.getAllEntities()) {
         if (!(ent instanceof LlmTownsfolk villager)) continue;
         reconcileOne(level, villager);
      }
   }

   private static void reconcileOne(ServerLevel level, LlmTownsfolk villager) {
      LlmVillagerComponent comp = villager.getData(ModRegistries.LLM_VILLAGER.get());

      // (1) Existing claim is still valid?
      if (comp.townSquarePos() != 0L) {
         BlockPos claimed = BlockPos.of(comp.townSquarePos());
         BlockEntity be = level.getBlockEntity(claimed);
         if (be instanceof TownSquareBlockEntity town) {
            TownData data = town.getTown();
            boolean inRoster = data.findVillager(villager.getUUID()).isPresent();
            if (inRoster) return;     // (1) all good
            // (2) Block exists, roster doesn't list them — re-add.
            String name = nameOf(villager);
            data.addVillager(new VillagerEntry(
               villager.getUUID(), name, "resident",
               comp.personaSeed() == null ? "" : comp.personaSeed(),
               villager.blockPosition()));
            town.setChanged();
            data.log().add(level.getGameTime(), TownLog.Level.INFO,
               name + " was re-adopted into the roster (block had no record)");
            VerboseLog.write("ADOPT_REJOIN", "actor=" + name + " town=" + data.townName(), "");
            return;
         }
      }

      // (3) No valid claim — find the nearest Town Square and adopt.
      TownSquareBlockEntity nearest = null;
      double bestSq = (double) ADOPTION_RADIUS_BLOCKS * ADOPTION_RADIUS_BLOCKS;
      for (TownSquareBlockEntity candidate : TownSquareBlockEntity.loadedIn(level)) {
         double d = villager.distanceToSqr(
            candidate.getBlockPos().getX() + 0.5,
            candidate.getBlockPos().getY(),
            candidate.getBlockPos().getZ() + 0.5);
         if (d <= bestSq) { bestSq = d; nearest = candidate; }
      }
      if (nearest == null) return;   // nothing nearby — try again next tick

      TownData data = nearest.getTown();
      String name = nameOf(villager);
      if (data.findVillager(villager.getUUID()).isEmpty()) {
         data.addVillager(new VillagerEntry(
            villager.getUUID(), name, "resident",
            comp.personaSeed() == null ? "" : comp.personaSeed(),
            villager.blockPosition()));
      }
      villager.setData(ModRegistries.LLM_VILLAGER.get(),
         comp.withTownSquarePos(nearest.getBlockPos().asLong()));
      nearest.setChanged();
      data.log().add(level.getGameTime(), TownLog.Level.INFO,
         name + " was adopted into " + data.townName() + " (rebound from orphan)");
      VerboseLog.write("ADOPT_FRESH", "actor=" + name + " town=" + data.townName(), "");
   }

   private static String nameOf(LlmTownsfolk v) {
      return v.hasCustomName() ? v.getCustomName().getString()
                               : v.getUUID().toString().substring(0, 8);
   }

   private TownAdoptionService() {}
}
