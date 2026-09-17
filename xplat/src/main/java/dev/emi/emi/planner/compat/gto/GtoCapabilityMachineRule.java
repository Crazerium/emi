package dev.emi.emi.planner.compat.gto;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.planner.PlannerText;
import dev.emi.emi.planner.ProductionPlanner;
import dev.emi.emi.planner.ProductionPlanner.Entry;
import dev.emi.emi.planner.ProductionPlanner.MachineProfile;
import dev.emi.emi.planner.ProductionPlanner.MachineSettingSpec;
import dev.emi.emi.planner.compat.PlannerMachineRule;
import dev.emi.emi.planner.compat.gtceu.GtceuHeatingCoilCatalog;

final class GtoCapabilityMachineRule implements PlannerMachineRule {
	private static final String GLASS_TIER = "glass_tier";
	private static final String CASING_TIER = "machine_casing_tier";
	private static final String COIL_PARALLEL = "gto_coil_parallel_coil";
	private final GtoMachineCapabilities capabilities;

	GtoCapabilityMachineRule(GtoMachineCapabilities capabilities) {
		this.capabilities = capabilities == null ? GtoMachineCapabilities.EMPTY : capabilities;
	}

	@Override
	public String id() {
		return "gto:auto-capabilities";
	}

	@Override
	public List<MachineSettingSpec> settings(MachineProfile profile) {
		List<MachineSettingSpec> settings = new ArrayList<>();
		if (capabilities.glassParallelBase() > 1) {
			settings.add(MachineSettingSpec.integer(
				GLASS_TIER, "gto.precision.glass_tier", "Glass Tier", 0, 15, 0, 1,
				"gto.precision.glass_tier_help", "Built-in parallel is determined by the detected glass-tier formula"));
		}
		if (capabilities.casingTierLimit()) {
			List<String> casingChoices = new ArrayList<>();
			casingChoices.add("AUTO");
			for (int tier = 0; tier <= ProductionPlanner.maxVoltageTier(); tier++) {
				casingChoices.add(ProductionPlanner.voltageTierName(tier));
			}
			settings.add(MachineSettingSpec.choice(
				CASING_TIER, "gto.precision.casing_tier", "Machine Casing Tier", casingChoices, 0,
				"gto.precision.casing_tier_help", "Recipe tier cannot exceed the selected Machine Casing Tier; AUTO uses the recipe minimum"));
		}
		if (capabilities.hasCoilParallel()) {
			settings.add(GtceuHeatingCoilCatalog.spec(
				COIL_PARALLEL, "gto.auto.coil_parallel", "Heating Coil",
				"gto.auto.coil_parallel_help", "Installed heating coil controls the machine's detected built-in parallel formula"));
		}
		if (capabilities.auxiliaryModules() && (capabilities.accelerationRequiresAuxiliary()
				|| capabilities.threadRequiresAuxiliary() || capabilities.overclockingRequiresAuxiliary())) {
			settings.add(GtoHatchCatalog.auxiliaryModulesSpec());
		}
		if (capabilities.parallelControl()) {
			settings.add(GtoHatchCatalog.parallelHatchLimitSpec());
		}
		if (capabilities.acceleration()) {
			settings.add(GtoHatchCatalog.accelerationTierSpec());
			settings.add(GtoHatchCatalog.accelerationMultiplierSpec());
		}
		if (capabilities.thread()) {
			settings.add(GtoHatchCatalog.threadTierSpec());
			settings.add(GtoHatchCatalog.threadCountSpec());
		}
		if (capabilities.overclocking()) {
			settings.add(GtoHatchCatalog.overclockTierSpec());
			settings.add(GtoHatchCatalog.overclockDivisorSpec());
		}
		return List.copyOf(settings);
	}

	@Override
	public int configuredMaxParallel(Entry entry, int fallback) {
		int value = fallback;
		if (capabilities.glassParallelBase() > 1) {
			MachineSettingSpec spec = setting(entry, GLASS_TIER);
			if (spec != null) {
				value = saturatedPower(capabilities.glassParallelBase(), entry.getMachineSettingValue(spec));
			}
		}
		if (capabilities.hasCoilParallel()) {
			MachineSettingSpec spec = setting(entry, COIL_PARALLEL);
			if (spec != null) {
				int choice = entry.getMachineSettingValue(spec);
				int temperature = GtceuHeatingCoilCatalog.temperatureForChoice(choice);
				if (temperature > 0) {
					int steps = Math.max(0, temperature / capabilities.coilParallelStepKelvin());
					value = saturatedPower(capabilities.coilParallelFactor(), steps);
				}
			}
		}
		if (capabilities.parallelControl()) {
			int hatchLimit = GtoHatchCatalog.parallelLimit(entry);
			if (hatchLimit > 0) {
				value = hatchLimit;
			}
		}
		return value;
	}

	@Override
	public boolean allowsRecipe(Entry entry) {
		if (entry == null) {
			return false;
		}
		if (capabilities.casingTierLimit()) {
			int casingTier = configuredCasingTier(entry);
			int recipeTier = entry.getRecipeTier();
			if (casingTier >= 0 && recipeTier >= 0 && recipeTier > casingTier) {
				return false;
			}
		}
		return !invalidAuxiliaryConfiguration(entry);
	}

	@Override
	public String constraintError(Entry entry) {
		if (entry == null) {
			return "";
		}
		if (capabilities.casingTierLimit()) {
			int casingTier = configuredCasingTier(entry);
			int recipeTier = entry.getRecipeTier();
			if (casingTier >= 0 && recipeTier >= 0 && recipeTier > casingTier) {
				return PlannerText.tr("gto.precision.casing_tier_low", "Machine Casing Tier is too low") + ": "
					+ entry.getMachineProfile().displayName() + " (" + ProductionPlanner.voltageTierName(casingTier)
					+ " < " + ProductionPlanner.voltageTierName(recipeTier) + ")";
			}
		}
		if (invalidAuxiliaryConfiguration(entry)) {
			return PlannerText.tr("gto.hatch.auxiliary_required_error", "Selected GTO hatch requires Auxiliary Modules")
				+ ": " + entry.getMachineProfile().displayName();
		}
		return "";
	}

	@Override
	public double durationMultiplier(Entry entry) {
		if (capabilities.acceleration() && accelerationUsable(entry)) {
			return GtoHatchCatalog.accelerationDurationMultiplier(entry);
		}
		return 1.0D;
	}

	@Override
	public double standardOcDurationMultiplier(Entry entry, double fallback) {
		if (capabilities.overclocking() && overclockingUsable(entry)) {
			return GtoHatchCatalog.overclockDurationMultiplier(entry, fallback);
		}
		return fallback;
	}

	@Override
	public double throughputMultiplier(Entry entry) {
		if (capabilities.thread() && threadUsable(entry)) {
			return GtoHatchCatalog.effectiveThreads(entry);
		}
		return 1.0D;
	}

	@Override
	public List<String> settingDetails(Entry entry, MachineSettingSpec spec) {
		if (entry == null || spec == null) {
			return List.of();
		}
		return switch (spec.key()) {
			case GLASS_TIER -> glassDetails(entry);
			case CASING_TIER -> casingDetails(entry);
			case COIL_PARALLEL -> coilParallelDetails(entry);
			case GtoHatchCatalog.AUXILIARY_MODULES -> List.of(
				PlannerText.tr("gto.hatch.auxiliary_state", "Auxiliary Modules enabled") + ": "
					+ yesNo(GtoHatchCatalog.auxiliaryEnabled(entry)));
			case GtoHatchCatalog.PARALLEL_HATCH_LIMIT -> parallelDetails(entry);
			case GtoHatchCatalog.ACCELERATION_HATCH_TIER, GtoHatchCatalog.ACCELERATION_MULTIPLIER -> accelerationDetails(entry);
			case GtoHatchCatalog.THREAD_HATCH_TIER, GtoHatchCatalog.THREAD_COUNT -> threadDetails(entry);
			case GtoHatchCatalog.OVERCLOCK_HATCH_TIER, GtoHatchCatalog.OVERCLOCK_DIVISOR -> overclockDetails(entry);
			default -> List.of();
		};
	}

	@Override
	public List<String> modifierDescriptions(MachineProfile profile) {
		List<String> lines = new ArrayList<>();
		if (capabilities.standardOcDurationMultiplier() > 0.0D) {
			lines.add("GTO auto-detected special standard OC time multiplier: "
				+ Math.round(capabilities.standardOcDurationMultiplier() * 10000.0D) / 100.0D + "%");
		}
		if (capabilities.glassParallelBase() > 1) {
			lines.add("GTO auto-detected glass-tier built-in parallel formula: " + capabilities.glassParallelBase() + "^(Glass Tier)");
		}
		if (capabilities.casingTierLimit()) {
			lines.add("GTO auto-detected recipe-tier limit from machine casing tier");
		}
		if (capabilities.hasCoilParallel()) {
			lines.add("GTO auto-detected coil parallel: x" + capabilities.coilParallelFactor() + " per "
				+ capabilities.coilParallelStepKelvin() + "K");
		}
		if (capabilities.parallelControl()) {
			lines.add("GTO/GTM Parallel Control Hatch detected automatically");
		}
		if (capabilities.acceleration()) {
			lines.add("GTO Acceleration Hatch detected automatically");
		}
		if (capabilities.thread()) {
			lines.add("GTO Thread Hatch detected automatically");
		}
		if (capabilities.overclocking()) {
			lines.add("GTO Overclocking Hatch detected automatically");
		}
		if (capabilities.laserEnergy()) {
			lines.add("GTO Laser Energy Hatch support detected");
		}
		return List.copyOf(lines);
	}

	private List<String> glassDetails(Entry entry) {
		return List.of(
			PlannerText.tr("gto.precision.current_parallel", "Current built-in parallel") + ": "
				+ configuredMaxParallel(entry, entry.getMachineProfile().maxParallel()),
			PlannerText.tr("gto.auto.detected_formula", "Detected formula") + ": "
				+ capabilities.glassParallelBase() + "^(Glass Tier)"
		);
	}

	private List<String> casingDetails(Entry entry) {
		int recipeTier = entry.getRecipeTier();
		return List.of(
			PlannerText.tr("gto.precision.recipe_tier", "Recipe Tier") + ": "
				+ (recipeTier < 0 ? "--" : ProductionPlanner.voltageTierName(recipeTier)),
			PlannerText.tr("gto.precision.recipe_allowed", "Recipe allowed") + ": " + yesNo(allowsRecipe(entry))
		);
	}

	private List<String> coilParallelDetails(Entry entry) {
		MachineSettingSpec spec = setting(entry, COIL_PARALLEL);
		int choice = spec == null ? 0 : entry.getMachineSettingValue(spec);
		int temperature = GtceuHeatingCoilCatalog.temperatureForChoice(choice);
		if (temperature <= 0) {
			return List.of(PlannerText.tr("gto.auto.coil_parallel_auto", "AUTO keeps the detected/profile parallel limit"));
		}
		int steps = temperature / capabilities.coilParallelStepKelvin();
		return List.of(
			PlannerText.tr("gto.auto.coil_temperature", "Coil temperature") + ": " + temperature + "K",
			PlannerText.tr("gto.auto.parallel_steps", "Parallel formula steps") + ": " + steps,
			PlannerText.tr("gto.precision.current_parallel", "Current built-in parallel") + ": "
				+ configuredMaxParallel(entry, entry.getMachineProfile().maxParallel()),
			PlannerText.tr("gto.auto.detected_formula", "Detected formula") + ": " + capabilities.coilParallelFactor()
				+ "^floor(T / " + capabilities.coilParallelStepKelvin() + "K)"
		);
	}

	private List<String> parallelDetails(Entry entry) {
		int selected = GtoHatchCatalog.parallelLimit(entry);
		int current = configuredMaxParallel(entry, entry.getMachineProfile().maxParallel());
		List<String> lines = new ArrayList<>();
		lines.add(PlannerText.tr("gto.hatch.current_max_parallel", "Current hatch max parallel") + ": "
			+ (current > 0 ? current : PlannerText.tr("gto.hatch.unknown", "unknown")));
		if (selected <= 0) {
			lines.add(PlannerText.tr("gto.hatch.auto_parallel", "AUTO keeps the detected/profile parallel limit"));
		}
		lines.add(PlannerText.tr("gto.hatch.row_parallel", "Row PAR remains the currently configured hatch parallel"));
		return List.copyOf(lines);
	}

	private List<String> accelerationDetails(Entry entry) {
		if (!GtoHatchCatalog.accelerationSelected(entry)) {
			return List.of(PlannerText.tr("gto.hatch.disabled", "Hatch disabled"));
		}
		int tier = GtoHatchCatalog.accelerationTier(entry);
		int recipeTier = entry.getRecipeTier();
		int missing = recipeTier < 0 ? 0 : Math.max(0, recipeTier - tier);
		List<String> lines = new ArrayList<>();
		lines.add(PlannerText.tr("gto.hatch.min_duration", "Minimum base duration percentage for this tier") + ": "
			+ GtoHatchCatalog.accelerationMinimumPercent(tier) + "%");
		lines.add(PlannerText.tr("gto.hatch.effective_duration", "Effective duration multiplier") + ": "
			+ GtoHatchCatalog.accelerationEffectivePercent(entry) + "%");
		lines.add(PlannerText.tr("gto.hatch.recipe_tier", "Recipe tier") + ": "
			+ (recipeTier < 0 ? "--" : ProductionPlanner.voltageTierName(recipeTier)));
		lines.add(PlannerText.tr("gto.hatch.missing_tiers", "Missing hatch tiers") + ": " + missing);
		if (capabilities.accelerationRequiresAuxiliary()) {
			lines.add(PlannerText.tr("gto.hatch.requires_auxiliary", "Requires Auxiliary Modules") + ": "
				+ yesNo(GtoHatchCatalog.auxiliaryEnabled(entry)));
		}
		return List.copyOf(lines);
	}

	private List<String> threadDetails(Entry entry) {
		if (!GtoHatchCatalog.threadSelected(entry)) {
			return List.of(PlannerText.tr("gto.hatch.disabled", "Hatch disabled"));
		}
		List<String> lines = new ArrayList<>();
		lines.add(PlannerText.tr("gto.hatch.max_threads", "Maximum threads for selected hatch") + ": "
			+ GtoHatchCatalog.maxThreads(entry));
		lines.add(PlannerText.tr("gto.hatch.effective_threads", "Effective recipe threads") + ": "
			+ GtoHatchCatalog.effectiveThreads(entry));
		if (capabilities.threadRequiresAuxiliary()) {
			lines.add(PlannerText.tr("gto.hatch.requires_auxiliary", "Requires Auxiliary Modules") + ": "
				+ yesNo(GtoHatchCatalog.auxiliaryEnabled(entry)));
		}
		return List.copyOf(lines);
	}

	private List<String> overclockDetails(Entry entry) {
		if (!GtoHatchCatalog.overclockSelected(entry)) {
			return List.of(PlannerText.tr("gto.hatch.disabled", "Hatch disabled"));
		}
		double base = entry.getMachineProfile().standardOcDurationMultiplier();
		double effective = GtoHatchCatalog.overclockDurationMultiplier(entry, base);
		List<String> lines = new ArrayList<>();
		lines.add(PlannerText.tr("gto.hatch.max_oc_divisor", "Selected hatch maximum OC time divisor") + ": /"
			+ GtoHatchCatalog.overclockMaxDivisor(entry));
		lines.add(PlannerText.tr("gto.hatch.effective_oc_divisor", "Effective OC time divisor") + ": /"
			+ GtoHatchCatalog.overclockEffectiveDivisor(entry));
		lines.add(PlannerText.tr("gto.hatch.base_oc_multiplier", "Machine base standard OC time multiplier") + ": "
			+ formatPercent(base));
		lines.add(PlannerText.tr("gto.hatch.effective_oc_multiplier", "Effective standard OC time multiplier") + ": "
			+ formatPercent(effective));
		if (capabilities.overclockingRequiresAuxiliary()) {
			lines.add(PlannerText.tr("gto.hatch.requires_auxiliary", "Requires Auxiliary Modules") + ": "
				+ yesNo(GtoHatchCatalog.auxiliaryEnabled(entry)));
		}
		return List.copyOf(lines);
	}

	private boolean invalidAuxiliaryConfiguration(Entry entry) {
		if (!capabilities.auxiliaryModules() || GtoHatchCatalog.auxiliaryEnabled(entry)) {
			return false;
		}
		return capabilities.accelerationRequiresAuxiliary() && GtoHatchCatalog.accelerationSelected(entry)
			|| capabilities.threadRequiresAuxiliary() && GtoHatchCatalog.threadSelected(entry)
			|| capabilities.overclockingRequiresAuxiliary() && GtoHatchCatalog.overclockSelected(entry);
	}

	private boolean accelerationUsable(Entry entry) {
		return GtoHatchCatalog.accelerationSelected(entry)
			&& (!capabilities.accelerationRequiresAuxiliary() || GtoHatchCatalog.auxiliaryEnabled(entry));
	}

	private boolean threadUsable(Entry entry) {
		return GtoHatchCatalog.threadSelected(entry)
			&& (!capabilities.threadRequiresAuxiliary() || GtoHatchCatalog.auxiliaryEnabled(entry));
	}

	private boolean overclockingUsable(Entry entry) {
		return GtoHatchCatalog.overclockSelected(entry)
			&& (!capabilities.overclockingRequiresAuxiliary() || GtoHatchCatalog.auxiliaryEnabled(entry));
	}

	private int configuredCasingTier(Entry entry) {
		MachineSettingSpec casing = setting(entry, CASING_TIER);
		if (casing == null) {
			return -1;
		}
		int value = entry.getMachineSettingValue(casing);
		return value <= 0 ? -1 : value - 1;
	}

	private MachineSettingSpec setting(Entry entry, String key) {
		if (entry == null) {
			return null;
		}
		for (MachineSettingSpec spec : settings(entry.getMachineProfile())) {
			if (key.equals(spec.key())) {
				return spec;
			}
		}
		return null;
	}

	private static int saturatedPower(int base, int exponent) {
		long value = 1L;
		for (int i = 0; i < Math.max(0, exponent); i++) {
			value *= Math.max(1, base);
			if (value >= 1_000_000_000L) {
				return 1_000_000_000;
			}
		}
		return (int) value;
	}

	private static String formatPercent(double multiplier) {
		return String.format(java.util.Locale.ROOT, "%.2f%%", multiplier * 100.0D);
	}

	private static String yesNo(boolean value) {
		return value
			? PlannerText.tr("gto.common.yes", "YES")
			: PlannerText.tr("gto.common.no", "NO");
	}
}
