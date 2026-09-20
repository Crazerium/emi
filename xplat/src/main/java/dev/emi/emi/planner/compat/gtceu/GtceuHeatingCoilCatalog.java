package dev.emi.emi.planner.compat.gtceu;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.planner.ProductionPlanner.MachineSettingSpec;

public final class GtceuHeatingCoilCatalog {
	private record Coil(String name, int temperature) {
	}

	private static final List<Coil> COILS = buildCoils();
	private static final List<String> NAMES = COILS.stream().map(Coil::name).toList();

	private GtceuHeatingCoilCatalog() {
	}

	public static MachineSettingSpec spec(String key, String labelKey, String englishLabel, String helpKey, String englishHelp) {
		return MachineSettingSpec.choice(key, labelKey, englishLabel, NAMES, 0, helpKey, englishHelp);
	}

	public static int temperatureForChoice(int choice) {
		return choice >= 0 && choice < COILS.size() ? COILS.get(choice).temperature() : 0;
	}

	public static String nameForChoice(int choice) {
		return choice >= 0 && choice < COILS.size() ? COILS.get(choice).name() : "AUTO";
	}

	private static List<Coil> buildCoils() {
		List<Coil> coils = new ArrayList<>();

		// Keep the original GTCEu order stable: machine CFG values are persisted as numeric choice indexes.
		coils.add(new Coil("AUTO", 0));
		coils.add(new Coil("Cupronickel", 1800));
		coils.add(new Coil("Kanthal", 2700));
		coils.add(new Coil("Nichrome", 3600));
		coils.add(new Coil("RTM Alloy", 4500));
		coils.add(new Coil("HSS-G", 5400));
		coils.add(new Coil("Naquadah", 7200));
		coils.add(new Coil("Trinium", 9001));
		coils.add(new Coil("Tritanium", 10800));

		if (isGtoCorePresent()) {
			// GTOCore heating coils. Append only so existing saved GTCEu coil indexes never shift.
			coils.add(new Coil("Abyssalalloy", 12600));
			coils.add(new Coil("Titansteel", 14400));
			coils.add(new Coil("Adamantine", 16200));
			coils.add(new Coil("Naquadriatictaranium", 18900));
			coils.add(new Coil("Starmetal", 21600));
			coils.add(new Coil("Infinity", 36000));
			coils.add(new Coil("Hypogen", 62000));
			coils.add(new Coil("Eternity", 96000));
		}

		return List.copyOf(coils);
	}

	private static boolean isGtoCorePresent() {
		try {
			Class.forName("com.gtocore.common.data.GTOBlocks", false, GtceuHeatingCoilCatalog.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
	}
}
