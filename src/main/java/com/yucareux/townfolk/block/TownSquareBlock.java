package com.yucareux.townfolk.block;

import com.mojang.serialization.MapCodec;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.dialogue.TownAdminService;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
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
 * Town Square — anchor block for an LLM-villager town. Right-clicking it opens
 * the town admin UI (server-driven, networked to a custom Screen).
 *
 * This release: no UI yet. Right-click just prints the current state line to
 * chat so we can verify persistence works end-to-end before bolting on the
 * Screen + network packets.
 */
public class TownSquareBlock extends BaseEntityBlock {

   public static final MapCodec<TownSquareBlock> CODEC = simpleCodec(TownSquareBlock::new);

   public TownSquareBlock(BlockBehaviour.Properties properties) {
      super(properties);
   }

   @Override
   protected MapCodec<? extends BaseEntityBlock> codec() {
      return CODEC;
   }

   @Override
   @Nullable
   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new TownSquareBlockEntity(pos, state);
   }

   @Override
   protected RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }

   @Override
   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
      if (level.isClientSide) {
         return InteractionResult.SUCCESS;
      }
      if (!(player instanceof ServerPlayer server)) {
         return InteractionResult.PASS;
      }
      BlockEntity be = level.getBlockEntity(pos);
      if (be instanceof TownSquareBlockEntity town) {
         TownAdminService.openAdminPanel(server, (ServerLevel) level, town);
      }
      return InteractionResult.CONSUME;
   }
}
