package com.yucareux.townfolk.network;

import com.yucareux.townfolk.Townfolk;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: an admin command targeting a specific Town Square.
 *
 * Union packet — extra fields used or empty depending on the action.
 */
public record AdminActionPayload(
   long townSquarePos,
   Action action,
   Optional<UUID> villagerUuid,
   String name,
   String role,
   String personaSeed,
   String factId,
   String factText,
   String factScope    // "" | "personal" | "town"
) implements CustomPacketPayload {

   public enum Action {
      OPEN,
      SPAWN,
      REMOVE,
      EDIT_PERSONA,
      REGENERATE_BACKSTORY,
      REFRESH_SPEND,
      RENAME_TOWN,
      PIN_ADD,           // factText (+ villagerUuid for personal, scope)
      PIN_EDIT,          // factId, factText (+ villagerUuid for personal)
      PIN_RESOLVE,       // factId (+ villagerUuid for personal); marks status=resolved
      PIN_REMOVE,        // factId (+ villagerUuid for personal)
      TODO_COMPLETE,     // villagerUuid, factId (= todo id); marks status=done
      TODO_ABANDON,      // villagerUuid, factId (= todo id); marks status=abandoned
      OPEN_PARCEL_EDITOR, // factId = parcel id; opens CropPlanScreen for that parcel
      OPEN_ANIMAL_PLAN    // factId = parcel id; opens AnimalPlanScreen for that ANIMAL parcel
   }

   public static final Type<AdminActionPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "admin_action"));

   public static final StreamCodec<RegistryFriendlyByteBuf, AdminActionPayload> STREAM_CODEC =
      StreamCodec.of(
         (buf, p) -> {
            buf.writeLong(p.townSquarePos);
            buf.writeEnum(p.action);
            buf.writeBoolean(p.villagerUuid.isPresent());
            p.villagerUuid.ifPresent(buf::writeUUID);
            buf.writeUtf(p.name == null ? "" : p.name);
            buf.writeUtf(p.role == null ? "" : p.role);
            buf.writeUtf(p.personaSeed == null ? "" : p.personaSeed);
            buf.writeUtf(p.factId == null ? "" : p.factId);
            buf.writeUtf(p.factText == null ? "" : p.factText, 1024);
            buf.writeUtf(p.factScope == null ? "" : p.factScope);
         },
         buf -> new AdminActionPayload(
            buf.readLong(),
            buf.readEnum(Action.class),
            buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readUtf(1024),
            buf.readUtf()
         )
      );

   @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

   private static AdminActionPayload of(long pos, Action a, Optional<UUID> uuid,
                                        String name, String role, String seed,
                                        String factId, String factText, String scope) {
      return new AdminActionPayload(pos, a, uuid, name, role, seed, factId, factText, scope);
   }

   public static AdminActionPayload open(long pos) {
      return of(pos, Action.OPEN, Optional.empty(), "", "", "", "", "", "");
   }
   public static AdminActionPayload spawn(long pos, String name, String role, String personaSeed) {
      return of(pos, Action.SPAWN, Optional.empty(), name, role, personaSeed, "", "", "");
   }
   public static AdminActionPayload remove(long pos, UUID villagerUuid) {
      return of(pos, Action.REMOVE, Optional.of(villagerUuid), "", "", "", "", "", "");
   }
   public static AdminActionPayload editPersona(long pos, UUID villagerUuid, String personaSeed) {
      return of(pos, Action.EDIT_PERSONA, Optional.of(villagerUuid), "", "", personaSeed, "", "", "");
   }
   public static AdminActionPayload regenerateBackstory(long pos, UUID villagerUuid) {
      return of(pos, Action.REGENERATE_BACKSTORY, Optional.of(villagerUuid), "", "", "", "", "", "");
   }
   public static AdminActionPayload refreshSpend(long pos) {
      return of(pos, Action.REFRESH_SPEND, Optional.empty(), "", "", "", "", "", "");
   }
   public static AdminActionPayload renameTown(long pos, String newName) {
      return of(pos, Action.RENAME_TOWN, Optional.empty(), newName, "", "", "", "", "");
   }
   public static AdminActionPayload pinAddTown(long pos, String text) {
      return of(pos, Action.PIN_ADD, Optional.empty(), "", "", "", "", text, "town");
   }
   public static AdminActionPayload pinAddPersonal(long pos, UUID villagerUuid, String text) {
      return of(pos, Action.PIN_ADD, Optional.of(villagerUuid), "", "", "", "", text, "personal");
   }
   public static AdminActionPayload pinEditTown(long pos, String id, String text) {
      return of(pos, Action.PIN_EDIT, Optional.empty(), "", "", "", id, text, "town");
   }
   public static AdminActionPayload pinEditPersonal(long pos, UUID villagerUuid, String id, String text) {
      return of(pos, Action.PIN_EDIT, Optional.of(villagerUuid), "", "", "", id, text, "personal");
   }
   public static AdminActionPayload pinResolveTown(long pos, String id) {
      return of(pos, Action.PIN_RESOLVE, Optional.empty(), "", "", "", id, "", "town");
   }
   public static AdminActionPayload pinResolvePersonal(long pos, UUID villagerUuid, String id) {
      return of(pos, Action.PIN_RESOLVE, Optional.of(villagerUuid), "", "", "", id, "", "personal");
   }
   public static AdminActionPayload pinRemoveTown(long pos, String id) {
      return of(pos, Action.PIN_REMOVE, Optional.empty(), "", "", "", id, "", "town");
   }
   public static AdminActionPayload pinRemovePersonal(long pos, UUID villagerUuid, String id) {
      return of(pos, Action.PIN_REMOVE, Optional.of(villagerUuid), "", "", "", id, "", "personal");
   }
   public static AdminActionPayload todoComplete(long pos, UUID villagerUuid, String todoId) {
      return of(pos, Action.TODO_COMPLETE, Optional.of(villagerUuid), "", "", "", todoId, "", "");
   }
   public static AdminActionPayload todoAbandon(long pos, UUID villagerUuid, String todoId) {
      return of(pos, Action.TODO_ABANDON, Optional.of(villagerUuid), "", "", "", todoId, "", "");
   }
   public static AdminActionPayload openParcelEditor(long pos, String parcelId) {
      return of(pos, Action.OPEN_PARCEL_EDITOR, Optional.empty(), "", "", "", parcelId, "", "");
   }
   public static AdminActionPayload openAnimalPlan(long pos, String parcelId) {
      return of(pos, Action.OPEN_ANIMAL_PLAN, Optional.empty(), "", "", "", parcelId, "", "");
   }
}
