package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player picked a parcel type (or cancelled) in
 * the popup. The {@code chosen} string is either a
 * {@link com.yucareux.townfolk.villager.FieldRegion.Type#name()} or
 * the literal {@code "cancel"} for an Escape / outside-click close.
 */
public record SelectParcelTypePayload(String chosen) implements CustomPacketPayload {

   public static final String CANCEL = "cancel";

   public static final Type<SelectParcelTypePayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "select_parcel_type"));

   public static final StreamCodec<RegistryFriendlyByteBuf, SelectParcelTypePayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> buf.writeUtf(p.chosen),
         buf -> new SelectParcelTypePayload(buf.readUtf()));

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
