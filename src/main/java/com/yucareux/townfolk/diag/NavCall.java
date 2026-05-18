package com.yucareux.townfolk.diag;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;

/**
 * Single chokepoint for nav.moveTo calls so we can attribute every command
 * to a caller in the verbose log. Use instead of villager.getNavigation().moveTo(...).
 */
public final class NavCall {

   public static boolean moveTo(Villager v, BlockPos pos, double speed, String caller) {
      var nav = v.getNavigation();
      boolean ok = nav.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
      String name = v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString().substring(0, 8);
      VerboseLog.write("NAV_MOVE",
         "villager=" + name + " caller=" + caller + " target=" + pos.toShortString()
            + " speed=" + speed + " ok=" + ok
            + " hasPath=" + (nav.getPath() != null)
            + " noActionTime=" + v.getNoActionTime(), "");
      return ok;
   }

   public static void stop(Villager v, String caller) {
      v.getNavigation().stop();
      String name = v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString().substring(0, 8);
      VerboseLog.write("NAV_STOP", "villager=" + name + " caller=" + caller, "");
   }

   private NavCall() {}
}
