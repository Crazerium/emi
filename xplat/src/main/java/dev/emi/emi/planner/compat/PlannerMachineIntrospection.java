package dev.emi.emi.planner.compat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.emi.emi.api.stack.EmiStack;

public final class PlannerMachineIntrospection {
	private static final int MAX_DEPTH = 3;
	private static final int MAX_OBJECTS = 160;
	private static final int MAX_CONTAINER_VALUES = 32;
	private static final Set<String> INTERESTING_WORDS = Set.of(
		"recipe", "modifier", "ability", "hatch", "parallel", "thread", "overclock", "acceler",
		"auxiliary", "module", "coil", "laser", "tier", "machine", "definition", "pattern", "trait",
		"part", "multi", "temperature", "glass", "casing", "energy"
	);
	private static final Set<String> SAFE_METHODS = Set.of(
		"getDefinition", "getMachineDefinition", "getRecipeModifier", "getRecipeModifiers", "getRecipeTypes",
		"getAbilities", "getTraits", "getPattern", "getTier", "getPartAbility", "getMachineSupplier"
	);
	private static final Set<String> IDENTITY_METHODS = Set.of(
		"getId", "getName", "getRegistryName", "getLocation", "location"
	);

	private PlannerMachineIntrospection() {
	}

	public static Snapshot inspect(EmiStack stack) {
		if (stack == null || stack.isEmpty()) {
			return Snapshot.EMPTY;
		}
		Set<String> tokens = new LinkedHashSet<>();
		List<String> strings = new ArrayList<>();
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Node> queue = new ArrayDeque<>();
		try {
			queue.add(new Node(stack.getKey(), 0));
		} catch (Throwable ignored) {
		}
		int count = 0;
		while (!queue.isEmpty() && count < MAX_OBJECTS) {
			Node node = queue.removeFirst();
			Object value = node.value;
			if (value == null || visited.contains(value)) {
				continue;
			}
			visited.add(value);
			count++;
			collectValue(value, node.depth, tokens, strings, queue);
		}
		return new Snapshot(Set.copyOf(tokens), List.copyOf(strings));
	}

	private static void collectValue(Object value, int depth, Set<String> tokens, List<String> strings, ArrayDeque<Node> queue) {
		Class<?> type = value.getClass();
		addToken(tokens, type.getName());
		addToken(tokens, type.getSimpleName());
		if (value instanceof CharSequence sequence) {
			addString(strings, sequence.toString());
			return;
		}
		if (value instanceof Enum<?> enumeration) {
			addToken(tokens, enumeration.name());
			addString(strings, enumeration.name());
			return;
		}
		if (isRecipeIdentityCarrier(type)) {
			collectRecipeIdentity(value, tokens, strings);
		}
		if (isLeaf(type) || depth >= MAX_DEPTH) {
			return;
		}
		if (type.isArray()) {
			int length = Math.min(Array.getLength(value), MAX_CONTAINER_VALUES);
			for (int i = 0; i < length; i++) {
				queue.addLast(new Node(Array.get(value, i), depth + 1));
			}
			return;
		}
		if (value instanceof Iterable<?> iterable) {
			int seen = 0;
			for (Object element : iterable) {
				if (seen++ >= MAX_CONTAINER_VALUES) {
					break;
				}
				queue.addLast(new Node(element, depth + 1));
			}
		}
		if (value instanceof Map<?, ?> map) {
			int seen = 0;
			try {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (seen++ >= MAX_CONTAINER_VALUES) {
						break;
					}
					queue.addLast(new Node(entry.getKey(), depth + 1));
					queue.addLast(new Node(entry.getValue(), depth + 1));
				}
			} catch (Throwable ignored) {
			}
		}
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			for (Field field : current.getDeclaredFields()) {
				addToken(tokens, field.getName());
				if (Modifier.isStatic(field.getModifiers()) || !isInteresting(field.getName())) {
					continue;
				}
				try {
					field.setAccessible(true);
					queue.addLast(new Node(field.get(value), depth + 1));
				} catch (Throwable ignored) {
				}
			}
			for (Method method : current.getDeclaredMethods()) {
				addToken(tokens, method.getName());
				if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
						|| (!SAFE_METHODS.contains(method.getName()) && !isInteresting(method.getName()))) {
					continue;
				}
				if (!SAFE_METHODS.contains(method.getName())) {
					continue;
				}
				try {
					method.setAccessible(true);
					queue.addLast(new Node(method.invoke(value), depth + 1));
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static boolean isRecipeIdentityCarrier(Class<?> type) {
		String name = normalize(type == null ? "" : type.getName());
		return name.contains("recipetype") || name.contains("recipemap") || name.contains("recipecategory");
	}

	private static void collectRecipeIdentity(Object value, Set<String> tokens, List<String> strings) {
		if (value == null) {
			return;
		}
		try {
			String text = String.valueOf(value);
			addToken(tokens, text);
			addString(strings, text);
		} catch (Throwable ignored) {
		}
		for (Class<?> current = value.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
			for (Field field : current.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || !isIdentityName(field.getName())) {
					continue;
				}
				try {
					field.setAccessible(true);
					addIdentityValue(field.get(value), tokens, strings);
				} catch (Throwable ignored) {
				}
			}
			for (Method method : current.getDeclaredMethods()) {
				if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
						|| !IDENTITY_METHODS.contains(method.getName()) || method.getReturnType() == Void.TYPE) {
					continue;
				}
				try {
					method.setAccessible(true);
					addIdentityValue(method.invoke(value), tokens, strings);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static boolean isIdentityName(String name) {
		String normalized = normalize(name);
		return normalized.equals("id") || normalized.equals("name") || normalized.contains("registry")
			|| normalized.contains("location") || normalized.contains("identifier");
	}

	private static void addIdentityValue(Object value, Set<String> tokens, List<String> strings) {
		if (value == null) {
			return;
		}
		try {
			String text = String.valueOf(value);
			addToken(tokens, text);
			addString(strings, text);
		} catch (Throwable ignored) {
		}
	}

	private static boolean isLeaf(Class<?> type) {
		if (type.isPrimitive() || Number.class.isAssignableFrom(type) || Boolean.class == type || Character.class == type
				|| Class.class == type) {
			return true;
		}
		String name = type.getName();
		return name.startsWith("java.time.") || name.startsWith("java.math.");
	}

	private static boolean isInteresting(String value) {
		String lower = normalize(value);
		for (String word : INTERESTING_WORDS) {
			if (lower.contains(word)) {
				return true;
			}
		}
		return false;
	}

	private static void addToken(Set<String> tokens, String value) {
		String normalized = normalize(value);
		if (!normalized.isBlank()) {
			tokens.add(normalized);
		}
	}

	private static void addString(List<String> strings, String value) {
		if (value == null || value.isBlank() || strings.size() >= 64) {
			return;
		}
		strings.add(value);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
	}

	private record Node(Object value, int depth) {
	}

	public record Snapshot(Set<String> tokens, List<String> strings) {
		private static final Snapshot EMPTY = new Snapshot(Set.of(), List.of());

		public boolean containsAny(String... needles) {
			for (String needle : needles) {
				String normalized = normalize(needle);
				if (normalized.isBlank()) {
					continue;
				}
				for (String token : tokens) {
					if (token.contains(normalized)) {
						return true;
					}
				}
				for (String value : strings) {
					if (normalize(value).contains(normalized)) {
						return true;
					}
				}
			}
			return false;
		}
	}
}
