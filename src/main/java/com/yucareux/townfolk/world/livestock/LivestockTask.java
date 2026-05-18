package com.yucareux.townfolk.world.livestock;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.villager.FieldRegion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * One pluggable thing a villager can do to a farm animal — shear a sheep,
 * milk a cow, breed any pair, etc. Slots into the {@link LivestockTasks}
 * registry so {@link com.yucareux.townfolk.world.ParcelRoutine}'s ANIMAL
 * parcel handler iterates uniformly over species without hard-coding any
 * one of them.
 *
 * Each implementation answers four questions:
 *   1. <b>Tool?</b> What tool (if any) does the villager need to hold to
 *      perform this task? Used both by the parcel routine to filter
 *      candidate tasks AND by the work-need registry to know what to
 *      fetch from the treasury when missing.
 *   2. <b>Target?</b> What species of {@link Animal} does this task act
 *      on, and which individual on the parcel is currently "ready"?
 *      ("Unsheared adult sheep," "any adult cow," "an adult mate-pair
 *      not yet in love.")
 *   3. <b>Action?</b> Once the villager has walked to the target, what
 *      happens — what gets dropped, what state flips, what memory is
 *      written?
 *   4. <b>Cosmetics?</b> A short verb for logs / VillagerIdentity
 *      bucket keywords ("sheared", "milked", "bred"), and a one-line
 *      memory phrase template.
 */
public interface LivestockTask {

   /** Stable id used in logs, debug, treasury fetch keys. Lower-snake. */
   String id();

   /** Verb fragment shown in feedback strings + memory entries — matches
    *  the keyword {@link com.yucareux.townfolk.villager.VillagerIdentity}
    *  buckets on. E.g. "shear" → memories say "I sheared a black sheep". */
   String verb();

   /** The species this task acts on. The parcel routine filters animals
    *  on the parcel down to this {@link Class} before calling
    *  {@link #ready}. Generic {@link Animal} matches any species. */
   Class<? extends Animal> targetType();

   /** True if the villager is carrying the tool required for this task.
    *  Tasks that need no tool (breed task supplies its own food) return
    *  true unconditionally. */
   boolean hasTool(Villager v);

   /** Is this individual animal currently a valid target? Cheap predicate
    *  — runs over every animal on the parcel each scan. */
   boolean ready(ServerLevel level, Animal animal, Villager v);

   /** Run the action. Called from inside the EntityTask's onArrive after
    *  the villager has walked into reach. Returns a one-line feedback
    *  string. May mutate inventory, animal state, drop items, write
    *  memory. */
   String perform(ServerLevel level, Villager v, Animal animal,
                  FieldRegion parcel, TownSquareBlockEntity town);

   /** Optional item id for the auto-fetch system. {@code null} for
    *  tag-based tools — see {@link #toolTag}. */
   default String toolItemId() { return null; }

   /** Optional tool tag for the auto-fetch system. {@code null} means
    *  no tool fetch is needed. */
   default TagKey<Item> toolTag() { return null; }

   /** Convenience: find the first ready target in the parcel. */
   default Animal findReadyTargetIn(ServerLevel level, FieldRegion parcel, Villager v) {
      var mn = parcel.scanMin(); var mx = parcel.scanMax();
      AABB aabb = new AABB(mn.getX(), mn.getY(), mn.getZ(),
                           mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
      Class<? extends Animal> klass = targetType();
      for (Animal a : level.getEntitiesOfClass(klass, aabb, an -> ready(level, an, v))) {
         return a;
      }
      return null;
   }

   /** Helper: find a specific item id in the villager's bag. */
   static boolean hasItem(Villager v, String itemId) {
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
      var inv = v.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).getItem() == target) return true;
      }
      return false;
   }

   /** Helper: find an item with a given tag in the villager's bag. */
   static boolean hasToolWithTag(Villager v, TagKey<Item> tag) {
      var inv = v.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (!s.isEmpty() && s.is(tag)) return true;
      }
      return false;
   }
}
