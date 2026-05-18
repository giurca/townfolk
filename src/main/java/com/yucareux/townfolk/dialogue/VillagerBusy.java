package com.yucareux.townfolk.dialogue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared in-flight registry for villagers. Both player-dialogue and
 * villager-to-villager exchange services consult this so a single villager
 * cannot be driving two LLM calls at once (race, double-charge, garbled
 * transcript).
 *
 * Self-healing: every lock is stamped with the wall-clock acquisition time
 * and auto-expires after {@link #MAX_HOLD_MS}. If a service crashes mid-flow
 * and forgets to {@link #markFree}, the villager unsticks on its own within
 * a few minutes rather than being permanently bricked.
 *
 * The auto-expire is generous (5 min) — longer than any well-behaved LLM
 * round trip, but short enough that a stuck villager recovers without the
 * player having to restart the world.
 */
public final class VillagerBusy {

   /** Stale-lock auto-release threshold. Real LLM round trips finish in
    *  under 30s; anything past 5 min is a leak we should recover from. */
   private static final long MAX_HOLD_MS = 5L * 60 * 1000;

   private static final Map<UUID, Long> BUSY_SINCE = new ConcurrentHashMap<>();

   public static boolean isBusy(UUID uuid) {
      Long since = BUSY_SINCE.get(uuid);
      if (since == null) return false;
      if (System.currentTimeMillis() - since > MAX_HOLD_MS) {
         BUSY_SINCE.remove(uuid, since);   // remove only if still our stale stamp
         return false;
      }
      return true;
   }

   /** Atomically acquire the lock. Returns {@code true} if we now hold it
    *  (either it was free, or the previous holder's stamp had expired).
    *  Returns {@code false} only if a fresh holder is still working. */
   public static boolean markBusy(UUID uuid) {
      long now = System.currentTimeMillis();
      boolean[] acquired = { false };
      BUSY_SINCE.compute(uuid, (k, prev) -> {
         if (prev == null || now - prev > MAX_HOLD_MS) {
            acquired[0] = true;
            return now;
         }
         return prev;
      });
      return acquired[0];
   }

   public static void markFree(UUID uuid) { BUSY_SINCE.remove(uuid); }

   private VillagerBusy() {}
}
