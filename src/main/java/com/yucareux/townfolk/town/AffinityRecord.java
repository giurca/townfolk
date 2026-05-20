package com.yucareux.townfolk.town;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * Per-pair social state — accumulated tavern-banter interactions
 * between two villagers, summarised into a {@link Tier}. Stage 22a.
 *
 * <p>{@code score} is a running sum of per-banter deltas (+1
 * neutral, +2 warm, -1 cold, -3 hostile in the planned tone model;
 * Stage 22b initially treats all banter as +1 neutral). {@code Tier}
 * is derived from {@code score} + {@code banterCount} so a single
 * hostile encounter doesn't immediately drop "close friend" to
 * "rival" — the count threshold builds in inertia.
 *
 * <p>Stored on {@link TownData} keyed by ordered UUID-pair string;
 * persisted via NBT through {@link #save(CompoundTag)} +
 * {@link #load(CompoundTag)}.
 */
public final class AffinityRecord {

   /** Discrete relationship buckets. Mapping is in {@link #tierFor}. */
   public enum Tier {
      STRANGER,
      ACQUAINTANCE,
      FRIEND,
      CLOSE,
      RIVAL;

      /** Short label for UI chips ("friend", "close"). */
      public String displayLabel() {
         return switch (this) {
            case STRANGER     -> "stranger";
            case ACQUAINTANCE -> "acquaintance";
            case FRIEND       -> "friend";
            case CLOSE        -> "close";
            case RIVAL        -> "rival";
         };
      }

      /** Colour hint for UI rendering. */
      public int color() {
         return switch (this) {
            case STRANGER     -> 0xFF7A6849;       // FAINT
            case ACQUAINTANCE -> 0xFFB89B70;       // MUTED
            case FRIEND       -> 0xFF7AB46A;       // OK / green
            case CLOSE        -> 0xFFFFD27A;       // HEADING / gold
            case RIVAL        -> 0xFFC76A50;       // ERROR / red
         };
      }
   }

   /** Tone sentiment for one banter exchange. Each enum maps to a
    *  score delta — picked up by RelationshipService.onBanter. */
   public enum Tone {
      WARM(2), NEUTRAL(1), COLD(-1), HOSTILE(-3);
      private final int delta;
      Tone(int d) { this.delta = d; }
      public int scoreDelta() { return delta; }
   }

   private final UUID a;
   private final UUID b;
   private int score;
   private int banterCount;
   private long lastBanterDay;

   public AffinityRecord(UUID a, UUID b, int score, int banterCount, long lastBanterDay) {
      // Canonical-order the pair so a/b are stable regardless of caller.
      if (a.compareTo(b) > 0) { UUID t = a; a = b; b = t; }
      this.a = a;
      this.b = b;
      this.score = score;
      this.banterCount = banterCount;
      this.lastBanterDay = lastBanterDay;
   }

   public UUID a() { return a; }
   public UUID b() { return b; }
   public int score() { return score; }
   public int banterCount() { return banterCount; }
   public long lastBanterDay() { return lastBanterDay; }

   /** True if {@code uuid} is one of the two parties — convenience
    *  for caller iteration. */
   public boolean involves(UUID uuid) { return uuid.equals(a) || uuid.equals(b); }

   /** Return the other party in this pair given one. */
   public UUID other(UUID one) { return one.equals(a) ? b : a; }

   /** Apply a banter delta and update the rolling day. */
   public void recordBanter(Tone tone, long day) {
      this.score += tone.scoreDelta();
      this.banterCount++;
      this.lastBanterDay = day;
   }

   /** Discrete tier from score + count. Designed with inertia:
    *  - count thresholds gate ACQUAINTANCE/FRIEND/CLOSE
    *  - RIVAL requires a {@link #RIVAL_DEADBAND}-strong negative score
    *    (audit P1 fix — Stage 22): without this, a pair sitting at
    *    score=0 with mixed COLD/WARM tone-tagged banter would
    *    ping-pong RIVAL↔FRIEND each exchange, spamming TownLog
    *    transitions + per-villager memory entries.
    *
    *  <p>{@link Tone#NEUTRAL} (the only tone fired today) is +1, so
    *  the current state evolution is monotonic — the deadband only
    *  matters once 22b's tone-tagged banter schema lands. Worth
    *  shipping the fix preemptively so it doesn't bite later. */
   public static final int RIVAL_DEADBAND = -2;

   public Tier tier() {
      return tierFor(score, banterCount);
   }

   public static Tier tierFor(int score, int banterCount) {
      if (banterCount == 0)            return Tier.STRANGER;
      if (score <= RIVAL_DEADBAND)     return Tier.RIVAL;
      if (banterCount <= 2)            return Tier.ACQUAINTANCE;
      if (banterCount <= 8)            return Tier.FRIEND;
      return Tier.CLOSE;
   }

   /** Canonical key for {@link TownData}-side maps. */
   public static String key(UUID x, UUID y) {
      return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
   }

   public CompoundTag save() {
      CompoundTag t = new CompoundTag();
      t.putUUID("a", a);
      t.putUUID("b", b);
      t.putInt("score", score);
      t.putInt("count", banterCount);
      t.putLong("last_day", lastBanterDay);
      return t;
   }

   public static AffinityRecord load(CompoundTag t) {
      return new AffinityRecord(
         t.getUUID("a"), t.getUUID("b"),
         t.getInt("score"), t.getInt("count"), t.getLong("last_day"));
   }
}
