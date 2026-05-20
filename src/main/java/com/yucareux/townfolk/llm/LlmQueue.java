package com.yucareux.townfolk.llm;

import com.yucareux.townfolk.diag.VerboseLog;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Stage 25 — priority + concurrency cap for outbound LLM requests.
 *
 * <p>Background: with 50+ villagers the OpenRouter throttle becomes
 * the system-wide bottleneck (1.5–3s per call × N villagers = LLM
 * traffic jam). Dialogue (player-facing) suffers the most because
 * autonomy + banter happily fill the connection pool with non-
 * interactive calls. This queue enforces:
 * <ul>
 *   <li><b>Priority lanes</b> — DIALOGUE → AUTONOMY → BANTER. A
 *       BANTER call defers if any higher-priority call is waiting.
 *   <li><b>Concurrency cap</b> — at most {@link #MAX_IN_FLIGHT}
 *       calls in flight at once. The cap leaves headroom for
 *       OpenRouter's rate limit (default 20 req/s on most paid
 *       tiers) without saturating it.
 *   <li><b>Bounded queue</b> — each lane has a soft cap; over-
 *       budget calls reject (the caller gets a completed-with-
 *       exception future) instead of memory-leaking.
 * </ul>
 *
 * <p>The submit method wraps a no-arg {@code Supplier<CompletableFuture<T>>}
 * so the rate limiter doesn't need to know the call's signature —
 * it just decides WHEN to invoke the supplier and what to do with
 * the resulting future. Callers (LlmClient.chat, dialogue service,
 * banter) flip from a direct call to {@code LlmQueue.submit(lane, ()
 * -> originalCall)}.
 */
public final class LlmQueue {

   /** Priority lanes, highest priority first. */
   public enum Lane {
      DIALOGUE,    // player-facing chat — never starve
      AUTONOMY,    // villager autonomous think — bursty
      BANTER       // tavern banter — bulk filler
   }

   /** Total in-flight cap across all lanes. Tunable via config in a
    *  future pass; default 4 leaves headroom for typical OpenRouter
    *  paid-tier limits. */
   public static final int MAX_IN_FLIGHT = 4;

   /** Per-lane soft queue cap before rejection. */
   public static final int MAX_PER_LANE = 32;

   private static final Object MUTEX = new Object();
   private static int inFlight = 0;
   private static final Map<Lane, Deque<PendingCall<?>>> QUEUES = new EnumMap<>(Lane.class);
   static {
      for (Lane l : Lane.values()) QUEUES.put(l, new ArrayDeque<>());
   }

   private record PendingCall<T>(Lane lane, Supplier<CompletableFuture<T>> supplier,
                                  CompletableFuture<T> result) {}

   private LlmQueue() {}

   /** Submit a deferred LLM call. The supplier is invoked when the
    *  queue decides the call should run; the returned future
    *  completes when the underlying call completes (or with an
    *  exception if the queue rejected the request).
    *
    *  <p>{@code lane} drives priority: lower lanes wait for higher
    *  lanes to drain. */
   public static <T> CompletableFuture<T> submit(Lane lane,
                                                  Supplier<CompletableFuture<T>> supplier) {
      CompletableFuture<T> result = new CompletableFuture<>();
      synchronized (MUTEX) {
         Deque<PendingCall<?>> q = QUEUES.get(lane);
         if (q.size() >= MAX_PER_LANE) {
            // Over budget — reject the call so we don't memory-leak.
            VerboseLog.write("LLM_QUEUE_REJECT",
               "lane=" + lane.name() + " depth=" + q.size(),
               "lane queue full (cap=" + MAX_PER_LANE + ")");
            result.completeExceptionally(new IllegalStateException(
               "LLM queue full for lane " + lane));
            return result;
         }
         q.addLast(new PendingCall<>(lane, supplier, result));
      }
      drain();
      return result;
   }

   /** Pump pending calls until either the concurrency cap is reached
    *  or no waiting calls exist. Lane priority strictly enforced —
    *  DIALOGUE always picked before AUTONOMY before BANTER. */
   private static void drain() {
      while (true) {
         PendingCall<?> next;
         synchronized (MUTEX) {
            if (inFlight >= MAX_IN_FLIGHT) return;
            next = pickHighestPriorityLocked();
            if (next == null) return;
            inFlight++;
         }
         dispatchAsync(next);
      }
   }

   private static PendingCall<?> pickHighestPriorityLocked() {
      for (Lane lane : Lane.values()) {        // enum is declared in priority order
         var q = QUEUES.get(lane);
         var c = q.pollFirst();
         if (c != null) return c;
      }
      return null;
   }

   private static <T> void dispatchAsync(PendingCall<T> call) {
      CompletableFuture<T> downstream;
      try {
         downstream = call.supplier.get();
      } catch (Throwable t) {
         finishLocked();
         call.result.completeExceptionally(t);
         return;
      }
      if (downstream == null) {
         finishLocked();
         call.result.completeExceptionally(new NullPointerException(
            "LLM call supplier returned null future"));
         return;
      }
      downstream.whenComplete((value, err) -> {
         finishLocked();
         if (err != null) call.result.completeExceptionally(err);
         else             call.result.complete(value);
      });
   }

   private static void finishLocked() {
      synchronized (MUTEX) {
         inFlight = Math.max(0, inFlight - 1);
      }
      drain();    // wake up the next waiter
   }

   /** Snapshot for diagnostics — total in-flight + per-lane queue
    *  depths. Read-only; safe to call from any thread. */
   public static String snapshot() {
      synchronized (MUTEX) {
         StringBuilder sb = new StringBuilder("inFlight=").append(inFlight);
         for (Lane l : Lane.values()) {
            sb.append(' ').append(l.name().toLowerCase()).append('=').append(QUEUES.get(l).size());
         }
         return sb.toString();
      }
   }
}
