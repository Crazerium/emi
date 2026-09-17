package dev.emi.emi.planner.compat.gtceu;

import java.util.List;

import dev.emi.emi.planner.ProductionPlanner.MachineSettingSpec;

public final class GtceuHeatingCoilCatalog {
	private static final List<String> NAMES = List.of(
		"AUTO", "Cupronickel", "Kanthal", "Nichrome", "RTM Alloy", "HSS-G", "Naquadah", "Trinium", "Tritanium"
	);
	private static final int[] TEMPERATURES = {
		0, 1800, 2700, 3600, 4500, 5400, 7200, 9001, 10800
	};

	private GtceuHeatingCoilCatalog() {
	}

	public static MachineSettingSpec spec(String key, String labelKey, String englishLabel, String helpKey, String englishHelp) {
		return MachineSettingSpec.choice(key, labelKey, englishLabel, NAMES, 0, helpKey, englishHelp);
	}

	public static int temperatureForChoice(int choice) {
		return choice >= 0 && choice < TEMPERATURES.length ? TEMPERATURES[choice] : 0;
	}

	public static String nameForChoice(int choice) {
		return choice >= 0 && choice < NAMES.size() ? NAMES.get(choice) : "AUTO";
	}
}
