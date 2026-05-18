package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: commit the entries the player set in the animal-plan
 * modal. Server validates each entry (clamps target to range, drops
 * malformed species ids / modes) before writing to the
 * {@link com.yucareux.townfolk.town.AnimalPlanRegistry}.
 *
 * <p>{@link Entry#mode} ships as a string so the codec doesn't have to
 * know about the {@link com.yucareux.townfolk.town.AnimalPlan.Mode}
 * enum. Unknown values get coerced to "HOLD" server-side.
 */
public record SetAnimalPlanPayload(
   String parcelId,
   long townSquarePos,
   List<Entry> entries
) implements CustomPacketPayload {

   public record Entry(String speciesId, int targetCount, String mode) {}

   public static final Type<SetAnimalPlanPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "set_animal_plan"));

   private static final StreamCodec<RegistryFriendlyByteBuf, Entry> ENTRY_CODEC =
      StreamCodec.of(
         (buf, e) -> {
            buf.writeUtf(e.speciesId());
            buf.writeVarInt(e.targetCount());
            buf.writeUtf(e.mode());
         },
         buf -> new Entry(buf.readUtf(), buf.readVarInt(), buf.readUtf())
      );

   public static final StreamCodec<RegistryFriendlyByteBuf, SetAnimalPlanPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeUtf(p.parcelId, 64);
            buf.writeLong(p.townSquarePos);
            buf.writeVarInt(p.entries.size());
            for (Entry e : p.entries) ENTRY_CODEC.encode(buf, e);
         },
         buf -> {
            String pid = buf.readUtf(64);
            long ts = buf.readLong();
            int n = buf.readVarInt();
            List<Entry> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(ENTRY_CODEC.decode(buf));
            return new SetAnimalPlanPayload(pid, ts, out);
         }
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
