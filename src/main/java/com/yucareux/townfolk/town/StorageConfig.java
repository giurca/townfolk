package com.yucareux.townfolk.town;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Player-declared configuration for a single storage container in the
 * town. Replaces the auto-discovery model: villagers ONLY route to /
 * from containers that the player has explicitly registered via the
 * sneak+empty-hand-right-click flow.
 *
 * The filter list is 16 ghost slots — only the {@link ItemStack#getItem()}
 * kind matters, counts are ignored. {@link #acceptsDeposit} checks
 * whether a given item passes the {@link StorageFilterMode} test.
 *
 * Identity (registeredBy / registeredAt) is informational — useful for
 * Jade tooltips and the admin panel ("set by Player at day 12"). The
 * configuration itself is location-keyed by the parent
 * {@link StorageRegistry}.
 */
public final class StorageConfig {

   public static final int FILTER_SLOTS = 16;

   private StorageFilterMode mode;
   private final List<ItemStack> filter;
   private String label;
   private final UUID registeredBy;
   private final long registeredAt;
   /** Last villager/player who deposited/withdrew/peeked. Defaults to
    *  the registering player; nameable for the LLM-facing prompt. */
   private String lastTouchedName;
   private long lastTouchedAt;

   public StorageConfig(StorageFilterMode mode, List<ItemStack> filter,
                        String label, UUID registeredBy, long registeredAt) {
      this.mode = mode == null ? StorageFilterMode.WHITELIST : mode;
      this.filter = padOrTrim(filter == null ? List.of() : filter, FILTER_SLOTS);
      this.label = label == null ? "" : label;
      this.registeredBy = registeredBy;
      this.registeredAt = registeredAt;
      this.lastTouchedName = "";
      this.lastTouchedAt = registeredAt;
   }

   /** Initial config when a player first opens the picker on a chest. */
   public static StorageConfig empty(UUID registeredBy, long registeredAt) {
      return new StorageConfig(StorageFilterMode.WHITELIST,
         emptyList(FILTER_SLOTS), "", registeredBy, registeredAt);
   }

   public StorageFilterMode mode() { return mode; }
   public List<ItemStack> filter() { return filter; }
   public String label() { return label; }
   public UUID registeredBy() { return registeredBy; }
   public long registeredAt() { return registeredAt; }
   public String lastTouchedName() { return lastTouchedName; }
   public long lastTouchedAt() { return lastTouchedAt; }

   public void setMode(StorageFilterMode mode) { this.mode = mode; }
   public void setLabel(String label) { this.label = label == null ? "" : label; }
   /** Record that {@code who} just deposited/withdrew/peeked at this
    *  container. Used by the LLM-facing "Nearby storage" summary to
    *  show recent activity ("last touched by Anna, 3m ago"). */
   public void touch(String who, long gameTime) {
      this.lastTouchedName = who == null ? "" : who;
      this.lastTouchedAt = gameTime;
   }
   public void setFilterSlot(int idx, ItemStack stack) {
      if (idx < 0 || idx >= FILTER_SLOTS) return;
      ItemStack copy = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
      filter.set(idx, copy);
   }

   /** Does the deposit-filter accept this item kind? Whitelist mode
    *  requires presence; blacklist mode requires absence. Empty
    *  whitelist → reject all; empty blacklist → accept all. */
   public boolean acceptsDeposit(ItemStack stack) {
      if (stack == null || stack.isEmpty()) return false;
      boolean inList = false;
      for (ItemStack f : filter) {
         if (!f.isEmpty() && f.getItem() == stack.getItem()) { inList = true; break; }
      }
      return mode == StorageFilterMode.WHITELIST ? inList : !inList;
   }

   /** Count of non-empty filter slots, for UI / tooltip display. */
   public int filterCount() {
      int n = 0;
      for (ItemStack s : filter) if (!s.isEmpty()) n++;
      return n;
   }

   // ───── NBT persistence ─────

   public CompoundTag save(HolderLookup.Provider registries) {
      CompoundTag tag = new CompoundTag();
      tag.putString("mode", mode.name());
      tag.putString("label", label);
      tag.putUUID("by", registeredBy);
      tag.putLong("at", registeredAt);
      tag.putString("lastBy", lastTouchedName == null ? "" : lastTouchedName);
      tag.putLong("lastAt", lastTouchedAt);
      ListTag list = new ListTag();
      for (ItemStack s : filter) {
         if (s.isEmpty()) {
            list.add(new CompoundTag());                    // empty placeholder, keeps slot index
         } else {
            list.add((CompoundTag) ItemStack.OPTIONAL_CODEC
               .encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), s)
               .getOrThrow());
         }
      }
      tag.put("filter", list);
      return tag;
   }

   public static StorageConfig load(CompoundTag tag, HolderLookup.Provider registries) {
      StorageFilterMode mode = StorageFilterMode.WHITELIST;
      if (tag.contains("mode")) {
         String modeStr = tag.getString("mode");
         try {
            mode = StorageFilterMode.valueOf(modeStr);
         } catch (IllegalArgumentException ex) {
            com.yucareux.townfolk.Townfolk.LOGGER.warn(
               "StorageConfig: unrecognised mode \"{}\" — defaulting to WHITELIST", modeStr);
         }
      }
      String label = tag.getString("label");
      UUID by = tag.hasUUID("by") ? tag.getUUID("by") : new UUID(0L, 0L);
      long at = tag.getLong("at");
      List<ItemStack> filter = new ArrayList<>(FILTER_SLOTS);
      ListTag list = tag.getList("filter", Tag.TAG_COMPOUND);
      for (int i = 0; i < FILTER_SLOTS; i++) {
         if (i < list.size()) {
            CompoundTag entry = list.getCompound(i);
            if (entry.isEmpty()) {
               filter.add(ItemStack.EMPTY);
            } else {
               ItemStack s = ItemStack.OPTIONAL_CODEC
                  .parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), entry)
                  .result().orElse(ItemStack.EMPTY);
               filter.add(s);
            }
         } else {
            filter.add(ItemStack.EMPTY);
         }
      }
      StorageConfig cfg = new StorageConfig(mode, filter, label, by, at);
      // Restore last-touched metadata if present (post-Phase-4 saves).
      if (tag.contains("lastBy")) cfg.lastTouchedName = tag.getString("lastBy");
      if (tag.contains("lastAt")) cfg.lastTouchedAt = tag.getLong("lastAt");
      return cfg;
   }

   private static List<ItemStack> emptyList(int n) {
      List<ItemStack> l = new ArrayList<>(n);
      for (int i = 0; i < n; i++) l.add(ItemStack.EMPTY);
      return l;
   }

   private static List<ItemStack> padOrTrim(List<ItemStack> src, int target) {
      List<ItemStack> out = new ArrayList<>(target);
      for (int i = 0; i < target; i++) {
         out.add(i < src.size() && src.get(i) != null ? src.get(i) : ItemStack.EMPTY);
      }
      return out;
   }
}
