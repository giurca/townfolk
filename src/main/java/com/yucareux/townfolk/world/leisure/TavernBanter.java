package com.yucareux.townfolk.world.leisure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/**
 * Tavern banter — Stage 11e. When two patrons are physically near each
 * other in a tavern AND the pair-cooldown is clear, fires a single
 * LLM call that returns both halves of a brief two-line exchange.
 * Lines are emitted to nearby players' chat with a small delay so
 * the exchange reads as a real back-and-forth, not a single block.
 *
 * <p>Design tenets:
 * <ul>
 *   <li><b>One LLM call per conversation.</b> Both lines come from a
 *       single JSON response. Two villagers chatting costs the same
 *       as one hail.
 *   <li><b>Per-pair cooldown.</b> Same two villagers won't banter
 *       more than once per ~10 IRL minutes. Lets pairs accumulate
 *       memories without dominating chat.
 *   <li><b>Per-villager busy flag.</b> A villager mid-banter won't
 *       trigger another exchange until the current one finishes.
 *   <li><b>Proximity dwell.</b> The pair must have been near each
 *       other for {@link #PROXIMITY_REQUIRED_TICKS} before triggering
 *       — fly-by encounters don't start conversations.
 *   <li><b>Random gate.</b> Even when conditions are met, only fires
 *       ~20% of the time — silence is normal, conversation is special.
 * </ul>
 *
 * <p>All state is in-memory only. Server restart resets cooldowns +
 * proximity timers. Persisted: per-villager MemoryStore entries
 * tagged {@code chat:<other_uuid>} so future stages can derive
 * relationship counters across days.
 */
public final class TavernBanter {

   /** Per-pair cooldown — same A↔B won't fire again for this many ticks. */
   private static final long PAIR_COOLDOWN_TICKS = 20L * 60 * 10;       // 10 min IRL

   /** Pair must be close for this many ticks (continuously) before banter
    *  can fire. Prevents a pass-by from starting a chat. */
   private static final long PROXIMITY_REQUIRED_TICKS = 60L;            // 3 s

   /** Distance threshold for "close enough to chat." Squared. */
   private static final double CHAT_RADIUS_SQ = 9.0;                    // 3 blocks

   /** Player must be within this distance from EITHER speaker to receive
    *  the chat lines. Otherwise the banter happens silently (still
    *  written to memory + TownLog). */
   private static final double PLAYER_HEAR_DISTANCE_SQ = 24.0 * 24.0;

   /** Per-poll trigger probability when conditions are met. */
   private static final double TRIGGER_PROBABILITY = 0.20;

   /** Pause between line A and line B so the exchange reads as a
    *  back-and-forth, not a single block of text. */
   private static final long LINE_B_DELAY_TICKS = 20L;                  // 1 s

   private static final Map<String, Long> LAST_BANTER = new ConcurrentHashMap<>();
   private static final Map<String, Long> PROXIMITY_START = new ConcurrentHashMap<>();
   private static final Set<UUID> IN_BANTER = ConcurrentHashMap.newKeySet();

   /** Pending second line — emitted by {@link #drainPendingLines} on the
    *  next tick after its {@code dueTick}. */
   private static final List<PendingLine> PENDING = new java.util.concurrent.CopyOnWriteArrayList<>();
   private record PendingLine(long dueTick, UUID speaker, UUID listener,
                              String speakerName, String text,
                              ServerLevel level) {}

   private TavernBanter() {}

   /** Called from {@code LeisureService.driveTavern} when a patron is
    *  parked at their spot. Determines whether to start a banter with
    *  a nearby patron, and if so fires the LLM call. */
   public static void maybeTrigger(ServerLevel level,
                                    TownSquareBlockEntity town,
                                    Villager v,
                                    VillagerEntry entry,
                                    LlmVillagerComponent comp,
                                    long now) {
      if (IN_BANTER.contains(v.getUUID())) return;

      Villager partner = findNearestPatron(level, v);
      if (partner == null) return;
      VillagerEntry partnerEntry = town.getTown().findVillager(partner.getUUID()).orElse(null);
      if (partnerEntry == null) return;
      if (IN_BANTER.contains(partner.getUUID())) return;

      String pair = pairKey(v.getUUID(), partner.getUUID());

      // ── 1. Proximity dwell. Both must be close for N consecutive ticks. ──
      Long startedAt = PROXIMITY_START.computeIfAbsent(pair, k -> now);
      if (now - startedAt < PROXIMITY_REQUIRED_TICKS) return;

      // ── 2. Pair cooldown. ──
      Long lastBanter = LAST_BANTER.get(pair);
      if (lastBanter != null && now - lastBanter < PAIR_COOLDOWN_TICKS) return;

      // ── 3. Random gate. ──
      if (level.getRandom().nextDouble() > TRIGGER_PROBABILITY) return;

      // ── 4. LLM must be available. Otherwise silently skip — banter
      //       is a luxury feature; no offline fallback for this stage. ──
      if (!LlmClient.get().isConfigured()) return;

      // Lock both villagers + record trigger time so re-poll doesn't double-fire.
      IN_BANTER.add(v.getUUID());
      IN_BANTER.add(partner.getUUID());
      LAST_BANTER.put(pair, now);
      PROXIMITY_START.remove(pair);

      LlmVillagerComponent partnerComp =
         partner.getData(ModRegistries.LLM_VILLAGER.get());
      fireExchange(level, town, v, entry, comp, partner, partnerEntry, partnerComp, now);
   }

   /** Background-tick callback to release pending line-B emissions
    *  whose dueTick has arrived. Called every poll from
    *  {@link LeisureService#onTick}. */
   public static void drainPendingLines(long now) {
      if (PENDING.isEmpty()) return;
      java.util.Iterator<PendingLine> it = PENDING.iterator();
      List<PendingLine> drained = new ArrayList<>();
      while (it.hasNext()) {
         PendingLine p = it.next();
         if (now >= p.dueTick) drained.add(p);
      }
      if (drained.isEmpty()) return;
      for (PendingLine p : drained) {
         emitLine(p.level, p.speaker, p.speakerName, p.text);
         long day = p.level.getGameTime() / 24000L;
         var speakerEnt = p.level.getEntity(p.speaker);
         if (speakerEnt instanceof Villager sv) {
            MemoryStore.write(sv, "chat:" + p.listener, day,
               "At the tavern I said to " + nameOf(p.level, p.listener)
                  + ": \"" + p.text + "\"");
         }
         IN_BANTER.remove(p.speaker);
         // Clear the listener's lock once their inbound line has landed.
         IN_BANTER.remove(p.listener);
      }
      PENDING.removeAll(drained);
   }

   // ────────── Internal: trigger + LLM call ──────────

   private static void fireExchange(ServerLevel level,
                                     TownSquareBlockEntity town,
                                     Villager a, VillagerEntry aEntry, LlmVillagerComponent aComp,
                                     Villager b, VillagerEntry bEntry, LlmVillagerComponent bComp,
                                     long now) {
      String system = """
         You are writing a brief Alpine-village tavern exchange between two villagers.
         Output strict JSON only — no prose, no markdown, no code fence.
         Schema: {"a_line": "<first speaker's opening, in-character, max 22 words>", "b_line": "<second speaker's reply, in-character, max 22 words>"}
         The exchange should sound like real-life small-talk in a village pub: brief, specific, in-character. Reference the speakers' backstories where natural. Avoid greetings like "hello" — start in the middle.
         """;
      StringBuilder u = new StringBuilder();
      u.append("Speaker A: ").append(aEntry.name());
      if (aComp.backstory() != null && !aComp.backstory().isBlank()) {
         u.append(" — ").append(snippet(aComp.backstory(), 220));
      }
      String aHunger = hungerNote(aComp.hunger());
      if (!aHunger.isEmpty()) u.append(" (").append(aHunger).append(")");
      u.append('\n');
      u.append("Speaker B: ").append(bEntry.name());
      if (bComp.backstory() != null && !bComp.backstory().isBlank()) {
         u.append(" — ").append(snippet(bComp.backstory(), 220));
      }
      String bHunger = hungerNote(bComp.hunger());
      if (!bHunger.isEmpty()) u.append(" (").append(bHunger).append(")");
      u.append('\n');
      u.append("Setting: a quiet evening at the village tavern.\n");
      u.append("Respond with JSON only.");

      LlmClient.get()
         .chat(TownfolkConfig.COMMON.dialogueModel.get(), system, u.toString())
         .thenAcceptAsync(result -> applyExchange(level, town,
               a, aEntry, b, bEntry, result, now),
            level.getServer());
   }

   private static void applyExchange(ServerLevel level,
                                      TownSquareBlockEntity town,
                                      Villager a, VillagerEntry aEntry,
                                      Villager b, VillagerEntry bEntry,
                                      LlmClient.LlmResult result, long firedAt) {
      if (!result.ok() || result.content() == null || result.content().isBlank()) {
         VerboseLog.write("TAVERN_BANTER_FAIL",
            "a=" + aEntry.name() + " b=" + bEntry.name(),
            "result=" + (result.error() == null ? "empty" : result.error()));
         IN_BANTER.remove(a.getUUID());
         IN_BANTER.remove(b.getUUID());
         return;
      }
      String content = result.content().trim();
      if (content.startsWith("```")) {
         int nl = content.indexOf('\n');
         if (nl > 0) content = content.substring(nl + 1);
         if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
         content = content.trim();
      }
      String aLine, bLine;
      try {
         JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
         aLine = obj.has("a_line") ? obj.get("a_line").getAsString().trim() : "";
         bLine = obj.has("b_line") ? obj.get("b_line").getAsString().trim() : "";
      } catch (Throwable t) {
         Townfolk.LOGGER.debug("TavernBanter parse failed: {}", t.getMessage());
         IN_BANTER.remove(a.getUUID());
         IN_BANTER.remove(b.getUUID());
         return;
      }
      if (aLine.isEmpty() || bLine.isEmpty()) {
         IN_BANTER.remove(a.getUUID());
         IN_BANTER.remove(b.getUUID());
         return;
      }
      if (aLine.length() > 160) aLine = aLine.substring(0, 160);
      if (bLine.length() > 160) bLine = bLine.substring(0, 160);

      // Emit line A immediately on the server thread (this runs there via
      // thenAcceptAsync(executor=server)).
      emitLine(level, a.getUUID(), aEntry.name(), aLine);
      long day = level.getGameTime() / 24000L;
      MemoryStore.write(a, "chat:" + b.getUUID(), day,
         "At the tavern I said to " + bEntry.name() + ": \"" + aLine + "\"");

      // Schedule line B for ~1s later via the pending queue.
      PENDING.add(new PendingLine(level.getGameTime() + LINE_B_DELAY_TICKS,
         b.getUUID(), a.getUUID(), bEntry.name(), bLine, level));

      // TownLog: condense to one line so the activity feed reads cleanly.
      town.getTown().log().add(level.getGameTime(), TownLog.Level.DIALOGUE,
         aEntry.name() + " and " + bEntry.name() + " chat at the tavern.");
      VerboseLog.write("TAVERN_BANTER",
         "a=" + aEntry.name() + " b=" + bEntry.name()
            + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
         "aLine=" + aLine + " | bLine=" + bLine);
   }

   /** Send the styled chat line to every player within hearing range
    *  of the speaker. The speaker's name is gold; the line is yellow
    *  — matches the NeedsService.maybeHail visual language. */
   private static void emitLine(ServerLevel level, UUID speakerUuid,
                                 String speakerName, String text) {
      if (!(level.getEntity(speakerUuid) instanceof Villager speaker)) return;
      Component msg = Component.literal("[" + speakerName + "] ")
         .withStyle(ChatFormatting.GOLD)
         .append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
      for (var player : level.players()) {
         if (player.distanceToSqr(speaker) <= PLAYER_HEAR_DISTANCE_SQ) {
            player.sendSystemMessage(msg);
         }
      }
   }

   // ────────── Internal: state + helpers ──────────

   /** Nearest TAVERN-choice patron within {@link #CHAT_RADIUS_SQ} of v,
    *  or null. Same-UUID is excluded. */
   private static Villager findNearestPatron(ServerLevel level, Villager v) {
      Villager best = null;
      double bestDistSq = CHAT_RADIUS_SQ;
      long today = level.getGameTime() / 24000L;
      for (var e : LeisureService.choicesSnapshot().entrySet()) {
         if (e.getKey().equals(v.getUUID())) continue;
         LeisureChoice c = e.getValue();
         if (c == null
             || c.day() != today
             || c.activity() != LeisureActivity.TAVERN) continue;
         if (!(level.getEntity(e.getKey()) instanceof Villager ov)) continue;
         double d = v.distanceToSqr(ov);
         if (d <= bestDistSq) {
            bestDistSq = d;
            best = ov;
         }
      }
      return best;
   }

   private static String pairKey(UUID a, UUID b) {
      return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
   }

   /** Render a brief hunger note for prompt context, or empty
    *  when the villager's hunger is unremarkable (≥ 60). The LLM
    *  uses these one-liners to colour the conversation naturally —
    *  hungry villagers may grumble about food, etc. */
   private static String hungerNote(int hunger) {
      if (hunger <= 15) return "starving — hasn't eaten in days";
      if (hunger <= 35) return "very hungry";
      if (hunger <= 55) return "peckish";
      return "";
   }

   private static String snippet(String s, int max) {
      if (s == null) return "";
      String trimmed = s.replaceAll("\\s+", " ").trim();
      return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
   }

   private static String nameOf(ServerLevel level, UUID uuid) {
      if (level.getEntity(uuid) instanceof Villager v) {
         return v.hasCustomName() ? v.getCustomName().getString()
                                  : uuid.toString().substring(0, 8);
      }
      return uuid.toString().substring(0, 8);
   }

   /** Used by {@link LeisureService#clearChoice} so pair-state for
    *  this villager's pairs is dropped at day rollover. */
   public static void clearForVillager(UUID villager) {
      IN_BANTER.remove(villager);
      // Drop any pair entries that mention this villager.
      String tag = villager.toString();
      LAST_BANTER.keySet().removeIf(k -> k.contains(tag));
      PROXIMITY_START.keySet().removeIf(k -> k.contains(tag));
   }
}
