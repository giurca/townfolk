package com.yucareux.townfolk.compat.create;

import com.yucareux.townfolk.diag.VerboseLog;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Stage 24b — actual {@link CreateBridge} implementation. Loaded
 * by reflection from {@link CreateBridge.Holder#init} only when
 * {@code ModList.isLoaded("create")} returns true, so this file's
 * presence doesn't break the no-Create case.
 *
 * <p>Currently uses ResourceLocation namespace-matching to detect
 * Create blocks (no compile-time dependency on Create classes). The
 * approach is robust to Create version churn — even if Create
 * renames {@code MechanicalHarvesterBlock} between minor versions,
 * the {@code create:mechanical_harvester} block id stays stable.
 *
 * <p>Future hardening: add a reflection-based capability bridge for
 * Create's {@code KineticBlockEntity#sequence()} if we want
 * fine-grained kinetic control. For now, "is this a mechanical
 * farm component?" + "redstone-pulse it as an operate signal" are
 * the only two affordances needed by Stage 24c's
 * {@code OperateMechanicalFarmTask}.
 */
public final class CreateBridgeImpl implements CreateBridge {

   /** Path-only identifiers (not full namespace:path) of the Create
    *  blocks we recognize as "mechanical farm components." Curated
    *  list rather than a wildcard so unrelated Create blocks (gears,
    *  belts, shafts) don't accidentally pass the predicate. */
   private static final Set<String> MECHANICAL_FARM_PATHS = Set.of(
      "mechanical_harvester",
      "mechanical_plough",
      "mechanical_planter",
      "mechanical_drill",
      "mechanical_saw"
   );

   public CreateBridgeImpl() {
      VerboseLog.write("CREATE_BRIDGE_INIT",
         "stage=loaded", "CreateBridgeImpl active");
   }

   @Override
   public boolean isAvailable() { return true; }

   @Override
   public Fluid fluidAt(ServerLevel level, BlockPos pos) {
      var handler = com.yucareux.townfolk.town.FluidIndex.at(level, pos);
      if (handler == null) return Fluids.EMPTY;
      var contents = com.yucareux.townfolk.town.FluidIndex.contents(handler);
      return contents.isEmpty() ? Fluids.EMPTY : contents.getFluid();
   }

   @Override
   public boolean isMechanicalFarmComponent(ServerLevel level, BlockPos pos) {
      var state = level.getBlockState(pos);
      ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
      if (id == null) return false;
      if (!"create".equals(id.getNamespace())) return false;
      return MECHANICAL_FARM_PATHS.contains(id.getPath());
   }

   @Override
   public boolean toggleKineticComponent(ServerLevel level, BlockPos pos) {
      // Cosmetic for now: pulse a 1-tick redstone signal at the
      // adjacent block above to trigger any wired Create contraption
      // assembly. Real KineticBlockEntity.sequence() access would
      // need reflection — deferred until a user actually needs
      // fine-grained start/stop control.
      VerboseLog.write("CREATE_TOGGLE_PULSE",
         "pos=" + pos.toShortString(),
         "stub: redstone-pulse contraction not yet implemented");
      return false;
   }
}
