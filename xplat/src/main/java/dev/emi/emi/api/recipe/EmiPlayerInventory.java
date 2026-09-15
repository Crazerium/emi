package dev.emi.emi.api.recipe;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jetbrains.annotations.ApiStatus;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.runtime.EmiCraftingToolCompat;
import dev.emi.emi.runtime.EmiFavorite;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

@SuppressWarnings({"unchecked", "rawtypes"})
public class EmiPlayerInventory {
	private final Comparison none = Comparison.DEFAULT_COMPARISON;
	private final Comparison strict = EmiPort.compareStrict();
	public Map<EmiStack, EmiStack> inventory = Maps.newHashMap();
	
	@Deprecated
	@ApiStatus.Internal
	public EmiPlayerInventory(PlayerEntity entity) {
		HandledScreen<?> screen = EmiApi.getHandledScreen();
		if (screen != null && screen.getScreenHandler() != null) {
			if (screen.getScreenHandler().getCursorStack() != null) {
				addStack(screen.getScreenHandler().getCursorStack());
			}
			List<EmiRecipeHandler<?>> handlers = (List) EmiRecipeFiller.getAllHandlers(screen);
			if (!handlers.isEmpty()) {
				if (handlers.get(0) instanceof StandardRecipeHandler standard) {
					List<Slot> slots = standard.getInputSources(screen.getScreenHandler());
					for (Slot slot : slots) {
						if (slot.canTakeItems(entity)) {
							addStack(slot.getStack());
						}
					}
					return;
				}
			}
		}

		PlayerInventory pInv = entity.getInventory();
		for (int i = 0; i < pInv.main.size(); i++) {
			addStack(pInv.main.get(i));
		}
	}

	public EmiPlayerInventory(List<EmiStack> stacks) {
		for (EmiStack stack : stacks) {
			addStack(stack);
		}
		HandledScreen<?> screen = EmiApi.getHandledScreen();
		if (screen != null && screen.getScreenHandler() != null) {
			if (screen.getScreenHandler().getCursorStack() != null) {
				addStack(screen.getScreenHandler().getCursorStack());
			}
		}
	}

	public static EmiPlayerInventory of(PlayerEntity entity) {
		HandledScreen<?> screen = EmiApi.getHandledScreen();
		if (screen != null) {
			List<EmiRecipeHandler<?>> handlers = (List) EmiRecipeFiller.getAllHandlers(screen);
			if (!handlers.isEmpty()) {
				return handlers.get(0).getInventory((HandledScreen) screen);
			}
		}
		if (entity == null) {
			return new EmiPlayerInventory(List.of());
		}
		return new EmiPlayerInventory(entity);
	}

	private void addStack(ItemStack is) {
		EmiStack stack = EmiStack.of(is).comparison(c -> EmiCraftingToolCompat.isGtTool(is) ? strict : none);
		addStack(stack);
	}

	private void addStack(EmiStack stack) {
		if (!stack.isEmpty()) {
			if (EmiCraftingToolCompat.isGtTool(stack)) {
				stack = stack.copy().comparison(c -> strict);
			}
			inventory.merge(stack, stack, (a, b) -> a.setAmount(a.getAmount() + b.getAmount()));
		}
	}

	public Predicate<EmiRecipe> getPredicate() {
		HandledScreen screen = EmiApi.getHandledScreen();
		List<EmiRecipeHandler> handlers = EmiRecipeFiller.getAllHandlers(screen);
		if (!handlers.isEmpty()) {
			EmiCraftContext context = new EmiCraftContext(screen, this, EmiCraftContext.Type.CRAFTABLE);
			return r -> {
				for (int i = 0; i < handlers.size(); i++) {
					EmiRecipeHandler handler = handlers.get(i);
					if (handler.supportsRecipe(r)) {
						return handler.canCraft(r, context);
					}
				}
				return false;
			};
		}
		return null;
	}

	public List<EmiIngredient> getCraftables() {
		Predicate<EmiRecipe> predicate = getPredicate();
		if (predicate == null) {
			return List.of();
		}
		Set<EmiRecipe> set = Sets.newHashSet();
		for (EmiStack stack : inventory.keySet()) {
			set.addAll(EmiApi.getRecipeManager().getRecipesByInput(stack));
		}
		return set.stream().filter(r -> !r.hideCraftable() && predicate.test(r) && r.getOutputs().size() > 0)
			.map(r -> new EmiFavorite.Craftable(r))
			.sorted((a, b) -> {
				int i = Integer.compare(
					EmiStackList.getIndex(a.getStack()),
					EmiStackList.getIndex(b.getStack()));
				if (i != 0) {
					return i;
				}
				return Long.compare(a.getAmount(), b.getAmount());
			}).collect(Collectors.toList());
	}

	public List<Boolean> getCraftAvailability(EmiRecipe recipe) {
		Object2LongMap<EmiStack> used = new Object2LongOpenHashMap<>();
		List<Boolean> states = Lists.newArrayList();
		outer:
		for (EmiIngredient ingredient : recipe.getInputs()) {
			for (EmiStack stack : ingredient.getEmiStacks()) {
				boolean reusable = EmiCraftingToolCompat.isReusable(stack);
				long desired = reusable ? Math.max(1L, stack.getAmount()) : stack.getAmount();
				if (reusable && EmiCraftingToolCompat.isGtTool(stack)) {
					if (getReusableCraftingUses(stack) >= desired) {
						states.add(true);
						continue outer;
					}
					continue;
				}
				EmiStack identity = findMatching(stack);
				if (identity != null) {
					long alreadyUsed = reusable ? 0L : used.getOrDefault(identity, 0);
					long available = identity.getAmount() - alreadyUsed;
					if (available >= desired) {
						if (!reusable) {
							used.put(identity, desired + alreadyUsed);
						}
						states.add(true);
						continue outer;
					}
				}
			}
			states.add(false);
		}
		return states;
	}

	public boolean canCraft(EmiRecipe recipe) {
		return canCraft(recipe, 1);
	}

	public boolean canCraft(EmiRecipe recipe, long amount) {
		Object2LongMap<EmiStack> used = new Object2LongOpenHashMap<>();
		outer:
		for (EmiIngredient ingredient : recipe.getInputs()) {
			if (ingredient.isEmpty()) {
				continue;
			}
			for (EmiStack stack : ingredient.getEmiStacks()) {
				boolean reusable = EmiCraftingToolCompat.isReusable(stack);
				long perCraft = Math.max(1L, stack.getAmount());
				if (reusable && EmiCraftingToolCompat.isGtTool(stack)) {
					long requiredUses = safeMultiply(perCraft, amount);
					if (getReusableCraftingUses(stack) >= requiredUses) {
						continue outer;
					}
					continue;
				}
				long desired = reusable ? perCraft : safeMultiply(perCraft, amount);
				EmiStack identity = findMatching(stack);
				if (identity != null) {
					long alreadyUsed = reusable ? 0L : used.getOrDefault(identity, 0);
					long available = identity.getAmount() - alreadyUsed;
					if (available >= desired) {
						if (!reusable) {
							used.put(identity, desired + alreadyUsed);
						}
						continue outer;
					}
				}
			}
			return false;
		}
		return true;
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

	private EmiStack findMatching(EmiStack stack) {
		EmiStack identity = inventory.get(stack);
		if (identity != null) {
			return identity;
		}
		if (!EmiCraftingToolCompat.isGtTool(stack)) {
			return null;
		}
		EmiStack best = null;
		long bestUses = -1L;
		for (EmiStack candidate : inventory.values()) {
			if (!EmiCraftingToolCompat.matches(stack, candidate)) {
				continue;
			}
			long uses = EmiCraftingToolCompat.getSafeCraftingUses(candidate);
			if (best == null || uses > bestUses) {
				best = candidate;
				bestUses = uses;
			}
		}
		return best;
	}

	private long getReusableCraftingUses(EmiStack stack) {
		long total = 0L;
		for (EmiStack candidate : inventory.values()) {
			if (!EmiCraftingToolCompat.matches(stack, candidate)) {
				continue;
			}
			long uses = EmiCraftingToolCompat.getSafeCraftingUses(candidate);
			if (uses == Long.MAX_VALUE) {
				return Long.MAX_VALUE;
			}
			if (uses > 0L) {
				if (total > Long.MAX_VALUE - uses) {
					return Long.MAX_VALUE;
				}
				total += uses;
			}
		}
		return total;
	}

	public boolean isEqual(EmiPlayerInventory other) {
		if (other == null) {
			return false;
		}
		Comparison comparison = Comparison.of((a, b) -> {
			return strict.compare(a, b) && a.getAmount() == b.getAmount();
		});
		if (other.inventory.size() != inventory.size()) {
			return false;
		} else {
			for (EmiStack stack : inventory.keySet()) {
				if (!other.inventory.containsKey(stack) || !other.inventory.get(stack).isEqual(stack, comparison)) {
					return false;
				}
			}
		}
		return true;
	}
}
