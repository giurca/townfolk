package com.yucareux.townfolk.client.screen;

import com.yucareux.townfolk.network.OpenDialoguePayload;
import com.yucareux.townfolk.network.PlayerSpeaksPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Chat-style screen for talking to a villager. Layout:
 *
 *   ┌─────────────────────────────────┐
 *   │  Name                       [i] │   header
 *   ├─────────────────────────────────┤
 *   │                                 │   transcript pane
 *   │  Hans: Good day, traveller.    │   (left-aligned villager,
 *   │                                 │    right-aligned player,
 *   │            you: Hello, Hans.   │    each behind a tinted pill)
 *   │  Hans: ...                      │
 *   │                                 │
 *   ├─────────────────────────────────┤
 *   │  [______________________] [Send]│   input bar
 *   └─────────────────────────────────┘
 *
 * Mouse wheel scrolls the transcript. Backstory hidden by default; reveal
 * on hovering / clicking the [i] glyph in the header.
 */
public final class DialogueScreen extends Screen {

   // Warm, nostalgic palette: dark coffee panel, aged-bronze border,
   // parchment-cream text on amber/walnut bubble pills.
   private static final int PANEL_BG       = 0xEE1A130E;
   private static final int PANEL_BORDER   = 0xFF8C6E3D;
   private static final int HEADER_BG      = 0xFF2C1F14;
   private static final int INPUT_BG       = 0xEE1A130E;
   private static final int VILLAGER_BG    = 0xFF5A3A18;
   private static final int VILLAGER_HI    = 0xFF6E4A22; // 1px top highlight for depth
   private static final int VILLAGER_FG    = 0xFFFFE0A8;
   private static final int PLAYER_BG      = 0xFF3D2D1E;
   private static final int PLAYER_HI      = 0xFF4F3B27;
   private static final int PLAYER_FG      = 0xFFEDE0C2;
   private static final int META_FG        = 0xFFB89B70;

   private static final int PADDING = 12;
   private static final int BUBBLE_PAD_X = 9;
   private static final int BUBBLE_PAD_Y = 5;
   // BUBBLE_GAP needs to accommodate the role label (~9 px tall) that sits
   // above each bubble, plus a few pixels of breathing room. Anything less
   // and the label overlaps the previous bubble below.
   private static final int BUBBLE_GAP = 14;
   private static final int LABEL_GAP = 3; // space between bubble top and label baseline
   private static final int MAX_PANEL_WIDTH = 560;
   private static final int MAX_BUBBLE_RATIO = 75; // % of pane width

   private final UUID villagerUuid;
   private final String villagerName;
   private final String backstory;
   private final List<OpenDialoguePayload.NeedFlag> needs;
   private final List<Line> lines = new ArrayList<>();
   private EditBox input;
   private boolean awaitingReply;
   private boolean showBackstory;
   private int scrollOffset; // pixels scrolled up from "stuck to bottom"

   public DialogueScreen(UUID villagerUuid, String villagerName, String backstory,
                         List<OpenDialoguePayload.Turn> history,
                         List<OpenDialoguePayload.NeedFlag> needs) {
      super(Component.literal(villagerName));
      this.villagerUuid = villagerUuid;
      this.villagerName = villagerName;
      this.backstory = backstory == null ? "" : backstory;
      this.needs = needs == null ? java.util.List.of() : needs;
      for (OpenDialoguePayload.Turn t : history) {
         this.lines.add(new Line(t.role(), t.text()));
      }
   }

   @Override
   protected void init() {
      super.init();
      int paneWidth = panelWidth();
      int paneX = (this.width - paneWidth) / 2;
      int inputY = panelBottom() - PADDING - 22;

      this.input = new EditBox(this.font,
         paneX + PADDING, inputY,
         paneWidth - PADDING * 2 - 70, 20,
         Component.literal("say"));
      this.input.setMaxLength(300);
      this.input.setBordered(true);
      this.addRenderableWidget(this.input);
      this.setInitialFocus(this.input);

      Button send = Button.builder(Component.literal("Send"), b -> this.sendInput())
         .bounds(paneX + paneWidth - PADDING - 64, inputY, 64, 20)
         .build();
      this.addRenderableWidget(send);

      // Needs banner: small buttons just below the header. Each button just
      // prefills + sends a natural phrase — the LLM picks it up and the
      // resulting [ACTION: follow] / chat-hint flow handles the rest. Player
      // then walks to the bed and presses X to claim.
      //
      // "Show them work" retired with the land-driven model — villagers no
      // longer need workstations; they need parcels assigned via the
      // Surveyor's Stake. Only the bed-assignment shortcut remains.
      int bannerY = panelTop() + 24;
      int bx = paneX + PADDING;
      for (OpenDialoguePayload.NeedFlag need : this.needs) {
         if (!"home".equals(need.kind())) continue;
         String label = "Show them a bed";
         String phrase = "Come with me — I'll show you where you can sleep.";
         int w = this.font.width(label) + 12;
         Button b = Button.builder(Component.literal(label), btn -> {
            this.input.setValue(phrase);
            this.sendInput();
         }).bounds(bx, bannerY, w, 16).build();
         this.addRenderableWidget(b);
         bx += w + 4;
      }
   }

   private int panelWidth() {
      // Target ~45% of the visible width, clamped so it stays a chat window
      // rather than a fullscreen modal even at high GUI scale.
      int target = (int) (this.width * 0.55);
      return Math.max(280, Math.min(target, 460));
   }

   private int panelHeight() {
      // Target ~50% of visible height; ensures a clear band of world is
      // visible above so the conversation feels grounded.
      int target = (int) (this.height * 0.55);
      return Math.max(200, Math.min(target, 280));
   }

   private int panelTop() {
      // Bias toward the lower half — leaves the villager visible above the panel.
      return (this.height - panelHeight()) / 2 + this.height / 12;
   }

   private int panelBottom() {
      return panelTop() + panelHeight();
   }

   private void sendInput() {
      if (this.awaitingReply) return;
      String text = this.input.getValue().trim();
      if (text.isEmpty()) return;
      this.input.setValue("");
      this.lines.add(new Line("player", text));
      this.lines.add(new Line("pending", this.villagerName + " is thinking..."));
      this.awaitingReply = true;
      this.scrollOffset = 0;
      PacketDistributor.sendToServer(new PlayerSpeaksPayload(this.villagerUuid, text));
   }

   public void receiveReply(UUID who, String text, boolean ok) {
      if (!who.equals(this.villagerUuid)) return;
      if (!this.lines.isEmpty() && this.lines.get(this.lines.size() - 1).role.equals("pending")) {
         this.lines.remove(this.lines.size() - 1);
      }
      this.lines.add(new Line(ok ? "villager" : "error", text));
      this.awaitingReply = false;
      this.scrollOffset = 0;
   }

   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.input.isFocused()) {
         this.sendInput();
         return true;
      }
      if (keyCode == GLFW.GLFW_KEY_I && !this.input.isFocused()) {
         this.showBackstory = !this.showBackstory;
         return true;
      }
      return super.keyPressed(keyCode, scanCode, modifiers);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int delta = (int) (scrollY * 18);
      this.scrollOffset = Math.max(0, this.scrollOffset + delta);
      return true;
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      // Click the [i] glyph in the header to toggle backstory.
      int paneWidth = panelWidth();
      int paneX = (this.width - paneWidth) / 2;
      int infoX = paneX + paneWidth - PADDING - 14;
      int infoY = panelTop() + PADDING - 2;
      if (mouseX >= infoX - 2 && mouseX <= infoX + 12 && mouseY >= infoY - 2 && mouseY <= infoY + 12) {
         this.showBackstory = !this.showBackstory;
         return true;
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      // Don't dim the world — the panel itself is opaque enough; keeping the
      // surrounding scene bright makes the chat feel like a HUD overlay
      // rather than a fullscreen modal.

      int paneWidth = panelWidth();
      int paneX = (this.width - paneWidth) / 2;
      int paneTop = panelTop();
      int paneBottom = panelBottom();

      int headerH = 22;
      int inputBarH = 38;
      int transcriptTop = paneTop + headerH + 4;
      int transcriptBottom = paneBottom - inputBarH;

      // Panel body — single unified container, drawn before any content so
      // bubbles + text render cleanly on top in call order.
      graphics.fill(paneX, paneTop, paneX + paneWidth, paneBottom, PANEL_BG);

      // Frame outline (1px) around the whole panel.
      int l = paneX, r = paneX + paneWidth, top = paneTop, bot = paneBottom;
      graphics.fill(l - 1, top - 1, r + 1, top, PANEL_BORDER);
      graphics.fill(l - 1, bot, r + 1, bot + 1, PANEL_BORDER);
      graphics.fill(l - 1, top - 1, l, bot + 1, PANEL_BORDER);
      graphics.fill(r, top - 1, r + 1, bot + 1, PANEL_BORDER);

      // Header strip on top of the panel body.
      graphics.fill(paneX, paneTop, paneX + paneWidth, paneTop + headerH, HEADER_BG);
      graphics.fill(paneX, paneTop + headerH, paneX + paneWidth, paneTop + headerH + 1, PANEL_BORDER);
      graphics.drawString(this.font, Component.literal(this.villagerName).copy(),
         paneX + PADDING, paneTop + 7, 0xFFFFD27A, true);
      int infoX = paneX + paneWidth - PADDING - 8;
      graphics.drawString(this.font, "[i]", infoX - 6, paneTop + 7, META_FG, true);

      // No separator; the input field lives directly inside the panel body
      // so the chat feels like one continuous space, not two stacked strips.

      // Transcript vs backstory are mutually exclusive — bubble text glyphs
      // always batch at a higher Z than fill rectangles in GuiGraphics, so
      // they would bleed through any overlay drawn on top of them. We just
      // pick one or the other for the transcript region.
      if (this.showBackstory && !this.backstory.isBlank()) {
         renderBackstoryPanel(graphics, paneX, paneWidth, transcriptTop, transcriptBottom);
      } else {
         graphics.enableScissor(paneX, transcriptTop, paneX + paneWidth, transcriptBottom);
         renderTranscript(graphics, paneX, paneWidth, transcriptTop, transcriptBottom);
         graphics.disableScissor();
      }

      super.render(graphics, mouseX, mouseY, partialTick);
   }

   private void renderTranscript(GuiGraphics graphics, int paneX, int paneWidth,
                                 int transcriptTop, int transcriptBottom) {
      int innerPad = 8;
      int innerWidth = paneWidth - innerPad * 2;
      int bubbleMaxWidth = (innerWidth * MAX_BUBBLE_RATIO / 100);

      // Pre-render each line into wrapped FormattedCharSequence lists; this
      // makes the pre-compute pass O(turns) but we keep the loop short.
      List<RenderedBubble> rendered = new ArrayList<>();
      for (Line l : this.lines) {
         List<FormattedCharSequence> wrapped = this.font.split(
            Component.literal(l.text),
            bubbleMaxWidth - BUBBLE_PAD_X * 2);
         rendered.add(new RenderedBubble(l, wrapped));
      }

      // Compute total stack height (bubbles + gaps).
      int totalHeight = 0;
      for (RenderedBubble rb : rendered) {
         totalHeight += bubbleHeight(rb.wrapped) + BUBBLE_GAP;
      }
      if (totalHeight > 0) totalHeight -= BUBBLE_GAP;

      // Anchor: bottom of transcript, minus scrollOffset. We render the
      // most-recent bubble at the bottom and stack upward.
      int paneHeight = transcriptBottom - transcriptTop - innerPad * 2;
      int maxScroll = Math.max(0, totalHeight - paneHeight);
      if (this.scrollOffset > maxScroll) this.scrollOffset = maxScroll;

      int y = transcriptBottom - innerPad + this.scrollOffset;
      for (int i = rendered.size() - 1; i >= 0; i--) {
         RenderedBubble rb = rendered.get(i);
         int bh = bubbleHeight(rb.wrapped);
         y -= bh;
         if (y + bh < transcriptTop) break; // off the top
         drawBubble(graphics, rb, paneX + innerPad, paneX + paneWidth - innerPad, y, bubbleMaxWidth);
         y -= BUBBLE_GAP;
      }
   }

   private int bubbleHeight(List<FormattedCharSequence> wrapped) {
      return wrapped.size() * (this.font.lineHeight + 2) + BUBBLE_PAD_Y * 2 - 1;
   }

   private void drawBubble(GuiGraphics graphics, RenderedBubble rb,
                           int leftEdge, int rightEdge, int y, int bubbleMaxWidth) {
      // Bubble visual width = max line width + padding, capped at bubbleMaxWidth.
      int maxLineWidth = 0;
      for (FormattedCharSequence seq : rb.wrapped) {
         maxLineWidth = Math.max(maxLineWidth, this.font.width(seq));
      }
      int width = Math.min(bubbleMaxWidth, maxLineWidth + BUBBLE_PAD_X * 2);
      int height = bubbleHeight(rb.wrapped);

      int fg;
      int bg;
      int hi;
      int x0;
      String prefix;
      switch (rb.line.role) {
         case "player" -> {
            fg = PLAYER_FG;
            bg = PLAYER_BG;
            hi = PLAYER_HI;
            x0 = rightEdge - width;
            prefix = "you";
         }
         case "villager" -> {
            fg = VILLAGER_FG;
            bg = VILLAGER_BG;
            hi = VILLAGER_HI;
            x0 = leftEdge;
            prefix = this.villagerName;
         }
         case "error" -> {
            fg = 0xFFB05050;
            bg = 0xFF3A1818;
            hi = 0;
            x0 = leftEdge;
            prefix = null;
         }
         case "pending" -> {
            fg = META_FG;
            bg = 0;
            hi = 0;
            x0 = leftEdge;
            prefix = null;
         }
         default -> {
            fg = META_FG;
            bg = 0;
            hi = 0;
            x0 = leftEdge;
            prefix = null;
         }
      }

      if (bg != 0) {
         drawSoftBubble(graphics, x0, y, x0 + width, y + height, bg, hi);
      }
      if (prefix != null) {
         graphics.drawString(this.font, prefix,
            x0 + BUBBLE_PAD_X, y - this.font.lineHeight - LABEL_GAP, META_FG, true);
      }
      int textY = y + BUBBLE_PAD_Y;
      for (FormattedCharSequence seq : rb.wrapped) {
         graphics.drawString(this.font, seq, x0 + BUBBLE_PAD_X, textY, fg, true);
         textY += this.font.lineHeight + 2;
      }
   }

   /**
    * Draws a bubble pill with 1-pixel corner cuts (fakes rounded corners) and
    * a slightly lighter top edge for a subtle "lit from above" feel.
    */
   private void drawSoftBubble(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                int bg, int hi) {
      // Main body, minus the 4 corner pixels.
      graphics.fill(x1 + 1, y1, x2 - 1, y2, bg);          // centre strip (full vertical)
      graphics.fill(x1, y1 + 1, x1 + 1, y2 - 1, bg);      // left edge (no top/bottom corner)
      graphics.fill(x2 - 1, y1 + 1, x2, y2 - 1, bg);      // right edge

      if (hi != 0) {
         // 1-px highlight on the very top row (skip the cut corners).
         graphics.fill(x1 + 1, y1, x2 - 1, y1 + 1, hi);
      }
   }

   private void renderBackstoryPanel(GuiGraphics graphics, int paneX, int paneWidth,
                                     int transcriptTop, int transcriptBottom) {
      int innerPad = 12;
      int width = paneWidth - innerPad * 2;
      List<FormattedCharSequence> wrapped = this.font.split(
         Component.literal(this.backstory),
         width - 8);

      graphics.drawString(this.font, "Backstory", paneX + innerPad, transcriptTop + 6, META_FG, true);

      int ty = transcriptTop + 22;
      int bottom = transcriptBottom - 18;
      for (FormattedCharSequence seq : wrapped) {
         if (ty + this.font.lineHeight > bottom) break;
         graphics.drawString(this.font, seq, paneX + innerPad, ty, 0xFFE0E0E0, true);
         ty += this.font.lineHeight + 2;
      }

      // Footer hint: press [i] to return to chat.
      graphics.drawString(this.font,
         Component.literal("press [i] to return to chat").withStyle(net.minecraft.ChatFormatting.DARK_GRAY),
         paneX + innerPad, transcriptBottom - 12, 0xFF888888, true);
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      // Intentional no-op. Default behaviour blurs and dims the entire
      // viewport behind the screen, which makes the chat HUD feel like a
      // modal overlay rather than a floating window. We want the world to
      // stay crisp behind the panel — only the panel itself obscures it.
   }

   private record Line(String role, String text) {
   }

   private record RenderedBubble(Line line, List<FormattedCharSequence> wrapped) {
   }
}
