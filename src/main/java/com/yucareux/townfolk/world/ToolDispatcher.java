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
      def(new String[]{"deposit"},          true,  false, ctx -> doStorage(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body, true)),
      def(new String[]{"withdraw"},         true,  false, ctx -> doStorage(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body, false)),
      def(new String[]{"peek", "inspect"},  true,  false, ctx -> doPeekNearest(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"attack", "defend", "flee"},
                                            false, false, com.yucareux.townfolk.world.verbs.ViolenceVerb::run),
      def(new String[]{"hand"},             false, false, com.yucareux.townfolk.world.verbs.HandVerb::run),
      def(new String[]{"eat"},              false, false, com.yucareux.townfolk.world.verbs.EatVerb::run),
      def(new String[]{"harvest"},          true,  false, ctx -> doHarvest(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"plant"},            true,  false, ctx -> doPlant(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"chop"},             true,  false, ctx -> doChop(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"mine"},             true,  false, ctx -> doMine(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"place"},            true,  false, ctx -> doPlace(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"till"},             true,  false, ctx -> doTill(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"shear"},            true,  false, ctx -> doLivestock(ctx.level, ctx.town, ctx.actor, ctx.self, "shear", ctx.body)),
      def(new String[]{"milk"},             true,  false, ctx -> doLivestock(ctx.level, ctx.town, ctx.actor, ctx.self, "milk", ctx.body)),
      def(new String[]{"breed"},            true,  false, ctx -> doLivestock(ctx.level, ctx.town, ctx.actor, ctx.self, "breed", ctx.body)),
      def(new String[]{"feed"},             true,  false, ctx -> doFeed(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
      def(new String[]{"water"},            true,  false, ctx -> doWater(ctx.level, ctx.town, ctx.actor, ctx.self, ctx.body)),
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

   private static int countItem(net.minecraft.world.SimpleContainer inv, String itemId) {
      ResourceLocation id = ResourceLocation.parse(itemId);
      Item target = BuiltInRegistries.ITEM.get(id);
      int n = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() == target) n += s.getCount();
      }
      return n;
   }

   /** Resolve an LLM-provided item token (may or may not have a
    *  namespace) to an actual {@link Item}. Falls back to a registry
    *  scan when the namespaced lookup hits AIR — necessary because the
    *  LLM frequently writes unprefixed modded paths (e.g.
    *  {@code "cabbage_seeds"} instead of {@code "farmersdelight:cabbage_seeds"}),
    *  and {@code BuiltInRegistries.ITEM} is a DefaultedRegistry whose
    *  missing-key sentinel is {@link Items#AIR}, not null.
    *
    *  <h2>Caching</h2>
    *  The fallback registry scan is O(n) over every loaded item — with
    *  30+ mods that's tens of thousands of entries. We cache hits AND
    *  misses against the lowercased token to make repeat lookups O(1).
    *  Cache is bounded only by the set of distinct tokens the LLM has
    *  ever emitted, which is small in practice.
    *
    *  Returns null if the token genuinely doesn't match any item. */
   private static final java.util.concurrent.ConcurrentHashMap<String, Item> ITEM_TOKEN_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();
   private static final Item ITEM_MISS_SENTINEL = net.minecraft.world.item.Items.AIR;

   static Item resolveItemFlexibly(String itemTok) {
      if (itemTok == null || itemTok.isBlank()) return null;
      String tok = itemTok.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
      Item cached = ITEM_TOKEN_CACHE.get(tok);
      if (cached != null) {
         return cached == ITEM_MISS_SENTINEL && !"air".equals(tok) ? null : cached;
      }
      ResourceLocation id = ResourceLocation.tryParse(tok.contains(":") ? tok : "minecraft:" + tok);
      if (id != null) {
         Item item = BuiltInRegistries.ITEM.get(id);
         if (item != null && (item != net.minecraft.world.item.Items.AIR || "air".equals(tok))) {
            ITEM_TOKEN_CACHE.put(tok, item);
            return item;
         }
      }
      // Fallback: scan registry for a path match. First hit wins.
      String bare = tok.contains(":") ? tok.substring(tok.indexOf(':') + 1) : tok;
      for (var entry : BuiltInRegistries.ITEM.entrySet()) {
         if (entry.getKey().location().getPath().equals(bare)) {
            ITEM_TOKEN_CACHE.put(tok, entry.getValue());
            return entry.getValue();
         }
      }
      // Cache the miss too — repeat lookups on the same bad token (e.g.
      // a stuck SeedNeed firing "withdraw 32 pota" every tick before
      // the regex bug was fixed) should be O(1).
      ITEM_TOKEN_CACHE.put(tok, ITEM_MISS_SENTINEL);
      return null;
   }

   private static void takeItem(net.minecraft.world.SimpleContainer inv, String itemId, int count) {
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      int need = count;
      for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() != target) continue;
         int take = Math.min(s.getCount(), need);
         s.shrink(take);
         need -= take;
         if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
      }
   }

   private static String shortName(String itemId) {
      int colon = itemId.indexOf(':');
      return (colon < 0 ? itemId : itemId.substring(colon + 1)).replace('_', ' ');
   }

   // ───── deposit / withdraw / peek nearby container ─────

   /** Body looks like "5 wheat" or "wheat" (default count 1) or "all wheat".
    *
    *  No trailing in/from/to alternation here on purpose — the label-hint
    *  extractor strips that suffix before the regex sees the body. Having
    *  the alternation in the pattern lets non-greedy backtracking steal
    *  the last two characters of item names ending in "to", "in", or
    *  "from" (so "potato" → group(2)="pota", with "to" consumed by the
    *  alternation). Took half a session to track that one down. */
   private static final Pattern QTY_ITEM = Pattern.compile(
      "(?:(\\d+|all)\\s+)?([\\w' ]+?)\\s*$", Pattern.CASE_INSENSITIVE);

   private static void doStorage(ServerLevel level, TownSquareBlockEntity town,
                                 Villager actor, VillagerEntry self, String body, boolean depositing) {
      String b = body.replaceFirst("^[:\\s]+", "").trim();
      // Trailing " in <name>" / " from <name>" / " to <name>" is the
      // optional barrel-label hint — preserve it so we can route to
      // the labelled barrel by name instead of generic item-nearest.
      String labelHint = "";
      int inIdx = b.toLowerCase(Locale.ROOT).indexOf(" in ");
      int fromIdx = b.toLowerCase(Locale.ROOT).indexOf(" from ");
      int toIdx = b.toLowerCase(Locale.ROOT).indexOf(" to ");
      int cut = inIdx;
      int cutLen = 4;       // length of " in "
      if (fromIdx >= 0 && (cut < 0 || fromIdx < cut)) { cut = fromIdx; cutLen = 6; }
      if (toIdx   >= 0 && (cut < 0 || toIdx   < cut)) { cut = toIdx;   cutLen = 4; }
      if (cut >= 0) {
         labelHint = b.substring(cut + cutLen).trim();
         b = b.substring(0, cut).trim();
      }
      Matcher m = QTY_ITEM.matcher(b);
      if (!m.matches()) {
         VerboseLog.write("ACTION_RESULT", "actor=" + self.name() + " status=storage_malformed",
            "body=\"" + body + "\"");
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            (depositing ? "deposit" : "withdraw") + " — malformed (\"" + body + "\")");
         return;
      }
      String qtyTok = m.group(1);
      String itemTok = m.group(2).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
      int qty = "all".equalsIgnoreCase(qtyTok) ? Integer.MAX_VALUE
              : qtyTok == null ? 1
              : Math.max(1, Math.min(256, Integer.parseInt(qtyTok)));
      // Namespace-aware item resolution — see resolveItemFlexibly().
      // The LLM frequently writes unprefixed modded paths (e.g.
      // "cabbage_seeds" without "farmersdelight:") because WorldSense
      // surfaces them namespace-less; the resolver does the
      // registry-scan fallback so they still match.
      Item item = resolveItemFlexibly(itemTok);
      ResourceLocation itemId = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
      // Diagnostic: every doStorage entry now logs its full parsed
      // state. Without this, silent returns from any of the early-out
      // branches (unknown item, missing label, etc.) leave the verbose
      // log with just an ACTION line and no follow-up — exactly the
      // situation that hid the cabbage/potato bug.
      VerboseLog.write("STORAGE_DISPATCH",
         "actor=" + self.name() + " verb=" + (depositing ? "deposit" : "withdraw")
            + " qty=" + qty + " item=" + itemId
            + " labelHint=\"" + labelHint + "\"",
         "body=\"" + body + "\"");
      if (item == null) {
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
            self.name() + " doesn't recognise item: " + itemTok);
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            (depositing ? "deposit" : "withdraw") + " — unknown item \"" + itemTok + "\"");
         VerboseLog.write("STORAGE_REJECT",
            "actor=" + self.name() + " reason=unknown-item itemTok=" + itemTok, "");
         return;
      }

      // EXPLICIT BARREL-BY-NAME routing. If the LLM said "...in <name>"
      // or "...from <name>" or "...to <name>", and that resolves to a
      // labelled registered container, target THAT barrel specifically
      // — bypassing the nearest-with-item search. This is a player
      // override channel: dialogue like "put the wool in the wool
      // stash" should route to the stash even if a closer barrel
      // already holds wool.
      com.yucareux.townfolk.town.StorageIndex.Hit hit = null;
      // Hoisted out of the labelHint block so the out-of-range routing
      // branch can use it as the walk target without persistent-data
      // round-trips. (Earlier versions stashed labelPos on the actor's
      // persistent data, which leaked across calls when the in-range
      // path consumed labelPos but never cleared the tag — see audit
      // item I.)
      BlockPos labelPos = null;
      if (!labelHint.isEmpty()) {
         labelPos = com.yucareux.townfolk.town.StorageRegistry.findByLabel(
            level, labelHint, actor.blockPosition());
         VerboseLog.write("STORAGE_LABEL_LOOKUP",
            "actor=" + self.name() + " hint=\"" + labelHint + "\""
               + " result=" + (labelPos == null ? "null" : labelPos.toShortString()), "");
         if (labelPos == null) {
            ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
               (depositing ? "deposit" : "withdraw")
                  + " — no barrel named \"" + labelHint + "\"");
            VerboseLog.write("STORAGE_REJECT",
               "actor=" + self.name() + " reason=no-such-label hint=\"" + labelHint + "\"", "");
            return;
         }
         // Materialize a Hit if the named barrel is in reach AND a real
         // container; otherwise we fall through to the routing branch
         // below, which will queue a walk to labelPos.
         double dsq = labelPos.distSqr(actor.blockPosition());
         if (dsq <= 6 * 6) {
            // Capability-driven lookup so mod storage (Sophisticated /
            // Create / etc.) resolves via the same path as vanilla.
            var c = com.yucareux.townfolk.town.ContainerAdapters.at(level, labelPos);
            VerboseLog.write("STORAGE_LABEL_INRANGE",
               "actor=" + self.name() + " labelPos=" + labelPos.toShortString()
                  + " distSq=" + String.format(java.util.Locale.ROOT, "%.2f", dsq)
                  + " hasHandler=" + (c != null),
               "");
            if (c != null) {
               hit = new com.yucareux.townfolk.town.StorageIndex.Hit(labelPos, c);
            }
         } else {
            VerboseLog.write("STORAGE_LABEL_OUTRANGE",
               "actor=" + self.name() + " labelPos=" + labelPos.toShortString()
                  + " distSq=" + String.format(java.util.Locale.ROOT, "%.2f", dsq),
               "");
         }
      }

      // Find the nearest USABLE barrel within range — not just the
      // nearest. "Usable" = registered + (deposit ? filter accepts :
      // contains the item). Without the predicate, a closer empty
      // barrel would always win over a slightly-further loaded one,
      // and treasury routing would ping-pong the villager between the
      // two on every arrival.
      final boolean depositingFinalForPred = depositing;
      final Item itemFinal = item;
      if (hit == null) hit = com.yucareux.townfolk.town.StorageIndex.nearestMatching(
         level, actor.blockPosition(), 6,
         candidate -> {
            var cfg = com.yucareux.townfolk.town.StorageRegistry.find(level, candidate.pos());
            if (cfg == null) return false;
            if (depositingFinalForPred) return cfg.acceptsDeposit(new ItemStack(itemFinal));
            // Withdraw: only usable if the container holds the item.
            var cc = candidate.container();
            for (int i = 0; i < cc.getContainerSize(); i++) {
               if (cc.getItem(i).getItem() == itemFinal) return true;
            }
            return false;
         });
      boolean inRangeUsable = hit != null;
      com.yucareux.townfolk.town.StorageConfig storageCfg =
         hit == null ? null : com.yucareux.townfolk.town.StorageRegistry.find(level, hit.pos());

      if (!inRangeUsable) {
         // Diagnostic: log the GEOMETRIC nearest (if any) and the
         // reason it was rejected, so we can see exactly which barrel
         // the predicate skipped.
         var fallbackHit = com.yucareux.townfolk.town.StorageIndex.nearest(level, actor.blockPosition(), 6);
         if (fallbackHit != null) {
            var cfg = com.yucareux.townfolk.town.StorageRegistry.find(level, fallbackHit.pos());
            String reason;
            if (cfg == null) reason = "unregistered";
            else if (depositing) reason = "filter-rejects";
            else reason = "no-item";
            VerboseLog.write("STORAGE_NEAREST_UNUSABLE",
               "actor=" + self.name() + " pos=" + fallbackHit.pos().toShortString()
                  + " item=" + itemId + " verb=" + (depositing ? "deposit" : "withdraw")
                  + " reason=" + reason,
               "no usable barrel within 6 blocks — falling through to treasury routing");
         }
      }

      if (!inRangeUsable) {
         // Out-of-range routing. If the LLM specified a named barrel
         // ("...in wool stash"), walk there explicitly — bypassing
         // the item-driven treasury search. Otherwise fall back to
         // treasury picking the nearest barrel that accepts/has the
         // item.
         BlockPos containerPos;
         if (labelPos != null) {
            // The LLM specified a named barrel and we found it — walk
            // there even though it's out of reach. labelPos came from
            // the labelHint parse at the top of this method, so it's
            // always in sync with the verb body (no stale-tag bug).
            containerPos = labelPos;
            VerboseLog.write("STORAGE_NAVIGATE_BY_LABEL",
               "actor=" + self.name() + " pos=" + containerPos.toShortString()
                  + " hint=\"" + labelHint + "\"", "");
         } else {
            var target = depositing
               ? com.yucareux.townfolk.town.TownTreasury.findNearestForDeposit(level, actor.blockPosition(), item)
               : com.yucareux.townfolk.town.TownTreasury.findNearestWith(level, actor.blockPosition(), item);
            if (target.isEmpty()) {
               ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
                  (depositing ? "deposit" : "withdraw") + " — no registered container in town"
                     + (depositing ? " accepts " + shortName(itemId.toString())
                                   : " has " + shortName(itemId.toString())));
               return;
            }
            containerPos = target.get().pos();
         }
         final String verbBody = body;
         final boolean depositingFinal = depositing;
         BlockTaskQueue.enqueue(level, actor, new BlockTaskQueue.BlockTask(
            actor.getUUID(), containerPos,
            level.getGameTime() + 20L * 60,
            depositingFinal ? "deposit" : "withdraw",
            (lvl, v, p) -> {
               doStorage(lvl, town, v, self, verbBody, depositingFinal);
               return (depositingFinal ? "delivered " : "fetched ")
                  + shortName(itemId.toString()) + " at " + p.toShortString();
            }));
         return;
      }

      // In-range barrel IS usable.
      net.minecraft.world.Container container = hit.container();
      BlockPos cpos = hit.pos();

      // Pre-check: if there's literally nothing to move, bail without
      // opening the container. Stops a "deposit all wheat" reflex from
      // playing barrel-open sounds + spamming the town log every harvest
      // when the villager has zero wheat in hand. ActionFeedback still
      // records the no-op so the LLM can see it; everything else stays
      // quiet.
      var inv = actor.getInventory();
      int available;
      if (depositing) {
         available = 0;
         for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() == item) available += s.getCount();
         }
      } else {
         available = 0;
         for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.getItem() == item) available += s.getCount();
         }
      }
      if (available <= 0) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            (depositing ? "deposit " : "withdraw ") + shortName(itemId.toString())
               + (depositing ? " — none in your bag" : " — none in the container"));
         VerboseLog.write("ACTION_RESULT", "actor=" + self.name() + " status="
            + (depositing ? "deposit_none" : "withdraw_none"),
            "item=" + itemId.toString());
         return;
      }

      playContainerSound(level, cpos, true);

      int moved = 0;
      if (depositing) {
         // Villager → container.
         int want = qty;
         for (int i = 0; i < inv.getContainerSize() && want > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(s.getCount(), want);
            ItemStack toMove = s.copy();
            toMove.setCount(take);
            ItemStack leftover = addToContainer(container, toMove);
            int placed = take - (leftover == null ? 0 : leftover.getCount());
            s.shrink(placed);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            moved += placed;
            want -= placed;
            if (leftover != null && !leftover.isEmpty()) break;   // container full
         }
      } else {
         // Container → villager.
         int want = qty;
         for (int i = 0; i < container.getContainerSize() && want > 0; i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(s.getCount(), want);
            ItemStack toMove = s.copy();
            toMove.setCount(take);
            ItemStack leftover = inv.addItem(toMove);
            int placed = take - (leftover == null ? 0 : leftover.getCount());
            s.shrink(placed);
            if (s.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            moved += placed;
            want -= placed;
            if (leftover != null && !leftover.isEmpty()) break;   // villager inv full
         }
      }
      container.setChanged();
      playContainerSound(level, cpos, false);

      // Update the storage ledger and write memory.
      com.yucareux.townfolk.town.StorageRegistry.touch(level, cpos, self.name(), level.getGameTime());
      long day = level.getGameTime() / 24000L;
      String verb = depositing ? "deposited" : "withdrew";
      String msg = self.name() + " " + verb + " " + moved + "× " + shortName(itemId.toString())
         + " " + (depositing ? "into" : "from") + " container at " + cpos.toShortString();
      VerboseLog.write("ACTION_RESULT", "actor=" + self.name() + " status=" + verb, msg);
      if (moved > 0) {
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, msg);
      }
      if (moved > 0) {
         // Include the player-set label (if any) so the LLM can later
         // refer to the barrel by name in dialogue / reasoning.
         String containerRef = containerRefFor(level, cpos);
         com.yucareux.townfolk.villager.MemoryStore.write(actor, "storage", day,
            "I " + verb + " " + moved + "× " + shortName(itemId.toString())
            + " " + (depositing ? "into" : "from") + " " + containerRef + ".");
         ActionFeedback.recordOk(actor.getUUID(), level.getGameTime(),
            verb + " " + moved + "× " + shortName(itemId.toString())
            + " " + (depositing ? "into" : "from") + " " + containerRef);
      } else {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            verb + " " + shortName(itemId.toString())
            + (depositing ? " — none of that in your inventory" : " — none of that in the container"));
      }
   }

   /** Open and immediately close a container to refresh the ledger
    *  (without moving items). The LLM uses this to "check" a barrel.
    *
    *  If {@code hint} resolves to a labelled barrel via
    *  {@link com.yucareux.townfolk.town.StorageRegistry#findByLabel},
    *  that specific barrel is the target — and if the villager is out
    *  of reach, a walk is queued so they peek the named barrel on
    *  arrival. Without a hint, falls back to the nearest container
    *  within 6 blocks (the original behaviour). */
   private static void doPeekNearest(ServerLevel level, TownSquareBlockEntity town,
                                     Villager actor, VillagerEntry self, String hint) {
      com.yucareux.townfolk.town.StorageIndex.Hit hit = null;
      if (hint != null && !hint.isBlank()) {
         BlockPos labelPos = com.yucareux.townfolk.town.StorageRegistry.findByLabel(
            level, hint, actor.blockPosition());
         if (labelPos != null) {
            if (labelPos.distSqr(actor.blockPosition()) <= 6 * 6) {
               var c = com.yucareux.townfolk.town.ContainerAdapters.at(level, labelPos);
               if (c != null) {
                  hit = new com.yucareux.townfolk.town.StorageIndex.Hit(labelPos, c);
               }
            } else {
               // Out of reach — walk to the named barrel, then re-fire peek.
               final BlockPos lpFinal = labelPos;
               BlockTaskQueue.enqueue(level, actor, new BlockTaskQueue.BlockTask(
                  actor.getUUID(), lpFinal,
                  level.getGameTime() + 20L * 60, "peek",
                  (lvl, v, p) -> {
                     doPeekNearest(lvl, town, v, self, hint);
                     return "peeked the named barrel at " + p.toShortString();
                  }));
               VerboseLog.write("STORAGE_NAVIGATE_BY_LABEL",
                  "actor=" + self.name() + " verb=peek pos=" + lpFinal.toShortString()
                     + " hint=\"" + hint + "\"", "");
               return;
            }
         }
      }
      if (hit == null) hit = com.yucareux.townfolk.town.StorageIndex.nearest(level, actor.blockPosition(), 6);
      if (hit == null) {
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
            self.name() + " peers around but sees no container");
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "peek — no container within 6 blocks"
               + (hint != null && !hint.isBlank() ? " (no labelled \"" + hint + "\" either)" : ""));
         return;
      }
      net.minecraft.world.Container container = hit.container();
      BlockPos cpos = hit.pos();
      playContainerSound(level, cpos, true);
      playContainerSound(level, cpos, false);
      com.yucareux.townfolk.town.StorageRegistry.touch(level, cpos, self.name(), level.getGameTime());
      long day = level.getGameTime() / 24000L;
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
         self.name() + " inspected container at " + cpos.toShortString());
      com.yucareux.townfolk.villager.MemoryStore.write(actor, "storage", day,
         "I peeked into " + containerRefFor(level, cpos) + ".");
      // Inline contents summary so the feedback line itself answers "what's in there".
      StringBuilder peekSummary = new StringBuilder("peeked container at ");
      peekSummary.append(cpos.toShortString()).append(": ");
      boolean any = false;
      java.util.LinkedHashMap<String, Integer> merged = new java.util.LinkedHashMap<>();
      for (int i = 0; i < container.getContainerSize(); i++) {
         ItemStack s = container.getItem(i);
         if (s.isEmpty()) continue;
         merged.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
      }
      for (var en : merged.entrySet()) {
         if (any) peekSummary.append(", ");
         peekSummary.append(en.getValue()).append("× ").append(en.getKey());
         any = true;
      }
      if (!any) peekSummary.append("empty");
      ActionFeedback.recordOk(actor.getUUID(), level.getGameTime(), peekSummary.toString());
   }

   /** Play the lid-open or lid-close sound matching the block at {@code pos}.
    *  Falls back to the generic chest sound if the block type isn't one of the
    *  recognised lidded containers. */
   private static void playContainerSound(ServerLevel level, BlockPos pos, boolean opening) {
      var block = level.getBlockState(pos).getBlock();
      net.minecraft.sounds.SoundEvent sound;
      if (block == net.minecraft.world.level.block.Blocks.BARREL) {
         sound = opening ? net.minecraft.sounds.SoundEvents.BARREL_OPEN
                         : net.minecraft.sounds.SoundEvents.BARREL_CLOSE;
      } else if (block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
         sound = opening ? net.minecraft.sounds.SoundEvents.SHULKER_BOX_OPEN
                         : net.minecraft.sounds.SoundEvents.SHULKER_BOX_CLOSE;
      } else if (block == net.minecraft.world.level.block.Blocks.ENDER_CHEST) {
         sound = opening ? net.minecraft.sounds.SoundEvents.ENDER_CHEST_OPEN
                         : net.minecraft.sounds.SoundEvents.ENDER_CHEST_CLOSE;
      } else {
         sound = opening ? net.minecraft.sounds.SoundEvents.CHEST_OPEN
                         : net.minecraft.sounds.SoundEvents.CHEST_CLOSE;
      }
      level.playSound(null, pos, sound, net.minecraft.sounds.SoundSource.BLOCKS,
         0.5f, level.getRandom().nextFloat() * 0.1f + 0.9f);
   }

   // Storage proximity scans now live in {@link com.yucareux.townfolk.town.StorageIndex}.
   // ToolDispatcher.doStorage uses StorageIndex.nearest directly.

   private static ItemStack addToContainer(net.minecraft.world.Container container, ItemStack stack) {
      ItemStack rem = stack.copy();
      // First, merge into existing matching stacks.
      for (int i = 0; i < container.getContainerSize() && !rem.isEmpty(); i++) {
         ItemStack s = container.getItem(i);
         if (s.isEmpty()) continue;
         if (!ItemStack.isSameItemSameComponents(s, rem)) continue;
         int max = Math.min(s.getMaxStackSize(), container.getMaxStackSize());
         int free = max - s.getCount();
         if (free <= 0) continue;
         int put = Math.min(free, rem.getCount());
         s.grow(put);
         rem.shrink(put);
      }
      // Then place into empty slots.
      for (int i = 0; i < container.getContainerSize() && !rem.isEmpty(); i++) {
         ItemStack s = container.getItem(i);
         if (!s.isEmpty()) continue;
         ItemStack copy = rem.copy();
         int max = Math.min(rem.getMaxStackSize(), container.getMaxStackSize());
         copy.setCount(Math.min(max, rem.getCount()));
         container.setItem(i, copy);
         rem.shrink(copy.getCount());
      }
      return rem;
   }

   // doHand (and HAND_PATTERN) moved to {@link com.yucareux.townfolk.world.verbs.HandVerb} (17a.1).


   // doEat moved to {@link com.yucareux.townfolk.world.verbs.EatVerb} (17a.2).

   // ───── block-task verbs: harvest / plant / chop / mine / place ─────

   private static final int BLOCK_TASK_TIMEOUT_TICKS = 20 * 30;     // 30 s

   /** Find the closest block in {@code radius} matching {@code pred}. Returns
    *  null if none. Uses a flat AABB scan — fine for radius ≤ 12. */
   private static BlockPos findClosest(ServerLevel level, BlockPos centre, int radius,
                                       java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> pred) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               if (!pred.test(level.getBlockState(cur))) continue;
               double d = cur.distSqr(centre);
               if (d < bestDist) { bestDist = d; best = cur.immutable(); }
            }
         }
      }
      return best;
   }

   /** Break a block, write its drops into the villager's inventory (overflow
    *  drops at the actor), and return a concise summary string. If the broken
    *  block sat on farmland AND the villager has a seed, auto-replant — keeps
    *  the harvest loop continuous instead of leaving fallow rows. */
   private static String breakAndCollect(ServerLevel level, Villager actor, BlockPos pos, String verb) {
      var state = level.getBlockState(pos);
      boolean wasOnFarmland = level.getBlockState(pos.below())
         .is(net.minecraft.world.level.block.Blocks.FARMLAND);
      var drops = net.minecraft.world.level.block.Block.getDrops(state, level, pos, null, actor, actor.getMainHandItem());
      level.destroyBlock(pos, false, actor);
      int totalCount = 0;
      String firstName = null;
      var inv = actor.getInventory();
      for (var drop : drops) {
         if (drop.isEmpty()) continue;
         if (firstName == null)
            firstName = shortName(BuiltInRegistries.ITEM.getKey(drop.getItem()).toString());
         totalCount += drop.getCount();
         ItemStack leftover = inv.addItem(drop);
         if (leftover != null && !leftover.isEmpty()) {
            var entity = new net.minecraft.world.entity.item.ItemEntity(
               level, actor.getX(), actor.getY(), actor.getZ(), leftover);
            entity.setPickUpDelay(20);
            level.addFreshEntity(entity);
         }
      }
      if (firstName == null) firstName = state.getBlock().getDescriptionId();

      String replantNote = "";
      if (wasOnFarmland && level.getBlockState(pos).isAir()) {
         // Find a seed in inventory (any BlockItem placing a CropBlock).
         var inv2 = actor.getInventory();
         for (int i = 0; i < inv2.getContainerSize(); i++) {
            var s = inv2.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof net.minecraft.world.item.BlockItem bi
                && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
               level.setBlockAndUpdate(pos, bi.getBlock().defaultBlockState());
               s.shrink(1);
               if (s.isEmpty()) inv2.setItem(i, ItemStack.EMPTY);
               replantNote = " + replanted " + shortName(BuiltInRegistries.ITEM.getKey(bi.asItem()).toString());
               break;
            }
         }
      }
      return verb + " " + totalCount + "× " + firstName + " from " + pos.toShortString() + replantNote;
   }

   private static void doHarvest(ServerLevel level, TownSquareBlockEntity town,
                                 Villager actor, VillagerEntry self, String hint) {
      String h = hint.toLowerCase(Locale.ROOT);
      // Crops: wheat, beetroot, potato, carrot are CropBlock; nether wart is NetherWartBlock.
      BlockPos target = findClosest(level, actor.blockPosition(), 8, state -> {
         var b = state.getBlock();
         if (b instanceof net.minecraft.world.level.block.CropBlock crop) {
            if (!h.isEmpty() && !state.toString().toLowerCase(Locale.ROOT).contains(h)) return false;
            return crop.isMaxAge(state);
         }
         if (b instanceof net.minecraft.world.level.block.NetherWartBlock) {
            return state.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) == 3;
         }
         return false;
      });
      if (target == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "harvest — no ripe crop within 8 blocks" + (h.isEmpty() ? "" : " matching \"" + hint + "\""));
         return;
      }
      enqueueBlockTask(level, actor, target, "harvest", (lvl, v, pos) -> breakAndCollect(lvl, v, pos, "harvested"));
   }

   private static void doPlant(ServerLevel level, TownSquareBlockEntity town,
                               Villager actor, VillagerEntry self, String hint) {
      // The "seed" — if given, otherwise infer from inventory.
      var inv = actor.getInventory();
      Item seed = hint.isEmpty() ? null : RecipeCatalog.resolveItemOpt(hint).orElse(null);
      if (seed == null) {
         // Auto-pick first viable seed in inventory.
         for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof net.minecraft.world.item.BlockItem bi
                && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
               seed = s.getItem(); break;
            }
         }
      }
      if (seed == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "plant — no seeds in your bag");
         return;
      }
      // Find tilled farmland with air above.
      Item seedF = seed;
      BlockPos target = findClosest(level, actor.blockPosition(), 8, state -> {
         if (!state.is(net.minecraft.world.level.block.Blocks.FARMLAND)) return false;
         return true;   // we check "air above" at execute time
      });
      if (target == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "plant — no tilled farmland within 8 blocks");
         return;
      }
      BlockPos plantAt = target.above();
      enqueueBlockTask(level, actor, target, "plant", (lvl, v, pos) -> {
         if (!lvl.getBlockState(plantAt).isAir()) {
            throw new RuntimeException("farmland above is occupied at " + plantAt.toShortString());
         }
         // Find the seed in inventory and place its block.
         int slot = -1;
         var inv2 = v.getInventory();
         for (int i = 0; i < inv2.getContainerSize(); i++) {
            if (inv2.getItem(i).getItem() == seedF) { slot = i; break; }
         }
         if (slot < 0) throw new RuntimeException("seeds gone before planting");
         var stack = inv2.getItem(slot);
         if (seedF instanceof net.minecraft.world.item.BlockItem bi) {
            lvl.setBlockAndUpdate(plantAt, bi.getBlock().defaultBlockState());
            stack.shrink(1);
            if (stack.isEmpty()) inv2.setItem(slot, ItemStack.EMPTY);
            return "planted " + shortName(BuiltInRegistries.ITEM.getKey(seedF).toString())
               + " at " + plantAt.toShortString();
         }
         throw new RuntimeException("item " + seedF + " is not a placeable block");
      });
   }

   private static void doChop(ServerLevel level, TownSquareBlockEntity town,
                              Villager actor, VillagerEntry self, String hint) {
      // Require an axe in inventory.
      if (!hasToolWithTag(actor, net.minecraft.tags.ItemTags.AXES)) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "chop — I need an axe to cut wood");
         return;
      }
      String h = hint.toLowerCase(Locale.ROOT);
      BlockPos target = findClosest(level, actor.blockPosition(), 10, state -> {
         if (!state.is(net.minecraft.tags.BlockTags.LOGS)) return false;
         if (h.isEmpty()) return true;
         var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
         return id != null && id.getPath().contains(h);
      });
      if (target == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "chop — no matching logs within 10 blocks");
         return;
      }
      enqueueBlockTask(level, actor, target, "chop", (lvl, v, pos) -> breakAndCollect(lvl, v, pos, "chopped"));
   }

   private static void doMine(ServerLevel level, TownSquareBlockEntity town,
                              Villager actor, VillagerEntry self, String hint) {
      if (!hasToolWithTag(actor, net.minecraft.tags.ItemTags.PICKAXES)) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "mine — I need a pickaxe to mine that");
         return;
      }
      String h = hint.toLowerCase(Locale.ROOT);
      BlockPos target = findClosest(level, actor.blockPosition(), 10, state -> {
         if (!state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) return false;
         if (h.isEmpty()) return true;
         var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
         return id != null && id.getPath().contains(h);
      });
      if (target == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "mine — no matching ore/stone within 10 blocks");
         return;
      }
      enqueueBlockTask(level, actor, target, "mine", (lvl, v, pos) -> breakAndCollect(lvl, v, pos, "mined"));
   }

   private static void doPlace(ServerLevel level, TownSquareBlockEntity town,
                               Villager actor, VillagerEntry self, String hint) {
      String h = hint.toLowerCase(Locale.ROOT);
      // Resolve the block: explicit hint, or first BlockItem in inventory.
      Item item = h.isEmpty() ? null : RecipeCatalog.resolveItemOpt(h).orElse(null);
      var inv = actor.getInventory();
      if (item == null) {
         for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BlockItem) {
               item = s.getItem(); break;
            }
         }
      }
      if (item == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "place — nothing placeable in your bag");
         return;
      }
      if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "place — " + h + " isn't a placeable block");
         return;
      }
      // Place adjacent to the villager in their facing direction, on a solid block.
      net.minecraft.core.Direction facing = actor.getDirection();
      BlockPos infront = actor.blockPosition().relative(facing);
      // Search for an air slot up/down from infront.
      BlockPos placeAt = null;
      for (int dy : new int[]{0, 1, -1, 2}) {
         BlockPos test = infront.offset(0, dy, 0);
         if (level.getBlockState(test).isAir()
             && !level.getBlockState(test.below()).isAir()) {
            placeAt = test; break;
         }
      }
      if (placeAt == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "place — no clear space in front of me to put it");
         return;
      }
      final BlockPos finalPlaceAt = placeAt;
      final Item finalItem = item;
      enqueueBlockTask(level, actor, infront, "place", (lvl, v, pos) -> {
         var inv2 = v.getInventory();
         int slot = -1;
         for (int i = 0; i < inv2.getContainerSize(); i++) {
            if (inv2.getItem(i).getItem() == finalItem) { slot = i; break; }
         }
         if (slot < 0) throw new RuntimeException("block gone from inventory before placing");
         lvl.setBlockAndUpdate(finalPlaceAt, blockItem.getBlock().defaultBlockState());
         var stack = inv2.getItem(slot);
         stack.shrink(1);
         if (stack.isEmpty()) inv2.setItem(slot, ItemStack.EMPTY);
         return "placed " + shortName(BuiltInRegistries.ITEM.getKey(finalItem).toString())
            + " at " + finalPlaceAt.toShortString();
      });
   }

   // ───── till — turn dirt/grass into farmland (requires hoe) ─────

   private static void doTill(ServerLevel level, TownSquareBlockEntity town,
                              Villager actor, VillagerEntry self, String hint) {
      if (!hasToolWithTag(actor, net.minecraft.tags.ItemTags.HOES)) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "till — I need a hoe to break up the soil");
         return;
      }
      // findClosest's predicate sees state only, not pos — but we need to
      // check the block above for air to ensure a placeable surface, so use
      // the positional variant.
      BlockPos target = findClosestPos(level, actor.blockPosition(), 8, pos -> {
         var s = level.getBlockState(pos);
         if (!s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
             && !s.is(net.minecraft.world.level.block.Blocks.DIRT)
             && !s.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)) return false;
         return level.getBlockState(pos.above()).isAir();
      });
      if (target == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "till — no tillable soil within 8 blocks");
         return;
      }
      enqueueBlockTask(level, actor, target, "till", (lvl, v, pos) -> {
         lvl.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState());
         return "tilled the soil at " + pos.toShortString();
      });
   }

   // ───── water — break a block adjacent to farmland and place a water source ─────

   private static void doWater(ServerLevel level, TownSquareBlockEntity town,
                               Villager actor, VillagerEntry self, String hint) {
      // Find dry farmland in range. Vanilla farmland.moisture == 0 means
      // bone-dry; higher means hydrated. We treat anything below max as "dry"
      // since we still want to top it up.
      BlockPos farmland = findClosestPos(level, actor.blockPosition(), 8, pos -> {
         var s = level.getBlockState(pos);
         if (!s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) return false;
         // Already hydrated? Skip — natural source nearby or recently watered.
         return s.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) < 7;
      });
      if (farmland == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "water — no dry farmland nearby");
         return;
      }
      // Check for natural water within 4 blocks of the farmland (horizontal,
      // same Y or one above). If found, the farmland will hydrate on its own
      // — no trench needed.
      if (waterWithinReach(level, farmland, 4)) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "water — farmland at " + farmland.toShortString() + " is already in reach of natural water");
         return;
      }
      // Pick a target tile for the source: ideally one block away from
      // farmland at the same Y, prefer empty tile, else any breakable.
      BlockPos waterPos = pickWaterSourceTile(level, farmland);
      if (waterPos == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "water — no viable spot to place a source near " + farmland.toShortString());
         return;
      }
      enqueueBlockTask(level, actor, waterPos, "water", (lvl, v, pos) -> {
         var existing = lvl.getBlockState(pos);
         String breakNote = "";
         if (!existing.isAir() && !existing.is(net.minecraft.tags.BlockTags.REPLACEABLE)) {
            // Excavate first.
            var drops = net.minecraft.world.level.block.Block.getDrops(
               existing, lvl, pos, null, v, v.getMainHandItem());
            lvl.destroyBlock(pos, false, v);
            for (var d : drops) {
               if (d.isEmpty()) continue;
               ItemStack leftover = v.getInventory().addItem(d);
               if (leftover != null && !leftover.isEmpty()) {
                  var ent = new net.minecraft.world.entity.item.ItemEntity(
                     lvl, v.getX(), v.getY(), v.getZ(), leftover);
                  ent.setPickUpDelay(20);
                  lvl.addFreshEntity(ent);
               }
            }
            breakNote = " (excavated first)";
         }
         lvl.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
         return "placed a water source at " + pos.toShortString() + breakNote;
      });
   }

   /** True if any WATER block exists within {@code horizontalRadius} of
    *  {@code farmland}, at the same Y or one above (matches vanilla farmland
    *  hydration rules). */
   private static boolean waterWithinReach(ServerLevel level, BlockPos farmland, int horizontalRadius) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
         for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
               cur.set(farmland.getX() + dx, farmland.getY() + dy, farmland.getZ() + dz);
               if (level.getFluidState(cur).is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
         }
      }
      return false;
   }

   /** Pick a tile to flood. Ideal: 1 block away from farmland, same Y, air-
    *  filled. Fallback: 2-4 blocks away. Refuse if everything in range is
    *  an unbreakable obstacle. */
   private static BlockPos pickWaterSourceTile(ServerLevel level, BlockPos farmland) {
      // Spiral out 1..4 blocks in cardinal directions at same Y.
      int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}};
      BlockPos best = null;
      double bestRank = Double.MAX_VALUE;
      for (int[] o : offsets) {
         BlockPos p = farmland.offset(o[0], 0, o[1]);
         var s = level.getBlockState(p);
         var below = level.getBlockState(p.below());
         // Must be supported (block below is solid-ish) so water doesn't
         // immediately flow away into a void.
         if (below.isAir()) continue;
         double rank;
         if (s.isAir()) rank = 0;
         else if (s.is(net.minecraft.tags.BlockTags.REPLACEABLE)) rank = 1;
         else if (s.is(net.minecraft.tags.BlockTags.DIRT)
               || s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) rank = 2;
         else continue;   // skip stone/etc — too valuable / hard to break
         if (rank < bestRank) { bestRank = rank; best = p; }
      }
      return best;
   }

   /** Like {@link #findClosest} but predicate takes a BlockPos so it can
    *  consult neighboring blocks. Same scan range and selection rule. */
   private static BlockPos findClosestPos(ServerLevel level, BlockPos centre, int radius,
                                          java.util.function.Predicate<BlockPos> pred) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               if (!pred.test(cur)) continue;
               double d = cur.distSqr(centre);
               if (d < bestDist) { bestDist = d; best = cur.immutable(); }
            }
         }
      }
      return best;
   }

   // ───── shear — entity-targeted, requires shears ─────

   /** Generic player-LLM-initiated livestock action dispatcher. Looks up
    *  every {@link com.yucareux.townfolk.world.livestock.LivestockTask}
    *  whose {@code verb()} matches and picks the nearest ready target in
    *  a 12-block radius. Same flow for shear / milk / breed — adding a
    *  new species or task is a registry entry only, no edits here. */
   private static void doLivestock(ServerLevel level, TownSquareBlockEntity town,
                                   Villager actor, VillagerEntry self,
                                   String verb, String hint) {
      // Collect every task matching this verb (e.g. all 5 BreedTasks for "breed").
      java.util.List<com.yucareux.townfolk.world.livestock.LivestockTask> candidates =
         new java.util.ArrayList<>();
      for (var t : com.yucareux.townfolk.world.livestock.LivestockTasks.ALL) {
         if (t.verb().equals(verb)) candidates.add(t);
      }
      if (candidates.isEmpty()) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            verb + " — no such livestock task registered");
         return;
      }

      // Optional hint narrows by species name (e.g. "milk cow", "breed pig").
      String hintKey = hint.toLowerCase(Locale.ROOT).trim();
      if (!hintKey.isEmpty()) {
         candidates.removeIf(t -> {
            // Match against the entity-type description; species classes
            // like "Sheep.class" → "sheep" / "cow" / etc. via the default
            // hint matching against task id + entity type display.
            String desc = t.targetType().getSimpleName().toLowerCase(Locale.ROOT);
            return !t.id().contains(hintKey) && !desc.contains(hintKey);
         });
         if (candidates.isEmpty()) {
            ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
               verb + " — no " + hintKey + " task registered");
            return;
         }
      }

      // First viable: task whose tool the villager has + nearest ready target.
      var box = actor.getBoundingBox().inflate(12);
      com.yucareux.townfolk.world.livestock.LivestockTask chosenTask = null;
      net.minecraft.world.entity.animal.Animal chosenTarget = null;
      double bestDist = Double.MAX_VALUE;
      for (var task : candidates) {
         if (!task.hasTool(actor)) continue;
         for (var a : level.getEntitiesOfClass(task.targetType(), box,
               an -> task.ready(level, an, actor))) {
            double d = actor.distanceToSqr(a);
            if (d < bestDist) { bestDist = d; chosenTask = task; chosenTarget = a; }
         }
      }
      if (chosenTask == null || chosenTarget == null) {
         // If we got here it's either "no tool" or "no ready target" — both
         // surface to the LLM via ActionFeedback. Differentiate so the
         // dialogue can be specific.
         boolean anyToolOk = candidates.stream().anyMatch(t -> t.hasTool(actor));
         if (!anyToolOk) {
            String toolList = candidates.stream()
               .map(t -> t.toolItemId() == null ? "the right tool" : t.toolItemId())
               .distinct().reduce((a, b) -> a + " or " + b).orElse("a tool");
            ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
               verb + " — I need " + toolList);
         } else {
            ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
               verb + " — no ready target within 12 blocks");
         }
         return;
      }

      final var taskF = chosenTask;
      final var targetF = chosenTarget;
      EntityTaskQueue.enqueue(level, actor, new EntityTaskQueue.EntityTask(
         actor.getUUID(), targetF.getUUID(),
         level.getGameTime() + 20L * 30, verb,
         (lvl, v, t) -> {
            if (!(t instanceof net.minecraft.world.entity.animal.Animal a)) {
               throw new RuntimeException(verb + " — target is no longer an animal");
            }
            return taskF.perform(lvl, v, a, /* parcel */ null, town);
         }));
   }

   private static boolean hasItem(Villager actor, String itemId) {
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).getItem() == target) return true;
      }
      return false;
   }

   // ───── feed — entity-targeted, consumes a matching food item ─────

   private static void doFeed(ServerLevel level, TownSquareBlockEntity town,
                              Villager actor, VillagerEntry self, String hint) {
      String hintKey = hint.toLowerCase(Locale.ROOT).trim();
      var box = actor.getBoundingBox().inflate(12);
      net.minecraft.world.entity.animal.Animal target = null;
      ItemStack matchingFood = null;
      double bestDist = Double.MAX_VALUE;

      var inv = actor.getInventory();
      // Pre-collect non-empty inventory stacks once.
      java.util.List<ItemStack> bag = new java.util.ArrayList<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (!s.isEmpty()) bag.add(s);
      }
      if (bag.isEmpty()) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            "feed — nothing in my bag to offer");
         return;
      }

      for (var a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, box,
            an -> an.isAlive() && (hintKey.isEmpty()
               || an.getType().getDescription().getString().toLowerCase(Locale.ROOT).contains(hintKey)
               || (BuiltInRegistries.ENTITY_TYPE.getKey(an.getType()) != null
                  && BuiltInRegistries.ENTITY_TYPE.getKey(an.getType()).getPath().contains(hintKey))))) {
         // Find food this animal accepts from the bag.
         for (var stack : bag) {
            if (a.isFood(stack)) {
               double d = actor.distanceToSqr(a);
               if (d < bestDist) { bestDist = d; target = a; matchingFood = stack; }
               break;
            }
         }
      }
      if (target == null) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            hintKey.isEmpty()
               ? "feed — no animal near me will accept anything I'm carrying"
               : "feed — no " + hintKey + " near me that will accept anything I'm carrying");
         return;
      }
      final net.minecraft.world.entity.animal.Animal animal = target;
      final ItemStack foodFinal = matchingFood;
      EntityTaskQueue.enqueue(level, actor, new EntityTaskQueue.EntityTask(
         actor.getUUID(), animal.getUUID(),
         level.getGameTime() + 20L * 30, "feed",
         (lvl, v, t) -> {
            if (!(t instanceof net.minecraft.world.entity.animal.Animal an)) {
               throw new RuntimeException("target is no longer an animal");
            }
            // Re-locate the same item type in the villager's bag (the stack
            // reference we captured at queue time may no longer be valid if
            // inventory shifted during the walk).
            var inv2 = v.getInventory();
            int slot = -1;
            for (int i = 0; i < inv2.getContainerSize(); i++) {
               if (!inv2.getItem(i).isEmpty()
                   && inv2.getItem(i).getItem() == foodFinal.getItem()
                   && an.isFood(inv2.getItem(i))) {
                  slot = i; break;
               }
            }
            if (slot < 0) throw new RuntimeException("food gone before delivery");
            var bite = inv2.getItem(slot).copy();
            bite.setCount(1);
            inv2.getItem(slot).shrink(1);
            if (inv2.getItem(slot).isEmpty()) inv2.setItem(slot, ItemStack.EMPTY);
            if (!an.isBaby() && an.canFallInLove()) an.setInLove(null);
            // Always emit a "love" / "happy" particle so the player sees the
            // feed land.
            lvl.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
               an.getX(), an.getY() + an.getBbHeight(), an.getZ(),
               4, 0.2, 0.2, 0.2, 0);
            long day = lvl.getGameTime() / 24000L;
            String foodName = bite.getHoverName().getString();
            String animalName = an.getType().getDescription().getString();
            com.yucareux.townfolk.villager.MemoryStore.write(v, "work", day,
               "I fed a " + animalName + " a piece of " + foodName + ".");
            return "fed a " + animalName + " 1× " + foodName;
         }));
   }

   /** Common dispatch path for block-task verbs. */
   private static void enqueueBlockTask(ServerLevel level, Villager actor, BlockPos target,
                                        String verb, BlockTaskQueue.OnArrive onArrive) {
      BlockTaskQueue.enqueue(level, actor, new BlockTaskQueue.BlockTask(
         actor.getUUID(), target,
         level.getGameTime() + BLOCK_TASK_TIMEOUT_TICKS,
         verb, onArrive));
   }

   /** Render a barrel reference using the player-set label if any:
    *  {@code "the 'wool stash' barrel at 880405, -864, -5876093"} or
    *  fallback to {@code "the container at 880405, -864, -5876093"}.
    *  Used in memories + ActionFeedback so the LLM hears the same name
    *  the player sees in the StorageConfig UI and admin Resources tab. */
   private static String containerRefFor(ServerLevel level, BlockPos pos) {
      var cfg = com.yucareux.townfolk.town.StorageRegistry.find(level, pos);
      String label = cfg == null ? "" : cfg.label();
      if (label != null && !label.isBlank()) {
         return "the \"" + label + "\" container at " + pos.toShortString();
      }
      return "the container at " + pos.toShortString();
   }

   private static boolean hasToolWithTag(Villager actor, net.minecraft.tags.TagKey<Item> tag) {
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (s.isEmpty()) continue;
         if (s.is(tag)) return true;
      }
      return false;
   }

   // doRemember moved to {@link com.yucareux.townfolk.world.verbs.RememberVerb} (stage 16b.2.a).

   // ───── reflex / forget — standing orders ─────
   // doReflex moved to {@link com.yucareux.townfolk.world.verbs.ReflexVerb} (16b.2.b).
   // doForget moved to {@link com.yucareux.townfolk.world.verbs.ForgetVerb} (16b.2.b).

   // doSleep moved to {@link com.yucareux.townfolk.world.verbs.SleepVerb} (stage 16b.2.a).
   // doViolencePlaceholder moved to {@link com.yucareux.townfolk.world.verbs.ViolenceVerb}.

   /** Pkg-private so verb-handler classes in {@code world.verbs.*}
    *  can write through the same log channel. */
   static void log(ServerLevel level, TownSquareBlockEntity town, VillagerEntry self, String msg) {
      VerboseLog.write("ACTION_RESULT", "actor=" + self.name(), msg);
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, self.name() + " " + msg);
   }

   private ToolDispatcher() {}
}
