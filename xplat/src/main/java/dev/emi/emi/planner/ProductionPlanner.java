package dev.emi.emi.planner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.planner.compat.PlannerMachineCompatRegistry;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.planner.compat.PlannerMachineRule;
import dev.emi.emi.planner.compat.PlannerMachineRuntimeOverride;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.runtime.EmiCraftingToolCompat;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiProductionPlannerPersistence;
import dev.emi.emi.runtime.EmiPersistentData;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ProductionPlanner {
	private static final double TICKS_PER_SECOND = 20.0D;
	private static final List<Line> LINES = new ArrayList<>();
	private static final Map<String, MachineRuntimeInfo> MACHINE_RUNTIME_CACHE = new LinkedHashMap<>();
	private static final Map<String, String> PREFERRED_MACHINE_PROFILES = new LinkedHashMap<>();
	private static final int MAX_FAVORITE_CHAIN_RECIPES = 128;
	private static final int UNDO_HISTORY_LIMIT = 50;
	private static final List<JsonObject> UNDO_HISTORY = new ArrayList<>();
	private static int activeIndex = -1;
	private static boolean loaded;
	private static boolean undoHistoryInitialized;
	private static boolean restoringUndo;
	private static int historyActionDepth;
	private static PendingReplacement pendingReplacement;

	private static final List<MachineProfile> BUILTIN_MACHINE_PROFILES = List.of(
		new MachineProfile("generic", "Generic GT", "Any recipe; manual fallback profile", "", 0, true, EmiStack.EMPTY),
		new MachineProfile("chemical_reactor", "Chemical Reactor", "Singleblock Chemical Reactor", "chemical reactor", 1, false, EmiStack.EMPTY),
		new MachineProfile("large_chemical_reactor", "Large Chemical Reactor", "Multiblock Chemical Reactor; exact parallel/coil rules are not modeled yet", "chemical reactor", 0, true, EmiStack.EMPTY),
		new MachineProfile("electrolyzer", "Electrolyzer", "Singleblock Electrolyzer", "electrolyzer", 1, false, EmiStack.EMPTY)
	);

	private static final List<String> STANDARD_COIL_NAMES = List.of(
		"Cupronickel", "Kanthal", "Nichrome", "RTM Alloy", "HSS-G", "Naquadah", "Trinium", "Tritanium"
	);

	private static final int MAX_MACHINE_SETTING_VALUE = 1_000_000_000;

	private ProductionPlanner() {
	}

	public static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		LINES.clear();
		activeIndex = -1;
		PREFERRED_MACHINE_PROFILES.clear();
		JsonObject root = EmiProductionPlannerPersistence.load();
		if (root.has("preferred_machines") && root.get("preferred_machines").isJsonObject()) {
			for (Map.Entry<String, JsonElement> preference : root.getAsJsonObject("preferred_machines").entrySet()) {
				try {
					if (preference.getValue().isJsonPrimitive() && preference.getValue().getAsJsonPrimitive().isString()) {
						String category = preference.getKey().trim();
						String profile = sanitizeMachineProfile(preference.getValue().getAsString());
						if (!category.isEmpty() && !profile.isEmpty()) {
							PREFERRED_MACHINE_PROFILES.put(category, profile);
						}
					}
				} catch (Throwable ignored) {
				}
			}
		}
		if (root.has("lines") && root.get("lines").isJsonArray()) {
			for (JsonElement element : root.getAsJsonArray("lines")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject object = element.getAsJsonObject();
				Line line = new Line();
				if (object.has("standard_voltage_tier")) {
					line.standardVoltageTier = sanitizeStandardVoltageTier(object.get("standard_voltage_tier").getAsInt());
				}
				if (object.has("name")) {
					String name = object.get("name").getAsString().trim();
					line.name = name.isEmpty() ? null : name;
				}
				if (object.has("groups") && object.get("groups").isJsonArray()) {
					for (JsonElement groupElement : object.getAsJsonArray("groups")) {
						if (!groupElement.isJsonObject()) {
							continue;
						}
						try {
							JsonObject groupObject = groupElement.getAsJsonObject();
							int id = groupObject.has("id") ? Math.max(1, groupObject.get("id").getAsInt()) : line.nextGroupId;
							int parentId = groupObject.has("parent") ? Math.max(0, groupObject.get("parent").getAsInt()) : 0;
							String groupName = groupObject.has("name") ? groupObject.get("name").getAsString().trim() : "";
							Group group = new Group(id, parentId, groupName.isEmpty() ? null : groupName);
							group.collapsed = groupObject.has("collapsed") && groupObject.get("collapsed").getAsBoolean();
							if (groupObject.has("links") && groupObject.get("links").isJsonArray()) {
								for (JsonElement linkElement : groupObject.getAsJsonArray("links")) {
									if (!linkElement.isJsonObject()) {
										continue;
									}
									JsonObject linkObject = linkElement.getAsJsonObject();
									if (!linkObject.has("stack")) {
										continue;
									}
									EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(linkObject.get("stack"));
									if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
										LinkMode mode = linkObject.has("mode") ? LinkMode.fromSerialized(linkObject.get("mode").getAsString()) : LinkMode.MATCH;
										group.links.put(normalizeStack(stack), mode);
									}
								}
							}
							line.groups.add(group);
							line.nextGroupId = Math.max(line.nextGroupId, id + 1);
						} catch (Throwable ignored) {
						}
					}
					sanitizeGroups(line);
				}
				if (object.has("root_links") && object.get("root_links").isJsonArray()) {
					for (JsonElement linkElement : object.getAsJsonArray("root_links")) {
						if (!linkElement.isJsonObject()) {
							continue;
						}
						try {
							JsonObject linkObject = linkElement.getAsJsonObject();
							EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(linkObject.get("stack"));
							if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
								LinkMode mode = linkObject.has("mode") ? LinkMode.fromSerialized(linkObject.get("mode").getAsString()) : LinkMode.MATCH;
								if (mode != LinkMode.MATCH) {
									line.rootLinks.put(normalizeStack(stack), mode);
								}
							}
						} catch (Throwable ignored) {
						}
					}
				}
				if (object.has("targets") && object.get("targets").isJsonArray()) {
					for (JsonElement targetElement : object.getAsJsonArray("targets")) {
						if (!targetElement.isJsonObject()) {
							continue;
						}
						try {
							JsonObject targetObject = targetElement.getAsJsonObject();
							if (!targetObject.has("stack")) {
								continue;
							}
							EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(targetObject.get("stack"));
							if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
								double rate = targetObject.has("rate")
									? sanitizeTargetRate(targetObject.get("rate").getAsDouble())
									: defaultTargetRate(stack);
								TargetMode mode = targetObject.has("mode")
									? TargetMode.fromSerialized(targetObject.get("mode").getAsString())
									: TargetMode.OUTPUT;
								line.targets.add(new Target(normalizeStack(stack), rate, mode));
							}
						} catch (Throwable ignored) {
						}
					}
				}
				if (line.targets.isEmpty() && object.has("target")) {
					try {
						EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(object.get("target"));
						if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
							double rate = object.has("target_rate")
								? sanitizeTargetRate(object.get("target_rate").getAsDouble())
								: defaultTargetRate(stack);
							line.targets.add(new Target(normalizeStack(stack), rate, TargetMode.OUTPUT));
						}
					} catch (Throwable ignored) {
					}
				}
				if (object.has("achieved_target_rate")) {
					line.achievedTargetRate = sanitizeAchievedTargetRate(object.get("achieved_target_rate").getAsDouble());
				}
				if (object.has("bottleneck")) {
					line.bottleneckName = object.get("bottleneck").getAsString().trim();
				}
				line.balanceEnabled = object.has("balance_enabled") && object.get("balance_enabled").getAsBoolean();
				if (object.has("entries") && object.get("entries").isJsonArray()) {
					for (JsonElement entryElement : object.getAsJsonArray("entries")) {
						if (!entryElement.isJsonObject()) {
							continue;
						}
						try {
							JsonObject entryObject = entryElement.getAsJsonObject();
							Identifier id = EmiPort.id(entryObject.get("recipe").getAsString());
							double rate = entryObject.has("rate") ? entryObject.get("rate").getAsDouble() : 1.0D;
							boolean automatic = entryObject.has("mode")
								&& "auto".equalsIgnoreCase(entryObject.get("mode").getAsString());
							int machines = entryObject.has("machines") ? entryObject.get("machines").getAsInt() : 1;
							int parallel = entryObject.has("parallel") ? entryObject.get("parallel").getAsInt() : 1;
							double durationOverrideTicks = entryObject.has("duration_ticks")
								? entryObject.get("duration_ticks").getAsDouble()
								: 0.0D;
							double balanceRate = entryObject.has("balance_rate")
								? entryObject.get("balance_rate").getAsDouble()
								: 0.0D;
							String machineProfile = entryObject.has("machine_profile")
								? entryObject.get("machine_profile").getAsString()
								: "generic";
							int voltageTier = entryObject.has("voltage_tier")
								? entryObject.get("voltage_tier").getAsInt()
								: -1;
							OcMode ocMode = entryObject.has("oc_mode")
								? OcMode.fromSerialized(entryObject.get("oc_mode").getAsString())
								: OcMode.STANDARD;
							boolean voltageOverride = entryObject.has("voltage_override")
								? entryObject.get("voltage_override").getAsBoolean()
								: entryObject.has("voltage_tier");
							int coilTier = entryObject.has("coil_tier") ? entryObject.get("coil_tier").getAsInt() : 0;
							boolean machinesFixed = entryObject.has("machines_fixed") && entryObject.get("machines_fixed").getAsBoolean();
							boolean parallelFixed = entryObject.has("parallel_fixed") && entryObject.get("parallel_fixed").getAsBoolean();
							Entry entry = new Entry(id, sanitizeRate(rate), automatic, sanitizeCount(machines),
								sanitizeCount(parallel), sanitizeDurationTicks(durationOverrideTicks), sanitizeBalanceRate(balanceRate),
								sanitizeMachineProfile(machineProfile), voltageTier, voltageOverride, ocMode, sanitizeCoilTier(coilTier),
								machinesFixed, parallelFixed);
							if (entryObject.has("machine_settings") && entryObject.get("machine_settings").isJsonObject()) {
								JsonObject settingsObject = entryObject.getAsJsonObject("machine_settings");
								for (Map.Entry<String, JsonElement> setting : settingsObject.entrySet()) {
									try {
										if (setting.getValue().isJsonPrimitive() && setting.getValue().getAsJsonPrimitive().isNumber()) {
											entry.machineSettings.put(sanitizeMachineSettingKey(setting.getKey()), sanitizeMachineSettingValue(setting.getValue().getAsInt()));
										}
									} catch (Throwable ignored) {
									}
								}
							}
							entry.groupId = entryObject.has("group_id") ? sanitizeGroupId(line, entryObject.get("group_id").getAsInt()) : 0;
							if (!entry.voltageOverride) {
								applyLineStandardVoltage(line, entry);
							}
							line.entries.add(entry);
						} catch (Throwable ignored) {
						}
					}
				}
				if (line.balanceEnabled && line.targets.isEmpty()) {
					line.balanceEnabled = false;
				}
				if (line.balanceEnabled && line.achievedTargetRate <= 0.0D) {
					line.achievedTargetRate = line.getTargetRate();
				}
				line.balanceMessage = line.balanceEnabled
					? (line.hasMachineCapacityShortfall()
						? buildBottleneckMessage(line)
						: PlannerText.tr("status.restored", "Balanced rates restored"))
					: "";
				LINES.add(line);
			}
		}
		if (!LINES.isEmpty()) {
			int requested = root.has("active") ? root.get("active").getAsInt() : 0;
			activeIndex = Math.max(0, Math.min(requested, LINES.size() - 1));
		}
		if (!undoHistoryInitialized && !restoringUndo) {
			initializeUndoHistory(serializeState());
		}
	}

	public static synchronized boolean addRecipe(EmiRecipe recipe) {
		ensureLoaded();
		if (recipe == null || recipe.getId() == null) {
			return false;
		}
		Line line = getOrCreateActiveLine();
		Entry root = addRecipeToLine(line, recipe, true);
		if (root == null) {
			return false;
		}
		Set<Identifier> visited = new HashSet<>();
		visited.add(recipe.getId());
		addFavoritedDependencies(line, recipe, visited);
		invalidateBalance(line);
		save();
		return true;
	}


	public static synchronized boolean beginRecipeReplacement(Line line, Entry entry, EmiStack output) {
		ensureLoaded();
		if (line == null || entry == null || output == null || output.isEmpty()) {
			return false;
		}
		int lineIndex = LINES.indexOf(line);
		if (lineIndex < 0 || !line.entries.contains(entry)) {
			return false;
		}
		pendingReplacement = new PendingReplacement(lineIndex, entry.recipeId, normalizeStack(output));
		return true;
	}

	public static synchronized boolean hasPendingRecipeReplacement() {
		return pendingReplacement != null;
	}

	public static synchronized boolean canReplacePendingWith(EmiRecipe recipe) {
		ensureLoaded();
		return pendingReplacement != null && recipe != null && recipe.getId() != null
			&& recipeProducesIngredient(recipe, pendingReplacement.output());
	}

	public static synchronized String pendingRecipeReplacementOutputName() {
		if (pendingReplacement == null || pendingReplacement.output() == null || pendingReplacement.output().isEmpty()) {
			return "";
		}
		return pendingReplacement.output().getName().getString();
	}

	public static synchronized void cancelPendingRecipeReplacement() {
		pendingReplacement = null;
	}

	public static synchronized boolean replacePendingRecipe(EmiRecipe recipe) {
		ensureLoaded();
		PendingReplacement request = pendingReplacement;
		if (request == null || recipe == null || recipe.getId() == null || !recipeProducesIngredient(recipe, request.output())) {
			return false;
		}
		if (request.lineIndex() < 0 || request.lineIndex() >= LINES.size()) {
			pendingReplacement = null;
			return false;
		}
		Line line = LINES.get(request.lineIndex());
		Entry source = null;
		for (Entry entry : line.entries) {
			if (entry.recipeId.equals(request.recipeId())) {
				source = entry;
				break;
			}
		}
		if (source == null) {
			pendingReplacement = null;
			return false;
		}
		if (source.recipeId.equals(recipe.getId())) {
			pendingReplacement = null;
			return true;
		}

		int sourceIndex = line.entries.indexOf(source);
		for (int i = line.entries.size() - 1; i >= 0; i--) {
			Entry other = line.entries.get(i);
			if (other != source && other.recipeId.equals(recipe.getId())) {
				line.entries.remove(i);
				if (i < sourceIndex) {
					sourceIndex--;
				}
			}
		}
		Entry replacement = copyEntryForRecipe(source, recipe.getId());
		line.entries.set(sourceIndex, replacement);
		invalidateBalance(line);
		pendingReplacement = null;
		save();
		return true;
	}

	private static Entry copyEntryForRecipe(Entry source, Identifier recipeId) {
		Entry replacement = new Entry(recipeId, source.rate, source.automatic, source.machines, source.parallel,
			0.0D, 0.0D, source.machineProfileId, source.voltageTier,
			source.voltageOverride, source.ocMode, source.coilTier, source.machinesFixed, source.parallelFixed);
		replacement.groupId = source.groupId;

		boolean profileCompatible = false;
		for (MachineProfile profile : getCompatibleMachineProfiles(replacement)) {
			if (profile.id().equals(source.machineProfileId)) {
				profileCompatible = true;
				break;
			}
		}
		if (profileCompatible) {
			for (MachineSettingSpec spec : getMachineSettingSpecs(replacement)) {
				Integer value = source.machineSettings.get(spec.key());
				if (value != null) {
					int sanitized = spec.sanitize(value);
					if (sanitized != spec.defaultValue()) {
						replacement.machineSettings.put(spec.key(), sanitized);
					}
				}
			}
			replacement.parallel = clampParallelForProfile(replacement, replacement.parallel);
			MachineProfile profile = getMachineProfile(replacement);
			if (profile.coilEfficiencyPerTier() <= 0.0D) {
				replacement.coilTier = 0;
			}
			if (replacement.ocMode == OcMode.PERFECT && !profile.allowsPerfectOc()) {
				replacement.ocMode = OcMode.STANDARD;
			}
		} else {
			replacement.machineProfileId = "generic";
			replacement.machineSettings.clear();
			replacement.coilTier = 0;
			if (replacement.ocMode == OcMode.PERFECT) {
				replacement.ocMode = OcMode.STANDARD;
			}
			applyPreferredMachine(replacement);
		}
		replacement.automatic = source.automatic && replacement.getDurationTicks() > 0.0D;
		return replacement;
	}

	private static Entry addRecipeToLine(Line line, EmiRecipe recipe, boolean incrementExisting) {
		if (line == null || recipe == null || recipe.getId() == null) {
			return null;
		}
		for (Entry entry : line.entries) {
			if (entry.recipeId.equals(recipe.getId())) {
				if (incrementExisting) {
					if (entry.automatic && entry.getDurationTicks() > 0.0D) {
						entry.machines = sanitizeCount(entry.machines + 1);
					} else {
						entry.rate = sanitizeRate(entry.rate + 1.0D);
					}
				}
				return entry;
			}
		}
		Entry entry = new Entry(recipe.getId(), 1.0D, false, 1, 1, 0.0D, 0.0D, "generic", -1, false, OcMode.STANDARD, 0, false, false);
		applyLineStandardVoltage(line, entry);
		applyPreferredMachine(entry);
		entry.automatic = entry.getDetectedDurationTicks() > 0.0D;
		line.entries.add(entry);
		return entry;
	}

	private static void addFavoritedDependencies(Line line, EmiRecipe recipe, Set<Identifier> visited) {
		if (line == null || recipe == null || visited.size() >= MAX_FAVORITE_CHAIN_RECIPES) {
			return;
		}
		for (EmiIngredient input : recipe.getInputs()) {
			if (input == null || input.isEmpty() || visited.size() >= MAX_FAVORITE_CHAIN_RECIPES) {
				continue;
			}
			EmiRecipe favoriteRecipe = findFavoritedRecipeFor(input, visited);
			if (favoriteRecipe == null || favoriteRecipe.getId() == null || !visited.add(favoriteRecipe.getId())) {
				continue;
			}
			addRecipeToLine(line, favoriteRecipe, false);
			addFavoritedDependencies(line, favoriteRecipe, visited);
		}
	}

	private static EmiRecipe findFavoritedRecipeFor(EmiIngredient input, Set<Identifier> excluded) {
		if (input == null || input.isEmpty()) {
			return null;
		}

		EmiRecipe defaultRecipe = findDefaultRecipeFor(input, excluded);
		if (defaultRecipe != null) {
			return defaultRecipe;
		}

		int currentPage = EmiFavorites.currentFavoritePage();
		EmiRecipe currentPageRecipe = findFavoritedRecipeFor(input, currentPage, excluded);
		if (currentPageRecipe != null) {
			return currentPageRecipe;
		}
		return findFavoritedRecipeFor(input, -1, excluded);
	}

	private static EmiRecipe findDefaultRecipeFor(EmiIngredient input, Set<Identifier> excluded) {
		try {
			EmiRecipe recipe = BoM.getRecipe(input);
			if (isUsableDependencyRecipe(recipe, input, excluded)) {
				return recipe;
			}
		} catch (Throwable ignored) {
		}
		for (EmiStack option : input.getEmiStacks()) {
			if (option == null || option.isEmpty()) {
				continue;
			}
			try {
				EmiRecipe recipe = BoM.getRecipe(option);
				if (isUsableDependencyRecipe(recipe, input, excluded)) {
					return recipe;
				}
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	private static boolean isUsableDependencyRecipe(EmiRecipe recipe, EmiIngredient input, Set<Identifier> excluded) {
		if (recipe == null || recipe.getId() == null) {
			return false;
		}
		if (excluded != null && excluded.contains(recipe.getId())) {
			return false;
		}
		return recipeProducesIngredient(recipe, input);
	}

	private static EmiRecipe findFavoritedRecipeFor(EmiIngredient input, int page, Set<Identifier> excluded) {
		Map<Identifier, EmiRecipe> resultRecipes = new LinkedHashMap<>();
		Map<Identifier, EmiIngredient> resultStacks = new LinkedHashMap<>();
		for (EmiFavorite favorite : EmiFavorites.favorites) {
			if (favorite == null || favorite.getRecipeId() == null || favorite.getRole() != EmiFavorite.Role.RESULT) {
				continue;
			}
			if (page >= 0 && EmiFavorites.getFavoritePage(favorite) != page) {
				continue;
			}
			Identifier recipeId = favorite.getRecipeId();
			if (excluded != null && excluded.contains(recipeId)) {
				continue;
			}
			EmiRecipe recipe = resolveFavoriteRecipe(favorite);
			if (recipe != null && recipe.getId() != null) {
				resultRecipes.putIfAbsent(recipeId, recipe);
				resultStacks.putIfAbsent(recipeId, favorite.getStack());
			}
		}
		if (resultRecipes.isEmpty()) {
			return null;
		}

		for (Map.Entry<Identifier, EmiIngredient> entry : resultStacks.entrySet()) {
			if (ingredientMatches(input, entry.getValue())) {
				return resultRecipes.get(entry.getKey());
			}
		}

		for (Map.Entry<Identifier, EmiRecipe> entry : resultRecipes.entrySet()) {
			if (recipeProducesIngredient(entry.getValue(), input)) {
				return entry.getValue();
			}
		}

		for (EmiStack option : input.getEmiStacks()) {
			if (option == null || option.isEmpty()) {
				continue;
			}
			try {
				for (EmiRecipe producer : EmiApi.getRecipeManager().getRecipesByOutput(option)) {
					if (producer == null || producer.getId() == null || !resultRecipes.containsKey(producer.getId())) {
						continue;
					}
					return resultRecipes.get(producer.getId());
				}
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	private static EmiRecipe resolveFavoriteRecipe(EmiFavorite favorite) {
		if (favorite == null || favorite.getRecipeId() == null) {
			return null;
		}
		Identifier id = favorite.getRecipeId();
		EmiRecipe recipe = favorite.getRecipe();
		if (recipe != null && id.equals(recipe.getId())) {
			return recipe;
		}
		try {
			recipe = EmiApi.getRecipeManager().getRecipe(id);
			if (recipe != null) {
				return recipe;
			}
			for (EmiRecipe candidate : EmiApi.getRecipeManager().getRecipes()) {
				if (candidate != null && id.equals(candidate.getId())) {
					return candidate;
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static boolean recipeProducesIngredient(EmiRecipe recipe, EmiIngredient input) {
		if (recipe == null || input == null || input.isEmpty()) {
			return false;
		}
		for (EmiStack output : recipe.getOutputs()) {
			if (output != null && !output.isEmpty() && ingredientMatches(input, output)) {
				return true;
			}
		}
		return false;
	}

	private static boolean ingredientMatches(EmiIngredient input, EmiIngredient candidate) {
		if (input == null || candidate == null || input.isEmpty() || candidate.isEmpty()) {
			return false;
		}
		for (EmiStack inputStack : input.getEmiStacks()) {
			if (inputStack == null || inputStack.isEmpty()) {
				continue;
			}
			for (EmiStack candidateStack : candidate.getEmiStacks()) {
				if (candidateStack == null || candidateStack.isEmpty()) {
					continue;
				}
				if (EmiCraftingToolCompat.matches(inputStack, candidateStack)) {
					return true;
				}
				try {
					if (inputStack.getKey().equals(candidateStack.getKey())) {
						return true;
					}
				} catch (Throwable ignored) {
				}
				try {
					if (inputStack.getId() != null && inputStack.getId().equals(candidateStack.getId())) {
						return true;
					}
				} catch (Throwable ignored) {
				}
			}
		}
		return false;
	}

	public static synchronized Line getOrCreateActiveLine() {
		ensureLoaded();
		if (LINES.isEmpty()) {
			LINES.add(new Line());
			activeIndex = 0;
		}
		if (activeIndex < 0 || activeIndex >= LINES.size()) {
			activeIndex = 0;
		}
		return LINES.get(activeIndex);
	}

	public static synchronized Line createLine() {
		ensureLoaded();
		Line line = new Line();
		LINES.add(line);
		activeIndex = LINES.size() - 1;
		save();
		return line;
	}

	public static synchronized void removeActiveLine() {
		ensureLoaded();
		if (activeIndex < 0 || activeIndex >= LINES.size()) {
			return;
		}
		LINES.remove(activeIndex);
		if (LINES.isEmpty()) {
			activeIndex = -1;
		} else {
			activeIndex = Math.min(activeIndex, LINES.size() - 1);
		}
		save();
	}

	public static synchronized List<Line> lines() {
		ensureLoaded();
		return Collections.unmodifiableList(LINES);
	}

	public static synchronized int getActiveIndex() {
		ensureLoaded();
		return activeIndex;
	}

	public static synchronized void setActiveIndex(int index) {
		ensureLoaded();
		if (index >= 0 && index < LINES.size()) {
			activeIndex = index;
			save();
		}
	}

	public static synchronized String displayName(int index) {
		ensureLoaded();
		if (index < 0 || index >= LINES.size()) {
			return PlannerText.tr("line", "Line");
		}
		String custom = LINES.get(index).name;
		return custom == null || custom.isBlank() ? PlannerText.tr("line", "Line") + " " + (index + 1) : custom;
	}

	public static synchronized void renameLine(int index, String value) {
		ensureLoaded();
		if (index < 0 || index >= LINES.size()) {
			return;
		}
		String name = value == null ? "" : value.trim();
		LINES.get(index).name = name.isEmpty() ? null : name;
		save();
	}

	public static synchronized void removeEntry(Line line, Entry entry) {
		if (line != null && entry != null && line.entries.remove(entry)) {
			invalidateBalance(line);
			save();
		}
	}

	public static synchronized Group createGroup(Line line, Group parent) {
		if (line == null) {
			return null;
		}
		int parentId = parent == null ? 0 : parent.id;
		Group group = new Group(line.nextGroupId++, parentId, null);
		line.groups.add(group);
		invalidateBalance(line);
		save();
		return group;
	}

	public static synchronized void removeGroup(Line line, Group group) {
		if (line == null || group == null || !line.groups.contains(group)) {
			return;
		}
		int parentId = sanitizeGroupId(line, group.parentId);
		for (Entry entry : line.entries) {
			if (entry.groupId == group.id) {
				entry.groupId = parentId;
			}
		}
		for (Group child : line.groups) {
			if (child.parentId == group.id) {
				child.parentId = parentId;
			}
		}
		line.groups.remove(group);
		invalidateBalance(line);
		save();
	}

	public static synchronized void renameGroup(Line line, Group group, String value) {
		if (line == null || group == null || !line.groups.contains(group)) {
			return;
		}
		String name = value == null ? "" : value.trim();
		group.name = name.isEmpty() ? null : name;
		save();
	}

	public static synchronized void setGroupCollapsed(Line line, Group group, boolean collapsed) {
		if (line == null || group == null || !line.groups.contains(group)) {
			return;
		}
		group.collapsed = collapsed;
		save();
	}

	public static synchronized void moveGroup(Line line, Group group, Group parent, int siblingIndex) {
		if (line == null || group == null || !line.groups.contains(group)) {
			return;
		}
		int oldParentId = group.parentId;
		int oldSiblingIndex = 0;
		for (Group candidate : line.groups) {
			if (candidate.parentId != oldParentId) {
				continue;
			}
			if (candidate == group) {
				break;
			}
			oldSiblingIndex++;
		}
		int parentId = parent == null ? 0 : sanitizeGroupId(line, parent.id);
		if (parentId == group.id || isGroupDescendant(line, parentId, group.id)) {
			return;
		}
		if (oldParentId == parentId && oldSiblingIndex < siblingIndex) {
			siblingIndex--;
		}

		line.groups.remove(group);
		group.parentId = parentId;
		if (parent != null) {
			parent.collapsed = false;
		}

		List<Group> siblings = new ArrayList<>();
		for (Group candidate : line.groups) {
			if (candidate.parentId == parentId) {
				siblings.add(candidate);
			}
		}
		int targetSibling = Math.max(0, Math.min(siblingIndex, siblings.size()));
		int insertAt;
		if (siblings.isEmpty()) {
			insertAt = line.groups.size();
		} else if (targetSibling >= siblings.size()) {
			Group lastSibling = siblings.get(siblings.size() - 1);
			insertAt = line.groups.indexOf(lastSibling) + 1;
		} else {
			insertAt = line.groups.indexOf(siblings.get(targetSibling));
		}
		line.groups.add(Math.max(0, Math.min(insertAt, line.groups.size())), group);
		invalidateBalance(line);
		save();
	}

	public static synchronized void moveEntry(Line line, Entry entry, int index) {
		if (line == null || entry == null || !line.entries.contains(entry)) {
			return;
		}
		int oldIndex = line.entries.indexOf(entry);
		int insertAt = Math.max(0, Math.min(index, line.entries.size()));
		line.entries.remove(oldIndex);
		if (oldIndex < insertAt) {
			insertAt--;
		}
		line.entries.add(Math.max(0, Math.min(insertAt, line.entries.size())), entry);
		save();
	}

	public static synchronized void setEntryGroup(Line line, Entry entry, Group group) {
		if (line == null || entry == null || !line.entries.contains(entry)) {
			return;
		}
		entry.groupId = group == null ? 0 : sanitizeGroupId(line, group.id);
		invalidateBalance(line);
		save();
	}

	public static synchronized void setGroupLinkMode(Line line, Group group, EmiStack stack, LinkMode mode) {
		if (line == null || stack == null || stack.isEmpty()) {
			return;
		}
		EmiStack normalized = normalizeStack(stack);
		Map<EmiStack, LinkMode> links = group == null ? line.rootLinks : group.links;
		LinkMode sanitized = mode == null ? LinkMode.MATCH : mode;
		if (sanitized == LinkMode.MATCH) {
			links.remove(normalized);
		} else {
			links.put(normalized, sanitized);
		}
		invalidateBalance(line);
		save();
	}

	public static synchronized List<EmiStack> getGroupLinkCandidates(Line line, Group group) {
		if (line == null) {
			return List.of();
		}
		int groupId = group == null ? 0 : group.id;
		Map<EmiStack, ResourceVector> residual = collectGroupResiduals(line, groupId, line.entries.size(), null, null, null, false);
		List<EmiStack> result = new ArrayList<>();
		for (ResourceVector vector : residual.values()) {
			if (vector.hasInput && vector.hasOutput) {
				result.add(vector.stack);
			}
		}
		return result;
	}

	public static synchronized void setBalanceTarget(Line line, EmiStack stack, double defaultRate) {
		setBalanceTarget(line, stack, defaultRate, TargetMode.OUTPUT);
	}

	public static synchronized void setBalanceTarget(Line line, EmiStack stack, double defaultRate, TargetMode mode) {
		if (line == null || stack == null || stack.isEmpty()) {
			return;
		}
		TargetMode sanitizedMode = mode == null ? TargetMode.OUTPUT : mode;
		EmiStack normalized = normalizeStack(stack);
		for (Target target : line.targets) {
			if (sameStack(target.stack, normalized)) {
				if (target.mode != sanitizedMode) {
					target.mode = sanitizedMode;
					line.balanceEnabled = false;
					line.achievedTargetRate = 0.0D;
					line.bottleneckName = "";
					line.balanceMessage = PlannerText.tr("status.target_changed", "Target changed. Press BALANCE.");
					save();
				}
				return;
			}
		}
		line.targets.add(new Target(normalized, sanitizeTargetRate(defaultRate), sanitizedMode));
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = line.targets.size() == 1
			? PlannerText.tr("status.target_selected", "Target selected. Press BALANCE.")
			: PlannerText.tr("status.target_added", "Target added. Press BALANCE.");
		save();
	}

	public static synchronized void toggleTargetMode(Line line, Target target) {
		if (line == null || target == null || !line.targets.contains(target)) {
			return;
		}
		target.mode = target.mode == TargetMode.INPUT ? TargetMode.OUTPUT : TargetMode.INPUT;
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = PlannerText.tr("status.target_changed", "Target changed. Press BALANCE.");
		save();
	}

	public static synchronized void setTargetRate(Line line, double rate) {
		if (line == null || line.targets.isEmpty()) {
			return;
		}
		setTargetRate(line, line.targets.get(0), rate);
	}

	public static synchronized void setTargetRate(Line line, Target target, double rate) {
		if (line == null || target == null || !line.targets.contains(target)) {
			return;
		}
		target.rate = sanitizeTargetRate(rate);
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = PlannerText.tr("status.target_changed", "Target rate changed. Press BALANCE.");
		save();
	}

	public static synchronized void removeBalanceTarget(Line line, Target target) {
		if (line == null || target == null || !line.targets.remove(target)) {
			return;
		}
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = line.targets.isEmpty() ? "" : PlannerText.tr("status.target_removed", "Target removed. Press BALANCE.");
		for (Entry entry : line.entries) {
			entry.balanceRate = 0.0D;
		}
		save();
	}

	public static synchronized void clearBalanceTarget(Line line) {
		if (line == null) {
			return;
		}
		line.targets.clear();
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = "";
		for (Entry entry : line.entries) {
			entry.balanceRate = 0.0D;
		}
		save();
	}

	public static synchronized void disableBalance(Line line) {
		if (line != null && line.balanceEnabled) {
			line.balanceEnabled = false;
			line.achievedTargetRate = 0.0D;
			line.bottleneckName = "";
			line.balanceMessage = PlannerText.tr("status.disabled", "Balance disabled");
			save();
		}
	}

	public static synchronized BalanceResult balanceLine(Line line) {
		if (line == null || line.targets.isEmpty()) {
			return failBalance(line, PlannerText.tr("status.choose_target", "Choose at least one target output first"));
		}
		if (line.entries.isEmpty()) {
			return failBalance(line, PlannerText.tr("status.no_recipes", "Line has no recipes"));
		}
		int n = line.entries.size();
		for (Entry entry : line.entries) {
			if (entry.getRecipe() == null) {
				return failBalance(line, PlannerText.tr("status.missing_recipe", "A recipe is missing"));
			}
		}

		List<double[]> rows = new ArrayList<>();
		List<Double> rhs = new ArrayList<>();
		List<ResourceVector> internalVectors = new ArrayList<>();
		Map<EmiStack, ResourceVector> rootResources = collectGroupResiduals(line, 0, n, rows, rhs, internalVectors, false);

		List<TargetVector> targetVectors = new ArrayList<>();
		for (Target target : line.targets) {
			ResourceVector vector = rootResources.get(normalizeStack(target.stack));
			boolean available = target.mode == TargetMode.INPUT
				? vector != null && maxNegative(vector.net) > 0.0D
				: vector != null && maxPositive(vector.net) > 0.0D;
			if (!available) {
				String direction = target.mode == TargetMode.INPUT
					? "No recipe in this line consumes input goal outside matched groups"
					: PlannerText.tr("status.target_blocked", "No recipe in this line produces target outside matched groups");
				return failBalance(line, direction + ": " + target.stack.getName().getString());
			}
			targetVectors.add(new TargetVector(target, vector));
		}

		for (TargetVector target : targetVectors) {
			double targetScale = maxAbs(target.vector.net);
			double[] targetRow = new double[n];
			for (int i = 0; i < n; i++) {
				targetRow[i] = target.vector.net[i] / targetScale * 12.0D;
			}
			rows.add(targetRow);
			rhs.add(target.target.signedRate() / targetScale * 12.0D);
		}

		for (ResourceVector vector : rootResources.values()) {
			if (line.hasTarget(vector.stack) || !vector.hasInput || !vector.hasOutput || line.getLinkMode(null, vector.stack) == LinkMode.IGNORE) {
				continue;
			}
			addMatchConstraint(vector, rows, rhs, internalVectors);
		}

		double[][] a = rows.toArray(double[][]::new);
		double[] b = new double[rhs.size()];
		for (int i = 0; i < b.length; i++) {
			b[i] = rhs.get(i);
		}
		double[] objective = new double[n];
		java.util.Arrays.fill(objective, 1.0D);
		LinearBalanceSolver.Result solveResult = LinearBalanceSolver.minimizeEqualities(a, b, objective);
		if (!solveResult.solved()) {
			String message = switch (solveResult.status()) {
				case INFEASIBLE -> PlannerText.tr("status.infeasible", "Production line constraints are infeasible");
				case UNBOUNDED -> PlannerText.tr("status.unbounded", "Production line solver is unbounded");
				default -> PlannerText.tr("status.failed", "Production line solver failed");
			};
			return failBalance(line, message);
		}
		double[] x = solveResult.solution();
		for (int i = 0; i < n; i++) {
			if (x[i] <= 1.0E-12D) {
				continue;
			}
			String constraintError = machineSettingsConstraintError(line.entries.get(i));
			if (!constraintError.isBlank()) {
				return failBalance(line, constraintError);
			}
		}
		TargetVector primaryTarget = targetVectors.get(0);
		double targetNet = primaryTarget.target.flowMagnitude(dot(primaryTarget.vector.net, x));
		if (!Double.isFinite(targetNet) || targetNet <= 0.0D) {
			return failBalance(line, PlannerText.tr("status.positive_flow", "Could not build a positive target flow"));
		}
		double maxResidual = 0.0D;
		for (ResourceVector vector : internalVectors) {
			double denom = Math.max(1.0D, maxAbs(vector.net));
			maxResidual = Math.max(maxResidual, Math.abs(dot(vector.net, x)) / denom);
		}
		for (TargetVector target : targetVectors) {
			double actual = dot(target.vector.net, x);
			double requested = target.target.signedRate();
			double denom = Math.max(1.0D, target.target.rate);
			maxResidual = Math.max(maxResidual, Math.abs(actual - requested) / denom);
		}
		for (int i = 0; i < n; i++) {
			line.entries.get(i).balanceRate = sanitizeBalanceRate(x[i]);
		}

		MachineSizingStatus sizingStatus = autoSizeBalancedLine(line);
		BottleneckPropagation bottleneck = findBottleneckPropagation(line);
		if (bottleneck.limited()) {
			double propagationScale = bottleneck.scale();
			for (Entry entry : line.entries) {
				if (entry.balanceRate > 0.0D) {
					entry.balanceRate = sanitizeBalanceRate(entry.balanceRate * propagationScale);
				}
			}
			line.achievedTargetRate = sanitizeAchievedTargetRate(line.getTargetRate() * propagationScale);
			line.bottleneckName = bottleneck.label();
			targetNet = primaryTarget.target.flowMagnitude(dotBalance(primaryTarget.vector, line));
			maxResidual = computeMaxResidual(internalVectors, line);
			sizingStatus = autoSizeBalancedLine(line);
		} else {
			line.achievedTargetRate = line.getTargetRate();
			line.bottleneckName = "";
		}
		line.balanceEnabled = true;
		line.balanceMessage = buildBalancedMessage(line, maxResidual, sizingStatus);
		save();
		return new BalanceResult(true, line.balanceMessage, targetNet, maxResidual);
	}

	private static Map<EmiStack, ResourceVector> collectGroupResiduals(Line line, int groupId, int n,
			List<double[]> rows, List<Double> rhs, List<ResourceVector> internalVectors, boolean applyCurrentLinks) {
		Map<EmiStack, ResourceVector> collection = new LinkedHashMap<>();
		for (int i = 0; i < line.entries.size(); i++) {
			Entry entry = line.entries.get(i);
			if (entry.groupId != groupId) {
				continue;
			}
			addRecipeResources(collection, entry, i, n);
		}
		for (Group child : line.groups) {
			if (child.parentId != groupId) {
				continue;
			}
			Map<EmiStack, ResourceVector> childResiduals = collectGroupResiduals(line, child.id, n, rows, rhs, internalVectors, true);
			mergeResourceCollections(collection, childResiduals, n);
		}
		if (applyCurrentLinks) {
			Group group = line.getGroup(groupId);
			List<EmiStack> matched = new ArrayList<>();
			for (ResourceVector vector : collection.values()) {
				if (!vector.hasInput || !vector.hasOutput || line.getLinkMode(group, vector.stack) == LinkMode.IGNORE) {
					continue;
				}
				if (rows != null && rhs != null && internalVectors != null) {
					addMatchConstraint(vector, rows, rhs, internalVectors);
				}
				matched.add(vector.stack);
			}
			for (EmiStack stack : matched) {
				collection.remove(stack);
			}
		}
		return collection;
	}

	private static void addRecipeResources(Map<EmiStack, ResourceVector> collection, Entry entry, int index, int n) {
		EmiRecipe recipe = entry == null ? null : entry.getRecipe();
		if (recipe == null) {
			return;
		}
		double outputMultiplier = machineSettingOutputMultiplier(entry);
		for (EmiIngredient ingredient : recipe.getInputs()) {
			if (ingredient == null || ingredient.isEmpty()) {
				continue;
			}
			EmiStack stack = firstStack(ingredient);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			double amount = ingredient.getAmount() * Math.max(0.0D, ingredient.getChance());
			ResourceVector vector = collection.computeIfAbsent(normalizeStack(stack), k -> new ResourceVector(k, n));
			vector.net[index] -= amount;
			vector.hasInput = true;
		}
		for (EmiStack stack : recipe.getOutputs()) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			double amount = stack.getAmount() * Math.max(0.0D, stack.getChance()) * outputMultiplier;
			ResourceVector vector = collection.computeIfAbsent(normalizeStack(stack), k -> new ResourceVector(k, n));
			vector.net[index] += amount;
			vector.hasOutput = true;
		}
	}

	private static void mergeResourceCollections(Map<EmiStack, ResourceVector> target, Map<EmiStack, ResourceVector> source, int n) {
		for (ResourceVector sourceVector : source.values()) {
			ResourceVector targetVector = target.computeIfAbsent(sourceVector.stack, k -> new ResourceVector(k, n));
			for (int i = 0; i < n; i++) {
				targetVector.net[i] += sourceVector.net[i];
			}
			targetVector.hasInput |= sourceVector.hasInput;
			targetVector.hasOutput |= sourceVector.hasOutput;
		}
	}

	private static void addMatchConstraint(ResourceVector vector, List<double[]> rows, List<Double> rhs,
			List<ResourceVector> internalVectors) {
		double scale = maxAbs(vector.net);
		if (scale <= 0.0D) {
			return;
		}
		double[] row = new double[vector.net.length];
		for (int i = 0; i < row.length; i++) {
			row[i] = vector.net[i] / scale;
		}
		rows.add(row);
		rhs.add(0.0D);
		internalVectors.add(vector);
	}

	private static MachineSizingStatus autoSizeBalancedLine(Line line) {
		if (line == null) {
			return new MachineSizingStatus(0, 0, 0);
		}
		int active = 0;
		int unavailable = 0;
		int insufficient = 0;
		for (Entry entry : line.entries) {
			if (entry.balanceRate <= 0.0D) {
				continue;
			}
			active++;
			MachineSizing sizing = autoSizeEntry(entry, entry.balanceRate);
			if (!sizing.available()) {
				unavailable++;
			} else if (!sizing.sufficient()) {
				insufficient++;
			}
		}
		return new MachineSizingStatus(active, unavailable, insufficient);
	}

	private static MachineSizing autoSizeEntry(Entry entry, double craftsPerSecond) {
		if (entry == null) {
			return MachineSizing.unavailable();
		}
		MachineSizing sizing = entry.getMachineSizing(craftsPerSecond);
		if (!sizing.available()) {
			return sizing;
		}
		if (!entry.machinesFixed) {
			entry.machines = sanitizeCount(sizing.machines());
		}
		if (!entry.parallelFixed) {
			entry.parallel = clampParallelForProfile(entry, sanitizeCount(sizing.parallel()));
		}
		return sizing;
	}

	private static String buildBalancedMessage(Line line, double maxResidual, MachineSizingStatus status) {
		if (line != null && line.hasMachineCapacityShortfall()) {
			return buildBottleneckMessage(line);
		}
		String flow = maxResidual < 0.0001D
			? (line != null && line.targets.size() > 1
				? PlannerText.tr("status.balanced_targets", "Balanced to all targets")
				: PlannerText.tr("status.balanced_target", "Balanced to target"))
			: PlannerText.tr("status.residual", "Balanced with residual") + " " + formatSolverNumber(maxResidual);
		if (status.insufficient() > 0) {
			return flow + "; " + PlannerText.tr("status.capacity_shortfall", "machine constraints still report a capacity shortfall");
		}
		if (status.unavailable() > 0) {
			return flow + "; " + PlannerText.tr("status.sizing_unavailable", "some machine sizing unavailable");
		}
		return flow + "; " + PlannerText.tr("status.machines_sized", "machines sized");
	}

	private static String buildBottleneckMessage(Line line) {
		if (line == null) {
			return PlannerText.tr("status.bottleneck", "Machine bottleneck limits line throughput");
		}
		String bottleneck = line.bottleneckName == null || line.bottleneckName.isBlank()
			? PlannerText.tr("status.fixed_setup", "fixed machine setup")
			: line.bottleneckName;
		if (line.targets.size() > 1) {
			double primary = Math.max(0.000000001D, line.getTargetRate());
			double percent = Math.max(0.0D, Math.min(100.0D, line.achievedTargetRate / primary * 100.0D));
			return PlannerText.tr("status.requested", "Requested") + ": " + line.targets.size() + " targets | "
				+ PlannerText.tr("status.achievable", "Achievable") + ": " + formatSolverNumber(percent) + "% | "
				+ PlannerText.tr("status.bottleneck_label", "Bottleneck") + ": " + bottleneck;
		}
		return PlannerText.tr("status.requested", "Requested") + ": " + formatSolverNumber(line.getTargetRate()) + "/s | "
			+ PlannerText.tr("status.achievable", "Achievable") + ": " + formatSolverNumber(line.achievedTargetRate) + "/s | "
			+ PlannerText.tr("status.bottleneck_label", "Bottleneck") + ": " + bottleneck;
	}

	private static BottleneckPropagation findBottleneckPropagation(Line line) {
		if (line == null || line.targets.isEmpty() || line.getTargetRate() <= 0.0D) {
			return BottleneckPropagation.none();
		}
		double scale = 1.0D;
		String label = "";
		for (Entry entry : line.entries) {
			if (entry.balanceRate <= 0.0D) {
				continue;
			}
			MachineSizing sizing = entry.getMachineSizing(entry.balanceRate);
			if (!sizing.available() || entry.balanceRate <= 0.0D) {
				continue;
			}
			double ratio = Math.max(0.0D, Math.min(1.0D, sizing.capacityRate() / entry.balanceRate));
			if (ratio + 1.0E-9D < scale) {
				scale = ratio;
				label = entry.getMachineProfile().displayName();
			}
		}
		return scale < 1.0D - 1.0E-9D
			? new BottleneckPropagation(scale, label)
			: BottleneckPropagation.none();
	}

	private static double dotBalance(ResourceVector vector, Line line) {
		double total = 0.0D;
		int count = Math.min(vector.net.length, line.entries.size());
		for (int i = 0; i < count; i++) {
			total += vector.net[i] * line.entries.get(i).balanceRate;
		}
		return total;
	}

	private static double computeMaxResidual(List<ResourceVector> vectors, Line line) {
		double residual = 0.0D;
		for (ResourceVector vector : vectors) {
			double denom = Math.max(1.0D, maxAbs(vector.net));
			residual = Math.max(residual, Math.abs(dotBalance(vector, line)) / denom);
		}
		return residual;
	}

	private static BalanceResult failBalance(Line line, String message) {
		if (line != null) {
			line.balanceEnabled = false;
			line.achievedTargetRate = 0.0D;
			line.bottleneckName = "";
			line.balanceMessage = message;
		}
		return new BalanceResult(false, message, 0.0D, 0.0D);
	}

	public static synchronized void setRate(Entry entry, double rate) {
		if (entry != null) {
			entry.rate = sanitizeRate(rate);
		}
	}

	public static synchronized boolean setAutomatic(Entry entry, boolean automatic) {
		if (entry == null) {
			return false;
		}
		if (automatic && entry.getDurationTicks() <= 0.0D) {
			return false;
		}
		entry.automatic = automatic;
		return true;
	}

	public static synchronized void setMachines(Entry entry, int machines) {
		if (entry != null) {
			entry.machines = sanitizeCount(machines);
			refreshBalancedSizing(entry);
		}
	}

	public static synchronized void setParallel(Entry entry, int parallel) {
		if (entry != null) {
			entry.parallel = clampParallelForProfile(entry, sanitizeCount(parallel));
			refreshBalancedSizing(entry);
		}
	}

	public static synchronized void setMachinesFixed(Entry entry, boolean fixed) {
		if (entry == null) {
			return;
		}
		entry.machinesFixed = fixed;
		refreshBalancedSizing(entry);
	}

	public static synchronized void setParallelFixed(Entry entry, boolean fixed) {
		if (entry == null) {
			return;
		}
		entry.parallelFixed = fixed;
		refreshBalancedSizing(entry);
	}

	public static synchronized void setVoltageTier(Entry entry, int tier) {
		if (entry == null || entry.getRecipeEUt() <= 0L) {
			return;
		}
		int minimum = Math.max(0, entry.getRecipeTier());
		entry.voltageTier = Math.max(minimum, Math.min(tier, GtVoltageResolver.maxTier()));
		entry.voltageOverride = true;
		refreshBalancedSizing(entry);
	}

	public static synchronized void resetVoltageToLine(Line line, Entry entry) {
		if (line == null || entry == null) {
			return;
		}
		entry.voltageOverride = false;
		applyLineStandardVoltage(line, entry);
		refreshBalancedSizing(entry);
	}

	public static synchronized void setLineStandardVoltage(Line line, int tier) {
		if (line == null) {
			return;
		}
		line.standardVoltageTier = sanitizeStandardVoltageTier(tier);
		for (Entry entry : line.entries) {
			if (!entry.voltageOverride) {
				applyLineStandardVoltage(line, entry);
				refreshBalancedSizing(entry);
			}
		}
	}

	public static synchronized void applyLineStandardVoltageToAll(Line line) {
		if (line == null) {
			return;
		}
		for (Entry entry : line.entries) {
			entry.voltageOverride = false;
			applyLineStandardVoltage(line, entry);
			refreshBalancedSizing(entry);
		}
	}

	private static void applyLineStandardVoltage(Line line, Entry entry) {
		if (line == null || entry == null) {
			return;
		}
		entry.voltageTier = line.standardVoltageTier;
	}

	public static int maxVoltageTier() {
		return GtVoltageResolver.maxTier();
	}

	public static String voltageTierName(int tier) {
		return tier < 0 ? "Recipe minimum" : GtVoltageResolver.name(tier);
	}

	public static int voltageTierColor(int tier) {
		return tier < 0 ? 0xFFC8C8D0 : GtVoltageResolver.color(tier);
	}

	public static synchronized void setOcMode(Entry entry, OcMode mode) {
		if (entry != null && mode != null) {
			MachineProfile profile = getMachineProfile(entry);
			entry.ocMode = mode == OcMode.PERFECT && !profile.allowsPerfectOc() ? OcMode.STANDARD : mode;
			refreshBalancedSizing(entry);
		}
	}

	public static synchronized void cycleOcMode(Entry entry, int direction) {
		if (entry == null || direction == 0) {
			return;
		}
		MachineProfile profile = getMachineProfile(entry);
		if (profile.allowsPerfectOc()) {
			setOcMode(entry, direction > 0 ? entry.ocMode.next() : entry.ocMode.previous());
			return;
		}
		entry.ocMode = entry.ocMode == OcMode.NONE ? OcMode.STANDARD : OcMode.NONE;
		refreshBalancedSizing(entry);
	}

	public static synchronized void setMachineProfile(Entry entry, String profileId) {
		if (entry == null) {
			return;
		}
		MachineProfile profile = findMachineProfile(entry, profileId);
		if (!profile.supports(entry.getRecipe())) {
			profile = BUILTIN_MACHINE_PROFILES.get(0);
		}
		boolean profileChanged = !profile.id().equals(entry.machineProfileId);
		entry.machineProfileId = profile.id();
		if (profileChanged) {
			entry.machineSettings.clear();
		}
		entry.parallel = clampParallelForProfile(entry, entry.parallel);
		if (profile.coilEfficiencyPerTier() <= 0.0D) {
			entry.coilTier = 0;
		} else {
			entry.coilTier = sanitizeCoilTier(entry.coilTier);
		}
		if (entry.ocMode == OcMode.PERFECT && !profile.allowsPerfectOc()) {
			entry.ocMode = OcMode.STANDARD;
		}
		refreshBalancedSizing(entry);
	}

	public static synchronized boolean isPreferredMachine(Entry entry, String profileId) {
		if (entry == null || profileId == null) {
			return false;
		}
		String category = machinePreferenceCategory(entry);
		if (category.isEmpty()) {
			return false;
		}
		return sanitizeMachineProfile(profileId).equals(PREFERRED_MACHINE_PROFILES.get(category));
	}

	public static synchronized void togglePreferredMachine(Entry entry, String profileId) {
		if (entry == null || profileId == null) {
			return;
		}
		String category = machinePreferenceCategory(entry);
		if (category.isEmpty()) {
			return;
		}
		String profile = sanitizeMachineProfile(profileId);
		if (profile.equals(PREFERRED_MACHINE_PROFILES.get(category))) {
			PREFERRED_MACHINE_PROFILES.remove(category);
		} else {
			MachineProfile compatible = findMachineProfile(entry, profile);
			if (!compatible.id().equals(profile)) {
				return;
			}
			PREFERRED_MACHINE_PROFILES.put(category, profile);
			setMachineProfile(entry, profile);
		}
		save();
	}

	private static void applyPreferredMachine(Entry entry) {
		if (entry == null) {
			return;
		}
		String category = machinePreferenceCategory(entry);
		String preferred = PREFERRED_MACHINE_PROFILES.get(category);
		if (preferred == null || preferred.isBlank()) {
			return;
		}
		for (MachineProfile profile : getCompatibleMachineProfiles(entry)) {
			if (profile.id().equals(preferred)) {
				setMachineProfile(entry, profile.id());
				return;
			}
		}
	}

	private static String machinePreferenceCategory(Entry entry) {
		EmiRecipe recipe = entry == null ? null : entry.getRecipe();
		if (recipe == null || recipe.getCategory() == null || recipe.getCategory().getId() == null) {
			return "";
		}
		return recipe.getCategory().getId().toString();
	}

	public static synchronized void cycleMachineProfile(Entry entry, int direction) {
		if (entry == null || direction == 0) {
			return;
		}
		List<MachineProfile> profiles = getCompatibleMachineProfiles(entry);
		if (profiles.isEmpty()) {
			return;
		}
		int current = 0;
		for (int i = 0; i < profiles.size(); i++) {
			if (profiles.get(i).id().equals(entry.machineProfileId)) {
				current = i;
				break;
			}
		}
		int next = Math.floorMod(current + Integer.signum(direction), profiles.size());
		setMachineProfile(entry, profiles.get(next).id());
	}

	public static List<MachineProfile> getCompatibleMachineProfiles(Entry entry) {
		MachineProfile generic = BUILTIN_MACHINE_PROFILES.get(0);
		if (entry == null) {
			return List.of(generic);
		}
		LinkedHashMap<String, MachineProfile> profiles = new LinkedHashMap<>();
		profiles.put(generic.id(), generic);
		EmiRecipe recipe = entry.getRecipe();
		if (recipe != null && recipe.getCategory() != null) {
			try {
				for (EmiIngredient workstation : EmiApi.getRecipeManager().getWorkstations(recipe.getCategory())) {
					for (EmiStack stack : workstation.getEmiStacks()) {
						if (stack == null || stack.isEmpty()) {
							continue;
						}
						MachineProfile profile = machineProfileFromWorkstation(stack);
						if (profile.canRunRecipeTier(entry.getRecipeTier())) {
							profiles.putIfAbsent(profile.id(), profile);
						}
					}
				}
			} catch (Throwable ignored) {
			}
		}
		for (int i = 1; i < BUILTIN_MACHINE_PROFILES.size(); i++) {
			MachineProfile profile = BUILTIN_MACHINE_PROFILES.get(i);
			if (profile.supports(recipe)) {
				profiles.putIfAbsent(profile.id(), profile);
			}
		}
		return List.copyOf(profiles.values());
	}

	public static MachineProfile getMachineProfile(Entry entry) {
		return entry == null ? BUILTIN_MACHINE_PROFILES.get(0) : findMachineProfile(entry, entry.machineProfileId);
	}

	private static MachineProfile findMachineProfile(Entry entry, String profileId) {
		String id = sanitizeMachineProfile(profileId);
		for (MachineProfile profile : getCompatibleMachineProfiles(entry)) {
			if (profile.id().equals(id)) {
				return profile;
			}
		}
		MachineProfile builtin = findBuiltinMachineProfile(id);
		if (builtin != null && builtin.supports(entry == null ? null : entry.getRecipe())) {
			return builtin;
		}
		return BUILTIN_MACHINE_PROFILES.get(0);
	}

	private static MachineProfile findBuiltinMachineProfile(String profileId) {
		String id = sanitizeMachineProfile(profileId);
		for (MachineProfile profile : BUILTIN_MACHINE_PROFILES) {
			if (profile.id().equals(id)) {
				return profile;
			}
		}
		return null;
	}

	private static MachineProfile machineProfileFromWorkstation(EmiStack stack) {
		EmiStack icon = normalizeStack(stack);
		String displayName = stack.getName() == null ? stack.getId().toString() : stack.getName().getString();
		String normalizedName = displayName.trim().toLowerCase(Locale.ROOT);
		String runtimeKey = stack.getId().toString();
		MachineRuntimeInfo runtime;
		synchronized (MACHINE_RUNTIME_CACHE) {
			runtime = MACHINE_RUNTIME_CACHE.computeIfAbsent(runtimeKey, key -> MachineRuntimeResolver.resolve(stack, displayName));
		}
		for (MachineProfile builtin : BUILTIN_MACHINE_PROFILES) {
			if (!"generic".equals(builtin.id()) && builtin.displayName().toLowerCase(Locale.ROOT).equals(normalizedName)) {
				return builtin.withRuntime(displayName, icon, runtime);
			}
		}
		String id = "workstation:" + stack.getId();
		String description = runtime.modeled()
			? "EMI workstation; detected machine properties are applied"
			: "EMI workstation; unknown bonuses use Generic GT math";
		return MachineProfile.runtime(id, displayName, description, icon, runtime);
	}

	private static int configuredMaxParallel(Entry entry) {
		if (entry == null) {
			return 0;
		}
		MachineProfile profile = getMachineProfile(entry);
		return profile.machineRule().configuredMaxParallel(entry, profile.maxParallel());
	}

	private static int clampParallelForProfile(Entry entry, int parallel) {
		int limit = configuredMaxParallel(entry);
		return limit > 0 ? Math.min(parallel, limit) : parallel;
	}

	public static synchronized void setCoilTier(Entry entry, int tier) {
		if (entry == null || entry.getMachineProfile().coilEfficiencyPerTier() <= 0.0D) {
			return;
		}
		entry.coilTier = sanitizeCoilTier(tier);
		refreshBalancedSizing(entry);
	}

	public static synchronized void cycleCoilTier(Entry entry, int direction) {
		if (entry == null || direction == 0 || entry.getMachineProfile().coilEfficiencyPerTier() <= 0.0D) {
			return;
		}
		setCoilTier(entry, entry.coilTier + Integer.signum(direction));
	}

	public static int maxCoilTier() {
		return STANDARD_COIL_NAMES.size() - 1;
	}

	public static String coilTierName(int tier) {
		int safe = sanitizeCoilTier(tier);
		return STANDARD_COIL_NAMES.get(safe);
	}

	public static List<MachineSettingSpec> getMachineSettingSpecs(Entry entry) {
		if (entry == null) {
			return List.of();
		}
		return machineSettingSpecsFor(entry.getMachineProfile());
	}

	public static synchronized void setMachineSetting(Entry entry, MachineSettingSpec spec, int value) {
		if (entry == null || spec == null || !getMachineSettingSpecs(entry).contains(spec)) {
			return;
		}
		int sanitized = spec.sanitize(value);
		if (sanitized == spec.defaultValue()) {
			entry.machineSettings.remove(spec.key());
		} else {
			entry.machineSettings.put(spec.key(), sanitized);
		}
		entry.parallel = clampParallelForProfile(entry, entry.parallel);
		refreshBalancedSizing(entry);
	}

	public static synchronized void cycleMachineSetting(Entry entry, MachineSettingSpec spec, int direction) {
		if (entry == null || spec == null || direction == 0) {
			return;
		}
		int current = entry.getMachineSettingValue(spec);
		int step = Math.max(1, spec.step());
		if (spec.type() == MachineSettingType.TOGGLE) {
			setMachineSetting(entry, spec, current == 0 ? 1 : 0);
		} else {
			setMachineSetting(entry, spec, current + Integer.signum(direction) * step);
		}
	}

	private static List<MachineSettingSpec> machineSettingSpecsFor(MachineProfile profile) {
		if (profile == null) {
			return List.of();
		}
		return List.copyOf(profile.machineRule().settings(profile));
	}

	private static boolean machineSettingsAllowRecipe(Entry entry) {
		return entry != null && entry.getMachineProfile().machineRule().allowsRecipe(entry);
	}

	private static String machineSettingsConstraintError(Entry entry) {
		if (entry == null) {
			return "";
		}
		return entry.getMachineProfile().machineRule().constraintError(entry);
	}

	private static double machineSettingDurationMultiplier(Entry entry) {
		if (entry == null) {
			return 1.0D;
		}
		return entry.getMachineProfile().machineRule().durationMultiplier(entry);
	}

	private static double machineSettingThroughputMultiplier(Entry entry) {
		if (entry == null) {
			return 1.0D;
		}
		double multiplier = entry.getMachineProfile().machineRule().throughputMultiplier(entry);
		if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
			return 1.0D;
		}
		return multiplier;
	}

	private static double machineSettingOutputMultiplier(Entry entry) {
		if (entry == null) {
			return 1.0D;
		}
		double multiplier = entry.getMachineProfile().machineRule().outputMultiplier(entry);
		if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
			return 1.0D;
		}
		return multiplier;
	}

	public static List<String> getMachineSettingDetailLines(Entry entry, MachineSettingSpec spec) {
		if (entry == null || spec == null) {
			return List.of();
		}
		return List.copyOf(entry.getMachineProfile().machineRule().settingDetails(entry, spec));
	}

	public static synchronized void setDurationOverrideSeconds(Entry entry, double seconds) {
		if (entry == null) {
			return;
		}
		if (!Double.isFinite(seconds) || seconds <= 0.0D) {
			entry.durationOverrideTicks = 0.0D;
		} else {
			entry.durationOverrideTicks = sanitizeDurationTicks(seconds * TICKS_PER_SECOND);
		}
		refreshBalancedSizing(entry);
	}

	public static synchronized void clearDurationOverride(Entry entry) {
		if (entry != null) {
			entry.durationOverrideTicks = 0.0D;
			refreshBalancedSizing(entry);
		}
	}

	private static void refreshBalancedSizing(Entry entry) {
		if (entry == null) {
			return;
		}
		for (Line line : LINES) {
			if (line.balanceEnabled && line.entries.contains(entry)) {
				balanceLine(line);
				return;
			}
		}
	}

	public static synchronized void beginHistoryAction() {
		ensureLoaded();
		historyActionDepth++;
	}

	public static synchronized void endHistoryAction() {
		if (historyActionDepth <= 0) {
			historyActionDepth = 0;
			return;
		}
		historyActionDepth--;
		if (historyActionDepth == 0) {
			checkpoint();
		}
	}

	public static synchronized void checkpoint() {
		ensureLoaded();
		JsonObject root = serializeState();
		if (!undoHistoryInitialized) {
			initializeUndoHistory(root);
			EmiProductionPlannerPersistence.save(root);
			return;
		}
		JsonObject latest = UNDO_HISTORY.isEmpty() ? null : UNDO_HISTORY.get(UNDO_HISTORY.size() - 1);
		if (latest == null || !latest.equals(root)) {
			EmiProductionPlannerPersistence.save(root);
			if (historyActionDepth == 0) {
				recordUndoSnapshot(root);
			}
		}
	}

	public static synchronized boolean canUndo() {
		ensureLoaded();
		return undoHistoryInitialized && UNDO_HISTORY.size() > 1;
	}

	public static synchronized boolean undo() {
		ensureLoaded();
		checkpoint();
		if (UNDO_HISTORY.size() <= 1) {
			return false;
		}
		UNDO_HISTORY.remove(UNDO_HISTORY.size() - 1);
		JsonObject previous = UNDO_HISTORY.get(UNDO_HISTORY.size() - 1).deepCopy();
		restoringUndo = true;
		try {
			EmiProductionPlannerPersistence.save(previous);
			loaded = false;
			ensureLoaded();
		} finally {
			restoringUndo = false;
		}
		return true;
	}

	public static synchronized String exportActiveLineJson() {
		ensureLoaded();
		getOrCreateActiveLine();
		JsonObject state = serializeState();
		JsonArray lines = state.getAsJsonArray("lines");
		if (activeIndex < 0 || activeIndex >= lines.size() || !lines.get(activeIndex).isJsonObject()) {
			return "";
		}
		JsonObject wrapper = new JsonObject();
		wrapper.addProperty("format", "emi-production-planner-line");
		wrapper.addProperty("version", 1);
		wrapper.addProperty("display_name", displayName(activeIndex));
		wrapper.add("line", lines.get(activeIndex).getAsJsonObject().deepCopy());
		return EmiPersistentData.GSON.toJson(wrapper);
	}

	public static synchronized String writeActiveLineExportFile(String json) {
		ensureLoaded();
		if (json == null || json.isBlank()) {
			return "";
		}
		try {
			Path directory = EmiAgnos.getConfigDirectory().resolve("emi-production-planner-exports");
			Files.createDirectories(directory);
			String baseName = sanitizeExportFileName(displayName(Math.max(0, activeIndex)));
			Path target = directory.resolve(baseName + ".json");
			int duplicate = 2;
			while (Files.exists(target)) {
				target = directory.resolve(baseName + "-" + duplicate++ + ".json");
			}
			Files.writeString(target, json, StandardCharsets.UTF_8);
			return target.toAbsolutePath().toString();
		} catch (Throwable ignored) {
			return "";
		}
	}


	public static synchronized String writeBuildSummaryExportFile(String text) {
		ensureLoaded();
		if (text == null || text.isBlank()) {
			return "";
		}
		try {
			Path directory = EmiAgnos.getConfigDirectory().resolve("emi-production-planner-exports");
			Files.createDirectories(directory);
			String baseName = sanitizeExportFileName(displayName(Math.max(0, activeIndex))) + "-build-summary";
			Path target = directory.resolve(baseName + ".txt");
			int duplicate = 2;
			while (Files.exists(target)) {
				target = directory.resolve(baseName + "-" + duplicate++ + ".txt");
			}
			Files.writeString(target, text, StandardCharsets.UTF_8);
			return target.toAbsolutePath().toString();
		} catch (Throwable ignored) {
			return "";
		}
	}

	public static synchronized LineTransferResult importLineJson(String json) {
		ensureLoaded();
		if (json == null || json.isBlank()) {
			return new LineTransferResult(false, "Clipboard is empty", -1);
		}
		JsonObject before = serializeState();
		try {
			JsonObject parsed = EmiPersistentData.GSON.fromJson(json, JsonObject.class);
			if (parsed == null) {
				return new LineTransferResult(false, "Clipboard does not contain planner JSON", -1);
			}
			JsonObject imported = extractImportedLine(parsed);
			if (imported == null) {
				return new LineTransferResult(false, "No Production Line found in clipboard JSON", -1);
			}
			JsonObject merged = before.deepCopy();
			JsonArray lines = merged.getAsJsonArray("lines");
			JsonObject lineObject = imported.deepCopy();
			String suggestedName = parsed.has("display_name") && parsed.get("display_name").isJsonPrimitive()
				? parsed.get("display_name").getAsString().trim()
				: "";
			applyUniqueImportedName(lineObject, lines, suggestedName);
			lines.add(lineObject);
			int importedIndex = lines.size() - 1;
			merged.addProperty("active", importedIndex);

			int oldCount = LINES.size();
			EmiProductionPlannerPersistence.save(merged);
			loaded = false;
			pendingReplacement = null;
			ensureLoaded();
			if (LINES.size() != oldCount + 1 || activeIndex != importedIndex) {
				EmiProductionPlannerPersistence.save(before);
				loaded = false;
				ensureLoaded();
				return new LineTransferResult(false, "Imported line could not be loaded", -1);
			}
			if (historyActionDepth == 0) {
				recordUndoSnapshot(serializeState());
			}
			return new LineTransferResult(true, "Imported " + displayName(importedIndex), importedIndex);
		} catch (Throwable ignored) {
			try {
				EmiProductionPlannerPersistence.save(before);
				loaded = false;
				ensureLoaded();
			} catch (Throwable restoreIgnored) {
			}
			return new LineTransferResult(false, "Invalid Production Line JSON", -1);
		}
	}

	private static JsonObject extractImportedLine(JsonObject parsed) {
		if (parsed.has("line") && parsed.get("line").isJsonObject()) {
			return parsed.getAsJsonObject("line");
		}
		if (parsed.has("lines") && parsed.get("lines").isJsonArray()) {
			JsonArray lines = parsed.getAsJsonArray("lines");
			if (lines.size() == 0) {
				return null;
			}
			int index = parsed.has("active") ? parsed.get("active").getAsInt() : 0;
			index = Math.max(0, Math.min(index, lines.size() - 1));
			JsonElement selected = lines.get(index);
			return selected.isJsonObject() ? selected.getAsJsonObject() : null;
		}
		if (parsed.has("entries") && parsed.get("entries").isJsonArray()) {
			return parsed;
		}
		return null;
	}

	private static void applyUniqueImportedName(JsonObject lineObject, JsonArray existingLines, String suggestedName) {
		String requested = lineObject.has("name") ? lineObject.get("name").getAsString().trim() : "";
		if (requested.isEmpty() && suggestedName != null) {
			requested = suggestedName.trim();
		}
		if (requested.isEmpty()) {
			requested = "Imported Line";
		}
		Set<String> used = new HashSet<>();
		for (int i = 0; i < existingLines.size(); i++) {
			JsonElement element = existingLines.get(i);
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			String existingName = object.has("name") ? object.get("name").getAsString().trim() : "";
			if (existingName.isEmpty()) {
				existingName = "Line " + (i + 1);
			}
			used.add(existingName.toLowerCase(Locale.ROOT));
		}
		String candidate = requested;
		int suffix = 2;
		while (used.contains(candidate.toLowerCase(Locale.ROOT))) {
			candidate = requested + " (" + suffix++ + ")";
		}
		lineObject.addProperty("name", candidate);
	}

	private static String sanitizeExportFileName(String value) {
		String sanitized = value == null ? "Production-Line" : value.trim();
		sanitized = sanitized.replaceAll("[\\/:*?\"<>|]", "_");
		sanitized = sanitized.replaceAll("\\s+", " ").trim();
		if (sanitized.isEmpty()) {
			sanitized = "Production-Line";
		}
		return sanitized.length() > 80 ? sanitized.substring(0, 80).trim() : sanitized;
	}

	public record LineTransferResult(boolean success, String message, int lineIndex) {
	}

	public static synchronized void save() {
		ensureLoaded();
		JsonObject root = serializeState();
		EmiProductionPlannerPersistence.save(root);
		if (historyActionDepth == 0) {
			recordUndoSnapshot(root);
		}
	}

	private static void initializeUndoHistory(JsonObject root) {
		if (restoringUndo) {
			return;
		}
		UNDO_HISTORY.clear();
		UNDO_HISTORY.add(root.deepCopy());
		undoHistoryInitialized = true;
	}

	private static void recordUndoSnapshot(JsonObject root) {
		if (restoringUndo) {
			return;
		}
		if (!undoHistoryInitialized) {
			initializeUndoHistory(root);
			return;
		}
		JsonObject snapshot = root.deepCopy();
		if (UNDO_HISTORY.isEmpty()) {
			UNDO_HISTORY.add(snapshot);
			return;
		}
		int lastIndex = UNDO_HISTORY.size() - 1;
		JsonObject latest = UNDO_HISTORY.get(lastIndex);
		if (latest.equals(snapshot)) {
			return;
		}
		if (sameUndoContent(latest, snapshot)) {
			UNDO_HISTORY.set(lastIndex, snapshot);
			return;
		}
		UNDO_HISTORY.add(snapshot);
		while (UNDO_HISTORY.size() > UNDO_HISTORY_LIMIT) {
			UNDO_HISTORY.remove(0);
		}
	}

	private static boolean sameUndoContent(JsonObject a, JsonObject b) {
		JsonObject left = a.deepCopy();
		JsonObject right = b.deepCopy();
		left.remove("active");
		right.remove("active");
		return left.equals(right);
	}

	private static JsonObject serializeState() {
		JsonObject root = new JsonObject();
		JsonArray lines = new JsonArray();
		for (Line line : LINES) {
			JsonObject lineObject = new JsonObject();
			if (line.name != null && !line.name.isBlank()) {
				lineObject.addProperty("name", line.name);
			}
			if (!line.targets.isEmpty()) {
				JsonArray targets = new JsonArray();
				for (Target target : line.targets) {
					JsonObject targetObject = new JsonObject();
					targetObject.add("stack", EmiIngredientSerializer.getSerialized(normalizeStack(target.stack)));
					targetObject.addProperty("rate", target.rate);
					if (target.mode != TargetMode.OUTPUT) {
						targetObject.addProperty("mode", target.mode.serialized());
					}
					targets.add(targetObject);
				}
				lineObject.add("targets", targets);
				Target primary = line.targets.get(0);
				lineObject.add("target", EmiIngredientSerializer.getSerialized(normalizeStack(primary.stack)));
				lineObject.addProperty("target_rate", primary.rate);
			}
			lineObject.addProperty("balance_enabled", line.balanceEnabled);
			if (line.balanceEnabled && line.achievedTargetRate > 0.0D) {
				lineObject.addProperty("achieved_target_rate", line.achievedTargetRate);
			}
			if (line.balanceEnabled && line.bottleneckName != null && !line.bottleneckName.isBlank()) {
				lineObject.addProperty("bottleneck", line.bottleneckName);
			}
			if (line.standardVoltageTier >= 0) {
				lineObject.addProperty("standard_voltage_tier", line.standardVoltageTier);
			}
			if (!line.groups.isEmpty()) {
				JsonArray groups = new JsonArray();
				for (Group group : line.groups) {
					JsonObject groupObject = new JsonObject();
					groupObject.addProperty("id", group.id);
					if (group.parentId > 0) {
						groupObject.addProperty("parent", group.parentId);
					}
					if (group.name != null && !group.name.isBlank()) {
						groupObject.addProperty("name", group.name);
					}
					if (group.collapsed) {
						groupObject.addProperty("collapsed", true);
					}
					JsonArray links = serializeLinks(group.links);
					if (links.size() > 0) {
						groupObject.add("links", links);
					}
					groups.add(groupObject);
				}
				lineObject.add("groups", groups);
			}
			JsonArray rootLinks = serializeLinks(line.rootLinks);
			if (rootLinks.size() > 0) {
				lineObject.add("root_links", rootLinks);
			}
			JsonArray entries = new JsonArray();
			for (Entry entry : line.entries) {
				JsonObject entryObject = new JsonObject();
				entryObject.addProperty("recipe", entry.recipeId.toString());
				entryObject.addProperty("rate", entry.rate);
				entryObject.addProperty("mode", entry.automatic ? "auto" : "manual");
				entryObject.addProperty("machines", entry.machines);
				entryObject.addProperty("parallel", entry.parallel);
				if (entry.groupId > 0) {
					entryObject.addProperty("group_id", entry.groupId);
				}
				if (entry.machinesFixed) {
					entryObject.addProperty("machines_fixed", true);
				}
				if (entry.parallelFixed) {
					entryObject.addProperty("parallel_fixed", true);
				}
				entryObject.addProperty("machine_profile", entry.machineProfileId);
				if (entry.voltageTier >= 0) {
					entryObject.addProperty("voltage_tier", entry.voltageTier);
				}
				entryObject.addProperty("voltage_override", entry.voltageOverride);
				entryObject.addProperty("oc_mode", entry.ocMode.serialized());
				if (entry.coilTier > 0) {
					entryObject.addProperty("coil_tier", entry.coilTier);
				}
				if (!entry.machineSettings.isEmpty()) {
					JsonObject settingsObject = new JsonObject();
					for (Map.Entry<String, Integer> setting : entry.machineSettings.entrySet()) {
						settingsObject.addProperty(setting.getKey(), setting.getValue());
					}
					entryObject.add("machine_settings", settingsObject);
				}
				if (entry.durationOverrideTicks > 0.0D) {
					entryObject.addProperty("duration_ticks", entry.durationOverrideTicks);
				}
				if (entry.balanceRate > 0.0D) {
					entryObject.addProperty("balance_rate", entry.balanceRate);
				}
				entries.add(entryObject);
			}
			lineObject.add("entries", entries);
			lines.add(lineObject);
		}
		root.add("lines", lines);
		root.addProperty("active", activeIndex);
		if (!PREFERRED_MACHINE_PROFILES.isEmpty()) {
			JsonObject preferredMachines = new JsonObject();
			for (Map.Entry<String, String> preference : PREFERRED_MACHINE_PROFILES.entrySet()) {
				preferredMachines.addProperty(preference.getKey(), preference.getValue());
			}
			root.add("preferred_machines", preferredMachines);
		}
		return root;
	}

	private static JsonArray serializeLinks(Map<EmiStack, LinkMode> links) {
		JsonArray array = new JsonArray();
		for (Map.Entry<EmiStack, LinkMode> link : links.entrySet()) {
			if (link.getValue() == null || link.getValue() == LinkMode.MATCH || link.getKey() == null || link.getKey().isEmpty()) {
				continue;
			}
			JsonObject object = new JsonObject();
			object.add("stack", EmiIngredientSerializer.getSerialized(normalizeStack(link.getKey())));
			object.addProperty("mode", link.getValue().serialized());
			array.add(object);
		}
		return array;
	}

	private static int sanitizeGroupId(Line line, int groupId) {
		if (line == null || groupId <= 0) {
			return 0;
		}
		return line.getGroup(groupId) == null ? 0 : groupId;
	}

	private static boolean isGroupDescendant(Line line, int candidateId, int ancestorId) {
		if (line == null || candidateId <= 0 || ancestorId <= 0) {
			return false;
		}
		Group cursor = line.getGroup(candidateId);
		int guard = 0;
		while (cursor != null && guard++ < line.groups.size() + 1) {
			if (cursor.id == ancestorId) {
				return true;
			}
			cursor = line.getGroup(cursor.parentId);
		}
		return false;
	}

	private static void sanitizeGroups(Line line) {
		if (line == null) {
			return;
		}
		Set<Integer> seenIds = new java.util.HashSet<>();
		line.groups.removeIf(group -> group == null || !seenIds.add(group.id));
		for (Group group : line.groups) {
			if (group.parentId == group.id || group.parentId > 0 && line.getGroup(group.parentId) == null) {
				group.parentId = 0;
			}
			Set<Integer> chain = new java.util.HashSet<>();
			Group cursor = group;
			while (cursor != null && cursor.parentId > 0) {
				if (!chain.add(cursor.id)) {
					group.parentId = 0;
					break;
				}
				cursor = line.getGroup(cursor.parentId);
			}
		}
		for (Entry entry : line.entries) {
			entry.groupId = sanitizeGroupId(line, entry.groupId);
		}
	}

	private static double sanitizeRate(double rate) {
		if (!Double.isFinite(rate)) {
			return 1.0D;
		}
		return Math.max(0.01D, Math.min(rate, 1_000_000_000D));
	}

	private static double sanitizeTargetRate(double rate) {
		if (!Double.isFinite(rate) || rate <= 0.0D) {
			return 1.0D;
		}
		return Math.max(0.000001D, Math.min(rate, 1_000_000_000_000D));
	}

	private static double defaultTargetRate(EmiStack stack) {
		if (stack != null && stack.getKey() instanceof net.minecraft.fluid.Fluid) {
			return 1000.0D;
		}
		return 1.0D;
	}

	private static double sanitizeAchievedTargetRate(double rate) {
		if (!Double.isFinite(rate) || rate <= 0.0D) {
			return 0.0D;
		}
		return Math.min(rate, 1_000_000_000_000D);
	}

	private static double sanitizeBalanceRate(double rate) {
		if (!Double.isFinite(rate) || rate <= 0.0D) {
			return 0.0D;
		}
		return Math.min(rate, 1_000_000_000D);
	}

	private static int sanitizeCoilTier(int tier) {
		return Math.max(0, Math.min(tier, STANDARD_COIL_NAMES.size() - 1));
	}

	private static void invalidateBalance(Line line) {
		if (line == null) {
			return;
		}
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = !line.targets.isEmpty() ? PlannerText.tr("status.line_changed", "Line changed. Press BALANCE.") : "";
	}

	private static EmiStack firstStack(EmiIngredient ingredient) {
		for (EmiStack stack : ingredient.getEmiStacks()) {
			if (stack != null && !stack.isEmpty()) {
				return stack;
			}
		}
		return EmiStack.EMPTY;
	}

	private static EmiStack normalizeStack(EmiStack stack) {
		return stack.copy().setAmount(1).setChance(1);
	}

	private static boolean sameStack(EmiStack a, EmiStack b) {
		return a != null && b != null && normalizeStack(a).equals(normalizeStack(b));
	}

	private static double maxAbs(double[] values) {
		double max = 0.0D;
		for (double value : values) {
			max = Math.max(max, Math.abs(value));
		}
		return max;
	}

	private static double maxPositive(double[] values) {
		double max = 0.0D;
		for (double value : values) {
			max = Math.max(max, value);
		}
		return max;
	}

	private static double maxNegative(double[] values) {
		double max = 0.0D;
		for (double value : values) {
			max = Math.max(max, -value);
		}
		return max;
	}

	private static double dot(double[] a, double[] b) {
		double value = 0.0D;
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			value += a[i] * b[i];
		}
		return value;
	}

	private static String formatSolverNumber(double value) {
		if (!Double.isFinite(value)) {
			return "?";
		}
		if (value < 0.0001D) {
			return "<0.0001";
		}
		return String.format(Locale.ROOT, "%.4f", value);
	}

	private static int sanitizeCount(int count) {
		return Math.max(1, Math.min(count, 1_000_000_000));
	}

	private static double sanitizeDurationTicks(double ticks) {
		if (!Double.isFinite(ticks) || ticks <= 0.0D) {
			return 0.0D;
		}
		return Math.max(0.000001D, Math.min(ticks, 20_000_000_000D));
	}

	private static int sanitizeStandardVoltageTier(int tier) {
		if (tier < 0) {
			return -1;
		}
		return Math.min(tier, GtVoltageResolver.maxTier());
	}

	private static String sanitizeMachineProfile(String value) {
		if (value == null || value.isBlank()) {
			return "generic";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
	}

	private static String sanitizeMachineSettingKey(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		return normalized.length() > 96 ? normalized.substring(0, 96) : normalized;
	}

	private static int sanitizeMachineSettingValue(int value) {
		return Math.max(-MAX_MACHINE_SETTING_VALUE, Math.min(MAX_MACHINE_SETTING_VALUE, value));
	}

	public enum MachineSettingType {
		INTEGER, CHOICE, TOGGLE
	}

	public record MachineSettingSpec(String key, String labelKey, String englishLabel, MachineSettingType type,
			int minValue, int maxValue, int defaultValue, int step, List<String> choices, String helpKey, String englishHelp) {
		public MachineSettingSpec {
			key = sanitizeMachineSettingKey(key);
			type = type == null ? MachineSettingType.INTEGER : type;
			if (maxValue < minValue) {
				int temp = maxValue;
				maxValue = minValue;
				minValue = temp;
			}
			defaultValue = Math.max(minValue, Math.min(maxValue, defaultValue));
			step = Math.max(1, step);
			choices = choices == null ? List.of() : List.copyOf(choices);
			labelKey = labelKey == null ? "" : labelKey;
			englishLabel = englishLabel == null ? key : englishLabel;
			helpKey = helpKey == null ? "" : helpKey;
			englishHelp = englishHelp == null ? "" : englishHelp;
		}

		public static MachineSettingSpec integer(String key, String labelKey, String englishLabel, int min, int max,
				int defaultValue, int step, String helpKey, String englishHelp) {
			return new MachineSettingSpec(key, labelKey, englishLabel, MachineSettingType.INTEGER, min, max, defaultValue, step,
				List.of(), helpKey, englishHelp);
		}

		public static MachineSettingSpec choice(String key, String labelKey, String englishLabel, List<String> choices,
				int defaultValue, String helpKey, String englishHelp) {
			int max = choices == null || choices.isEmpty() ? 0 : choices.size() - 1;
			return new MachineSettingSpec(key, labelKey, englishLabel, MachineSettingType.CHOICE, 0, max, defaultValue, 1,
				choices, helpKey, englishHelp);
		}

		public static MachineSettingSpec toggle(String key, String labelKey, String englishLabel, boolean defaultValue,
				String helpKey, String englishHelp) {
			return new MachineSettingSpec(key, labelKey, englishLabel, MachineSettingType.TOGGLE, 0, 1, defaultValue ? 1 : 0, 1,
				List.of("OFF", "ON"), helpKey, englishHelp);
		}

		public int sanitize(int value) {
			return Math.max(minValue, Math.min(maxValue, sanitizeMachineSettingValue(value)));
		}

		public String displayValue(int value) {
			int safe = sanitize(value);
			if ((type == MachineSettingType.CHOICE || type == MachineSettingType.TOGGLE) && safe >= 0 && safe < choices.size()) {
				return choices.get(safe);
			}
			return Integer.toString(safe);
		}
	}

	public record MachineProfile(String id, String displayName, String description, String categoryNeedle, int maxParallel,
			boolean allowsPerfectOc, EmiStack icon, int fixedVoltageTier, boolean perfectOcKnown,
			double durationMultiplier, double energyMultiplier, boolean parallelControl,
			double coilEfficiencyPerTier, double standardOcDurationMultiplier, PlannerMachineRule machineRule,
			List<String> runtimeNotes) {
		public MachineProfile {
			machineRule = machineRule == null ? PlannerMachineRule.NONE : machineRule;
			runtimeNotes = runtimeNotes == null ? List.of() : List.copyOf(runtimeNotes);
		}

		public MachineProfile(String id, String displayName, String description, String categoryNeedle, int maxParallel,
				boolean allowsPerfectOc, EmiStack icon) {
			this(id, displayName, description, categoryNeedle, maxParallel, allowsPerfectOc, icon, -1, false,
				1.0D, 1.0D, false, 0.0D, 0.5D, PlannerMachineRule.NONE, List.of());
		}

		private static MachineProfile runtime(String id, String displayName, String description, EmiStack icon,
				MachineRuntimeInfo runtime) {
			return new MachineProfile(id, displayName, description, "", runtime.maxParallel(), runtime.allowsPerfectOc(), icon,
				runtime.fixedVoltageTier(), runtime.perfectOcKnown(), runtime.durationMultiplier(), runtime.energyMultiplier(),
				runtime.parallelControl(), runtime.coilEfficiencyPerTier(), runtime.standardOcDurationMultiplier(),
				runtime.machineRule(), runtime.notes());
		}

		private MachineProfile withRuntime(String name, EmiStack runtimeIcon, MachineRuntimeInfo runtime) {
			int detectedParallel = runtime.maxParallel() > 0 ? runtime.maxParallel() : maxParallel;
			boolean detectedPerfect = runtime.perfectOcKnown() ? runtime.allowsPerfectOc() : allowsPerfectOc;
			return new MachineProfile(id, name, description, "", detectedParallel, detectedPerfect, runtimeIcon,
				runtime.fixedVoltageTier(), runtime.perfectOcKnown(), runtime.durationMultiplier(), runtime.energyMultiplier(),
				runtime.parallelControl(), runtime.coilEfficiencyPerTier(), runtime.standardOcDurationMultiplier(),
				runtime.machineRule(), runtime.notes());
		}

		public boolean supports(EmiRecipe recipe) {
			if (categoryNeedle == null || categoryNeedle.isBlank()) {
				return true;
			}
			if (recipe == null || recipe.getCategory() == null || recipe.getCategory().getName() == null) {
				return false;
			}
			String category = recipe.getCategory().getName().getString().toLowerCase(Locale.ROOT);
			return category.contains(categoryNeedle.toLowerCase(Locale.ROOT));
		}

		public String parallelDescription() {
			return maxParallel > 0 ? "Max parallel per machine: " + maxParallel : "Parallel limit: profile-specific / not modeled yet";
		}

		public boolean hasFixedVoltage() {
			return fixedVoltageTier >= 0;
		}

		public boolean canRunRecipeTier(int recipeTier) {
			return fixedVoltageTier < 0 || recipeTier < 0 || fixedVoltageTier >= recipeTier;
		}

		public boolean hasRuntimeModifiers() {
			return fixedVoltageTier >= 0 || perfectOcKnown || maxParallel > 0 || parallelControl
				|| Math.abs(durationMultiplier - 1.0D) > 0.0000001D || Math.abs(energyMultiplier - 1.0D) > 0.0000001D
				|| coilEfficiencyPerTier > 0.0D || Math.abs(standardOcDurationMultiplier - 0.5D) > 0.0000001D
				|| machineRule != PlannerMachineRule.NONE || !machineSettingSpecsFor(this).isEmpty() || !runtimeNotes.isEmpty();
		}

		public boolean hasConfigurableSettings() {
			return coilEfficiencyPerTier > 0.0D || parallelControl || !machineSettingSpecsFor(this).isEmpty();
		}

		public List<MachineSettingSpec> specialSettings() {
			return machineSettingSpecsFor(this);
		}

		public List<String> modifierDescriptions() {
			List<String> lines = new ArrayList<>();
			if (fixedVoltageTier >= 0) {
				lines.add("Fixed machine voltage: " + voltageTierName(fixedVoltageTier));
			}
			if (perfectOcKnown) {
				lines.add("Perfect OC: " + (allowsPerfectOc ? "supported" : "not supported"));
			}
			if (maxParallel > 0) {
				lines.add("Detected max parallel: " + maxParallel);
			} else if (parallelControl) {
				lines.add("Parallel Control support detected");
			}
			if (Math.abs(durationMultiplier - 1.0D) > 0.0000001D) {
				lines.add("Machine duration multiplier: x" + formatSolverNumber(durationMultiplier));
			}
			if (Math.abs(energyMultiplier - 1.0D) > 0.0000001D) {
				lines.add("Machine energy multiplier: x" + formatSolverNumber(energyMultiplier));
			}
			if (coilEfficiencyPerTier > 0.0D) {
				lines.add("Coil efficiency: -" + formatSolverNumber(coilEfficiencyPerTier * 100.0D) + "% duration/EU per tier");
				lines.add("Coil tier is configurable in Machine Settings");
			}
			if (Math.abs(standardOcDurationMultiplier - 0.5D) > 0.0000001D) {
				lines.add("Special OC: each 4x EU/t multiplies duration by x" + formatSolverNumber(standardOcDurationMultiplier));
			}
			lines.addAll(machineRule.modifierDescriptions(this));
			for (MachineSettingSpec spec : machineSettingSpecsFor(this)) {
				lines.add(spec.englishLabel() + " is configurable in Machine Settings");
			}
			lines.addAll(runtimeNotes);
			return List.copyOf(lines);
		}

		public boolean hasIcon() {
			return icon != null && !icon.isEmpty();
		}
	}

	private record MachineRuntimeInfo(int fixedVoltageTier, int maxParallel, boolean perfectOcKnown,
			boolean allowsPerfectOc, double durationMultiplier, double energyMultiplier,
			boolean parallelControl, double coilEfficiencyPerTier, double standardOcDurationMultiplier,
			PlannerMachineRule machineRule, boolean modeled, List<String> notes) {
	}

	public static final class Line {
		private String name;
		private final List<Entry> entries = new ArrayList<>();
		private final List<Target> targets = new ArrayList<>();
		private final List<Group> groups = new ArrayList<>();
		private final Map<EmiStack, LinkMode> rootLinks = new LinkedHashMap<>();
		private int nextGroupId = 1;
		private double achievedTargetRate;
		private String bottleneckName = "";
		private boolean balanceEnabled;
		private String balanceMessage = "";
		private int standardVoltageTier = -1;

		public String getName() {
			return name;
		}

		public List<Entry> getEntries() {
			return entries;
		}

		public List<Target> getTargets() {
			return Collections.unmodifiableList(targets);
		}

		public List<Group> getGroups() {
			return Collections.unmodifiableList(groups);
		}

		public Group getGroup(int id) {
			if (id <= 0) {
				return null;
			}
			for (Group group : groups) {
				if (group.id == id) {
					return group;
				}
			}
			return null;
		}

		public String getEntryGroupName(Entry entry) {
			Group group = entry == null ? null : getGroup(entry.groupId);
			return group == null ? PlannerText.tr("groups.root", "Root") : group.getDisplayName(this);
		}

		public LinkMode getLinkMode(Group group, EmiStack stack) {
			Map<EmiStack, LinkMode> links = group == null ? rootLinks : group.links;
			LinkMode mode = links.get(normalizeStack(stack));
			return mode == null ? LinkMode.MATCH : mode;
		}

		public EmiStack getTarget() {
			return targets.isEmpty() ? EmiStack.EMPTY : targets.get(0).stack;
		}

		public double getTargetRate() {
			return targets.isEmpty() ? 1.0D : targets.get(0).rate;
		}

		public boolean hasTarget(EmiStack stack) {
			if (stack == null || stack.isEmpty()) {
				return false;
			}
			for (Target target : targets) {
				if (sameStack(target.stack, stack)) {
					return true;
				}
			}
			return false;
		}

		public boolean isBalanceEnabled() {
			return balanceEnabled;
		}

		public String getBalanceMessage() {
			return balanceMessage;
		}

		public int getStandardVoltageTier() {
			return standardVoltageTier;
		}

		public String getStandardVoltageName() {
			return voltageTierName(standardVoltageTier);
		}

		public double getEffectiveRate(Entry entry) {
			if (balanceEnabled && entry != null) {
				return entry.balanceRate;
			}
			return entry == null ? 0.0D : entry.getEffectiveRate();
		}

		public boolean hasMachineCapacityShortfall() {
			return balanceEnabled && achievedTargetRate > 0.0D
				&& achievedTargetRate + Math.max(1.0E-9D, getTargetRate() * 1.0E-9D) < getTargetRate();
		}

		public double getAchievableTargetRate() {
			return balanceEnabled && achievedTargetRate > 0.0D ? achievedTargetRate : getTargetRate();
		}

		public double getAchievableTargetRate(Target target) {
			if (target == null || !targets.contains(target)) {
				return 0.0D;
			}
			if (!balanceEnabled || achievedTargetRate <= 0.0D || targets.isEmpty()) {
				return target.rate;
			}
			double primaryRate = Math.max(0.000000001D, getTargetRate());
			double scale = achievedTargetRate / primaryRate;
			return target.rate * scale;
		}

		public String getBottleneckName() {
			return bottleneckName == null ? "" : bottleneckName;
		}
	}

	public enum LinkMode {
		MATCH("match"),
		IGNORE("ignore");

		private final String serialized;

		LinkMode(String serialized) {
			this.serialized = serialized;
		}

		public String serialized() {
			return serialized;
		}

		public LinkMode toggled() {
			return this == MATCH ? IGNORE : MATCH;
		}

		private static LinkMode fromSerialized(String value) {
			return value != null && "ignore".equalsIgnoreCase(value) ? IGNORE : MATCH;
		}
	}

	public static final class Group {
		private final int id;
		private int parentId;
		private String name;
		private boolean collapsed;
		private final Map<EmiStack, LinkMode> links = new LinkedHashMap<>();

		private Group(int id, int parentId, String name) {
			this.id = Math.max(1, id);
			this.parentId = Math.max(0, parentId);
			this.name = name;
		}

		public int getId() {
			return id;
		}

		public int getParentId() {
			return parentId;
		}

		public Group getParent(Line line) {
			return line == null ? null : line.getGroup(parentId);
		}

		public String getName() {
			return name;
		}

		public String getDisplayName(Line line) {
			if (name != null && !name.isBlank()) {
				return name;
			}
			int number = 1;
			if (line != null) {
				for (Group group : line.groups) {
					if (group == this) {
						break;
					}
					number++;
				}
			}
			return PlannerText.tr("group.default", "Group") + " " + number;
		}

		public boolean isCollapsed() {
			return collapsed;
		}
	}

	public enum TargetMode {
		OUTPUT("output", "OUT"),
		INPUT("input", "IN");

		private final String serialized;
		private final String label;

		TargetMode(String serialized, String label) {
			this.serialized = serialized;
			this.label = label;
		}

		public String serialized() {
			return serialized;
		}

		public String label() {
			return label;
		}

		private static TargetMode fromSerialized(String value) {
			return value != null && "input".equalsIgnoreCase(value) ? INPUT : OUTPUT;
		}
	}

	public static final class Target {
		private final EmiStack stack;
		private double rate;
		private TargetMode mode;

		private Target(EmiStack stack, double rate, TargetMode mode) {
			this.stack = normalizeStack(stack);
			this.rate = sanitizeTargetRate(rate);
			this.mode = mode == null ? TargetMode.OUTPUT : mode;
		}

		public EmiStack getStack() {
			return stack;
		}

		public double getRate() {
			return rate;
		}

		public TargetMode getMode() {
			return mode;
		}

		private double signedRate() {
			return mode == TargetMode.INPUT ? -rate : rate;
		}

		private double flowMagnitude(double signedFlow) {
			return mode == TargetMode.INPUT ? -signedFlow : signedFlow;
		}
	}

	public record BalanceResult(boolean success, String message, double targetNet, double maxInternalResidual) {
	}

	private record PendingReplacement(int lineIndex, Identifier recipeId, EmiStack output) {
	}

	private record MachineSizingStatus(int active, int unavailable, int insufficient) {
	}

	private record TargetVector(Target target, ResourceVector vector) {
	}

	private record BottleneckPropagation(double scale, String label) {
		private static BottleneckPropagation none() {
			return new BottleneckPropagation(1.0D, "");
		}

		private boolean limited() {
			return scale < 1.0D - 1.0E-9D;
		}
	}

	public record MachineSizing(boolean available, boolean exact, int machines, int parallel,
			double requiredEffectiveParallel, double installedEffectiveParallel, double capacityRate,
			double headroomPercent, String note) {
		private static MachineSizing unavailable() {
			return unavailable("Machine sizing unavailable");
		}

		private static MachineSizing unavailable(String note) {
			return new MachineSizing(false, false, 1, 1, 0.0D, 1.0D, 0.0D, 0.0D,
				note == null || note.isBlank() ? "Machine sizing unavailable" : note);
		}

		public boolean sufficient() {
			return available && installedEffectiveParallel + 1.0E-9D >= requiredEffectiveParallel;
		}

		public double shortfallPercent() {
			if (!available || requiredEffectiveParallel <= 0.0D || sufficient()) {
				return 0.0D;
			}
			return Math.max(0.0D, (1.0D - installedEffectiveParallel / requiredEffectiveParallel) * 100.0D);
		}
	}

	public enum OcMode {
		NONE("off", "OFF", 1.0D),
		STANDARD("standard", "STD", 2.0D),
		PERFECT("perfect", "PERF", 4.0D);

		private final String serialized;
		private final String label;
		private final double durationDivisor;

		OcMode(String serialized, String label, double durationDivisor) {
			this.serialized = serialized;
			this.label = label;
			this.durationDivisor = durationDivisor;
		}

		public String serialized() {
			return serialized;
		}

		public String label() {
			return label;
		}

		public double durationDivisor() {
			return durationDivisor;
		}

		public OcMode next() {
			return switch (this) {
				case NONE -> STANDARD;
				case STANDARD -> PERFECT;
				case PERFECT -> NONE;
			};
		}

		public OcMode previous() {
			return switch (this) {
				case NONE -> PERFECT;
				case STANDARD -> NONE;
				case PERFECT -> STANDARD;
			};
		}

		private static OcMode fromSerialized(String value) {
			if (value != null) {
				for (OcMode mode : values()) {
					if (mode.serialized.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
						return mode;
					}
				}
			}
			return STANDARD;
		}
	}

	public static final class Entry {
		private final Identifier recipeId;
		private double rate;
		private boolean automatic;
		private int machines;
		private int parallel;
		private double durationOverrideTicks;
		private double balanceRate;
		private String machineProfileId;
		private int voltageTier;
		private boolean voltageOverride;
		private OcMode ocMode;
		private int coilTier;
		private final Map<String, Integer> machineSettings = new LinkedHashMap<>();
		private boolean machinesFixed;
		private boolean parallelFixed;
		private int groupId;
		private double detectedDurationTicks = Double.NaN;
		private long detectedRecipeEUt = Long.MIN_VALUE;

		private Entry(Identifier recipeId, double rate, boolean automatic, int machines, int parallel,
				double durationOverrideTicks, double balanceRate, String machineProfileId, int voltageTier, boolean voltageOverride, OcMode ocMode, int coilTier,
				boolean machinesFixed, boolean parallelFixed) {
			this.recipeId = recipeId;
			this.rate = rate;
			this.automatic = automatic;
			this.machines = machines;
			this.parallel = parallel;
			this.durationOverrideTicks = durationOverrideTicks;
			this.balanceRate = balanceRate;
			this.machineProfileId = sanitizeMachineProfile(machineProfileId);
			this.voltageTier = voltageTier;
			this.voltageOverride = voltageOverride;
			this.ocMode = ocMode == null ? OcMode.STANDARD : ocMode;
			this.coilTier = sanitizeCoilTier(coilTier);
			this.machinesFixed = machinesFixed;
			this.parallelFixed = parallelFixed;
			MachineProfile profile = findBuiltinMachineProfile(this.machineProfileId);
			if (profile == null) {
				profile = BUILTIN_MACHINE_PROFILES.get(0);
			}
			this.parallel = profile.maxParallel() > 0 ? Math.min(this.parallel, profile.maxParallel()) : this.parallel;
			if (this.ocMode == OcMode.PERFECT && !profile.allowsPerfectOc()) {
				this.ocMode = OcMode.STANDARD;
			}
		}

		public Identifier getRecipeId() {
			return recipeId;
		}

		public double getRate() {
			return rate;
		}

		public boolean isAutomatic() {
			return automatic;
		}

		public int getMachines() {
			return machines;
		}

		public int getParallel() {
			return parallel;
		}

		public int getGroupId() {
			return groupId;
		}

		public boolean isMachinesFixed() {
			return machinesFixed;
		}

		public boolean isParallelFixed() {
			return parallelFixed;
		}

		public double getBalanceRate() {
			return balanceRate;
		}

		public String getMachineProfileId() {
			return machineProfileId;
		}

		public MachineProfile getMachineProfile() {
			return ProductionPlanner.getMachineProfile(this);
		}

		public String getMachineProfileName() {
			return getMachineProfile().displayName();
		}

		public OcMode getOcMode() {
			return ocMode;
		}

		public int getCoilTier() {
			return coilTier;
		}

		public String getCoilName() {
			return ProductionPlanner.coilTierName(coilTier);
		}

		public double getCoilMultiplier() {
			double efficiency = getMachineProfile().coilEfficiencyPerTier();
			if (efficiency <= 0.0D || coilTier <= 0) {
				return 1.0D;
			}
			return Math.pow(Math.max(0.01D, 1.0D - efficiency), coilTier);
		}

		public int getMachineSettingValue(MachineSettingSpec spec) {
			if (spec == null) {
				return 0;
			}
			return spec.sanitize(machineSettings.getOrDefault(spec.key(), spec.defaultValue()));
		}

		public String getMachineSettingDisplayValue(MachineSettingSpec spec) {
			return spec == null ? "" : spec.displayValue(getMachineSettingValue(spec));
		}

		public double getMachineSettingDurationMultiplier() {
			return machineSettingDurationMultiplier(this);
		}

		public double getMachineSettingThroughputMultiplier() {
			return machineSettingThroughputMultiplier(this);
		}

		public int getConfiguredMaxParallel() {
			return configuredMaxParallel(this);
		}

		public double getOcDurationMultiplierPerStep() {
			return getOcDurationMultiplierForStep(0);
		}

		public double getOcDurationMultiplierForStep(int overclockIndex) {
			return switch (ocMode) {
				case NONE -> 1.0D;
				case PERFECT -> 0.25D;
				case STANDARD -> {
					double base = getMachineProfile().standardOcDurationMultiplier();
					double modified = getMachineProfile().machineRule().standardOcDurationMultiplier(this, base);
					modified = getMachineProfile().machineRule().ocDurationMultiplierForStep(this, Math.max(0, overclockIndex), modified);
					yield Math.max(0.000001D, Math.min(1.0D, modified));
				}
			};
		}

		public String getOcDisplayLabel() {
			if (ocMode == OcMode.STANDARD && getOverclockCount() > 0
					&& Math.abs(getOcDurationMultiplierPerStep() - 0.5D) > 0.0000001D) {
				return Math.round(getOcDurationMultiplierPerStep() * 100.0D) + "%";
			}
			return ocMode.label();
		}

		public boolean isDurationOverridden() {
			return durationOverrideTicks > 0.0D;
		}

		public double getDurationTicks() {
			return durationOverrideTicks > 0.0D ? durationOverrideTicks : getDetectedDurationTicks();
		}

		public double getDurationSeconds() {
			double ticks = getDurationTicks();
			return ticks > 0.0D ? ticks / TICKS_PER_SECOND : -1.0D;
		}

		public double getDetectedDurationTicks() {
			if (Double.isNaN(detectedDurationTicks)) {
				detectedDurationTicks = RecipeTimingResolver.resolveDurationTicks(getRecipe());
			}
			return detectedDurationTicks;
		}

		public double getDetectedDurationSeconds() {
			double ticks = getDetectedDurationTicks();
			return ticks > 0.0D ? ticks / TICKS_PER_SECOND : -1.0D;
		}

		public long getRecipeEUt() {
			if (detectedRecipeEUt == Long.MIN_VALUE) {
				detectedRecipeEUt = RecipePowerResolver.resolveRecipeEUt(getRecipe());
			}
			return detectedRecipeEUt;
		}

		public int getRecipeTier() {
			long eut = getRecipeEUt();
			return eut > 0L ? GtVoltageResolver.tierForVoltage(eut) : -1;
		}

		public boolean isVoltageOverridden() {
			return voltageOverride;
		}

		public int getVoltageTier() {
			int recipeTier = getRecipeTier();
			if (recipeTier < 0) {
				return -1;
			}
			MachineProfile profile = getMachineProfile();
			if (profile.hasFixedVoltage()) {
				return Math.max(recipeTier, profile.fixedVoltageTier());
			}
			if (voltageTier < recipeTier) {
				return recipeTier;
			}
			return Math.min(voltageTier, GtVoltageResolver.maxTier());
		}

		public String getVoltageName() {
			int tier = getVoltageTier();
			return tier < 0 ? "--" : GtVoltageResolver.name(tier);
		}

		public long getSelectedVoltage() {
			int tier = getVoltageTier();
			return tier < 0 ? 0L : GtVoltageResolver.voltage(tier);
		}

		public int getOverclockCount() {
			long baseEUt = getRuleAdjustedPreOverclockEUt();
			double duration = getDurationTicks();
			long selectedVoltage = getSelectedVoltage();
			if (baseEUt <= 0L || duration <= 0.0D || selectedVoltage <= 0L || ocMode == OcMode.NONE) {
				return 0;
			}
			long eut = baseEUt;
			int overclocks = 0;
			while (overclocks < 32 && duration > 1.0D && eut <= selectedVoltage / 4L) {
				eut = multiplyByFourSaturated(eut);
				duration = Math.max(1.0D, duration * getOcDurationMultiplierForStep(overclocks));
				overclocks++;
			}
			return overclocks;
		}

		public double getProcessedDurationTicks() {
			double duration = getDurationTicks();
			if (duration <= 0.0D) {
				return duration;
			}
			int overclocks = getOverclockCount();
			for (int i = 0; i < overclocks; i++) {
				duration = Math.max(1.0D, duration * getOcDurationMultiplierForStep(i));
			}
			duration = Math.max(1.0D, duration * getMachineProfile().durationMultiplier() * getCoilMultiplier() * getMachineSettingDurationMultiplier());
			return duration;
		}

		public double getProcessedDurationSeconds() {
			double ticks = getProcessedDurationTicks();
			return ticks > 0.0D ? ticks / TICKS_PER_SECOND : -1.0D;
		}

		private long getRuleAdjustedPreOverclockEUt() {
			long base = getRecipeEUt();
			if (base <= 0L) {
				return 0L;
			}
			double multiplier = getMachineProfile().machineRule().energyMultiplier(this);
			if (!Double.isFinite(multiplier) || multiplier <= 0.0D) {
				multiplier = 1.0D;
			}
			double modified = base * multiplier;
			if (!Double.isFinite(modified) || modified >= Long.MAX_VALUE) {
				return Long.MAX_VALUE;
			}
			return Math.max(1L, Math.round(modified));
		}

		public long getProcessedEUt() {
			long eut = getRuleAdjustedPreOverclockEUt();
			if (eut <= 0L) {
				return 0L;
			}
			for (int i = 0; i < getOverclockCount(); i++) {
				eut = multiplyByFourSaturated(eut);
			}
			double modified = eut * getMachineProfile().energyMultiplier() * getCoilMultiplier();
			if (!Double.isFinite(modified) || modified >= Long.MAX_VALUE) {
				return Long.MAX_VALUE;
			}
			return Math.max(1L, Math.round(modified));
		}

		public boolean isVoltageFixedByMachine() {
			return getMachineProfile().hasFixedVoltage();
		}

		public double getEnergyPerCraft() {
			long eut = getProcessedEUt();
			double ticks = getProcessedDurationTicks();
			if (eut <= 0L || ticks <= 0.0D) {
				return 0.0D;
			}
			return eut * ticks;
		}

		public double getAveragePowerEUt(double craftsPerSecond) {
			if (!Double.isFinite(craftsPerSecond) || craftsPerSecond <= 0.0D) {
				return 0.0D;
			}
			return getEnergyPerCraft() * craftsPerSecond / TICKS_PER_SECOND;
		}

		public double getRequiredEffectiveParallel(double craftsPerSecond) {
			double seconds = getProcessedDurationSeconds();
			if (seconds <= 0.0D || !Double.isFinite(craftsPerSecond) || craftsPerSecond <= 0.0D) {
				return 0.0D;
			}
			return craftsPerSecond * seconds;
		}

		public MachineSizing getMachineSizing(double craftsPerSecond) {
			String constraintError = machineSettingsConstraintError(this);
			if (!constraintError.isBlank()) {
				return MachineSizing.unavailable(constraintError);
			}
			double seconds = getProcessedDurationSeconds();
			double required = getRequiredEffectiveParallel(craftsPerSecond);
			double throughput = getMachineSettingThroughputMultiplier();
			if (seconds <= 0.0D || !Double.isFinite(required) || required <= 0.0D) {
				return MachineSizing.unavailable();
			}
			MachineProfile profile = getMachineProfile();
			int machines;
			int parallel;
			boolean exact;
			String note;
			int maxParallel = getConfiguredMaxParallel();

			if (machinesFixed && parallelFixed) {
				machines = sanitizeCount(this.machines);
				parallel = clampParallelForProfile(this, sanitizeCount(this.parallel));
				exact = maxParallel > 0;
				note = "MACH and PAR are fixed by the user";
			} else if (machinesFixed) {
				machines = sanitizeCount(this.machines);
				if (maxParallel > 0) {
					parallel = Math.min(maxParallel, ceilCount(required / (machines * throughput)));
					exact = true;
					note = "MACH is fixed; PAR is sized up to detected max parallel " + maxParallel;
				} else if (profile.parallelControl() || "generic".equals(profile.id())) {
					parallel = ceilCount(required / (machines * throughput));
					exact = false;
					note = "MACH is fixed; PAR is provisional because the machine parallel limit is unknown";
				} else {
					parallel = 1;
					exact = false;
					note = "MACH is fixed; unknown machine parallel limit is treated conservatively as 1";
				}
			} else if (parallelFixed) {
				parallel = clampParallelForProfile(this, sanitizeCount(this.parallel));
				machines = ceilCount(required / (parallel * throughput));
				exact = maxParallel > 0;
				note = maxParallel > 0
					? "PAR is fixed; MACH is sized from the fixed parallel"
					: "PAR is fixed by the user; MACH sizing is provisional because the profile parallel limit is unknown";
			} else if (maxParallel > 0) {
				machines = ceilCount(required / (maxParallel * throughput));
				parallel = Math.min(maxParallel, ceilCount(required / (machines * throughput)));
				exact = true;
				note = "Sized from detected max parallel " + maxParallel + " per machine";
			} else if (profile.parallelControl()) {
				machines = 1;
				parallel = ceilCount(required / throughput);
				exact = false;
				note = "Parallel Control detected, but its maximum parallel is unknown";
			} else if ("generic".equals(profile.id())) {
				machines = 1;
				parallel = ceilCount(required / throughput);
				exact = false;
				note = "Generic profile assumes the requested effective parallel can be supplied";
			} else {
				machines = ceilCount(required / throughput);
				parallel = 1;
				exact = false;
				note = "Parallel limit is unknown; conservative 1 parallel per machine sizing";
			}
			double installed = machines * (double) parallel * throughput;
			double capacityRate = installed / seconds;
			if (throughput > 1.0D + 0.0000001D) {
				note += "; machine throughput multiplier x" + formatSolverNumber(throughput);
			}
			double headroom = craftsPerSecond > 0.0D
				? Math.max(0.0D, (capacityRate / craftsPerSecond - 1.0D) * 100.0D)
				: 0.0D;
			return new MachineSizing(true, exact, machines, parallel, required, installed, capacityRate, headroom, note);
		}

		public double getEffectiveRate() {
			if (!machineSettingsAllowRecipe(this)) {
				return 0.0D;
			}
			if (automatic) {
				double ticks = getProcessedDurationTicks();
				if (ticks > 0.0D) {
					return (machines * (double) parallel * getMachineSettingThroughputMultiplier() * TICKS_PER_SECOND) / ticks;
				}
			}
			return rate;
		}


		public EmiRecipe getRecipe() {
			return EmiRecipes.manager.getRecipe(recipeId);
		}
	}

	private static int ceilCount(double value) {
		if (!Double.isFinite(value) || value <= 1.0D) {
			return 1;
		}
		if (value >= 1_000_000_000D) {
			return 1_000_000_000;
		}
		return sanitizeCount((int) Math.ceil(value - 1.0E-12D));
	}

	private static long multiplyByFourSaturated(long value) {
		if (value <= 0L) {
			return 0L;
		}
		return value > Long.MAX_VALUE / 4L ? Long.MAX_VALUE : value * 4L;
	}

	private static final class ResourceVector {
		private final EmiStack stack;
		private final double[] net;
		private boolean hasInput;
		private boolean hasOutput;

		private ResourceVector(EmiStack stack, int size) {
			this.stack = stack;
			this.net = new double[size];
		}
	}

	private static final class MachineRuntimeResolver {
		private static final Pattern NUMBER_BEFORE_PARALLEL = Pattern.compile("(?i)([0-9][0-9,._]*)\\s*(?:x\\s*)?(?:max(?:imum)?\\s+)?parallels?");
		private static final Pattern PARALLEL_BEFORE_NUMBER = Pattern.compile("(?i)(?:max(?:imum)?\\s+)?parallels?(?:\\s+per\\s+(?:machine|recipe))?\\s*[:=x]?\\s*([0-9][0-9,._]*)");
		private static final Pattern VOLTAGE_NUMBER = Pattern.compile("(?i)([0-9][0-9,._ ]*)\\s*(?:eu/t|v)");
		private static final Pattern PERCENT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%");

		private MachineRuntimeResolver() {
		}

		private static MachineRuntimeInfo resolve(EmiStack stack, String displayName) {
			int fixedVoltageTier = -1;
			int maxParallel = 0;
			boolean perfectKnown = false;
			boolean allowsPerfect = true;
			boolean parallelControl = false;
			double coilEfficiency = 0.0D;
			double durationMultiplier = 1.0D;
			double energyMultiplier = 1.0D;
			double standardOcDurationMultiplier = 0.5D;
			PlannerMachineRule machineRule = PlannerMachineRule.NONE;
			List<String> notes = new ArrayList<>();
			List<String> tooltipLines = tooltipLines(stack);
			for (String line : tooltipLines) {
				String lower = line.toLowerCase(Locale.ROOT);
				if (fixedVoltageTier < 0 && isVoltageLine(lower)) {
					int tier = GtVoltageResolver.tierFromText(line);
					if (tier < 0) {
						Matcher matcher = VOLTAGE_NUMBER.matcher(line);
						if (matcher.find()) {
							long voltage = parseLong(matcher.group(1));
							if (voltage > 0L) {
								tier = GtVoltageResolver.tierForVoltage(voltage);
							}
						}
					}
					if (tier >= 0) {
						fixedVoltageTier = tier;
					}
				}
				if (lower.contains("perfect overclock") || lower.contains("perfect oc")) {
					perfectKnown = true;
					allowsPerfect = !isNegativeFeatureLine(lower);
				}
				if (lower.contains("parallel control")) {
					parallelControl = !isNegativeFeatureLine(lower) && !lower.contains("no parallel control");
				}
				int parsedParallel = parseParallel(line);
				if (parsedParallel > 0) {
					maxParallel = Math.max(maxParallel, parsedParallel);
				}
				if (lower.contains("coil") && lower.contains("duration") && lower.contains("energy") && lower.contains("%")) {
					Matcher matcher = PERCENT.matcher(line);
					if (matcher.find()) {
						try {
							double percent = Double.parseDouble(matcher.group(1));
							if (percent > 0.0D && percent < 100.0D) {
								coilEfficiency = percent / 100.0D;
							}
						} catch (Throwable ignored) {
						}
					}
				}
				if (!lower.contains("coil") && !lower.contains("per tier") && !lower.contains("each tier")) {
					Matcher matcher = PERCENT.matcher(line);
					if (matcher.find() && (lower.contains("reduc") || lower.contains("discount"))) {
						try {
							double fraction = Double.parseDouble(matcher.group(1)) / 100.0D;
							if (fraction > 0.0D && fraction < 1.0D) {
								if (lower.contains("duration") || lower.contains("recipe time")) {
									durationMultiplier *= 1.0D - fraction;
								}
								if (lower.contains("energy") || lower.contains("eu/t") || lower.contains("eut")) {
									energyMultiplier *= 1.0D - fraction;
								}
							}
						} catch (Throwable ignored) {
						}
					}
				}
			}
			String machineId = stack == null || stack.isEmpty() ? "" : stack.getId().toString();
			PlannerMachineRuntimeOverride compat = PlannerMachineCompatRegistry.resolve(stack, machineId, tooltipLines);
			if (compat != null) {
				if (compat.fixedVoltageTier() != null) {
					fixedVoltageTier = compat.fixedVoltageTier();
				}
				if (compat.maxParallel() != null) {
					maxParallel = Math.max(0, compat.maxParallel());
				}
				if (compat.perfectOcKnown() != null) {
					perfectKnown = compat.perfectOcKnown();
				}
				if (compat.allowsPerfectOc() != null) {
					allowsPerfect = compat.allowsPerfectOc();
				}
				if (compat.durationMultiplier() != null) {
					durationMultiplier = compat.durationMultiplier();
				}
				if (compat.energyMultiplier() != null) {
					energyMultiplier = compat.energyMultiplier();
				}
				if (compat.parallelControl() != null) {
					parallelControl = compat.parallelControl();
				}
				if (compat.coilEfficiencyPerTier() != null) {
					coilEfficiency = compat.coilEfficiencyPerTier();
				}
				if (compat.standardOcDurationMultiplier() != null) {
					standardOcDurationMultiplier = compat.standardOcDurationMultiplier();
				}
				machineRule = compat.machineRule();
				notes.addAll(compat.notes());
			}

			boolean tieredSingleblock = fixedVoltageTier >= 0 && !parallelControl && machineRule == PlannerMachineRule.NONE;
			if (tieredSingleblock && maxParallel <= 0) {
				maxParallel = 1;
			}
			if (!perfectKnown && tieredSingleblock) {
				allowsPerfect = false;
				perfectKnown = true;
			}
			if (fixedVoltageTier >= 0) {
				notes.add("Voltage tier was read from the machine definition/tooltip");
			}
			boolean modeled = fixedVoltageTier >= 0 || maxParallel > 0 || perfectKnown || parallelControl || coilEfficiency > 0.0D
				|| Math.abs(durationMultiplier - 1.0D) > 0.0000001D || Math.abs(energyMultiplier - 1.0D) > 0.0000001D
				|| Math.abs(standardOcDurationMultiplier - 0.5D) > 0.0000001D || machineRule != PlannerMachineRule.NONE;
			return new MachineRuntimeInfo(fixedVoltageTier, maxParallel, perfectKnown, allowsPerfect,
				durationMultiplier, energyMultiplier, parallelControl, coilEfficiency, standardOcDurationMultiplier,
				machineRule, modeled, List.copyOf(notes));
		}

		private static int definitionTier(EmiStack stack) {
			if (stack == null || stack.isEmpty()) {
				return -1;
			}
			try {
				Object key = stack.getKey();
				Object definition = invokeObject(key, "getDefinition");
				if (definition == null) {
					return -1;
				}
				double tier = invokeNumber(definition, "getTier");
				if (tier >= 0.0D) {
					return Math.min((int) Math.round(tier), GtVoltageResolver.maxTier());
				}
			} catch (Throwable ignored) {
			}
			return -1;
		}

		private static List<String> tooltipLines(EmiStack stack) {
			List<String> lines = new ArrayList<>();
			try {
				for (Text text : stack.getTooltipText()) {
					if (text == null) {
						continue;
					}
					String value = text.getString().trim();
					if (!value.isEmpty()) {
						lines.add(value);
					}
				}
			} catch (Throwable ignored) {
			}
			return lines;
		}

		private static boolean isVoltageLine(String lower) {
			return lower.contains("voltage in") || lower.contains("input voltage") || lower.contains("voltage input");
		}

		private static boolean isNegativeFeatureLine(String lower) {
			return lower.contains("unavailable") || lower.contains("disabled") || lower.contains("false")
				|| lower.contains("not supported") || lower.contains("not available") || lower.contains("✗")
				|| lower.matches(".*(?:^|[:= ])x(?:$|[ .]).*");
		}

		private static int parseParallel(String line) {
			String lower = line == null ? "" : line.toLowerCase(Locale.ROOT);
			if (lower.contains("for each") || lower.contains("for every") || lower.contains("each layer")
					|| lower.contains("tier") || lower.contains("temperature") || lower.contains("formula")
					|| lower.contains("multiplier") || lower.contains("robots") || lower.contains("plasma")
					|| lower.contains("current neutron") || lower.contains("actual parallel")
					|| lower.contains("^") || lower.contains("log") || lower.contains("×") && lower.contains("(")) {
				return 0;
			}
			Matcher matcher = NUMBER_BEFORE_PARALLEL.matcher(line);
			if (matcher.find()) {
				long value = parseLong(matcher.group(1));
				return value > 0L ? (int) Math.min(value, Integer.MAX_VALUE) : 0;
			}
			matcher = PARALLEL_BEFORE_NUMBER.matcher(line);
			if (matcher.find()) {
				long value = parseLong(matcher.group(1));
				return value > 0L ? (int) Math.min(value, Integer.MAX_VALUE) : 0;
			}
			return 0;
		}

		private static long parseLong(String value) {
			if (value == null) {
				return 0L;
			}
			String digits = value.replaceAll("[^0-9]", "");
			if (digits.isEmpty()) {
				return 0L;
			}
			try {
				return Long.parseLong(digits);
			} catch (Throwable ignored) {
				return Long.MAX_VALUE;
			}
		}

		private static Object invokeObject(Object target, String name) {
			if (target == null) {
				return null;
			}
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Method method = type.getDeclaredMethod(name);
					if (method.getParameterCount() != 0) {
						continue;
					}
					method.setAccessible(true);
					return method.invoke(target);
				} catch (Throwable ignored) {
				}
			}
			return null;
		}

		private static double invokeNumber(Object target, String name) {
			Object value = invokeObject(target, name);
			return value instanceof Number number ? number.doubleValue() : -1.0D;
		}
	}

	private static final class GtVoltageResolver {
		private static final long[] FALLBACK_VOLTAGES = {
			8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L,
			2097152L, 8388608L, 33554432L, 134217728L, 536870912L, 2147483648L,
			8589934592L, 34359738368L, 137438953472L
		};
		private static final String[] FALLBACK_NAMES = {
			"ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV",
			"UHV", "UEV", "UIV", "UXV", "OpV", "MAX", "MAX+1", "MAX+2", "MAX+3"
		};
		private static final int[] FALLBACK_COLORS = {
			0xFF555555, 0xFFAAAAAA, 0xFF55FFFF, 0xFFFFAA00, 0xFFAA00AA, 0xFF5555FF,
			0xFFFF55FF, 0xFFFF5555, 0xFF00AAAA, 0xFFAA0000, 0xFF55FF55, 0xFF00AA00,
			0xFFFFFF55, 0xFF0000AA, 0xFFFF5555, 0xFFFFAA00, 0xFF55FFFF, 0xFFFFFFFF
		};
		private static volatile boolean resolved;
		private static long[] voltages = FALLBACK_VOLTAGES;
		private static String[] names = FALLBACK_NAMES;

		private GtVoltageResolver() {
		}

		private static void resolve() {
			if (resolved) {
				return;
			}
			synchronized (GtVoltageResolver.class) {
				if (resolved) {
					return;
				}
				try {
					Class<?> values = Class.forName("com.gregtechceu.gtceu.api.GTValues", false, ProductionPlanner.class.getClassLoader());
					Object v = readStaticField(values, "V");
					if (v instanceof long[] array && array.length > 0) {
						voltages = array.clone();
					}
					Object vn = readStaticField(values, "VN");
					if (vn instanceof String[] array && array.length > 0) {
						names = array.clone();
					}
				} catch (Throwable ignored) {
				}
				resolved = true;
			}
		}

		private static int tierForVoltage(long eut) {
			resolve();
			long value = Math.max(1L, eut);
			for (int i = 0; i < voltages.length; i++) {
				if (value <= voltages[i]) {
					return i;
				}
			}
			return voltages.length - 1;
		}

		private static int tierFromText(String text) {
			if (text == null || text.isBlank()) {
				return -1;
			}
			resolve();
			String upper = text.toUpperCase(Locale.ROOT).replace("§", "");
			for (int i = names.length - 1; i >= 0; i--) {
				String name = name(i).toUpperCase(Locale.ROOT);
				if (name.isBlank()) {
					continue;
				}
				if (upper.contains("(" + name + ")") || upper.matches(".*(?:^|[^A-Z0-9+])" + Pattern.quote(name) + "(?:$|[^A-Z0-9+]).*")) {
					return i;
				}
			}
			return -1;
		}

		private static int maxTier() {
			resolve();
			return Math.max(0, voltages.length - 1);
		}

		private static long voltage(int tier) {
			resolve();
			int index = Math.max(0, Math.min(tier, voltages.length - 1));
			return voltages[index];
		}

		private static String name(int tier) {
			resolve();
			int index = Math.max(0, Math.min(tier, voltages.length - 1));
			if (index < names.length && names[index] != null && !names[index].isBlank()) {
				return names[index].replaceAll("§.", "");
			}
			return "T" + index;
		}

		private static int color(int tier) {
			resolve();
			int index = Math.max(0, Math.min(tier, voltages.length - 1));
			if (index < names.length && names[index] != null) {
				int parsed = legacyColor(names[index]);
				if (parsed != 0) {
					return parsed;
				}
			}
			return FALLBACK_COLORS[Math.min(index, FALLBACK_COLORS.length - 1)];
		}

		private static int legacyColor(String text) {
			if (text == null) {
				return 0;
			}
			for (int i = 0; i + 1 < text.length(); i++) {
				if (text.charAt(i) != '§') {
					continue;
				}
				return switch (Character.toLowerCase(text.charAt(i + 1))) {
					case '0' -> 0xFF000000;
					case '1' -> 0xFF0000AA;
					case '2' -> 0xFF00AA00;
					case '3' -> 0xFF00AAAA;
					case '4' -> 0xFFAA0000;
					case '5' -> 0xFFAA00AA;
					case '6' -> 0xFFFFAA00;
					case '7' -> 0xFFAAAAAA;
					case '8' -> 0xFF555555;
					case '9' -> 0xFF5555FF;
					case 'a' -> 0xFF55FF55;
					case 'b' -> 0xFF55FFFF;
					case 'c' -> 0xFFFF5555;
					case 'd' -> 0xFFFF55FF;
					case 'e' -> 0xFFFFFF55;
					case 'f' -> 0xFFFFFFFF;
					default -> 0;
				};
			}
			return 0;
		}

		private static Object readStaticField(Class<?> type, String name) {
			for (Class<?> current = type; current != null; current = current.getSuperclass()) {
				try {
					Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					return field.get(null);
				} catch (Throwable ignored) {
				}
			}
			return null;
		}
	}

	private static final class RecipePowerResolver {
		private static final String[] EUT_METHODS = {
			"getInputEUt", "getEUt", "getRecipeEUt", "getEUPerTick"
		};

		private RecipePowerResolver() {
		}

		private static long resolveRecipeEUt(EmiRecipe recipe) {
			if (recipe == null) {
				return 0L;
			}
			List<Object> roots = new ArrayList<>();
			try {
				Object backing = recipe.getBackingRecipe();
				if (backing != null) {
					roots.add(backing);
				}
			} catch (Throwable ignored) {
			}
			roots.add(recipe);
			Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			List<Object> level = roots;
			for (int depth = 0; depth < 5 && !level.isEmpty(); depth++) {
				List<Object> next = new ArrayList<>();
				for (Object target : level) {
					if (target == null || !visited.add(target)) {
						continue;
					}
					long eut = readEUt(target);
					if (eut > 0L) {
						return eut;
					}
					collectNestedRecipes(target, next);
				}
				level = next;
			}
			return 0L;
		}

		private static long readEUt(Object target) {
			String className = target.getClass().getName().toLowerCase(Locale.ROOT);
			boolean gt = className.startsWith("com.gregtechceu.") || className.contains("gtrecipe");
			if (!gt) {
				Object definition = invokeObject(target, "getRecipeDefinition");
				if (definition == null) {
					definition = invokeObject(target, "getRecipe");
				}
				if (definition != null && definition != target) {
					long nested = readEUt(definition);
					if (nested > 0L) {
						return nested;
					}
				}
			}
			if (gt) {
				for (String name : EUT_METHODS) {
					double value = invokeNumber(target, name);
					if (value != 0.0D) {
						return absoluteLong(value);
					}
				}
				double field = readNumberField(target, "eut");
				if (field == 0.0D) {
					field = readNumberField(target, "EUt");
				}
				if (field != 0.0D) {
					return absoluteLong(field);
				}
			}
			return 0L;
		}

		private static long absoluteLong(double value) {
			if (!Double.isFinite(value)) {
				return 0L;
			}
			double absolute = Math.abs(value);
			return absolute >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(absolute);
		}

		private static Object invokeObject(Object target, String name) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Method method = type.getDeclaredMethod(name);
					if (method.getParameterCount() != 0) {
						continue;
					}
					method.setAccessible(true);
					return method.invoke(target);
				} catch (Throwable ignored) {
				}
			}
			return null;
		}

		private static double invokeNumber(Object target, String name) {
			Object value = invokeObject(target, name);
			return value instanceof Number number ? number.doubleValue() : 0.0D;
		}

		private static double readNumberField(Object target, String name) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Field field = type.getDeclaredField(name);
					field.setAccessible(true);
					Object value = field.get(target);
					if (value instanceof Number number) {
						return number.doubleValue();
					}
				} catch (Throwable ignored) {
				}
			}
			return 0.0D;
		}

		private static void collectNestedRecipes(Object target, List<Object> output) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				for (Field field : type.getDeclaredFields()) {
					String fieldName = field.getName().toLowerCase(Locale.ROOT);
					String fieldType = field.getType().getName().toLowerCase(Locale.ROOT);
					if (!fieldName.contains("recipe") && !fieldType.contains("recipe")) {
						continue;
					}
					try {
						field.setAccessible(true);
						Object value = field.get(target);
						if (value != null && value != target) {
							output.add(value);
						}
					} catch (Throwable ignored) {
					}
				}
			}
		}
	}

	private static final class RecipeTimingResolver {
		private static final String[] DURATION_METHODS = {
			"getDuration", "duration", "getDurationTicks", "getRecipeDuration"
		};
		private static final String[] COOKING_METHODS = {
			"getCookingTime", "getCookTime"
		};

		private RecipeTimingResolver() {
		}

		private static double resolveDurationTicks(EmiRecipe recipe) {
			if (recipe == null) {
				return -1.0D;
			}
			try {
				Object backing = recipe.getBackingRecipe();
				double direct = readDuration(backing, true);
				if (direct > 0.0D) {
					return direct;
				}
				direct = walkRecipeObjects(backing);
				if (direct > 0.0D) {
					return direct;
				}
			} catch (Throwable ignored) {
			}
			return walkRecipeObjects(recipe);
		}

		private static double walkRecipeObjects(Object root) {
			if (root == null) {
				return -1.0D;
			}
			Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			List<Object> level = new ArrayList<>();
			level.add(root);
			for (int depth = 0; depth < 4 && !level.isEmpty(); depth++) {
				List<Object> next = new ArrayList<>();
				for (Object target : level) {
					if (target == null || !visited.add(target)) {
						continue;
					}
					double duration = readDuration(target, false);
					if (duration > 0.0D) {
						return duration;
					}
					collectNestedRecipes(target, next);
				}
				level = next;
			}
			return -1.0D;
		}

		private static double readDuration(Object target, boolean backingRecipe) {
			if (target == null) {
				return -1.0D;
			}
			Class<?> type = target.getClass();
			String className = type.getName().toLowerCase(Locale.ROOT);
			boolean gtRecipe = className.startsWith("com.gregtechceu.") && className.contains("recipe");
			if (gtRecipe || backingRecipe) {
				for (String name : DURATION_METHODS) {
					double value = invokeNumber(target, name);
					if (value > 0.0D) {
						return value;
					}
				}
				double field = readNumberField(target, "duration");
				if (field > 0.0D) {
					return field;
				}
			}
			if (className.contains("cooking") || className.contains("smelting") || className.contains("blasting")) {
				for (String name : COOKING_METHODS) {
					double value = invokeNumber(target, name);
					if (value > 0.0D) {
						return value;
					}
				}
			}
			return -1.0D;
		}

		private static double invokeNumber(Object target, String name) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Method method = type.getDeclaredMethod(name);
					if (method.getParameterCount() != 0) {
						continue;
					}
					method.setAccessible(true);
					Object value = method.invoke(target);
					if (value instanceof Number number) {
						return number.doubleValue();
					}
				} catch (Throwable ignored) {
				}
			}
			return -1.0D;
		}

		private static double readNumberField(Object target, String name) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Field field = type.getDeclaredField(name);
					field.setAccessible(true);
					Object value = field.get(target);
					if (value instanceof Number number) {
						return number.doubleValue();
					}
				} catch (Throwable ignored) {
				}
			}
			return -1.0D;
		}

		private static void collectNestedRecipes(Object target, List<Object> output) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				for (Field field : type.getDeclaredFields()) {
					String fieldName = field.getName().toLowerCase(Locale.ROOT);
					String fieldType = field.getType().getName().toLowerCase(Locale.ROOT);
					if (!fieldName.contains("recipe") && !fieldType.contains("recipe")) {
						continue;
					}
					try {
						field.setAccessible(true);
						Object value = field.get(target);
						if (value != null && value != target) {
							output.add(value);
						}
					} catch (Throwable ignored) {
					}
				}
			}
		}
	}
}
