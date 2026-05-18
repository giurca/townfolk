package com.yucareux.townfolk.world;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

/**
 * Stage 3: parse and execute movement intents emitted by the LLM.
 *
 * Recognised marker (anywhere in the reply, case-insensitive):
 *   [INTENT: walk to <target>]
 *
 * Target resolution (in order):
 *   - "town square" / "square"          → town centre block pos
 *   - "home" / "bed"                    → not yet tracked, falls through to town square
 *   - "<villager name>"                 → that villager's current position
 *   - bare BlockPos like "100, 64, -42" → that pos (with sanity bounds)
 *
 * On resolve, the villager's pathfinder is told to move to within 2 blocks
 * of the target at speed 0.6. Failures are logged but never thrown.
 */
public final class IntentExecutor {

   private static final Pattern INTENT = Pattern.compile(
      "\\[\\s*INTENT\\s*:\\s*([^\\]]+?)\\s*\\]", Pattern.CASE_INSENSITIVE);
   private static final Pattern WALK_TO =
      Pattern.compile("(?:walk|go|head|move)\\s+(?:to|towards|over\\s+to)?\\s*(.+?)\\.?$",
         Pattern.CASE_INSENSITIVE);

   public record ParseResult(String cleanedText, java.util.List<String> intents) {}

   public static ParseResult parse(String raw) {
      if (raw == null || raw.isEmpty()) return new ParseResult("", java.util.List.of());
      Matcher m = INTENT.matcher(raw);
      java.util.List<String> found = new java.util.ArrayList<>();
      StringBuffer sb = new StringBuffer();
      while (m.find()) {
         found.add(m.group(1).trim());
         m.appendReplacement(sb, "");
      }
      m.appendTail(sb);
      return new ParseResult(sb.toString().replaceAll("\\s+", " ").trim(), found);
   }

   /** Backwards-compat shim: callers that don't specify a source are
    *  treated as autonomous (no schedule override). Existing autonomy +
    *  villager-to-villager paths can keep using the 5-arg form. */
   public static void execute(ServerLevel level, TownSquareBlockEntity town, Villager actor,
                              VillagerEntry self, String intent) {
      execute(level, town, actor, self, intent, false);
   }

   /** Run an LLM-emitted walk/follow intent.
    *  @param playerInitiated true when the source is a player-driven
    *    dialogue reply — i.e. the player typed something and the
    *    villager's response contained an [INTENT: …]. In that case we
    *    suspend the schedule for {@link ScheduleService#PLAYER_OVERRIDE_TICKS}
    *    so it doesn't immediately drag the villager off again.
    *    When false (autonomous tick, villager-to-villager chatter) we
    *    DO NOT suspend — otherwise an evening-time autonomous "let me
    *    go check on the goats" intent blocks bedtime for 6 minutes
    *    and the villager never gets to bed. */
   public static void execute(ServerLevel level, TownSquareBlockEntity town, Villager actor,
                              VillagerEntry self, String intent, boolean playerInitiated) {
      String text = intent.toLowerCase(Locale.ROOT).trim();
      VerboseLog.write("INTENT", "actor=" + self.name()
         + " source=" + (playerInitiated ? "player" : "auto")
         + " raw=\"" + intent + "\"", "");

      Matcher m = WALK_TO.matcher(text);
      String target;
      if (m.find()) {
         target = m.group(1).trim();
      } else {
         target = text;     // permissive: treat the whole thing as a target name
      }
      target = target.replaceAll("^(the|a|an)\\s+", "");

      // Player (or villager) target → continuous follow, not a one-shot walk.
      // Moving entities can't be tracked via a static BlockPos.
      for (var p : level.players()) {
         if (target.contains(p.getName().getString().toLowerCase(Locale.ROOT))) {
            long expire = level.getGameTime() + FollowService.DEFAULT_DURATION_TICKS;
            FollowService.start(level, actor.getUUID(), p.getUUID(), expire);
            VerboseLog.write("INTENT_RESULT", "actor=" + self.name() + " status=follow_via_intent",
               "target=player:" + p.getName().getString());
            town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
               self.name() + " starts following " + p.getName().getString());
            return;
         }
      }
      for (VillagerEntry other : town.getTown().villagers()) {
         if (other.uuid().equals(self.uuid())) continue;
         if (target.contains(other.name().toLowerCase(Locale.ROOT))) {
            if (level.getEntity(other.uuid()) == null) continue;
            long expire = level.getGameTime() + FollowService.DEFAULT_DURATION_TICKS;
            FollowService.start(level, actor.getUUID(), other.uuid(), expire);
            VerboseLog.write("INTENT_RESULT", "actor=" + self.name() + " status=follow_via_intent",
               "target=villager:" + other.name());
            town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
               self.name() + " starts following " + other.name());
            return;
         }
      }

      Optional<BlockPos> resolved = resolveTarget(level, town, actor, target);
      if (resolved.isEmpty()) {
         VerboseLog.write("INTENT_RESULT", "actor=" + self.name() + " status=unresolved",
            "target=\"" + target + "\"");
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
            self.name() + " intent unresolved: \"" + target + "\"");
         return;
      }
      BlockPos goal = resolved.get();
      // Enforce the town boundary as a hard limit: if the resolved goal lies
      // beyond town radius from the square AND no player is currently
      // escorting (following us toward it), refuse and log. Player follows
      // legitimately drag villagers further afield; raw intents don't.
      // Boundary check via the town's current coverage — once Stage 1.3
      // lands, this automatically respects auxiliary blocks (Trade Post,
      // etc.) that extend the town's footprint.
      com.yucareux.townfolk.town.TownCoverage coverage = town.coverage();
      if (!coverage.contains(goal)) {
         int rad = coverage.maxRadius();
         VerboseLog.write("INTENT_RESULT", "actor=" + self.name() + " status=outside_boundary",
            "target=\"" + target + "\" pos=" + goal.toShortString() + " radius=" + rad);
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
            self.name() + " refuses to leave town (target outside " + rad + "-block radius)");
         return;
      }
      // Walking to a fixed place is incompatible with following a moving
      // entity — stop any active follow so its per-tick refresh doesn't
      // clobber our nav.
      FollowService.stop(level, actor.getUUID());
      // Player intents suspend the schedule briefly so it doesn't
      // immediately drag the villager off again ("go home" mid-workday).
      // Autonomous intents do NOT suspend — otherwise an evening
      // "I think I'll wander the market" autonomy intent locks the
      // schedule out for 6 minutes and the villager never goes to bed.
      if (playerInitiated) {
         ScheduleService.suspendFor(level, actor.getUUID(), ScheduleService.PLAYER_OVERRIDE_TICKS);
      }
      boolean ok = com.yucareux.townfolk.diag.NavCall.moveTo(actor, goal, 0.6, "IntentExecutor:" + target);
      VerboseLog.write("INTENT_RESULT", "actor=" + self.name() + " status=" + (ok ? "navigating" : "blocked"),
         "target=\"" + target + "\" pos=" + goal.toShortString());
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
         self.name() + " heads toward " + target + " (" + goal.toShortString() + ")");
   }

   private static Optional<BlockPos> resolveTarget(ServerLevel level, TownSquareBlockEntity town,
                                                   Villager actor, String target) {
      if (target.isEmpty()) return Optional.empty();

      // Town square / square / centre.
      if (target.contains("town square") || target.equals("square") || target.contains("centre") || target.contains("center")) {
         return Optional.of(town.getBlockPos());
      }
      // Home / bed → the villager's player-assigned bed (Brain.HOME), if any.
      // Falls back to town centre only when nothing has been assigned yet.
      if (target.equals("home") || target.contains("my house") || target.equals("bed") || target.contains("my bed")) {
         var home = actor.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
         if (home.isPresent()) return Optional.of(home.get().pos());
         return Optional.of(town.getBlockPos());
      }
      // Work / workstation / forge / shop → JOB_SITE memory.
      if (target.equals("work") || target.contains("my work") || target.contains("workstation")
          || target.contains("my forge") || target.contains("my shop")) {
         var job = actor.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
         if (job.isPresent()) return Optional.of(job.get().pos());
         return Optional.of(town.getBlockPos());
      }

      // Villager by name (case-insensitive substring match against entry names).
      // Skip self: an LLM that emits [INTENT: walk to <self>] would
      // otherwise resolve to its own position and lock up the navigator.
      TownData data = town.getTown();
      java.util.UUID selfUuid = actor.getUUID();
      for (VillagerEntry other : data.villagers()) {
         if (other.uuid().equals(selfUuid)) continue;
         if (target.contains(other.name().toLowerCase(Locale.ROOT))) {
            Entity ent = level.getEntity(other.uuid());
            if (ent != null) return Optional.of(ent.blockPosition());
         }
      }

      // Player by name (online + in this level).
      for (var p : level.players()) {
         if (target.contains(p.getName().getString().toLowerCase(Locale.ROOT))) {
            return Optional.of(p.blockPosition());
         }
      }

      // Bare "x, y, z" coordinates.
      try {
         String[] parts = target.replaceAll("[()]", "").split("\\s*,\\s*");
         if (parts.length == 3) {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            // Cap within ±256 of the town square so we don't ship them across the world.
            BlockPos t = town.getBlockPos();
            if (Math.abs(x - t.getX()) < 512 && Math.abs(z - t.getZ()) < 512) {
               return Optional.of(new BlockPos(x, y, z));
            }
         }
      } catch (NumberFormatException ignored) {}

      return Optional.empty();
   }

   private IntentExecutor() {}
}
