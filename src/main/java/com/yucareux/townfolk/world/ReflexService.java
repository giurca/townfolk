package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.Reflex;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Deterministic rule engine that fires villager {@link Reflex}es when their
 * trigger conditions are met. Designed so:
 *
 *   1. ACTIONS are NOT special — every reflex's action body is a normal
 *      [ACTION: …] verb body dispatched through
 *      {@link ToolDispatcher#execute}. Any verb the LLM can emit, a reflex
 *      can fire. New verbs work for reflexes for free.
 *
 *   2. TRIGGERS are namespaced strings so new families slot in without a
 *      schema change. v1 supports:
 *        after:<verb>            — edge, fired by BlockTask completion +
 *                                  by ToolDispatcher (for non-block verbs)
 *        inv:>=:<item>:<n>       — level, evaluated on slow inventory tick
 *        inv:<=:<item>:<n>       — level, ditto
 *        phase:<phase>           — edge, fired by ScheduleService on phase
 *                                  transition
 *
 *   3. SCALE is fine at 50 villagers × 10 reflexes. All evaluation is in
 *      a single service; per-tick cost is O(villagers × reflexes_per).
 *      Each reflex has a cooldown (see {@link Reflex#COOLDOWN_TICKS}) so
 *      level-triggered ones don't refire every check.
 *
 * Hook surfaces (called from existing services):
 *   - {@link #onAfterVerb}                   ← BlockTaskQueue completion,
 *                                              ToolDispatcher after each
 *                                              inline verb
 *   - {@link #onPhaseEnter}                  ← ScheduleService phase boundary
 *   - {@link #onTick} (this class)            ← inventory predicate ticker
 *
 * Creation paths (all converge on {@link #addReflex}):
 *   - LLM inline: [ACTION: reflex when=… do=…]
 *   - Post-dialogue extractor (phase 2 — TODO)
 *   - Admin UI (phase 2 — TODO)
 *
 * Removal: [ACTION: forget <id-or-fuzzy-text>] via ToolDispatcher.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class ReflexService {

   /** Inventory predicates are level-triggered, so we have to poll. 30 s is
    *  cheap (block scan is microseconds) but slow enough that the cooldown
    *  inside each reflex prevents thrash. */
   private static final int INVENTORY_SCAN_INTERVAL_TICKS = 20 * 30;

   /** Wire up the BlockTask completion hook. Class-init runs at mod load
    *  (because @EventBusSubscriber forces class loading before tick events
    *  start firing), so this is safely in place by the first villager tick. */
   static {
      BlockTaskQueue.addCompletionListener((level, actor, task) ->
         onAfterVerb(level, actor, task.verb()));
   }

   // ───── public API ─────

   /** Append a reflex to a villager. Caps the list at
    *  {@link LlmVillagerComponent#MAX_REFLEXES}. Returns the added reflex
    *  (or {@code Optional.empty()} on validation failure). */
   public static Optional<Reflex> addReflex(ServerLevel level, Villager actor,
                                             String trigger, String action) {
      if (trigger == null || trigger.isBlank()) return Optional.empty();
      if (action == null || action.isBlank()) return Optional.empty();
      String t = canonicaliseTrigger(trigger);
      if (t == null) {
         VerboseLog.write("REFLEX_REJECT", "actor=" + nameOf(actor),
            "unknown trigger=" + trigger);
         return Optional.empty();
      }
      String a = action.trim();

      LlmVillagerComponent comp = actor.getData(ModRegistries.LLM_VILLAGER.get());
      // Replace-by-trigger semantics: a villager has at most ONE standing order
      // per trigger. Re-issuing "after:harvest do=…" overwrites the previous
      // action instead of stacking — otherwise "deposit all wheat" alongside
      // "deposit all potatoes" both fire every harvest, one silently failing.
      // To keep two distinct rules on the same trigger, vary the trigger
      // (e.g. inv:>=:wheat:1 vs inv:>=:potato:1).
      var prior = comp.reflexes().stream()
         .filter(r2 -> r2.trigger().equalsIgnoreCase(t))
         .findFirst();
      long day = level.getGameTime() / 24000L;
      Reflex r = new Reflex(UUID.randomUUID().toString().substring(0, 8),
         t, a, day, Reflex.NEVER_FIRED);
      if (prior.isPresent()) {
         var nextList = new java.util.ArrayList<>(comp.reflexes());
         nextList.removeIf(rr -> rr.id().equals(prior.get().id()));
         nextList.add(r);
         actor.setData(ModRegistries.LLM_VILLAGER.get(), comp.withReflexes(nextList));
         VerboseLog.write("REFLEX_REPLACE",
            "actor=" + nameOf(actor) + " trigger=" + t,
            "was=\"" + prior.get().action() + "\" now=\"" + a + "\"");
         return Optional.of(r);
      }
      actor.setData(ModRegistries.LLM_VILLAGER.get(), comp.withAppendedReflex(r));
      VerboseLog.write("REFLEX_ADD", "actor=" + nameOf(actor),
         "id=" + r.id() + " when=" + t + " do=" + a);
      return Optional.of(r);
   }

   /** Remove a reflex by id OR by fuzzy match of its action text. */
   public static boolean removeReflex(Villager actor, String idOrFuzzy) {
      LlmVillagerComponent comp = actor.getData(ModRegistries.LLM_VILLAGER.get());
      String needle = idOrFuzzy.toLowerCase(Locale.ROOT).trim();
      Reflex match = null;
      for (Reflex r : comp.reflexes()) {
         if (r.id().equals(idOrFuzzy)) { match = r; break; }
      }
      if (match == null) {
         for (Reflex r : comp.reflexes()) {
            if (r.action().toLowerCase(Locale.ROOT).contains(needle)
                || r.trigger().toLowerCase(Locale.ROOT).contains(needle)) {
               match = r; break;
            }
         }
      }
      if (match == null) return false;
      actor.setData(ModRegistries.LLM_VILLAGER.get(), comp.withoutReflex(match.id()));
      VerboseLog.write("REFLEX_REMOVE", "actor=" + nameOf(actor),
         "id=" + match.id() + " when=" + match.trigger() + " do=" + match.action());
      return true;
   }

   /** Called by BlockTaskQueue completion + ToolDispatcher inline verbs.
    *  Fires every reflex whose trigger is {@code after:<verb>}. */
   public static void onAfterVerb(ServerLevel level, Villager actor, String verb) {
      LlmVillagerComponent comp = actor.getData(ModRegistries.LLM_VILLAGER.get());
      if (comp.reflexes().isEmpty()) return;
      String want = ("after:" + verb).toLowerCase(Locale.ROOT);
      fireMatching(level, actor, comp, want);
   }

   /** Called by ScheduleService when a villager's day-phase changes. */
   public static void onPhaseEnter(ServerLevel level, Villager actor, String newPhase) {
      LlmVillagerComponent comp = actor.getData(ModRegistries.LLM_VILLAGER.get());
      if (comp.reflexes().isEmpty()) return;
      String want = ("phase:" + newPhase).toLowerCase(Locale.ROOT);
      fireMatching(level, actor, comp, want);
   }

   /** Slow tick: evaluate inventory-predicate reflexes. */
   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % INVENTORY_SCAN_INTERVAL_TICKS != 0L) return;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            Entity ent = level.getEntity(entry.uuid());
            if (!(ent instanceof LlmTownsfolk v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.reflexes().isEmpty()) continue;
            evalInventoryReflexes(level, v, comp);
         }
      }
   }

   // ───── core matching + firing ─────

   private static void fireMatching(ServerLevel level, Villager actor,
                                    LlmVillagerComponent comp, String triggerString) {
      long now = level.getGameTime();
      // Snapshot the reflex ids that match, then re-read the component
      // for each fire — earlier the loop iterated the original list
      // even after a reflex's action mutated the reflex set, so a
      // self-modifying reflex (e.g. one that emits `forget <id>`) would
      // still fire siblings that had been removed. Snapshotting ids
      // lets us re-validate against the live component before firing.
      java.util.List<String> matchingIds = new java.util.ArrayList<>();
      for (Reflex r : comp.reflexes()) {
         if (!r.trigger().equalsIgnoreCase(triggerString)) continue;
         if (r.lastFiredTick() > Reflex.NEVER_FIRED
             && now > r.lastFiredTick()
             && now - r.lastFiredTick() < Reflex.COOLDOWN_TICKS) continue;
         matchingIds.add(r.id());
      }
      for (String id : matchingIds) {
         LlmVillagerComponent live = actor.getData(ModRegistries.LLM_VILLAGER.get());
         Reflex r = null;
         for (Reflex candidate : live.reflexes()) {
            if (candidate.id().equals(id)) { r = candidate; break; }
         }
         if (r == null) continue;       // reflex was removed mid-loop
         fireReflex(level, actor, r);
      }
   }

   private static void evalInventoryReflexes(ServerLevel level, Villager actor, LlmVillagerComponent comp) {
      long now = level.getGameTime();
      // Snapshot ids first (same defence as fireMatching) — a fired
      // inventory reflex can mutate the reflex set via [ACTION: forget …]
      // and corrupt iteration if we walk the original list.
      java.util.List<String> matchingIds = new java.util.ArrayList<>();
      for (Reflex r : comp.reflexes()) {
         if (!r.trigger().startsWith("inv:")) continue;
         if (r.lastFiredTick() > Reflex.NEVER_FIRED
             && now > r.lastFiredTick()
             && now - r.lastFiredTick() < Reflex.COOLDOWN_TICKS) continue;
         if (!matchInventory(actor, r.trigger())) continue;
         matchingIds.add(r.id());
      }
      for (String id : matchingIds) {
         LlmVillagerComponent live = actor.getData(ModRegistries.LLM_VILLAGER.get());
         Reflex r = null;
         for (Reflex candidate : live.reflexes()) {
            if (candidate.id().equals(id)) { r = candidate; break; }
         }
         if (r == null) continue;       // forgotten by an earlier reflex's action
         fireReflex(level, actor, r);
      }
   }

   /** Trigger format: "inv:>=:<item>:<count>" or "inv:<=:<item>:<count>". */
   private static boolean matchInventory(Villager actor, String trigger) {
      String[] parts = trigger.split(":");
      if (parts.length != 4) return false;
      String op = parts[1];
      String itemId = parts[2].contains("/") ? parts[2] : "minecraft:" + parts[2];
      int threshold;
      try { threshold = Integer.parseInt(parts[3]); } catch (NumberFormatException e) { return false; }
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      if (target == null) return false;
      int have = 0;
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() == target) have += s.getCount();
      }
      return ">=".equals(op) ? have >= threshold
           : "<=".equals(op) ? have <= threshold
           : false;
   }

   private static void fireReflex(ServerLevel level, Villager actor, Reflex r) {
      VerboseLog.write("REFLEX_FIRE", "actor=" + nameOf(actor),
         "id=" + r.id() + " when=" + r.trigger() + " do=" + r.action());
      // Find the villager's town for ToolDispatcher.execute (needs it for
      // logging + town-scoped lookups).
      LlmVillagerComponent comp = actor.getData(ModRegistries.LLM_VILLAGER.get());
      TownSquareBlockEntity town = null;
      if (comp.townSquarePos() != 0L) {
         var be = level.getBlockEntity(net.minecraft.core.BlockPos.of(comp.townSquarePos()));
         if (be instanceof TownSquareBlockEntity t) town = t;
      }
      if (town == null) return;
      VillagerEntry self = town.getTown().findVillager(actor.getUUID()).orElse(null);
      if (self == null) return;

      // Stamp lastFired BEFORE the action runs, so any synchronous recursion
      // through onAfterVerb doesn't refire the same reflex inside its own
      // execution.
      actor.setData(ModRegistries.LLM_VILLAGER.get(), comp.withReflexFired(r.id(), level.getGameTime()));
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
         self.name() + " standing order fires: " + r.trigger() + " → " + r.action());

      // Execute through the normal verb dispatch. Reflexes share the LLM's
      // verb surface verbatim — no special-casing.
      try {
         ToolDispatcher.execute(level, town, actor, self, r.action());
      } catch (Throwable t) {
         VerboseLog.write("REFLEX_ERROR", "actor=" + nameOf(actor),
            "id=" + r.id() + " error=" + t);
      }
   }

   /** Normalise variant trigger spellings into our canonical form. Returns
    *  null if the trigger is unrecognised, so {@link #addReflex} can reject
    *  it and give the LLM a chance to retry with a valid form. */
   public static String canonicaliseTrigger(String raw) {
      String s = raw.trim().toLowerCase(Locale.ROOT);
      // "after:<verb>"
      if (s.startsWith("after:") || s.startsWith("after ")) {
         String verb = s.substring(s.indexOf(s.startsWith("after:") ? ':' : ' ') + 1).trim();
         if (verb.isEmpty()) return null;
         return "after:" + verb;
      }
      // "phase:<phase>"
      if (s.startsWith("phase:") || s.startsWith("phase ")) {
         String phase = s.substring(s.indexOf(s.startsWith("phase:") ? ':' : ' ') + 1).trim();
         if (phase.isEmpty()) return null;
         return "phase:" + phase;
      }
      // "inv:>=:<item>:<n>" / "inv:<=:..." — already canonical
      if (s.startsWith("inv:")) {
         String[] parts = s.split(":");
         if (parts.length == 4 && (">=".equals(parts[1]) || "<=".equals(parts[1]))) return s;
         return null;
      }
      return null;
   }

   private static String nameOf(Villager v) {
      return v.hasCustomName() ? v.getCustomName().getString()
                               : v.getUUID().toString().substring(0, 8);
   }

   private ReflexService() {}
}
