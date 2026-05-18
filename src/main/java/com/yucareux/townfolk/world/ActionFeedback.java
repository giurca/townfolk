package com.yucareux.townfolk.world;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-villager ring buffer of recent action outcomes (last few minutes only),
 * surfaced back to the LLM in the next prompt so it knows whether its marker
 * actually did anything.
 *
 * Why this exists: without it, the LLM emits [ACTION: deposit …] and has no
 * idea whether it worked. On the next turn it tends to hedge ("I'll get to
 * it"). With this block in the prompt the LLM has hard evidence to either
 * confirm or retry, and the absence of an expected line is itself a signal
 * that a marker was missed last turn.
 *
 * Implementation note: ticks would be ideal for ageing entries, but the
 * prompt-build site doesn't have a {@code Level} reference. Wall-clock
 * (System.currentTimeMillis) is good enough — these are transient UI hints,
 * not gameplay state, and the cost of a few seconds of drift across a save
 * pause is zero.
 *
 * Entries auto-expire after {@link #MAX_AGE_MS}. Not persisted.
 */
public final class ActionFeedback {

   public record Entry(long wallTimeMs, boolean ok, String text) {}

   private static final int CAP_PER_VILLAGER = 16;
   private static final long MAX_AGE_MS = 8 * 60 * 1000L;     // 8 minutes

   private static final Map<UUID, Deque<Entry>> BY_VILLAGER = new ConcurrentHashMap<>();

   public static void recordOk(UUID actor, long ignoredGameTick, String text) {
      record(actor, new Entry(System.currentTimeMillis(), true, text));
   }

   public static void recordFail(UUID actor, long ignoredGameTick, String text) {
      record(actor, new Entry(System.currentTimeMillis(), false, text));
   }

   private static void record(UUID actor, Entry e) {
      BY_VILLAGER.compute(actor, (k, deque) -> {
         Deque<Entry> d = deque == null ? new ArrayDeque<>() : deque;
         d.addLast(e);
         while (d.size() > CAP_PER_VILLAGER) d.removeFirst();
         return d;
      });
   }

   /** Trim expired entries, return the rest oldest-first. */
   public static List<Entry> recentFor(UUID actor) {
      Deque<Entry> d = BY_VILLAGER.get(actor);
      if (d == null) return List.of();
      long now = System.currentTimeMillis();
      synchronized (d) {
         while (!d.isEmpty() && now - d.peekFirst().wallTimeMs > MAX_AGE_MS) {
            d.removeFirst();
         }
         return List.copyOf(d);
      }
   }

   /** Prompt-friendly multi-line block. Empty when no surviving entries. */
   public static String formatFor(UUID actor) {
      var entries = recentFor(actor);
      if (entries.isEmpty()) return "";
      long now = System.currentTimeMillis();
      StringBuilder sb = new StringBuilder();
      for (Entry e : entries) {
         long ageS = Math.max(0, (now - e.wallTimeMs) / 1000L);
         sb.append(e.ok ? "  ✓ " : "  ✗ ").append(e.text);
         if (ageS > 0) sb.append(" (").append(ageS).append("s ago)");
         sb.append('\n');
      }
      return sb.toString();
   }

   private ActionFeedback() {}
}
