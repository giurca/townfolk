package com.yucareux.townfolk.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.network.AssignBlockPayload;
import com.yucareux.townfolk.network.LogSubscribePayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = Townfolk.MODID, value = Dist.CLIENT)
public final class TownfolkKeybinds {

   private static final KeyMapping TOGGLE_LOG = new KeyMapping(
      "key.townfolk.toggle_log",
      InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.townfolk");

   static final KeyMapping ASSIGN_TO_VILLAGER = new KeyMapping(
      "key.townfolk.assign_to_villager",
      InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.townfolk");

   @SubscribeEvent
   public static void onRegister(RegisterKeyMappingsEvent event) {
      event.register(TOGGLE_LOG);
      event.register(ASSIGN_TO_VILLAGER);
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent.Post event) {
      while (TOGGLE_LOG.consumeClick()) {
         boolean visible = ClientHudState.toggleHud();
         long pos = ClientHudState.activeTownPos();
         if (pos != 0L) {
            PacketDistributor.sendToServer(new LogSubscribePayload(pos, visible));
         }
      }
      while (ASSIGN_TO_VILLAGER.consumeClick()) {
         tryAssign();
      }
   }

   /**
    * If the player's crosshair is on a block within reach, send an
    * {@link AssignBlockPayload} to the server. The server figures out which
    * villager (if any) gets the block based on proximity + open needs.
    */
   private static void tryAssign() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.level == null) return;
      HitResult hit = mc.hitResult;
      if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS) return;
      PacketDistributor.sendToServer(new AssignBlockPayload(bhr.getBlockPos().asLong()));
   }

   private TownfolkKeybinds() {}
}
