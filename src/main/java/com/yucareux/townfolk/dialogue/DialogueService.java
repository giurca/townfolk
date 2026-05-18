package com.yucareux.townfolk.dialogue;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.llm.DialogueGenerator;
import com.yucareux.townfolk.network.OpenDialoguePayload;
import com.yucareux.townfolk.network.PlayerSpeaksPayload;
import com.yucareux.townfolk.network.VillagerReplyPayload;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.DialogueTurn;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side bridge between the dialogue UI and the LLM.
 *
 * Persistence model: each conversation turn is committed to the villager's
 * {@link LlmVillagerComponent} as it occurs.
 *   - When the player sends a line we append it to history before firing the
 *     LLM call, so the message survives even if the player closes the screen
 *     before the reply lands.
 *   - When the villager's reply arrives we append that too.
 *
 * The component is NBT-serialised with the entity, so conversations survive
 * across world reloads. (Earlier in-memory DialogueHistory class is retired.)
 */
public final class DialogueService {

   public static void openConversation(ServerPlayer player, Entity villager) {
      LlmVillagerComponent component = villager.getData(ModRegistries.LLM_VILLAGER.get());
      String backstory = component.backstoryOrEmpty().orElse("");
      String displayName = villager.hasCustomName()
         ? villager.getCustomName().getString()
         : "Villager";
      List<OpenDialoguePayload.Turn> history = component.dialogueHistory().stream()
         .map(t -> new OpenDialoguePayload.Turn(t.role(), t.text()))
         .toList();
      // Open need-flagged todos → render as banner with action buttons.
      java.util.List<OpenDialoguePayload.NeedFlag> needs = new java.util.ArrayList<>();
      for (var todo : component.todos()) {
         if (!todo.isOpen()) continue;
         if (todo.text().startsWith("[need:home]"))
            needs.add(new OpenDialoguePayload.NeedFlag("home", "a home"));
         else if (todo.text().startsWith("[need:job]"))
            needs.add(new OpenDialoguePayload.NeedFlag("job", "a workstation"));
      }
      PacketDistributor.sendToPlayer(
         player,
         new OpenDialoguePayload(villager.getUUID(), displayName, backstory, history, needs)
      );
   }

   public static void handlePlayerSpeech(Player player, PlayerSpeaksPayload incoming) {
      if (!(player instanceof ServerPlayer server)) return;
      // Defensive clamp — the wire codec already caps but a bad
      // packet/older client could still slip through. Lambdas below
      // capture `payload`, so we need a final local; rebinding
      // wouldn't compile.
      final PlayerSpeaksPayload payload;
      if (incoming.message() != null
          && incoming.message().length() > PlayerSpeaksPayload.MAX_MESSAGE_CHARS) {
         payload = new PlayerSpeaksPayload(incoming.villagerUuid(),
            incoming.message().substring(0, PlayerSpeaksPayload.MAX_MESSAGE_CHARS));
      } else {
         payload = incoming;
      }
      ServerLevel level = server.serverLevel();
      Entity entity = level.getEntity(payload.villagerUuid());
      if (entity == null) {
         PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
            payload.villagerUuid(),
            "(this villager is no longer here)",
            false
         ));
         return;
      }

      LlmVillagerComponent component = entity.getData(ModRegistries.LLM_VILLAGER.get());
      if (component.backstory() == null || component.backstory().isBlank()) {
         PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
            payload.villagerUuid(),
            "(...they seem distracted, lost in thought)",
            false
         ));
         return;
      }
      if (VillagerBusy.isBusy(payload.villagerUuid())) {
         PendingPlayerMessages.enqueue(
            payload.villagerUuid(), server.getUUID(), payload, level.getGameTime());
         PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
            payload.villagerUuid(),
            "(...they're mid-conversation — I'll get to your line in a moment)",
            false
         ));
         return;
      }
      VillagerBusy.markBusy(payload.villagerUuid());
      Optional<VillagerEntry> entry = resolveEntry(level, payload.villagerUuid(), component);
      if (entry.isEmpty()) {
         // Lock-leak fix: every early-return after markBusy must also
         // markFree, otherwise the villager is wedged for the full
         // 5-minute self-heal window.
         VillagerBusy.markFree(payload.villagerUuid());
         PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
            payload.villagerUuid(),
            "(this villager isn't registered with a town)",
            false
         ));
         return;
      }

      // Commit the player's turn immediately so it persists even if the
      // network call fails or the player closes the screen.
      LlmVillagerComponent afterPlayer = component.withAppendedTurn("player", payload.message());
      entity.setData(ModRegistries.LLM_VILLAGER.get(), afterPlayer);

      // Resolve the town for the prompt builder.
      BlockPos townPos = BlockPos.of(afterPlayer.townSquarePos());
      BlockEntity townBe = level.getBlockEntity(townPos);
      if (!(townBe instanceof TownSquareBlockEntity townSquare)) {
         VillagerBusy.markFree(payload.villagerUuid());
         PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
            payload.villagerUuid(), "(this villager's town is missing)", false));
         return;
      }
      long currentDay = level.getGameTime() / 24000L;

      // Store the player's line as a retrievable memory.
      String snippet = payload.message();
      if (snippet.length() > 140) snippet = snippet.substring(0, 140) + "…";
      String playerName = server.getName().getString();
      LlmVillagerComponent withEvent = afterPlayer;   // dialogueHistory already updated above
      com.yucareux.townfolk.villager.MemoryStore.write(entity, "dialogue", currentDay,
         "Player " + playerName + " said: \"" + snippet + "\"");

      townSquare.getTown().log().add(level.getGameTime(),
         com.yucareux.townfolk.town.TownLog.Level.DIALOGUE,
         playerName + " → " + entry.get().name() + ": \"" + snippet + "\"");
      com.yucareux.townfolk.diag.VerboseLog.write("DIALOGUE_PLAYER",
         "villager=" + entry.get().name() + " player=" + playerName,
         payload.message());

      String worldSense = com.yucareux.townfolk.world.WorldSense.describe(
         entity, level, townSquare.getBlockPos(), entry.get());

      // RAG: embed the player's message, pull top-K relevant memories, then
      // build the prompt with those injected. Falls back to recency on
      // embedding failure.
      com.yucareux.townfolk.villager.MemoryStore.retrieveAsync(withEvent, payload.message(), 8)
         .thenCompose(retrieved -> {
            com.yucareux.townfolk.diag.VerboseLog.write("RAG_INJECT",
               "villager=" + entry.get().name() + " count=" + retrieved.size() + " query=" + payload.message(),
               retrieved.stream().map(m -> m.kind() + ":" + m.text()).reduce((a, b) -> a + "\n" + b).orElse(""));
            return DialogueGenerator.reply(entry.get(), withEvent, townSquare.getTown(),
               currentDay, withEvent.dialogueHistory(), worldSense, retrieved);
         })
         .thenAcceptAsync(result -> {
            try {
               if (!result.ok()) {
                  Townfolk.LOGGER.warn("Dialogue LLM call failed: {}", result.error());
                  PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
                     payload.villagerUuid(),
                     "(...the villager seems distracted)",
                     false
                  ));
                  return;
               }
               String rawReply = result.content();
               // Parse [INTENT:] and [ACTION:] markers; the cleaned text is what the player sees.
               var intentParse = com.yucareux.townfolk.world.IntentExecutor.parse(rawReply);
               var actionParse = com.yucareux.townfolk.world.ToolDispatcher.parse(intentParse.cleanedText());
               String reply = collapseRepetition(actionParse.cleanedText());
               Entity fresh2 = level.getEntity(payload.villagerUuid());
               if (fresh2 instanceof net.minecraft.world.entity.npc.Villager villagerEnt) {
                  for (String it : intentParse.intents()) {
                     // player-initiated: the LLM is replying to the player
                     // right now, so this is their instruction.
                     com.yucareux.townfolk.world.IntentExecutor.execute(level, townSquare, villagerEnt, entry.get(), it, true);
                  }
                  for (String act : actionParse.actions()) {
                     com.yucareux.townfolk.world.ToolDispatcher.execute(level, townSquare, villagerEnt, entry.get(), act);
                  }
                  // Heuristic fallback: if the player gave an explicit
                  // movement command and the LLM didn't emit the matching
                  // marker, infer it from the player's message. Keeps the
                  // mechanic working when the LLM forgets the marker
                  // grammar (which it does).
                  boolean emittedFollow = actionParse.actions().stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).startsWith("follow"));
                  boolean emittedStop = actionParse.actions().stream().anyMatch(s -> {
                     String x = s.toLowerCase(java.util.Locale.ROOT);
                     return x.startsWith("stop following") || x.equals("unfollow");
                  });
                  boolean emittedHome = intentParse.intents().stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains("home"))
                                      || actionParse.actions().stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains("home"));
                  boolean emittedWork = intentParse.intents().stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains("work"))
                                      || actionParse.actions().stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains("work"));
                  String pmsg = payload.message().toLowerCase(java.util.Locale.ROOT);
                  // Word-boundary regexes — substring matches were too
                  // loose: "come on" matched "welcome onboard", "hold
                  // on" matched "household onslaught", etc. (\b at
                  // both ends so phrases must appear as discrete words.)
                  if (!emittedFollow && !emittedStop && (
                        pmsg.matches(".*\\bfollow me\\b.*") || pmsg.matches(".*\\bcome with me\\b.*")
                        || pmsg.matches(".*\\bcome on\\b.*") || pmsg.matches(".*\\bthis way\\b.*")
                        || pmsg.matches(".*\\blead the way\\b.*") || pmsg.matches(".*\\bcome here\\b.*"))) {
                     com.yucareux.townfolk.world.ToolDispatcher.execute(level, townSquare, villagerEnt, entry.get(), "follow " + playerName);
                     com.yucareux.townfolk.diag.VerboseLog.write("HEURISTIC_FOLLOW",
                        "villager=" + entry.get().name() + " trigger=\"" + payload.message() + "\"", "");
                  } else if (!emittedStop && (
                        pmsg.matches(".*\\bstop following\\b.*") || pmsg.matches(".*\\bwait here\\b.*")
                        || pmsg.equals("stay") || pmsg.matches(".*\\bhold on\\b.*"))) {
                     com.yucareux.townfolk.world.ToolDispatcher.execute(level, townSquare, villagerEnt, entry.get(), "stop following");
                     com.yucareux.townfolk.diag.VerboseLog.write("HEURISTIC_STOP", "villager=" + entry.get().name(), "");
                  } else if (!emittedHome && (
                        pmsg.matches(".*\\bgo home\\b.*") || pmsg.matches(".*\\bhead home\\b.*")
                        || pmsg.matches(".*\\breturn home\\b.*") || pmsg.matches(".*\\bgo to bed\\b.*"))) {
                     com.yucareux.townfolk.world.IntentExecutor.execute(level, townSquare, villagerEnt, entry.get(), "walk to home", true);
                     com.yucareux.townfolk.diag.VerboseLog.write("HEURISTIC_HOME", "villager=" + entry.get().name(), "");
                  } else if (!emittedWork && (
                        pmsg.matches(".*\\bgo to work\\b.*") || pmsg.matches(".*\\bback to work\\b.*")
                        || pmsg.matches(".*\\byour workstation\\b.*") || pmsg.matches(".*\\byour forge\\b.*"))) {
                     com.yucareux.townfolk.world.IntentExecutor.execute(level, townSquare, villagerEnt, entry.get(), "walk to work", true);
                     com.yucareux.townfolk.diag.VerboseLog.write("HEURISTIC_WORK", "villager=" + entry.get().name(), "");
                  } else {
                     // "go see <name>" / "go find <name>" / "go talk to <name>" / "visit <name>"
                     // — resolve <name> against this town's roster, fire INTENT
                     // toward that villager. IntentExecutor routes villager
                     // names to FollowService.start, giving a moving follow.
                     String targetName = extractVisitTarget(pmsg, townSquare, entry.get().uuid());
                     if (targetName != null) {
                        com.yucareux.townfolk.world.IntentExecutor.execute(level, townSquare, villagerEnt, entry.get(), "walk to " + targetName, true);
                        com.yucareux.townfolk.diag.VerboseLog.write("HEURISTIC_VISIT",
                           "villager=" + entry.get().name() + " target=" + targetName, "");
                     }
                  }
               }
               String replyShown = reply == null ? "" :
                  (reply.length() > 160 ? reply.substring(0, 160) + "…" : reply);
               townSquare.getTown().log().add(level.getGameTime(),
                  com.yucareux.townfolk.town.TownLog.Level.DIALOGUE,
                  entry.get().name() + " → " + playerName + ": \"" + replyShown + "\"");
               com.yucareux.townfolk.diag.VerboseLog.write("DIALOGUE_REPLY",
                  "villager=" + entry.get().name() + " player=" + playerName
                     + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
                  reply);
               Entity fresh = level.getEntity(payload.villagerUuid());
               if (fresh != null) {
                  LlmVillagerComponent now = fresh.getData(ModRegistries.LLM_VILLAGER.get());
                  fresh.setData(
                     ModRegistries.LLM_VILLAGER.get(),
                     now.withAppendedTurn("villager", reply)
                        .withUsage(result.inputTokens(), result.outputTokens())
                  );
               }
               PacketDistributor.sendToPlayer(server, new VillagerReplyPayload(
                  payload.villagerUuid(),
                  reply,
                  true
               ));
            } finally {
               VillagerBusy.markFree(payload.villagerUuid());
            }
         }, level.getServer());
   }

   private static Optional<VillagerEntry> resolveEntry(ServerLevel level, UUID villagerUuid,
                                                       LlmVillagerComponent component) {
      if (component.townSquarePos() == 0L) return Optional.empty();
      BlockPos pos = BlockPos.of(component.townSquarePos());
      BlockEntity be = level.getBlockEntity(pos);
      if (be instanceof TownSquareBlockEntity town) {
         return town.getTown().findVillager(villagerUuid);
      }
      return Optional.empty();
   }

   /**
    * If the player's message contains a "go see / go find / go to / visit /
    * go talk to / find <name>" pattern AND <name> matches a living
    * townsfolk's name (case-insensitive), return that exact villager name.
    * Returns null otherwise. The speaker themselves is excluded so the
    * villager can't be told to go find themselves.
    */
   private static String extractVisitTarget(String pmsg,
                                            com.yucareux.townfolk.blockentity.TownSquareBlockEntity town,
                                            java.util.UUID speakerUuid) {
      // Each cue is anchored to a word boundary so e.g. "foresee me"
      // doesn't accidentally trip the "see " cue (audit item 5.5).
      // Cues with leading "go " / "visit " etc. carry their own
      // implicit boundary because they start with a non-letter.
      String[] cues = { "go see ", "go find ", "go to ", "go talk to ", "visit ",
                         "find ", "talk to ", "go meet ", "see ", "meet " };
      String hit = null;
      for (String cue : cues) {
         // Find a position where the cue starts at the beginning of
         // pmsg OR right after a non-letter character — i.e., it's
         // its own word, not the suffix of another.
         int searchFrom = 0;
         while (searchFrom < pmsg.length()) {
            int i = pmsg.indexOf(cue, searchFrom);
            if (i < 0) break;
            if (i == 0 || !Character.isLetter(pmsg.charAt(i - 1))) {
               hit = pmsg.substring(i + cue.length()).trim();
               break;
            }
            searchFrom = i + 1;
         }
         if (hit != null) break;
      }
      if (hit == null || hit.isEmpty()) return null;
      for (var entry : town.getTown().villagers()) {
         if (!entry.alive()) continue;
         if (entry.uuid().equals(speakerUuid)) continue;
         String n = entry.name().toLowerCase(java.util.Locale.ROOT);
         if (hit.startsWith(n) || hit.contains(" " + n) || hit.contains(n + " ") || hit.equals(n)) {
            return entry.name();
         }
      }
      return null;
   }

   /** Server-side guard against small LLMs (notably DeepSeek v4-flash:free)
    *  emitting near-duplicate sentences in a single reply ("I'm doing fine.
    *  I'm doing fine. Yes, I put wheat. I confirm wheat is in the barrel.").
    *
    *  Splits the reply into sentences, drops any sentence whose normalised
    *  form (lower-cased, alphanumerics only) is already present, and stops
    *  early at the first DUP-AFTER-NEW pattern (i.e. once we've started
    *  cycling, the rest is almost certainly garbage). Cheap and idempotent
    *  — doesn't touch well-formed replies. */
   static String collapseRepetition(String text) {
      if (text == null || text.isEmpty()) return text;
      // Split on sentence-terminating punctuation while keeping the
      // delimiter so we can rebuild the prose.
      String[] sentences = text.split("(?<=[.!?])\\s+");
      java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
      java.util.List<String> kept = new java.util.ArrayList<>();
      int consecutiveDupAfterNew = 0;
      for (String s : sentences) {
         String norm = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
         if (norm.isEmpty()) { kept.add(s); continue; }
         if (seen.contains(norm)) {
            consecutiveDupAfterNew++;
            // Two dupes after fresh content → the model has started cycling.
            // Stop now; everything past this point is almost certainly noise.
            if (consecutiveDupAfterNew >= 2 && !kept.isEmpty()) break;
            continue;
         }
         seen.add(norm);
         kept.add(s);
         consecutiveDupAfterNew = 0;
      }
      String joined = String.join(" ", kept).trim();
      return joined.isEmpty() ? text : joined;
   }

   private DialogueService() {
   }
}
