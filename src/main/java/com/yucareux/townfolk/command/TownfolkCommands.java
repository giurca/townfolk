package com.yucareux.townfolk.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.llm.PersonaGenerator;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Scaffold-era admin commands — once the in-game UI is in, these stay
 * available as ops-friendly shortcuts.
 *
 *   /townfolk list                          - show town + villager roster
 *   /townfolk spawn <name>                  - spawn a vanilla villager,
 *                                              register them with the town
 *   /townfolk remove <name>                 - kill villager + drop from town
 *   /townfolk name <new name>               - rename the town
 *
 * All commands operate on the nearest Town Square within 32 blocks of the
 * source position. Lookup is server-thread, naive O(scan loaded BEs); fine
 * for the scaffold, would tighten if we ever ran many towns.
 */
public final class TownfolkCommands {

   private static final int SEARCH_RADIUS = 32;

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      // Player-runnable subcommands (no perm gate) — invoked by ClickEvent hooks
      // in chat hails. Keep this list tiny and read-only-ish.
      dispatcher.register(
         Commands.literal("townfolk")
            .then(Commands.literal("talk")
               .then(Commands.argument("uuid", StringArgumentType.word())
                  .executes(ctx -> talk(ctx.getSource(), StringArgumentType.getString(ctx, "uuid")))))
      );
      // Admin subcommands (perm 2).
      dispatcher.register(
         Commands.literal("townfolk")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("spawn")
               .then(Commands.argument("name", StringArgumentType.word())
                  .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("remove")
               .then(Commands.argument("name", StringArgumentType.word())
                  .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("name")
               .then(Commands.argument("name", StringArgumentType.greedyString())
                  .executes(ctx -> rename(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("describe")
               .then(Commands.argument("name", StringArgumentType.word())
                  .executes(ctx -> describe(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
      );
   }

   /** Opens dialogue with a specific villager by UUID. Invoked by chat-hail ClickEvent. */
   private static int talk(CommandSourceStack src, String uuidStr) {
      net.minecraft.server.level.ServerPlayer player;
      try {
         player = src.getPlayerOrException();
      } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
         return 0;
      }
      UUID uuid;
      try { uuid = UUID.fromString(uuidStr); }
      catch (IllegalArgumentException e) { return 0; }
      net.minecraft.world.entity.Entity ent = src.getLevel().getEntity(uuid);
      if (ent == null) {
         src.sendFailure(Component.literal("(they've wandered off)"));
         return 0;
      }
      com.yucareux.townfolk.dialogue.DialogueService.openConversation(player, ent);
      return 1;
   }

   private static Optional<TownSquareBlockEntity> findNearest(CommandSourceStack src) {
      ServerLevel level = src.getLevel();
      BlockPos origin = BlockPos.containing(src.getPosition());
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      TownSquareBlockEntity best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
         for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
               cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
               BlockEntity be = level.getBlockEntity(cursor);
               if (be instanceof TownSquareBlockEntity town) {
                  double d = cursor.distSqr(origin);
                  if (d < bestDist) {
                     bestDist = d;
                     best = town;
                  }
               }
            }
         }
      }
      return Optional.ofNullable(best);
   }

   private static int list(CommandSourceStack src) {
      Optional<TownSquareBlockEntity> opt = findNearest(src);
      if (opt.isEmpty()) {
         src.sendFailure(Component.literal("No Town Square within " + SEARCH_RADIUS + " blocks."));
         return 0;
      }
      TownData t = opt.get().getTown();
      src.sendSuccess(() -> Component.literal(String.format("Town: %s (radius %d)", t.townName(), t.defaultRadius())), false);
      if (t.villagers().isEmpty()) {
         src.sendSuccess(() -> Component.literal("  (no villagers)"), false);
      } else {
         for (VillagerEntry v : t.villagers()) {
            src.sendSuccess(() -> Component.literal(String.format(
               "  - %s [%s] %s",
               v.name(), v.role(), v.alive() ? "" : "(deceased)"
            )), false);
         }
      }
      return t.villagerCount();
   }

   private static int spawn(CommandSourceStack src, String name) {
      Optional<TownSquareBlockEntity> opt = findNearest(src);
      if (opt.isEmpty()) {
         src.sendFailure(Component.literal("No Town Square within " + SEARCH_RADIUS + " blocks."));
         return 0;
      }
      TownSquareBlockEntity be = opt.get();
      ServerLevel level = src.getLevel();
      BlockPos spawnPos = be.getBlockPos().above();

      Villager villager = ModRegistries.LLM_TOWNSFOLK.get().create(level);
      if (villager == null) {
         src.sendFailure(Component.literal("Could not create villager entity."));
         return 0;
      }
      villager.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0F, 0F);
      villager.setCustomName(Component.literal(name));
      villager.setCustomNameVisible(true);
      if (!level.addFreshEntity(villager)) {
         src.sendFailure(Component.literal("Failed to add villager to world."));
         return 0;
      }

      VillagerEntry entry = new VillagerEntry(
         villager.getUUID(),
         name,
         "wanderer",
         "",
         spawnPos
      );
      be.getTown().addVillager(entry);
      be.setChanged();

      // Attach the LLM-side component to the entity itself.
      LlmVillagerComponent component = LlmVillagerComponent.EMPTY
         .withPersonaSeed(entry.personaSeed());
      // Patch in the namespace + town anchor too.
      component = new LlmVillagerComponent(
         component.personaSeed(), "", villager.getUUID().toString(),
         be.getBlockPos().asLong(), java.util.List.of(), 0, 0L, 0L,
         java.util.List.of(), "", java.util.List.of(), src.getLevel().getGameTime() / 24000L,
         java.util.List.of(), LlmVillagerComponent.Anchors.NONE, java.util.List.of(), java.util.List.of());
      villager.setData(ModRegistries.LLM_VILLAGER.get(), component);

      src.sendSuccess(() -> Component.literal("Spawned " + name + " in " + be.getTown().townName() + "."), true);

      // Fire async backstory generation. Result re-applied on the server thread
      // so we can safely call setData.
      MinecraftServer server = src.getServer();
      PersonaGenerator.generateBackstory(entry, be.getTown())
         .thenAcceptAsync(result -> {
            if (!result.ok()) {
               if (LlmClient.get().isConfigured()) {
                  src.sendSystemMessage(Component.literal(
                     "[Townfolk] Backstory generation for " + name + " failed: " + result.error()));
               }
               return;
            }
            var fresh = level.getEntity(villager.getUUID());
            if (fresh == null) {
               return; // entity removed/unloaded in the meantime
            }
            LlmVillagerComponent updated = fresh.getData(ModRegistries.LLM_VILLAGER.get())
               .withBackstory(result.content())
               .withUsage(result.inputTokens(), result.outputTokens());
            fresh.setData(ModRegistries.LLM_VILLAGER.get(), updated);
            src.sendSystemMessage(Component.literal(
               "[Townfolk] " + name + "'s backstory recorded."));
         }, server);
      return 1;
   }

   private static int remove(CommandSourceStack src, String name) {
      Optional<TownSquareBlockEntity> opt = findNearest(src);
      if (opt.isEmpty()) {
         src.sendFailure(Component.literal("No Town Square within " + SEARCH_RADIUS + " blocks."));
         return 0;
      }
      TownSquareBlockEntity be = opt.get();
      Optional<VillagerEntry> match = be.getTown().villagers().stream()
         .filter(v -> v.name().equalsIgnoreCase(name))
         .findFirst();
      if (match.isEmpty()) {
         src.sendFailure(Component.literal("No villager named '" + name + "' in this town."));
         return 0;
      }
      UUID uuid = match.get().uuid();
      ServerLevel level = src.getLevel();
      var entity = level.getEntity(uuid);
      if (entity != null) {
         entity.discard();
      }
      be.getTown().removeVillager(uuid);
      be.setChanged();
      src.sendSuccess(() -> Component.literal("Removed " + name + "."), true);
      return 1;
   }

   private static int describe(CommandSourceStack src, String name) {
      Optional<TownSquareBlockEntity> opt = findNearest(src);
      if (opt.isEmpty()) {
         src.sendFailure(Component.literal("No Town Square within " + SEARCH_RADIUS + " blocks."));
         return 0;
      }
      Optional<VillagerEntry> match = opt.get().getTown().villagers().stream()
         .filter(v -> v.name().equalsIgnoreCase(name))
         .findFirst();
      if (match.isEmpty()) {
         src.sendFailure(Component.literal("No villager named '" + name + "' in this town."));
         return 0;
      }
      VillagerEntry entry = match.get();
      ServerLevel level = src.getLevel();
      var entity = level.getEntity(entry.uuid());
      String backstory = "(no entity loaded)";
      if (entity != null) {
         backstory = entity.getData(ModRegistries.LLM_VILLAGER.get()).backstoryOrEmpty()
            .orElse("(backstory pending or not generated)");
      }
      String finalBackstory = backstory;
      src.sendSuccess(() -> Component.literal(String.format(
         "%s — %s\n  persona: %s\n  backstory: %s",
         entry.name(),
         entry.role(),
         entry.personaSeed().isBlank() ? "(none)" : entry.personaSeed(),
         finalBackstory
      )), false);
      return 1;
   }

   private static int rename(CommandSourceStack src, String name) {
      Optional<TownSquareBlockEntity> opt = findNearest(src);
      if (opt.isEmpty()) {
         src.sendFailure(Component.literal("No Town Square within " + SEARCH_RADIUS + " blocks."));
         return 0;
      }
      opt.get().getTown().setTownName(name);
      opt.get().setChanged();
      src.sendSuccess(() -> Component.literal("Town renamed to " + name + "."), true);
      return 1;
   }

   private TownfolkCommands() {
   }
}
