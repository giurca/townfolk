package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TradePostBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

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
}
