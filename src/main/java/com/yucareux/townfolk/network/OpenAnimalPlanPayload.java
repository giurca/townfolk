package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the animal-plan modal for a given parcel.
 * Carries the parcel id (so the client knows what to edit), a
 * snapshot of species currently present on the parcel (for the
 * "census" column), and the saved plan entries for those species
 * (so existing target+mode load correctly).
 *
 * <p>Species ids are minecraft entity-type ids; the client renders
 * them by looking up the EntityType and pulling its display name +
 * spawn-egg-style icon. Counts are server-authoritative — the client
 * never scans the world for them.
 */
public record OpenAnimalPlanPayload(
   String parcelId,
   long townSquarePos,
   List<SpeciesView> species
) implements CustomPacketPayload {

   /** One row in the animal-plan modal. {@code currentCount} and
    *  {@code currentBabies} reflect right-now reality; {@code target}
    *  and {@code mode} reflect the saved plan ("0" / "HOLD" mean no
    *  saved entry). */
   public record SpeciesView(
      String speciesId,
      int currentCount,
      int currentBabies,
      int target,
      String mode
   ) {}

   public static final Type<OpenAnimalPlanPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "open_animal_plan"));

   private static final StreamCodec<RegistryFriendlyByteBuf, SpeciesView> SPECIES_CODEC =
      StreamCodec.of(
         (buf, s) -> {
            buf.writeUtf(s.speciesId());
            buf.writeVarInt(s.currentCount());
            buf.writeVarInt(s.currentBabies());
            buf.writeVarInt(s.target());
            buf.writeUtf(s.mode());
         },
         buf -> new SpeciesView(buf.readUtf(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt(), buf.readUtf())
      );

   public static final StreamCodec<RegistryFriendlyByteBuf, OpenAnimalPlanPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeUtf(p.parcelId, 64);
            buf.writeLong(p.townSquarePos);
            buf.writeVarInt(p.species.size());
            for (SpeciesView s : p.species) SPECIES_CODEC.encode(buf, s);
         },
         buf -> {
            String pid = buf.readUtf(64);
            long ts = buf.readLong();
            int n = buf.readVarInt();
            List<SpeciesView> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(SPECIES_CODEC.decode(buf));
            return new OpenAnimalPlanPayload(pid, ts, out);
         }
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
