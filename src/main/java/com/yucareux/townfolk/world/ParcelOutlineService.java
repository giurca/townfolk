package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;

/**
 * Renders each villager-owned parcel as a dust-particle outline along the
 * four horizontal edges of its bounding box, but only while a player nearby
 * is holding a Surveyor's Stake (main or off hand). When no one's holding a
 * stake, no particles are emitted — cost stays at zero except during active
 * surveying.
 *
 * Each villager gets a deterministic color hash from their UUID, mapped onto
 * the full hue wheel. Same villager always renders the same color across
 * sessions; with the ≤50 villager cap, hues spread well enough that adjacent
 * plots are visually distinct.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class ParcelOutlineService {

   private static final int EMIT_INTERVAL_TICKS = 20;        // 1 s — feels lively
   private static final double VIEW_RADIUS = 96.0;
   /** Spacing between particles along an edge. 1 = every block; 2 = every other. */
   private static final int EDGE_STRIDE = 1;

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % EMIT_INTERVAL_TICKS != 0L) return;

      // Collect all players in this level holding a stake. If none, bail.
      java.util.List<ServerPlayer> viewers = new java.util.ArrayList<>();
      for (ServerPlayer p : level.players()) {
         if (isHoldingStake(p)) viewers.add(p);
      }
      if (viewers.isEmpty()) return;

      // Walk every loaded townsfolk villager once; render each of their
      // parcels if any viewer is within range.
      for (Entity ent : level.getAllEntities()) {
         if (!(ent instanceof Villager v)) continue;
         LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
         if (comp.parcels().isEmpty()) continue;

         // Only emit if at least one viewer is close.
         boolean anyClose = false;
         for (ServerPlayer p : viewers) {
            if (p.distanceToSqr(v) <= VIEW_RADIUS * VIEW_RADIUS) {
               anyClose = true; break;
            }
         }
         if (!anyClose) continue;

         DustParticleOptions colour = colourFor(v.getUUID());
         for (FieldRegion fr : comp.parcels()) {
            drawOutline(level, fr, colour);
         }
      }
   }

   /** Walks the four horizontal edges of the parcel's AABB at the floor Y
    *  (minY + 1, so particles sit just above ground). */
   private static void drawOutline(ServerLevel level, FieldRegion fr, DustParticleOptions colour) {
      BlockPos mn = fr.minCorner();
      BlockPos mx = fr.maxCorner();
      double y = mn.getY() + 1.05;
      int x0 = mn.getX(), x1 = mx.getX();
      int z0 = mn.getZ(), z1 = mx.getZ();
      // South and north edges (along X, at z=z0 and z=z1).
      for (int x = x0; x <= x1; x += EDGE_STRIDE) {
         level.sendParticles(colour, x + 0.5, y, z0 + 0.5, 1, 0, 0, 0, 0);
         level.sendParticles(colour, x + 0.5, y, z1 + 0.5, 1, 0, 0, 0, 0);
      }
      // West and east edges (along Z, at x=x0 and x=x1). Skip corners — they
      // were emitted above.
      for (int z = z0 + EDGE_STRIDE; z <= z1 - EDGE_STRIDE; z += EDGE_STRIDE) {
         level.sendParticles(colour, x0 + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
         level.sendParticles(colour, x1 + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
      }
   }

   private static boolean isHoldingStake(ServerPlayer p) {
      var stake = ModRegistries.SURVEYOR_STAKE.get();
      return p.getMainHandItem().getItem() == stake
          || p.getOffhandItem().getItem() == stake;
   }

   /** Deterministic HSV→RGB color from villager UUID. Hue from the low 16
    *  bits of {@link UUID#hashCode}, full saturation, slightly darkened
    *  value to keep particles readable on bright terrain. */
   private static DustParticleOptions colourFor(UUID id) {
      float hue = (id.hashCode() & 0xFFFF) / 65535f;
      int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 0.85f);
      float r = ((rgb >> 16) & 0xFF) / 255f;
      float g = ((rgb >>  8) & 0xFF) / 255f;
      float b = ( rgb        & 0xFF) / 255f;
      return new DustParticleOptions(new Vector3f(r, g, b), 1.0f);
   }

   private ParcelOutlineService() {}
}
