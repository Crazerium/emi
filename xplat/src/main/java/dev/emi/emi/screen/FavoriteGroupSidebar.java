package dev.emi.emi.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavoriteGroups;
import dev.emi.emi.runtime.EmiFavoriteGroups.AmountEntry;
import dev.emi.emi.runtime.EmiFavoriteGroups.ChainPlan;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public final class FavoriteGroupSidebar {
	private static EmiFavorite pressedFavorite;
	private static EmiFavorite dragFavorite;
	private static int pressedButton = -1;
	private static boolean dragging;

	private FavoriteGroupSidebar() {
	}

	public static void render(EmiDrawContext context, int mouseX, int mouseY, float delta) {
		Layout layout = layout();
		if (layout.boxes.isEmpty() && !dragging) {
			return;
		}
		context.push();
		context.matrices().translate(0, 0, 220);
		for (GroupBox box : layout.boxes) {
			renderGroup(context, box, mouseX, mouseY);
		}
		if (dragging && pressedFavorite != null && dragFavorite != null) {
			renderDragPreview(context, layout, mouseX, mouseY);
		}
		context.pop();
	}

	public static void renderTooltip(Screen screen, EmiDrawContext context, int mouseX, int mouseY) {
		GroupBox box = hoveredHandle(layout(), mouseX, mouseY);
		if (box == null) {
			return;
		}
		List<TooltipComponent> tooltip = new ArrayList<>();
		tooltip.add(line("Bookmarks Group", Formatting.WHITE));
		tooltip.add(line("[alt]", Formatting.YELLOW));
		tooltip.add(line("Quantity: x" + box.group.quantity, Formatting.GRAY));
		if (EmiInput.isAltDown()) {
			tooltip.add(line("LMB - Toggle Group Mode", Formatting.YELLOW));
			tooltip.add(line("RMB - Toggle Crafting Chain", Formatting.YELLOW));
			tooltip.add(line("CTRL + L - Send Bookmark Group to Chat", Formatting.YELLOW));
			tooltip.add(line("ALT + LMB - Toggle Collapse/Expand", Formatting.YELLOW));
			tooltip.add(line("SHIFT + A - Remove Group", Formatting.YELLOW));
			tooltip.add(line("LMB + Drag - Create/Include Group", Formatting.YELLOW));
			tooltip.add(line("RMB + Drag - Remove/Exclude Group", Formatting.YELLOW));
			tooltip.add(line("CTRL + Scroll - Change Quantity", Formatting.YELLOW));
			tooltip.add(line("SHIFT + Scroll - Change Quantity", Formatting.YELLOW));
			tooltip.add(line("SHIFT + LMB + Drag - Move Position", Formatting.YELLOW));
			tooltip.add(line("CTRL + SHIFT + C - Craft Missing Items", Formatting.YELLOW));
			tooltip.add(line("CTRL + ALT + Scroll - Change Quantity by 64", Formatting.YELLOW));
		}
		if (box.group.craftingChain) {
			appendChainTooltip(tooltip, box.group);
		}
		EmiRenderHelper.drawTooltip(screen, context, tooltip, mouseX, mouseY);
	}

	private static void renderGroup(EmiDrawContext context, GroupBox box, int mouseX, int mouseY) {
		int color = box.group.craftingChain ? 0xFFFF55FF : box.group.groupMode ? 0xFF00FFFF : 0xFF55FF55;
		Bounds b = box.bracket;
		boolean onRight = box.bracketOnRight;
		int x = onRight ? b.x() : b.right() - 2;
		context.fill(x, b.y(), 2, b.height(), color);
		if (onRight) {
			context.fill(x - 5, b.y(), 7, 2, color);
			context.fill(x - 5, b.bottom() - 2, 7, 2, color);
		} else {
			context.fill(x, b.y(), 7, 2, color);
			context.fill(x, b.bottom() - 2, 7, 2, color);
		}

		Bounds h = box.handle;
		int bg = h.contains(mouseX, mouseY) ? 0xEE252525 : 0xDD101010;
		context.fill(h.x(), h.y(), h.width(), h.height(), bg);
		context.fill(h.x(), h.y(), h.width(), 1, color);
		context.fill(h.x(), h.bottom() - 1, h.width(), 1, color);
		context.fill(h.x(), h.y(), 1, h.height(), color);
		context.fill(h.right() - 1, h.y(), 1, h.height(), color);
		int gx = h.x() + 2;
		int gy = h.y() + 2;
		context.fill(gx, gy, 2, 2, color);
		context.fill(gx + 3, gy, 2, 2, color);
		context.fill(gx, gy + 3, 2, 2, color);
		context.fill(gx + 3, gy + 3, 2, 2, color);
		if (box.group.collapsed) {
			context.fill(h.x() + 2, h.y() + 4, h.width() - 4, 1, 0xFFFFFFFF);
		}
	}

	private static void renderDragPreview(EmiDrawContext context, Layout layout, int mouseX, int mouseY) {
		int start = rawIndex(pressedFavorite);
		int end = rawIndex(dragFavorite);
		if (start < 0 || end < 0) {
			return;
		}
		int min = Math.min(start, end);
		int max = Math.max(start, end);
		int color = pressedButton == 1 ? 0x66FF5555 : EmiInput.isShiftDown() ? 0x66FF55FF : 0x6600FFFF;
		for (VisibleSlot slot : layout.slots) {
			int raw = rawIndex(slot.favorite);
			if (raw >= min && raw <= max) {
				context.fill(slot.bounds.x(), slot.bounds.y(), slot.bounds.width(), slot.bounds.height(), color);
			}
		}
	}

	public static boolean mouseClicked(double mouseX, double mouseY, int button) {
		Layout layout = layout();
		GroupBox box = hoveredHandle(layout, (int) mouseX, (int) mouseY);
		if (box != null) {
			if (button == 0) {
				if (EmiInput.isAltDown()) {
					EmiFavoriteGroups.toggleCollapsed(box.group);
				} else {
					EmiFavoriteGroups.toggleGroupMode(box.group);
				}
				playSound();
				return true;
			}
			if (button == 1) {
				EmiFavoriteGroups.toggleCraftingChain(box.group);
				playSound();
				return true;
			}
		}
		VisibleSlot slot = favoriteAt(layout, (int) mouseX, (int) mouseY);
		if (slot != null && (button == 0 || button == 1)) {
			pressedFavorite = slot.favorite;
			dragFavorite = slot.favorite;
			pressedButton = button;
			dragging = false;
		} else {
			clearDrag();
		}
		return false;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (pressedFavorite == null || button != pressedButton || (button != 0 && button != 1)) {
			return false;
		}
		VisibleSlot slot = favoriteAt(layout(), (int) mouseX, (int) mouseY);
		if (slot != null) {
			dragFavorite = slot.favorite;
			if (dragFavorite != pressedFavorite) {
				dragging = true;
			}
		}
		return dragging;
	}

	public static boolean mouseReleased(double mouseX, double mouseY, int button) {
		try {
			if (!dragging || pressedFavorite == null || dragFavorite == null || button != pressedButton) {
				return false;
			}
			int start = rawIndex(pressedFavorite);
			int end = rawIndex(dragFavorite);
			if (start < 0 || end < 0) {
				return true;
			}
			if (button == 0 && EmiInput.isShiftDown()) {
				EmiFavoriteGroups.Group group = EmiFavoriteGroups.groupFor(pressedFavorite);
				if (group != null) {
					int insertion = end > start ? end + 1 : end;
					EmiFavoriteGroups.moveGroup(group, insertion);
				}
			} else if (button == 0) {
				EmiFavoriteGroups.createOrInclude(start, end);
			} else if (button == 1) {
				EmiFavoriteGroups.excludeRange(start, end);
			}
			playSound();
			return true;
		} finally {
			clearDrag();
		}
	}

	public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		boolean control = EmiInput.isControlDown();
		boolean shift = EmiInput.isShiftDown();
		if ((!control && !shift) || amount == 0) {
			return false;
		}
		Layout current = layout();
		VisibleSlot slot = favoriteAt(current, (int) mouseX, (int) mouseY);
		EmiFavoriteGroups.Group group = slot == null ? null : EmiFavoriteGroups.groupFor(slot.favorite);
		int direction = amount > 0 ? 1 : -1;
		boolean stackStep = control && EmiInput.isAltDown();
		if (group != null && slot.favorite.getRecipe() != null) {
			EmiFavoriteGroups.adjustRecipeQuantity(group, slot.favorite.getRecipe(), direction, stackStep);
			return true;
		}
		GroupBox box = groupAtPoint(current, (int) mouseX, (int) mouseY);
		if (group == null && box != null) {
			group = box.group;
		}
		if (group == null) {
			return false;
		}
		EmiFavoriteGroups.adjustQuantity(group, direction, stackStep);
		return true;
	}

	public static boolean keyPressed(int mouseX, int mouseY, int keyCode, int modifiers) {
		GroupBox box = groupAtPoint(layout(), mouseX, mouseY);
		if (box == null) {
			return false;
		}
		boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
		boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
		if (shift && keyCode == GLFW.GLFW_KEY_A) {
			EmiFavoriteGroups.removeGroup(box.group);
			return true;
		}
		if (control && keyCode == GLFW.GLFW_KEY_L) {
			sendGroupToChat(box.group);
			return true;
		}
		if (control && shift && keyCode == GLFW.GLFW_KEY_C) {
			return craftMissing(box.group);
		}
		return false;
	}

	private static void appendChainTooltip(List<TooltipComponent> tooltip, EmiFavoriteGroups.Group group) {
		ChainPlan plan = EmiFavoriteGroups.calculatePlan(group, EmiScreenManager.lastPlayerInventory);
		tooltip.add(line("", Formatting.GRAY));
		tooltip.add(line("Crafting Chain", Formatting.WHITE));
		appendAmounts(tooltip, "Results:", plan.results, Formatting.GREEN);
		appendAmounts(tooltip, "Ingredients:", plan.ingredients, Formatting.GRAY);
		if (!plan.missing.isEmpty()) {
			appendAmounts(tooltip, "Missing Items:", plan.missing, Formatting.RED);
		}
	}

	private static void appendAmounts(List<TooltipComponent> tooltip, String title, List<AmountEntry> entries, Formatting color) {
		tooltip.add(line(title, Formatting.YELLOW));
		int shown = 0;
		for (AmountEntry entry : entries) {
			if (shown++ >= 8) {
				tooltip.add(line("  ...", Formatting.DARK_GRAY));
				break;
			}
			tooltip.add(line("  " + entry.amount() + "x " + ingredientName(entry.ingredient()), color));
		}
		if (entries.isEmpty()) {
			tooltip.add(line("  -", Formatting.DARK_GRAY));
		}
	}

	private static void sendGroupToChat(EmiFavoriteGroups.Group group) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		ChainPlan plan = EmiFavoriteGroups.calculatePlan(group, null);
		StringBuilder builder = new StringBuilder("Bookmark Group: ");
		if (!plan.results.isEmpty()) {
			for (int i = 0; i < plan.results.size(); i++) {
				if (i > 0) {
					builder.append(", ");
				}
				AmountEntry entry = plan.results.get(i);
				builder.append(entry.amount()).append("x ").append(ingredientName(entry.ingredient()));
			}
		} else {
			builder.append(group.members().size()).append(" bookmarks");
		}
		String message = builder.substring(0, Math.min(240, builder.length()));
		client.player.networkHandler.sendChatMessage(message);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean craftMissing(EmiFavoriteGroups.Group group) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(client.currentScreen instanceof HandledScreen handled)) {
			return false;
		}
		ChainPlan plan = EmiFavoriteGroups.calculatePlan(group, EmiScreenManager.lastPlayerInventory);
		List<EmiFavorite> members = group.members();
		Set<Identifier> attempted = new HashSet<>();
		for (int i = members.size() - 1; i >= 0; i--) {
			EmiFavorite favorite = members.get(i);
			EmiRecipe recipe = favorite.getRecipe();
			if (recipe == null || recipe.getId() == null || recipe.getOutputs().isEmpty() || !attempted.add(recipe.getId())) {
				continue;
			}
			long batches = plan.batchesFor(recipe);
			if (batches <= 0) {
				long desired = plan.requiredFavorites.getOrDefault(favorite, favorite.getAmount());
				long factor = Math.max(1L, group.baseAmount(favorite));
				batches = 1L + Math.max(0L, desired - 1L) / factor;
			}
			int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, batches));
			if (EmiRecipeFiller.performFill(recipe, handled, EmiCraftContext.Type.CRAFTABLE, EmiCraftContext.Destination.NONE, amount)) {
				playSound();
				return true;
			}
		}
		return false;
	}

	private static TooltipComponent line(String text, Formatting formatting) {
		return TooltipComponent.of(EmiPort.literal(text).formatted(formatting).asOrderedText());
	}

	private static String ingredientName(EmiIngredient ingredient) {
		if (ingredient == null || ingredient.getEmiStacks().isEmpty()) {
			return "?";
		}
		return ingredient.getEmiStacks().get(0).getName().getString();
	}

	private static Layout layout() {
		EmiScreenManager.SidebarPanel panel = EmiScreenManager.getPanelFor(SidebarType.FAVORITES);
		if (panel == null || panel.space == null || !panel.isVisible()) {
			return Layout.EMPTY;
		}
		EmiScreenManager.ScreenSpace space = panel.space;
		int pageStart = panel.page * space.pageSize;
		int pageEnd = pageStart + space.pageSize;
		List<GroupBox> boxes = new ArrayList<>();
		List<VisibleSlot> slots = new ArrayList<>();
		List<? extends EmiIngredient> sidebar = space.getStacks();
		for (int global = pageStart; global < Math.min(pageEnd, sidebar.size()); global++) {
			EmiIngredient ingredient = sidebar.get(global);
			if (!(ingredient instanceof EmiFavorite favorite) || favorite.isEmpty() || EmiFavoriteGroups.isSidebarSpacer(favorite)) {
				continue;
			}
			int local = global - pageStart;
			Bounds bounds = new Bounds(space.getRawX(local), space.getRawY(local), EmiScreenManager.ENTRY_SIZE, EmiScreenManager.ENTRY_SIZE);
			slots.add(new VisibleSlot(favorite, global, bounds));
		}
		for (EmiFavoriteGroups.Group group : EmiFavoriteGroups.groups()) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			for (VisibleSlot slot : slots) {
				if (!containsIdentity(group.members(), slot.favorite)) {
					continue;
				}
				minX = Math.min(minX, slot.bounds.x());
				minY = Math.min(minY, slot.bounds.y());
				maxX = Math.max(maxX, slot.bounds.right());
				maxY = Math.max(maxY, slot.bounds.bottom());
			}
			if (minX == Integer.MAX_VALUE) {
				continue;
			}
			int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
			boolean bracketOnRight = panel.side == SidebarSide.LEFT;
			int bx = bracketOnRight ? maxX + 2 : minX - 10;
			if (bx < 0 || bx + 8 > screenWidth) {
				bracketOnRight = !bracketOnRight;
				bx = bracketOnRight ? maxX + 2 : minX - 10;
			}
			bx = Math.max(0, Math.min(screenWidth - 8, bx));
			Bounds area = new Bounds(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
			Bounds bracket = new Bounds(bx, minY, 8, Math.max(8, maxY - minY));
			int handleY = Math.max(bracket.y(), Math.min(bracket.bottom() - 8, bracket.y() + bracket.height() / 2 - 4));
			Bounds handle = new Bounds(bracket.x(), handleY, 8, 8);
			boxes.add(new GroupBox(group, panel, area, bracket, handle, bracketOnRight));
		}
		return new Layout(panel, space, pageStart, List.copyOf(boxes), List.copyOf(slots));
	}

	private static GroupBox hoveredHandle(Layout layout, int mouseX, int mouseY) {
		for (GroupBox box : layout.boxes) {
			if (box.handle.contains(mouseX, mouseY)) {
				return box;
			}
		}
		return null;
	}

	private static GroupBox groupAtPoint(Layout layout, int mouseX, int mouseY) {
		for (GroupBox box : layout.boxes) {
			if (box.handle.contains(mouseX, mouseY) || box.area.contains(mouseX, mouseY)) {
				return box;
			}
		}
		return null;
	}

	private static VisibleSlot favoriteAt(Layout layout, int mouseX, int mouseY) {
		for (VisibleSlot slot : layout.slots) {
			if (slot.bounds.contains(mouseX, mouseY)) {
				return slot;
			}
		}
		return null;
	}

	private static int rawIndex(EmiFavorite favorite) {
		for (int i = 0; i < dev.emi.emi.runtime.EmiFavorites.favorites.size(); i++) {
			if (dev.emi.emi.runtime.EmiFavorites.favorites.get(i) == favorite) {
				return i;
			}
		}
		return -1;
	}

	private static boolean containsIdentity(List<EmiFavorite> list, EmiFavorite target) {
		for (EmiFavorite favorite : list) {
			if (favorite == target) {
				return true;
			}
		}
		return false;
	}

	private static void clearDrag() {
		pressedFavorite = null;
		dragFavorite = null;
		pressedButton = -1;
		dragging = false;
	}

	private static void playSound() {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	private record VisibleSlot(EmiFavorite favorite, int visibleIndex, Bounds bounds) {
	}

	private record GroupBox(EmiFavoriteGroups.Group group, EmiScreenManager.SidebarPanel panel, Bounds area, Bounds bracket,
			Bounds handle, boolean bracketOnRight) {
	}

	private record Layout(EmiScreenManager.SidebarPanel panel, EmiScreenManager.ScreenSpace space, int pageStart,
			List<GroupBox> boxes, List<VisibleSlot> slots) {
		private static final Layout EMPTY = new Layout(null, null, 0, List.of(), List.of());
	}
}
