package com.yucareux.townfolk.villager;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.llm.EmbeddingClient;
import com.yucareux.townfolk.registry.ModRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Per-villager RAG memory store. Static helpers because the data lives on
 * the entity's {@link LlmVillagerComponent} attachment.
 *
 * Writes are fire-and-forget — the caller can keep mutating the component
 * normally; the embed call fires async and the splice happens on the
 * server thread when the vector arrives. The entry is invisible to the
 * system until then. If embed fails or the entity unloads, the entry is
 * dropped silently (no half-formed memories cluttering recall).
 *
 * Reads (retrieve / retrieveAsync) are synchronous-ish: retrieveAsync
 * dispatches a query embed call then synchronously dot-products against
 * the existing entries when the query vector lands.
 */
public final class MemoryStore {

   public static final int MAX_ENTRIES = LlmVillagerComponent.MAX_MEMORIES;

   /** Fire-and-forget write. Embed-then-splice happens async. */
   public static void write(Entity actor, String kind, long day, String text) {
      if (actor == null || text == null || text.isBlank()) return;
      if (!(actor.level() instanceof ServerLevel sl)) return;
      MinecraftServer server = sl.getServer();
      if (server == null) return;
      UUID actorUuid = actor.getUUID();
      String entryId = UUID.randomUUID().toString();
      String who = actor.hasCustomName() ? actor.getCustomName().getString() : actorUuid.toString().substring(0, 8);

      VerboseLog.write("MEM_WRITE_PENDING",
         "actor=" + who + " kind=" + kind + " day=" + day + " id=" + entryId, text);

      EmbeddingClient.get().embed(text).thenAcceptAsync(vec -> {
         if (vec.length == 0) {
            VerboseLog.write("MEM_EMBED_FAIL", "actor=" + who + " id=" + entryId, text);
            return;
         }
         Entity target = sl.getEntity(actorUuid);
         if (target == null) {
            VerboseLog.write("MEM_EMBED_DROPPED",
               "actor=" + who + " id=" + entryId + " reason=entity_unloaded", "");
            return;
         }
         LlmVillagerComponent c = target.getData(ModRegistries.LLM_VILLAGER.get());
         List<Float> boxed = new ArrayList<>(vec.length);
         for (float f : vec) boxed.add(f);
         List<EmbeddedEntry> next = new ArrayList<>(c.memories());
         next.add(new EmbeddedEntry(entryId, kind, day, text, boxed));
         target.setData(ModRegistries.LLM_VILLAGER.get(), c.withMemories(next));
         VerboseLog.write("MEM_EMBED_OK",
            "actor=" + who + " id=" + entryId + " dim=" + vec.length + " total=" + next.size(), "");
      }, server);
   }

   /** Top-K entries by cosine similarity to a pre-computed normalised vector. */
   public static List<EmbeddedEntry> retrieve(LlmVillagerComponent c, float[] queryVec, int k) {
      if (queryVec.length == 0 || c.memories().isEmpty()) return List.of();
      List<Scored> scored = new ArrayList<>(c.memories().size());
      for (EmbeddedEntry e : c.memories()) {
         if (e.isPending()) continue;
         double s = e.similarity(queryVec);
         if (Double.isInfinite(s)) continue;
         scored.add(new Scored(e, s));
      }
      scored.sort(Comparator.comparingDouble((Scored x) -> x.score).reversed());
      List<EmbeddedEntry> out = new ArrayList<>(Math.min(k, scored.size()));
      for (int i = 0; i < scored.size() && i < k; i++) out.add(scored.get(i).e);
      return out;
   }

   /** Top-K by recency. Fallback when embedding fails or query is empty. */
   public static List<EmbeddedEntry> retrieveRecent(LlmVillagerComponent c, int k) {
      var mems = c.memories();
      if (mems.isEmpty()) return List.of();
      int start = Math.max(0, mems.size() - k);
      return new ArrayList<>(mems.subList(start, mems.size()));
   }

   /** Embed a query then return top-K by similarity. Falls back to recency. */
   public static CompletableFuture<List<EmbeddedEntry>> retrieveAsync(LlmVillagerComponent c,
                                                                      String queryText, int k) {
      if (queryText == null || queryText.isBlank() || c.memories().isEmpty()) {
         return CompletableFuture.completedFuture(retrieveRecent(c, k));
      }
      return EmbeddingClient.get().embed(queryText).thenApply(qv -> {
         if (qv.length == 0) return retrieveRecent(c, k);
         List<EmbeddedEntry> hit = retrieve(c, qv, k);
         return hit.isEmpty() ? retrieveRecent(c, k) : hit;
      });
   }

   /** Linear-scan filter for memories on a specific day (used by compaction). */
   public static List<EmbeddedEntry> memoriesForDay(LlmVillagerComponent c, long day) {
      List<EmbeddedEntry> out = new ArrayList<>();
      for (EmbeddedEntry e : c.memories()) {
         if (e.createdDay() == day && !e.isPending()) out.add(e);
      }
      return out;
   }

   private record Scored(EmbeddedEntry e, double score) {}

   private MemoryStore() {}
}
