package dev.emi.emi.planner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.runtime.EmiProductionPlannerPersistence;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ProductionPlanner {
	private static final double TICKS_PER_SECOND = 20.0D;
	private static final List<Line> LINES = new ArrayList<>();
	private static final Map<String, MachineRuntimeInfo> MACHINE_RUNTIME_CACHE = new LinkedHashMap<>();
	private static int activeIndex = -1;
	private static boolean loaded;

	private static final List<MachineProfile> BUILTIN_MACHINE_PROFILES = List.of(
		new MachineProfile("generic", "Generic GT", "Any recipe; manual fallback profile", "", 0, true, EmiStack.EMPTY),
		new MachineProfile("chemical_reactor", "Chemical Reactor", "Singleblock Chemical Reactor", "chemical reactor", 1, false, EmiStack.EMPTY),
		new MachineProfile("large_chemical_reactor", "Large Chemical Reactor", "Multiblock Chemical Reactor; exact parallel/coil rules are not modeled yet", "chemical reactor", 0, true, EmiStack.EMPTY),
		new MachineProfile("electrolyzer", "Electrolyzer", "Singleblock Electrolyzer", "electrolyzer", 1, false, EmiStack.EMPTY)
	);

	private static final List<String> STANDARD_COIL_NAMES = List.of(
		"Cupronickel", "Kanthal", "Nichrome", "RTM Alloy", "HSS-G", "Naquadah", "Trinium", "Tritanium"
	);

	private ProductionPlanner() {
	}

	public static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		LINES.clear();
		JsonObject root = EmiProductionPlannerPersistence.load();
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
				if (object.has("target")) {
					try {
						EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(object.get("target"));
						if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
							line.target = normalizeStack(stack);
						}
					} catch (Throwable ignored) {
					}
				}
				if (object.has("target_rate")) {
					line.targetRate = sanitizeTargetRate(object.get("target_rate").getAsDouble());
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
							if (!entry.voltageOverride) {
								applyLineStandardVoltage(line, entry);
							}
							line.entries.add(entry);
						} catch (Throwable ignored) {
						}
					}
				}
				if (line.balanceEnabled && (line.target == null || line.target.isEmpty())) {
					line.balanceEnabled = false;
				}
				if (line.balanceEnabled && line.achievedTargetRate <= 0.0D) {
					line.achievedTargetRate = line.targetRate;
				}
				line.balanceMessage = line.balanceEnabled
					? (line.hasMachineCapacityShortfall()
						? buildBottleneckMessage(line)
						: "Balanced rates restored")
					: "";
				LINES.add(line);
			}
		}
		if (!LINES.isEmpty()) {
			int requested = root.has("active") ? root.get("active").getAsInt() : 0;
			activeIndex = Math.max(0, Math.min(requested, LINES.size() - 1));
		}
	}

	public static synchronized boolean addRecipe(EmiRecipe recipe) {
		ensureLoaded();
		if (recipe == null || recipe.getId() == null) {
			return false;
		}
		Line line = getOrCreateActiveLine();
		for (Entry entry : line.entries) {
			if (entry.recipeId.equals(recipe.getId())) {
				invalidateBalance(line);
				if (entry.automatic && entry.getDurationTicks() > 0.0D) {
					entry.machines = sanitizeCount(entry.machines + 1);
				} else {
					entry.rate = sanitizeRate(entry.rate + 1.0D);
				}
				save();
				return true;
			}
		}
		Entry entry = new Entry(recipe.getId(), 1.0D, false, 1, 1, 0.0D, 0.0D, "generic", -1, false, OcMode.STANDARD, 0, false, false);
		applyLineStandardVoltage(line, entry);
		entry.automatic = entry.getDetectedDurationTicks() > 0.0D;
		line.entries.add(entry);
		invalidateBalance(line);
		save();
		return true;
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
			return "Line";
		}
		String custom = LINES.get(index).name;
		return custom == null || custom.isBlank() ? "Line " + (index + 1) : custom;
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

	public static synchronized void setBalanceTarget(Line line, EmiStack stack, double defaultRate) {
		if (line == null || stack == null || stack.isEmpty()) {
			return;
		}
		line.target = normalizeStack(stack);
		line.targetRate = sanitizeTargetRate(defaultRate);
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = "Target selected. Press BALANCE.";
		save();
	}

	public static synchronized void setTargetRate(Line line, double rate) {
		if (line == null) {
			return;
		}
		line.targetRate = sanitizeTargetRate(rate);
		line.balanceEnabled = false;
		line.achievedTargetRate = 0.0D;
		line.bottleneckName = "";
		line.balanceMessage = "Target rate changed. Press BALANCE.";
		save();
	}

	public static synchronized void clearBalanceTarget(Line line) {
		if (line == null) {
			return;
		}
		line.target = EmiStack.EMPTY;
		line.targetRate = 1.0D;
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
			line.balanceMessage = "Balance disabled";
			save();
		}
	}

	public static synchronized BalanceResult balanceLine(Line line) {
		if (line == null || line.target == null || line.target.isEmpty()) {
			return failBalance(line, "Choose a target output first");
		}
		if (line.entries.isEmpty()) {
			return failBalance(line, "Line has no recipes");
		}
		int n = line.entries.size();
		Map<EmiStack, ResourceVector> resources = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			EmiRecipe recipe = line.entries.get(i).getRecipe();
			if (recipe == null) {
				return failBalance(line, "A recipe is missing");
			}
			for (EmiIngredient ingredient : recipe.getInputs()) {
				if (ingredient == null || ingredient.isEmpty()) {
					continue;
				}
				EmiStack stack = firstStack(ingredient);
				if (stack == null || stack.isEmpty()) {
					continue;
				}
				double amount = ingredient.getAmount() * Math.max(0.0D, ingredient.getChance());
				ResourceVector vector = resources.computeIfAbsent(normalizeStack(stack), k -> new ResourceVector(k, n));
				vector.net[i] -= amount;
				vector.hasInput = true;
			}
			for (EmiStack stack : recipe.getOutputs()) {
				if (stack == null || stack.isEmpty()) {
					continue;
				}
				double amount = stack.getAmount() * Math.max(0.0D, stack.getChance());
				ResourceVector vector = resources.computeIfAbsent(normalizeStack(stack), k -> new ResourceVector(k, n));
				vector.net[i] += amount;
				vector.hasOutput = true;
			}
		}
		ResourceVector targetVector = resources.get(normalizeStack(line.target));
		if (targetVector == null || maxPositive(targetVector.net) <= 0.0D) {
			return failBalance(line, "No recipe in this line produces the target");
		}

		List<double[]> rows = new ArrayList<>();
		List<Double> rhs = new ArrayList<>();
		List<ResourceVector> internalVectors = new ArrayList<>();
		for (ResourceVector vector : resources.values()) {
			if (sameStack(vector.stack, line.target) || !vector.hasInput || !vector.hasOutput) {
				continue;
			}
			double scale = maxAbs(vector.net);
			if (scale <= 0.0D) {
				continue;
			}
			double[] row = new double[n];
			for (int i = 0; i < n; i++) {
				row[i] = vector.net[i] / scale;
			}
			rows.add(row);
			rhs.add(0.0D);
			internalVectors.add(vector);
		}
		double targetScale = maxAbs(targetVector.net);
		double[] targetRow = new double[n];
		for (int i = 0; i < n; i++) {
			targetRow[i] = targetVector.net[i] / targetScale * 12.0D;
		}
		rows.add(targetRow);
		rhs.add(line.targetRate / targetScale * 12.0D);

		double[][] a = rows.toArray(double[][]::new);
		double[] b = new double[rhs.size()];
		for (int i = 0; i < b.length; i++) {
			b[i] = rhs.get(i);
		}
		double[] x = nonNegativeLeastSquares(a, b, n);
		double targetNet = dot(targetVector.net, x);
		if (!Double.isFinite(targetNet) || targetNet <= 0.0D) {
			return failBalance(line, "Could not build a positive target flow");
		}
		double scaleToTarget = line.targetRate / targetNet;
		for (int i = 0; i < x.length; i++) {
			x[i] = sanitizeBalanceRate(x[i] * scaleToTarget);
		}
		targetNet = dot(targetVector.net, x);
		double maxResidual = 0.0D;
		for (ResourceVector vector : internalVectors) {
			double denom = Math.max(1.0D, maxAbs(vector.net));
			maxResidual = Math.max(maxResidual, Math.abs(dot(vector.net, x)) / denom);
		}
		for (int i = 0; i < n; i++) {
			line.entries.get(i).balanceRate = x[i];
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
			line.achievedTargetRate = sanitizeAchievedTargetRate(line.targetRate * propagationScale);
			line.bottleneckName = bottleneck.label();
			targetNet = dotBalance(targetVector, line);
			maxResidual = computeMaxResidual(internalVectors, line);
			sizingStatus = autoSizeBalancedLine(line);
		} else {
			line.achievedTargetRate = line.targetRate;
			line.bottleneckName = "";
		}
		line.balanceEnabled = true;
		line.balanceMessage = buildBalancedMessage(line, maxResidual, sizingStatus);
		save();
		return new BalanceResult(true, line.balanceMessage, targetNet, maxResidual);
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
			? "Balanced to target"
			: "Balanced with residual " + formatSolverNumber(maxResidual);
		if (status.insufficient() > 0) {
			return flow + "; machine constraints still report a capacity shortfall";
		}
		if (status.unavailable() > 0) {
			return flow + "; some machine sizing unavailable";
		}
		return flow + "; machines sized";
	}

	private static String buildBottleneckMessage(Line line) {
		if (line == null) {
			return "Machine bottleneck limits line throughput";
		}
		String bottleneck = line.bottleneckName == null || line.bottleneckName.isBlank()
			? "fixed machine setup"
			: line.bottleneckName;
		return "Requested: " + formatSolverNumber(line.targetRate) + "/s | Achievable: "
			+ formatSolverNumber(line.achievedTargetRate) + "/s | Bottleneck: " + bottleneck;
	}

	private static BottleneckPropagation findBottleneckPropagation(Line line) {
		if (line == null || line.targetRate <= 0.0D) {
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
		entry.machineProfileId = profile.id();
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
			? "EMI/GTO workstation; detected runtime machine properties are applied"
			: "EMI/GTO workstation; unknown bonuses use Generic GT math";
		return MachineProfile.runtime(id, displayName, description, icon, runtime);
	}

	private static int clampParallelForProfile(Entry entry, int parallel) {
		MachineProfile profile = getMachineProfile(entry);
		int limit = profile.maxParallel();
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

	public static synchronized void save() {
		ensureLoaded();
		JsonObject root = new JsonObject();
		JsonArray lines = new JsonArray();
		for (Line line : LINES) {
			JsonObject lineObject = new JsonObject();
			if (line.name != null && !line.name.isBlank()) {
				lineObject.addProperty("name", line.name);
			}
			if (line.target != null && !line.target.isEmpty()) {
				lineObject.add("target", EmiIngredientSerializer.getSerialized(normalizeStack(line.target)));
				lineObject.addProperty("target_rate", line.targetRate);
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
			JsonArray entries = new JsonArray();
			for (Entry entry : line.entries) {
				JsonObject entryObject = new JsonObject();
				entryObject.addProperty("recipe", entry.recipeId.toString());
				entryObject.addProperty("rate", entry.rate);
				entryObject.addProperty("mode", entry.automatic ? "auto" : "manual");
				entryObject.addProperty("machines", entry.machines);
				entryObject.addProperty("parallel", entry.parallel);
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
		EmiProductionPlannerPersistence.save(root);
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
		line.balanceMessage = line.target != null && !line.target.isEmpty() ? "Line changed. Press BALANCE." : "";
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

	private static double dot(double[] a, double[] b) {
		double value = 0.0D;
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			value += a[i] * b[i];
		}
		return value;
	}

	private static double[] nonNegativeLeastSquares(double[][] a, double[] b, int columns) {
		double[] x = new double[columns];
		double[] residual = new double[b.length];
		for (int r = 0; r < b.length; r++) {
			residual[r] = -b[r];
		}
		for (int pass = 0; pass < 6000; pass++) {
			double maxDelta = 0.0D;
			for (int c = 0; c < columns; c++) {
				double norm = 0.0D;
				double grad = 0.0D;
				for (int r = 0; r < a.length; r++) {
					double v = a[r][c];
					norm += v * v;
					grad += v * residual[r];
				}
				if (norm <= 1.0E-18D) {
					continue;
				}
				double next = Math.max(0.0D, x[c] - grad / norm);
				double delta = next - x[c];
				if (Math.abs(delta) > 0.0D) {
					x[c] = next;
					for (int r = 0; r < a.length; r++) {
						residual[r] += a[r][c] * delta;
					}
					maxDelta = Math.max(maxDelta, Math.abs(delta));
				}
			}
			if (maxDelta < 1.0E-10D) {
				break;
			}
		}
		return x;
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

	public record MachineProfile(String id, String displayName, String description, String categoryNeedle, int maxParallel,
			boolean allowsPerfectOc, EmiStack icon, int fixedVoltageTier, boolean perfectOcKnown,
			double durationMultiplier, double energyMultiplier, boolean parallelControl,
			double coilEfficiencyPerTier, List<String> runtimeNotes) {
		public MachineProfile(String id, String displayName, String description, String categoryNeedle, int maxParallel,
				boolean allowsPerfectOc, EmiStack icon) {
			this(id, displayName, description, categoryNeedle, maxParallel, allowsPerfectOc, icon, -1, false,
				1.0D, 1.0D, false, 0.0D, List.of());
		}

		private static MachineProfile runtime(String id, String displayName, String description, EmiStack icon,
				MachineRuntimeInfo runtime) {
			return new MachineProfile(id, displayName, description, "", runtime.maxParallel(), runtime.allowsPerfectOc(), icon,
				runtime.fixedVoltageTier(), runtime.perfectOcKnown(), runtime.durationMultiplier(), runtime.energyMultiplier(),
				runtime.parallelControl(), runtime.coilEfficiencyPerTier(), runtime.notes());
		}

		private MachineProfile withRuntime(String name, EmiStack runtimeIcon, MachineRuntimeInfo runtime) {
			int detectedParallel = runtime.maxParallel() > 0 ? runtime.maxParallel() : maxParallel;
			boolean detectedPerfect = runtime.perfectOcKnown() ? runtime.allowsPerfectOc() : allowsPerfectOc;
			return new MachineProfile(id, name, description, "", detectedParallel, detectedPerfect, runtimeIcon,
				runtime.fixedVoltageTier(), runtime.perfectOcKnown(), runtime.durationMultiplier(), runtime.energyMultiplier(),
				runtime.parallelControl(), runtime.coilEfficiencyPerTier(), runtime.notes());
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
				|| coilEfficiencyPerTier > 0.0D || !runtimeNotes.isEmpty();
		}

		public boolean hasConfigurableSettings() {
			return coilEfficiencyPerTier > 0.0D || parallelControl;
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
			lines.addAll(runtimeNotes);
			return List.copyOf(lines);
		}

		public boolean hasIcon() {
			return icon != null && !icon.isEmpty();
		}
	}

	private record MachineRuntimeInfo(int fixedVoltageTier, int maxParallel, boolean perfectOcKnown,
			boolean allowsPerfectOc, double durationMultiplier, double energyMultiplier,
			boolean parallelControl, double coilEfficiencyPerTier, boolean modeled, List<String> notes) {
	}

	public static final class Line {
		private String name;
		private final List<Entry> entries = new ArrayList<>();
		private EmiStack target = EmiStack.EMPTY;
		private double targetRate = 1.0D;
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

		public EmiStack getTarget() {
			return target;
		}

		public double getTargetRate() {
			return targetRate;
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
				&& achievedTargetRate + Math.max(1.0E-9D, targetRate * 1.0E-9D) < targetRate;
		}

		public double getAchievableTargetRate() {
			return balanceEnabled && achievedTargetRate > 0.0D ? achievedTargetRate : targetRate;
		}

		public String getBottleneckName() {
			return bottleneckName == null ? "" : bottleneckName;
		}
	}

	public record BalanceResult(boolean success, String message, double targetNet, double maxInternalResidual) {
	}

	private record MachineSizingStatus(int active, int unavailable, int insufficient) {
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
			return new MachineSizing(false, false, 1, 1, 0.0D, 1.0D, 0.0D, 0.0D, "Machine sizing unavailable");
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
		private boolean machinesFixed;
		private boolean parallelFixed;
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
			long baseEUt = getRecipeEUt();
			double duration = getDurationTicks();
			long selectedVoltage = getSelectedVoltage();
			if (baseEUt <= 0L || duration <= 0.0D || selectedVoltage <= 0L || ocMode == OcMode.NONE) {
				return 0;
			}
			long eut = baseEUt;
			int overclocks = 0;
			while (overclocks < 32 && duration > 1.0D && eut <= selectedVoltage / 4L) {
				eut = multiplyByFourSaturated(eut);
				duration = Math.max(1.0D, duration / ocMode.durationDivisor());
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
				duration = Math.max(1.0D, duration / ocMode.durationDivisor());
			}
			duration = Math.max(1.0D, duration * getMachineProfile().durationMultiplier() * getCoilMultiplier());
			return duration;
		}

		public double getProcessedDurationSeconds() {
			double ticks = getProcessedDurationTicks();
			return ticks > 0.0D ? ticks / TICKS_PER_SECOND : -1.0D;
		}

		public long getProcessedEUt() {
			long eut = getRecipeEUt();
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
			double seconds = getProcessedDurationSeconds();
			double required = getRequiredEffectiveParallel(craftsPerSecond);
			if (seconds <= 0.0D || !Double.isFinite(required) || required <= 0.0D) {
				return MachineSizing.unavailable();
			}
			MachineProfile profile = getMachineProfile();
			int machines;
			int parallel;
			boolean exact;
			String note;
			int maxParallel = profile.maxParallel();

			if (machinesFixed && parallelFixed) {
				machines = sanitizeCount(this.machines);
				parallel = clampParallelForProfile(this, sanitizeCount(this.parallel));
				exact = maxParallel > 0;
				note = "MACH and PAR are fixed by the user";
			} else if (machinesFixed) {
				machines = sanitizeCount(this.machines);
				if (maxParallel > 0) {
					parallel = Math.min(maxParallel, ceilCount(required / machines));
					exact = true;
					note = "MACH is fixed; PAR is sized up to detected max parallel " + maxParallel;
				} else if (profile.parallelControl() || "generic".equals(profile.id())) {
					parallel = ceilCount(required / machines);
					exact = false;
					note = "MACH is fixed; PAR is provisional because the machine parallel limit is unknown";
				} else {
					parallel = 1;
					exact = false;
					note = "MACH is fixed; unknown machine parallel limit is treated conservatively as 1";
				}
			} else if (parallelFixed) {
				parallel = clampParallelForProfile(this, sanitizeCount(this.parallel));
				machines = ceilCount(required / parallel);
				exact = maxParallel > 0;
				note = maxParallel > 0
					? "PAR is fixed; MACH is sized from the fixed parallel"
					: "PAR is fixed by the user; MACH sizing is provisional because the profile parallel limit is unknown";
			} else if (maxParallel > 0) {
				machines = ceilCount(required / maxParallel);
				parallel = Math.min(maxParallel, ceilCount(required / machines));
				exact = true;
				note = "Sized from detected max parallel " + maxParallel + " per machine";
			} else if (profile.parallelControl()) {
				machines = 1;
				parallel = ceilCount(required);
				exact = false;
				note = "Parallel Control detected, but its maximum parallel is unknown";
			} else if ("generic".equals(profile.id())) {
				machines = 1;
				parallel = ceilCount(required);
				exact = false;
				note = "Generic profile assumes the requested effective parallel can be supplied";
			} else {
				machines = ceilCount(required);
				parallel = 1;
				exact = false;
				note = "Parallel limit is unknown; conservative 1 parallel per machine sizing";
			}
			double installed = machines * (double) parallel;
			double capacityRate = installed / seconds;
			double headroom = craftsPerSecond > 0.0D
				? Math.max(0.0D, (capacityRate / craftsPerSecond - 1.0D) * 100.0D)
				: 0.0D;
			return new MachineSizing(true, exact, machines, parallel, required, installed, capacityRate, headroom, note);
		}

		public double getEffectiveRate() {
			if (automatic) {
				double ticks = getProcessedDurationTicks();
				if (ticks > 0.0D) {
					return (machines * (double) parallel * TICKS_PER_SECOND) / ticks;
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
					parallelControl = !isNegativeFeatureLine(lower);
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
			boolean tieredSingleblock = fixedVoltageTier >= 0 && !parallelControl;
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
				|| Math.abs(durationMultiplier - 1.0D) > 0.0000001D || Math.abs(energyMultiplier - 1.0D) > 0.0000001D;
			return new MachineRuntimeInfo(fixedVoltageTier, maxParallel, perfectKnown, allowsPerfect,
				durationMultiplier, energyMultiplier, parallelControl, coilEfficiency, modeled, List.copyOf(notes));
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
