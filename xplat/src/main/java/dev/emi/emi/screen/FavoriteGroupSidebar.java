package dev.emi.emi.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiCraftingToolCompat;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavoriteGroups;
import dev.emi.emi.runtime.EmiFavoriteGroups.AmountEntry;
import dev.emi.emi.runtime.EmiFavoriteGroups.ChainPlan;
import dev.emi.emi.runtime.EmiFavorite.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public final class FavoriteGroupSidebar {
	private static EmiFavorite pressedFavorite;
	private static EmiFavorite dragFavorite;
	private static int pressedButton = -1;
	private static boolean dragging;
	private static AutoCraftJob autoCraftJob;

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
			tooltip.add(line("SHIFT + C - Craft Items", Formatting.YELLOW));
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
		if (shift && !control && keyCode == GLFW.GLFW_KEY_C) {
			return craftItems(box.group);
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
			long amount = hasReusableOption(entry.ingredient()) ? 1L : entry.amount();
			tooltip.add(line("  " + amount + "x " + ingredientName(entry.ingredient()), color));
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

	private static boolean craftItems(EmiFavoriteGroups.Group group) {
		return startCraft(group, false);
	}

	private static boolean craftMissing(EmiFavoriteGroups.Group group) {
		return startCraft(group, true);
	}

	private static boolean startCraft(EmiFavoriteGroups.Group group, boolean missingOnly) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
			return false;
		}
		List<CraftStep> steps = buildCraftSteps(group);
		if (steps.isEmpty()) {
			return false;
		}
		autoCraftJob = new AutoCraftJob(handled, handled.getScreenHandler().syncId, steps, missingOnly);
		playSound();
		return true;
	}

	public static void tick() {
		AutoCraftJob job = autoCraftJob;
		if (job == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(client.currentScreen instanceof HandledScreen<?> handled) || handled != job.screen
				|| handled.getScreenHandler().syncId != job.syncId || client.player == null) {
			autoCraftJob = null;
			return;
		}
		if (job.waitTicks > 0) {
			job.waitTicks--;
			return;
		}
		if (job.index >= job.steps.size()) {
			autoCraftJob = null;
			return;
		}
		CraftStep step = job.steps.get(job.index);
		long available = available(step.output);
		if (step.target < 0L) {
			step.target = job.missingOnly ? step.amount : safeAdd(available, step.amount);
		}
		if (available >= step.target) {
			advance(job);
			return;
		}
		if (step.phase == 1) {
			Slot output = outputSlot(step.recipe, handled);
			if (output != null && !output.getStack().isEmpty() && matchesOutput(step.output, EmiStack.of(output.getStack()))) {
				long shown = Math.max(1L, output.getStack().getCount());
				if (step.outputPerBatch <= 0L) {
					step.outputPerBatch = step.pendingBatches == 1 ? shown : Math.max(1L, shown / Math.max(1, step.pendingBatches));
				}
				if (takeOutput(step.recipe, handled)) {
					step.phase = 2;
					step.pendingTicks = 0;
					job.waitTicks = 1;
					return;
				}
			}
			step.pendingTicks++;
			if (step.pendingTicks >= 40) {
				autoCraftJob = null;
			}
			return;
		}
		if (step.phase == 2) {
			if (available > step.pendingAvailable) {
				long produced = available - step.pendingAvailable;
				if (step.outputPerBatch <= 0L) {
					step.outputPerBatch = Math.max(1L, produced / Math.max(1, step.pendingBatches));
				}
				step.phase = 0;
				step.pendingTicks = 0;
				step.failures = 0;
				if (available >= step.target) {
					advance(job);
				} else {
					job.waitTicks = 1;
				}
				return;
			}
			step.pendingTicks++;
			if (step.pendingTicks >= 40) {
				autoCraftJob = null;
			}
			return;
		}
		long missing = Math.max(1L, step.target - available);
		long batches = step.outputPerBatch > 0L ? ceilDiv(missing, step.outputPerBatch) : 1L;
		int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, batches));
		if (performFill(step.recipe, handled, amount)) {
			step.phase = 1;
			step.pendingAvailable = available;
			step.pendingBatches = amount;
			step.pendingTicks = 0;
			job.waitTicks = 2;
			return;
		}
		step.failures++;
		if (step.failures >= 3) {
			autoCraftJob = null;
		} else {
			job.waitTicks = 2;
		}
	}

	private static void advance(AutoCraftJob job) {
		job.index++;
		job.waitTicks = 1;
		if (job.index >= job.steps.size()) {
			autoCraftJob = null;
		}
	}

	private static List<CraftStep> buildCraftSteps(EmiFavoriteGroups.Group group) {
		LinkedHashMap<Identifier, EmiRecipe> unique = new LinkedHashMap<>();
		for (EmiFavorite favorite : group.members()) {
			EmiRecipe recipe = favorite.getRecipe();
			if (recipe != null && recipe.getId() != null && favorite.getRole() != Role.ITEM) {
				unique.putIfAbsent(recipe.getId(), recipe);
			}
		}
		if (unique.isEmpty()) {
			return List.of();
		}
		List<EmiRecipe> recipes = new ArrayList<>(unique.values());
		List<EmiRecipe> order = craftingOrder(recipes);
		ChainPlan plan = group.craftingChain ? EmiFavoriteGroups.calculatePlan(group, null) : ChainPlan.EMPTY;
		List<CraftStep> steps = new ArrayList<>();
		for (EmiRecipe recipe : order) {
			if (group.craftingChain && plan.batchesFor(recipe) <= 0L) {
				continue;
			}
			EmiFavorite resultFavorite = resultFavorite(group, recipe);
			EmiIngredient output;
			if (resultFavorite != null && !resultFavorite.isEmpty()) {
				output = normalized(resultFavorite.getStack());
			} else if (!recipe.getOutputs().isEmpty() && recipe.getOutputs().get(0) != null
					&& !recipe.getOutputs().get(0).isEmpty()) {
				output = normalized(recipe.getOutputs().get(0));
			} else {
				continue;
			}
			long amount;
			if (resultFavorite != null) {
				if (group.craftingChain) {
					amount = Math.max(1L, plan.requiredFavorites.getOrDefault(resultFavorite, resultFavorite.getAmount()));
				} else {
					amount = Math.max(1L, resultFavorite.getAmount());
				}
			} else {
				long batches = group.craftingChain ? plan.batchesFor(recipe)
						: safeMultiply(Math.max(1L, group.quantity), EmiFavoriteGroups.recipeQuantity(group, recipe));
				long perBatch = 1L;
				for (EmiStack candidate : recipe.getOutputs()) {
					if (accepts(output, candidate)) {
						perBatch = Math.max(perBatch, candidate.getAmount());
					}
				}
				amount = safeMultiply(perBatch, Math.max(1L, batches));
			}
			if (amount <= 0L) {
				continue;
			}
			steps.add(new CraftStep(recipe, output, amount));
		}
		return steps;
	}

	private static List<EmiRecipe> craftingOrder(List<EmiRecipe> recipes) {
		List<EmiRecipe> result = new ArrayList<>();
		Set<Identifier> visiting = new HashSet<>();
		Set<Identifier> done = new HashSet<>();
		for (EmiRecipe recipe : recipes) {
			visitRecipe(recipe, recipes, result, visiting, done);
		}
		return result;
	}

	private static void visitRecipe(EmiRecipe recipe, List<EmiRecipe> recipes, List<EmiRecipe> result,
			Set<Identifier> visiting, Set<Identifier> done) {
		if (recipe == null || recipe.getId() == null || done.contains(recipe.getId())) {
			return;
		}
		if (!visiting.add(recipe.getId())) {
			return;
		}
		for (EmiIngredient input : recipe.getInputs()) {
			EmiRecipe producer = findProducer(recipes, input, recipe);
			if (producer != null) {
				visitRecipe(producer, recipes, result, visiting, done);
			}
		}
		visiting.remove(recipe.getId());
		if (done.add(recipe.getId())) {
			result.add(recipe);
		}
	}

	private static EmiRecipe findProducer(List<EmiRecipe> recipes, EmiIngredient input, EmiRecipe consumer) {
		if (input == null || input.isEmpty()) {
			return null;
		}
		for (EmiRecipe candidate : recipes) {
			if (candidate == consumer || candidate.getId() == null || consumer.getId() == null
					|| candidate.getId().equals(consumer.getId())) {
				continue;
			}
			for (EmiStack output : candidate.getOutputs()) {
				if (accepts(input, output)) {
					return candidate;
				}
			}
		}
		return null;
	}

	private static boolean accepts(EmiIngredient ingredient, EmiStack stack) {
		if (ingredient == null || stack == null || stack.isEmpty()) {
			return false;
		}
		for (EmiStack option : ingredient.getEmiStacks()) {
			if (option.equals(stack)) {
				return true;
			}
		}
		return false;
	}

	private static EmiFavorite resultFavorite(EmiFavoriteGroups.Group group, EmiRecipe recipe) {
		if (recipe == null || recipe.getId() == null) {
			return null;
		}
		for (EmiFavorite favorite : group.members()) {
			if (favorite.getRole() == Role.RESULT && recipe.getId().equals(favorite.getRecipeId())) {
				return favorite;
			}
		}
		return null;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean performFill(EmiRecipe recipe, HandledScreen<?> handled, int amount) {
		return EmiRecipeFiller.performFill(recipe, (HandledScreen) handled, EmiCraftContext.Type.CRAFTABLE,
				EmiCraftContext.Destination.NONE, amount);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Slot outputSlot(EmiRecipe recipe, HandledScreen<?> handled) {
		var handler = EmiRecipeFiller.getFirstValidHandler(recipe, (HandledScreen) handled);
		if (handler instanceof StandardRecipeHandler standard) {
			Slot output = standard.getOutputSlot(handled.getScreenHandler());
			if (output != null) {
				return output;
			}
		}
		for (Slot slot : handled.getScreenHandler().slots) {
			if (slot instanceof CraftingResultSlot) {
				return slot;
			}
		}
		return null;
	}

	private static boolean takeOutput(EmiRecipe recipe, HandledScreen<?> handled) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.interactionManager == null) {
			return false;
		}
		Slot output = outputSlot(recipe, handled);
		if (output == null || output.getStack().isEmpty()) {
			return false;
		}
		client.interactionManager.clickSlot(handled.getScreenHandler().syncId, output.id, 0, SlotActionType.QUICK_MOVE, client.player);
		return true;
	}

	private static boolean matchesOutput(EmiIngredient expected, EmiStack actual) {
		if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
			return false;
		}
		for (EmiStack option : expected.getEmiStacks()) {
			if (matchesOutput(option, actual)) {
				return true;
			}
		}
		return false;
	}

	private static long available(EmiIngredient ingredient) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || ingredient == null || ingredient.isEmpty()) {
			return 0L;
		}
		long total = 0L;
		for (var itemStack : client.player.getInventory().main) {
			if (itemStack.isEmpty()) {
				continue;
			}
			EmiStack actual = EmiStack.of(itemStack);
			for (EmiStack option : ingredient.getEmiStacks()) {
				if (matchesOutput(option, actual)) {
					total = safeAdd(total, itemStack.getCount());
					break;
				}
			}
		}
		return total;
	}

	private static boolean matchesOutput(EmiStack expected, EmiStack actual) {
		if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
			return false;
		}
		if (EmiCraftingToolCompat.matches(expected, actual)) {
			return true;
		}
		if (!expected.getKey().equals(actual.getKey())) {
			return false;
		}
		var nbt = expected.getNbt();
		return nbt == null || nbt.isEmpty();
	}

	private static EmiIngredient normalized(EmiIngredient ingredient) {
		try {
			return ingredient.copy().setAmount(1).setChance(1);
		} catch (Throwable ignored) {
			return ingredient;
		}
	}

	private static long ceilDiv(long value, long divisor) {
		if (value <= 0L) {
			return 0L;
		}
		return 1L + (value - 1L) / Math.max(1L, divisor);
	}

	private static long safeMultiply(long a, long b) {
		if (a <= 0L || b <= 0L) {
			return 0L;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
	}

	private static long safeAdd(long a, long b) {
		if (b > 0L && a > Long.MAX_VALUE - b) {
			return Long.MAX_VALUE;
		}
		if (b < 0L && a < Long.MIN_VALUE - b) {
			return Long.MIN_VALUE;
		}
		return a + b;
	}

	private static TooltipComponent line(String text, Formatting formatting) {
		return TooltipComponent.of(EmiPort.literal(text).formatted(formatting).asOrderedText());
	}

	private static String ingredientName(EmiIngredient ingredient) {
		if (ingredient == null || ingredient.getEmiStacks().isEmpty()) {
			return "?";
		}
		for (EmiStack stack : ingredient.getEmiStacks()) {
			if (isReusable(stack)) {
				return stack.getName().getString();
			}
		}
		return ingredient.getEmiStacks().get(0).getName().getString();
	}

	private static boolean hasReusableOption(EmiIngredient ingredient) {
		if (ingredient == null) {
			return false;
		}
		for (EmiStack stack : ingredient.getEmiStacks()) {
			if (isReusable(stack)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isReusable(EmiStack stack) {
		return EmiCraftingToolCompat.isReusable(stack);
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

	private static final class AutoCraftJob {
		private final HandledScreen<?> screen;
		private final int syncId;
		private final List<CraftStep> steps;
		private final boolean missingOnly;
		private int index;
		private int waitTicks;

		private AutoCraftJob(HandledScreen<?> screen, int syncId, List<CraftStep> steps, boolean missingOnly) {
			this.screen = screen;
			this.syncId = syncId;
			this.steps = steps;
			this.missingOnly = missingOnly;
		}
	}

	private static final class CraftStep {
		private final EmiRecipe recipe;
		private final EmiIngredient output;
		private final long amount;
		private long outputPerBatch;
		private long target = -1L;
		private long pendingAvailable = -1L;
		private int pendingBatches;
		private int pendingTicks;
		private int failures;
		private int phase;

		private CraftStep(EmiRecipe recipe, EmiIngredient output, long amount) {
			this.recipe = recipe;
			this.output = output;
			this.amount = amount;
		}
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
