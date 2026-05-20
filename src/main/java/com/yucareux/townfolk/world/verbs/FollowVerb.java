package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.FollowService;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code [ACTION: follow <name>]} — start following a player or
 * fellow villager. The follow is registered with
 * {@link FollowService} for {@code DEFAULT_DURATION_TICKS} and
 * {@code ScheduleService} yields to it (FOLLOWING activity wins over
 * the schedule).
 *
 * <p>Extracted from {@code ToolDispatcher.doFollow} in stage 16b.2.a.
 */
public final class FollowVerb {

   private FollowVerb() {}

   public static void run(VerbContext ctx) {
      String t = ctx.body().toLowerCase(Locale.ROOT).replaceAll("^(the|a|an)\\s+", "").trim();
      UUID targetUuid = null;
      String resolvedName = null;

      // Players first (so "follow me" / "follow Dev" works).
      for (var p : ctx.level().players()) {
         if (t.contains(p.getName().getString().toLowerCase(Locale.ROOT))) {
            targetUuid = p.getUUID();
            resolvedName = p.getName().getString();
            break;
         }
      }
      // Then fellow villagers.
      if (targetUuid == null) {
         for (VillagerEntry e : ctx.town().getTown().villagers()) {
            if (t.contains(e.name().toLowerCase(Locale.ROOT))) {
               if (ctx.level().getEntity(e.uuid()) != null) {
                  targetUuid = e.uuid();
                  resolvedName = e.name();
                  break;
               }
            }
         }
      }
      if (targetUuid == null) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=follow_unresolved",
            "target=\"" + ctx.body() + "\"");
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " can't find " + ctx.body() + " to follow");
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "follow " + ctx.body() + " — couldn't resolve target");
         return;
      }
      long expire = ctx.level().getGameTime() + FollowService.DEFAULT_DURATION_TICKS;
      FollowService.start(ctx.level(), ctx.actor().getUUID(), targetUuid, expire);
      String summary = ctx.self().name() + " starts following " + resolvedName;
      VerboseLog.write("ACTION_RESULT", "actor=" + ctx.self().name() + " status=follow",
         "target=" + resolvedName + " expire=" + expire);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, summary);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "started following " + resolvedName);
   }
}
