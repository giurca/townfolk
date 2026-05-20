package com.yucareux.townfolk.villager;

/**
 * Villager life-stage bucket derived from age-in-days. Drives
 * gating rules across systems — only adults can breed, only adults
 * fully participate in the parcel routine, elders eventually retire,
 * children get a future protection band. The thresholds are loose
 * Minecraft-game-day approximations (one MC day ≈ 20 minutes
 * real-time at default tick rate).
 *
 * <p>Adding stages later (e.g. {@code NEWBORN}, {@code TODDLER}) is a
 * matter of inserting a new enum value with appropriate thresholds —
 * the {@link #fromDays} dispatcher is the single source of truth.
 *
 * <p>Pairs with {@link Gender} (both fold into
 * {@link LlmVillagerComponent.Anchors}). Together they form the
 * "physical identity" stripe that drives breeding + future age-
 * sensitive mechanics like profession unlocks.
 */
public enum AgeCategory {

   /** 0 — 29 days. Cannot work parcels or breed. Future: protection
    *  band + accelerated growth visuals. */
   CHILD(0, 30),

   /** 30 — 89 days. Apprentice band — limited parcel participation,
    *  no breeding. Future: profession-trial mechanic. */
   ADOLESCENT(30, 90),

   /** 90 — 1999 days. Full participation: works parcels, can breed
    *  (if paired with opposite-gender adult), eligible for every
    *  profession + every dialogue beat. */
   ADULT(90, 2000),

   /** 2000+ days. Retired band. Future hook: stops working parcels,
    *  becomes wisdom giver (boosts nearby memory compaction quality),
    *  eventually dies of old age. Currently functionally identical to
    *  ADULT — exists so the gating predicates can already query
    *  {@code .isElder()} without a follow-up refactor. */
   ELDER(2000, Integer.MAX_VALUE);

   private final int minDaysInclusive;
   private final int maxDaysExclusive;

   AgeCategory(int minDaysInclusive, int maxDaysExclusive) {
      this.minDaysInclusive = minDaysInclusive;
      this.maxDaysExclusive = maxDaysExclusive;
   }

   public int minDaysInclusive() { return minDaysInclusive; }
   public int maxDaysExclusive() { return maxDaysExclusive; }

   public boolean isChild()      { return this == CHILD; }
   public boolean isAdolescent() { return this == ADOLESCENT; }
   public boolean isAdult()      { return this == ADULT; }
   public boolean isElder()      { return this == ELDER; }

   /** True if breeding (Stage 18e) is permitted for this age. Only
    *  ADULT — the rest get a polite memory line explaining why. */
   public boolean canBreed() { return this == ADULT; }

   /** True if this age can participate in the full parcel work
    *  routine. CHILD is excluded; ADOLESCENT/ADULT/ELDER all
    *  participate today. */
   public boolean canWork()  { return this != CHILD; }

   public static AgeCategory fromDays(int ageDays) {
      if (ageDays < ADOLESCENT.minDaysInclusive) return CHILD;
      if (ageDays < ADULT.minDaysInclusive)      return ADOLESCENT;
      if (ageDays < ELDER.minDaysInclusive)      return ADULT;
      return ELDER;
   }

   /** Short label for UI ("child" / "elder"). Use
    *  {@link Gender#glyph()} alongside for full identity strip. */
   public String displayLabel() {
      return switch (this) {
         case CHILD      -> "child";
         case ADOLESCENT -> "adolescent";
         case ADULT      -> "adult";
         case ELDER      -> "elder";
      };
   }
}
