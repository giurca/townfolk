package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: rename a registered storage container.
 *
 * Sent while the {@link com.yucareux.townfolk.client.screen.StorageConfigScreen}
 * is open, when the player commits the label EditBox. The server validates
 * the currently-open menu is a {@link com.yucareux.townfolk.world.inventory.StorageConfigMenu}
 * targeting the right block, then updates the live {@link com.yucareux.townfolk.town.StorageConfig}
 * in the registry without waiting for menu close. (Close-commit still works
 * — this just lets the label change be visible to other systems / tabs in
 * real time, e.g. the Town Square Resources tab.)
 *
 * Label is clamped to 64 chars server-side to keep the registry NBT small
 * and the UI rows uncluttered.
 */
public record SetStorageLabelPayload(String label) implements CustomPacketPayload {

   public static final int MAX_LABEL_CHARS = 64;

   public static final Type<SetStorageLabelPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "set_storage_label"));

   public static final StreamCodec<RegistryFriendlyByteBuf, SetStorageLabelPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> buf.writeUtf(p.label == null ? "" : p.label, MAX_LABEL_CHARS),
         buf -> new SetStorageLabelPayload(buf.readUtf(MAX_LABEL_CHARS)));

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
