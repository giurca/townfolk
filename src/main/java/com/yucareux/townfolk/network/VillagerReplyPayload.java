package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the villager's response to the previous PlayerSpeaks
 * line. Async — may arrive several seconds after the player's message was
 * acknowledged. {@code ok} false signals the request failed (rate limit,
 * API down) so the UI can render something sensible instead of hanging.
 */
public record VillagerReplyPayload(UUID villagerUuid, String message, boolean ok)
   implements CustomPacketPayload {

   public static final Type<VillagerReplyPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "villager_reply"));

   public static final StreamCodec<RegistryFriendlyByteBuf, VillagerReplyPayload> STREAM_CODEC =
      StreamCodec.composite(
         ByteBufCodecs.STRING_UTF8, p -> p.villagerUuid.toString(),
         ByteBufCodecs.STRING_UTF8, VillagerReplyPayload::message,
         ByteBufCodecs.BOOL, VillagerReplyPayload::ok,
         (uuid, msg, ok) -> new VillagerReplyPayload(UUID.fromString(uuid), msg, ok)
      );

   @Override
   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
