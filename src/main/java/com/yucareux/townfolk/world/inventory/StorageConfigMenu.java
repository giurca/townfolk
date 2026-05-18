package com.yucareux.townfolk.world.inventory;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.StorageConfig;
import com.yucareux.townfolk.town.StorageFilterMode;
import com.yucareux.townfolk.town.StorageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side {@link AbstractContainerMenu} backing the storage-config
 * popup. 36 player-inventory slots (vanilla pass-through) + 16 ghost
 * slots holding the filter spec for the targeted barrel/chest/shulker.
 *
 * Click semantics (handled in {@link #clicked}):
 *   - Cursor item + click on ghost slot → store single-count copy in
 *     the slot. Cursor stack is untouched.
 *   - Empty cursor + click on ghost slot → clear that slot.
 *   - Shift-click on ghost slot → clear that slot (same as empty-click,
 *     prevents vanilla quick-move from pulling items out of inventory).
 *   - Player-inventory slots behave normally.
 *
 * Mode toggle is sent via the standard {@code clickMenuButton(0)}
 * mechanism — see {@link #clickMenuButton}. Saving happens in
 * {@link #removed} so closing the screen commits the current state.
 */
public final class StorageConfigMenu extends AbstractContainerMenu {

   /** Button id sent via clickMenuButton to flip whitelist/blacklist. */
   public static final int BUTTON_TOGGLE_MODE = 0;

   private final BlockPos containerPos;
   private final SimpleContainer filterContainer;
   /** Pre-fill label sent through the open buf. Client side reads this to
    *  seed the EditBox; server side stays in the registry and is ignored. */
   private final String initialLabel;
   /** Synced server→client via {@link AbstractContainerMenu#addDataSlot} so
    *  the client UI reflects the registered mode when a saved config is
    *  reopened. Value is {@link StorageFilterMode#ordinal()}. */
   private final DataSlot modeSlot = DataSlot.standalone();

   /** Client-side ctor — invoked when the player opens the menu; the
    *  barrel pos + existing label are sent in the extra data buffer. */
   public StorageConfigMenu(int id, Inventory inv, net.minecraft.network.RegistryFriendlyByteBuf buf) {
      this(id, inv, buf.readBlockPos(), buf.readUtf(64));
   }

   /** Server-side ctor (no label preload — the registry will be
    *  consulted directly in the same body). */
   public StorageConfigMenu(int id, Inventory inv, BlockPos containerPos) {
      this(id, inv, containerPos, "");
   }

   /** Shared ctor body. The {@code initialLabel} is only meaningful for
    *  the client-side construction; server-side, we look the live label
    *  up from the registry. */
   public StorageConfigMenu(int id, Inventory inv, BlockPos containerPos, String initialLabel) {
      super(ModRegistries.STORAGE_CONFIG_MENU.get(), id);
      this.containerPos = containerPos;
      this.initialLabel = initialLabel == null ? "" : initialLabel;
      this.filterContainer = new SimpleContainer(StorageConfig.FILTER_SLOTS);

      // Register the mode DataSlot BEFORE the initial value is set so
      // the very first broadcastChanges() picks it up. (DataSlot.set on
      // an unregistered slot doesn't notify anyone.)
      this.addDataSlot(modeSlot);

      // Pre-fill the filter container + mode from the existing
      // StorageConfig, if any. Server-side path only — client opens
      // with empty filter / WHITELIST default, then receives slot +
      // DataSlot syncs from the server.
      if (inv.player.level() instanceof ServerLevel sl) {
         StorageConfig existing = StorageRegistry.find(sl, containerPos);
         if (existing != null) {
            modeSlot.set(existing.mode().ordinal());
            for (int i = 0; i < StorageConfig.FILTER_SLOTS; i++) {
               filterContainer.setItem(i, existing.filter().get(i).copy());
            }
         }
      }

      // Slot positions live in the SAME absolute coords as the screen
      // (leftPos/topPos + slot.x/y). The 12-pixel header band added in
      // {@link com.yucareux.townfolk.client.screen.StorageConfigScreen}
      // for the title + label EditBox pushes everything down by
      // {@link #HEADER_BAND_PX} compared to the original layout.
      //
      // 16 ghost filter slots, 2 rows of 8, top of the panel.
      int filterOriginX = 26;
      int filterOriginY = 24 + HEADER_BAND_PX;
      int gap = 18;
      for (int row = 0; row < 2; row++) {
         for (int col = 0; col < 8; col++) {
            int idx = row * 8 + col;
            this.addSlot(new GhostSlot(filterContainer, idx,
               filterOriginX + col * gap, filterOriginY + row * gap));
         }
      }

      // 27 player inventory slots (3×9), then 9 hotbar slots, vanilla layout.
      int invOriginX = 8;
      int invOriginY = 84 + HEADER_BAND_PX;
      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col + row * 9 + 9,
               invOriginX + col * 18, invOriginY + row * 18));
         }
      }
      for (int col = 0; col < 9; col++) {
         this.addSlot(new Slot(inv, col, invOriginX + col * 18, invOriginY + 58));
      }
   }

   /** Extra vertical room added at the top of the popup for the title
    *  + optional label EditBox. The screen consults the same constant
    *  when shifting its own widgets. Keep these in sync. */
   public static final int HEADER_BAND_PX = 18;

   public BlockPos containerPos() { return containerPos; }
   /** Label seeded from the open buffer — clients use this to pre-fill
    *  the screen's EditBox; server callers should consult the live
    *  {@link StorageRegistry} entry instead. */
   public String initialLabel() { return initialLabel; }
   public StorageFilterMode mode() {
      int o = modeSlot.get();
      var values = StorageFilterMode.values();
      return values[o >= 0 && o < values.length ? o : 0];
   }
   public SimpleContainer filterContainer() { return filterContainer; }

   /** Slot count of the GHOST filter region — first 16 slots in the menu. */
   public static int filterRegionSize() { return StorageConfig.FILTER_SLOTS; }

   @Override
   public boolean stillValid(Player player) {
      // Distance gate + block-still-present check, matching vanilla
      // container-menu semantics. Without this, a player can hold the
      // menu open while walking 200 blocks away and our SetGhostSlot /
      // SetStorageLabel handlers will still mutate the config.
      if (player.isRemoved() || !player.isAlive()) return false;
      if (containerPos == null) return true;       // server-side edge case
      // 8-block reach radius matches AbstractContainerMenu.stillValid's
      // canInteractWithBlockEntity heuristic.
      double dx = player.getX() - (containerPos.getX() + 0.5);
      double dy = player.getY() - (containerPos.getY() + 0.5);
      double dz = player.getZ() - (containerPos.getZ() + 0.5);
      if (dx * dx + dy * dy + dz * dz > 64.0) return false;
      // Block must still be a storage block.
      if (player.level().isLoaded(containerPos)) {
         var be = player.level().getBlockEntity(containerPos);
         if (!com.yucareux.townfolk.town.StorageIndex.isStorage(be)) return false;
      }
      return true;
   }

   @Override
   public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
      // Ghost slot click handling — intercept before vanilla does anything.
      if (slotId >= 0 && slotId < filterRegionSize()) {
         Slot slot = this.slots.get(slotId);
         ItemStack cursor = this.getCarried();
         if (clickType == ClickType.PICKUP || clickType == ClickType.PICKUP_ALL) {
            if (cursor.isEmpty()) {
               // Empty hand → clear slot.
               slot.set(ItemStack.EMPTY);
            } else {
               // Item in hand → store single-count copy. Cursor not modified.
               ItemStack ghost = cursor.copy();
               ghost.setCount(1);
               slot.set(ghost);
            }
            this.broadcastChanges();
            return;
         }
         if (clickType == ClickType.QUICK_MOVE) {
            // Shift-click on filter slot → clear.
            slot.set(ItemStack.EMPTY);
            this.broadcastChanges();
            return;
         }
         // Throw / swap / clone / etc.: ignore so items don't escape.
         return;
      }
      super.clicked(slotId, dragType, clickType, player);
   }

   @Override
   public boolean clickMenuButton(Player player, int buttonId) {
      if (buttonId == BUTTON_TOGGLE_MODE) {
         StorageFilterMode next = mode().toggled();
         modeSlot.set(next.ordinal());      // auto-syncs to client on next broadcast
         this.broadcastChanges();
         VerboseLog.write("STORAGE_TOGGLE_MODE",
            "player=" + player.getName().getString()
               + " pos=" + containerPos.toShortString() + " mode=" + next, "");
         return true;
      }
      return false;
   }

   /** Block vanilla quick-move from yanking player-inventory items into
    *  the ghost region — items shouldn't leave the player's bag just
    *  because they shift-clicked a slot. */
   @Override
   public ItemStack quickMoveStack(Player player, int slotId) {
      // Shift-click in the ghost region clears the slot (handled in clicked()).
      // Shift-click in player inventory: take the held item kind into
      // the FIRST empty ghost slot — convenience for "shift to add to filter".
      if (slotId >= filterRegionSize() && slotId < this.slots.size()) {
         Slot src = this.slots.get(slotId);
         ItemStack srcStack = src.getItem();
         if (!srcStack.isEmpty()) {
            for (int i = 0; i < filterRegionSize(); i++) {
               Slot dst = this.slots.get(i);
               if (dst.getItem().isEmpty()) {
                  ItemStack ghost = srcStack.copy();
                  ghost.setCount(1);
                  dst.set(ghost);
                  this.broadcastChanges();
                  break;
               }
            }
         }
      }
      return ItemStack.EMPTY;
   }

   @Override
   public void removed(Player player) {
      super.removed(player);
      if (!(player instanceof ServerPlayer sp)) return;
      ServerLevel level = sp.serverLevel();

      // Build a fresh StorageConfig from the current filter container +
      // mode, persist to the registry. Preserve the original
      // registeredBy/At if this was an update.
      StorageConfig existing = StorageRegistry.find(level, containerPos);
      java.util.UUID by   = existing != null ? existing.registeredBy() : sp.getUUID();
      long at             = existing != null ? existing.registeredAt() : level.getGameTime();
      String label        = existing != null ? existing.label() : "";

      java.util.List<ItemStack> slots = new java.util.ArrayList<>(StorageConfig.FILTER_SLOTS);
      for (int i = 0; i < StorageConfig.FILTER_SLOTS; i++) {
         slots.add(filterContainer.getItem(i).copy());
      }
      StorageConfig cfg = new StorageConfig(mode(), slots, label, by, at);
      StorageRegistry.put(level, containerPos, cfg);
   }
}
