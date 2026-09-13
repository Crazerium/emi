package dev.emi.emi.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.item.ItemStack;

public final class EmiCraftingToolCompat {
	private static final String GT_TOOL_INTERFACE = "com.gregtechceu.gtceu.api.item.IGTTool";
	private static final Map<Class<?>, Boolean> GT_TOOL_CACHE = new ConcurrentHashMap<>();

	private EmiCraftingToolCompat() {
	}

	public static boolean isGtTool(EmiStack stack) {
		return stack != null && !stack.isEmpty() && isGtTool(stack.getItemStack());
	}

	public static boolean isGtTool(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return GT_TOOL_CACHE.computeIfAbsent(stack.getItem().getClass(), EmiCraftingToolCompat::implementsGtTool);
	}

	public static boolean isReusable(EmiStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (isGtTool(stack)) {
			return true;
		}
		try {
			return stack.getRemainder().isEqual(stack, Comparison.DEFAULT_COMPARISON);
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static boolean matches(EmiStack expected, EmiStack actual) {
		if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
			return false;
		}
		if (expected.isEqual(actual)) {
			return true;
		}
		ItemStack expectedStack = expected.getItemStack();
		ItemStack actualStack = actual.getItemStack();
		return isGtTool(expectedStack) && isGtTool(actualStack) && expectedStack.getItem() == actualStack.getItem();
	}

	public static boolean matches(ItemStack expected, ItemStack actual) {
		if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
			return false;
		}
		if (ItemStack.canCombine(expected, actual)) {
			return true;
		}
		return isGtTool(expected) && isGtTool(actual) && expected.getItem() == actual.getItem();
	}

	private static boolean implementsGtTool(Class<?> type) {
		if (type == null) {
			return false;
		}
		for (Class<?> iface : type.getInterfaces()) {
			if (GT_TOOL_INTERFACE.equals(iface.getName()) || implementsGtTool(iface)) {
				return true;
			}
		}
		return implementsGtTool(type.getSuperclass());
	}
}
