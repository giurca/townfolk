package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: toggle log streaming for the player's HUD.
 *   enable=true with a valid town pos → subscribe to that town's log
 *   enable=false → unsubscribe (townSquarePos ignored)
 */
public record LogSubscribePayload(long townSquarePos, boolean enable) implements CustomPacketPayload {

   public static final Type<LogSubscribePayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "log_subscribe"));

   public static final StreamCodec<RegistryFriendlyByteBuf, LogSubscribePayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> { buf.writeLong(p.townSquarePos); buf.writeBoolean(p.enable); },
         buf -> new LogSubscribePayload(buf.readLong(), buf.readBoolean())
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
