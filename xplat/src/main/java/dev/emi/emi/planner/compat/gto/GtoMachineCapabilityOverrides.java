package dev.emi.emi.planner.compat.gto;

import java.util.Locale;

final class GtoMachineCapabilityOverrides {
	private GtoMachineCapabilityOverrides() {
	}

	static GtoMachineCapabilities forMachineId(String machineId) {
		String id = machineId == null ? "" : machineId.toLowerCase(Locale.ROOT);
		return switch (id) {
			case "gtocore:precision_assembler" -> new GtoMachineCapabilities(
				false, false, false, false, true, false, false, false, false,
				0, 0.0D, 0.65D, true, 4, true, 0, 0);
			case "gtceu:electric_blast_furnace" -> new GtoMachineCapabilities(
				false, true, false, false, false, true, true, false, false,
				0, 0.0D, 0.0D, false, 0, false, 0, 0);
			case "gtocore:nyarlathoteps_tentacle" -> new GtoMachineCapabilities(
				false, false, true, false, true, false, false, false, false,
				0, 0.0D, 0.0D, false, 0, false, 900, 2);
			default -> GtoMachineCapabilities.EMPTY;
		};
	}
}
