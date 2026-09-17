package dev.emi.emi.planner.compat;

import java.util.List;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.planner.compat.gto.GtoPlannerMachineCompat;
import dev.emi.emi.planner.compat.gtceu.GtceuPlannerMachineCompat;

public final class PlannerMachineCompatRegistry {
	private static final List<PlannerMachineCompatProvider> PROVIDERS = List.of(
		new GtceuPlannerMachineCompat(),
		new GtoPlannerMachineCompat()
	);

	private PlannerMachineCompatRegistry() {
	}

	public static PlannerMachineRuntimeOverride resolve(EmiStack machineStack, String machineId, List<String> tooltipLines) {
		PlannerMachineRuntimeOverride result = null;
		for (PlannerMachineCompatProvider provider : PROVIDERS) {
			if (!provider.isActive()) {
				continue;
			}
			PlannerMachineRuntimeOverride override = provider.resolve(machineStack, machineId, tooltipLines);
			if (override == null) {
				continue;
			}
			result = result == null ? override : result.merge(override);
		}
		return result;
	}

	public static String russianText(String key) {
		for (PlannerMachineCompatProvider provider : PROVIDERS) {
			if (!provider.isActive()) {
				continue;
			}
			String value = provider.russianText(key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}
}
