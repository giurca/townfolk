package com.yucareux.townfolk.world.livestock;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.MemoryStore;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Breed two adult animals of a given species when:
 *   1. The villager is carrying at least 2 of any food that species
 *      accepts (delegated to vanilla {@link Animal#isFood(ItemStack)}).
 *   2. There are at least two non-baby, not-already-in-love animals of
 *      the species on the parcel, within {@link #PAIR_RADIUS} of each
 *      other (so they'll actually walk into love-mode pairing range).
 *
 * Implementation: one entity-task walks to ANY adult of the species,
 * consumes a food from the bag, sets it in love. Vanilla's in-love
 * pairing AI then handles the rest. The parcel routine will chain-fire
 * after the task completes; the second animal becomes the new
 * "ready" target on the next pass.
 *
 * Cooldown: once an animal is in love, vanilla flags
 * {@code Animal#canFallInLove()} false until breeding completes, so
 * {@link #ready} naturally skips that animal. We also rate-limit per
 * parcel via the deposit-cycle (if eggs/milk/wool fill the bag, the
 * villager deposits before breeding the next pair) so a parcel of 50
 * pigs doesn't pump 200 piglets in 60 seconds.
 */
public final class BreedTask implements LivestockTask {

   /** Two adults within this many blocks of each other ⇒ valid pair. */
   private static final double PAIR_RADIUS = 8.0;

   private final String id;
   private final Class<? extends Animal> species;

   private BreedTask(String id, Class<? extends Animal> species) {
      this.id = id;
      this.species = species;
   }

   public static BreedTask forSheep()   { return new BreedTask("breed_sheep",   Sheep.class);   }
   public static BreedTask forCow()     { return new BreedTask("breed_cow",     Cow.class);     }
   public static BreedTask forPig()     { return new BreedTask("breed_pig",     Pig.class);     }
   public static BreedTask forChicken() { return new BreedTask("breed_chicken", Chicken.class); }
   public static BreedTask forRabbit()  { return new BreedTask("breed_rabbit",  Rabbit.class);  }

   @Override public String id()   { return id; }
   @Override public String verb() { return "breed"; }
   @Override public Class<? extends Animal> targetType() { return species; }

   /** Breed needs no specific tool — it consults the bag at
    *  {@link #ready} time against the candidate animal's own
    *  {@link Animal#isFood} predicate, so we sidestep the question of
    *  "which crop is the breeding food for THIS species" and let
    *  vanilla answer.  */
   @Override public boolean hasTool(Villager v) { return true; }

   @Override public boolean ready(ServerLevel level, Animal animal, Villager v) {
      if (animal.isBaby()) return false;
      if (!animal.canFallInLove()) return false;
      // Bag must contain a food this animal accepts.
      if (!bagHasFoodFor(v, animal)) return false;
      // Need at least one other adult mate of the same species nearby.
      var around = new AABB(animal.getX() - PAIR_RADIUS, animal.getY() - 2, animal.getZ() - PAIR_RADIUS,
                            animal.getX() + PAIR_RADIUS, animal.getY() + 2, animal.getZ() + PAIR_RADIUS);
      List<? extends Animal> mates = level.getEntitiesOfClass(species, around,
         m -> m != animal && !m.isBaby() && m.canFallInLove());
      return !mates.isEmpty();
   }

   @Override public String perform(ServerLevel level, Villager v, Animal animal,
                                   FieldRegion parcel, TownSquareBlockEntity town) {
      var inv = v.getInventory();
      int slot = -1;
      ItemStack food = null;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (!s.isEmpty() && animal.isFood(s)) {
            slot = i; food = s; break;
         }
      }
      if (slot < 0 || food == null) {
         throw new RuntimeException("food gone before breed");
      }
      String foodName = food.getHoverName().getString();
      inv.getItem(slot).shrink(1);
      if (inv.getItem(slot).isEmpty()) inv.setItem(slot, ItemStack.EMPTY);

      animal.setInLove(null);
      level.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
         animal.getX(), animal.getY() + animal.getBbHeight(), animal.getZ(),
         6, 0.2, 0.2, 0.2, 0);

      String species = animal.getType().getDescription().getString().toLowerCase();
      long day = level.getGameTime() / 24000L;
      String parcelTag = parcel == null ? "this spot"
         : parcel.shortLabel(town.getBlockPos());
      MemoryStore.write(v, "work", day,
         "At my " + parcelTag + " I fed a " + species + " " + foodName
            + " to get them to breed.");
      return "fed a " + species + " " + foodName + " (breeding)";
   }

   private static boolean bagHasFoodFor(Villager v, Animal animal) {
      var inv = v.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (!s.isEmpty() && animal.isFood(s)) return true;
      }
      return false;
   }
}
