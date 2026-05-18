package com.yucareux.townfolk.diag;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Heavy diagnostic — every {@value #INTERVAL_TICKS} ticks (1 second), log a
 * snapshot of every LLM townsfolk's nav state. We want to actually SEE what
 * the navigator is doing so we stop guessing about why villagers freeze.
 *
 * Per-villager line includes:
 *   - position (x,y,z, rounded)
 *   - has-path flag + path's target node
 *   - distance from current to path target
 *   - isDone() / isStuck() (where exposed)
 *   - velocity magnitude (deltaMovement length)
 *   - noActionTime
 *   - isSleeping / isPassenger / isImmobile
 *
 * Plus a one-line CHANGE delta if position moved since last snapshot.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class NavTracer {

   private static final int INTERVAL_TICKS = 20;

   private record Last(double x, double y, double z, long gameTime) {}
   private static final Map<UUID, Last> LAST_POS = new HashMap<>();

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % INTERVAL_TICKS != 0L) return;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.townSquarePos() == 0L) continue;

            var nav = v.getNavigation();
            var path = nav.getPath();
            double dx = 0, dy = 0, dz = 0;
            double dist = 0;
            Last prev = LAST_POS.get(v.getUUID());
            long now = level.getGameTime();
            if (prev != null) {
               dx = v.getX() - prev.x();
               dy = v.getY() - prev.y();
               dz = v.getZ() - prev.z();
               dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            LAST_POS.put(v.getUUID(), new Last(v.getX(), v.getY(), v.getZ(), now));

            String name = v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString().substring(0, 8);
            String pathTarget = path == null ? "-" : (path.getTarget() == null ? "?" : path.getTarget().toShortString());
            String pathStatus = path == null ? "none"
               : (path.isDone() ? "done" : "active(node " + path.getNextNodeIndex() + "/" + path.getNodeCount() + ")");
            double vel = v.getDeltaMovement().length();

            String line = String.format(Locale.ROOT,
               "pos=(%.1f,%.1f,%.1f) Δ=%.2f vel=%.3f path=%s pathTarget=%s noActionTime=%d "
                  + "isSleeping=%b isPassenger=%b isAlive=%b health=%.1f isNoAi=%b",
               v.getX(), v.getY(), v.getZ(), dist, vel, pathStatus, pathTarget,
               v.getNoActionTime(), v.isSleeping(), v.isPassenger(),
               v.isAlive(), v.getHealth(), v.isNoAi());
            VerboseLog.write("NAV_SNAPSHOT", "villager=" + name + " tick=" + now, line);
         }
      }
   }

   private NavTracer() {}
}
