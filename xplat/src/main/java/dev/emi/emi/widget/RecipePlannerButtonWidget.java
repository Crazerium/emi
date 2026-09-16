package dev.emi.emi.widget;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.planner.ProductionPlanner;
import dev.emi.emi.runtime.EmiDrawContext;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;

public class RecipePlannerButtonWidget extends RecipeButtonWidget {

	public RecipePlannerButtonWidget(int x, int y, EmiRecipe recipe) {
		super(x, y, 0, 0, recipe);
	}

	@Override
	public void render(DrawContext raw, int mouseX, int mouseY, float delta) {
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		boolean hovered = getBounds().contains(mouseX, mouseY);
		int bg = hovered ? 0xFF4A4A54 : 0xFF303038;
		int border = hovered ? 0xFFE0E0E0 : 0xFF808088;
		context.fill(x, y, 12, 12, bg);
		context.fill(x, y, 12, 1, border);
		context.fill(x, y + 11, 12, 1, border);
		context.fill(x, y, 1, 12, border);
		context.fill(x + 11, y, 1, 12, border);
		context.fill(x + 5, y + 2, 2, 8, 0xFF66FF99);
		context.fill(x + 2, y + 5, 8, 2, 0xFF66FF99);
	}

	@Override
	public List<TooltipComponent> getTooltip(int mouseX, int mouseY) {
		List<TooltipComponent> tooltip = new ArrayList<>();
		if (recipe.getId() == null) {
			tooltip.add(TooltipComponent.of(EmiPort.ordered(EmiPort.literal("Production Planner"))));
			tooltip.add(TooltipComponent.of(EmiPort.ordered(EmiPort.literal("This recipe has no stable ID and cannot be saved"))));
		} else {
			tooltip.add(TooltipComponent.of(EmiPort.ordered(EmiPort.literal("Production Planner"))));
			tooltip.add(TooltipComponent.of(EmiPort.ordered(EmiPort.literal("LMB - Add to active line (AUTO when recipe duration is detected)"))));
			tooltip.add(TooltipComponent.of(EmiPort.ordered(EmiPort.literal("RMB - Open planner without adding"))));
		}
		return tooltip;
	}

	@Override
	public boolean mouseClicked(int mouseX, int mouseY, int button) {
		if (recipe.getId() == null) {
			return false;
		}
		if (button == 1) {
			playButtonSound();
			EmiApi.viewProductionPlanner();
			return true;
		}
		if (button == 0 && ProductionPlanner.addRecipe(recipe)) {
			playButtonSound();
			EmiApi.viewProductionPlanner();
			return true;
		}
		return false;
	}
}
