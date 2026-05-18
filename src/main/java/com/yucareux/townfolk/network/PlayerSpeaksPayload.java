package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a chat line the player typed into the dialogue screen,
 * targeted at a specific villager.
 *
 * <p>Uses {@code buf.writeUUID} / {@code buf.readUUID} (raw 16 bytes,
 * two longs) rather than a string-form UUID. The string form requires
 * {@code UUID.fromString} which throws on malformed input — a hostile
 * or out-of-sync client could kill the channel with a single bad
 * packet. The raw codec can't fail decode.
 *
 * <p>Message is capped at {@link #MAX_MESSAGE_CHARS} on the wire AND
 * server-side trimmed defensively before reaching the LLM — keeps a
 * spammy client from driving token cost.
 */
public record PlayerSpeaksPayload(UUID villagerUuid, String message)
   implements CustomPacketPayload {

   public static final int MAX_MESSAGE_CHARS = 512;

   public static final Type<PlayerSpeaksPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "player_speaks"));

   public static final StreamCodec<RegistryFriendlyByteBuf, PlayerSpeaksPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeUUID(p.villagerUuid);
            String msg = p.message == null ? "" : p.message;
            if (msg.length() > MAX_MESSAGE_CHARS) msg = msg.substring(0, MAX_MESSAGE_CHARS);
            buf.writeUtf(msg, MAX_MESSAGE_CHARS);
         },
         buf -> new PlayerSpeaksPayload(buf.readUUID(), buf.readUtf(MAX_MESSAGE_CHARS))
      );

   @Override
   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
