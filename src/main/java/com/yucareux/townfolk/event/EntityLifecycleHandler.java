package com.yucareux.townfolk.event;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.world.ParcelRoutine;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Garbage-collects per-villager state stashed in static maps when the
 * entity goes away — death, chunk unload, dimension change, whatever. The
 * various ParcelRoutine bookkeeping maps (parcel-visit timers, "lacking
 * X" memory dedupe) used to grow forever over a long session because
 * they had no cleanup hook.
 *
 * This is a no-op for non-townsfolk entities so the event handler stays
 * cheap.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class EntityLifecycleHandler {

   @SubscribeEvent
   public static void onLeave(EntityLeaveLevelEvent event) {
      if (event.getEntity() instanceof LlmTownsfolk t) {
         ParcelRoutine.onVillagerRemoved(t.getUUID());
      }
   }

   private EntityLifecycleHandler() {}
}
