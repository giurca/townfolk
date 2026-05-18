package com.yucareux.townfolk.town;

/**
 * Kinds of "town-extending" blocks. Each type knows how big a coverage
 * disk it contributes when registered with a town.
 *
 * <p>Why an enum, not a registry of resource-locations? Internal-only
 * for now — we control every type. If players or other mods ever need
 * to define their own auxiliary kinds, swap this for a real registry.
 * Until then, keeping it an enum:
 * <ul>
 *   <li>Compile-time safety on the type → radius mapping.
 *   <li>Trivial NBT serialization (just the {@link #name()}).
 *   <li>Unknown values on load (an old save's removed type, or a
 *       newer save loaded on an older mod) gracefully skip instead of
 *       crashing — see {@link #safeFromName(String)}.
 * </ul>
 */
public enum TownAuxiliaryType {
   /** Trade Post: enables the trades feature and extends the town's
    *  coverage by 64 blocks. Crafted from planks + smooth stone + paper. */
   TRADE_POST(64);

   /** Coverage disk radius this aux block contributes to its town. */
   private final int contributedRadius;

   TownAuxiliaryType(int contributedRadius) {
      this.contributedRadius = contributedRadius;
   }

   public int contributedRadius() { return contributedRadius; }

   /** Tolerant {@link #valueOf} replacement: returns {@code null} for
    *  unknown names instead of throwing. Used by load paths where a
    *  stale save might reference a removed type. */
   public static TownAuxiliaryType safeFromName(String name) {
      if (name == null) return null;
      for (TownAuxiliaryType t : values()) if (t.name().equals(name)) return t;
      return null;
   }
}
