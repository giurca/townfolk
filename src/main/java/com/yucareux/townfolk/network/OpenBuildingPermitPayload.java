package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: opens the Building Permit modal for the marker
 * block at {@code markerPos}, with a snapshot of its current state.
 *
 * <p>All measurement / cap values are computed server-side at the
 * moment the player right-clicks with a permit, so the modal opens
 * pre-populated with truthful "you are at X / Y" readings — no need
 * for the client to run the recognizer itself.
 *
 * @param markerPos        packed BlockPos of the marker (Charter Stone,
 *                         bed, etc.)
 * @param templateId       human-readable id ("home", "town_hall") —
 *                         used as the modal title
 * @param overrideMaxVolume current saved override for volume; -1 if
 *                         "use template default"
 * @param overrideMaxHeight current saved override for height; -1 if
 *                         "use template default"
 * @param effectiveMaxVolume the cap actually in force RIGHT NOW
 *                         (override if set, else template default)
 * @param effectiveMaxHeight same for height
 * @param measuredVolume   air blocks the recognizer just measured
 *                         inside the room; informational
 * @param measuredHeight   vertical extent the recognizer measured
 * @param templateMaxVolume the template-default volume cap. Shown in
 *                         the modal as the "default" value next to
 *                         the override input.
 * @param templateMaxHeight same for height
 * @param recognitionValid did the most recent recognition pass?
 * @param failureReason    human-readable explanation when not valid
 */
public record OpenBuildingPermitPayload(
   long markerPos,
   String templateId,
   int overrideMaxVolume,
   int overrideMaxHeight,
   int effectiveMaxVolume,
   int effectiveMaxHeight,
   int measuredVolume,
   int measuredHeight,
   int templateMaxVolume,
   int templateMaxHeight,
   boolean recognitionValid,
   String failureReason
) implements CustomPacketPayload {

   public static final Type<OpenBuildingPermitPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "open_building_permit"));

   public static final StreamCodec<RegistryFriendlyByteBuf, OpenBuildingPermitPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.markerPos);
            buf.writeUtf(p.templateId, 64);
            buf.writeVarInt(p.overrideMaxVolume + 1);   // +1 so -1 fits in unsigned varint
            buf.writeVarInt(p.overrideMaxHeight + 1);
            buf.writeVarInt(p.effectiveMaxVolume);
            buf.writeVarInt(p.effectiveMaxHeight);
            buf.writeVarInt(p.measuredVolume);
            buf.writeVarInt(p.measuredHeight);
            buf.writeVarInt(p.templateMaxVolume);
            buf.writeVarInt(p.templateMaxHeight);
            buf.writeBoolean(p.recognitionValid);
            buf.writeUtf(p.failureReason == null ? "" : p.failureReason, 256);
         },
         buf -> new OpenBuildingPermitPayload(
            buf.readLong(),
            buf.readUtf(64),
            buf.readVarInt() - 1,
            buf.readVarInt() - 1,
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readBoolean(),
            buf.readUtf(256)
         )
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
