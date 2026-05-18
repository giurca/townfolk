package com.yucareux.townfolk.town;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring buffer of recent activity events for a single town. Surfaced
 * verbatim through the admin UI so the player can see what's actually
 * happening server-side (exchanges, compactions, dialogue, errors).
 *
 * Not persisted: a fresh world load starts with an empty log. Cheap to
 * rebuild — events flow back in once the level ticks.
 */
public final class TownLog {

   public static final int MAX_ENTRIES = 200;

   public enum Level { INFO, EXCHANGE, COMPACT, DIALOGUE, WARN }

   public record Entry(long gameTime, Level level, String message) {}

   private final Deque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);

   public synchronized void add(long gameTime, Level level, String message) {
      this.entries.addLast(new Entry(gameTime, level, message));
      while (this.entries.size() > MAX_ENTRIES) this.entries.removeFirst();
   }

   public synchronized List<Entry> snapshot() {
      return new ArrayList<>(this.entries);
   }
}
