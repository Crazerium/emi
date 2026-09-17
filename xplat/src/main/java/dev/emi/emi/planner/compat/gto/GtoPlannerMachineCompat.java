package dev.emi.emi.planner.compat.gto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.planner.compat.PlannerMachineCompatProvider;
import dev.emi.emi.planner.compat.PlannerMachineRuntimeOverride;
import dev.emi.emi.platform.EmiAgnos;

public final class GtoPlannerMachineCompat implements PlannerMachineCompatProvider {
	private static final Map<String, String> RU = Map.ofEntries(
		Map.entry("gto.precision.glass_tier", "Тир стекла"),
		Map.entry("gto.precision.glass_tier_help", "Встроенные параллели определяются обнаруженной формулой от тира стекла"),
		Map.entry("gto.precision.casing_tier", "Тир корпуса машины"),
		Map.entry("gto.precision.casing_tier_help", "Тир рецепта не может быть выше тира корпуса машины; AUTO использует минимум рецепта"),
		Map.entry("gto.precision.current_parallel", "Текущие встроенные параллели"),
		Map.entry("gto.precision.recipe_tier", "Тир рецепта"),
		Map.entry("gto.precision.recipe_allowed", "Рецепт разрешен"),
		Map.entry("gto.precision.casing_tier_low", "Тир корпуса машины слишком низкий"),
		Map.entry("gto.auto.coil_parallel", "Нагревательная катушка"),
		Map.entry("gto.auto.coil_parallel_help", "Выбранная катушка управляет автоматически обнаруженной формулой встроенного parallel"),
		Map.entry("gto.auto.coil_parallel_auto", "AUTO сохраняет обнаруженный/профильный лимит parallel"),
		Map.entry("gto.auto.coil_temperature", "Температура катушки"),
		Map.entry("gto.auto.parallel_steps", "Шаги формулы parallel"),
		Map.entry("gto.auto.detected_formula", "Обнаруженная формула"),
		Map.entry("gto.hatch.auxiliary", "Вспомогательные модули"),
		Map.entry("gto.hatch.auxiliary_help", "Включите, если расширение/вспомогательный модуль мультиблока установлен"),
		Map.entry("gto.hatch.auxiliary_state", "Вспомогательные модули включены"),
		Map.entry("gto.hatch.parallel_limit", "Лимит параллельного хэтча"),
		Map.entry("gto.hatch.parallel_limit_help", "Максимальный parallel, указанный на Parallel Control Hatch; PAR в строке остается текущей настройкой хэтча"),
		Map.entry("gto.hatch.acceleration_tier", "Тир хэтча ускорения"),
		Map.entry("gto.hatch.acceleration_tier_help", "Тир Acceleration Hatch; за каждый недостающий относительно рецепта тир добавляется 20% времени"),
		Map.entry("gto.hatch.acceleration_multiplier", "Настройка ускорения"),
		Map.entry("gto.hatch.acceleration_multiplier_help", "Желаемый процент времени; MAX SPEED использует максимальное ускорение выбранного тира"),
		Map.entry("gto.hatch.thread_tier", "Тир потокового хэтча"),
		Map.entry("gto.hatch.thread_tier_help", "Тир Thread Hatch определяет максимальное число одновременно работающих потоков рецепта"),
		Map.entry("gto.hatch.thread_count", "Количество потоков"),
		Map.entry("gto.hatch.thread_count_help", "Число активных потоков рецепта; MAX использует максимум выбранного Thread Hatch"),
		Map.entry("gto.hatch.overclock_tier", "Тир хэтча оверклока"),
		Map.entry("gto.hatch.overclock_tier_help", "Тир Overclocking Hatch задает максимальный делитель времени на каждый оверклок x4 EU/t"),
		Map.entry("gto.hatch.overclock_divisor", "Делитель времени OC"),
		Map.entry("gto.hatch.overclock_divisor_help", "Делитель времени на каждый оверклок x4 EU/t; MAX использует максимум выбранного тира"),
		Map.entry("gto.hatch.max_oc_divisor", "Максимальный делитель времени OC выбранного хэтча"),
		Map.entry("gto.hatch.effective_oc_divisor", "Эффективный делитель времени OC"),
		Map.entry("gto.hatch.base_oc_multiplier", "Базовый множитель времени стандартного OC машины"),
		Map.entry("gto.hatch.effective_oc_multiplier", "Эффективный множитель времени стандартного OC"),
		Map.entry("gto.hatch.current_max_parallel", "Текущий максимум parallel хэтча"),
		Map.entry("gto.hatch.auto_parallel", "AUTO сохраняет обнаруженный/профильный лимит parallel"),
		Map.entry("gto.hatch.row_parallel", "PAR в строке остается текущим настроенным parallel хэтча"),
		Map.entry("gto.hatch.unknown", "неизвестно"),
		Map.entry("gto.hatch.disabled", "Хэтч выключен"),
		Map.entry("gto.hatch.min_duration", "Минимальный базовый процент времени для этого тира"),
		Map.entry("gto.hatch.effective_duration", "Итоговый множитель времени"),
		Map.entry("gto.hatch.recipe_tier", "Тир рецепта"),
		Map.entry("gto.hatch.missing_tiers", "Недостающие тиры хэтча"),
		Map.entry("gto.hatch.max_threads", "Максимум потоков выбранного хэтча"),
		Map.entry("gto.hatch.effective_threads", "Эффективные потоки рецепта"),
		Map.entry("gto.hatch.requires_auxiliary", "Требует вспомогательные модули"),
		Map.entry("gto.hatch.auxiliary_required_error", "Выбранный GTO-хэтч требует вспомогательные модули"),
		Map.entry("gto.common.yes", "ДА"),
		Map.entry("gto.common.no", "НЕТ")
	);

	@Override
	public boolean isActive() {
		return EmiAgnos.isModLoaded("gtocore");
	}

	@Override
	public PlannerMachineRuntimeOverride resolve(EmiStack machineStack, String machineId, List<String> tooltipLines) {
		GtoMachineCapabilities capabilities = GtoMachineCapabilityScanner.scan(machineStack, machineId, tooltipLines);
		if (!capabilities.hasAny()) {
			return null;
		}

		List<String> notes = new ArrayList<>();
		notes.add("GTO generic capability scanner matched this machine");
		if (!capabilities.summary().isBlank()) {
			notes.add("GTO auto capabilities: " + capabilities.summary());
		}
		if (capabilities.hasRuleMechanics()) {
			notes.add("GTO configurable mechanics are generated from detected capabilities");
		}
		if (capabilities.fixedParallel() > 0) {
			notes.add("GTO fixed built-in parallel detected: " + capabilities.fixedParallel());
		}
		if (capabilities.processingTimeMultiplier() > 0.0D) {
			notes.add("GTO processing-time multiplier detected: x" + capabilities.processingTimeMultiplier());
		}
		if (capabilities.standardOcDurationMultiplier() > 0.0D) {
			notes.add("GTO standard OC multiplier detected: x" + capabilities.standardOcDurationMultiplier());
		}

		Boolean perfectKnown = capabilities.disablesPerfectOc() ? Boolean.TRUE : null;
		Boolean allowsPerfect = capabilities.disablesPerfectOc() ? Boolean.FALSE : null;
		return new PlannerMachineRuntimeOverride(
			null,
			capabilities.fixedParallel() > 0 ? capabilities.fixedParallel() : null,
			perfectKnown,
			allowsPerfect,
			capabilities.processingTimeMultiplier() > 0.0D ? capabilities.processingTimeMultiplier() : null,
			null,
			capabilities.parallelControl() ? Boolean.TRUE : null,
			null,
			capabilities.standardOcDurationMultiplier() > 0.0D ? capabilities.standardOcDurationMultiplier() : null,
			capabilities.hasRuleMechanics() ? new GtoCapabilityMachineRule(capabilities) : null,
			List.copyOf(notes)
		);
	}

	@Override
	public String russianText(String key) {
		return RU.get(key);
	}
}
