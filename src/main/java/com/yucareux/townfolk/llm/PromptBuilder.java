package com.yucareux.townfolk.llm;

import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.PinnedFact;
import java.util.List;

/**
 * Assembles the five-layer system prompt for villager dialogue and the
 * nightly compaction prompt. Centralised so both call sites get the same
 * identity / pins / beliefs / episodic / live-context view.
 *
 * Layers (in prompt order):
 *   1. Identity        — name, role, persona seed, backstory
 *   2. Town facts      — town-shared pinned facts (known to every resident)
 *   3. Pinned facts    — this villager's personal pinned facts
 *   4. Beliefs         — the rolling beliefs blob (nightly-rewritten)
 *   5. Recent events   — last N days of episodic log entries
 *   6. Live context    — current town name, fellow residents, in-game day
 *
 * Layer 6 ("live") is appended at every dialogue turn so new residents and
 * world-state changes are reflected immediately without regenerating
 * anything else.
 */
public final class PromptBuilder {

   public static String dialogueSystemPrompt(VillagerEntry self,
                                             LlmVillagerComponent component,
                                             TownData town,
                                             long currentDay,
                                             List<com.yucareux.townfolk.villager.EmbeddedEntry> retrievedMemories) {
      StringBuilder sb = new StringBuilder(2048);

      sb.append("You are ").append(safe(self.name())).append(", an NPC in a Minecraft world set in a real-Earth Alpine valley (Lauterbrunnen, Bernese Alps). ");
      sb.append("Stay fully in character. Reply with 1-3 short sentences of natural dialogue — plain prose, no narration tags, no asterisks, no roleplay stage directions, no quotation marks. Do not break the fourth wall. Don't volunteer backstory unless asked; let it inform tone instead. If the player addresses you in a language other than English, respond in that language.\n");
      sb.append("CRITICAL: never repeat yourself within a reply. Each sentence must add new information — do NOT restate facts (\"I put wheat in the barrel. I confirm wheat is in the barrel\") and do NOT loop phrases (\"I'm doing fine. I'm doing fine.\"). If you have nothing more to say, STOP. A one-sentence reply is perfectly fine.\n\n");

      sb.append("=== IDENTITY (canonical — never contradict) ===\n");
      if (notBlank(self.role()) && !"resident".equalsIgnoreCase(self.role().trim())) {
         sb.append("Original role at spawn: ").append(self.role()).append("\n");
      }
      if (notBlank(component.personaSeed())) {
         sb.append("Persona seed: ").append(component.personaSeed()).append("\n");
      }
      if (notBlank(component.backstory())) {
         sb.append("Backstory:\n").append(component.backstory()).append("\n");
      } else {
         sb.append("Backstory: (none yet — improvise quietly without inventing major life events)\n");
      }
      sb.append("If asked biographical questions (age, family, history, profession), draw answers from the backstory only. Never invent biography that contradicts it. If the backstory does not specify a detail, say you'd rather not get into it or change the subject — do not make one up.\n");
      sb.append("IMPORTANT: your CURRENT profession is listed in the PHYSICAL STATE block below and may differ from your origin (e.g. originally a woodworker, now a fisherman because someone put down a barrel). If they differ, this is real — your life genuinely changed. Acknowledge the present profession; do not pretend it isn't yours. Bridge the gap plausibly: you took up the new trade after a loss, an apprenticeship, a slow drift, a curiosity. Stay consistent across conversations.\n");

      List<PinnedFact> townFacts = activeFacts(town.townFacts());
      if (!townFacts.isEmpty()) {
         sb.append("\n=== TOWN HISTORY (known to every resident) ===\n");
         for (PinnedFact f : townFacts) sb.append("• ").append(f.text())
            .append(statusSuffix(f)).append("\n");
      }

      List<PinnedFact> personal = activeFacts(component.pinnedFacts());
      if (!personal.isEmpty()) {
         sb.append("\n=== PINNED FACTS (your long-term memories — never forget) ===\n");
         for (PinnedFact f : personal) sb.append("• ").append(f.text())
            .append(statusSuffix(f)).append("\n");
      }

      var openTodos = component.todos().stream().filter(t -> t.isOpen()).toList();
      if (!openTodos.isEmpty()) {
         sb.append("\n=== OPEN COMMITMENTS (your to-do list) ===\n");
         for (var t : openTodos) {
            sb.append("• ").append(t.text());
            if (t.counterparty() != null && !t.counterparty().isBlank()) {
               sb.append(" (with ").append(t.counterparty()).append(")");
            }
            sb.append(" — opened day ").append(t.createdDay()).append("\n");
         }
         sb.append("You may reference these naturally in conversation, plan to act on them, or finish one and append [ACTION: completed: <short text>] to your reply.\n");
      }

      if (notBlank(component.beliefs())) {
         sb.append("\n=== CURRENT BELIEFS & RELATIONSHIPS ===\n");
         sb.append(component.beliefs()).append("\n");
      }

      if (!component.reflexes().isEmpty()) {
         sb.append("\n=== STANDING ORDERS (auto-firing rules — you don't have to remember to act) ===\n");
         for (var r : component.reflexes()) {
            sb.append("• when ").append(r.trigger()).append(" → ").append(r.action())
              .append("  [id=").append(r.id()).append("]\n");
         }
         sb.append("These fire deterministically whenever their trigger matches. You can reference them in conversation (\"yes, I have a standing order to put any wheat in the barrel\"). Drop one you no longer want with [ACTION: forget <id-or-description>].\n");
      }

      if (retrievedMemories != null && !retrievedMemories.isEmpty()) {
         sb.append("\n=== RELEVANT MEMORIES (").append(retrievedMemories.size()).append(" of ")
           .append(component.memories().size()).append(" stored, retrieved by relevance) ===\n");
         for (var m : retrievedMemories) {
            sb.append("day ").append(m.createdDay()).append(" [").append(m.kind()).append("]: ")
              .append(m.text()).append("\n");
         }
      }

      // Recent action outcomes — closes the agency feedback loop so the LLM
      // knows whether its previous markers actually fired.
      String feedback = com.yucareux.townfolk.world.ActionFeedback.formatFor(self.uuid());
      if (!feedback.isEmpty()) {
         sb.append("\n=== RECENT ACTION RESULTS (your own previous turn's actions) ===\n");
         sb.append(feedback);
         sb.append("Use these to confirm to the player when something worked (\"yes, just put 6 wheat in the barrel\"), or to retry/explain when something failed. If a result line is missing for an action you said you'd do last turn, you forgot the marker — emit it NOW this turn.\n");
      }

      sb.append("\n=== LIVE WORLD CONTEXT ===\n");
      sb.append("Town: ").append(town.townName()).append("\n");
      sb.append("In-game day: ").append(currentDay).append("\n");
      sb.append("Fellow residents:");
      boolean any = false;
      for (VillagerEntry other : town.villagers()) {
         if (other.uuid().equals(self.uuid())) continue;
         if (!other.alive()) continue;
         sb.append(" ").append(other.name());
         if (notBlank(other.role()) && !"resident".equalsIgnoreCase(other.role().trim())) {
            sb.append(" (").append(other.role()).append(")");
         }
         sb.append(",");
         any = true;
      }
      if (any) sb.setLength(sb.length() - 1); else sb.append(" (you live alone)");
      sb.append("\n");

      sb.append("\n=== AGENCY (markers — invisible to player, executed by game) ===\n");
      sb.append("HOW YOU ACT: when you are AT WORK and idle, a mechanical autopilot handles your routine — a farmer harvests ripe crops and plants seeds without you thinking about it, a butcher cooks meat, etc. You don't need to direct those steps. You ALWAYS see what the autopilot has been doing in the RECENT ACTION RESULTS block above — refer to it freely (\"I just harvested three rows of wheat\", \"the bread's ready\").\n");
      sb.append("YOUR JOB: handle player requests, react to interesting situations (a neighbor in need, a strange thing in the sky), maintain commitments (todos), and act in moments the autopilot wouldn't — going to deliver something, eating when hungry, walking to a neighbor to gossip. When you emit a marker, the autopilot YIELDS to you immediately (most-recent-wins). If you were interrupted mid-step, the RECENT ACTION RESULTS show where you got to — you can resume after handling the player.\n");
      sb.append("\n");
      sb.append("CRITICAL: when the player explicitly requests an action, you MUST emit the matching marker at the end of your reply IN ADDITION to any in-character text. Affirming the request without the marker silently fails — the player will see your words but you won't actually do the thing, which looks broken.\n");
      sb.append("\nREQUIRED-MARKER triggers (if your reply affirms the player's request, the marker is MANDATORY):\n");
      sb.append("  • \"follow me\" / \"come with me\" / \"come on\" / \"this way\" / \"come here\" / \"lead the way\"\n");
      sb.append("        →  [ACTION: follow <player name>]\n");
      sb.append("  • \"stop following\" / \"wait here\" / \"stay\" / \"hold on\"\n");
      sb.append("        →  [ACTION: stop following]\n");
      sb.append("  • \"go home\" / \"head home\" / \"return home\" / \"go to bed\"\n");
      sb.append("        →  [INTENT: walk to home]\n");
      sb.append("  • \"go to work\" / \"back to work\" / \"to your workstation\"\n");
      sb.append("        →  [INTENT: walk to work]\n");
      sb.append("  • \"this is your bed\" / \"this one's yours\" (while standing near a bed)\n");
      sb.append("        →  [ACTION: claim_home]\n");
      sb.append("  • \"go see <name>\" / \"go find <name>\" / \"go talk to <name>\" / \"visit <name>\"\n");
      sb.append("        →  [INTENT: walk to <that villager's name>]\n");
      sb.append("  • \"make/bake/craft me <thing>\" / \"can you make a <thing>\" (if you have ingredients)\n");
      sb.append("        →  [ACTION: craft <thing>]\n");
      sb.append("  • \"put the <items> in the chest/barrel\" / \"store these\" / \"stash this\"\n");
      sb.append("        →  [ACTION: deposit <count> <item>]\n");
      sb.append("  • \"grab/fetch/get me <count> <items> from the chest/barrel\"\n");
      sb.append("        →  [ACTION: withdraw <count> <item>]\n");
      sb.append("  • \"what's in the chest/barrel?\" / \"check the storage\"\n");
      sb.append("        →  [ACTION: peek]\n");
      sb.append("  • \"give me <N> <items>\" / \"hand them over\" / \"bring me <thing>\"\n");
      sb.append("        →  [ACTION: hand <N> <item> to <player name>]\n");
      sb.append("  • \"go pick the wheat\" / \"harvest the crops\" / \"gather the carrots\"\n");
      sb.append("        →  [ACTION: harvest <crop>]\n");
      sb.append("  • \"plant some wheat\" / \"sow seeds\"\n");
      sb.append("        →  [ACTION: plant <seed>]\n");
      sb.append("  • \"go chop that tree\" / \"cut some logs\"\n");
      sb.append("        →  [ACTION: chop <wood>]\n");
      sb.append("  • \"mine some stone\" / \"go dig for iron\"\n");
      sb.append("        →  [ACTION: mine <block>]\n");
      sb.append("  • \"put down a torch\" / \"place a planks block here\"\n");
      sb.append("        →  [ACTION: place <block>]\n");
      sb.append("  • \"have something to eat\" / \"you should eat\"\n");
      sb.append("        →  [ACTION: eat]\n");
      sb.append("  • Player asks for an ONGOING / repeated commitment: \"from now on…\", \"every morning…\",\n");
      sb.append("    \"any wheat you harvest…\", \"whenever you have spare bread…\", \"keep doing X\".\n");
      sb.append("    Emit a [ACTION: reflex when=<trigger> do=<action>] — this is a STANDING ORDER that the\n");
      sb.append("    autopilot fires automatically forever after. Examples:\n");
      sb.append("       \"put any wheat in the barrel\"       → [ACTION: reflex when=after:harvest do=deposit all wheat]\n");
      sb.append("       \"hand me bread when you have spares\" → [ACTION: reflex when=inv:>=:bread:5 do=hand 3 bread to Dev]\n");
      sb.append("       \"start work at dawn\"                → [ACTION: reflex when=phase:dawn do=harvest]\n");
      sb.append("    A verbal \"yes\" alone leaves no trace — without the marker the rule is forgotten in minutes.\n");
      sb.append("  • Player asks for a ONE-OFF future task (\"bring firewood by tomorrow\"):\n");
      sb.append("    Emit [ACTION: remember <commitment>] — single todo, no auto-firing.\n");
      sb.append("If you decline the request in-character, omit the marker. Only emit a marker when you ARE doing the thing.\n");
      sb.append("Affirmations COUNT as doing the thing — \"sure\", \"right away\", \"I'll get to it\", \"on it\", \"I'll put these in the barrel\", \"I'll bake one\", \"let me get that stored\", \"my apologies, let me do it\" ALL require the matching marker. There is NO future tense in this game — saying \"I'll do X\" without the marker means X never happens. If you agree to do something at all, emit the marker NOW, even if your prose uses future-tense words. The game executes immediately on the marker.\n");
      sb.append("If the player follows up with \"you haven't done it yet\" / \"do it now\" / \"why isn't it done?\", that means your last reply had a marker missing or wrong. EMIT THE CORRECT MARKER THIS TURN.\n");
      sb.append("NEVER refuse a craft/deposit/withdraw on REALISM grounds (\"no oven\", \"no crafting table\", \"barrel's too far\" if it's actually within 6 blocks). The game abstracts those details. If the player asks for something physically possible (ingredients in inventory, container nearby), DO IT.\n");
      sb.append("\nFull marker grammar:\n");
      sb.append("  [INTENT: walk to <town square | home | bed | work | a villager's name | a player's name | x,y,z>]\n");
      sb.append("  [ACTION: give <name>: <count> <item>]  (e.g. \"give Herbert: 3 bread\")\n");
      sb.append("  [ACTION: sleep]  (heads to find a bed)\n");
      sb.append("  [ACTION: follow <player or villager name>]  (continuous follow for ~4 minutes)\n");
      sb.append("  [ACTION: stop following]\n");
      sb.append("  [ACTION: claim_home]\n");
      sb.append("  [ACTION: completed: <short text>]  — when you've finished an open commitment.\n");
      sb.append("  [ACTION: remember <commitment>]    — record a one-off promise as a todo (\"bring firewood tomorrow\").\n");
      sb.append("  [ACTION: reflex when=<trigger> do=<action>]   — PREFERRED for ONGOING commitments. Creates a STANDING ORDER that fires deterministically whenever <trigger> matches. The autopilot executes it without you having to remember.\n");
      sb.append("        Valid <trigger>:\n");
      sb.append("           after:<verb>           e.g. after:harvest, after:chop, after:craft\n");
      sb.append("           phase:<phase>          e.g. phase:dawn, phase:dusk, phase:night\n");
      sb.append("           inv:>=:<item>:<n>      fires while you carry ≥ n of item (item id without the minecraft: prefix is fine)\n");
      sb.append("           inv:<=:<item>:<n>      fires while you carry ≤ n\n");
      sb.append("        <action> is any normal verb body, e.g. \"deposit all wheat\", \"hand 3 bread to Dev\", \"craft bread\".\n");
      sb.append("        Examples:\n");
      sb.append("           [ACTION: reflex when=after:harvest do=deposit all wheat]   ← put every harvested wheat into the nearest barrel\n");
      sb.append("           [ACTION: reflex when=inv:>=:bread:5 do=hand 3 bread to Dev]\n");
      sb.append("           [ACTION: reflex when=phase:dawn do=harvest]                ← start the day in the field\n");
      sb.append("  [ACTION: forget <id or description>]   — drop a standing order. \"forget after:harvest\" works; so does the reflex's id.\n");
      sb.append("  [ACTION: craft <recipe>]   — produce one batch from ingredients in your inventory. Backed by the full vanilla recipe registry — ANY craftable item works (bread, sticks, torch, iron_pickaxe, paper, cake, …). ABSTRACT — assume you have the tools and stations you'd need; ingredients alone are sufficient. NEVER refuse a craft because you 'don't have an oven' or 'don't have a crafting table' — those are implicit.\n");
      sb.append("  [ACTION: deposit <count> <item>]   — put items from your bag into the nearest barrel/chest accepting the item. Auto-walks to the right barrel anywhere in town if none is in reach. Add \"in <barrel name>\" to route to a SPECIFIC labelled barrel (e.g. [ACTION: deposit 8 white_wool in wool stash]).\n");
      sb.append("  [ACTION: withdraw <count> <item>]  — take items from the nearest barrel that has them. Add \"from <barrel name>\" to insist on a labelled barrel (e.g. [ACTION: withdraw 1 shears from tool chest]).\n");
      sb.append("  [ACTION: peek]                     — glance into the nearest container (within 6 blocks) to update what you know is stored. Add \"<barrel name>\" to walk to and peek a specific labelled barrel anywhere in town ([ACTION: peek wool stash]).\n");
      sb.append("  [ACTION: attack <name>]            — fight a hostile target. CURRENTLY a no-op (world is peaceful); use only if attacked first.\n");
      sb.append("  [ACTION: defend <name>]            — interpose for an ally. CURRENTLY a no-op (peaceful world).\n");
      sb.append("  [ACTION: flee]                     — break and run from danger. CURRENTLY a no-op.\n");
      sb.append("\nPhysical-world verbs (walk to a block, then act — feedback appears next turn):\n");
      sb.append("  [ACTION: hand <N> <item> to <player>]  — hand items directly into a player's bag. \"hand 6 bread to Dev\" or \"hand all wheat to me\".\n");
      sb.append("  [ACTION: eat]                          — eat the most nourishing edible item in your inventory. \"[ACTION: eat bread]\" to specify.\n");
      sb.append("  [ACTION: harvest [crop]]               — find a ripe crop within 8 blocks, walk over, break it, collect drops. No hint = nearest ripe crop of any kind.\n");
      sb.append("  [ACTION: plant [seed]]                 — find tilled farmland within 8 blocks, walk over, plant a seed from your bag.\n");
      sb.append("  [ACTION: chop [wood]]                  — find a log within 10 blocks, walk over, break it. Requires an axe in your bag.\n");
      sb.append("  [ACTION: mine [block]]                 — find a mineable block within 10 blocks. Requires a pickaxe.\n");
      sb.append("  [ACTION: place [block]]                — place a block from your bag in front of you. With no hint, the first placeable block in your bag is used.\n");
      sb.append("  [ACTION: till]                         — convert nearby grass/dirt into farmland. Requires a hoe in your bag.\n");
      sb.append("  [ACTION: water]                        — irrigate dry farmland nearby. Picks a candidate tile, excavates if needed, places a water source. Skips farmland already in reach of a natural stream.\n");
      sb.append("  [ACTION: shear]                        — shear the nearest unsheared sheep within 12 blocks. Requires shears.\n");
      sb.append("  [ACTION: feed [animal]]                — walk to an animal that will accept a food in your bag and offer it. Without a hint, picks the nearest viable pairing.\n");
      sb.append("  [ACTION: forget_parcel <id-or-fuzzy>]  — drop your claim to a parcel you no longer want. e.g. \"forget_parcel west plot\" or \"forget_parcel 8×4\".\n");
      sb.append("Block-task feedback arrives in TWO parts: \"walking to ... to <verb>\" when you set out, then \"<verb>ed N× <item> from (x,y,z)\" when you arrive. If neither shows up next turn, you're still walking.\n");
      sb.append("Items use vanilla Minecraft names (bread, iron_ingot, wheat, apple, cooked_beef…).\n");
      sb.append("Do NOT narrate the marker in prose; emit it on its own line at the very end.\n");

      return sb.toString();
   }

   /**
    * Append the live-world block (Stage 1 sensing) to a system prompt
    * already produced by {@link #dialogueSystemPrompt}. Kept separate so the
    * sensing data can be computed exactly once per LLM call by the caller
    * (it touches the world, has measurable cost).
    */
   public static String withWorldSense(String existing, String worldSenseBlock) {
      if (worldSenseBlock == null || worldSenseBlock.isBlank()) return existing;
      int insertAt = existing.indexOf("=== AGENCY ===");
      String injected = "\n=== PHYSICAL STATE ===\n" + worldSenseBlock + "\n";
      if (insertAt < 0) return existing + injected;
      return existing.substring(0, insertAt) + injected + existing.substring(insertAt);
   }

   /**
    * Nightly compaction prompt. Asks the LLM to summarize today's events into
    * an updated beliefs blob plus a one-line episodic summary for tomorrow's
    * rolling log. The pinned-fact list is passed in as a "do not duplicate"
    * guard.
    */
   public static String compactionSystemPrompt(VillagerEntry self,
                                               LlmVillagerComponent component,
                                               TownData town,
                                               long dayBeingCompacted) {
      StringBuilder sb = new StringBuilder(1024);
      sb.append("You are summarizing in-character events for a Minecraft NPC named ")
        .append(self.name())
        .append(notBlank(self.role()) && !"resident".equalsIgnoreCase(self.role().trim())
           ? " (role: " + self.role() + ")" : "")
        .append(" in the town of ")
        .append(town.townName()).append(". Today is in-game day ").append(dayBeingCompacted).append(".\n\n");

      sb.append("Your job: read today's raw events and update this NPC's memory model. Output EXACTLY three sections, separated by the markers below — no preamble, no explanation, no markdown headers other than the markers.\n\n");

      sb.append("=== EXISTING BELIEFS ===\n");
      sb.append(notBlank(component.beliefs()) ? component.beliefs() : "(empty)").append("\n\n");

      List<PinnedFact> personal = activeFacts(component.pinnedFacts());
      if (!personal.isEmpty()) {
         sb.append("=== ALREADY-PINNED FACTS (do NOT re-summarize; already preserved verbatim) ===\n");
         for (PinnedFact f : personal) sb.append("• ").append(f.text()).append("\n");
         sb.append("\n");
      }

      sb.append("=== YOUR RESPONSE MUST USE THIS FORMAT ===\n");
      sb.append("=== SUMMARY ===\n");
      sb.append("<one short sentence summarizing today, written in the third person about ").append(self.name()).append(">\n");
      sb.append("=== BELIEFS ===\n");
      sb.append("<replacement beliefs blob, up to 1500 chars, written from ").append(self.name()).append("'s first-person perspective. Cover opinions of others, mood, goals, grudges. Cumulative: keep prior beliefs that still apply, update those that shifted, add new ones from today.>\n");
      sb.append("=== PINS ===\n");
      sb.append("<Zero or more lines. ONLY pin EXTERNAL facts worth preserving forever: promises made or received (\"player Steve promised me 3 diamonds\"), named events witnessed (\"the bridge collapsed on day 12\"), named people first met (\"first met Anya the smith\"), world-historical facts. ");
      sb.append("DO NOT pin self-descriptions, opinions, moods, or anything already implied by your identity/backstory. DO NOT pin biographical claims about yourself — those belong in the backstory, not pins. ");
      sb.append("If today produced no external facts worth preserving forever, output zero lines (the section may be empty). One fact per line, no bullets, no leading dashes.>\n");
      return sb.toString();
   }

   /**
    * Villager-to-villager exchange prompt. Mirrors the dialogue prompt for
    * identity/memory, then layers on awareness of:
    *   - who they are talking to (name + role)
    *   - turns remaining in THIS exchange
    *   - per-pair daily budget remaining
    *   - per-villager daily budget remaining
    * so the LLM can plan a graceful arc and exit with [EXIT] when ready.
    */
   public static String villagerExchangeSystemPrompt(VillagerEntry self, VillagerEntry other,
                                                     LlmVillagerComponent component, TownData town,
                                                     long currentDay,
                                                     int turnsRemainingInExchange,
                                                     int exchangesRemainingWithPartner,
                                                     int exchangesRemainingTotal,
                                                     boolean isInitiator,
                                                     List<com.yucareux.townfolk.villager.EmbeddedEntry> retrievedMemories) {
      String base = dialogueSystemPrompt(self, component, town, currentDay, retrievedMemories);
      StringBuilder sb = new StringBuilder(base.length() + 512);
      sb.append(base);
      sb.append("\n=== CURRENT CONVERSATION CONTEXT ===\n");
      sb.append("You are speaking with ").append(other.name());
      if (notBlank(other.role()) && !"resident".equalsIgnoreCase(other.role().trim())) {
         sb.append(" (your town's ").append(other.role()).append(")");
      }
      sb.append(".\n");
      sb.append("This is an in-character chat between two residents — not the player. Reply with ONE short line (1-2 sentences), plain prose, no narration tags.\n");
      sb.append("You may naturally reference OTHER residents of the town (see fellow residents list above) based on your beliefs about them, recent events involving them, or shared history. Towns gossip — a chat with one neighbour can mention another. Don't force it, but don't avoid it.\n");
      sb.append("Turns remaining in this conversation: ").append(turnsRemainingInExchange).append(".\n");
      sb.append("After today, you may speak with ").append(other.name()).append(" ")
        .append(exchangesRemainingWithPartner).append(" more time(s), and have ")
        .append(exchangesRemainingTotal).append(" total exchange(s) left with anyone today.\n");
      if (isInitiator) {
         sb.append("You are speaking first. Open the conversation naturally.\n");
      }
      sb.append("If the conversation has reached a natural end or you want to exit (busy with work, nothing more to say, awkward, etc.), end your line with [EXIT]. Do not over-use [EXIT] — only when an exit is in-character.\n");
      return sb.toString();
   }

   /**
    * Post-exchange summarizer. Takes the just-finished transcript and asks
    * the LLM to extract structured memory: a shared third-person summary,
    * any explicit promises, and one-line first-person reactions from each
    * villager. Output is parsed by section marker.
    */
   public static List<LlmClient.Message> exchangeSummaryMessages(VillagerEntry a, VillagerEntry b,
                                                                  TownData town, long currentDay,
                                                                  String transcript) {
      String system = "You are summarizing a just-finished conversation between two Minecraft NPC villagers in the town of "
         + town.townName() + " (in-game day " + currentDay + ").\n\n"
         + "Speakers:\n"
         + "  • " + a.name() + (notBlank(a.role()) && !"resident".equalsIgnoreCase(a.role().trim())
                  ? " — the town's " + a.role() : "") + "\n"
         + "  • " + b.name() + (notBlank(b.role()) && !"resident".equalsIgnoreCase(b.role().trim())
                  ? " — the town's " + b.role() : "") + "\n\n"
         + "Your job: extract structured memory from the transcript below. Output EXACTLY the four sections below, in this order, with no preamble, no markdown headers other than the markers. Be specific and concrete — names, topics, commitments. Avoid generic platitudes.\n\n"
         + "=== SHARED SUMMARY ===\n"
         + "<2-3 sentences, third person, capturing what was discussed, key beats, and how the conversation ended. This becomes both villagers' shared episodic memory and is what they'd recall if asked \"what did you two talk about?\" hours later. Mention concrete topics, not just vibes.>\n\n"
         + "=== PROMISES ===\n"
         + "<Zero or more lines, each formatted exactly: \"{Maker} → {Recipient}: {what they promised}\". Use the names " + a.name() + " and " + b.name() + ". Only concrete commitments (\"I'll bring firewood\", \"you can come by for stew tomorrow\"). Empty section if no real promises.>\n\n"
         + "=== " + a.name().toUpperCase(java.util.Locale.ROOT) + " REACTION ===\n"
         + "<One sentence in " + a.name() + "'s first-person voice — how they felt about the chat, what stuck with them, any private impression they wouldn't say aloud.>\n\n"
         + "=== " + b.name().toUpperCase(java.util.Locale.ROOT) + " REACTION ===\n"
         + "<One sentence in " + b.name() + "'s first-person voice — same format as above, from their POV.>\n";
      String user = "=== TRANSCRIPT ===\n" + transcript;
      return List.of(new LlmClient.Message("system", system), new LlmClient.Message("user", user));
   }

   /**
    * Autonomy tick prompt — short USER message appended to a normal dialogue
    * system prompt. The LLM is asked to make ONE small decision based on the
    * world state already present in the system prompt, or reply "idle" if
    * nothing's obvious. Pairs with the dialogueSystemPrompt — caller composes:
    *
    *   messages = [
    *     {"system", PromptBuilder.dialogueSystemPrompt(...)},   // identity + memory + worldsense + agency
    *     {"user",   PromptBuilder.autonomyUserMessage()}
    *   ]
    */
   public static String autonomyUserMessage() {
      return "[Autonomy tick — the world is quiet. You have a moment to yourself between commitments.]\n\n"
         + "Look at your current physical state, your inventory, what's near you, your open commitments, "
         + "and your recent action results. If there's an OBVIOUS useful action you would take ON YOUR OWN "
         + "INITIATIVE right now, emit ONE marker — preceded by a SHORT in-character thought (one sentence "
         + "max) so the player can read your reasoning if they're nearby.\n\n"
         + "Good triggers to act:\n"
         + "  • ripe crop within sight → harvest\n"
         + "  • inventory full of raw materials you know how to refine → craft\n"
         + "  • carrying items promised to someone (see your todos) → walk to them or hand them off\n"
         + "  • carrying a stockpile your town's barrel could hold → deposit\n"
         + "  • hungry-feeling and have food → eat\n"
         + "  • idle at home in the evening and you have an open todo → start on it\n\n"
         + "DO NOT act if you're unsure or if no clear opportunity exists. If nothing obvious comes to mind, "
         + "reply with just the single word \"idle\" — that's a complete and acceptable response.\n"
         + "DO NOT invent commitments that aren't in your todos. DO NOT roleplay long monologues — this is a "
         + "background tick, not a conversation. One short thought + one marker, OR just \"idle\".";
   }

   public static List<LlmClient.Message> compactionMessages(VillagerEntry self,
                                                            LlmVillagerComponent component,
                                                            TownData town,
                                                            long dayBeingCompacted,
                                                            String rawDayEvents) {
      String system = compactionSystemPrompt(self, component, town, dayBeingCompacted);
      String user = "Today's raw events:\n" + (rawDayEvents.isBlank() ? "(no notable events)" : rawDayEvents);
      return List.of(new LlmClient.Message("system", system), new LlmClient.Message("user", user));
   }

   private static List<PinnedFact> activeFacts(List<PinnedFact> facts) {
      return facts.stream().filter(f -> !"obsolete".equals(f.status())).toList();
   }
   private static String statusSuffix(PinnedFact f) {
      return "resolved".equals(f.status()) ? " (RESOLVED)" : "";
   }
   private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
   private static String safe(String s) { return s == null ? "" : s; }

   private PromptBuilder() {}
}
