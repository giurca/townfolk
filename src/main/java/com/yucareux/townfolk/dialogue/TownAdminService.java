package com.yucareux.townfolk.dialogue;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.llm.ModelPricing;
import com.yucareux.townfolk.llm.OpenRouterClient;
import com.yucareux.townfolk.llm.PersonaGenerator;
import com.yucareux.townfolk.network.AdminActionPayload;
import com.yucareux.townfolk.network.TownStateUpdatePayload;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.PinnedFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TownAdminService {

   public static void handle(Player rawPlayer, AdminActionPayload action) {
      com.yucareux.townfolk.diag.VerboseLog.write("ADMIN_ACTION",
         "player=" + (rawPlayer == null ? "?" : rawPlayer.getName().getString())
            + " action=" + action.action()
            + " townPos=" + action.townSquarePos()
            + " name=" + (action.name() == null ? "" : action.name())
            + " villagerUuid=" + action.villagerUuid().orElse(null),
         "");
      if (!(rawPlayer instanceof ServerPlayer player)) {
         com.yucareux.townfolk.diag.VerboseLog.write("ADMIN_ACTION_REJECT",
            "reason=not-server-player", "");
         return;
      }
      ServerLevel level = player.serverLevel();
      BlockPos pos = BlockPos.of(action.townSquarePos());
      BlockEntity be = level.getBlockEntity(pos);
      if (!(be instanceof TownSquareBlockEntity town)) {
         Townfolk.LOGGER.warn("Admin action targets non-town block at {}", pos);
         com.yucareux.townfolk.diag.VerboseLog.write("ADMIN_ACTION_REJECT",
            "reason=not-town-block pos=" + pos.toShortString() + " block="
               + (be == null ? "null" : be.getClass().getSimpleName()), "");
         return;
      }
      long day = level.getGameTime() / 24000L;

      switch (action.action()) {
         case OPEN -> pushState(player, level, town, "ok-cached");
         case REFRESH_SPEND -> refreshSpend(player, level, town);
         case SPAWN -> spawn(player, level, town, action.name(), action.role(), action.personaSeed());
         case REMOVE -> action.villagerUuid().ifPresent(uuid -> remove(player, level, town, uuid));
         case EDIT_PERSONA -> action.villagerUuid().ifPresent(uuid ->
            editPersona(player, level, town, uuid, action.personaSeed()));
         case REGENERATE_BACKSTORY -> action.villagerUuid().ifPresent(uuid ->
            regenerateBackstory(player, level, town, uuid));
         case RENAME_TOWN -> rename(player, level, town, action.name());
         case PIN_ADD -> pinAdd(player, level, town, action, day);
         case PIN_EDIT -> pinEdit(player, level, town, action);
         case PIN_RESOLVE -> pinResolve(player, level, town, action);
         case PIN_REMOVE -> pinRemove(player, level, town, action);
         case TODO_COMPLETE -> todoSetStatus(player, level, town, action, "done");
         case TODO_ABANDON -> todoSetStatus(player, level, town, action, "abandoned");
         case OPEN_PARCEL_EDITOR -> openParcelEditor(player, level, town, action.factId());
         case OPEN_ANIMAL_PLAN  -> openAnimalPlan(player, level, town, action.factId());
      }
   }

   /** Locate a parcel by id within this town, returning the parcel
    *  itself (so the caller has access to type, bounds, etc.) or null
    *  if no such parcel exists for this town. */
   private static com.yucareux.townfolk.villager.FieldRegion findParcelInTown(
         ServerLevel level, TownSquareBlockEntity town, String parcelId) {
      if (parcelId == null || parcelId.isEmpty()) return null;
      for (var entry : town.getTown().villagers()) {
         if (!entry.alive()) continue;
         var ent = level.getEntity(entry.uuid());
         if (ent == null) continue;
         var c = ent.getData(ModRegistries.LLM_VILLAGER.get());
         for (var p : c.parcels()) {
            if (parcelId.equals(p.id())) return p;
         }
      }
      return null;
   }

   private static void openParcelEditor(ServerPlayer player, ServerLevel level,
                                         TownSquareBlockEntity town, String parcelId) {
      var parcel = findParcelInTown(level, town, parcelId);
      if (parcel == null || parcel.type() != com.yucareux.townfolk.villager.FieldRegion.Type.PLANT) {
         com.yucareux.townfolk.diag.VerboseLog.write("ADMIN_ACTION_REJECT",
            "reason=parcel-not-plant-or-not-in-town parcelId=" + parcelId, "");
         return;
      }
      com.yucareux.townfolk.world.PendingFieldBinding.openCropPlanScreen(player, parcelId);
   }

   /** Build the animal-plan census for one ANIMAL parcel and open the
    *  modal client-side. Walks every adult+baby of every known farm
    *  species inside the parcel's AABB; tallies counts; joins with
    *  the saved plan so the modal renders with the player's previous
    *  settings pre-filled. */
   private static void openAnimalPlan(ServerPlayer player, ServerLevel level,
                                       TownSquareBlockEntity town, String parcelId) {
      var parcel = findParcelInTown(level, town, parcelId);
      if (parcel == null || parcel.type() != com.yucareux.townfolk.villager.FieldRegion.Type.ANIMAL) {
         com.yucareux.townfolk.diag.VerboseLog.write("ADMIN_ACTION_REJECT",
            "reason=parcel-not-animal-or-not-in-town parcelId=" + parcelId, "");
         return;
      }
      // Species we know how to count. Stage-5 keeps this list narrow —
      // the entities our herders interact with via livestock tasks.
      String[] speciesIds = {
         "minecraft:cow", "minecraft:sheep",
         "minecraft:pig", "minecraft:chicken",
         "minecraft:rabbit", "minecraft:goat"
      };

      var mn = parcel.scanMin(); var mx = parcel.scanMax();
      var aabb = new net.minecraft.world.phys.AABB(
         mn.getX(), mn.getY(), mn.getZ(),
         mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);

      com.yucareux.townfolk.town.AnimalPlan savedPlan =
         com.yucareux.townfolk.town.AnimalPlanRegistry.find(level, parcelId);

      java.util.ArrayList<com.yucareux.townfolk.network.OpenAnimalPlanPayload.SpeciesView> rows
         = new java.util.ArrayList<>();
      for (String speciesId : speciesIds) {
         net.minecraft.resources.ResourceLocation rl =
            net.minecraft.resources.ResourceLocation.tryParse(speciesId);
         if (rl == null) continue;
         var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(rl);
         if (type == null) continue;
         @SuppressWarnings("unchecked")
         var entities = level.getEntitiesOfClass(
            (Class<net.minecraft.world.entity.animal.Animal>)(Class<?>) net.minecraft.world.entity.animal.Animal.class,
            aabb,
            a -> a.isAlive() && a.getType() == type);
         if (entities.isEmpty()) {
            // Only include species with at least one present OR with a saved plan entry.
            var savedEntry = savedPlan.findSpecies(speciesId);
            if (savedEntry.isEmpty()) continue;
         }
         int adults = 0, babies = 0;
         for (var e : entities) {
            if (e.isBaby()) babies++; else adults++;
         }
         var savedEntry = savedPlan.findSpecies(speciesId);
         int target = savedEntry.map(com.yucareux.townfolk.town.AnimalPlan.Entry::targetCount).orElse(0);
         String mode = savedEntry.map(e -> e.mode().name()).orElse(com.yucareux.townfolk.town.AnimalPlan.Mode.HOLD.name());
         rows.add(new com.yucareux.townfolk.network.OpenAnimalPlanPayload.SpeciesView(
            speciesId, adults + babies, babies, target, mode));
      }

      net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
         new com.yucareux.townfolk.network.OpenAnimalPlanPayload(
            parcelId, town.getBlockPos().asLong(), rows));
      com.yucareux.townfolk.diag.VerboseLog.write("ANIMAL_PLAN_OPEN",
         "player=" + player.getName().getString() + " parcel=" + parcelId
            + " species=" + rows.size(), "");
   }

   private static void todoSetStatus(ServerPlayer player, ServerLevel level,
                                     TownSquareBlockEntity town, AdminActionPayload a, String newStatus) {
      String id = a.factId();
      if (id == null || id.isEmpty()) return;
      a.villagerUuid().ifPresent(uuid -> {
         Entity e = level.getEntity(uuid);
         if (e == null) return;
         LlmVillagerComponent c = e.getData(ModRegistries.LLM_VILLAGER.get());
         java.util.List<com.yucareux.townfolk.villager.Todo> next = new ArrayList<>(c.todos());
         for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(id)) {
               next.set(i, next.get(i).withStatus(newStatus));
               break;
            }
         }
         e.setData(ModRegistries.LLM_VILLAGER.get(), c.withTodos(next));
      });
      pushState(player, level, town, "ok-cached");
   }

   // ----- Pin actions -----

   private static void pinAdd(ServerPlayer player, ServerLevel level, TownSquareBlockEntity town,
                              AdminActionPayload a, long day) {
      String text = a.factText() == null ? "" : a.factText().trim();
      if (text.isEmpty()) return;
      PinnedFact fact = new PinnedFact(UUID.randomUUID().toString(), text, "active", day, true);
      if ("town".equals(a.factScope())) {
         town.getTown().addTownFact(fact);
         town.setChanged();
      } else {
         a.villagerUuid().ifPresent(uuid -> {
            Entity e = level.getEntity(uuid);
            if (e == null) return;
            LlmVillagerComponent c = e.getData(ModRegistries.LLM_VILLAGER.get());
            List<PinnedFact> next = new ArrayList<>(c.pinnedFacts());
            next.add(fact);
            e.setData(ModRegistries.LLM_VILLAGER.get(), c.withPinnedFacts(next));
         });
      }
      pushState(player, level, town, "ok-cached");
   }

   private static void pinEdit(ServerPlayer player, ServerLevel level, TownSquareBlockEntity town, AdminActionPayload a) {
      String id = a.factId();
      String text = a.factText() == null ? "" : a.factText().trim();
      if (id == null || id.isEmpty() || text.isEmpty()) return;
      if ("town".equals(a.factScope())) {
         town.getTown().findTownFact(id).ifPresent(f -> {
            town.getTown().replaceTownFact(f.withText(text));
            town.setChanged();
         });
      } else {
         a.villagerUuid().ifPresent(uuid -> mutatePersonalPin(level, uuid, id, f -> f.withText(text)));
      }
      pushState(player, level, town, "ok-cached");
   }

   private static void pinResolve(ServerPlayer player, ServerLevel level, TownSquareBlockEntity town, AdminActionPayload a) {
      String id = a.factId();
      if (id == null || id.isEmpty()) return;
      if ("town".equals(a.factScope())) {
         town.getTown().findTownFact(id).ifPresent(f -> {
            town.getTown().replaceTownFact(f.withStatus("resolved"));
            town.setChanged();
         });
      } else {
         a.villagerUuid().ifPresent(uuid -> mutatePersonalPin(level, uuid, id, f -> f.withStatus("resolved")));
      }
      pushState(player, level, town, "ok-cached");
   }

   private static void pinRemove(ServerPlayer player, ServerLevel level, TownSquareBlockEntity town, AdminActionPayload a) {
      String id = a.factId();
      if (id == null || id.isEmpty()) return;
      if ("town".equals(a.factScope())) {
         town.getTown().removeTownFact(id);
         town.setChanged();
      } else {
         a.villagerUuid().ifPresent(uuid -> {
            Entity e = level.getEntity(uuid);
            if (e == null) return;
            LlmVillagerComponent c = e.getData(ModRegistries.LLM_VILLAGER.get());
            List<PinnedFact> next = new ArrayList<>(c.pinnedFacts());
            next.removeIf(f -> f.id().equals(id));
            e.setData(ModRegistries.LLM_VILLAGER.get(), c.withPinnedFacts(next));
         });
      }
      pushState(player, level, town, "ok-cached");
   }

   private static void mutatePersonalPin(ServerLevel level, UUID villagerUuid, String factId,
                                         java.util.function.UnaryOperator<PinnedFact> op) {
      Entity e = level.getEntity(villagerUuid);
      if (e == null) return;
      LlmVillagerComponent c = e.getData(ModRegistries.LLM_VILLAGER.get());
      List<PinnedFact> next = new ArrayList<>(c.pinnedFacts());
      for (int i = 0; i < next.size(); i++) {
         if (next.get(i).id().equals(factId)) {
            next.set(i, op.apply(next.get(i)));
            break;
         }
      }
      e.setData(ModRegistries.LLM_VILLAGER.get(), c.withPinnedFacts(next));
   }

   // ----- Existing actions -----

   private static void spawn(ServerPlayer player, ServerLevel level,
                             TownSquareBlockEntity town, String name, String role, String personaSeed) {
      // Population-cap gate (Stage 10a): you can't recruit more
      // villagers than your town has Homes for (+4 if a Town Hall is
      // recognized). Build more houses first.
      int cap = com.yucareux.townfolk.building.PopulationCap.effectiveCap(level);
      int alive = town.getTown().aliveVillagerCount();
      if (alive >= cap) {
         String hint = cap == 0
            ? "No homes built yet — place a bed inside an enclosed room with a door first."
            : "Population at cap (" + alive + " / " + cap + "). "
              + "Build another Home"
              + (com.yucareux.townfolk.building.PopulationCap.hasTownHall(level)
                 ? "."
                 : ", or enclose your Charter Stone into a Town Hall for +4 cap.");
         player.sendSystemMessage(net.minecraft.network.chat.Component.literal(hint)
            .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
         com.yucareux.townfolk.diag.VerboseLog.write("SPAWN_REJECT",
            "town=" + town.getTown().townName()
               + " alive=" + alive + " cap=" + cap, hint);
         return;
      }
      String seed = personaSeed == null ? "" : personaSeed.trim();
      // Empty role is fine now — the field has been removed from the UI. We
      // leave the protocol's role string in place for backwards compat.
      String chosenRole = role == null || role.isBlank() ? "resident" : role.trim();

      // If the player left the name blank, ask the LLM for one before
      // continuing. We don't block — instead we spawn under a placeholder
      // and rename when the LLM responds. This keeps the spawn UI snappy.
      if (name == null || name.isBlank()) {
         if (!LlmClient.get().isConfigured()) {
            doSpawn(player, level, town, generateFallbackName(town), chosenRole, seed);
            return;
         }
         MinecraftServer srv = level.getServer();
         PersonaGenerator.generateName(town.getTown(), seed).thenAcceptAsync(result -> {
            String picked;
            if (result.ok() && result.content() != null && !result.content().isBlank()) {
               picked = sanitiseName(result.content());
               if (picked.isEmpty()) picked = generateFallbackName(town);
            } else {
               Townfolk.LOGGER.warn("Name generation failed: {}", result == null ? "(null)" : result.error());
               picked = generateFallbackName(town);
            }
            doSpawn(player, level, town, picked, chosenRole, seed);
         }, srv);
         return;
      }
      doSpawn(player, level, town, name.trim(), chosenRole, seed);
   }

   /** Strip whitespace, quotes, surrounding punctuation, multi-word fluff
    *  ("Marta, perhaps" → "Marta"). Caps to 24 chars. */
   private static String sanitiseName(String raw) {
      String s = raw.trim().replaceAll("[\\\"'`.,!?;:]", "").trim();
      // First whitespace-delimited token, in case the model returned a phrase.
      int sp = s.indexOf(' ');
      if (sp > 0) s = s.substring(0, sp);
      if (s.length() > 24) s = s.substring(0, 24);
      return s;
   }

   /** Used when the LLM isn't configured or returned junk. */
   private static String generateFallbackName(TownSquareBlockEntity town) {
      String[] pool = { "Hans", "Anya", "Brauhn", "Marta", "Ueli", "Liesl", "Otto", "Greta",
                        "Albrecht", "Frieda", "Klaus", "Heidi", "Rudi", "Else", "Werner", "Trude" };
      java.util.Set<String> taken = new java.util.HashSet<>();
      for (var v : town.getTown().villagers()) taken.add(v.name());
      for (int i = 0; i < pool.length; i++) {
         String candidate = pool[(int) (Math.random() * pool.length)];
         if (!taken.contains(candidate)) return candidate;
      }
      return "Resident-" + ((int) (Math.random() * 1000));
   }

   private static void doSpawn(ServerPlayer player, ServerLevel level,
                               TownSquareBlockEntity town, String chosenName, String chosenRole, String seed) {
      BlockPos spawnPos = town.getBlockPos().above();
      com.yucareux.townfolk.diag.VerboseLog.write("SPAWN_START",
         "name=" + chosenName + " role=" + chosenRole
            + " seed=\"" + (seed == null ? "" : seed.replace('\n', ' ')) + "\""
            + " townPos=" + town.getBlockPos().toShortString()
            + " spawnPos=" + spawnPos.toShortString(), "");
      // Custom entity — vanilla Villager subclass with empty Brain. See
      // com.yucareux.townfolk.entity.LlmTownsfolk for rationale.
      Villager villager;
      try {
         villager = ModRegistries.LLM_TOWNSFOLK.get().create(level);
      } catch (Throwable t) {
         com.yucareux.townfolk.diag.VerboseLog.write("SPAWN_FAIL",
            "stage=create name=" + chosenName, "exception: " + t);
         Townfolk.LOGGER.error("Spawn create failed for {}", chosenName, t);
         return;
      }
      if (villager == null) {
         com.yucareux.townfolk.diag.VerboseLog.write("SPAWN_FAIL",
            "stage=create name=" + chosenName, "EntityType.create returned null");
         return;
      }
      villager.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0F, 0F);
      villager.setCustomName(Component.literal(chosenName));
      villager.setCustomNameVisible(true);
      if (!level.addFreshEntity(villager)) {
         com.yucareux.townfolk.diag.VerboseLog.write("SPAWN_FAIL",
            "stage=addFreshEntity name=" + chosenName,
            "level.addFreshEntity returned false (chunk unloaded? duplicate UUID?)");
         return;
      }
      com.yucareux.townfolk.diag.VerboseLog.write("SPAWN_ENTITY_OK",
         "name=" + chosenName + " uuid=" + villager.getUUID() + " pos=" + spawnPos.toShortString(), "");

      VillagerEntry entry = new VillagerEntry(villager.getUUID(), chosenName, chosenRole, seed, spawnPos);
      town.getTown().addVillager(entry);
      town.setChanged();

      LlmVillagerComponent component = new LlmVillagerComponent(
         seed, "", villager.getUUID().toString(), town.getBlockPos().asLong(),
         java.util.List.of(), 0, 0L, 0L,
         java.util.List.of(), "", java.util.List.of(), level.getGameTime() / 24000L,
         java.util.List.of(), LlmVillagerComponent.Anchors.NONE, java.util.List.of(), java.util.List.of());
      villager.setData(ModRegistries.LLM_VILLAGER.get(), component);

      MinecraftServer server = level.getServer();
      pushState(player, level, town, "ok-cached");

      PersonaGenerator.generateBackstory(entry, town.getTown()).thenAcceptAsync(result -> {
         Entity fresh = level.getEntity(villager.getUUID());
         if (fresh == null) return;
         if (!result.ok()) {
            Townfolk.LOGGER.warn("Backstory generation failed for {}: {}", chosenName, result.error());
         } else {
            LlmVillagerComponent updated = fresh.getData(ModRegistries.LLM_VILLAGER.get())
               .withBackstory(result.content())
               .withUsage(result.inputTokens(), result.outputTokens());
            fresh.setData(ModRegistries.LLM_VILLAGER.get(), updated);
         }
         pushState(player, level, town, "ok-cached");
      }, server);
   }

   private static void remove(ServerPlayer player, ServerLevel level,
                              TownSquareBlockEntity town, UUID villagerUuid) {
      Entity entity = level.getEntity(villagerUuid);
      if (entity != null) entity.discard();
      town.getTown().removeVillager(villagerUuid);
      town.setChanged();
      pushState(player, level, town, "ok-cached");
   }

   private static void editPersona(ServerPlayer player, ServerLevel level,
                                   TownSquareBlockEntity town, UUID villagerUuid, String newSeed) {
      Optional<VillagerEntry> entry = town.getTown().findVillager(villagerUuid);
      if (entry.isEmpty()) return;
      entry.get().setPersonaSeed(newSeed == null ? "" : newSeed.trim());
      town.setChanged();
      Entity entity = level.getEntity(villagerUuid);
      if (entity != null) {
         LlmVillagerComponent component = entity.getData(ModRegistries.LLM_VILLAGER.get());
         entity.setData(ModRegistries.LLM_VILLAGER.get(),
            component.withPersonaSeed(entry.get().personaSeed()));
      }
      pushState(player, level, town, "ok-cached");
   }

   private static void regenerateBackstory(ServerPlayer player, ServerLevel level,
                                           TownSquareBlockEntity town, UUID villagerUuid) {
      Optional<VillagerEntry> entry = town.getTown().findVillager(villagerUuid);
      if (entry.isEmpty()) return;
      MinecraftServer server = level.getServer();
      PersonaGenerator.generateBackstory(entry.get(), town.getTown()).thenAcceptAsync(result -> {
         Entity fresh = level.getEntity(villagerUuid);
         if (fresh == null) return;
         if (!result.ok()) {
            Townfolk.LOGGER.warn("Backstory regeneration failed: {}", result.error());
         } else {
            LlmVillagerComponent updated = fresh.getData(ModRegistries.LLM_VILLAGER.get())
               .withBackstory(result.content())
               .withUsage(result.inputTokens(), result.outputTokens());
            fresh.setData(ModRegistries.LLM_VILLAGER.get(), updated);
         }
         pushState(player, level, town, "ok-cached");
      }, server);
   }

   private static void rename(ServerPlayer player, ServerLevel level,
                              TownSquareBlockEntity town, String newName) {
      town.getTown().setTownName(newName);
      town.setChanged();
      pushState(player, level, town, "ok-cached");
   }

   private static void refreshSpend(ServerPlayer player, ServerLevel level, TownSquareBlockEntity town) {
      pushState(player, level, town, "loading");
      MinecraftServer server = level.getServer();
      OpenRouterClient.get().fetchCredits().thenAcceptAsync(status -> {
         String label;
         double usage = -1, limit = -1;
         if (!LlmClient.get().isConfigured()) label = "disabled";
         else if (status.ok()) {
            label = "ok";
            usage = status.totalUsage().orElse(-1.0);
            limit = status.totalCredits().orElse(-1.0);
         } else {
            label = "error: " + (status.error() == null ? "unknown" : status.error());
         }
         pushState(player, level, town, label, usage, limit);
      }, server);
   }

   // ----- State serialisation + push -----

   private static void pushState(ServerPlayer player, ServerLevel level,
                                 TownSquareBlockEntity town, String openrouterStatus) {
      pushState(player, level, town, openrouterStatus, -1.0, -1.0);
   }

   private static void pushState(ServerPlayer player, ServerLevel level,
                                 TownSquareBlockEntity town, String openrouterStatus,
                                 double usage, double limit) {
      TownData data = town.getTown();
      List<TownStateUpdatePayload.VillagerSummary> summaries = new ArrayList<>(data.villagers().size());
      double totalCost = 0.0;
      String dialogueModel = com.yucareux.townfolk.config.TownfolkConfig.COMMON.dialogueModel.get();
      // Overview-tab tallies, accumulated as we walk villagers below.
      int idleCountTally = 0, workingCountTally = 0, sleepingCountTally = 0;
      int populationAliveTally = 0;
      // Per-parcel snapshot accumulator. We index the per-villager loop
      // for owner-name lookup without a second pass.
      List<TownStateUpdatePayload.ParcelSummary> parcelSummaries = new ArrayList<>();
      for (VillagerEntry entry : data.villagers()) {
         if (entry.alive()) populationAliveTally++;
         LlmVillagerComponent component = LlmVillagerComponent.EMPTY;
         Entity entity = level.getEntity(entry.uuid());
         if (entity != null) component = entity.getData(ModRegistries.LLM_VILLAGER.get());
         double cost = ModelPricing.estimateCostUsd(dialogueModel,
            component.inputTokens(), component.outputTokens());
         totalCost += cost;
         List<TownStateUpdatePayload.PinSummary> pins = new ArrayList<>(component.pinnedFacts().size());
         for (PinnedFact f : component.pinnedFacts()) {
            pins.add(new TownStateUpdatePayload.PinSummary(f.id(), f.text(), f.status(), f.createdDay()));
         }
         List<TownStateUpdatePayload.TodoSummary> todoOut = new ArrayList<>(component.todos().size());
         for (var t : component.todos()) {
            todoOut.add(new TownStateUpdatePayload.TodoSummary(
               t.id(), t.text(), t.counterparty(), t.status(), t.createdDay()));
         }
         // Body fields — pulled live off the entity.
         long packedPos = 0L;
         float hp = 0f, maxHp = 1f;
         String activity = "idle";
         String profession = "none";
         boolean isSleeping = false;
         List<TownStateUpdatePayload.ItemCount> invOut = new ArrayList<>();
         if (entity instanceof Villager v) {
            packedPos = v.blockPosition().asLong();
            hp = v.getHealth();
            maxHp = v.getMaxHealth();
            activity = com.yucareux.townfolk.world.ScheduleService.activityOf(v.getUUID());
            isSleeping = v.isSleeping();
            var pkey = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
               .getKey(v.getVillagerData().getProfession());
            profession = pkey == null ? "none" : pkey.getPath();
            var inv = v.getInventory();
            java.util.LinkedHashMap<String, Integer> merged = new java.util.LinkedHashMap<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
               var stack = inv.getItem(i);
               if (stack.isEmpty()) continue;
               var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
               String id = key == null ? "minecraft:air" : key.toString();
               merged.merge(id, stack.getCount(), Integer::sum);
            }
            for (var e : merged.entrySet())
               invOut.add(new TownStateUpdatePayload.ItemCount(e.getKey(), e.getValue()));
         }
         summaries.add(new TownStateUpdatePayload.VillagerSummary(
            entry.uuid(),
            entry.name(),
            entry.role(),
            entry.personaSeed() == null ? "" : entry.personaSeed(),
            component.backstory() == null ? "" : component.backstory(),
            entry.alive(),
            component.llmCalls(),
            component.inputTokens(),
            component.outputTokens(),
            cost,
            component.beliefs() == null ? "" : component.beliefs(),
            pins,
            component.memories().size(),
            component.lastCompactedDay(),
            todoOut,
            packedPos, hp, maxHp, activity,
            component.playerSetHome(), component.playerSetJob(),
            invOut,
            profession
         ));
         // Tally for overview counters. "sleeping" is authoritative;
         // anything else with an activity != "idle" is working; the
         // rest are idle (which includes "no actionable work" cases).
         if (entry.alive()) {
            if (isSleeping) sleepingCountTally++;
            else if (activity == null || activity.equals("idle")) idleCountTally++;
            else workingCountTally++;
         }
         // Per-parcel summaries — one row per owned parcel.
         if (entity instanceof Villager v) {
            for (var parcel : component.parcels()) {
               parcelSummaries.add(buildParcelSummary(level, parcel, entry, v));
            }
         }
      }
      List<TownStateUpdatePayload.PinSummary> townFacts = new ArrayList<>(data.townFacts().size());
      for (PinnedFact f : data.townFacts()) {
         townFacts.add(new TownStateUpdatePayload.PinSummary(f.id(), f.text(), f.status(), f.createdDay()));
      }
      var logSnap = data.log().snapshot();
      List<TownStateUpdatePayload.LogEntry> logOut = new ArrayList<>(logSnap.size());
      for (var le : logSnap) {
         logOut.add(new TownStateUpdatePayload.LogEntry(le.gameTime(), le.level().name(), le.message()));
      }
      // Storage snapshot — registered containers + LIVE contents. We
      // no longer rely on the auto-discovery ledger; player must
      // sneak-right-click chests to register them.
      //
      // Two new aggregate views built in the same pass:
      //   aggregateTotals  : item-id → sum across every container
      //   resourceLocs     : list of (item-id, container-pos, count)
      //                      so the Resources tab can show
      //                      "wheat is in barrel A (200), barrel B (56)".
      List<TownStateUpdatePayload.StorageEntry> storageOut = new ArrayList<>();
      java.util.LinkedHashMap<String, Integer> aggregateTotals = new java.util.LinkedHashMap<>();
      List<TownStateUpdatePayload.ResourceLoc> resourceLocs = new ArrayList<>();
      for (var e : com.yucareux.townfolk.town.StorageRegistry.entries(level)) {
         net.minecraft.core.BlockPos cpos = net.minecraft.core.BlockPos.of(e.getKey());
         var cfg = e.getValue();
         List<TownStateUpdatePayload.ItemCount> contents = new ArrayList<>();
         var be = level.getBlockEntity(cpos);
         if (be instanceof net.minecraft.world.Container c) {
            java.util.LinkedHashMap<String, Integer> merged = new java.util.LinkedHashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) {
               var s = c.getItem(i);
               if (s.isEmpty()) continue;
               var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
               if (id == null) continue;
               merged.merge(id.toString(), s.getCount(), Integer::sum);
            }
            for (var en : merged.entrySet()) {
               contents.add(new TownStateUpdatePayload.ItemCount(en.getKey(), en.getValue()));
               aggregateTotals.merge(en.getKey(), en.getValue(), Integer::sum);
               resourceLocs.add(new TownStateUpdatePayload.ResourceLoc(
                  en.getKey(), cpos.asLong(), en.getValue()));
            }
         }
         var blockState = level.getBlockState(cpos);
         var blockKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
         // Keep block kind as the raw name for the admin display's
         // existing renderer. A future patch can extend the payload
         // with a dedicated filter column.
         String kind = blockKey == null ? "container" : blockKey.getPath();
         String registeredBy = "(registered)";
         var server = level.getServer();
         if (server != null) {
            var profile = server.getProfileCache() == null ? null
               : server.getProfileCache().get(cfg.registeredBy()).orElse(null);
            if (profile != null) registeredBy = profile.getName();
         }
         storageOut.add(new TownStateUpdatePayload.StorageEntry(
            cpos.asLong(), kind, registeredBy, cfg.registeredAt(), contents,
            cfg.label() == null ? "" : cfg.label()));
      }

      // Snapshot production targets for the Resources tab. Every item
      // in DEFAULTS shows up, plus any player-overridden items not in
      // DEFAULTS, so the UI can render the full configurable surface
      // even when an item has zero stock right now.
      List<TownStateUpdatePayload.ProductionTarget> targetsOut = new ArrayList<>();
      for (var en : com.yucareux.townfolk.town.ProductionTargets.snapshot(level, town.getBlockPos()).entrySet()) {
         var t = en.getValue();
         targetsOut.add(new TownStateUpdatePayload.ProductionTarget(
            en.getKey(), t.min(), t.max(), t.active()));
      }

      // Sort the aggregate descending so the UI just iterates it.
      // Attach a trend signal computed against yesterday's snapshot
      // (TradeService rolls it at the start of each game day).
      List<TownStateUpdatePayload.ItemCount> aggregateOut = new ArrayList<>(aggregateTotals.size());
      aggregateTotals.entrySet().stream()
         .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
         .forEach(en -> {
            int trend = data.stockTrend(en.getKey(), en.getValue());
            aggregateOut.add(new TownStateUpdatePayload.ItemCount(en.getKey(), en.getValue(), trend));
         });
      // Resource-locations sorted by item then descending count — UI
      // can sub-slice by item and the order within each item already
      // shows the biggest stash first.
      resourceLocs.sort((a, b) -> {
         int c = a.itemId().compareTo(b.itemId());
         return c != 0 ? c : Integer.compare(b.count(), a.count());
      });

      // Births / deaths today — pulled from TownLog with simple
      // keyword matching since we don't yet have dedicated event
      // entries. Cheap and self-correcting if the log scrolls past.
      long currentDay = level.getGameTime() / 24000L;
      int birthsToday = 0, deathsToday = 0;
      for (var le : logSnap) {
         long entryDay = le.gameTime() / 24000L;
         if (entryDay != currentDay) continue;
         String m = le.message() == null ? "" : le.message().toLowerCase(java.util.Locale.ROOT);
         if (m.contains("was born") || m.contains("welcomed ")) birthsToday++;
         if (m.contains("died") || m.contains(" has died") || m.contains("was killed")) deathsToday++;
      }

      int tradePostCount = 0;
      for (var aux : data.auxiliaries()) {
         if (aux.type() == com.yucareux.townfolk.town.TownAuxiliaryType.TRADE_POST) tradePostCount++;
      }

      // Build client-facing views for ACTIVE trade offers. Resolved
      // offers stay in TownData (for end-of-day compaction) but never
      // ship — the UI shouldn't show fulfilled / expired anyway.
      long todayForTrades = level.getDayTime() / 24000L;
      java.util.ArrayList<TownStateUpdatePayload.TradeOfferView> tradeOffersOut = new java.util.ArrayList<>();
      for (var o : data.tradeOffers()) {
         if (!o.isActive()) continue;
         int daysRemaining = (int) Math.max(0, o.expireDay() - todayForTrades);
         tradeOffersOut.add(new TownStateUpdatePayload.TradeOfferView(
            o.id(),
            o.archetype().displayName(),
            o.tier().name(),
            o.requestItemId(),
            o.requestCount(),
            o.paymentEmeralds(),
            daysRemaining,
            o.flavorBlurb()
         ));
      }

      // Treasury snapshot — preserves insertion order (LinkedHashMap)
      // so newest payouts appear first when the player opens the tab.
      java.util.ArrayList<TownStateUpdatePayload.ItemCount> treasuryOut = new java.util.ArrayList<>();
      for (var e : data.treasury().entrySet()) {
         treasuryOut.add(new TownStateUpdatePayload.ItemCount(e.getKey(), e.getValue()));
      }

      PacketDistributor.sendToPlayer(player, new TownStateUpdatePayload(
         town.getBlockPos().asLong(),
         data.townName(),
         openrouterStatus,
         usage,
         limit,
         totalCost,
         summaries,
         townFacts,
         logOut,
         storageOut,
         aggregateOut,
         resourceLocs,
         targetsOut,
         parcelSummaries,
         tradeOffersOut,
         treasuryOut,
         populationAliveTally,
         data.villagers().size(),
         tradePostCount,
         data.prestige(),
         com.yucareux.townfolk.building.PopulationCap.homeCount(level),
         com.yucareux.townfolk.building.PopulationCap.hasTownHall(level),
         idleCountTally,
         workingCountTally,
         sleepingCountTally,
         birthsToday,
         deathsToday
      ));
   }

   /** Build a {@link TownStateUpdatePayload.ParcelSummary} for one
    *  villager-owned parcel. Scans the parcel AABB for ripe crops /
    *  empty farmland / tillable tiles / animal counts so the Parcels
    *  tab can render "at-a-glance" without doing its own server query. */
   private static TownStateUpdatePayload.ParcelSummary buildParcelSummary(
         ServerLevel level, com.yucareux.townfolk.villager.FieldRegion parcel,
         VillagerEntry owner, Villager v) {
      var mn = parcel.scanMin(); var mx = parcel.scanMax();
      int ripe = 0, empty = 0, tillable = 0, anim = 0, baby = 0, unshorn = 0;
      net.minecraft.core.BlockPos.MutableBlockPos cur = new net.minecraft.core.BlockPos.MutableBlockPos();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               var st = level.getBlockState(cur);
               if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock cb
                   && cb.isMaxAge(st)) { ripe++; }
               else if (st.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                        && level.getBlockState(cur.above()).isAir()) { empty++; }
               else if ((st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                         || st.is(net.minecraft.world.level.block.Blocks.DIRT))
                        && level.getBlockState(cur.above()).isAir()) { tillable++; }
            }
         }
      }
      var aabb = new net.minecraft.world.phys.AABB(
         mn.getX(), mn.getY(), mn.getZ(),
         mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
      for (var a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, aabb,
            ent -> ent.isAlive())) {
         anim++;
         if (a.isBaby()) baby++;
         if (a instanceof net.minecraft.world.entity.animal.Sheep sh
             && !sh.isSheared() && !sh.isBaby()) unshorn++;
      }
      net.minecraft.core.BlockPos centre = new net.minecraft.core.BlockPos(
         (mn.getX() + mx.getX()) / 2,
         (mn.getY() + mx.getY()) / 2,
         (mn.getZ() + mx.getZ()) / 2);
      int sizeX = mx.getX() - mn.getX() + 1;
      int sizeZ = mx.getZ() - mn.getZ() + 1;
      return new TownStateUpdatePayload.ParcelSummary(
         parcel.id(),
         owner.uuid(),
         owner.name(),
         parcel.type().name(),
         centre.asLong(),
         sizeX, sizeZ,
         parcel.createdDay(),
         ripe, empty, tillable, anim, baby, unshorn);
   }

   public static void openAdminPanel(ServerPlayer player, ServerLevel level, TownSquareBlockEntity town) {
      pushState(player, level, town, "ok-cached");
   }

   /** Open the admin panel and jump straight to a specific villager's detail
    *  view. Used when the player sneak-right-clicks a villager so they can
    *  skip the "back to the Town Square, navigate, click" round-trip. */
   public static void openAdminPanelFocused(ServerPlayer player, ServerLevel level,
                                            TownSquareBlockEntity town, UUID villagerUuid) {
      pushState(player, level, town, "ok-cached");
      // The focus packet rides after the state so the receiving screen exists
      // by the time it's processed.
      PacketDistributor.sendToPlayer(player,
         new com.yucareux.townfolk.network.OpenVillagerDetailPayload(
            town.getBlockPos().asLong(), villagerUuid));
   }

   private TownAdminService() {
   }
}
