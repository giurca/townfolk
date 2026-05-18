package com.yucareux.townfolk.world.inventory;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.CropPlan;
import com.yucareux.townfolk.town.CropPlanRegistry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * Container menu backing the {@link com.yucareux.townfolk.client.screen.CropPlanScreen}
 * crop-plan editor for a single PLANT parcel. Mirrors the
 * {@link StorageConfigMenu} pattern: a row of ghost slots (one per plan
 * entry, drag from inventory or JEI / EMI), a DataSlot per row holding
 * the weight (synced server → client), and {@link #removed} commits the
 * resulting {@link CropPlan} into {@link CropPlanRegistry} on close.
 *
 * <h2>Layout</h2>
 *   - Up to {@link CropPlan#MAX_ENTRIES} ghost slots, vertical column on
 *     the left.
 *   - Weight per row: small +/- buttons handled via
 *     {@link #clickMenuButton}, ids {@link #BUTTON_BUMP_BASE} ..
 *     {@code BUMP + MAX_ENTRIES - 1} for "+1", same for {@link #BUTTON_DROP_BASE}
 *     for "-1".
 *   - 36 player inventory slots underneath, vanilla layout (only used
 *     for shift-click → first empty row convenience).
 *
 * <h2>State sync</h2>
 *   - GhostSlot contents flow through the standard slot-changed
 *     broadcastChanges pipeline.
 *   - Weight DataSlots auto-sync when changed via clickMenuButton.
 *   - {@link #parcelId} is sent through the menu-open buffer so the
 *     client knows which parcel this screen is for (purely cosmetic —
 *     server-side commit re-resolves the id from {@link #parcelId}
 *     itself, not from anything sync-state-dependent).
 */
public final class CropPlanMenu extends AbstractContainerMenu {

   /** Button ID base for "+1 weight" — add the row index to get the
    *  actual id. So row 0's bump is {@link #BUTTON_BUMP_BASE}, row 1
    *  is BUTTON_BUMP_BASE + 1, etc. */
   public static final int BUTTON_BUMP_BASE = 100;
   /** Same scheme for "−1 weight". */
   public static final int BUTTON_DROP_BASE = 200;

   private final String parcelId;
   private final SimpleContainer ghostContainer;
   private final DataSlot[] weights;

   public CropPlanMenu(int id, Inventory inv, net.minecraft.network.RegistryFriendlyByteBuf buf) {
      this(id, inv, buf.readUtf(64));
   }

   public CropPlanMenu(int id, Inventory inv, String parcelId) {
      super(ModRegistries.CROP_PLAN_MENU.get(), id);
      this.parcelId = parcelId == null ? "" : parcelId;
      this.ghostContainer = new SimpleContainer(CropPlan.MAX_ENTRIES);
      this.weights = new DataSlot[CropPlan.MAX_ENTRIES];
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         this.weights[i] = DataSlot.standalone();
         this.addDataSlot(this.weights[i]);
      }

      // Pre-fill from the registry on the server side. Client constructs
      // empty and receives slot + DataSlot updates from broadcastChanges.
      if (inv.player.level() instanceof ServerLevel sl && !this.parcelId.isEmpty()) {
         CropPlan plan = CropPlanRegistry.find(sl, this.parcelId);
         List<CropPlan.Entry> entries = plan.entries();
         for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
            if (i < entries.size()) {
               CropPlan.Entry e = entries.get(i);
               this.weights[i].set(e.weight());
               var item = e.resolveItem();
               if (item != null) {
                  this.ghostContainer.setItem(i, new ItemStack(item, 1));
               }
            } else {
               this.weights[i].set(0);
            }
         }
      }

      // Ghost slots — one per plan row, vertical column.
      int slotX = 12;
      int slotY0 = 28;
      int rowH = 22;
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         this.addSlot(new GhostSlot(this.ghostContainer, i, slotX, slotY0 + i * rowH));
      }

      // Player inventory: 27 slots (3×9) + 9 hotbar. Below the rows.
      int invOriginX = 12;
      int invOriginY = slotY0 + CropPlan.MAX_ENTRIES * rowH + 12;
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

   public String parcelId() { return parcelId; }
   public SimpleContainer ghostContainer() { return ghostContainer; }
   public int weight(int row) { return weights[row].get(); }
   public static int filterRegionSize() { return CropPlan.MAX_ENTRIES; }

   @Override
   public boolean stillValid(Player player) {
      // The crop-plan editor isn't anchored to a block — it's keyed
      // by a parcel id. The only "close it" trigger is the player
      // dying or leaving the level. If the parcel itself was unbound
      // via another path while the menu was open, the commit on
      // close will create a new orphan plan entry (acceptable —
      // forget() cleans those up next time).
      return !player.isRemoved() && player.isAlive();
   }

   @Override
   public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
      // Ghost-slot click handling — same semantics as StorageConfigMenu.
      if (slotId >= 0 && slotId < CropPlan.MAX_ENTRIES) {
         Slot slot = this.slots.get(slotId);
         ItemStack cursor = this.getCarried();
         if (clickType == ClickType.PICKUP || clickType == ClickType.PICKUP_ALL) {
            if (cursor.isEmpty()) {
               slot.set(ItemStack.EMPTY);
               this.weights[slotId].set(0);    // clearing seed → weight 0
            } else if (!CropPlan.isPlantableSeed(cursor.getItem())) {
               // Refuse non-seed items so the plan never holds something
               // that would break the planter (e.g. dirt being "planted").
               return;
            } else {
               ItemStack ghost = cursor.copy();
               ghost.setCount(1);
               slot.set(ghost);
               // Default the row's weight to 1 if currently 0 — so a
               // freshly-dropped seed actually counts toward the plan.
               if (this.weights[slotId].get() <= 0) this.weights[slotId].set(1);
            }
            this.broadcastChanges();
            return;
         }
         if (clickType == ClickType.QUICK_MOVE) {
            slot.set(ItemStack.EMPTY);
            this.weights[slotId].set(0);
            this.broadcastChanges();
            return;
         }
         return;
      }
      super.clicked(slotId, dragType, clickType, player);
   }

   @Override
   public boolean clickMenuButton(Player player, int buttonId) {
      if (buttonId >= BUTTON_BUMP_BASE && buttonId < BUTTON_BUMP_BASE + CropPlan.MAX_ENTRIES) {
         int row = buttonId - BUTTON_BUMP_BASE;
         // Ignore bumps on empty rows — a weight without a seed is
         // meaningless and would render as "×1 (no seed)" on the screen.
         if (this.ghostContainer.getItem(row).isEmpty()) return true;
         int cur = this.weights[row].get();
         if (cur < 10) this.weights[row].set(cur + 1);
         this.broadcastChanges();
         return true;
      }
      if (buttonId >= BUTTON_DROP_BASE && buttonId < BUTTON_DROP_BASE + CropPlan.MAX_ENTRIES) {
         int row = buttonId - BUTTON_DROP_BASE;
         if (this.ghostContainer.getItem(row).isEmpty()) return true;
         int cur = this.weights[row].get();
         if (cur > 0) this.weights[row].set(cur - 1);
         this.broadcastChanges();
         return true;
      }
      return false;
   }

   @Override
   public ItemStack quickMoveStack(Player player, int slotId) {
      // Same convenience as StorageConfigMenu: shift-click a seed in
      // player inv → drop it into the first empty plan row.
      if (slotId >= CropPlan.MAX_ENTRIES && slotId < this.slots.size()) {
         Slot src = this.slots.get(slotId);
         ItemStack srcStack = src.getItem();
         if (!srcStack.isEmpty() && CropPlan.isPlantableSeed(srcStack.getItem())) {
            for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
               Slot dst = this.slots.get(i);
               if (dst.getItem().isEmpty()) {
                  ItemStack ghost = srcStack.copy();
                  ghost.setCount(1);
                  dst.set(ghost);
                  if (this.weights[i].get() <= 0) this.weights[i].set(1);
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
      if (this.parcelId.isEmpty()) return;
      ServerLevel level = sp.serverLevel();

      // Build the new plan from current slot + DataSlot state.
      List<CropPlan.Entry> entries = new ArrayList<>();
      for (int i = 0; i < CropPlan.MAX_ENTRIES; i++) {
         ItemStack stack = this.ghostContainer.getItem(i);
         int w = this.weights[i].get();
         if (stack.isEmpty() || w <= 0) continue;
         var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
         if (id == null) continue;
         entries.add(new CropPlan.Entry(id.toString(), w));
      }
      CropPlan plan = entries.isEmpty() ? CropPlan.DEFAULT : new CropPlan(entries);
      CropPlanRegistry.put(level, this.parcelId, plan);
      // Visible feedback so the player knows the close-to-commit
      // mechanic worked — they don't have to trust the registry log.
      StringBuilder summary = new StringBuilder();
      for (var e : plan.activeEntries()) {
         if (summary.length() > 0) summary.append(", ");
         String shortId = e.seedItemId();
         int colon = shortId.indexOf(':');
         summary.append(e.weight()).append("× ")
            .append((colon < 0 ? shortId : shortId.substring(colon + 1)).replace('_', ' '));
      }
      String chatLine = entries.isEmpty()
         ? "Plant plan saved: default (wheat only)"
         : "Plant plan saved: " + summary;
      sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(chatLine)
         .withStyle(net.minecraft.ChatFormatting.GREEN), false);
      VerboseLog.write("CROP_PLAN_COMMIT",
         "player=" + sp.getName().getString() + " parcel=" + this.parcelId,
         "entries=" + plan.activeEntries().size()
            + " totalWeight=" + plan.totalWeight()
            + " summary=" + summary);
   }
}
