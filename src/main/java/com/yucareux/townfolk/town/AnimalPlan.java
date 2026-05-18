package com.yucareux.townfolk.town;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Per-parcel animal husbandry plan. One entry per species the player
 * has configured a target for. Species not listed are uncontrolled
 * (default behaviour — no breeding push, no culling).
 *
 * <p>Stage 5 v1 supports two modes:
 * <ul>
 *   <li>{@link Mode#BREED_UP} — herder actively breeds adults until
 *       the species reaches {@code targetCount}. Required feed must
 *       be in the herder's inventory.
 *   <li>{@link Mode#HOLD} — no breeding, no culling. Used to "lock in"
 *       a flock size that's already at target.
 * </ul>
 *
 * <p>{@code CULL} mode (slaughter excess for meat/leather) is
 * deliberately deferred to a later stage — it's the first villager
 * behaviour that kills entities and deserves separate review.
 *
 * <p>Records are immutable; the menu / payload layer rebuilds the
 * whole plan on every save.
 */
public record AnimalPlan(List<Entry> entries) {

   /** Maximum target a player can set per species. 64 is generous but
    *  not unbounded — runaway breeding hurts everything. */
   public static final int MAX_TARGET = 64;

   public static final AnimalPlan EMPTY = new AnimalPlan(List.of());

   public AnimalPlan {
      // Defensive copy; record's contract requires immutability.
      entries = Collections.unmodifiableList(new ArrayList<>(entries));
   }

   public Optional<Entry> findSpecies(String speciesId) {
      for (Entry e : entries) if (e.speciesId().equals(speciesId)) return Optional.of(e);
      return Optional.empty();
   }

   public enum Mode {
      BREED_UP,
      HOLD;

      public static Mode safeFromName(String name) {
         if (name == null) return null;
         for (Mode m : values()) if (m.name().equals(name)) return m;
         return null;
      }
   }

   /**
    * @param speciesId   Minecraft entity-type id, e.g. "minecraft:cow"
    * @param targetCount target adult+baby count, clamped to [0, MAX_TARGET]
    * @param mode        what the herder should do toward the target
    */
   public record Entry(String speciesId, int targetCount, Mode mode) {
      public Entry {
         if (speciesId == null || speciesId.isBlank()) {
            throw new IllegalArgumentException("speciesId must not be blank");
         }
         if (mode == null) throw new IllegalArgumentException("mode must not be null");
         if (targetCount < 0)            targetCount = 0;
         else if (targetCount > MAX_TARGET) targetCount = MAX_TARGET;
      }

      CompoundTag save() {
         CompoundTag tag = new CompoundTag();
         tag.putString("species", speciesId);
         tag.putInt("target", targetCount);
         tag.putString("mode", mode.name());
         return tag;
      }

      static Entry load(CompoundTag tag) {
         String species = tag.getString("species");
         if (species == null || species.isBlank()) return null;
         int target = tag.contains("target") ? tag.getInt("target") : 0;
         Mode mode = Mode.safeFromName(tag.contains("mode") ? tag.getString("mode") : "HOLD");
         if (mode == null) mode = Mode.HOLD;
         return new Entry(species, target, mode);
      }
   }

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      ListTag list = new ListTag();
      for (Entry e : entries) list.add(e.save());
      tag.put("entries", list);
      return tag;
   }

   public static AnimalPlan load(CompoundTag tag) {
      if (!tag.contains("entries")) return EMPTY;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      List<Entry> out = new ArrayList<>(list.size());
      for (int i = 0; i < list.size(); i++) {
         Entry e = Entry.load(list.getCompound(i));
         if (e != null) out.add(e);
      }
      return new AnimalPlan(out);
   }
}
