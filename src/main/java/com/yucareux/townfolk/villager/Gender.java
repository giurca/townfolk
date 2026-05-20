package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import java.util.Locale;
import java.util.UUID;

/**
 * Villager gender — male / female only by design, because villager
 * breeding (Stage 18d) needs a clean two-sex pairing model. Same
 * codec-default-resolves-at-spawn pattern as the rest of {@code Anchors}:
 * persisted as a lowercase string ({@code "male"} / {@code "female"}),
 * unknown / missing keys backfill to {@link #MALE} so old saves don't
 * crash but the SpawnService randomises new villagers on creation.
 *
 * <p>Mirrors {@link com.yucareux.townfolk.world.Activity} (Stage 16a)
 * and {@link ProfessionTraits} (Stage 15c) — single source of truth
 * for everything gender-adjacent (display glyph, pronoun helper,
 * deterministic UUID-derived randomisation).
 */
public enum Gender {

   MALE  ("male",   "♂", "he",  "him",  "his"),
   FEMALE("female", "♀", "she", "her",  "her");

   private final String wireKey;
   private final String glyph;
   private final String pronounSubject;     // he / she
   private final String pronounObject;      // him / her
   private final String pronounPossessive;  // his / her

   Gender(String wireKey, String glyph, String he, String him, String his) {
      this.wireKey = wireKey;
      this.glyph = glyph;
      this.pronounSubject = he;
      this.pronounObject = him;
      this.pronounPossessive = his;
   }

   public String wireKey()           { return wireKey; }
   public String glyph()             { return glyph; }
   public String pronounSubject()    { return pronounSubject; }
   public String pronounObject()     { return pronounObject; }
   public String pronounPossessive() { return pronounPossessive; }

   /** {@link Codec} round-tripped through the lowercase wire key.
    *  Unknown keys deserialize to {@link #MALE} so legacy saves
    *  (test-world disposable, no migration) keep loading. */
   public static final Codec<Gender> CODEC = Codec.STRING.xmap(
      s -> fromWire(s),
      Gender::wireKey);

   public static Gender fromWire(String key) {
      if (key == null) return MALE;
      String lower = key.toLowerCase(Locale.ROOT);
      for (Gender g : values()) if (g.wireKey.equals(lower)) return g;
      return MALE;
   }

   /** Deterministic 50/50 selection driven by the villager's UUID
    *  least-significant bit. Used at spawn so any villager that
    *  didn't get an explicit gender (e.g. /townfolk talk routed
    *  spawn, or the gender field was added between sessions) lands
    *  in a stable, reproducible slot — no RNG noise across reloads. */
   public static Gender fromUuid(UUID uuid) {
      return (uuid.getLeastSignificantBits() & 1L) == 0 ? MALE : FEMALE;
   }
}
