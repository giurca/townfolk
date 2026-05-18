package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: take {@code requested} of {@code itemId} out of the
 * town's treasury and give it to the player. Server clamps to what's
 * actually in the treasury, drops overflow at the player's feet, and
 * pushes a fresh admin-state update.
 *
 * @param townSquarePos packed BlockPos of the owning Town Square
 * @param itemId        item id to withdraw (e.g. "minecraft:emerald")
 * @param requested     how many to try to take. The server clamps to
 *                      the actual treasury count; 0 / negative are
 *                      treated as "all of it."
 */
public record WithdrawTreasuryPayload(long townSquarePos, String itemId, int requested)
   implements CustomPacketPayload {

   public static final Type<WithdrawTreasuryPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "withdraw_treasury"));

   public static final StreamCodec<RegistryFriendlyByteBuf, WithdrawTreasuryPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.townSquarePos);
            buf.writeUtf(p.itemId);
            buf.writeVarInt(p.requested);
         },
         buf -> new WithdrawTreasuryPayload(buf.readLong(), buf.readUtf(), buf.readVarInt())
      );

   @Override
   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
