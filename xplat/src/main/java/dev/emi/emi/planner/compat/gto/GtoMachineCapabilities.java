package dev.emi.emi.planner.compat.gto;

record GtoMachineCapabilities(
		boolean parallelControl,
		boolean acceleration,
		boolean thread,
		boolean overclocking,
		boolean laserEnergy,
		boolean auxiliaryModules,
		boolean accelerationRequiresAuxiliary,
		boolean threadRequiresAuxiliary,
		boolean overclockingRequiresAuxiliary,
		int fixedParallel,
		double processingTimeMultiplier,
		double standardOcDurationMultiplier,
		boolean disablesPerfectOc,
		int glassParallelBase,
		boolean casingTierLimit,
		int coilParallelStepKelvin,
		int coilParallelFactor) {

	static final GtoMachineCapabilities EMPTY = new GtoMachineCapabilities(
		false, false, false, false, false, false, false, false, false,
		0, 0.0D, 0.0D, false, 0, false, 0, 0);

	GtoMachineCapabilities merge(GtoMachineCapabilities other) {
		if (other == null) {
			return this;
		}
		return new GtoMachineCapabilities(
			parallelControl || other.parallelControl,
			acceleration || other.acceleration,
			thread || other.thread,
			overclocking || other.overclocking,
			laserEnergy || other.laserEnergy,
			auxiliaryModules || other.auxiliaryModules,
			accelerationRequiresAuxiliary || other.accelerationRequiresAuxiliary,
			threadRequiresAuxiliary || other.threadRequiresAuxiliary,
			overclockingRequiresAuxiliary || other.overclockingRequiresAuxiliary,
			other.fixedParallel > 0 ? other.fixedParallel : fixedParallel,
			other.processingTimeMultiplier > 0.0D ? other.processingTimeMultiplier : processingTimeMultiplier,
			other.standardOcDurationMultiplier > 0.0D ? other.standardOcDurationMultiplier : standardOcDurationMultiplier,
			disablesPerfectOc || other.disablesPerfectOc,
			other.glassParallelBase > 1 ? other.glassParallelBase : glassParallelBase,
			casingTierLimit || other.casingTierLimit,
			other.coilParallelStepKelvin > 0 ? other.coilParallelStepKelvin : coilParallelStepKelvin,
			other.coilParallelFactor > 1 ? other.coilParallelFactor : coilParallelFactor
		);
	}

	boolean hasRuleMechanics() {
		return parallelControl || acceleration || thread || overclocking || auxiliaryModules
			|| glassParallelBase > 1 || casingTierLimit || hasCoilParallel();
	}

	boolean hasRuntimeOverrides() {
		return fixedParallel > 0 || processingTimeMultiplier > 0.0D || standardOcDurationMultiplier > 0.0D;
	}

	boolean hasAny() {
		return hasRuleMechanics() || hasRuntimeOverrides() || laserEnergy;
	}

	String summary() {
		java.util.List<String> parts = new java.util.ArrayList<>();
		if (parallelControl) parts.add("parallel-hatch");
		if (acceleration) parts.add("acceleration-hatch");
		if (thread) parts.add("thread-hatch");
		if (overclocking) parts.add("overclock-hatch");
		if (laserEnergy) parts.add("laser-energy");
		if (auxiliaryModules) parts.add("auxiliary-modules");
		if (glassParallelBase > 1) parts.add("glass-parallel");
		if (casingTierLimit) parts.add("casing-tier-limit");
		if (hasCoilParallel()) parts.add("coil-parallel");
		if (fixedParallel > 0) parts.add("fixed-parallel=" + fixedParallel);
		if (processingTimeMultiplier > 0.0D) parts.add("duration=x" + processingTimeMultiplier);
		if (standardOcDurationMultiplier > 0.0D) parts.add("oc=x" + standardOcDurationMultiplier);
		return String.join(", ", parts);
	}

	boolean hasCoilParallel() {
		return coilParallelStepKelvin > 0 && coilParallelFactor > 1;
	}
}
