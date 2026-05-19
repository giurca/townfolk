package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.dialogue.VillagerBusy;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.llm.PromptBuilder;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.Todo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Evolution, not bolt-on. Two responsibilities, one poll cycle:
 *
 *   1. Need ↔ todo sync. Vanilla Brain memories (HOME, JOB_SITE, …) are
 *      mapped to open todos. When the memory appears → todo auto-closes.
 *      When the memory is missing → an open todo is created (if not already
 *      present). The LLM sees the need in its existing OPEN COMMITMENTS
 *      section every prompt and can act on it via INTENT/ACTION markers.
 *
 *   2. Hailing. When a villager has a player-actionable open todo AND a
 *      player is in range AND the per-villager cooldown is clear, the
 *      villager walks toward that player (reusing FollowService for a short
 *      duration) and fires a one-shot LLM call to produce a brief in-character
 *      chat-message hail. The player sees it as a yellow chat line; they can
 *      right-click the villager to open the full dialogue if they choose.
 *
 * Pure reuse of existing layers — todos, intents, follow, dialogue prompt,
 * verbose log. No new memory or UI surface.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class NeedsService {

   private static final int POLL_TICKS = 100;                  // 5 s
   private static final double HAIL_RADIUS = 24.0;
   private static final long HAIL_COOLDOWN_TICKS = 20L * 60 * 5; // 5 real min
   private static final long FOLLOW_DURATION_TICKS = 20L * 30;    // 30 s walk-up window
   private static final Map<UUID, Long> LAST_HAIL = new ConcurrentHashMap<>();

   // Tag prefix used on todo text so we can recognise auto-generated needs.
   private static final String NEED_TAG_HOME = "[need:home] ";
   private static final String NEED_TEXT_HOME = NEED_TAG_HOME + "Find a bed in this village.";
   // [need:job] retired with the move to land-driven identity. Villagers
   // no longer need a vanilla workstation; they need PARCELS, assigned via
   // the Surveyor's Stake. The free-form [need:hoe] / [need:seeds] / etc.
   // tags emitted by ParcelRoutine cover the gap. Existing [need:job] todos
   // on saves from before this change are cleaned up below.

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % POLL_TICKS != 0L) return;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         TownData data = town.getTown();
         for (VillagerEntry entry : data.villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.townSquarePos() == 0L) continue;

            comp = reconcileNeeds(v, comp, level.getGameTime() / 24000L);
            v.setData(ModRegistries.LLM_VILLAGER.get(), comp);

            maybeHail(level, town, v, entry, comp);
         }
      }
   }

   // ----- Needs ↔ todos sync -----

   private static LlmVillagerComponent reconcileNeeds(Villager v, LlmVillagerComponent comp, long day) {
      // A need is "met" only if the PLAYER assigned the bed. Vanilla AI
      // auto-claims don't count — we want the player to choose so the home
      // anchor is meaningful. The Brain memory check is a secondary safety:
      // if the vanilla memory got cleared (bed broken), the need re-opens.
      boolean hasBed = comp.playerSetHome() && v.getBrain().hasMemoryValue(MemoryModuleType.HOME);
      comp = syncNeed(v, comp, day, hasBed, NEED_TAG_HOME, NEED_TEXT_HOME);
      // Close any legacy [need:job] todos still sitting open from older saves.
      comp = retireLegacyJobTodo(comp);
      return comp;
   }

   /** One-shot migration helper: marks any open "[need:job] " todo as
    *  abandoned. The whole concept is gone under the parcel-driven model;
    *  ParcelRoutine writes [need:hoe] / [need:seeds] / [need:shears] for
    *  what villagers actually want now. */
   private static LlmVillagerComponent retireLegacyJobTodo(LlmVillagerComponent c) {
      boolean anyOpen = c.todos().stream()
         .anyMatch(t -> t.isOpen() && t.text().startsWith("[need:job] "));
      if (!anyOpen) return c;
      var next = new java.util.ArrayList<>(c.todos());
      for (int i = 0; i < next.size(); i++) {
         Todo t = next.get(i);
         if (t.isOpen() && t.text().startsWith("[need:job] ")) {
            next.set(i, t.withStatus("abandoned"));
         }
      }
      return c.withTodos(next);
   }

   private static LlmVillagerComponent syncNeed(Villager v, LlmVillagerComponent c, long day,
                                                boolean needMet, String tag, String text) {
      Optional<Todo> existing = c.todos().stream()
         .filter(t -> t.text().startsWith(tag))
         .findFirst();
      if (needMet) {
         if (existing.isPresent() && existing.get().isOpen()) {
            List<Todo> next = new ArrayList<>(c.todos());
            for (int i = 0; i < next.size(); i++) {
               if (next.get(i).id().equals(existing.get().id())) {
                  next.set(i, existing.get().withStatus("done"));
                  break;
               }
            }
            c = c.withTodos(next);
            VerboseLog.write("NEED_RESOLVED", "tag=" + tag.trim(), text);
            com.yucareux.townfolk.villager.MemoryStore.write(v, "action", day,
               "My need is met: " + text.substring(text.indexOf(']') + 2));
         }
         return c;
      }
      if (existing.isEmpty() || !existing.get().isOpen()) {
         Todo t = Todo.create(text, "player", day);
         c = c.withAppendedTodo(t);
         VerboseLog.write("NEED_RAISED", "tag=" + tag.trim(), text);
      }
      return c;
   }

   // ----- Hailing -----

   private static void maybeHail(ServerLevel level, TownSquareBlockEntity town,
                                 Villager v, VillagerEntry entry, LlmVillagerComponent comp) {
      if (VillagerBusy.isBusy(v.getUUID())) return;
      // Don't yank sleeping villagers out of bed. Two guards: a phase
      // check that suppresses hails after dusk and during night, and a
      // direct isSleeping() check that catches even the edge case
      // where the schedule says work_hours but the villager is still
      // physically in bed (e.g. early dawn naps).
      if (ScheduleService.isSleepTime(level)) return;
      if (v.isSleeping()) return;
      if (comp.backstory() == null || comp.backstory().isBlank()) return;
      Long last = LAST_HAIL.get(v.getUUID());
      long now = level.getGameTime();
      if (last != null && now - last < HAIL_COOLDOWN_TICKS) return;
      if (!LlmClient.get().isConfigured()) return;

      // Collect ALL open need-flagged todos — hail mentions everything they
      // need at once, so the LLM can mention bed + work in the same line.
      java.util.List<Todo> openNeeds = comp.todos().stream()
         .filter(Todo::isOpen)
         .filter(t -> t.text().startsWith("[need:") || "player".equalsIgnoreCase(t.counterparty()))
         .toList();
      if (openNeeds.isEmpty()) return;

      // Nearest player in range.
      Player nearest = level.getNearestPlayer(v, HAIL_RADIUS);
      if (!(nearest instanceof ServerPlayer player)) return;

      LAST_HAIL.put(v.getUUID(), now);
      VillagerBusy.markBusy(v.getUUID());

      // Walk towards them while we wait for the LLM line.
      FollowService.start(level, v.getUUID(), player.getUUID(),
         now + FOLLOW_DURATION_TICKS);

      String stripped = openNeeds.stream()
         .map(t -> {
            String s = t.text();
            return s.contains("] ") ? s.substring(s.indexOf("] ") + 2) : s;
         })
         .collect(java.util.stream.Collectors.joining(" AND "));

      String system = PromptBuilder.dialogueSystemPrompt(entry, comp, town.getTown(), now / 24000L, java.util.List.of());
      String worldSense = WorldSense.describe(v, level, town.getBlockPos(), entry);
      system = PromptBuilder.withWorldSense(system, worldSense);

      List<LlmClient.Message> messages = new ArrayList<>();
      messages.add(new LlmClient.Message("system", system));
      messages.add(new LlmClient.Message("user",
         "[" + player.getName().getString() + " is within sight but hasn't noticed you. You want to flag them down to ask about: "
            + stripped + ". Speak ONE short, natural line to get their attention — in character. No markers, no narration tags. Plain prose only.]"));

      VerboseLog.write("HAIL_FIRE",
         "villager=" + entry.name() + " player=" + player.getName().getString() + " need=\"" + stripped + "\"", "");

      LlmClient.get().chat(TownfolkConfig.COMMON.dialogueModel.get(), messages)
         .thenAcceptAsync(result -> {
            try {
               if (!result.ok()) {
                  Townfolk.LOGGER.warn("Hail LLM call failed: {}", result.error());
                  return;
               }
               String line = result.content() == null ? "" : result.content().trim();
               // Strip any markers the LLM may have appended despite the instruction.
               line = IntentExecutor.parse(line).cleanedText();
               line = ToolDispatcher.parse(line).cleanedText();
               if (line.isEmpty()) return;

               // Wrap the entire hail in a ClickEvent so the player can just
               // click the chat message to open dialogue with this villager.
               String talkCmd = "/townfolk talk " + v.getUUID();
               net.minecraft.network.chat.Style clickable = net.minecraft.network.chat.Style.EMPTY
                  .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                     net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, talkCmd))
                  .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                     net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                     Component.literal("Click to talk with " + entry.name())));
               Component msg = Component.literal("[" + entry.name() + "] ")
                  .withStyle(ChatFormatting.GOLD).withStyle(clickable)
                  .append(Component.literal(line).withStyle(ChatFormatting.YELLOW).withStyle(clickable));
               player.sendSystemMessage(msg);

               // Persist the hail in the villager's memory.
               long day = level.getGameTime() / 24000L;
               Villager fresh = level.getEntity(v.getUUID()) instanceof Villager vv ? vv : null;
               if (fresh != null) {
                  LlmVillagerComponent c = fresh.getData(ModRegistries.LLM_VILLAGER.get())
                     .withUsage(result.inputTokens(), result.outputTokens());
                  fresh.setData(ModRegistries.LLM_VILLAGER.get(), c);
                  com.yucareux.townfolk.villager.MemoryStore.write(fresh, "dialogue", day,
                     "I called out to " + player.getName().getString() + ": \"" + line + "\"");
               }
               VerboseLog.write("HAIL_SENT",
                  "villager=" + entry.name() + " player=" + player.getName().getString()
                     + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
                  line);
               town.getTown().log().add(now, TownLog.Level.DIALOGUE,
                  entry.name() + " → " + player.getName().getString() + " (hail): \"" + line + "\"");
            } finally {
               VillagerBusy.markFree(v.getUUID());
            }
         }, level.getServer());
   }

   /** Server handler for {@link com.yucareux.townfolk.network.AssignBlockPayload}. */
   public static void handleAssign(net.minecraft.world.entity.player.Player rawPlayer,
                                   com.yucareux.townfolk.network.AssignBlockPayload payload) {
      if (!(rawPlayer instanceof ServerPlayer player)) return;
      ServerLevel level = player.serverLevel();
      BlockPos target = payload.pos();
      var state = level.getBlockState(target);
      boolean isBed = state.is(net.minecraft.tags.BlockTags.BEDS);
      String wantedTag = isBed ? "[need:home] " : "[need:job] ";

      // Match priority: (1) villager currently following this player,
      // (2) nearest with an open need-todo matching this block type,
      // (3) nearest with an existing player-set anchor (reassignment).
      Villager followingMatch = null;
      Villager openNeedMatch = null;
      double openNeedDistSq = 16.0 * 16.0;
      Villager reassignMatch = null;
      double reassignDistSq = 16.0 * 16.0;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent c = v.getData(ModRegistries.LLM_VILLAGER.get());
            double d = v.distanceToSqr(player);
            if (d > 16.0 * 16.0) continue;

            var follow = FollowService.peek(v.getUUID());
            boolean isFollowingPlayer = follow.isPresent() && follow.get().targetUuid().equals(player.getUUID());
            boolean hasOpenNeed = c.todos().stream().anyMatch(t -> t.isOpen() && t.text().startsWith(wantedTag));
            boolean hasExistingAnchor = isBed ? c.playerSetHome() : c.playerSetJob();

            if (isFollowingPlayer) followingMatch = v;
            if (hasOpenNeed && d < openNeedDistSq) { openNeedMatch = v; openNeedDistSq = d; }
            else if (!hasOpenNeed && hasExistingAnchor && d < reassignDistSq) {
               reassignMatch = v; reassignDistSq = d;
            }
         }
      }
      Villager bestVillager = followingMatch != null ? followingMatch
                              : openNeedMatch != null ? openNeedMatch
                              : reassignMatch;

      if (bestVillager == null) {
         player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            isBed ? "(no nearby villager is looking for a home)"
                  : "(no nearby villager is looking for a workstation)")
            .withStyle(net.minecraft.ChatFormatting.GRAY));
         return;
      }

      LlmVillagerComponent c = bestVillager.getData(ModRegistries.LLM_VILLAGER.get());
      boolean isReassignment = isBed ? c.playerSetHome() : c.playerSetJob();
      net.minecraft.core.GlobalPos oldPos = bestVillager.getBrain().getMemory(
         isBed ? net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME
               : net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE).orElse(null);
      String oldProfession = bestVillager.getVillagerData().getProfession().name();

      // Same position re-assignment is a no-op for setting things, but we
      // still want to acknowledge.
      if (oldPos != null && oldPos.pos().equals(target)) {
         player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "(" + (bestVillager.hasCustomName() ? bestVillager.getCustomName().getString() : "villager")
               + " already lives/works here)")
            .withStyle(net.minecraft.ChatFormatting.GRAY));
         return;
      }

      bestVillager.getBrain().setMemory(
         isBed ? net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME
               : net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
         net.minecraft.core.GlobalPos.of(level.dimension(), target));

      // For workstation assignments, also set the matching profession.
      net.minecraft.world.entity.npc.VillagerProfession newProf = null;
      if (!isBed) {
         newProf = professionForBlock(state.getBlock());
         if (newProf != null) {
            var oldData = bestVillager.getVillagerData();
            bestVillager.setVillagerData(oldData.setProfession(newProf));
         }
      }

      long day = level.getGameTime() / 24000L;
      String name = bestVillager.hasCustomName() ? bestVillager.getCustomName().getString() : "villager";
      String newTrade = bestVillager.getVillagerData().getProfession().name();
      String playerName = player.getName().getString();
      String narrative = composeAssignNarrative(playerName, isBed, isReassignment, oldProfession, newTrade, target);

      c = isBed ? c.withPlayerSetHome(true) : c.withPlayerSetJob(true);
      bestVillager.setData(ModRegistries.LLM_VILLAGER.get(), c);
      com.yucareux.townfolk.villager.MemoryStore.write(bestVillager, "action", day, narrative);

      // End any active follow + start walking toward the new spot.
      FollowService.stop(level, bestVillager.getUUID());
      com.yucareux.townfolk.diag.NavCall.moveTo(bestVillager, target, 0.55,
         isBed ? "NeedsService.assignHome" : "NeedsService.assignJob");

      VerboseLog.write("ASSIGN_BLOCK",
         "villager=" + name + " type=" + (isBed ? "HOME" : "JOB_SITE")
            + " pos=" + target.toShortString() + " reassignment=" + isReassignment
            + " oldProf=" + oldProfession + " newProf=" + newTrade
            + " player=" + playerName, "");

      String ack;
      if (isBed) {
         ack = isReassignment ? "✓ Moved " + name + " to a new home." : "✓ Set home for " + name + ".";
      } else if (isReassignment && !oldProfession.equals(newTrade)) {
         ack = "✓ " + name + " is now a " + newTrade + " (was " + oldProfession + ").";
      } else {
         ack = "✓ Set workstation for " + name
            + (!"none".equals(newTrade) ? " — now a " + newTrade + "." : ".");
      }
      player.sendSystemMessage(net.minecraft.network.chat.Component.literal(ack)
         .withStyle(net.minecraft.ChatFormatting.GREEN));
   }

   /**
    * Compose a first-person narrative event for a home/work assignment,
    * varying tone based on whether it's a first-time or reassignment, and
    * whether the trade changed. The text feeds episodic memory, which
    * nightly compaction weaves into Beliefs — so future conversations
    * naturally reference the change.
    */
   private static String composeAssignNarrative(String player, boolean isBed,
                                                boolean isReassignment,
                                                String oldProfession, String newProfession,
                                                BlockPos pos) {
      if (isBed) {
         return isReassignment
            ? player + " moved me to a new bed at " + pos.toShortString()
               + ". I'll need to settle into the new place."
            : player + " gave me a bed at " + pos.toShortString() + " to call home.";
      }
      // Workstation:
      boolean tradeChanged = !oldProfession.equalsIgnoreCase(newProfession)
                              && !"none".equalsIgnoreCase(newProfession);
      if (!isReassignment) {
         return "none".equalsIgnoreCase(newProfession)
            ? player + " set up my workstation at " + pos.toShortString() + "."
            : player + " set up my workstation at " + pos.toShortString()
               + " — I'm a " + newProfession + " now.";
      }
      if (tradeChanged) {
         return player + " moved me from " + oldProfession + " to " + newProfession
            + " today — set up at " + pos.toShortString() + ". Time to learn a new trade.";
      }
      return player + " moved my workstation to " + pos.toShortString()
         + " — same trade, new spot.";
   }

   /**
    * Vanilla profession ↔ workstation block mapping. Returns null for blocks
    * that aren't a recognised workstation. Tracks the standard 13 trades.
    */
   public static net.minecraft.world.entity.npc.VillagerProfession professionForBlock(
         net.minecraft.world.level.block.Block b) {
      net.minecraft.world.level.block.Blocks B = null;   // alias for readability not needed
      if (b == net.minecraft.world.level.block.Blocks.COMPOSTER) return net.minecraft.world.entity.npc.VillagerProfession.FARMER;
      if (b == net.minecraft.world.level.block.Blocks.BARREL) return net.minecraft.world.entity.npc.VillagerProfession.FISHERMAN;
      if (b == net.minecraft.world.level.block.Blocks.LECTERN) return net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN;
      if (b == net.minecraft.world.level.block.Blocks.SMOKER) return net.minecraft.world.entity.npc.VillagerProfession.BUTCHER;
      if (b == net.minecraft.world.level.block.Blocks.BLAST_FURNACE) return net.minecraft.world.entity.npc.VillagerProfession.ARMORER;
      if (b == net.minecraft.world.level.block.Blocks.CARTOGRAPHY_TABLE) return net.minecraft.world.entity.npc.VillagerProfession.CARTOGRAPHER;
      if (b == net.minecraft.world.level.block.Blocks.BREWING_STAND) return net.minecraft.world.entity.npc.VillagerProfession.CLERIC;
      if (b == net.minecraft.world.level.block.Blocks.FLETCHING_TABLE) return net.minecraft.world.entity.npc.VillagerProfession.FLETCHER;
      if (b == net.minecraft.world.level.block.Blocks.CAULDRON) return net.minecraft.world.entity.npc.VillagerProfession.LEATHERWORKER;
      if (b == net.minecraft.world.level.block.Blocks.STONECUTTER) return net.minecraft.world.entity.npc.VillagerProfession.MASON;
      if (b == net.minecraft.world.level.block.Blocks.LOOM) return net.minecraft.world.entity.npc.VillagerProfession.SHEPHERD;
      if (b == net.minecraft.world.level.block.Blocks.SMITHING_TABLE) return net.minecraft.world.entity.npc.VillagerProfession.TOOLSMITH;
      if (b == net.minecraft.world.level.block.Blocks.GRINDSTONE) return net.minecraft.world.entity.npc.VillagerProfession.WEAPONSMITH;
      return null;
   }

   private NeedsService() {}
}
