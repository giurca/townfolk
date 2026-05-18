package com.yucareux.townfolk.client;

import com.yucareux.townfolk.client.screen.DialogueScreen;
import com.yucareux.townfolk.client.screen.TownAdminScreen;
import com.yucareux.townfolk.network.OpenDialoguePayload;
import com.yucareux.townfolk.network.OpenVillagerDetailPayload;
import com.yucareux.townfolk.network.TownLogPushPayload;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import com.yucareux.townfolk.network.VillagerReplyPayload;
import net.minecraft.client.Minecraft;

public final class ClientHooks {

   public static void openDialogueScreen(OpenDialoguePayload payload) {
      Minecraft mc = Minecraft.getInstance();
      mc.setScreen(new DialogueScreen(
         payload.villagerUuid(),
         payload.villagerName(),
         payload.backstory(),
         payload.history(),
         payload.needs()
      ));
   }

   public static void deliverVillagerReply(VillagerReplyPayload payload) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen instanceof DialogueScreen screen) {
         screen.receiveReply(payload.villagerUuid(), payload.message(), payload.ok());
      }
   }

   public static void deliverTownState(TownStateUpdatePayload payload) {
      ClientHudState.setActiveTownPos(payload.townSquarePos());
      ClientHudState.setLog(payload.log());
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen instanceof TownAdminScreen screen) {
         screen.updateState(payload);
      } else {
         mc.setScreen(new TownAdminScreen(payload));
      }
   }

   public static void deliverLogPush(TownLogPushPayload payload) {
      if (ClientHudState.activeTownPos() != payload.townSquarePos()) return;
      ClientHudState.setLog(payload.log());
   }

   public static void openVillagerDetail(OpenVillagerDetailPayload payload) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen instanceof TownAdminScreen screen) {
         screen.focusVillager(payload.villagerUuid());
      }
   }

   public static void openParcelTypePopup(com.yucareux.townfolk.network.OpenParcelTypePopupPayload payload) {
      Minecraft mc = Minecraft.getInstance();
      mc.setScreen(new com.yucareux.townfolk.client.screen.ParcelTypePopupScreen(
         payload.sizeX(), payload.sizeZ()));
   }

   public static void openAnimalPlanScreen(com.yucareux.townfolk.network.OpenAnimalPlanPayload payload) {
      Minecraft mc = Minecraft.getInstance();
      mc.setScreen(new com.yucareux.townfolk.client.screen.AnimalPlanScreen(payload));
   }

   private ClientHooks() {}
}
