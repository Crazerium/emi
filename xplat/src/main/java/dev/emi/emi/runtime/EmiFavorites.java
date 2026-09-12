package dev.emi.emi.runtime;

import java.util.AbstractList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ItemEmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.ChanceMaterialCost;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.MaterialNode;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

public class EmiFavorites {
	public static List<EmiFavorite> favorites = Lists.newArrayList();
	public static List<EmiFavorite.Synthetic> syntheticFavorites = Lists.newArrayList();
	public static List<EmiFavorite> favoriteSidebar = new FavoriteSidebarList();

	public static JsonArray save() {
		JsonArray arr = new JsonArray();
		for (EmiFavorite fav : favorites) {
			JsonElement stack = EmiIngredientSerializer.getSerialized(fav.getStack());
			if (stack != null) {
				JsonObject obj = new JsonObject();
				obj.add("stack", stack);
				if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
					obj.addProperty("recipe", fav.getRecipe().getId().toString());
					obj.addProperty("role", fav.getRole().name().toLowerCase(Locale.ROOT));
				}
				arr.add(obj);
			}
		}
		return arr;
	}

	public static void load(JsonArray arr) {
		favorites.clear();
		for (JsonElement el : arr) {
			if (el.isJsonObject()) {
				JsonObject json = el.getAsJsonObject();
				EmiRecipe recipe = null;
				if (JsonHelper.hasString(json, "recipe")) {
					recipe = EmiApi.getRecipeManager().getRecipe(EmiPort.id(JsonHelper.getString(json, "recipe")));
				}
				if (JsonHelper.hasElement(json, "stack")) {
					EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(json.get("stack"));
					if (ingredient.isEmpty()) {
						continue;
					}
					if (ingredient instanceof EmiStack es) {
						ingredient = es.copy();
					}
					EmiFavorite.Role role = recipe == null ? EmiFavorite.Role.ITEM : EmiFavorite.Role.RESULT;
					if (recipe != null && JsonHelper.hasString(json, "role")) {
						try {
							role = EmiFavorite.Role.valueOf(JsonHelper.getString(json, "role").toUpperCase(Locale.ROOT));
						} catch (IllegalArgumentException ignored) {
						}
					}
					favorites.add(new EmiFavorite(ingredient, recipe, role));
				}
			}
		}
		EmiFavoriteGroups.onFavoritesChanged();
	}

	public static boolean canFavorite(EmiIngredient stack, EmiRecipe recipe) {
		stack = EmiIngredientSerializer.getDeserialized(EmiIngredientSerializer.getSerialized(stack));
		if (stack.isEmpty()) {
			return false;
		}
		if (recipe != null) {
			return recipe.getId() != null;
		}
		return true;
	}

	private static int indexOf(EmiIngredient stack) {
		EmiRecipe context = EmiApi.getRecipeContext(stack);
		EmiFavorite.Role role = stack instanceof EmiFavorite favorite ? favorite.getRole() : context == null ? EmiFavorite.Role.ITEM : EmiFavorite.Role.RESULT;
		for (int i = 0; i < favorites.size(); i++) {
			EmiFavorite favorite = favorites.get(i);
			if (favorite.strictEquals(stack) && sameRecipe(favorite.getRecipe(), context) && favorite.getRole() == role) {
				return i;
			}
		}
		return -1;
	}

	public static boolean removeFavorite(EmiIngredient stack) {
		int index = indexOf(stack);
		if (index != -1) {
			favorites.remove(index);
			EmiFavoriteGroups.onFavoritesChanged();
			return true;
		}
		return false;
	}

	public static void addFavorite(EmiIngredient stack) {
		addFavorite(stack, null);
	}

	public static void addFavoriteAt(EmiIngredient stack, int offset) {
		if (stack instanceof EmiFavorite.Synthetic) {
			return;
		}
		if (stack instanceof EmiFavorite.Craftable craftable) {
			stack = craftable.stack;
		}
		EmiFavorite favorite;
		if (stack instanceof EmiFavorite fav) {
			int original = indexOf(stack);
			if (original != -1) {
				if (original < offset) {
					offset--;
				}
				favorites.remove(original);
			}
			favorite = fav;
		} else {
			stack = EmiIngredientSerializer.getDeserialized(EmiIngredientSerializer.getSerialized(stack));
			if (stack.isEmpty()) {
				return;
			}
			for (int i = 0; i < favorites.size(); i++) {
				EmiFavorite fav = favorites.get(i);
				if (fav.getRecipe() == null && fav.strictEquals(stack)) {
					favorites.remove(i--);
				}
			}
			favorite = new EmiFavorite(stack, null, EmiFavorite.Role.ITEM);
		}
		if (offset < 0) {
			offset = 0;
		}
		if (offset >= favorites.size()) {
			favorites.add(favorite);
		} else {
			favorites.add(offset, favorite);
		}
		EmiFavoriteGroups.onFavoritesChanged();
		EmiPersistentData.save();
	}

	public static void addFavorite(EmiIngredient stack, EmiRecipe context) {
		if (stack instanceof EmiFavorite.Synthetic) {
			return;
		}
		if (stack instanceof EmiFavorite.Craftable craftable) {
			stack = craftable.stack;
		}
		if (stack instanceof EmiFavorite f) {
			if (!removeFavorite(f)) {
				favorites.add(f);
			}
		} else {
			stack = EmiIngredientSerializer.getDeserialized(EmiIngredientSerializer.getSerialized(stack));
			if (stack instanceof EmiStack es && context != null && context.getId() != null) {
				es = es.copy();
				if (es instanceof ItemEmiStack ies) {
					ies.getItemStack().setCount(1);
				}
				if (!es.isEmpty()) {
					for (int i = 0; i < favorites.size(); i++) {
						EmiFavorite fav = favorites.get(i);
						if (sameRecipe(fav.getRecipe(), context) && fav.getRole() == EmiFavorite.Role.RESULT && fav.strictEquals(es)) {
							return;
						}
					}
					favorites.add(new EmiFavorite(es, context, EmiFavorite.Role.RESULT));
				}
			} else {
				if (stack.isEmpty()) {
					return;
				}
				for (int i = 0; i < favorites.size(); i++) {
					EmiFavorite fav = favorites.get(i);
					if (fav.getRecipe() == null && fav.strictEquals(stack)) {
						return;
					}
				}
				favorites.add(new EmiFavorite(stack, null, EmiFavorite.Role.ITEM));
			}
		}
		EmiFavoriteGroups.onFavoritesChanged();
		EmiPersistentData.save();
	}

	public static EmiFavorite addRecipeFavorite(EmiIngredient stack, EmiRecipe context, boolean preserveAmount) {
		return addRecipeFavorite(stack, context, EmiFavorite.Role.RESULT, preserveAmount);
	}

	public static EmiFavorite addRecipeFavorite(EmiIngredient stack, EmiRecipe context, EmiFavorite.Role role, boolean preserveAmount) {
		return addRecipeFavoriteInternal(stack, context, role, preserveAmount, true);
	}

	static EmiFavorite addRecipeFavoriteQuiet(EmiIngredient stack, EmiRecipe context, EmiFavorite.Role role, boolean preserveAmount) {
		return addRecipeFavoriteInternal(stack, context, role, preserveAmount, false);
	}

	static void finishFavoriteBatch() {
		EmiFavoriteGroups.onFavoritesChanged();
		EmiPersistentData.save();
	}

	private static EmiFavorite addRecipeFavoriteInternal(EmiIngredient stack, EmiRecipe context, EmiFavorite.Role role,
			boolean preserveAmount, boolean persist) {
		if (context == null || context.getId() == null || stack == null || stack.isEmpty() || role == EmiFavorite.Role.ITEM) {
			return null;
		}
		JsonElement serialized = EmiIngredientSerializer.getSerialized(stack);
		if (serialized == null) {
			return null;
		}
		stack = EmiIngredientSerializer.getDeserialized(serialized);
		if (stack.isEmpty()) {
			return null;
		}
		stack = stack.copy();
		if (!preserveAmount) {
			stack.setAmount(1);
		}
		for (EmiFavorite favorite : favorites) {
			if (sameRecipe(favorite.getRecipe(), context) && favorite.getRole() == role && favorite.strictEquals(stack)) {
				return favorite;
			}
		}
		EmiFavorite favorite = new EmiFavorite(stack, context, role);
		favorites.add(favorite);
		if (persist) {
			finishFavoriteBatch();
		}
		return favorite;
	}

	public static List<EmiFavorite> removeRecipeFavorites(EmiRecipe recipe) {
		List<EmiFavorite> removed = Lists.newArrayList();
		for (int i = favorites.size() - 1; i >= 0; i--) {
			EmiFavorite favorite = favorites.get(i);
			if (favorite.getRecipe() != null && sameRecipe(favorite.getRecipe(), recipe)) {
				removed.add(favorite);
				favorites.remove(i);
			}
		}
		if (!removed.isEmpty()) {
			EmiFavoriteGroups.onFavoritesChanged();
			EmiPersistentData.save();
		}
		return removed;
	}

	private static boolean sameRecipe(EmiRecipe a, EmiRecipe b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null || a.getId() == null || b.getId() == null) {
			return false;
		}
		return a.getId().equals(b.getId());
	}

	public static boolean removeFavorite(EmiFavorite favorite) {
		for (int i = 0; i < favorites.size(); i++) {
			if (favorites.get(i) == favorite) {
				favorites.remove(i);
				EmiFavoriteGroups.onFavoritesChanged();
				EmiPersistentData.save();
				return true;
			}
		}
		return false;
	}

	public static void updateSynthetic(EmiPlayerInventory inv) {
		syntheticFavorites.clear();
		if (BoM.tree != null && BoM.craftingMode) {
			BoM.tree.calculateCost();
			Map<EmiIngredient, FlatMaterialCost> originalCosts = Maps.newHashMap(BoM.tree.cost.costs);
			Map<EmiIngredient, ChanceMaterialCost> chancedCosts = Maps.newHashMap(BoM.tree.cost.chanceCosts);
			Object2LongMap<EmiRecipe> originalBatches = new Object2LongLinkedOpenHashMap<>();
			Object2LongMap<EmiRecipe> originalAmounts = new Object2LongLinkedOpenHashMap<>();
			EmiPlayerInventory emptyInventory = new EmiPlayerInventory(List.of());
			emptyInventory.inventory.clear();
			BoM.tree.calculateProgress(emptyInventory);
			countRecipes(originalBatches, originalAmounts, BoM.tree.goal);
			BoM.tree.calculateProgress(inv);
			Object2LongMap<EmiRecipe> batches = new Object2LongLinkedOpenHashMap<>();
			Object2LongMap<EmiRecipe> amounts = new Object2LongLinkedOpenHashMap<>();
			countRecipes(batches, amounts, BoM.tree.goal);
			boolean hasSomething = false;
			for (Object2LongMap.Entry<EmiRecipe> entry : batches.object2LongEntrySet()) {
				EmiRecipe recipe = entry.getKey();
				long amount = amounts.getOrDefault(recipe, 0);
				long batch = entry.getLongValue();
				if (amount == 0) {
					continue;
				}
				hasSomething = true;
				int state = 0;
				if (inv.canCraft(recipe, batch)) {
					state = 2;
				} else if (inv.canCraft(recipe)) {
					state = 1;
				}
				syntheticFavorites.add(new EmiFavorite.Synthetic(recipe, batch, amount, originalAmounts.getOrDefault(recipe, amount), state));
			}
			if (!hasSomething) {
				BoM.craftingMode = false;
			} else {
				for (FlatMaterialCost cost : BoM.tree.cost.costs.values()) {
					if (cost.amount > 0) {
						syntheticFavorites.add(new EmiFavorite.Synthetic(cost.ingredient, cost.amount, originalCosts.getOrDefault(cost.ingredient, cost).amount));
					}
				}
				for (ChanceMaterialCost cost : BoM.tree.cost.chanceCosts.values()) {
					if (cost.getEffectiveAmount() > 0) {
						long needed = cost.getEffectiveAmount();
						if (chancedCosts.containsKey(cost.ingredient)) {
							ChanceMaterialCost original = chancedCosts.get(cost.ingredient);
							long done = (long) Math.ceil(original.amount * original.chance - cost.amount * cost.chance);
							needed = original.getEffectiveAmount() - done;
						}
						if (needed > 0) {
							syntheticFavorites.add(new EmiFavorite.Synthetic(cost.ingredient, needed, needed));
						}
					}
				}
			}
		}
	}

	public static void countRecipes(Object2LongMap<EmiRecipe> batches, Object2LongMap<EmiRecipe> amounts, MaterialNode node) {
		if (node.recipe instanceof EmiResolutionRecipe recipe) {
			countRecipes(batches, amounts, node.children.get(0));
			return;
		}
		// Include empty costs for proper sorting
		if (node.recipe != null) {
			long amount = node.neededBatches;
			if (batches.containsKey(node.recipe)) {
				// Remove?
				amount += batches.getLong(node.recipe);
				batches.removeLong(node.recipe);
			}
			batches.put(node.recipe, amount);
			amount = node.totalNeeded;
			if (amounts.containsKey(node.recipe)) {
				// Remove?
				amount += amounts.getLong(node.recipe);
				amounts.removeLong(node.recipe);
			}
			amounts.put(node.recipe, amount);
			for (MaterialNode child : node.children) {
				countRecipes(batches, amounts, child);
			}
		}
	}

	private static class FavoriteSidebarList extends AbstractList<EmiFavorite> {
		@Override
		public EmiFavorite get(int index) {
			return EmiFavoriteGroups.sidebarFavorites().get(index);
		}

		@Override
		public int size() {
			return EmiFavoriteGroups.sidebarFavoriteCount();
		}
	}
}
