package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.blockentity.TradePostBlockEntity;
import com.yucareux.townfolk.dialogue.TownAdminService;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Trade Post — an auxiliary town block that:
 *   - extends the owning town's coverage by 64 blocks, and
 *   - enables the Trade tab in that town's admin screen.
 *
 * <p>Must be placed within an existing town's coverage. Placement
 * validation + registry binding live in
 * {@link com.yucareux.townfolk.world.TradePostHooks}; this block class
 * holds only the registration boilerplate and the right-click handler.
 *
 * <p>Right-click semantics land in Stage 2.4.
 */
public class TradePostBlock extends BaseEntityBlock {

   public static final MapCodec<TradePostBlock> CODEC = simpleCodec(TradePostBlock::new);

   public TradePostBlock(BlockBehaviour.Properties properties) {
      super(properties);
   }

   @Override
   protected MapCodec<? extends BaseEntityBlock> codec() {
      return CODEC;
   }

   @Override
   @Nullable
   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new TradePostBlockEntity(pos, state);
   }

   @Override
   protected RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }

   /**
    * Right-click opens the OWNING town's admin screen. The owner pos is
    * stamped on the Trade Post BE at place-time
    * ({@link com.yucareux.townfolk.world.TradePostHooks}). If the BE
    * has no owner (orphaned post — placed before hooks landed, or
    * loaded into a world without its town), report it and bail.
    */
   @Override
   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
      if (level.isClientSide) return InteractionResult.SUCCESS;
      if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
      if (!(level.getBlockEntity(pos) instanceof TradePostBlockEntity tpe)) {
         return InteractionResult.PASS;
      }
      long ownerLong = tpe.ownerTownPos();
      if (ownerLong == 0L) {
         sp.sendSystemMessage(Component.literal(
               "This Trade Post isn't bound to a town. Break and replace it inside a town to rebind.")
            .withStyle(ChatFormatting.YELLOW), true);
         return InteractionResult.CONSUME;
      }
      BlockPos ownerPos = BlockPos.of(ownerLong);
      if (!(level.getBlockEntity(ownerPos) instanceof TownSquareBlockEntity owner)) {
         sp.sendSystemMessage(Component.literal(
               "This Trade Post's town isn't loaded right now (square at "
               + ownerPos.toShortString() + ").")
            .withStyle(ChatFormatting.YELLOW), true);
         return InteractionResult.CONSUME;
      }
      TownAdminService.openAdminPanel(sp, (ServerLevel) level, owner);
      return InteractionResult.CONSUME;
   }
}
