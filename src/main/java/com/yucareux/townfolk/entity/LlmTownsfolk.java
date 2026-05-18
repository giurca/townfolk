package com.yucareux.townfolk.entity;

import com.mojang.serialization.Dynamic;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.Level;

/**
 * Our LLM-driven NPC entity. Extends {@link Villager} purely so we keep the
 * vanilla renderer, model, profession data, and trading infrastructure for
 * free — but with the Brain neutered to zero registered behaviors so vanilla
 * AI never claims POIs, never wanders, never panics, never tries to schedule
 * itself.
 *
 * All movement comes from:
 *   - {@code goalSelector} goals registered here (currently just FloatGoal so
 *     they don't drown), and
 *   - direct {@code navigation.moveTo} calls from FollowService / IntentExecutor.
 *
 * Phase 1 = parity with current Villager-with-overrides behavior, minus the
 * memory-erase loops. Phase 2 adds custom Goals (sleep, work, idle wander).
 */
public class LlmTownsfolk extends Villager {

   /**
    * Synced server→client field so the client renderer can draw the
    * current schedule activity as a small label above the villager's
    * head ("at work", "heading home", "sleeping", …). Server-side
    * {@code ScheduleService} writes this; client reads via {@code getActivity}.
    */
   private static final EntityDataAccessor<String> DATA_ACTIVITY =
      SynchedEntityData.defineId(LlmTownsfolk.class, EntityDataSerializers.STRING);

   /** Our enlarged inventory. Vanilla Villager has a private-final 8-slot
    *  {@link SimpleContainer}, which we can't modify directly. Instead we
    *  carry our own 12-slot bag and override {@link #getInventory()} to
    *  return this one — every read+write in our code (and in vanilla code
    *  that calls getInventory()) goes through this larger container. The
    *  vanilla 8-slot field still exists, unused, in the parent class. */
   private final SimpleContainer townsfolkInventory = new SimpleContainer(12);

   public LlmTownsfolk(EntityType<? extends Villager> type, Level level) {
      super(type, level, VillagerType.PLAINS);
   }

   @Override
   protected PathNavigation createNavigation(Level level) {
      // Custom evaluator that treats closed wooden fence gates as
      // passable wooden doors. See {@link TownsfolkNodeEvaluator}.
      //
      // Mirror vanilla Villager.createNavigation defaults — swim across
      // water (setCanFloat), open doors. Without canFloat, a villager
      // crossing a 1-tile irrigation channel could path-fail instead
      // of swimming through (audit item 6.2).
      TownsfolkNavigation nav = new TownsfolkNavigation(this, level);
      nav.setCanOpenDoors(true);
      nav.setCanPassDoors(true);
      nav.setCanFloat(true);
      return nav;
   }

   @Override
   public SimpleContainer getInventory() {
      // After the super() constructor's field initializers run, our own
      // field is set. If vanilla code calls getInventory() DURING the
      // super constructor (rare — it shouldn't), fall back to the parent
      // container so we never return null.
      return townsfolkInventory != null ? townsfolkInventory : super.getInventory();
   }

   @Override
   public void addAdditionalSaveData(CompoundTag tag) {
      super.addAdditionalSaveData(tag);
      // Vanilla wrote its own (unused, empty) 8-slot Inventory tag in
      // super. Overwrite it with our 12-slot container's contents so
      // save data round-trips correctly.
      tag.put("Inventory", townsfolkInventory.createTag(this.registryAccess()));
   }

   @Override
   public void readAdditionalSaveData(CompoundTag tag) {
      super.readAdditionalSaveData(tag);
      if (tag.contains("Inventory", Tag.TAG_LIST)) {
         ListTag invList = tag.getList("Inventory", Tag.TAG_COMPOUND);
         townsfolkInventory.fromTag(invList, this.registryAccess());
      }
   }

   @Override
   protected void defineSynchedData(SynchedEntityData.Builder builder) {
      super.defineSynchedData(builder);
      builder.define(DATA_ACTIVITY, "");
   }

   public String getActivity() { return this.entityData.get(DATA_ACTIVITY); }
   public void setActivity(String s) {
      if (!s.equals(this.entityData.get(DATA_ACTIVITY))) this.entityData.set(DATA_ACTIVITY, s);
   }

   @Override
   protected void registerGoals() {
      // Just don't drown. Everything else is driven by direct nav calls or
      // future custom Goals.
      this.goalSelector.addGoal(0, new FloatGoal(this));
      // Open doors / fence gates along the path, close them behind us. Vanilla
      // villagers get this from their Brain's InteractWithDoor task, but we
      // neutered the Brain — so we provide a minimal Goal-based version.
      this.goalSelector.addGoal(1, new DoorInteractionGoal(this));
      // Vacuum nearby item drops into the villager's inventory — wool from
      // shearing, leftover harvest drops, etc. Brain-neutered villagers
      // don't otherwise pick anything up.
      this.goalSelector.addGoal(2, new ItemPickupGoal(this));
   }

   /**
    * Critically: skip the vanilla Villager makeBrain entirely. Build a Brain
    * with the standard memory/sensor types (so vanilla code that reads
    * memories like NEAREST_PLAYERS / HOME doesn't NPE) but DO NOT register
    * any activities, schedules, or behaviors. The Brain.tick is a no-op.
    */
   @Override
   protected Brain<?> makeBrain(Dynamic<?> dynamic) {
      Brain<Villager> brain = this.brainProvider().makeBrain(dynamic);
      // Intentionally do NOT call any of the vanilla
      // VillagerGoalPackages.getXxxPackage(...) registration helpers.
      return brain;
   }

   /**
    * Vanilla Villager.refreshBrain rebuilds the brain when profession
    * changes. Override to keep our empty-brain invariant — re-call our
    * makeBrain instead of vanilla's.
    */
   @Override
   public void refreshBrain(net.minecraft.server.level.ServerLevel level) {
      Brain<Villager> brain = this.getBrain();
      brain.stopAll(level, this);
      this.brain = brain.copyWithoutBehaviors();
   }

   /**
    * Reset the dormancy counter every tick.
    *
    * <p>Vanilla {@code LivingEntity.aiStep} unconditionally increments
    * {@code noActionTime}, and {@code Mob.serverAiStep} (which ticks the
    * brain and the navigator) is gated by {@code noActionTime &lt; 600}.
    * Without something resetting the counter, an LLM townsfolk freezes
    * mid-walk ~30 s after the last external command.
    *
    * <p>A "minimal Brain Activity" can't fix this — the brain ticks
    * <em>inside</em> the gate it's trying to keep open. The reset has to
    * happen in {@code aiStep} itself, which means an override here. This
    * is the intended design, not a workaround: an LLM-driven NPC is
    * always potentially-active regardless of what behaviors are queued.
    *
    * <p>Note: {@code noActionTime} is also consulted by vanilla mob
    * despawn / persistence logic. Holding it at 0 forces our townsfolk to
    * be persistent, which is what we want anyway (they have NBT we don't
    * lose).
    */
   @Override
   public void aiStep() {
      this.noActionTime = 0;
      super.aiStep();
   }

   /**
    * Vanilla {@link Villager#wantsToPickUp} gates by profession — farmers
    * grab wheat/seeds, librarians grab paper, most professions grab almost
    * nothing. That model doesn't fit an LLM-driven villager who might be
    * asked to do anything. We accept any item with an "obvious" use:
    * tools, food, seeds, blocks. Hostile-mob drops (e.g. raw rotten flesh)
    * are fine — the LLM can decide whether to keep or discard.
    *
    * NOTE: this only governs auto-pickup as the villager walks over items.
    * Player-driven {@code [ACTION: give]} and sneak-gift always work
    * regardless of this filter.
    */
   @Override
   public boolean wantsToPickUp(net.minecraft.world.item.ItemStack stack) {
      if (stack.isEmpty()) return false;
      // Bypass vanilla's profession filter entirely — we route through our
      // own decision rules instead of super.wantsToPickUp.
      // Tools (any tag): axes, pickaxes, shovels, hoes, swords.
      if (stack.is(net.minecraft.tags.ItemTags.AXES)
          || stack.is(net.minecraft.tags.ItemTags.PICKAXES)
          || stack.is(net.minecraft.tags.ItemTags.SHOVELS)
          || stack.is(net.minecraft.tags.ItemTags.HOES)
          || stack.is(net.minecraft.tags.ItemTags.SWORDS)) return true;
      // Anything edible.
      if (stack.getFoodProperties(this) != null) return true;
      // Any placeable block.
      if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) return true;
      // Common raw materials worth picking up.
      if (stack.is(net.minecraft.tags.ItemTags.LOGS)
          || stack.is(net.minecraft.tags.ItemTags.PLANKS)
          || stack.is(net.minecraft.tags.ItemTags.WOOL)) return true;
      // Seeds and crops the vanilla filter would have accepted for farmers.
      var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
      if (id != null) {
         String path = id.getPath();
         if (path.endsWith("_seeds") || path.equals("wheat") || path.equals("beetroot")
             || path.equals("potato") || path.equals("carrot")) return true;
      }
      // Default: don't pick up — keeps inventories from clogging with cobblestone.
      return false;
   }
}
