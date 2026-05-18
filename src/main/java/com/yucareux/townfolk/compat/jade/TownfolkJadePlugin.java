package com.yucareux.townfolk.compat.jade;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.StorageConfig;
import com.yucareux.townfolk.town.StorageRegistry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.Todo;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade tooltip integration. Injects supplementary lines into Jade's HUD when
 * the crosshair is on an {@link LlmTownsfolk}:
 *
 *   Activity: at work
 *   Tasks: 3 open
 *   Memory: 247 entries
 *   Anchored: home + work
 *
 * Why two providers:
 *   - {@link ServerData} pushes the AttachmentType-backed fields (todos /
 *     memory count / anchor flags) from the server into Jade's per-entity
 *     CompoundTag, since those records live server-side only.
 *   - {@link ClientLines} runs client-side and reads (a) the synced
 *     activity string from {@link LlmTownsfolk#getActivity()} and (b) the
 *     server-pushed tag, then formats the tooltip lines.
 *
 * This whole class is class-loaded by Jade only when Jade itself is present
 * (declared optional in mods.toml), so the mod runs fine without it.
 */
@WailaPlugin
public final class TownfolkJadePlugin implements IWailaPlugin {

   public static final ResourceLocation UID =
      ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "townsfolk_info");

   private static final String K_OPEN_TODOS    = "tf_open_todos";
   private static final String K_MEMORIES      = "tf_memories";
   private static final String K_HOME          = "tf_home";
   private static final String K_JOB           = "tf_job";
   private static final String K_BARREL_LABEL  = "tf_barrel_label";
   private static final String K_BARREL_MODE   = "tf_barrel_mode";
   private static final String K_BARREL_FILTERS = "tf_barrel_filters";

   @Override
   public void register(IWailaCommonRegistration r) {
      r.registerEntityDataProvider(ServerData.INSTANCE, LlmTownsfolk.class);
      // Registered-storage tooltip info — barrel label + whitelist/blacklist
      // mode + filter count. Provider fires for any block-entity, then
      // filters to whitelisted container types in appendServerData.
      r.registerBlockDataProvider(StorageServerData.INSTANCE, BlockEntity.class);
   }

   @Override
   public void registerClient(IWailaClientRegistration r) {
      r.registerEntityComponent(ClientLines.INSTANCE, LlmTownsfolk.class);
      r.registerBlockComponent(StorageClientLines.INSTANCE, net.minecraft.world.level.block.Block.class);
   }

   // ───────── server side ─────────

   private enum ServerData implements IServerDataProvider<EntityAccessor> {
      INSTANCE;

      @Override
      public void appendServerData(CompoundTag tag, EntityAccessor accessor) {
         if (!(accessor.getEntity() instanceof LlmTownsfolk v)) return;
         LlmVillagerComponent c = v.getData(ModRegistries.LLM_VILLAGER.get());
         int open = 0;
         for (Todo t : c.todos()) if (t.isOpen()) open++;
         tag.putInt(K_OPEN_TODOS, open);
         tag.putInt(K_MEMORIES, c.memories().size());
         tag.putBoolean(K_HOME, c.playerSetHome());
         tag.putBoolean(K_JOB,  c.playerSetJob());
      }

      @Override
      public ResourceLocation getUid() { return UID; }
   }

   // ───────── client side ─────────

   private enum ClientLines implements IEntityComponentProvider {
      INSTANCE;

      @Override
      public void appendTooltip(ITooltip tip, EntityAccessor accessor, IPluginConfig cfg) {
         if (!(accessor.getEntity() instanceof LlmTownsfolk v)) return;

         // Activity comes from SynchedEntityData — already on the client.
         String activity = stripParens(v.getActivity());
         if (!activity.isEmpty()) {
            tip.add(Component.literal("Activity: " + activity).withStyle(ChatFormatting.YELLOW));
         }

         CompoundTag data = accessor.getServerData();
         if (data == null || !data.contains(K_MEMORIES)) return;  // server data not arrived yet

         int open = data.getInt(K_OPEN_TODOS);
         if (open > 0) {
            tip.add(Component.literal("Tasks: " + open + " open").withStyle(ChatFormatting.GRAY));
         }

         int mem = data.getInt(K_MEMORIES);
         tip.add(Component.literal("Memory: " + mem + " entries").withStyle(ChatFormatting.DARK_GRAY));

         boolean home = data.getBoolean(K_HOME);
         boolean job  = data.getBoolean(K_JOB);
         String anchored = home && job ? "home + work"
                         : home        ? "home only"
                         : job         ? "work only"
                         :               "wandering";
         tip.add(Component.literal("Anchored: " + anchored).withStyle(ChatFormatting.DARK_GRAY));
      }

      @Override
      public ResourceLocation getUid() { return UID; }
   }

   // ───────── storage tooltip (registered barrels) ─────────

   /** Pushes per-block storage metadata (label / mode / filter count) into
    *  Jade's server-data tag. Runs on the server because the
    *  {@link StorageRegistry} lives there only. */
   private enum StorageServerData implements IServerDataProvider<BlockAccessor> {
      INSTANCE;

      @Override
      public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
         BlockEntity be = accessor.getBlockEntity();
         if (!(be instanceof BarrelBlockEntity
             || be instanceof ChestBlockEntity
             || be instanceof ShulkerBoxBlockEntity)) return;
         if (!(accessor.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)) return;
         StorageConfig cfg = StorageRegistry.find(sl, accessor.getPosition());
         if (cfg == null) return;
         tag.putString(K_BARREL_LABEL, cfg.label() == null ? "" : cfg.label());
         tag.putString(K_BARREL_MODE, cfg.mode().name());
         tag.putInt(K_BARREL_FILTERS, cfg.filterCount());
      }

      @Override
      public ResourceLocation getUid() { return UID; }
   }

   /** Client-side renderer: reads the server-pushed barrel tag and adds:
    *    "<label>" (when set, as a gold heading-ish line)
    *    Townfolk storage · whitelist · 6/16 filters
    *  When no Townfolk config exists on this block, contributes nothing —
    *  vanilla chests / unowned barrels keep their stock tooltip clean. */
   private enum StorageClientLines implements IBlockComponentProvider {
      INSTANCE;

      @Override
      public void appendTooltip(ITooltip tip, BlockAccessor accessor, IPluginConfig cfg) {
         CompoundTag data = accessor.getServerData();
         if (data == null || !data.contains(K_BARREL_MODE)) return;
         String label = data.getString(K_BARREL_LABEL);
         if (label != null && !label.isBlank()) {
            tip.add(Component.literal("\"" + label + "\"").withStyle(ChatFormatting.GOLD));
         }
         String mode = data.getString(K_BARREL_MODE).toLowerCase(java.util.Locale.ROOT);
         int filters = data.getInt(K_BARREL_FILTERS);
         tip.add(Component.literal("Townfolk storage · " + mode + " · " + filters
            + "/" + com.yucareux.townfolk.town.StorageConfig.FILTER_SLOTS + " filters")
            .withStyle(ChatFormatting.GRAY));
      }

      @Override
      public ResourceLocation getUid() { return UID; }
   }

   /** Schedule labels are stored as "(at work)" etc. for the bubble; strip
    *  the outer parens for a cleaner tooltip line. */
   private static String stripParens(String s) {
      if (s == null) return "";
      String t = s.trim();
      if (t.length() >= 2 && t.charAt(0) == '(' && t.charAt(t.length() - 1) == ')') {
         return t.substring(1, t.length() - 1).trim();
      }
      return t;
   }
}
