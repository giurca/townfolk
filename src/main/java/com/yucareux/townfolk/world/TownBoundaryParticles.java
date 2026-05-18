package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Visualises each town's boundary as a faint particle ring at ground level.
 * Only emitted when a player is within {@link #PLAYER_NEARBY_BLOCKS} of the
 * Town Square so the world isn't sprinkled with particles in unobserved
 * chunks (zero net cost when nobody's watching).
 *
 * Cosmetic only — the actual boundary enforcement lives in
 * {@link com.yucareux.townfolk.world.IntentExecutor}; this just makes the
 * limit legible to the player at a glance.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class TownBoundaryParticles {

   private static final int EMIT_INTERVAL_TICKS = 40;          // 2 s
   private static final int PARTICLES_PER_RING = 48;
   private static final double PLAYER_NEARBY_BLOCKS = 96.0;

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % EMIT_INTERVAL_TICKS != 0L) return;
      if (level.players().isEmpty()) return;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         // One ring per coverage disk. Currently a single disk around
         // the master block; once Stage 1.3 lands and the Trade Post
         // contributes its own disk, this loop draws all of them
         // automatically — no logic change needed.
         com.yucareux.townfolk.town.TownCoverage coverage = town.coverage();
         for (var disk : coverage.disks()) {
            BlockPos centre = disk.center();
            int radius = disk.radius();

            // Skip if no player close enough to see THIS disk.
            boolean anyClose = false;
            for (ServerPlayer p : level.players()) {
               double dx = p.getX() - centre.getX();
               double dz = p.getZ() - centre.getZ();
               if (Math.sqrt(dx * dx + dz * dz) < radius + PLAYER_NEARBY_BLOCKS) {
                  anyClose = true; break;
               }
            }
            if (!anyClose) continue;

            double cx = centre.getX() + 0.5;
            double cz = centre.getZ() + 0.5;
            double y  = centre.getY() + 0.3;
            for (int i = 0; i < PARTICLES_PER_RING; i++) {
               double theta = (2 * Math.PI * i) / PARTICLES_PER_RING
                            // Slow rotation so it looks alive rather than stencilled.
                            + (level.getGameTime() / 200.0);
               double x = cx + Math.cos(theta) * radius;
               double z = cz + Math.sin(theta) * radius;
               level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0, 0, 0);
            }
         }
      }
   }

   private TownBoundaryParticles() {}
}
