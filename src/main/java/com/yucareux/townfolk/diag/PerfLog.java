package com.yucareux.townfolk.diag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight rolling-average timer harness used by the hot per-tick
 * services (ParcelRoutine, NeedsService, MealService, ScheduleService).
 * Stage 19 instrumentation — landed before any algorithmic fixes so
 * the optimisation pass has empirical numbers to target instead of
 * speculative ones.
 *
 * <p>Usage:
 * <pre>{@code
 *   long t0 = PerfLog.now();
 *   ...hot work...
 *   PerfLog.sample("ParcelRoutine.scanParcel", PerfLog.now() - t0);
 * }</pre>
 *
 * <p>Every {@link #FLUSH_EVERY_SAMPLES}th sample, the per-phase
 * running stats (count + total nanos + max nanos) get serialized to
 * {@link VerboseLog} category {@code PERF} as a compact summary line.
 * Reports include count, mean μs, p100 max μs.
 *
 * <p>All operations are O(1). Map keys are interned phase-name
 * strings; ConcurrentHashMap handles cross-thread updates without
 * locking. The sample method bails fast when verbose-diagnostics is
 * off, so leaving call sites in place at runtime costs ~one volatile
 * read per call when disabled.
 */
public final class PerfLog {

   /** Flush a rolling summary every Nth sample per phase. 500 keeps
    *  the log readable without losing fidelity at high call counts. */
   private static final int FLUSH_EVERY_SAMPLES = 500;

   private static final Map<String, Stats> PHASES = new ConcurrentHashMap<>();

   /** Per-phase rolling stats. {@code count}/{@code totalNanos} are
    *  monotonic over the process lifetime; {@code maxSinceFlush} +
    *  {@code countSinceFlush} reset on each flush so each summary
    *  line covers exactly one window. */
   private static final class Stats {
      long count;
      long totalNanos;
      long countSinceFlush;
      long totalNanosSinceFlush;
      long maxNanosSinceFlush;
   }

   private PerfLog() {}

   /** Convenience for the start of a measurement block. Same as
    *  {@link System#nanoTime()} but renamed for symmetry with the
    *  {@code sample} call site. */
   public static long now() { return System.nanoTime(); }

   /** Record an elapsed-time sample. Bails immediately if verbose
    *  diagnostics are off — leave the call sites in place; they cost
    *  almost nothing at runtime when disabled. */
   public static void sample(String phase, long elapsedNanos) {
      if (!com.yucareux.townfolk.config.TownfolkConfig.COMMON.verboseDiagnostics.get()) return;
      Stats s = PHASES.computeIfAbsent(phase, k -> new Stats());
      synchronized (s) {
         s.count++;
         s.totalNanos += elapsedNanos;
         s.countSinceFlush++;
         s.totalNanosSinceFlush += elapsedNanos;
         if (elapsedNanos > s.maxNanosSinceFlush) s.maxNanosSinceFlush = elapsedNanos;
         if (s.countSinceFlush >= FLUSH_EVERY_SAMPLES) {
            flushLocked(phase, s);
         }
      }
   }

   /** Force-flush every phase's accumulated stats. Useful for end-of-
    *  session summaries. Caller owns no lock; {@code flushLocked} reads
    *  + resets atomically. */
   public static void flushAll() {
      if (!com.yucareux.townfolk.config.TownfolkConfig.COMMON.verboseDiagnostics.get()) return;
      for (var e : PHASES.entrySet()) {
         synchronized (e.getValue()) {
            if (e.getValue().countSinceFlush > 0) {
               flushLocked(e.getKey(), e.getValue());
            }
         }
      }
   }

   /** Caller MUST hold the Stats lock. */
   private static void flushLocked(String phase, Stats s) {
      double meanUs = (s.totalNanosSinceFlush / (double) s.countSinceFlush) / 1000.0;
      double maxUs  = s.maxNanosSinceFlush / 1000.0;
      double lifetimeMeanUs = s.count == 0 ? 0
         : (s.totalNanos / (double) s.count) / 1000.0;
      VerboseLog.write("PERF",
         "phase=" + phase
            + " window_n=" + s.countSinceFlush
            + " window_mean_us=" + String.format(java.util.Locale.ROOT, "%.2f", meanUs)
            + " window_max_us="  + String.format(java.util.Locale.ROOT, "%.2f", maxUs)
            + " lifetime_n=" + s.count
            + " lifetime_mean_us=" + String.format(java.util.Locale.ROOT, "%.2f", lifetimeMeanUs),
         "");
      s.countSinceFlush = 0;
      s.totalNanosSinceFlush = 0;
      s.maxNanosSinceFlush = 0;
   }
}
