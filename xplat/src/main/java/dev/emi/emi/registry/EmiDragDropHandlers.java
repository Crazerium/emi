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
