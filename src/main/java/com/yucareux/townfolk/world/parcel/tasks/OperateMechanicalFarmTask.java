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

   @Override
   public int score(Ctx ctx, FieldRegion parcel) {
      CreateBridge bridge = CreateBridge.get();
      if (!bridge.isAvailable()) return 0;     // Create not loaded
      if (findFarmComponent(ctx, parcel, bridge) == null) return 0;
      // Modest priority — vanilla farming (harvest/plant/till) should
      // beat this when both apply, since the villager being there
      // is what makes Create farms feel grounded. A future tweak
      // could bump this when the LLM declares a "mechanical farmer"
      // role.
      return 5;
   }

   @Override
   public boolean execute(Ctx ctx, FieldRegion parcel) {
      CreateBridge bridge = CreateBridge.get();
      BlockPos pos = findFarmComponent(ctx, parcel, bridge);
      if (pos == null) return false;
      bridge.toggleKineticComponent(ctx.level(), pos);
      VerboseLog.write("PARCEL_TASK_OPERATE_FARM",
         "actor=" + ctx.entry().name()
            + " parcel=" + parcel.id()
            + " component=" + pos.toShortString(), "");
      long day = ctx.level().getGameTime() / 24000L;
      com.yucareux.townfolk.villager.MemoryStore.write(ctx.actor(), "work", day,
         "I checked over the mechanical farm at " + pos.toShortString() + ".");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "operated the mechanical farm at " + pos.toShortString());
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
