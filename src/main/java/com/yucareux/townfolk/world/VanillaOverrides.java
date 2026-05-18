package com.yucareux.townfolk.world;

import com.yucareux.townfolk.diag.VerboseLog;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Vestigial holder for what used to be a forest of vanilla-AI override
 * loops. Now that {@link com.yucareux.townfolk.entity.LlmTownsfolk} runs an
 * empty Brain there is nothing to override — vanilla AI has no registered
 * behaviors that could clobber our navigation, claim our beds, or panic
 * our brave villagers.
 *
 * The two methods below remain as no-op stubs so callers (IntentExecutor,
 * NeedsService) compile without churn; they used to register / clear an
 * intent for the now-defunct re-issue loop. Direct {@code nav.moveTo}
 * calls are now authoritative — set the path once, the navigator walks it.
 */
public final class VanillaOverrides {

   public record IntentState(BlockPos target, double speed, long expireGameTime, String reason) {}

   public static void assertIntent(ServerLevel level, UUID actor, BlockPos target, double speed, String reason) {
      VerboseLog.write("INTENT_ASSERT", "actor=" + actor + " target=" + target.toShortString()
         + " reason=" + reason + " (noop — nav is direct now)", "");
   }

   public static void clearIntent(UUID actor) {
      // No-op kept for callers (FollowService.start). Nothing to clear.
   }

   private VanillaOverrides() {}
}
