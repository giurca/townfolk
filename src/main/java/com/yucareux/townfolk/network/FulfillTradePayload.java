package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player clicked [Deliver] on a trade offer.
 *
 * @param townSquarePos packed BlockPos of the owning Town Square
 *                      (the trade offer lives on that town's TownData)
 * @param offerId       the {@code TradeOffer#id} the player wants to
 *                      fulfil
 */
public record FulfillTradePayload(long townSquarePos, String offerId)
   implements CustomPacketPayload {

   public static final Type<FulfillTradePayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "fulfill_trade"));

   public static final StreamCodec<RegistryFriendlyByteBuf, FulfillTradePayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> { buf.writeLong(p.townSquarePos); buf.writeUtf(p.offerId, 64); },
         buf -> new FulfillTradePayload(buf.readLong(), buf.readUtf(64))
      );

   @Override
   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
