package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: lightweight push of a town's log, sent periodically while
 * the player has the HUD enabled for that town. Decoupled from the full
 * {@link TownStateUpdatePayload} so the streaming overlay doesn't drag the
 * whole villager roster across the wire every second.
 */
public record TownLogPushPayload(long townSquarePos, List<TownStateUpdatePayload.LogEntry> log)
   implements CustomPacketPayload {

   public static final Type<TownLogPushPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "town_log_push"));

   public static final StreamCodec<RegistryFriendlyByteBuf, TownLogPushPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.townSquarePos);
            buf.writeVarInt(p.log.size());
            for (TownStateUpdatePayload.LogEntry e : p.log) {
               buf.writeVarLong(e.gameTime());
               buf.writeUtf(e.level());
               buf.writeUtf(e.message(), 512);
            }
         },
         buf -> {
            long pos = buf.readLong();
            int n = buf.readVarInt();
            List<TownStateUpdatePayload.LogEntry> log = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
               log.add(new TownStateUpdatePayload.LogEntry(buf.readVarLong(), buf.readUtf(), buf.readUtf(512)));
            }
            return new TownLogPushPayload(pos, log);
         }
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
