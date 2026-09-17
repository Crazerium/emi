package dev.emi.emi.planner;

import java.util.Map;

import dev.emi.emi.planner.compat.PlannerMachineCompatRegistry;
import net.minecraft.client.MinecraftClient;

public final class PlannerText {
	private static final Map<String, String> RU = Map.ofEntries(
		Map.entry("planner.title", "Производственная линия"),
		Map.entry("line", "Линия"),
		Map.entry("summary.external", "Внешние входы/сек"),
		Map.entry("summary.internal", "Внутренний поток/сек"),
		Map.entry("summary.outputs", "Чистый выход/сек"),
		Map.entry("targets", "Цели:"),
		Map.entry("balance", "РАСЧЕТ"),
		Map.entry("balanced", "ГОТОВО"),
		Map.entry("limited", "ЛИМИТ"),
		Map.entry("clear", "ОЧИСТИТЬ"),
		Map.entry("power", "Энергия"),
		Map.entry("header.mode", "РЕЖИМ"),
		Map.entry("header.machine", "МАШИНА"),
		Map.entry("header.cfg", "НАСТР"),
		Map.entry("header.mach", "МАШ"),
		Map.entry("header.par", "ПАР"),
		Map.entry("header.volt", "НАПР"),
		Map.entry("header.oc", "OC"),
		Map.entry("header.duration", "ВРЕМЯ"),
		Map.entry("header.rate", "СКОР"),
		Map.entry("header.recipe", "РЕЦЕПТ"),
		Map.entry("header.inputs", "ВХОДЫ/сек"),
		Map.entry("header.outputs", "ВЫХОДЫ/сек"),
		Map.entry("footer.standard_voltage", "Стандартное напряжение:"),
		Map.entry("footer.recipe_min", "Мин. рецепта"),
		Map.entry("footer.apply_all", "ВСЕМ"),
		Map.entry("footer.groups", "ГРУППЫ"),
		Map.entry("footer.voltage_hint", "Новые машины наследуют это напряжение; строку можно переопределить."),
		Map.entry("machine.settings", "Настройки машины"),
		Map.entry("machine.select", "Выбор машины"),
		Map.entry("machine.coils", "Катушки"),
		Map.entry("machine.select_coils", "Выбор катушек"),
		Map.entry("machine.parallel_control", "Контроль параллелей"),
		Map.entry("machine.coil_help", "Бонус катушек умножает время и EU/t"),
		Map.entry("machine.parallel_help", "Контроль параллелей повторяет значение PAR в строке"),
		Map.entry("machine.special_help", "Специальные параметры машины применяются к расчету линии"),
		Map.entry("machine.throughput_multiplier", "Текущий множитель пропускной способности"),
		Map.entry("groups.title", "Группы и связи"),
		Map.entry("groups.root", "Корень"),
		Map.entry("groups.new", "+ ГРУППА"),
		Map.entry("groups.child", "+ ДОЧЕРНЯЯ"),
		Map.entry("groups.delete", "УДАЛИТЬ"),
		Map.entry("groups.rename", "ИМЯ"),
		Map.entry("groups.rename_label", "Имя группы"),
		Map.entry("groups.collapse", "СВЕРНУТЬ"),
		Map.entry("groups.expand", "РАЗВЕРНУТЬ"),
		Map.entry("groups.collapsed", "свернуто"),
		Map.entry("groups.group_row", "ГРУППА"),
		Map.entry("groups.close", "ЗАКРЫТЬ"),
		Map.entry("groups.recipes", "Рецепты"),
		Map.entry("groups.links", "Связи"),
		Map.entry("groups.move_here", "СЮДА"),
		Map.entry("groups.in_group", "В ГРУППЕ"),
		Map.entry("groups.match", "MATCH"),
		Map.entry("groups.ignore", "IGNORE"),
		Map.entry("groups.match_help", "MATCH: ресурс должен замыкаться внутри этой группы"),
		Map.entry("groups.ignore_help", "IGNORE: ресурс может пройти в родительскую группу"),
		Map.entry("groups.empty_links", "Нет ресурсов, которые одновременно производятся и потребляются на этом уровне"),
		Map.entry("groups.empty_recipes", "В линии пока нет рецептов"),
		Map.entry("groups.tooltip", "Вложенные группы и правила связей ресурсов"),
		Map.entry("groups.tooltip2", "MATCH замыкает ресурс внутри группы; IGNORE передает его уровнем выше"),
		Map.entry("groups.drag_group", "Перемещение группы"),
		Map.entry("groups.invalid_drop", "Нельзя переместить группу внутрь самой себя"),
		Map.entry("groups.drop_root", "Переместить в Корень"),
		Map.entry("groups.drop_before", "Поставить перед"),
		Map.entry("groups.drop_after", "Поставить после"),
		Map.entry("groups.drop_inside", "Переместить внутрь"),
		Map.entry("groups.drag_recipe", "Перемещение рецепта"),
		Map.entry("groups.drop_recipe_group", "Переместить рецепт в"),
		Map.entry("groups.reorder_recipe", "Изменить порядок рецепта"),
		Map.entry("status.add_targets", "Нажмите на выход рецепта, чтобы добавить цель"),
		Map.entry("status.target_selected", "Цель выбрана. Нажмите РАСЧЕТ."),
		Map.entry("status.target_added", "Цель добавлена. Нажмите РАСЧЕТ."),
		Map.entry("status.target_changed", "Скорость цели изменена. Нажмите РАСЧЕТ."),
		Map.entry("status.target_removed", "Цель удалена. Нажмите РАСЧЕТ."),
		Map.entry("status.line_changed", "Линия изменена. Нажмите РАСЧЕТ."),
		Map.entry("status.choose_target", "Сначала выберите хотя бы одну цель"),
		Map.entry("status.no_recipes", "В линии нет рецептов"),
		Map.entry("status.missing_recipe", "Один из рецептов отсутствует"),
		Map.entry("status.target_blocked", "Ни один рецепт не выводит цель за пределы замкнутых групп"),
		Map.entry("status.infeasible", "Ограничения производственной линии несовместимы"),
		Map.entry("status.unbounded", "Решение производственной линии не ограничено"),
		Map.entry("status.failed", "Не удалось решить производственную линию"),
		Map.entry("status.positive_flow", "Не удалось построить положительный поток цели"),
		Map.entry("status.balanced_target", "Сбалансировано по цели"),
		Map.entry("status.balanced_targets", "Сбалансировано по всем целям"),
		Map.entry("status.residual", "Сбалансировано с остатком"),
		Map.entry("status.capacity_shortfall", "ограничения машин все еще дают нехватку мощности"),
		Map.entry("status.sizing_unavailable", "для части машин расчет количества недоступен"),
		Map.entry("status.machines_sized", "машины рассчитаны"),
		Map.entry("status.bottleneck", "Узкое место ограничивает производительность линии"),
		Map.entry("status.fixed_setup", "фиксированная конфигурация машин"),
		Map.entry("status.requested", "Запрошено"),
		Map.entry("status.achievable", "Достижимо"),
		Map.entry("status.bottleneck_label", "Узкое место"),
		Map.entry("status.restored", "Сбалансированные скорости восстановлены"),
		Map.entry("status.disabled", "Балансировка отключена"),
		Map.entry("group.default", "Группа")
	);

	private PlannerText() {
	}

	public static String tr(String key, String english) {
		if (isRussian()) {
			String builtIn = RU.get(key);
			if (builtIn != null) {
				return builtIn;
			}
			String compat = PlannerMachineCompatRegistry.russianText(key);
			if (compat != null) {
				return compat;
			}
		}
		return english;
	}

	public static String tr(String key, String english, Object... args) {
		return String.format(tr(key, english), args);
	}

	public static boolean isRussian() {
		try {
			String language = MinecraftClient.getInstance().getLanguageManager().getLanguage();
			return language != null && language.toLowerCase().startsWith("ru");
		} catch (Throwable ignored) {
			return false;
		}
	}
}
