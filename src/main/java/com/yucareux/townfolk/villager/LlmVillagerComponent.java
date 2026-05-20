package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-villager data attached to the Villager entity via NeoForge's
 * AttachmentType system. Travels with the entity, persists in entity NBT.
 *
 * Architecture (RAG-augmented):
 *   1. Identity              — personaSeed + backstory (frozen / regenerated)
 *   2. Pinned facts          — long-term promises, named events (capped 100)
 *   3. Beliefs               — single text blob, rewritten nightly cumulatively
 *   4. Todos                 — open commitments (capped 30)
 *   5. Memories (RAG)        — embedded experience entries (capped 1000)
 *   6. Live context          — assembled at prompt time from world state
 *
 * The experience layer is no longer the bounded {@code recentEvents} list
 * of 30 entries — it's the unbounded (up to MAX) {@link EmbeddedEntry} list,
 * retrieved by relevance per query via {@link MemoryStore}.
 */
public record LlmVillagerComponent(
   String personaSeed,
   String backstory,
   String memoryNamespace,
   long townSquarePos,
   List<DialogueTurn> dialogueHistory,
   int llmCalls,
   long inputTokens,
   long outputTokens,
   List<PinnedFact> pinnedFacts,
   String beliefs,
   List<EmbeddedEntry> memories,
   long lastCompactedDay,
   List<Todo> todos,
   Anchors anchors,
   List<Reflex> reflexes,
   List<FieldRegion> parcels
) {

   /** Bundles the player-set anchor flags + hunger so the parent
    *  record stays under Mojang Codec's 16-field limit. {@code home}
    *  / {@code job} == "the player explicitly assigned this villager
    *  a home/workstation". {@code hunger} is 0..100, where 100 = full,
    *  0 = starving (Stage 12). Preserves the external accessor
    *  surface ({@link #playerSetHome()} / {@link #playerSetJob()})
    *  so call sites don't need to change. */
   public record Anchors(boolean home, boolean job, int hunger) {
      public Anchors {
         hunger = Math.max(0, Math.min(100, hunger));
      }
      public static final Anchors NONE = new Anchors(false, false, 100);
      public static final com.mojang.serialization.Codec<Anchors> CODEC =
         com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("home", false).forGetter(Anchors::home),
            Codec.BOOL.optionalFieldOf("job",  false).forGetter(Anchors::job),
            Codec.INT.optionalFieldOf("hunger", 100).forGetter(Anchors::hunger)
         ).apply(i, Anchors::new));
      public Anchors withHome(boolean v)   { return new Anchors(v, job, hunger); }
      public Anchors withJob(boolean v)    { return new Anchors(home, v, hunger); }
      public Anchors withHunger(int v)     { return new Anchors(home, job, v); }
   }

   /** Backwards-compat accessor — preserves the {@code playerSetHome} reader
    *  used by every consumer of this component. */
   public boolean playerSetHome() { return anchors.home(); }
   public boolean playerSetJob()  { return anchors.job(); }
   /** 0..100; 100 = freshly fed, 0 = starving. Decays ~25 per game
    *  day via {@link com.yucareux.townfolk.world.ScheduleService}'s
    *  dawn rollover; tops up when the villager eats. */
   public int hunger() { return anchors.hunger(); }

   /** Convenience for callers that prefer chaining on the component
    *  directly (rather than {@code .withAnchors(c.anchors().withHunger(...))}). */
   public LlmVillagerComponent withHunger(int v) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors.withHunger(v), reflexes, parcels);
   }

   public static final int MAX_HISTORY = 24;
   public static final int MAX_PINNED = 100;
   public static final int MAX_TODOS = 30;
   public static final int MAX_MEMORIES = 1000;
   public static final int MAX_BELIEFS_CHARS = 1500;
   public static final int MAX_REFLEXES = 10;
   public static final int MAX_PARCELS = 8;
   public static final long TODO_DECAY_DAYS = 5L;

   public static final LlmVillagerComponent EMPTY = new LlmVillagerComponent(
      "", "", "", 0L, List.of(), 0, 0L, 0L, List.of(), "", List.of(), 0L, List.of(),
      Anchors.NONE, List.of(), List.of());

   public static final Codec<LlmVillagerComponent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.STRING.optionalFieldOf("persona_seed", "").forGetter(LlmVillagerComponent::personaSeed),
      Codec.STRING.optionalFieldOf("backstory", "").forGetter(LlmVillagerComponent::backstory),
      Codec.STRING.optionalFieldOf("memory_namespace", "").forGetter(LlmVillagerComponent::memoryNamespace),
      Codec.LONG.optionalFieldOf("town_square_pos", 0L).forGetter(LlmVillagerComponent::townSquarePos),
      DialogueTurn.CODEC.listOf().optionalFieldOf("dialogue_history", List.of()).forGetter(LlmVillagerComponent::dialogueHistory),
      Codec.INT.optionalFieldOf("llm_calls", 0).forGetter(LlmVillagerComponent::llmCalls),
      Codec.LONG.optionalFieldOf("input_tokens", 0L).forGetter(LlmVillagerComponent::inputTokens),
      Codec.LONG.optionalFieldOf("output_tokens", 0L).forGetter(LlmVillagerComponent::outputTokens),
      PinnedFact.CODEC.listOf().optionalFieldOf("pinned_facts", List.of()).forGetter(LlmVillagerComponent::pinnedFacts),
      Codec.STRING.optionalFieldOf("beliefs", "").forGetter(LlmVillagerComponent::beliefs),
      EmbeddedEntry.CODEC.listOf().optionalFieldOf("memories", List.of()).forGetter(LlmVillagerComponent::memories),
      Codec.LONG.optionalFieldOf("last_compacted_day", 0L).forGetter(LlmVillagerComponent::lastCompactedDay),
      Todo.CODEC.listOf().optionalFieldOf("todos", List.of()).forGetter(LlmVillagerComponent::todos),
      Anchors.CODEC.optionalFieldOf("anchors", Anchors.NONE).forGetter(LlmVillagerComponent::anchors),
      Reflex.CODEC.listOf().optionalFieldOf("reflexes", List.of()).forGetter(LlmVillagerComponent::reflexes),
      FieldRegion.CODEC.listOf().optionalFieldOf("parcels", List.of()).forGetter(LlmVillagerComponent::parcels)
   ).apply(instance, LlmVillagerComponent::new));

   private LlmVillagerComponent copyWith(List<EmbeddedEntry> newMemories) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, newMemories,
         lastCompactedDay, todos, anchors, reflexes, parcels);
   }

   public LlmVillagerComponent withBackstory(String newBackstory) {
      return new LlmVillagerComponent(personaSeed, newBackstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withPersonaSeed(String newSeed) {
      return new LlmVillagerComponent(newSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withAppendedTurn(String role, String text) {
      List<DialogueTurn> next = new ArrayList<>(dialogueHistory);
      next.add(new DialogueTurn(role, text));
      while (next.size() > MAX_HISTORY) next.remove(0);
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         List.copyOf(next), llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withUsage(long addIn, long addOut) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls + 1, inputTokens + addIn, outputTokens + addOut,
         pinnedFacts, beliefs, memories, lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withPinnedFacts(List<PinnedFact> next) {
      List<PinnedFact> trimmed = next.size() <= MAX_PINNED ? next : next.subList(next.size() - MAX_PINNED, next.size());
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, List.copyOf(trimmed), beliefs,
         memories, lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withBeliefs(String newBeliefs) {
      String capped = newBeliefs == null ? "" :
         (newBeliefs.length() > MAX_BELIEFS_CHARS ? newBeliefs.substring(0, MAX_BELIEFS_CHARS) : newBeliefs);
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, capped,
         memories, lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withMemories(List<EmbeddedEntry> next) {
      List<EmbeddedEntry> trimmed = next.size() <= MAX_MEMORIES ? next
         : next.subList(next.size() - MAX_MEMORIES, next.size());
      return copyWith(List.copyOf(trimmed));
   }
   public LlmVillagerComponent withAppendedMemory(EmbeddedEntry m) {
      List<EmbeddedEntry> next = new ArrayList<>(memories);
      next.add(m);
      return withMemories(next);
   }
   public LlmVillagerComponent withLastCompactedDay(long day) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         day, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withClearedDialogueHistory() {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         List.of(), llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withTodos(List<Todo> next) {
      List<Todo> trimmed = next.size() <= MAX_TODOS ? next : next.subList(next.size() - MAX_TODOS, next.size());
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, List.copyOf(trimmed), anchors, reflexes, parcels);
   }
   public LlmVillagerComponent withAppendedTodo(Todo t) {
      List<Todo> next = new ArrayList<>(todos);
      next.add(t);
      return withTodos(next);
   }
   public LlmVillagerComponent withTownSquarePos(long newPos) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, newPos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, reflexes, parcels);
   }

   public LlmVillagerComponent withPlayerSetHome(boolean v) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors.withHome(v), reflexes, parcels);
   }
   public LlmVillagerComponent withPlayerSetJob(boolean v) {
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors.withJob(v), reflexes, parcels);
   }

   public LlmVillagerComponent withReflexes(List<Reflex> next) {
      List<Reflex> trimmed = next.size() <= MAX_REFLEXES ? next
         : next.subList(next.size() - MAX_REFLEXES, next.size());
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, List.copyOf(trimmed), parcels);
   }

   public LlmVillagerComponent withAppendedReflex(Reflex r) {
      List<Reflex> next = new ArrayList<>(reflexes);
      next.add(r);
      return withReflexes(next);
   }

   public LlmVillagerComponent withReflexFired(String reflexId, long tick) {
      List<Reflex> next = new ArrayList<>(reflexes);
      for (int i = 0; i < next.size(); i++) {
         if (next.get(i).id().equals(reflexId)) {
            next.set(i, next.get(i).withLastFired(tick));
            break;
         }
      }
      return withReflexes(next);
   }

   public LlmVillagerComponent withoutReflex(String reflexId) {
      List<Reflex> next = new ArrayList<>(reflexes);
      next.removeIf(r -> r.id().equals(reflexId));
      return withReflexes(next);
   }

   public LlmVillagerComponent withParcels(List<FieldRegion> next) {
      List<FieldRegion> trimmed = next.size() <= MAX_PARCELS ? next
         : next.subList(next.size() - MAX_PARCELS, next.size());
      return new LlmVillagerComponent(personaSeed, backstory, memoryNamespace, townSquarePos,
         dialogueHistory, llmCalls, inputTokens, outputTokens, pinnedFacts, beliefs, memories,
         lastCompactedDay, todos, anchors, reflexes, List.copyOf(trimmed));
   }

   public LlmVillagerComponent withAppendedParcel(FieldRegion p) {
      List<FieldRegion> next = new ArrayList<>(parcels);
      next.add(p);
      return withParcels(next);
   }

   public LlmVillagerComponent withoutParcel(String parcelId) {
      List<FieldRegion> next = new ArrayList<>(parcels);
      next.removeIf(p -> p.id().equals(parcelId));
      return withParcels(next);
   }

   public Optional<String> backstoryOrEmpty() {
      return (backstory == null || backstory.isBlank()) ? Optional.empty() : Optional.of(backstory);
   }
}
