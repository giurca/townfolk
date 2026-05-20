package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;

/**
 * {@code [ACTION: sleep]} — the villager stops what they're doing and
 * decides to find a bed. Vanilla AI handles the actual sleep
 * transition once they reach a {@code HOME} bed at night;
 * {@link com.yucareux.townfolk.world.ScheduleService} drives
 * navigation. This verb just clears the current path so the villager
 * doesn't keep walking somewhere irrelevant.
 *
 * <p>Extracted from {@code ToolDispatcher.doSleep} in stage 16b.2.a.
 */
public final class SleepVerb {

   private SleepVerb() {}

   public static void run(VerbContext ctx) {
      ctx.actor().getNavigation().stop();
      ctx.log("decides to find a bed");
   }
}
