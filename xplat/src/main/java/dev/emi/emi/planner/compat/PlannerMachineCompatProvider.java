package dev.emi.emi.planner.compat;

import java.util.List;

import dev.emi.emi.api.stack.EmiStack;

public interface PlannerMachineCompatProvider {
	boolean isActive();

	PlannerMachineRuntimeOverride resolve(EmiStack machineStack, String machineId, List<String> tooltipLines);

	default String russianText(String key) {
		return null;
	}
}
