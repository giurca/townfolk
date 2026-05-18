package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.town.StorageRegistry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Lifecycle glue for {@link StorageRegistry}: when a registered
 * container block is broken by a player, drop the registry entry so
 * villagers stop routing to a phantom barrel.
 *
 * <p>Caveat: {@link BlockEvent.BreakEvent} only fires for player-driven
 * breaks. Explosions, pistons, or world-edit-style replacements leave
 * an orphan entry behind. That's tolerable because all
 * registry-reading paths (touch / findByLabel / summaryNear) silently
 * no-op or skip when the BE at the recorded pos is no longer a
 * container — the orphan just wastes a few bytes of save data until
 * something rewrites the registry.
 *
 * <p>Pre-Phase-4 this was {@code StorageLedgerHooks} doing the same
 * thing for the now-deleted {@code TownStorageLedger}. The registry
 * itself had no cleanup hook — orphan entries would linger across
 * sessions and waste villager block-task timeouts trying to walk to
 * non-existent containers.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class StorageRegistryHooks {

   private StorageRegistryHooks() {}

   /** Drop the registry entry when a registered block is broken. */
   @SubscribeEvent
   public static void onBlockBreak(BlockEvent.BreakEvent event) {
      if (!(event.getLevel() instanceof ServerLevel sl)) return;
      if (!StorageRegistry.isRegistered(sl, event.getPos())) return;
      StorageRegistry.forget(sl, event.getPos());
   }
}
