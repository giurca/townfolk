package com.yucareux.townfolk.town;

/**
 * How a {@link StorageConfig}'s filter list is interpreted. The same 16
 * filter slots can be either an inclusive whitelist or an exclusive
 * blacklist depending on this mode.
 *
 *   WHITELIST — only items appearing in the filter list are accepted.
 *               An empty whitelist accepts NOTHING (the chest is sealed
 *               until configured).
 *   BLACKLIST — every item EXCEPT those in the filter list is accepted.
 *               An empty blacklist accepts EVERYTHING (true overflow).
 */
public enum StorageFilterMode {
   WHITELIST,
   BLACKLIST;

   public String label() {
      return switch (this) {
         case WHITELIST -> "Whitelist";
         case BLACKLIST -> "Blacklist";
      };
   }

   public StorageFilterMode toggled() {
      return this == WHITELIST ? BLACKLIST : WHITELIST;
   }
}
