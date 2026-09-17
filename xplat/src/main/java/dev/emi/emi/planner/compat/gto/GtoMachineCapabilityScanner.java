package dev.emi.emi.planner.compat.gto;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.planner.compat.PlannerMachineIntrospection;

final class GtoMachineCapabilityScanner {
	private static final Pattern PROCESSING_TIME = Pattern.compile("(?i)processing\\s+time\\s+multiplier\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)");
	private static final Pattern FIXED_PARALLEL = Pattern.compile("(?i)^\\s*(?:[-•]\\s*)?parallels?\\s*[:=]\\s*([0-9][0-9,._]*)\\s*$");
	private static final Pattern OC_PERCENT = Pattern.compile("(?i)(?:time|duration)[^%]{0,80}(?:multiplied\\s+by|multiplier\\s*[:=x]?)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%");
	private static final Pattern GLASS_POWER = Pattern.compile("(?i)([0-9]+)\\s*\\^\\s*\\(?\\s*glass\\s*tier");
	private static final Pattern COIL_PARALLEL = Pattern.compile("(?i)for\\s+each\\s+([0-9]+)\\s*k[^,;]*coil\\s+temperature[^,;]*[,;:]?\\s*([0-9]+)\\s*x\\s*parallels?");
	private static final Pattern COIL_FORMULA = Pattern.compile("(?i)([0-9]+)\\s*\\^.*temperature\\s*/\\s*([0-9]+)");

	private GtoMachineCapabilityScanner() {
	}

	static GtoMachineCapabilities scan(EmiStack stack, String machineId, List<String> tooltipLines) {
		boolean parallel = false;
		boolean acceleration = false;
		boolean thread = false;
		boolean overclocking = false;
		boolean laser = false;
		boolean auxiliary = false;
		boolean accelerationNeedsAuxiliary = false;
		boolean threadNeedsAuxiliary = false;
		boolean overclockingNeedsAuxiliary = false;
		int fixedParallel = 0;
		double processingTime = 0.0D;
		double standardOc = 0.0D;
		boolean disablesPerfectOc = false;
		int glassBase = 0;
		boolean casingTierLimit = false;
		int coilStep = 0;
		int coilFactor = 0;

		StringBuilder joined = new StringBuilder();
		for (String raw : tooltipLines == null ? List.<String>of() : tooltipLines) {
			String line = raw == null ? "" : raw.trim();
			String lower = line.toLowerCase(Locale.ROOT);
			if (joined.length() > 0) {
				joined.append('\n');
			}
			joined.append(lower);
			if (lower.contains("auxiliary module")) {
				auxiliary = true;
			}
			if (lower.contains("parallel control") && !negative(lower, "parallel control")) {
				parallel = true;
			}
			if ((lower.contains("acceleration hatch") || lower.contains("accelerate hatch")) && !negative(lower, "hatch")) {
				acceleration = true;
			}
			if ((lower.contains("thread hatch") || lower.contains("multi-threading") || lower.contains("multi-threaded")) && !negative(lower, "thread")) {
				thread = true;
			}
			if ((lower.contains("overclocking hatch") || lower.contains("overclock hatch")) && !negative(lower, "overclock")) {
				overclocking = true;
			}
			if (lower.contains("laser energy hatch")) {
				laser = true;
			}
			if (lower.contains("unlocked hatch types")) {
				if (lower.contains("acceleration hatch") || lower.contains("accelerate hatch")) {
					accelerationNeedsAuxiliary = true;
				}
				if (lower.contains("thread hatch")) {
					threadNeedsAuxiliary = true;
				}
				if (lower.contains("overclocking hatch") || lower.contains("overclock hatch")) {
					overclockingNeedsAuxiliary = true;
				}
			}
			Matcher processing = PROCESSING_TIME.matcher(line);
			if (processing.find()) {
				processingTime = positiveDouble(processing.group(1));
			}
			Matcher fixed = FIXED_PARALLEL.matcher(line);
			if (fixed.find()) {
				fixedParallel = positiveInt(fixed.group(1));
			}
			Matcher oc = OC_PERCENT.matcher(line);
			if (oc.find() && (lower.contains("4") || lower.contains("overclock") || lower.contains("power"))) {
				double percent = positiveDouble(oc.group(1));
				if (percent > 0.0D && percent <= 100.0D) {
					standardOc = percent / 100.0D;
				}
			}
			Matcher coil = COIL_PARALLEL.matcher(line);
			if (coil.find()) {
				coilStep = positiveInt(coil.group(1));
				coilFactor = positiveInt(coil.group(2));
			}
			Matcher formula = COIL_FORMULA.matcher(line);
			if (formula.find()) {
				coilFactor = positiveInt(formula.group(1));
				coilStep = positiveInt(formula.group(2));
			}
		}

		String tooltip = joined.toString();
		disablesPerfectOc = tooltip.contains("worse-than-imperfect overclock") || tooltip.contains("worse than imperfect overclock");
		Matcher glass = GLASS_POWER.matcher(tooltip);
		if (glass.find()) {
			glassBase = positiveInt(glass.group(1));
		}
		if (tooltip.contains("decided by glass tier") && glassBase <= 1) {
			glassBase = 4;
		}
		casingTierLimit = tooltip.contains("recipe tier is limited by machine casing tier")
			|| tooltip.contains("recipe tier cannot exceed") && tooltip.contains("casing tier");

		PlannerMachineIntrospection.Snapshot inspection = PlannerMachineIntrospection.inspect(stack);
		parallel |= inspection.containsAny("parallelcontrolhatch", "parallel_hatch", "parallelcontrol");
		acceleration |= inspection.containsAny("accelerationhatch", "acceleration_hatch", "acceleratehatch");
		thread |= inspection.containsAny("threadhatch", "thread_hatch", "multithread");
		overclocking |= inspection.containsAny("overclockinghatch", "overclock_hatch", "overclockhatch");
		laser |= inspection.containsAny("laserenergyhatch", "laser_energy_hatch");
		auxiliary |= inspection.containsAny("auxiliarymodule", "auxiliary_module");

		GtoMachineCapabilities detected = new GtoMachineCapabilities(
			parallel, acceleration, thread, overclocking, laser, auxiliary,
			accelerationNeedsAuxiliary, threadNeedsAuxiliary, overclockingNeedsAuxiliary,
			fixedParallel, processingTime, standardOc, disablesPerfectOc, glassBase, casingTierLimit, coilStep, coilFactor);
		return detected.merge(GtoMachineCapabilityOverrides.forMachineId(machineId));
	}

	private static boolean negative(String line, String subject) {
		if (!line.contains(subject)) {
			return false;
		}
		return line.contains("no " + subject) || line.contains("not supported") || line.contains("unavailable")
			|| line.contains("disabled") || line.contains("✗");
	}

	private static int positiveInt(String value) {
		if (value == null) {
			return 0;
		}
		String digits = value.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return 0;
		}
		try {
			return (int) Math.min(Integer.parseInt(digits), 1_000_000_000L);
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static double positiveDouble(String value) {
		try {
			double parsed = Double.parseDouble(value);
			return Double.isFinite(parsed) && parsed > 0.0D ? parsed : 0.0D;
		} catch (Throwable ignored) {
			return 0.0D;
		}
	}
}
