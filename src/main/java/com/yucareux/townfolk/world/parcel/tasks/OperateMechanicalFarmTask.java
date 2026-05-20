package com.yucareux.townfolk.world.parcel.tasks;

import com.yucareux.townfolk.compat.create.CreateBridge;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.WorkProductionService.Ctx;
import net.minecraft.core.BlockPos;

/**
 * Stage 24c — the first {@link ParcelTask} implementation, and the
 * first piece of real Create integration. When a villager owns a
 * parcel that contains a Create mechanical farm component (harvester
 * / plough / planter / drill / saw), this task fires periodically
 * to "operate" it.
 *
 * <p>Detection is via {@link CreateBridge#isMechanicalFarmComponent}
 * — namespace-based, so this file is safe to load even when Create
 * isn't installed. The {@link CreateBridge#get} call resolves the
 * no-op bridge in that case and {@link #score} returns 0 (no work).
 *
 * <p>For now {@link #execute} just logs the find + writes a memory
 * line. The actual kinetic toggle goes through
 * {@link CreateBridge#toggleKineticComponent} which is itself a stub
 * (returns false, logs). Closing that loop is on the future-work
 * pile — but the ParcelTask integration shape is now real, so adding
 * the kinetic pulse is a one-file follow-up.
 */
public final class OperateMechanicalFarmTask implements ParcelTask {

   public static final OperateMechanicalFarmTask INSTANCE = new OperateMechanicalFarmTask();

   /** How often this villager should re-poke the farm. 600 game
    *  ticks = ~30 seconds — slow enough that the LLM-driven side of
    *  things stays cheap. */
   private static final long COOLDOWN_TICKS = 600L;

   private OperateMechanicalFarmTask() {}

   @Override
   public String id() { return "operate_mechanical_farm"; }

   /** Audit P2-A: low base score (1) so vanilla farming branches —
    *  which run AFTER ParcelTask dispatch in tryTickImpl — can still
    *  pre-empt this whenever they have something concrete to do
    *  (ripe crops, dry farmland, etc.). Even at 1 we still win over
    *  any future task that scores 0; future tasks that should out-
    *  rank this declare score ≥2. */
   private static final int BASE_SCORE = 1;

   @Override
   public Eval evaluate(Ctx ctx, FieldRegion parcel) {
      CreateBridge bridge = CreateBridge.get();
      if (!bridge.isAvailable()) return Eval.NONE;   // Create not loaded
      BlockPos pos = findFarmComponent(ctx, parcel, bridge);
      if (pos == null) return Eval.NONE;
      // Audit P1-A fix: cache the found pos in the Eval payload so
      // execute() doesn't re-scan the parcel.
      return new Eval(BASE_SCORE, pos);
   }

   @Override
   public boolean execute(Ctx ctx, FieldRegion parcel, Object payload) {
      if (!(payload instanceof BlockPos pos)) return false;
      CreateBridge bridge = CreateBridge.get();
      // Audit P1-C fix: respect the bridge's return value. The kinetic
      // toggle is currently a logged stub returning false — claim only
      // "inspected" until the redstone-pulse implementation lands.
      boolean toggled = bridge.toggleKineticComponent(ctx.level(), pos);
      VerboseLog.write("PARCEL_TASK_OPERATE_FARM",
         "actor=" + ctx.entry().name()
            + " parcel=" + parcel.id()
            + " component=" + pos.toShortString()
            + " toggled=" + toggled, "");
      long day = ctx.level().getGameTime() / 24000L;
      String memoryLine = toggled
         ? "I operated the mechanical farm at " + pos.toShortString() + "."
         : "I inspected the mechanical farm at " + pos.toShortString() + ".";
      com.yucareux.townfolk.villager.MemoryStore.write(ctx.actor(), "work", day, memoryLine);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         (toggled ? "operated" : "inspected")
            + " the mechanical farm at " + pos.toShortString());
      return true;
   }

   /** Scan the parcel for the first block that matches the bridge's
    *  mechanical-farm-component predicate. Returns null if none, or
    *  if Create isn't loaded. */
   private static BlockPos findFarmComponent(Ctx ctx, FieldRegion parcel, CreateBridge bridge) {
      BlockPos centre = parcel.centre();
      int rx = parcel.sizeX() / 2 + 1;
      int rz = parcel.sizeZ() / 2 + 1;
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      for (int dx = -rx; dx <= rx; dx++) {
         for (int dy = -2; dy <= 2; dy++) {
            for (int dz = -rz; dz <= rz; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               if (bridge.isMechanicalFarmComponent(ctx.level(), cur)) {
                  return cur.immutable();
               }
            }
         }
      }
      return null;
   }
}
