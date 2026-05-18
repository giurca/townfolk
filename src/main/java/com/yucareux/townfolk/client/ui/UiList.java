package com.yucareux.townfolk.client.ui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A scrollable list of {@code T} laid out as fixed-height rows.
 *
 * Caller responsibilities:
 *   1. Construct with the area bounds + row height + a per-row renderer.
 *   2. Call {@link #setItems(List)} every frame (cheap — just stores ref).
 *   3. Call {@link #render(GuiGraphics, int, int)} from your screen render.
 *   4. Call {@link #onMouseScrolled(double, double, double)} from your screen
 *      mouseScrolled and {@link #itemAt(double, double)} from mouseClicked.
 *
 * What the list handles for you (so each tab doesn't reinvent it):
 *   - clipping (scissor) to the area bounds
 *   - cumulative scroll offset with bottom clamping
 *   - hover detection (row index passed to the renderer)
 *   - row-background fill (hover-aware)
 *   - hit-testing under the mouse → returns the item or null
 *
 * Per-row drawing is delegated. The renderer gets a {@link Row} record with
 * the absolute pixel coords of THIS row (so it doesn't have to know about
 * scroll offsets) and the index.
 */
public final class UiList<T> {

   /** Per-row context handed to the renderer. */
   public record Row<T>(GuiGraphics g, T item, int index, int x, int y, int w, int h, boolean hover) {}

   public interface RowRenderer<T> { void render(Row<T> row); }

   private int x, y, w, h;
   private final int rowHeight;
   private final RowRenderer<T> rowRenderer;

   private List<T> items = List.of();
   private int scrollOffset = 0;

   public UiList(int x, int y, int w, int h, int rowHeight, RowRenderer<T> rowRenderer) {
      this.x = x; this.y = y; this.w = w; this.h = h;
      this.rowHeight = rowHeight;
      this.rowRenderer = rowRenderer;
   }

   public void setItems(List<T> items) { this.items = items == null ? List.of() : items; clampScroll(); }
   public List<T> items() { return this.items; }

   /** Rebind to a new area without resetting scroll offset (clamped to the new size). */
   public void setBounds(int x, int y, int w, int h) {
      this.x = x; this.y = y; this.w = w; this.h = h;
      clampScroll();
   }

   private void clampScroll() {
      int max = Math.max(0, items.size() * rowHeight - h);
      if (scrollOffset > max) scrollOffset = max;
      if (scrollOffset < 0) scrollOffset = 0;
   }

   public void render(GuiGraphics g, int mouseX, int mouseY) {
      if (items.isEmpty()) return;
      // Pose-aware scissor — see TownAdminScreen.scaledScissor for
      // the full root-cause writeup. enableScissor uses raw
      // framebuffer pixels and ignores the parent PoseStack, so we
      // transform the corners through the live pose first.
      var m = g.pose().last().pose();
      org.joml.Vector4f tl = new org.joml.Vector4f(x, y, 0f, 1f).mul(m);
      org.joml.Vector4f br = new org.joml.Vector4f(x + w, y + h, 0f, 1f).mul(m);
      g.enableScissor(
         Math.round(Math.min(tl.x, br.x)),
         Math.round(Math.min(tl.y, br.y)),
         Math.round(Math.max(tl.x, br.x)),
         Math.round(Math.max(tl.y, br.y)));
      int rowY = y - scrollOffset;
      for (int i = 0; i < items.size(); i++, rowY += rowHeight) {
         if (rowY + rowHeight < y) continue;
         if (rowY > y + h) break;
         boolean hover = mouseX >= x && mouseX < x + w
                      && mouseY >= rowY && mouseY < rowY + rowHeight;
         g.fill(x, rowY, x + w, rowY + rowHeight - 2,
            hover ? UiTheme.ROW_BG_HOVER : UiTheme.ROW_BG);
         rowRenderer.render(new Row<>(g, items.get(i), i, x, rowY, w, rowHeight, hover));
      }
      g.disableScissor();
   }

   /** Resolve the item under the mouse, or null. */
   public T itemAt(double mouseX, double mouseY) {
      if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return null;
      int local = (int) (mouseY - y + scrollOffset);
      int idx = local / rowHeight;
      if (idx < 0 || idx >= items.size()) return null;
      return items.get(idx);
   }

   /** Forward this from your screen's mouseScrolled. Returns true if consumed. */
   public boolean onMouseScrolled(double mouseX, double mouseY, double scrollY) {
      if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return false;
      scrollOffset = Math.max(0, scrollOffset - (int) (scrollY * 18));
      clampScroll();
      return true;
   }

   public void resetScroll() { this.scrollOffset = 0; }
}
