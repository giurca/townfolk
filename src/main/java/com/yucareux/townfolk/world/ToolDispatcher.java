package com.yucareux.townfolk.world;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Stage 4: discrete tool calls emitted by the LLM as inline markers.
 *
 * Recognised markers (anywhere in the reply, case-insensitive, one per line
 * or interleaved):
 *
 *   [ACTION: give <target>: <count> <item_name>]
 *   [ACTION: sleep]
 *   [ACTION: work]                 — placeholder: clears any active walk target
 *   [ACTION: follow <target>]      — like INTENT walk-to but continuously refreshed
 *
 * Each call is text-based rather than OpenAI function-calling so it works
 * with any model. Failures are logged but never thrown.
 */
public final class ToolDispatcher {

   private static final Pattern ACTION = Pattern.compile(
      "\\[\\s*ACTION\\s*:\\s*([^\\]]+?)\\s*\\]", Pattern.CASE_INSENSITIVE);

   public record ParseResult(String cleanedText, List<String> actions) {}

   public static ParseResult parse(String raw) {
      if (raw == null || raw.isEmpty()) return new ParseResult("", List.of());
      Matcher m = ACTION.matcher(raw);
      List<String> found = new ArrayList<>();
      StringBuffer sb = new StringBuffer();
      while (m.find()) {
         found.add(m.group(1).trim());
         m.appendReplacement(sb, "");
      }
      m.appendTail(sb);
      return new ParseResult(sb.toString().replaceAll("\\s+", " ").trim(), found);
   }

   // ─────────────────────── Verb registry ───────────────────────
   //
   // Each verb entry is matched against the lowercased action text in
   // declaration order; first match wins. Body = everything after the
   // matched keyword, with leading ":" / whitespace stripped — every
   // handler that needs args reads the same normalized form.
   //
   // Stage 16b.1: collapse the if/else chain into a typed registry.
   // Stage 16b.2 will move each handler into its own world/verbs/*Verb.java.

   /** Context bundle handed to every verb handler. {@code verbKey} is
    *  the matched alias from the dispatch table; {@code body} is the
    *  args after the matched verb keyword, normalized. Convenience
    *  {@link #log} routes through {@link ToolDispatcher#log} so verb
    *  files in {@code world.verbs.*} don't depend on the parent
    *  package's package-private access. */
   public record VerbContext(ServerLevel level, TownSquareBlockEntity town,
                              Villager actor, VillagerEntry self,
                              String verbKey, String body, String actionRaw) {
      public void log(String msg) {
         ToolDispatcher.log(this.level, this.town, this.self, msg);
      }
   }

   @FunctionalInterface
   public interface VerbHandler {
      void run(VerbContext ctx);
   }

   /** One row in the dispatch table. {@code keywords} is the list of
    *  aliases the LLM might use ("claim_home" / "claim home" / "claim_bed").
    *  Match is "equals OR startsWith(kw + ' ')" — strict-prefix to avoid
    *  e.g. "forget_parcels" matching the "forget" branch. */
   private record VerbDef(String[] keywords, boolean isBlockTask,
                           boolean suppressAfterReflex, VerbHandler handler) {
      /** Returns the matched keyword (longest within this def's aliases) or null.
       *  Accepts {@code action == kw}, {@code "kw "}, or {@code "kw:"} — the
       *  latter covers LLM emissions like {@code "give:Player: 1 wheat"} or
       *  {@code "completed:fetch hoe"}. The body extractor strips the
       *  leading {@code ':'} so handlers see a clean payload. */
      String matchKeyword(String action) {
         String best = null;
         for (String kw : keywords) {
            boolean match;
            if (action.equals(kw)) {
               match = true;
            } else if (action.length() > kw.length() && action.startsWith(kw)) {
               char next = action.charAt(kw.length());
               match = (next == ' ' || next == ':');
            } else {
               match = false;
            }
            if (match && (best == null || kw.length() > best.length())) best = kw;
         }
         return best;
      }
   }

   /** Pull the trailing args off a verb-matched action. Strips the
    *  matched keyword + any leading ":" or whitespace so handlers
    *  receive a clean payload. */
   private static String extractBody(String action, String matchedKeyword) {
      if (action.length() <= matchedKeyword.length()) return "";
      return action.substring(matchedKeyword.length()).replaceFirst("^[:\\s]+", "").trim();
   }

   /** Block-task verbs already fire their after:<verb> reflexes via
    *  BlockTaskQueue's completion listener — listed here just for the
    *  isBlockTask flag construction below. */
   private static VerbDef def(String[] kws, boolean blockTask, boolean suppressReflex, VerbHandler h) {
      return new VerbDef(kws, blockTask, suppressReflex, h);
   }

   /** Ordered list of verb defs. Longer / more-specific keywords come
    *  first so "stop following" wins against "stop". */
   private static final List<VerbDef> VERBS = List.of(
      // Specific stop-action variants — must precede the bare "work"/"stop" branch.
      def(new String[]{"stop following", "unfollow"}, false, false, ctx -> {
         FollowService.stop(ctx.level, ctx.actor.getUUID());
         log(ctx.level, ctx.town, ctx.self, "stops following");
      }),
      def(new String[]{"work", "stop"}, false, false, ctx -> {
         ctx.actor.getNavigation().stop();
         log(ctx.level, ctx.town, ctx.self, "stops to work");
      }),

      def(new String[]{"give"},             false, false, com.yucareux.townfolk.world.verbs.GiveVerb::run),
      def(new String[]{"claim_home", "claim home", "claim_bed", "claim bed"},
                                            false, false, com.yucareux.townfolk.world.verbs.ClaimHomeVerb::run),
      def(new String[]{"completed", "done"},false, false, com.yucareux.townfolk.world.verbs.CompleteVerb::run),
      def(new String[]{"sleep"},            false, false, com.yucareux.townfolk.world.verbs.SleepVerb::run),
      def(new String[]{"follow"},           false, false, com.yucareux.townfolk.world.verbs.FollowVerb::run),
      def(new String[]{"craft"},            false, false, com.yucareux.townfolk.world.verbs.CraftVerb::run),
      def(new String[]{"deposit"},          true,  false, com.yucareux.townfolk.world.verbs.StorageVerb::runDeposit),
      def(new String[]{"withdraw"},         true,  false, com.yucareux.townfolk.world.verbs.StorageVerb::runWithdraw),
      def(new String[]{"peek", "inspect"},  true,  false, com.yucareux.townfolk.world.verbs.PeekVerb::run),
      def(new String[]{"attack", "defend", "flee"},
                                            false, false, com.yucareux.townfolk.world.verbs.ViolenceVerb::run),
      def(new String[]{"hand"},             false, false, com.yucareux.townfolk.world.verbs.HandVerb::run),
      def(new String[]{"eat"},              false, false, com.yucareux.townfolk.world.verbs.EatVerb::run),
      def(new String[]{"harvest"},          true,  false, com.yucareux.townfolk.world.verbs.HarvestVerb::run),
      def(new String[]{"plant"},            true,  false, com.yucareux.townfolk.world.verbs.PlantVerb::run),
      def(new String[]{"chop"},             true,  false, com.yucareux.townfolk.world.verbs.ChopVerb::run),
      def(new String[]{"mine"},             true,  false, com.yucareux.townfolk.world.verbs.MineVerb::run),
      def(new String[]{"place"},            true,  false, com.yucareux.townfolk.world.verbs.PlaceVerb::run),
      def(new String[]{"till"},             true,  false, com.yucareux.townfolk.world.verbs.TillVerb::run),
      def(new String[]{"shear"},            true,  false, com.yucareux.townfolk.world.verbs.LivestockVerb::runShear),
      def(new String[]{"milk"},             true,  false, com.yucareux.townfolk.world.verbs.LivestockVerb::runMilk),
      def(new String[]{"breed"},            true,  false, com.yucareux.townfolk.world.verbs.LivestockVerb::runBreed),
      def(new String[]{"feed"},             true,  false, com.yucareux.townfolk.world.verbs.FeedVerb::run),
      def(new String[]{"water"},            true,  false, com.yucareux.townfolk.world.verbs.WaterVerb::run),
      def(new String[]{"remember", "commit"},
                                            false, false, com.yucareux.townfolk.world.verbs.RememberVerb::run),
      def(new String[]{"reflex", "standing_order", "rule"},
                                            false, true,  com.yucareux.townfolk.world.verbs.ReflexVerb::run),
      def(new String[]{"forget_parcel", "drop_parcel", "unbind_parcel"},
                                            false, true,  com.yucareux.townfolk.world.verbs.ForgetParcelVerb::run),
      def(new String[]{"forget", "cancel_rule", "drop_reflex"},
                                            false, true,  com.yucareux.townfolk.world.verbs.ForgetVerb::run)
   );

   public static void execute(ServerLevel level, TownSquareBlockEntity town, Villager actor,
                              VillagerEntry self, String actionRaw) {
      String action = actionRaw.toLowerCase(Locale.ROOT).trim();
      VerboseLog.write("ACTION", "actor=" + self.name() + " raw=\"" + actionRaw + "\"", "");

      VerbDef matched = null;
      String matchedKw = null;
      for (VerbDef d : VERBS) {
         String kw = d.matchKeyword(action);
         if (kw != null) { matched = d; matchedKw = kw; break; }
      }

      try {
         if (matched == null) {
            VerboseLog.write("ACTION_RESULT", "actor=" + self.name() + " status=unknown_verb",
               "raw=\"" + actionRaw + "\"");
            town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
               self.name() + " unknown action: " + actionRaw);
         } else {
            // Pass the matched keyword (not the canonical first alias)
            // through to the handler + reflex fire so e.g. a player-
            // authored "after:standing_order" reflex still triggers when
            // the LLM emits "standing_order …" rather than "reflex …".
            String body = extractBody(action, matchedKw);
            matched.handler().run(new VerbContext(level, town, actor, self, matchedKw, body, actionRaw));
         }
      } catch (Exception e) {
         VerboseLog.write("ACTION_ERROR", "actor=" + self.name(),
            "raw=\"" + actionRaw + "\" error=" + e);
      }

      // Fire after:<verb> reflexes for inline (non-block-task) verbs.
      // Block-task verbs fire their own after-hook via BlockTaskQueue's
      // completion listener; reflex/forget are meta-verbs that mutate
      // the rule store and shouldn't trigger reflexes themselves.
      if (matched != null && !matched.isBlockTask() && !matched.suppressAfterReflex()) {
         // matchedKw is the keyword that actually matched (handles
         // multi-word like "stop following" as well as the firstWord
         // case ScheduleService used before this refactor).
         String afterKey = matchedKw.indexOf(' ') < 0
            ? matchedKw
            : matchedKw.substring(0, matchedKw.indexOf(' '));
         ReflexService.onAfterVerb(level, actor, afterKey);
      }
   }

   // firstWord / verbExact removed in 16b.1 — VerbDef.matchKeyword
   // now owns the strict-prefix + alias-match logic.

   // doGive (and the GIVE regex) moved to {@link com.yucareux.townfolk.world.verbs.GiveVerb}
   // (which uses {@link com.yucareux.townfolk.world.verbs.StorageHelpers#GIVE}) — stage 17a.1.

   // doFollow moved to {@link com.yucareux.townfolk.world.verbs.FollowVerb} (stage 16b.2.a).

   // doComplete moved to {@link com.yucareux.townfolk.world.verbs.CompleteVerb} (16b.2.b).
   // doClaimHome moved to {@link com.yucareux.townfolk.world.verbs.ClaimHomeVerb} (16b.2.b).
   // fuzzyOverlap + findBlock now live on
   // {@link com.yucareux.townfolk.world.verbs.VerbUtils}.

   // doForgetParcel moved to {@link com.yucareux.townfolk.world.verbs.ForgetParcelVerb} (16b.2.b).
   // Stub kept here to avoid breaking the file until a clean delete.
   // doCraft moved to {@link com.yucareux.townfolk.world.verbs.CraftVerb} (17a.2).

   // All do* methods + private helpers (resolveItemFlexibly, takeItem, countItem,
   // shortName, hasItem, findClosest/Pos, breakAndCollect, enqueueBlockTask,
   // containerRefFor, hasToolWithTag, playContainerSound, addToContainer, and
   // patterns GIVE/QTY_ITEM/HAND_PATTERN) now live in
   // {@link com.yucareux.townfolk.world.verbs.StorageHelpers},
   // {@link com.yucareux.townfolk.world.verbs.BlockTaskHelpers}, and the
   // individual *Verb files. Stage 17a.5 finished the split — only the
   // dispatch shell + {@link #log} remain here.

   /** Pkg-private so verb-handler classes in {@code world.verbs.*}
    *  can write through the same log channel. */
   static void log(ServerLevel level, TownSquareBlockEntity town, VillagerEntry self, String msg) {
      VerboseLog.write("ACTION_RESULT", "actor=" + self.name(), msg);
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, self.name() + " " + msg);
   }

   private ToolDispatcher() {}
}
