package dev.emi.emi.registry;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.mixin.accessor.HandledScreenAccessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;

public class EmiDragDropHandlers {
	public static Map<Class<?>, List<EmiDragDropHandler<?>>> fromClass = Maps.newHashMap();
	public static List<EmiDragDropHandler<?>> generic = Lists.newArrayList();

	public static void clear() {
		fromClass.clear();
		generic.clear();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void render(Screen screen, EmiIngredient stack, DrawContext draw, int mouseX, int mouseY, float delta) {
		if (fromClass.containsKey(screen.getClass())) {
			for (EmiDragDropHandler handler : fromClass.get(screen.getClass())) {
				handler.render(screen, stack, draw, mouseX, mouseY, delta);
			}
		}
		for (EmiDragDropHandler handler : generic) {
			handler.render(screen, stack, draw, mouseX, mouseY, delta);
		}
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean dropStacks(Screen screen, List<? extends EmiIngredient> stacks, int x, int y) {
		if (stacks == null || stacks.isEmpty()) {
			return false;
		}
		if (dropStacksToCellWorkbench(screen, stacks, x, y)) {
			return true;
		}
		if (dropStacksToAe2StorageBus(screen, stacks, x, y)) {
			return true;
		}
		if (fromClass.containsKey(screen.getClass())) {
			for (EmiDragDropHandler handler : fromClass.get(screen.getClass())) {
				if (handler.dropStacks(screen, stacks, x, y)) {
					return true;
				}
			}
		}
		for (EmiDragDropHandler handler : generic) {
			if (handler.dropStacks(screen, stacks, x, y)) {
				return true;
			}
		}
		return false;
	}

	private static boolean dropStacksToAe2StorageBus(Screen screen, List<? extends EmiIngredient> stacks, int x, int y) {
		if (!(screen instanceof HandledScreen<?> handled) || !isAe2StorageBus(handled)) {
			return false;
		}
		HandledScreenAccessor accessor = (HandledScreenAccessor) handled;
		int left = accessor.getX();
		int top = accessor.getY();
		List<Slot> targets = Lists.newArrayList();
		for (Slot slot : handled.getScreenHandler().slots) {
			if (isAe2FilterSlotEnabled(slot) && hasAe2FilterSetter(slot)) {
				targets.add(slot);
			}
		}
		if (targets.isEmpty()) {
			return false;
		}
		targets.sort((a, b) -> {
			int yCompare = Integer.compare(a.y, b.y);
			return yCompare != 0 ? yCompare : Integer.compare(a.x, b.x);
		});
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Slot slot : targets) {
			minX = Math.min(minX, slot.x - 1);
			minY = Math.min(minY, slot.y - 1);
			maxX = Math.max(maxX, slot.x + 17);
			maxY = Math.max(maxY, slot.y + 17);
		}
		if (x < left + minX || x >= left + maxX || y < top + minY || y >= top + maxY) {
			return false;
		}

		int target = 0;
		boolean placed = false;
		for (EmiIngredient ingredient : stacks) {
			if (ingredient == null || ingredient.isEmpty() || ingredient.getEmiStacks().isEmpty()) {
				continue;
			}
			ItemStack itemStack = toAe2FilterStack(ingredient);
			if (itemStack.isEmpty()) {
				continue;
			}
			while (target < targets.size() && !targets.get(target).getStack().isEmpty()) {
				target++;
			}
			while (target < targets.size()) {
				Slot slot = targets.get(target++);
				if (setAe2Filter(slot, itemStack)) {
					placed = true;
					break;
				}
			}
			if (target >= targets.size() && !placed) {
				break;
			}
		}
		return placed;
	}

	private static ItemStack toAe2FilterStack(EmiIngredient ingredient) {
		if (ingredient == null || ingredient.isEmpty() || ingredient.getEmiStacks().isEmpty()) {
			return ItemStack.EMPTY;
		}
		var emiStack = ingredient.getEmiStacks().get(0);
		ItemStack itemStack = emiStack.getItemStack();
		if (!itemStack.isEmpty()) {
			itemStack = itemStack.copy();
			itemStack.setCount(1);
			return itemStack;
		}

		Object fluid = emiStack.getKey();
		if (fluid == null) {
			return ItemStack.EMPTY;
		}
		try {
			Class<?> fluidKeyClass = Class.forName("appeng.api.stacks.AEFluidKey");
			Object fluidKey = null;
			Object nbt = emiStack.getNbt();
			for (var method : fluidKeyClass.getMethods()) {
				if (!"of".equals(method.getName())) {
					continue;
				}
				Class<?>[] params = method.getParameterTypes();
				if (params.length == 2 && params[0].isInstance(fluid)
						&& (nbt == null || params[1].isInstance(nbt))) {
					fluidKey = method.invoke(null, fluid, nbt);
					break;
				}
			}
			if (fluidKey == null) {
				for (var method : fluidKeyClass.getMethods()) {
					if (!"of".equals(method.getName())) {
						continue;
					}
					Class<?>[] params = method.getParameterTypes();
					if (params.length == 1 && params[0].isInstance(fluid)) {
						fluidKey = method.invoke(null, fluid);
						break;
					}
				}
			}
			if (fluidKey == null) {
				return ItemStack.EMPTY;
			}

			Class<?> genericStackClass = Class.forName("appeng.api.stacks.GenericStack");
			for (var method : genericStackClass.getMethods()) {
				if (!"wrapInItemStack".equals(method.getName())) {
					continue;
				}
				Class<?>[] params = method.getParameterTypes();
				if (params.length == 2 && params[0].isInstance(fluidKey)
						&& (params[1] == long.class || params[1] == Long.class)) {
					Object wrapped = method.invoke(null, fluidKey, 1L);
					if (wrapped instanceof ItemStack stack) {
						return stack;
					}
				}
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
		return ItemStack.EMPTY;
	}

	private static boolean isAe2StorageBus(HandledScreen<?> screen) {
		Class<?> screenType = screen.getClass();
		while (screenType != null) {
			if ("StorageBusScreen".equals(screenType.getSimpleName())) {
				return true;
			}
			screenType = screenType.getSuperclass();
		}
		Class<?> menuType = screen.getScreenHandler().getClass();
		while (menuType != null) {
			if ("StorageBusMenu".equals(menuType.getSimpleName())) {
				return true;
			}
			menuType = menuType.getSuperclass();
		}
		return false;
	}

	private static boolean isAe2FilterSlotEnabled(Slot slot) {
		try {
			Object enabled = slot.getClass().getMethod("isSlotEnabled").invoke(slot);
			if (enabled instanceof Boolean b) {
				return b;
			}
		} catch (ReflectiveOperationException ignored) {
		}
		return slot.isEnabled();
	}

	private static boolean hasAe2FilterSetter(Slot slot) {
		try {
			slot.getClass().getMethod("setFilterTo", ItemStack.class);
			return true;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static boolean setAe2Filter(Slot slot, ItemStack stack) {
		try {
			try {
				Object allowed = slot.getClass().getMethod("canSetFilterTo", ItemStack.class).invoke(slot, stack);
				if (allowed instanceof Boolean b && !b) {
					return false;
				}
			} catch (NoSuchMethodException ignored) {
			}
			slot.getClass().getMethod("setFilterTo", ItemStack.class).invoke(slot, stack);
			return true;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static boolean dropStacksToCellWorkbench(Screen screen, List<? extends EmiIngredient> stacks, int x, int y) {
		if (!(screen instanceof HandledScreen<?> handled)) {
			return false;
		}
		HandledScreenAccessor accessor = (HandledScreenAccessor) handled;
		int left = accessor.getX();
		int top = accessor.getY();
		List<Slot> targets = Lists.newArrayList();
		for (Slot slot : handled.getScreenHandler().slots) {
			if (slot.isEnabled() && isCellPartitionSlot(slot)) {
				targets.add(slot);
			}
		}
		if (targets.isEmpty()) {
			return false;
		}
		targets.sort((a, b) -> {
			int yCompare = Integer.compare(a.y, b.y);
			return yCompare != 0 ? yCompare : Integer.compare(a.x, b.x);
		});
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Slot slot : targets) {
			minX = Math.min(minX, slot.x);
			minY = Math.min(minY, slot.y);
			maxX = Math.max(maxX, slot.x + 16);
			maxY = Math.max(maxY, slot.y + 16);
		}
		if (x < left + minX || x >= left + maxX || y < top + minY || y >= top + maxY) {
			return false;
		}
		int target = 0;
		boolean placed = false;
		for (EmiIngredient stack : stacks) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			while (target < targets.size() && !targets.get(target).getStack().isEmpty()) {
				target++;
			}
			if (target >= targets.size()) {
				break;
			}
			Slot slot = targets.get(target);
			if (dropStack(screen, stack, left + slot.x + 8, top + slot.y + 8)) {
				target++;
				placed = true;
			}
		}
		return placed;
	}

	private static boolean isCellPartitionSlot(Slot slot) {
		Class<?> type = slot.getClass();
		while (type != null) {
			if ("CellPartitionSlot".equals(type.getSimpleName())) {
				return true;
			}
			type = type.getSuperclass();
		}
		return false;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean dropStack(Screen screen, EmiIngredient stack, int x, int y) {
		if (fromClass.containsKey(screen.getClass())) {
			for (EmiDragDropHandler handler : fromClass.get(screen.getClass())) {
				if (handler.dropStack(screen, stack, x, y)) {
					return true;
				}
			}
		}
		for (EmiDragDropHandler handler : generic) {
			if (handler.dropStack(screen, stack, x, y)) {
				return true;
			}
		}
		return false;
	}
}
