package com.yucareux.townfolk.dialogue;

import java.util.Locale;

/**
 * Extracts the body of a {@code === SECTION ===}-style block out of an
 * LLM response. Shared by {@link NightlyCompactor} and
 * {@link VillagerExchangeService}, which previously had two near-duplicate
 * inline copies of this logic.
 *
 * <p>Format the response is expected to follow:
 * <pre>
 *   === SUMMARY ===
 *   one or more lines of summary text…
 *   === BELIEFS ===
 *   - belief one
 *   - belief two
 *   === PINS ===
 *   …
 * </pre>
 *
 * <p>{@link #section} returns the body of one named section (everything
 * between its header line and the next {@code ===} OR the next sibling
 * section header — whichever comes first). Section names are matched
 * case-insensitively. Returns {@code null} if the named header isn't
 * present at all.
 */
public final class SectionParser {

   private SectionParser() {}

   /** Parse one section, treating only {@code "==="} as a terminator. */
   public static String section(String response, String name) {
      return section(response, name, EMPTY);
   }

   /**
    * Parse one section, terminating at either {@code "==="} or the first
    * occurrence of any name in {@code otherSectionNames} (skip-self).
    *
    * <p>The extra terminator list matters when an LLM emits sibling
    * headers without the leading {@code ===} (e.g. just {@code "PINS"} on
    * its own line). Pass the full set of expected sibling names so a
    * missing terminator doesn't bleed the next section into this one.
    */
   public static String section(String response, String name, String[] otherSectionNames) {
      String upper = response.toUpperCase(Locale.ROOT);
      String key = name.toUpperCase(Locale.ROOT);
      int i = upper.indexOf(key);
      if (i < 0) return null;
      int eol = response.indexOf('\n', i);
      if (eol < 0) return "";
      int start = eol + 1;
      int next = response.indexOf("===", start);
      for (String o : otherSectionNames) {
         if (o.equalsIgnoreCase(name)) continue;
         int alt = upper.indexOf(o.toUpperCase(Locale.ROOT), start);
         if (alt > 0 && (next < 0 || alt < next)) next = alt;
      }
      return (next < 0 ? response.substring(start) : response.substring(start, next)).trim();
   }

   private static final String[] EMPTY = new String[0];
}
