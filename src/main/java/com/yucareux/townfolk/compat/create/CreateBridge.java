package com.yucareux.townfolk.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Bridge interface for Create-mod integration. All Create-specific
 * surface area (fluid handlers, kinetic streams, processing recipes)
 * lands behind this interface so the mod loads cleanly with or
 * without Create installed.
 *
 * <p>{@link #get()} returns either {@link CreateBridgeImpl} (if Create
 * is on the mod list) or a no-op {@link NoopCreateBridge}. Call sites
 * use {@link #isAvailable()} as a fast gate before doing any
 * Create-flavoured work.
 *
 * <p>Stage 20 scaffolding only — actual fluid + kinetic + recipe
 * integration lands in 20b/c/d. The signatures below are the planned
 * shape; today every default returns the conservative "not present /
 * not handled" answer so feature code can be drafted against them
 * even before Create wiring is real.
 */
public interface CreateBridge {

   /** True iff Create is loaded AND we successfully resolved the
    *  capability provider names. Always false from the no-op impl. */
   boolean isAvailable();

   /** Stage 20b: check for a fluid handler at {@code pos}. Returns
    *  the {@link Fluid} type currently held (or {@link Fluids#EMPTY}
    *  if the position has no Create fluid handler / it's empty). */
   default Fluid fluidAt(ServerLevel level, BlockPos pos) {
      return Fluids.EMPTY;
   }

   /** Stage 20c: true if {@code pos} is part of a recognisable Create
    *  mechanical farm — harvester, plough, mechanical planter, etc.
    *  Used by the OperateMechanicalFarmTask parcel evaluator. */
   default boolean isMechanicalFarmComponent(ServerLevel level, BlockPos pos) {
      return false;
   }

   /** Stage 20c: signal an "operate" pulse to the kinetic component at
    *  {@code pos}. Returns true if the bridge thinks the pulse landed
    *  somewhere useful. No-op impl returns false. */
   default boolean toggleKineticComponent(ServerLevel level, BlockPos pos) {
      return false;
   }

   // ───────── resolution ─────────

   /** Resolve the active bridge. Lazily initialised; result is cached
    *  for the lifetime of the JVM (Create can't be hot-loaded into a
    *  running mod environment). */
   static CreateBridge get() {
      return Holder.INSTANCE;
   }

   /** True iff the Create mod is detected on the active mod list.
    *  Doesn't guarantee the bridge implementation succeeded — use
    *  {@link #isAvailable()} on the returned bridge for the final
    *  answer. */
   static boolean isCreateLoaded() {
      try {
         return net.neoforged.fml.ModList.get().isLoaded("create");
      } catch (Throwable t) {
         return false;
      }
   }

   /** Lazy-init holder. Picking {@link CreateBridgeImpl} via reflection
    *  guards against {@code NoClassDefFoundError} when Create isn't on
    *  the classpath — classloading {@code CreateBridgeImpl} would fail
    *  if its imports reference Create classes directly (which is
    *  exactly what 20b/c/d will do). The holder catches that error
    *  and falls back to the no-op. */
   final class Holder {
      static final CreateBridge INSTANCE = init();

      private Holder() {}

      private static CreateBridge init() {
         if (!isCreateLoaded()) return new NoopCreateBridge();
         try {
            Class<?> impl = Class.forName("com.yucareux.townfolk.compat.create.CreateBridgeImpl");
            return (CreateBridge) impl.getDeclaredConstructor().newInstance();
         } catch (Throwable t) {
            // Create is on the mod list but the bridge impl failed to
            // load — log once and fall back to the no-op so the mod
            // keeps working without Create features.
            com.yucareux.townfolk.diag.VerboseLog.write("CREATE_BRIDGE_INIT_FAIL",
               "stage=class-load",
               "Create detected but CreateBridgeImpl couldn't load: " + t);
            return new NoopCreateBridge();
         }
      }
   }
}
