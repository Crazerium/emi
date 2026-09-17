package dev.emi.emi.screen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.planner.ProductionPlanner;
import dev.emi.emi.planner.PlannerText;
import dev.emi.emi.planner.ProductionPlanner.Entry;
import dev.emi.emi.planner.ProductionPlanner.Group;
import dev.emi.emi.planner.ProductionPlanner.LinkMode;
import dev.emi.emi.planner.ProductionPlanner.Line;
import dev.emi.emi.planner.ProductionPlanner.MachineProfile;
import dev.emi.emi.planner.ProductionPlanner.MachineSettingSpec;
import dev.emi.emi.planner.ProductionPlanner.MachineSizing;
import dev.emi.emi.planner.ProductionPlanner.OcMode;
import dev.emi.emi.planner.ProductionPlanner.Target;
import dev.emi.emi.runtime.EmiDrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.fluid.Fluid;

public class ProductionPlannerScreen extends Screen {
	private static final int HEADER_HEIGHT = 26;
	private static final int SUMMARY_TOP = 28;
	private static final int SUMMARY_HEIGHT = 90;
	private static final int TABLE_HEADER_Y = 122;
	private static final int ROW_TOP = 140;
	private static final int ROW_HEIGHT = 36;
	private static final int FOOTER_HEIGHT = 30;
	private static final int MENU_ROW_HEIGHT = 22;
	private static final int MENU_HEADER_HEIGHT = 20;
	private static final int MENU_MAX_ROWS = 10;
	private static final int TAB_WIDTH = 112;
	private static final int ICON_STEP = 50;
	private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);
	private static final int BG_COLOR = 0xFF0B0B12;
	private static final int HEADER_COLOR = 0xFF24242A;
	private static final int PANEL_COLOR = 0xFF14141B;
	private static final int BORDER_COLOR = 0xFF55555F;
	private static final int ACTIVE_COLOR = 0xFF355048;
	private static final int HOVER_COLOR = 0xFF3A3A44;
	private static final double EPSILON = 0.0000001D;

	public final HandledScreen<?> old;
	private Bounds newLineButton = EMPTY;
	private Bounds closeLineButton = EMPTY;
	private Bounds targetIconBounds = EMPTY;
	private Bounds targetRateBounds = EMPTY;
	private List<TargetHitbox> targetHitboxes = List.of();
	private Bounds balanceButton = EMPTY;
	private Bounds clearTargetButton = EMPTY;
	private Bounds powerBounds = EMPTY;
	private Bounds standardVoltageBounds = EMPTY;
	private Bounds applyStandardVoltageBounds = EMPTY;
	private Bounds groupsButton = EMPTY;
	private boolean groupsOpen;
	private Group selectedGroup;
	private Bounds groupsModalBounds = EMPTY;
	private Bounds groupListArea = EMPTY;
	private Bounds groupRecipeArea = EMPTY;
	private Bounds groupLinkArea = EMPTY;
	private Bounds groupAddButton = EMPTY;
	private Bounds groupChildButton = EMPTY;
	private Bounds groupDeleteButton = EMPTY;
	private Bounds groupRenameButton = EMPTY;
	private Bounds groupCollapseButton = EMPTY;
	private Bounds groupCloseButton = EMPTY;
	private Bounds groupSelectedNameBounds = EMPTY;
	private List<GroupListHitbox> groupListHitboxes = List.of();
	private List<GroupRecipeHitbox> groupRecipeHitboxes = List.of();
	private List<GroupLinkHitbox> groupLinkHitboxes = List.of();
	private int groupListScroll;
	private int groupRecipeScroll;
	private int groupLinkScroll;
	private TextFieldWidget groupRenameField;
	private Group groupRenameTarget;
	private Group pendingGroupDrag;
	private Entry pendingRecipeDrag;
	private boolean groupDragActive;
	private boolean recipeDragActive;
	private double groupDragStartX;
	private double groupDragStartY;
	private double recipeDragStartX;
	private double recipeDragStartY;
	private List<GroupMainHitbox> groupMainHitboxes = List.of();
	private Bounds activeDropdownBounds = EMPTY;
	private Entry machineMenuEntry;
	private Bounds machineMenuAnchor = EMPTY;
	private int machineMenuScroll;
	private Entry machineConfigEntry;
	private Bounds machineConfigAnchor = EMPTY;
	private Bounds configCoilMinus = EMPTY;
	private Bounds configCoilValue = EMPTY;
	private Bounds configCoilPlus = EMPTY;
	private Bounds configParallelMinus = EMPTY;
	private Bounds configParallelValue = EMPTY;
	private Bounds configParallelPlus = EMPTY;
	private List<MachineSettingControl> configSpecialControls = List.of();
	private boolean coilMenuOpen;
	private Bounds coilMenuAnchor = EMPTY;
	private List<CoilOptionHitbox> coilOptionHitboxes = List.of();
	private Entry voltageMenuEntry;
	private Bounds voltageMenuAnchor = EMPTY;
	private int voltageMenuScroll;
	private boolean standardVoltageMenuOpen;
	private int standardVoltageMenuScroll;
	private List<MachineOptionHitbox> machineOptionHitboxes = List.of();
	private List<VoltageOptionHitbox> voltageOptionHitboxes = List.of();
	private List<TabHitbox> tabHitboxes = List.of();
	private List<RowHitbox> rowHitboxes = List.of();
	private List<FlowHitbox> flowHitboxes = List.of();
	private int rowScroll;
	private TextFieldWidget renameField;
	private int renameIndex = -1;
	private TextFieldWidget editField;
	private Entry editEntry;
	private EditKind editKind;
	private TextFieldWidget targetRateField;
	private Line targetEditLine;
	private Target targetEditTarget;
	private Bounds targetEditBounds = EMPTY;

	public ProductionPlannerScreen(HandledScreen<?> old) {
		super(EmiPort.literal(PlannerText.tr("planner.title", "Production Planner")));
		this.old = old;
		ProductionPlanner.ensureLoaded();
		ProductionPlanner.getOrCreateActiveLine();
	}

	@Override
	protected void init() {
		newLineButton = new Bounds(6, 4, 18, 18);
		closeLineButton = new Bounds(28, 4, 18, 18);
		clampScroll();
	}

	@Override
	public void render(DrawContext raw, int mouseX, int mouseY, float delta) {
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		context.fill(0, 0, width, height, BG_COLOR);
		context.fill(0, 0, width, HEADER_HEIGHT, HEADER_COLOR);
		renderHeader(context, raw, mouseX, mouseY, delta);
		Line line = ProductionPlanner.getOrCreateActiveLine();
		PlanTotals totals = calculate(line);
		renderSummary(context, line, totals, mouseX, mouseY);
		renderTableHeader(context);
		renderRows(context, line, mouseX, mouseY, delta);
		renderFooter(context, line, mouseX, mouseY);
		if (editField != null) {
			editField.render(raw, mouseX, mouseY, delta);
		}
		if (targetRateField != null) {
			targetRateField.render(raw, mouseX, mouseY, delta);
		}
		if (groupsOpen) {
			renderGroupsModal(context, line, mouseX, mouseY, delta);
		} else {
			renderDropdowns(context, line, mouseX, mouseY);
			if (isDropdownOpen()) {
				renderDropdownTooltip(context, mouseX, mouseY);
			} else {
				renderTooltip(context, mouseX, mouseY);
			}
		}
	}

	private void renderHeader(EmiDrawContext context, DrawContext raw, int mouseX, int mouseY, float delta) {
		drawButton(context, newLineButton, mouseX, mouseY, "+", false);
		drawButton(context, closeLineButton, mouseX, mouseY, "x", false);
		tabHitboxes = new ArrayList<>();
		List<Line> lines = ProductionPlanner.lines();
		int startX = 52;
		int maxTabs = Math.max(1, (width - startX - 8) / TAB_WIDTH);
		int active = ProductionPlanner.getActiveIndex();
		int first = 0;
		if (active >= maxTabs) {
			first = active - maxTabs + 1;
		}
		int visible = Math.min(maxTabs, lines.size() - first);
		for (int i = 0; i < visible; i++) {
			int index = first + i;
			Bounds bounds = new Bounds(startX + i * TAB_WIDTH, 3, TAB_WIDTH - 3, 20);
			boolean hovered = bounds.contains(mouseX, mouseY);
			int color = index == active ? ACTIVE_COLOR : hovered ? HOVER_COLOR : 0xFF2B2B32;
			context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
			drawBorder(context, bounds, hovered || index == active ? 0xFFB0B0B8 : BORDER_COLOR);
			String display = ProductionPlanner.displayName(index);
			String trimmed = textRenderer.trimToWidth(display, bounds.width() - 8);
			context.drawCenteredText(EmiPort.literal(trimmed), bounds.x() + bounds.width() / 2, bounds.y() + 6, 0xFFFFFFFF);
			tabHitboxes.add(new TabHitbox(bounds, index));
		}
		if (renameField != null) {
			renameField.render(raw, mouseX, mouseY, delta);
		}
	}

	private void renderSummary(EmiDrawContext context, Line line, PlanTotals totals, int mouseX, int mouseY) {
		context.fill(4, SUMMARY_TOP, width - 8, SUMMARY_HEIGHT, PANEL_COLOR);
		drawBorder(context, new Bounds(4, SUMMARY_TOP, width - 8, SUMMARY_HEIGHT), BORDER_COLOR);
		int sectionWidth = Math.max(1, (width - 12) / 3);
		flowHitboxes = new ArrayList<>();
		renderFlowSection(context, line, PlannerText.tr("summary.external", "External Inputs/sec"), totals.externalInputs(), 6, SUMMARY_TOP + 4, sectionWidth - 2, false);
		renderFlowSection(context, line, PlannerText.tr("summary.internal", "Internal Flow/sec"), totals.internalFlow(), 6 + sectionWidth, SUMMARY_TOP + 4, sectionWidth - 2, false);
		renderFlowSection(context, line, PlannerText.tr("summary.outputs", "Net Outputs/sec"), totals.netOutputs(), 6 + sectionWidth * 2, SUMMARY_TOP + 4, sectionWidth - 2, true);

		int controlsY = SUMMARY_TOP + SUMMARY_HEIGHT - 21;
		PowerSummary power = calculatePower(line);
		powerBounds = new Bounds(Math.max(272, width - 166), controlsY, Math.min(158, Math.max(80, width - 280)), 18);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("targets", "Targets:")), 8, controlsY + 5, 0xFFC8C8D0);
		targetHitboxes = new ArrayList<>();
		targetIconBounds = EMPTY;
		targetRateBounds = EMPTY;
		boolean capacityLimited = line.isBalanceEnabled() && line.hasMachineCapacityShortfall();
		int targetX = 52;
		List<Target> targets = line.getTargets();
		if (!targets.isEmpty()) {
			int targetAreaEnd = Math.max(targetX + 118, powerBounds.x() - 132);
			int shown = 0;
			for (Target target : targets) {
				if (shown >= 4 || targetX + 118 > targetAreaEnd) {
					break;
				}
				Bounds icon = new Bounds(targetX, controlsY, 18, 18);
				Bounds rate = new Bounds(targetX + 22, controlsY, 78, 18);
				Bounds remove = new Bounds(targetX + 104, controlsY + 1, 16, 16);
				EmiStack stack = target.getStack();
				context.drawStack(stack, icon.x(), icon.y(), EmiIngredient.RENDER_ICON);
				drawBorder(context, icon, capacityLimited ? 0xFFFFA04D : line.isBalanceEnabled() ? 0xFF7FD8A1 : 0xFFB0B0B8);
				drawValueBox(context, rate, mouseX, mouseY, formatRate(target.getRate()) + unitSuffixShort(stack), line.isBalanceEnabled() && !capacityLimited);
				drawButton(context, remove, mouseX, mouseY, "x", false);
				targetHitboxes.add(new TargetHitbox(target, icon, rate, remove));
				if (shown == 0) {
					targetIconBounds = icon;
					targetRateBounds = rate;
				}
				targetX += 124;
				shown++;
			}
			int hidden = targets.size() - shown;
			if (hidden > 0) {
				Bounds more = new Bounds(targetX, controlsY, 30, 18);
				drawValueBox(context, more, mouseX, mouseY, "+" + hidden, false);
				targetX += 34;
			}
		} else {
			targetIconBounds = new Bounds(targetX, controlsY, 18, 18);
			targetRateBounds = new Bounds(targetX + 22, controlsY, 78, 18);
			context.fill(targetIconBounds.x(), targetIconBounds.y(), targetIconBounds.width(), targetIconBounds.height(), 0xFF222229);
			drawBorder(context, targetIconBounds, BORDER_COLOR);
			drawValueBox(context, targetRateBounds, mouseX, mouseY, "--", false);
			targetX += 104;
		}
		balanceButton = new Bounds(targetX + 4, controlsY, 64, 18);
		clearTargetButton = new Bounds(balanceButton.right() + 4, controlsY, 44, 18);
		drawButton(context, balanceButton, mouseX, mouseY, line.isBalanceEnabled() ? (capacityLimited ? PlannerText.tr("limited", "LIMITED") : PlannerText.tr("balanced", "BALANCED")) : PlannerText.tr("balance", "BALANCE"), line.isBalanceEnabled() && !capacityLimited);
		drawButton(context, clearTargetButton, mouseX, mouseY, PlannerText.tr("clear", "CLEAR"), false);
		String message = line.getBalanceMessage();
		if (message == null || message.isBlank()) {
			message = PlannerText.tr("status.add_targets", "Click recipe outputs to add one or more targets");
		}
		String powerText = power.knownEntries > 0
			? PlannerText.tr("power", "Power") + ": " + formatCompactRate(power.averageEUt, false) + " EU/t" + (power.unknownEntries > 0 ? " +?" : "")
			: PlannerText.tr("power", "Power") + ": --";
		drawValueBox(context, powerBounds, mouseX, mouseY, powerText, power.knownEntries > 0);
		int messageColor = capacityLimited ? 0xFFFFB05C : line.isBalanceEnabled() ? 0xFF8ED6A4 : 0xFF8D8D98;
		int messageX = clearTargetButton.right() + 8;
		int messageWidth = Math.max(20, powerBounds.x() - messageX - 4);
		String trimmed = textRenderer.trimToWidth(message, messageWidth);
		context.drawTextWithShadow(EmiPort.literal(trimmed), messageX, controlsY + 5, messageColor);
	}

	private void renderFlowSection(EmiDrawContext context, Line line, String label, List<Flow> flows, int x, int y, int w,
			boolean targetCandidate) {
		context.drawCenteredText(EmiPort.literal(label), x + w / 2, y, 0xFFE8E8EE);
		int available = Math.max(1, w - 8);
		int perRow = Math.max(1, available / ICON_STEP);
		int capacity = perRow * 2;
		int count = Math.min(capacity, flows.size());
		for (int i = 0; i < count; i++) {
			Flow flow = flows.get(i);
			int row = i / perRow;
			int col = i % perRow;
			int ix = x + 4 + col * ICON_STEP;
			int iy = y + 15 + row * 20;
			context.drawStack(flow.stack, ix, iy, EmiIngredient.RENDER_ICON);
			if (isTarget(line, flow.stack)) {
				drawBorder(context, new Bounds(ix, iy, 18, 18), 0xFFFFFF66);
			}
			EmiRenderHelper.renderAmount(context, ix, iy, EmiPort.literal(flow.displayAmount));
			flowHitboxes.add(new FlowHitbox(new Bounds(ix, iy, 18, 18), flow, targetCandidate));
		}
		if (flows.size() > capacity) {
			String more = "+" + (flows.size() - capacity);
			context.drawTextWithShadow(EmiPort.literal(more), x + w - 30, y + 43, 0xFFB8B8C0);
		}
	}

	private void renderTableHeader(EmiDrawContext context) {
		context.fill(0, TABLE_HEADER_Y, width, 16, 0xFF1B1B22);
		int inputsX = inputsColumnX();
		int outputsX = outputsColumnX();
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.mode", "MODE")), 10, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.machine", "MACHINE")), 58, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.cfg", "CFG")), 170, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.mach", "MACH")), 218, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.par", "PAR")), 300, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.volt", "VOLT")), 364, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.oc", "OC")), 422, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.duration", "DURATION")), 472, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.rate", "RATE")), 538, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.recipe", "RECIPE")), 610, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.inputs", "INPUTS/sec")), inputsX, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("header.outputs", "OUTPUTS/sec")), outputsX, TABLE_HEADER_Y + 4, 0xFFC8C8D0);
	}

	private void renderRows(EmiDrawContext context, Line line, int mouseX, int mouseY, float delta) {
		rowHitboxes = new ArrayList<>();
		groupMainHitboxes = new ArrayList<>();
		List<PlannerDisplayRow> displayRows = buildPlannerDisplayRows(line);
		int visibleRows = visibleRows();
		clampScroll();
		int end = Math.min(displayRows.size(), rowScroll + visibleRows);
		for (int visible = 0, index = rowScroll; index < end; visible++, index++) {
			PlannerDisplayRow displayRow = displayRows.get(index);
			int y = ROW_TOP + visible * ROW_HEIGHT;
			int bg = (index & 1) == 0 ? 0xFF111118 : 0xFF16161D;
			context.fill(0, y, width, ROW_HEIGHT - 1, bg);

			if (displayRow.group != null) {
				Group group = displayRow.group;
				int indent = Math.min(96, displayRow.depth * 14);
				Bounds rowBounds = new Bounds(6, y + 4, width - 12, ROW_HEIGHT - 9);
				Bounds toggle = new Bounds(10 + indent, y + 9, 18, 18);
				boolean hovered = rowBounds.contains(mouseX, mouseY);
				context.fill(rowBounds.x(), rowBounds.y(), rowBounds.width(), rowBounds.height(), hovered ? 0xFF26312F : 0xFF1B2423);
				drawBorder(context, rowBounds, hovered ? 0xFF7FAE9F : 0xFF40504B);
				drawButton(context, toggle, mouseX, mouseY, group.isCollapsed() ? ">" : "v", group.isCollapsed());
				String name = group.getDisplayName(line);
				String suffix = group.isCollapsed()
					? "  [" + PlannerText.tr("groups.collapsed", "collapsed") + "]"
					: "";
				String label = textRenderer.trimToWidth(name + suffix, Math.max(80, width - 120 - indent));
				context.drawTextWithShadow(EmiPort.literal(label), 36 + indent, y + 13, 0xFFE8F2EE);
				context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("groups.group_row", "GROUP")), Math.max(36 + indent, width - 62), y + 13, 0xFF88A39A);
				groupMainHitboxes.add(new GroupMainHitbox(rowBounds, group));
				continue;
			}

			Entry entry = displayRow.entry;
			if (entry == null) {
				continue;
			}
			EmiRecipe recipe = entry.getRecipe();

			Bounds mode = new Bounds(8, y + 7, 42, 20);
			Bounds machineProfile = new Bounds(54, y + 7, 112, 20);
			Bounds machineConfig = new Bounds(170, y + 9, 26, 16);
			Bounds machinesLock = new Bounds(198, y + 9, 16, 16);
			Bounds machinesMinus = new Bounds(216, y + 9, 14, 16);
			Bounds machinesValue = new Bounds(230, y + 7, 32, 20);
			Bounds machinesPlus = new Bounds(262, y + 9, 14, 16);
			Bounds parallelLock = new Bounds(280, y + 9, 16, 16);
			Bounds parallelMinus = new Bounds(298, y + 9, 14, 16);
			Bounds parallelValue = new Bounds(312, y + 7, 32, 20);
			Bounds parallelPlus = new Bounds(344, y + 9, 14, 16);
			Bounds voltage = new Bounds(362, y + 7, 54, 20);
			Bounds oc = new Bounds(420, y + 7, 44, 20);
			Bounds duration = new Bounds(468, y + 7, 62, 20);
			Bounds rate = new Bounds(534, y + 7, 68, 20);
			Bounds remove = new Bounds(width - 22, y + 9, 16, 16);

			String modeLabel = line.isBalanceEnabled() ? "BAL" : entry.isAutomatic() ? "AUTO" : "MAN";
			drawButton(context, mode, mouseX, mouseY, modeLabel, line.isBalanceEnabled() || entry.isAutomatic());
			drawMachineValueBox(context, machineProfile, mouseX, mouseY, entry.getMachineProfile(), !"generic".equals(entry.getMachineProfile().id()));
			if (entry.getMachineProfile().hasConfigurableSettings()) {
				drawButton(context, machineConfig, mouseX, mouseY, "CFG", machineConfigEntry == entry);
			}
			drawButton(context, machinesLock, mouseX, mouseY, "F", entry.isMachinesFixed());
			drawButton(context, machinesMinus, mouseX, mouseY, "-", false);
			drawValueBox(context, machinesValue, mouseX, mouseY, formatInteger(entry.getMachines()), entry.isAutomatic() || entry.isMachinesFixed());
			drawButton(context, machinesPlus, mouseX, mouseY, "+", false);
			drawButton(context, parallelLock, mouseX, mouseY, "F", entry.isParallelFixed());
			drawButton(context, parallelMinus, mouseX, mouseY, "-", false);
			drawValueBox(context, parallelValue, mouseX, mouseY, formatInteger(entry.getParallel()), entry.isAutomatic() || entry.isParallelFixed());
			drawButton(context, parallelPlus, mouseX, mouseY, "+", false);
			drawValueBox(context, voltage, mouseX, mouseY, entry.getVoltageName(), entry.getRecipeEUt() > 0L,
				ProductionPlanner.voltageTierColor(entry.getVoltageTier()));
			drawButton(context, oc, mouseX, mouseY, entry.getOcDisplayLabel(), entry.getOcMode() != OcMode.NONE);

			double durationSeconds = entry.getProcessedDurationSeconds();
			String durationText = durationSeconds > 0.0D ? formatDuration(durationSeconds) + (entry.isDurationOverridden() ? "*" : "") : "--";
			drawValueBox(context, duration, mouseX, mouseY, durationText, entry.isAutomatic());
			double rowRate = line.getEffectiveRate(entry);
			drawValueBox(context, rate, mouseX, mouseY, formatRate(rowRate) + "/s", line.isBalanceEnabled() || !entry.isAutomatic());
			drawButton(context, remove, mouseX, mouseY, "x", false);

			int recipeIndent = Math.min(48, displayRow.depth * 9);
			Bounds recipeBounds = new Bounds(608, y + 3, Math.max(30, inputsColumnX() - 614), ROW_HEIGHT - 7);
			if (recipe == null) {
				context.drawTextWithShadow(EmiPort.literal("Missing recipe: " + entry.getRecipeId()), 610 + recipeIndent, y + 9, 0xFFFF7777);
			} else {
				recipe.getCategory().renderSimplified(context.raw(), 610 + recipeIndent, y + 9, delta);
				String name = recipeName(recipe);
				String trimmed = textRenderer.trimToWidth(name, Math.max(20, recipeBounds.width() - 24 - recipeIndent));
				context.drawTextWithShadow(EmiPort.literal(trimmed), 632 + recipeIndent, y + 7, 0xFFFFFFFF);
				String category = recipe.getCategory().getName().getString();
				if (entry.getGroupId() > 0) {
					category += " • " + line.getEntryGroupName(entry);
				}
				String categoryTrimmed = textRenderer.trimToWidth(category, Math.max(20, recipeBounds.width() - 24 - recipeIndent));
				context.drawTextWithShadow(EmiPort.literal(categoryTrimmed), 632 + recipeIndent, y + 20, 0xFF90909B);
				double effectiveRate = line.getEffectiveRate(entry);
				renderRateStacks(context, line, recipeInputs(recipe, effectiveRate), inputsColumnX(), y + 9,
					outputsColumnX() - inputsColumnX() - 8, false);
				renderRateStacks(context, line, recipeOutputs(recipe, effectiveRate), outputsColumnX(), y + 9,
					width - outputsColumnX() - 32, true);
			}
			rowHitboxes.add(new RowHitbox(entry, mode, machineProfile, machineConfig, machinesLock, machinesMinus, machinesValue, machinesPlus, parallelLock, parallelMinus,
				parallelValue, parallelPlus, voltage, oc, duration, rate, remove, recipeBounds));
		}
		if (line.getEntries().isEmpty()) {
			context.drawCenteredText(EmiPort.literal("Open any EMI recipe and press the + planner button to add it to this line."),
				width / 2, ROW_TOP + 32, 0xFFA0A0AA);
			context.drawCenteredText(EmiPort.literal("New recipes use AUTO when a recipe duration can be detected; MAN keeps direct crafts/sec control."),
				width / 2, ROW_TOP + 48, 0xFF777783);
		}
	}

	private List<PlannerDisplayRow> buildPlannerDisplayRows(Line line) {
		List<PlannerDisplayRow> rows = new ArrayList<>();
		appendPlannerDisplayRows(line, 0, 0, rows);
		return rows;
	}

	private void appendPlannerDisplayRows(Line line, int groupId, int depth, List<PlannerDisplayRow> rows) {
		for (Entry entry : line.getEntries()) {
			if (entry.getGroupId() == groupId) {
				rows.add(new PlannerDisplayRow(entry, null, depth));
			}
		}
		for (Group group : line.getGroups()) {
			if (group.getParentId() != groupId) {
				continue;
			}
			rows.add(new PlannerDisplayRow(null, group, depth));
			if (!group.isCollapsed()) {
				appendPlannerDisplayRows(line, group.getId(), depth + 1, rows);
			}
		}
	}

	private void renderRateStacks(EmiDrawContext context, Line line, List<RateStack> stacks, int x, int y,
			int availableWidth, boolean targetCandidate) {
		int capacity = Math.max(1, availableWidth / 42);
		int count = Math.min(capacity, stacks.size());
		for (int i = 0; i < count; i++) {
			RateStack rate = stacks.get(i);
			int ix = x + i * 42;
			context.drawStack(rate.stack, ix, y, EmiIngredient.RENDER_ICON);
			if (isTarget(line, rate.stack)) {
				drawBorder(context, new Bounds(ix, y, 18, 18), 0xFFFFFF66);
			}
			EmiRenderHelper.renderAmount(context, ix, y, EmiPort.literal(formatCompactRate(rate.amount, rate.approximate)));
			flowHitboxes.add(new FlowHitbox(new Bounds(ix, y, 18, 18), new Flow(rate.stack, 0, 0, 0, 0,
				rate.approximate, formatCompactRate(rate.amount, rate.approximate), rate.amount), targetCandidate));
		}
		if (stacks.size() > capacity) {
			context.drawTextWithShadow(EmiPort.literal("+" + (stacks.size() - capacity)), x + capacity * 42 - 18, y + 9, 0xFFB8B8C0);
		}
	}


	private void renderFooter(EmiDrawContext context, Line line, int mouseX, int mouseY) {
		int y = Math.max(ROW_TOP, height - FOOTER_HEIGHT);
		context.fill(0, y, width, FOOTER_HEIGHT, HEADER_COLOR);
		context.fill(0, y, width, 1, BORDER_COLOR);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("footer.standard_voltage", "Standard Voltage for machines:")), 10, y + 10, 0xFFC8C8D0);
		standardVoltageBounds = new Bounds(158, y + 5, 92, 20);
		applyStandardVoltageBounds = new Bounds(256, y + 5, 70, 20);
		groupsButton = new Bounds(332, y + 5, 78, 20);
		String label = line.getStandardVoltageTier() < 0 ? PlannerText.tr("footer.recipe_min", "Recipe Min") : line.getStandardVoltageName();
		drawValueBox(context, standardVoltageBounds, mouseX, mouseY, label, line.getStandardVoltageTier() >= 0,
			ProductionPlanner.voltageTierColor(line.getStandardVoltageTier()));
		drawButton(context, applyStandardVoltageBounds, mouseX, mouseY, PlannerText.tr("footer.apply_all", "APPLY ALL"), false);
		drawButton(context, groupsButton, mouseX, mouseY, PlannerText.tr("footer.groups", "GROUPS") + " " + line.getGroups().size(), groupsOpen);
		String hint = PlannerText.tr("footer.voltage_hint", "New machines inherit this voltage. Individual rows can override it.");
		int hintX = groupsButton.right() + 10;
		if (hintX < width - 20) {
			context.drawTextWithShadow(EmiPort.literal(textRenderer.trimToWidth(hint, width - hintX - 8)), hintX, y + 10, 0xFF858590);
		}
	}

	private void renderGroupsModal(EmiDrawContext context, Line line, int mouseX, int mouseY, float delta) {
		context.push();
		context.matrices().translate(0, 0, 1000);
		if (selectedGroup != null && !line.getGroups().contains(selectedGroup)) {
			selectedGroup = null;
		}
		if (groupRenameTarget != null && !line.getGroups().contains(groupRenameTarget)) {
			cancelGroupRename();
		}
		context.fill(0, 0, width, height, 0x88000000);
		int modalWidth = Math.min(900, Math.max(640, width - 80));
		int modalHeight = Math.min(500, Math.max(360, height - 120));
		int x = (width - modalWidth) / 2;
		int y = (height - modalHeight) / 2;
		groupsModalBounds = new Bounds(x, y, modalWidth, modalHeight);
		context.fill(x, y, modalWidth, modalHeight, 0xFF15151D);
		drawBorder(context, groupsModalBounds, 0xFF8A8A96);
		context.fill(x, y, modalWidth, 28, 0xFF24242D);
		context.drawCenteredText(EmiPort.literal(PlannerText.tr("groups.title", "Groups and links")), x + modalWidth / 2, y + 9, 0xFFFFFFFF);

		int leftWidth = Math.min(250, modalWidth / 3);
		int listX = x + 10;
		int listY = y + 38;
		int listWidth = leftWidth - 20;
		int footerButtonY = y + modalHeight - 28;
		int footerHelpY = footerButtonY - 17;
		int contentBottomY = footerHelpY - 7;
		int listHeight = contentBottomY - listY;
		groupListArea = new Bounds(listX - 2, listY - 2, listWidth + 4, listHeight + 4);
		context.fill(groupListArea.x(), groupListArea.y(), groupListArea.width(), groupListArea.height(), 0xFF101017);
		drawBorder(context, groupListArea, BORDER_COLOR);

		List<Group> flattened = flattenGroups(line);
		int totalRows = flattened.size() + 1;
		int visibleRows = Math.max(1, listHeight / 24);
		groupListScroll = Math.max(0, Math.min(groupListScroll, Math.max(0, totalRows - visibleRows)));
		groupListHitboxes = new ArrayList<>();
		for (int visible = 0, logical = groupListScroll; visible < visibleRows && logical < totalRows; visible++, logical++) {
			Group group = logical == 0 ? null : flattened.get(logical - 1);
			int rowY = listY + visible * 24;
			int depth = group == null ? 0 : groupDepth(line, group);
			Bounds bounds = new Bounds(listX, rowY, listWidth, 21);
			Bounds collapse = group == null ? EMPTY : new Bounds(listX + 3 + Math.min(72, depth * 11), rowY + 2, 17, 17);
			boolean selected = group == selectedGroup;
			boolean hovered = bounds.contains(mouseX, mouseY);
			context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), selected ? ACTIVE_COLOR : hovered ? HOVER_COLOR : 0xFF24242C);
			drawBorder(context, bounds, selected ? 0xFF9ED5BE : BORDER_COLOR);
			String name = group == null ? PlannerText.tr("groups.root", "Root") : group.getDisplayName(line);
			int textX = bounds.x() + 5;
			if (group != null) {
				drawButton(context, collapse, mouseX, mouseY, group.isCollapsed() ? ">" : "v", group.isCollapsed());
				textX = collapse.right() + 4;
			}
			String label = textRenderer.trimToWidth(name, Math.max(12, bounds.right() - textX - 5));
			context.drawTextWithShadow(EmiPort.literal(label), textX, bounds.y() + 7, 0xFFFFFFFF);
			groupListHitboxes.add(new GroupListHitbox(bounds, collapse, group));
		}

		groupAddButton = new Bounds(listX, footerButtonY, 72, 20);
		groupChildButton = new Bounds(listX + 76, footerButtonY, 92, 20);
		groupDeleteButton = new Bounds(listX + 172, footerButtonY, Math.max(48, listWidth - 172), 20);
		drawButton(context, groupAddButton, mouseX, mouseY, PlannerText.tr("groups.new", "+ GROUP"), false);
		drawButton(context, groupChildButton, mouseX, mouseY, PlannerText.tr("groups.child", "+ CHILD"), selectedGroup != null);
		drawButton(context, groupDeleteButton, mouseX, mouseY, PlannerText.tr("groups.delete", "DELETE"), false);

		int rightX = x + leftWidth + 8;
		int rightWidth = modalWidth - leftWidth - 18;
		String selectedName = selectedGroup == null ? PlannerText.tr("groups.root", "Root") : selectedGroup.getDisplayName(line);
		groupSelectedNameBounds = new Bounds(rightX, y + 34, Math.min(210, Math.max(110, rightWidth - 270)), 20);
		context.drawTextWithShadow(EmiPort.literal(textRenderer.trimToWidth(selectedName, groupSelectedNameBounds.width() - 4)), rightX, y + 39, 0xFFFFFFFF);
		groupRenameButton = selectedGroup == null ? EMPTY : new Bounds(groupSelectedNameBounds.right() + 6, y + 34, 72, 20);
		groupCollapseButton = selectedGroup == null ? EMPTY : new Bounds(groupRenameButton.right() + 6, y + 34, 78, 20);
		if (selectedGroup != null) {
			drawButton(context, groupRenameButton, mouseX, mouseY, PlannerText.tr("groups.rename", "RENAME"), false);
			drawButton(context, groupCollapseButton, mouseX, mouseY,
				selectedGroup.isCollapsed() ? PlannerText.tr("groups.expand", "EXPAND") : PlannerText.tr("groups.collapse", "COLLAPSE"), selectedGroup.isCollapsed());
		}
		if (selectedGroup != null) {
			Group parent = selectedGroup.getParent(line);
			String parentName = parent == null ? PlannerText.tr("groups.root", "Root") : parent.getDisplayName(line);
			int parentX = groupCollapseButton == EMPTY ? rightX + 160 : groupCollapseButton.right() + 8;
			if (parentX < rightX + rightWidth - 30) {
				context.drawTextWithShadow(EmiPort.literal(textRenderer.trimToWidth("< " + parentName, rightX + rightWidth - parentX)), parentX, y + 39, 0xFF9696A2);
			}
		}

		int recipesTitleY = y + 61;
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("groups.recipes", "Recipes")), rightX, recipesTitleY, 0xFFC8C8D0);
		int recipesY = recipesTitleY + 16;
		int recipesHeight = Math.max(110, (modalHeight - 126) / 2);
		groupRecipeArea = new Bounds(rightX, recipesY, rightWidth, recipesHeight);
		context.fill(groupRecipeArea.x(), groupRecipeArea.y(), groupRecipeArea.width(), groupRecipeArea.height(), 0xFF101017);
		drawBorder(context, groupRecipeArea, BORDER_COLOR);
		List<Entry> entries = line.getEntries();
		int recipeVisible = Math.max(1, (recipesHeight - 4) / 25);
		groupRecipeScroll = Math.max(0, Math.min(groupRecipeScroll, Math.max(0, entries.size() - recipeVisible)));
		groupRecipeHitboxes = new ArrayList<>();
		if (entries.isEmpty()) {
			context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("groups.empty_recipes", "There are no recipes in this line yet")), rightX + 7, recipesY + 8, 0xFF8E8E99);
		} else {
			for (int visible = 0, index = groupRecipeScroll; visible < recipeVisible && index < entries.size(); visible++, index++) {
				Entry entry = entries.get(index);
				int rowY = recipesY + 3 + visible * 25;
				Bounds rowBounds = new Bounds(rightX + 2, rowY, rightWidth - 4, 23);
				if (recipeDragActive && pendingRecipeDrag == entry) {
					context.fill(rowBounds.x(), rowBounds.y(), rowBounds.width(), rowBounds.height(), 0x553F806B);
				}
				EmiRecipe recipe = entry.getRecipe();
				EmiStack icon = recipe == null ? EmiStack.EMPTY : firstOutput(recipe);
				if (!icon.isEmpty()) {
					context.drawStack(icon, rightX + 4, rowY + 2, EmiIngredient.RENDER_ICON);
				}
				String name = recipe == null ? entry.getRecipeId().toString() : recipeName(recipe);
				int selectedId = selectedGroup == null ? 0 : selectedGroup.getId();
				boolean inSelected = entry.getGroupId() == selectedId;
				String currentGroup = line.getEntryGroupName(entry);
				String display = textRenderer.trimToWidth(name + "  [" + currentGroup + "]", Math.max(80, rightWidth - 132));
				context.drawTextWithShadow(EmiPort.literal(display), rightX + 27, rowY + 7, 0xFFE3E3E8);
				Bounds move = new Bounds(rightX + rightWidth - 96, rowY + 2, 90, 20);
				drawButton(context, move, mouseX, mouseY, inSelected ? PlannerText.tr("groups.in_group", "IN GROUP") : PlannerText.tr("groups.move_here", "MOVE HERE"), inSelected);
				groupRecipeHitboxes.add(new GroupRecipeHitbox(rowBounds, move, entry));
			}
		}

		int linksTitleY = recipesY + recipesHeight + 12;
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("groups.links", "Links")), rightX, linksTitleY, 0xFFC8C8D0);
		int linksY = linksTitleY + 16;
		int linksHeight = Math.max(72, contentBottomY - linksY);
		groupLinkArea = new Bounds(rightX, linksY, rightWidth, linksHeight);
		context.fill(groupLinkArea.x(), groupLinkArea.y(), groupLinkArea.width(), groupLinkArea.height(), 0xFF101017);
		drawBorder(context, groupLinkArea, BORDER_COLOR);
		List<EmiStack> links = ProductionPlanner.getGroupLinkCandidates(line, selectedGroup);
		int linkVisible = Math.max(1, (linksHeight - 4) / 25);
		groupLinkScroll = Math.max(0, Math.min(groupLinkScroll, Math.max(0, links.size() - linkVisible)));
		groupLinkHitboxes = new ArrayList<>();
		if (links.isEmpty()) {
			String empty = PlannerText.tr("groups.empty_links", "No resources are both produced and consumed at this level");
			context.drawTextWithShadow(EmiPort.literal(textRenderer.trimToWidth(empty, rightWidth - 12)), rightX + 6, linksY + 8, 0xFF8E8E99);
		} else {
			for (int visible = 0, index = groupLinkScroll; visible < linkVisible && index < links.size(); visible++, index++) {
				EmiStack stack = links.get(index);
				int rowY = linksY + 3 + visible * 25;
				context.drawStack(stack, rightX + 4, rowY + 2, EmiIngredient.RENDER_ICON);
				String name = textRenderer.trimToWidth(stack.getName().getString(), Math.max(60, rightWidth - 130));
				context.drawTextWithShadow(EmiPort.literal(name), rightX + 27, rowY + 7, 0xFFE3E3E8);
				LinkMode mode = line.getLinkMode(selectedGroup, stack);
				Bounds modeBounds = new Bounds(rightX + rightWidth - 96, rowY + 2, 90, 20);
				String modeText = mode == LinkMode.MATCH ? PlannerText.tr("groups.match", "MATCH") : PlannerText.tr("groups.ignore", "IGNORE");
				drawButton(context, modeBounds, mouseX, mouseY, modeText, mode == LinkMode.MATCH);
				groupLinkHitboxes.add(new GroupLinkHitbox(modeBounds, stack, mode));
			}
		}

		groupCloseButton = new Bounds(x + modalWidth - 82, footerButtonY, 72, 20);
		drawButton(context, groupCloseButton, mouseX, mouseY, PlannerText.tr("groups.close", "CLOSE"), false);
		String help = PlannerText.tr("groups.match_help", "MATCH: resource must balance inside this group") + "   |   "
			+ PlannerText.tr("groups.ignore_help", "IGNORE: resource may flow to the parent group");
		context.drawTextWithShadow(EmiPort.literal(textRenderer.trimToWidth(help, modalWidth - 20)), x + 10, footerHelpY, 0xFF80808C);

		if (groupRenameField != null) {
			groupRenameField.render(context.raw(), mouseX, mouseY, delta);
		}
		if (groupDragActive && pendingGroupDrag != null) {
			renderGroupDragOverlay(context, line, mouseX, mouseY);
		} else if (recipeDragActive && pendingRecipeDrag != null) {
			renderRecipeDragOverlay(context, line, mouseX, mouseY);
		}
		context.pop();
	}

	private List<Group> flattenGroups(Line line) {
		List<Group> result = new ArrayList<>();
		appendGroupChildren(line, 0, result);
		return result;
	}

	private void appendGroupChildren(Line line, int parentId, List<Group> result) {
		for (Group group : line.getGroups()) {
			if (group.getParentId() == parentId) {
				result.add(group);
				if (!group.isCollapsed()) {
					appendGroupChildren(line, group.getId(), result);
				}
			}
		}
	}

	private int groupDepth(Line line, Group group) {
		int depth = 0;
		Group cursor = group;
		while (cursor != null && cursor.getParentId() > 0 && depth < 8) {
			depth++;
			cursor = cursor.getParent(line);
		}
		return depth;
	}

	private boolean applyGroupDrop(Line line, Group dragged, int mx, int my) {
		if (line == null || dragged == null) {
			return false;
		}
		for (GroupListHitbox hitbox : groupListHitboxes) {
			if (!hitbox.bounds.contains(mx, my)) {
				continue;
			}
			Group target = hitbox.group;
			if (target == dragged) {
				return false;
			}
			if (target == null) {
				ProductionPlanner.moveGroup(line, dragged, null, siblingCount(line, 0));
				selectedGroup = dragged;
				groupListScroll = 0;
				rowScroll = 0;
				return true;
			}
			if (isGroupDescendant(line, target, dragged)) {
				return false;
			}
			int localY = my - hitbox.bounds.y();
			int edge = Math.max(5, hitbox.bounds.height() / 3);
			if (localY < edge) {
				Group parent = target.getParent(line);
				ProductionPlanner.moveGroup(line, dragged, parent, siblingIndex(line, target));
			} else if (localY >= hitbox.bounds.height() - edge) {
				Group parent = target.getParent(line);
				ProductionPlanner.moveGroup(line, dragged, parent, siblingIndex(line, target) + 1);
			} else {
				ProductionPlanner.moveGroup(line, dragged, target, siblingCount(line, target.getId()));
			}
			selectedGroup = dragged;
			groupListScroll = 0;
			rowScroll = 0;
			return true;
		}
		if (groupListArea.contains(mx, my)) {
			ProductionPlanner.moveGroup(line, dragged, null, siblingCount(line, 0));
			selectedGroup = dragged;
			groupListScroll = 0;
			rowScroll = 0;
			return true;
		}
		return false;
	}

	private boolean applyRecipeDrop(Line line, Entry dragged, int mx, int my) {
		if (line == null || dragged == null) {
			return false;
		}
		for (GroupListHitbox hitbox : groupListHitboxes) {
			if (hitbox.bounds.contains(mx, my)) {
				ProductionPlanner.setEntryGroup(line, dragged, hitbox.group);
				selectedGroup = hitbox.group;
				rowScroll = 0;
				return true;
			}
		}
		for (GroupRecipeHitbox hitbox : groupRecipeHitboxes) {
			if (!hitbox.rowBounds.contains(mx, my) || hitbox.entry == dragged) {
				continue;
			}
			int targetIndex = line.getEntries().indexOf(hitbox.entry);
			if (targetIndex < 0) {
				continue;
			}
			if (my >= hitbox.rowBounds.y() + hitbox.rowBounds.height() / 2) {
				targetIndex++;
			}
			ProductionPlanner.moveEntry(line, dragged, targetIndex);
			groupRecipeScroll = 0;
			rowScroll = 0;
			return true;
		}
		return false;
	}

	private int siblingIndex(Line line, Group target) {
		if (line == null || target == null) {
			return 0;
		}
		int index = 0;
		for (Group group : line.getGroups()) {
			if (group.getParentId() != target.getParentId()) {
				continue;
			}
			if (group == target) {
				return index;
			}
			index++;
		}
		return index;
	}

	private int siblingCount(Line line, int parentId) {
		if (line == null) {
			return 0;
		}
		int count = 0;
		for (Group group : line.getGroups()) {
			if (group.getParentId() == parentId) {
				count++;
			}
		}
		return count;
	}

	private boolean isGroupDescendant(Line line, Group candidate, Group ancestor) {
		if (line == null || candidate == null || ancestor == null) {
			return false;
		}
		Group cursor = candidate;
		int guard = 0;
		while (cursor != null && guard++ < line.getGroups().size() + 1) {
			if (cursor == ancestor) {
				return true;
			}
			cursor = cursor.getParent(line);
		}
		return false;
	}

	private void renderGroupDragOverlay(EmiDrawContext context, Line line, int mouseX, int mouseY) {
		String action = PlannerText.tr("groups.drag_group", "Move group");
		for (GroupListHitbox hitbox : groupListHitboxes) {
			if (!hitbox.bounds.contains(mouseX, mouseY) || hitbox.group == pendingGroupDrag) {
				continue;
			}
			Group target = hitbox.group;
			if (target != null && isGroupDescendant(line, target, pendingGroupDrag)) {
				drawBorder(context, hitbox.bounds, 0xFFFF6666);
				action = PlannerText.tr("groups.invalid_drop", "Cannot move a group into itself");
				break;
			}
			int localY = mouseY - hitbox.bounds.y();
			int edge = Math.max(5, hitbox.bounds.height() / 3);
			if (target == null) {
				drawBorder(context, hitbox.bounds, 0xFF8CCFB8);
				action = PlannerText.tr("groups.drop_root", "Move to Root");
			} else if (localY < edge) {
				context.fill(hitbox.bounds.x(), hitbox.bounds.y(), hitbox.bounds.width(), 2, 0xFF9FE2C9);
				action = PlannerText.tr("groups.drop_before", "Place before") + " " + target.getDisplayName(line);
			} else if (localY >= hitbox.bounds.height() - edge) {
				context.fill(hitbox.bounds.x(), hitbox.bounds.bottom() - 2, hitbox.bounds.width(), 2, 0xFF9FE2C9);
				action = PlannerText.tr("groups.drop_after", "Place after") + " " + target.getDisplayName(line);
			} else {
				drawBorder(context, hitbox.bounds, 0xFF9FE2C9);
				action = PlannerText.tr("groups.drop_inside", "Move inside") + " " + target.getDisplayName(line);
			}
			break;
		}
		drawDragLabel(context, mouseX, mouseY, action);
	}

	private void renderRecipeDragOverlay(EmiDrawContext context, Line line, int mouseX, int mouseY) {
		String action = PlannerText.tr("groups.drag_recipe", "Move recipe");
		for (GroupListHitbox hitbox : groupListHitboxes) {
			if (hitbox.bounds.contains(mouseX, mouseY)) {
				drawBorder(context, hitbox.bounds, 0xFF9FE2C9);
				String name = hitbox.group == null ? PlannerText.tr("groups.root", "Root") : hitbox.group.getDisplayName(line);
				action = PlannerText.tr("groups.drop_recipe_group", "Move recipe to") + " " + name;
				drawDragLabel(context, mouseX, mouseY, action);
				return;
			}
		}
		for (GroupRecipeHitbox hitbox : groupRecipeHitboxes) {
			if (hitbox.rowBounds.contains(mouseX, mouseY) && hitbox.entry != pendingRecipeDrag) {
				int lineY = mouseY < hitbox.rowBounds.y() + hitbox.rowBounds.height() / 2
					? hitbox.rowBounds.y() : hitbox.rowBounds.bottom() - 2;
				context.fill(hitbox.rowBounds.x(), lineY, hitbox.rowBounds.width(), 2, 0xFF9FE2C9);
				action = PlannerText.tr("groups.reorder_recipe", "Reorder recipe");
				break;
			}
		}
		drawDragLabel(context, mouseX, mouseY, action);
	}

	private void drawDragLabel(EmiDrawContext context, int mouseX, int mouseY, String value) {
		String label = textRenderer.trimToWidth(value, 220);
		int w = textRenderer.getWidth(label) + 8;
		int x = Math.min(width - w - 4, mouseX + 12);
		int y = Math.min(height - 18, mouseY + 10);
		context.fill(x, y, w, 16, 0xEE202027);
		drawBorder(context, new Bounds(x, y, w, 16), 0xFF8A8A96);
		context.drawTextWithShadow(EmiPort.literal(label), x + 4, y + 4, 0xFFFFFFFF);
	}

	private EmiStack firstOutput(EmiRecipe recipe) {
		if (recipe != null) {
			for (EmiStack stack : recipe.getOutputs()) {
				if (stack != null && !stack.isEmpty()) {
					return stack;
				}
			}
		}
		return EmiStack.EMPTY;
	}

	private void renderDropdowns(EmiDrawContext context, Line line, int mouseX, int mouseY) {
		activeDropdownBounds = EMPTY;
		machineOptionHitboxes = List.of();
		voltageOptionHitboxes = List.of();
		if (coilMenuOpen && machineConfigEntry != null) {
			renderCoilDropdown(context, mouseX, mouseY);
		} else if (machineConfigEntry != null) {
			renderMachineConfig(context, mouseX, mouseY);
		} else if (machineMenuEntry != null) {
			renderMachineDropdown(context, mouseX, mouseY);
		} else if (voltageMenuEntry != null || standardVoltageMenuOpen) {
			renderVoltageDropdown(context, line, mouseX, mouseY);
		}
	}

	private void renderMachineDropdown(EmiDrawContext context, int mouseX, int mouseY) {
		List<MachineProfile> profiles = ProductionPlanner.getCompatibleMachineProfiles(machineMenuEntry);
		if (profiles.isEmpty()) {
			closeDropdowns();
			return;
		}
		int visible = Math.min(MENU_MAX_ROWS, profiles.size());
		machineMenuScroll = Math.max(0, Math.min(machineMenuScroll, Math.max(0, profiles.size() - visible)));
		int menuWidth = 236;
		int menuHeight = MENU_HEADER_HEIGHT + visible * MENU_ROW_HEIGHT + 2;
		int x = Math.max(4, Math.min(machineMenuAnchor.x(), width - menuWidth - 4));
		int y = machineMenuAnchor.bottom() + 2;
		if (y + menuHeight > height - FOOTER_HEIGHT - 2) {
			y = Math.max(HEADER_HEIGHT + 2, machineMenuAnchor.y() - menuHeight - 2);
		}
		activeDropdownBounds = new Bounds(x, y, menuWidth, menuHeight);
		context.fill(x, y, menuWidth, menuHeight, 0xFF111118);
		drawBorder(context, activeDropdownBounds, 0xFFD0D0D8);
		context.fill(x + 1, y + 1, menuWidth - 2, MENU_HEADER_HEIGHT - 1, 0xFF262630);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("machine.select", "Select machine")), x + 7, y + 6, 0xFFFFFFFF);
		if (profiles.size() > visible) {
			String marker = (machineMenuScroll > 0 ? "^ " : "") + (machineMenuScroll + visible < profiles.size() ? "v" : "");
			context.drawTextWithShadow(EmiPort.literal(marker), x + menuWidth - 20, y + 6, 0xFFB8B8C0);
		}
		List<MachineOptionHitbox> hitboxes = new ArrayList<>();
		for (int i = 0; i < visible; i++) {
			MachineProfile profile = profiles.get(machineMenuScroll + i);
			Bounds row = new Bounds(x + 2, y + MENU_HEADER_HEIGHT + i * MENU_ROW_HEIGHT, menuWidth - 4, MENU_ROW_HEIGHT);
			boolean selected = profile.id().equals(machineMenuEntry.getMachineProfileId());
			boolean hovered = row.contains(mouseX, mouseY);
			context.fill(row.x(), row.y(), row.width(), row.height(), selected ? ACTIVE_COLOR : hovered ? HOVER_COLOR : 0xFF18181F);
			if (selected) {
				context.fill(row.x(), row.y(), 3, row.height(), 0xFF7FD8A1);
			}
			int textX = row.x() + 8;
			if (profile.hasIcon()) {
				context.drawStack(profile.icon(), row.x() + 6, row.y() + 3, EmiIngredient.RENDER_ICON);
				textX = row.x() + 28;
			}
			String name = textRenderer.trimToWidth(profile.displayName(), Math.max(8, row.right() - textX - 6));
			context.drawTextWithShadow(EmiPort.literal(name), textX, row.y() + 7, 0xFFFFFFFF);
			hitboxes.add(new MachineOptionHitbox(row, profile));
		}
		machineOptionHitboxes = hitboxes;
	}

	private void renderVoltageDropdown(EmiDrawContext context, Line line, int mouseX, int mouseY) {
		boolean standard = standardVoltageMenuOpen;
		List<Integer> tiers = new ArrayList<>();
		if (standard) {
			tiers.add(-1);
			for (int tier = 0; tier <= ProductionPlanner.maxVoltageTier(); tier++) {
				tiers.add(tier);
			}
		} else if (voltageMenuEntry != null && voltageMenuEntry.getRecipeEUt() > 0L) {
			int minimum = Math.max(0, voltageMenuEntry.getRecipeTier());
			for (int tier = minimum; tier <= ProductionPlanner.maxVoltageTier(); tier++) {
				tiers.add(tier);
			}
		}
		if (tiers.isEmpty()) {
			closeDropdowns();
			return;
		}
		int visible = Math.min(MENU_MAX_ROWS, tiers.size());
		int scroll = standard ? standardVoltageMenuScroll : voltageMenuScroll;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, tiers.size() - visible)));
		if (standard) {
			standardVoltageMenuScroll = scroll;
		} else {
			voltageMenuScroll = scroll;
		}
		Bounds anchor = standard ? standardVoltageBounds : voltageMenuAnchor;
		int menuWidth = 126;
		int menuHeight = MENU_HEADER_HEIGHT + visible * MENU_ROW_HEIGHT + 2;
		int x = Math.max(4, Math.min(anchor.x(), width - menuWidth - 4));
		int y = standard ? anchor.y() - menuHeight - 2 : anchor.bottom() + 2;
		if (!standard && y + menuHeight > height - FOOTER_HEIGHT - 2) {
			y = Math.max(HEADER_HEIGHT + 2, anchor.y() - menuHeight - 2);
		}
		activeDropdownBounds = new Bounds(x, y, menuWidth, menuHeight);
		context.fill(x, y, menuWidth, menuHeight, 0xFF111118);
		drawBorder(context, activeDropdownBounds, 0xFFD0D0D8);
		context.fill(x + 1, y + 1, menuWidth - 2, MENU_HEADER_HEIGHT - 1, 0xFF262630);
		context.drawTextWithShadow(EmiPort.literal(standard ? "Standard voltage" : "Select voltage"), x + 7, y + 6, 0xFFFFFFFF);
		List<VoltageOptionHitbox> hitboxes = new ArrayList<>();
		int selectedTier = standard ? line.getStandardVoltageTier() : voltageMenuEntry.getVoltageTier();
		for (int i = 0; i < visible; i++) {
			int tier = tiers.get(scroll + i);
			Bounds row = new Bounds(x + 2, y + MENU_HEADER_HEIGHT + i * MENU_ROW_HEIGHT, menuWidth - 4, MENU_ROW_HEIGHT);
			boolean selected = tier == selectedTier;
			boolean hovered = row.contains(mouseX, mouseY);
			context.fill(row.x(), row.y(), row.width(), row.height(), selected ? ACTIVE_COLOR : hovered ? HOVER_COLOR : 0xFF18181F);
			if (selected) {
				context.fill(row.x(), row.y(), 3, row.height(), 0xFF7FD8A1);
			}
			String name = ProductionPlanner.voltageTierName(tier);
			context.drawTextWithShadow(EmiPort.literal(name), row.x() + 8, row.y() + 7, ProductionPlanner.voltageTierColor(tier));
			hitboxes.add(new VoltageOptionHitbox(row, tier));
		}
		voltageOptionHitboxes = hitboxes;
	}

	private void renderMachineConfig(EmiDrawContext context, int mouseX, int mouseY) {
		MachineProfile profile = machineConfigEntry.getMachineProfile();
		if (!profile.hasConfigurableSettings()) {
			closeDropdowns();
			return;
		}
		boolean coils = profile.coilEfficiencyPerTier() > 0.0D;
		boolean parallelControl = profile.parallelControl();
		List<MachineSettingSpec> specialSettings = ProductionPlanner.getMachineSettingSpecs(machineConfigEntry);
		int rows = (coils ? 1 : 0) + (parallelControl ? 1 : 0) + specialSettings.size();
		int menuWidth = specialSettings.isEmpty() ? 264 : 316;
		int menuHeight = MENU_HEADER_HEIGHT + rows * 26 + 20;
		int x = Math.max(4, Math.min(machineConfigAnchor.x(), width - menuWidth - 4));
		int y = machineConfigAnchor.bottom() + 2;
		if (y + menuHeight > height - FOOTER_HEIGHT - 2) {
			y = Math.max(HEADER_HEIGHT + 2, machineConfigAnchor.y() - menuHeight - 2);
		}
		activeDropdownBounds = new Bounds(x, y, menuWidth, menuHeight);
		context.fill(x, y, menuWidth, menuHeight, 0xFF111118);
		drawBorder(context, activeDropdownBounds, 0xFFD0D0D8);
		context.fill(x + 1, y + 1, menuWidth - 2, MENU_HEADER_HEIGHT - 1, 0xFF262630);
		String titleText = PlannerText.tr("machine.settings", "Machine settings") + " - " + profile.displayName();
		String title = textRenderer.trimToWidth(titleText, menuWidth - 12);
		context.drawTextWithShadow(EmiPort.literal(title), x + 7, y + 6, 0xFFFFFFFF);

		configCoilMinus = EMPTY;
		configCoilValue = EMPTY;
		configCoilPlus = EMPTY;
		configParallelMinus = EMPTY;
		configParallelValue = EMPTY;
		configParallelPlus = EMPTY;
		List<MachineSettingControl> specialControls = new ArrayList<>();
		int cy = y + MENU_HEADER_HEIGHT + 4;
		if (coils) {
			context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("machine.coils", "Coils") + ":"), x + 8, cy + 5, 0xFFC8C8D0);
			configCoilMinus = new Bounds(x + 70, cy + 1, 18, 18);
			configCoilValue = new Bounds(x + 90, cy, 136, 20);
			configCoilPlus = new Bounds(x + 228, cy + 1, 18, 18);
			drawButton(context, configCoilMinus, mouseX, mouseY, "-", false);
			drawValueBox(context, configCoilValue, mouseX, mouseY, machineConfigEntry.getCoilName(), machineConfigEntry.getCoilTier() > 0);
			drawButton(context, configCoilPlus, mouseX, mouseY, "+", false);
			cy += 26;
		}
		if (parallelControl) {
			String parallelLabel = PlannerText.tr("machine.parallel_control", "Parallel Control") + ":";
			context.drawTextWithShadow(EmiPort.literal(parallelLabel), x + 8, cy + 5, 0xFFC8C8D0);
			int plusX = x + menuWidth - 36;
			configParallelMinus = new Bounds(plusX - 96, cy + 1, 18, 18);
			configParallelValue = new Bounds(plusX - 76, cy, 74, 20);
			configParallelPlus = new Bounds(plusX, cy + 1, 18, 18);
			drawButton(context, configParallelMinus, mouseX, mouseY, "-", false);
			drawValueBox(context, configParallelValue, mouseX, mouseY, Integer.toString(machineConfigEntry.getParallel()), true);
			drawButton(context, configParallelPlus, mouseX, mouseY, "+", false);
			cy += 26;
		}
		for (MachineSettingSpec spec : specialSettings) {
			int plusX = x + menuWidth - 36;
			int valueWidth = 84;
			int valueX = plusX - valueWidth - 2;
			int minusX = valueX - 20;
			String label = PlannerText.tr(spec.labelKey(), spec.englishLabel()) + ":";
			label = textRenderer.trimToWidth(label, Math.max(40, minusX - x - 16));
			context.drawTextWithShadow(EmiPort.literal(label), x + 8, cy + 5, 0xFFC8C8D0);
			Bounds minus = new Bounds(minusX, cy + 1, 18, 18);
			Bounds value = new Bounds(valueX, cy, valueWidth, 20);
			Bounds plus = new Bounds(plusX, cy + 1, 18, 18);
			drawButton(context, minus, mouseX, mouseY, "-", false);
			drawValueBox(context, value, mouseX, mouseY, machineConfigEntry.getMachineSettingDisplayValue(spec),
				machineConfigEntry.getMachineSettingValue(spec) != spec.defaultValue());
			drawButton(context, plus, mouseX, mouseY, "+", false);
			specialControls.add(new MachineSettingControl(spec, minus, value, plus));
			cy += 26;
		}
		configSpecialControls = List.copyOf(specialControls);
		String info;
		if (!specialSettings.isEmpty()) {
			info = PlannerText.tr("machine.special_help", "Special machine settings are applied to line calculations");
		} else if (coils) {
			info = PlannerText.tr("machine.coil_help", "Coil bonus affects both duration and EU/t multiplicatively");
		} else {
			info = PlannerText.tr("machine.parallel_help", "Parallel Control mirrors the row PAR setting");
		}
		context.drawTextWithShadow(EmiPort.literal(textRenderer.trimToWidth(info, menuWidth - 16)), x + 8, y + menuHeight - 14, 0xFF858590);
	}

	private void renderCoilDropdown(EmiDrawContext context, int mouseX, int mouseY) {
		int count = ProductionPlanner.maxCoilTier() + 1;
		int menuWidth = 180;
		int menuHeight = MENU_HEADER_HEIGHT + count * MENU_ROW_HEIGHT + 2;
		int x = Math.max(4, Math.min(coilMenuAnchor.x(), width - menuWidth - 4));
		int y = coilMenuAnchor.bottom() + 2;
		if (y + menuHeight > height - FOOTER_HEIGHT - 2) {
			y = Math.max(HEADER_HEIGHT + 2, coilMenuAnchor.y() - menuHeight - 2);
		}
		activeDropdownBounds = new Bounds(x, y, menuWidth, menuHeight);
		context.fill(x, y, menuWidth, menuHeight, 0xFF111118);
		drawBorder(context, activeDropdownBounds, 0xFFD0D0D8);
		context.fill(x + 1, y + 1, menuWidth - 2, MENU_HEADER_HEIGHT - 1, 0xFF262630);
		context.drawTextWithShadow(EmiPort.literal(PlannerText.tr("machine.select_coils", "Select coils")), x + 7, y + 6, 0xFFFFFFFF);
		List<CoilOptionHitbox> hitboxes = new ArrayList<>();
		for (int tier = 0; tier < count; tier++) {
			Bounds row = new Bounds(x + 2, y + MENU_HEADER_HEIGHT + tier * MENU_ROW_HEIGHT, menuWidth - 4, MENU_ROW_HEIGHT);
			boolean selected = machineConfigEntry.getCoilTier() == tier;
			boolean hovered = row.contains(mouseX, mouseY);
			context.fill(row.x(), row.y(), row.width(), row.height(), selected ? ACTIVE_COLOR : hovered ? HOVER_COLOR : 0xFF18181F);
			if (selected) {
				context.fill(row.x(), row.y(), 3, row.height(), 0xFF7FD8A1);
			}
			String suffix = tier == 0 ? " (base)" : " (+" + tier + ")";
			context.drawTextWithShadow(EmiPort.literal(ProductionPlanner.coilTierName(tier) + suffix), row.x() + 8, row.y() + 7, 0xFFFFFFFF);
			hitboxes.add(new CoilOptionHitbox(row, tier));
		}
		coilOptionHitboxes = hitboxes;
	}

	private boolean isDropdownOpen() {
		return machineConfigEntry != null || machineMenuEntry != null || voltageMenuEntry != null || standardVoltageMenuOpen;
	}

	private void closeDropdowns() {
		machineMenuEntry = null;
		machineConfigEntry = null;
		voltageMenuEntry = null;
		standardVoltageMenuOpen = false;
		machineMenuAnchor = EMPTY;
		machineConfigAnchor = EMPTY;
		voltageMenuAnchor = EMPTY;
		configCoilMinus = EMPTY;
		configCoilValue = EMPTY;
		configCoilPlus = EMPTY;
		configParallelMinus = EMPTY;
		configParallelValue = EMPTY;
		configParallelPlus = EMPTY;
		configSpecialControls = List.of();
		coilMenuOpen = false;
		coilMenuAnchor = EMPTY;
		coilOptionHitboxes = List.of();
		activeDropdownBounds = EMPTY;
		machineOptionHitboxes = List.of();
		voltageOptionHitboxes = List.of();
	}

	private void openMachineMenu(RowHitbox row) {
		closeDropdowns();
		machineMenuEntry = row.entry;
		machineMenuAnchor = row.machineProfile;
		List<MachineProfile> profiles = ProductionPlanner.getCompatibleMachineProfiles(row.entry);
		int current = 0;
		for (int i = 0; i < profiles.size(); i++) {
			if (profiles.get(i).id().equals(row.entry.getMachineProfileId())) {
				current = i;
				break;
			}
		}
		machineMenuScroll = Math.max(0, current - MENU_MAX_ROWS / 2);
	}

	private void openMachineConfig(RowHitbox row) {
		if (row == null || !row.entry.getMachineProfile().hasConfigurableSettings()) {
			return;
		}
		closeDropdowns();
		machineConfigEntry = row.entry;
		machineConfigAnchor = row.machineConfig;
	}

	private void openVoltageMenu(RowHitbox row) {
		if (row.entry.getRecipeEUt() <= 0L) {
			return;
		}
		closeDropdowns();
		voltageMenuEntry = row.entry;
		voltageMenuAnchor = row.voltage;
		int minimum = Math.max(0, row.entry.getRecipeTier());
		voltageMenuScroll = Math.max(0, row.entry.getVoltageTier() - minimum - MENU_MAX_ROWS / 2);
	}

	private void openStandardVoltageMenu(Line line) {
		closeDropdowns();
		standardVoltageMenuOpen = true;
		int index = line.getStandardVoltageTier() < 0 ? 0 : line.getStandardVoltageTier() + 1;
		standardVoltageMenuScroll = Math.max(0, index - MENU_MAX_ROWS / 2);
	}

	private void renderDropdownTooltip(EmiDrawContext context, int mouseX, int mouseY) {
		if (coilMenuOpen && machineConfigEntry != null) {
			for (CoilOptionHitbox option : coilOptionHitboxes) {
				if (option.bounds.contains(mouseX, mouseY)) {
					double multiplier = Math.pow(Math.max(0.01D, 1.0D - machineConfigEntry.getMachineProfile().coilEfficiencyPerTier()), option.tier);
					drawTooltip(context, mouseX, mouseY, ProductionPlanner.coilTierName(option.tier),
						"Tier above Cupronickel: " + option.tier,
						"Duration/EU multiplier: x" + formatExactRate(multiplier));
					return;
				}
			}
			return;
		}
		if (machineConfigEntry != null) {
			MachineProfile profile = machineConfigEntry.getMachineProfile();
			if (configCoilValue.contains(mouseX, mouseY)) {
				double perTier = profile.coilEfficiencyPerTier() * 100.0D;
				drawTooltip(context, mouseX, mouseY, "Coils: " + machineConfigEntry.getCoilName(),
					"Tier above Cupronickel: " + machineConfigEntry.getCoilTier(),
					"Detected bonus: -" + formatExactRate(perTier) + "% duration/EU per tier (multiplicative)",
					"Current multiplier: x" + formatExactRate(machineConfigEntry.getCoilMultiplier()),
					"Use +/- or mouse wheel");
				return;
			}
			if (configParallelValue.contains(mouseX, mouseY)) {
				drawTooltip(context, mouseX, mouseY, PlannerText.tr("machine.parallel_control", "Parallel Control") + ": " + machineConfigEntry.getParallel(),
					PlannerText.tr("machine.parallel_help", "Parallel Control mirrors the row PAR setting"),
					"Use +/- here or edit PAR in the recipe row");
				return;
			}
			for (MachineSettingControl control : configSpecialControls) {
				if (control.minus.contains(mouseX, mouseY) || control.value.contains(mouseX, mouseY) || control.plus.contains(mouseX, mouseY)) {
					MachineSettingSpec spec = control.spec;
					List<String> lines = new ArrayList<>();
					lines.add(PlannerText.tr(spec.labelKey(), spec.englishLabel()) + ": " + machineConfigEntry.getMachineSettingDisplayValue(spec));
					if (!spec.englishHelp().isBlank()) {
						lines.add(PlannerText.tr(spec.helpKey(), spec.englishHelp()));
					}
					lines.addAll(ProductionPlanner.getMachineSettingDetailLines(machineConfigEntry, spec));
					double durationMultiplier = machineConfigEntry.getMachineSettingDurationMultiplier();
					if (Math.abs(durationMultiplier - 1.0D) > EPSILON) {
						lines.add("Current duration multiplier: x" + formatExactRate(durationMultiplier));
					}
					double throughputMultiplier = machineConfigEntry.getMachineSettingThroughputMultiplier();
					if (Math.abs(throughputMultiplier - 1.0D) > EPSILON) {
						lines.add(PlannerText.tr("machine.throughput_multiplier", "Current throughput multiplier") + ": x"
							+ formatExactRate(throughputMultiplier));
					}
					lines.add("Use +/- or mouse wheel");
					drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
					return;
				}
			}
		}
		if (machineMenuEntry != null) {
			for (MachineOptionHitbox option : machineOptionHitboxes) {
				if (option.bounds.contains(mouseX, mouseY)) {
					MachineProfile profile = option.profile;
					List<String> lines = new ArrayList<>();
					lines.add(profile.displayName());
					lines.add(profile.description());
					lines.addAll(profile.modifierDescriptions());
					if (!profile.hasRuntimeModifiers()) {
						lines.add("No runtime-specific modifiers detected; Generic GT math is used");
					}
					drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
					return;
				}
			}
		}
	}

	private void renderTooltip(EmiDrawContext context, int mouseX, int mouseY) {
		if (newLineButton.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, "New production line", "Right-click a line tab to rename it");
			return;
		}
		if (closeLineButton.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, "Delete active production line");
			return;
		}
		Line activeLine = ProductionPlanner.getOrCreateActiveLine();
		for (TargetHitbox hitbox : targetHitboxes) {
			Target target = hitbox.target;
			EmiStack stack = target.getStack();
			if (hitbox.icon.contains(mouseX, mouseY)) {
				List<String> lines = new ArrayList<>();
				lines.add(stack.getName().getString());
				lines.add("Target: " + formatExactRate(target.getRate()) + unitSuffix(stack));
				if (activeLine.isBalanceEnabled() && activeLine.hasMachineCapacityShortfall()) {
					lines.add("Achievable: " + formatExactRate(activeLine.getAchievableTargetRate(target)) + unitSuffix(stack));
				}
				drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				return;
			}
			if (hitbox.rate.contains(mouseX, mouseY)) {
				List<String> lines = new ArrayList<>();
				lines.add("Requested target output rate");
				lines.add(stack.getName().getString());
				lines.add("Click to type an exact amount per second");
				lines.add("Items use /s; fluids use mB/s");
				if (activeLine.isBalanceEnabled() && activeLine.hasMachineCapacityShortfall()) {
					lines.add("Achievable: " + formatExactRate(activeLine.getAchievableTargetRate(target)) + unitSuffix(stack));
					if (!activeLine.getBottleneckName().isBlank()) {
						lines.add("Bottleneck: " + activeLine.getBottleneckName());
					}
				}
				drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				return;
			}
			if (hitbox.remove.contains(mouseX, mouseY)) {
				drawTooltip(context, mouseX, mouseY, "Remove target", stack.getName().getString());
				return;
			}
		}
		if (activeLine.getTargets().isEmpty() && targetIconBounds.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, "No balance targets", "Click recipe outputs to add one or more targets");
			return;
		}
		if (balanceButton.contains(mouseX, mouseY)) {
			List<String> lines = new ArrayList<>();
			lines.add(activeLine.isBalanceEnabled() ? "Recalculate line balance" : "Auto-balance line");
			lines.add("Matches internal produced/consumed resources");
			lines.add("and solves every selected target at its requested rate");
			lines.add("Fixed MACH/PAR values are treated as hard equipment constraints");
			lines.add("A bottleneck is propagated through every recipe in the line");
			if (activeLine.isBalanceEnabled() && activeLine.hasMachineCapacityShortfall()) {
				if (activeLine.getTargets().size() == 1) {
					lines.add("Requested: " + formatExactRate(activeLine.getTargetRate()) + unitSuffix(activeLine.getTarget()));
					lines.add("Achievable: " + formatExactRate(activeLine.getAchievableTargetRate()) + unitSuffix(activeLine.getTarget()));
				} else {
					lines.add("Targets: " + activeLine.getTargets().size());
					double percent = activeLine.getAchievableTargetRate() / Math.max(EPSILON, activeLine.getTargetRate()) * 100.0D;
					lines.add("Achievable throughput: " + formatExactRate(percent) + "%");
				}
				if (!activeLine.getBottleneckName().isBlank()) {
					lines.add("Bottleneck: " + activeLine.getBottleneckName());
				}
			}
			drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
			return;
		}
		if (clearTargetButton.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, "Clear all targets and leave balance mode");
			return;
		}
		if (standardVoltageBounds.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, "Standard Voltage for machines: " + activeLine.getStandardVoltageName(),
				"New recipes inherit this voltage automatically",
				"Left-click: choose a tier", "Right-click: Recipe minimum",
				"Rows with an individual VOLT override are preserved");
			return;
		}
		if (applyStandardVoltageBounds.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, "Apply Standard Voltage to every recipe in this Line",
				"Clears individual VOLT overrides and makes every row inherit the Line setting");
			return;
		}
		if (groupsButton.contains(mouseX, mouseY)) {
			drawTooltip(context, mouseX, mouseY, PlannerText.tr("groups.title", "Groups and links"),
				PlannerText.tr("groups.tooltip", "Nested groups and resource link rules"),
				PlannerText.tr("groups.tooltip2", "MATCH keeps a resource inside the group; IGNORE passes it to the parent"));
			return;
		}
		if (powerBounds.contains(mouseX, mouseY)) {
			PowerSummary power = calculatePower(activeLine);
			List<String> lines = new ArrayList<>();
			if (power.knownEntries > 0) {
				lines.add("Average power: " + formatExactRate(power.averageEUt) + " EU/t");
				lines.add("Calculated from current crafts/sec and selected machine profiles");
			} else {
				lines.add("Power unavailable for this line");
			}
			if (power.unknownEntries > 0) {
				lines.add(power.unknownEntries + " active recipe(s) have no detected GT EU/t");
			}
			lines.add("Profiles without exact modifiers currently fall back to generic GT math");
			drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
			return;
		}
		for (TabHitbox tab : tabHitboxes) {
			if (tab.bounds.contains(mouseX, mouseY)) {
				drawTooltip(context, mouseX, mouseY, ProductionPlanner.displayName(tab.index), "Right-click to rename");
				return;
			}
		}
		for (RowHitbox row : rowHitboxes) {
			Entry entry = row.entry;
			if (row.mode.contains(mouseX, mouseY)) {
				if (activeLine.isBalanceEnabled()) {
					drawTooltip(context, mouseX, mouseY, "BAL rate", "Rate is controlled by Line Auto-Balance",
						"Click to leave balance mode and restore AUTO/MAN rates");
				} else if (entry.isAutomatic()) {
					drawTooltip(context, mouseX, mouseY, "AUTO rate", "crafts/sec = Machines x Parallel / Recipe Duration",
						"Click to switch to manual crafts/sec");
				} else if (entry.getDurationTicks() > 0.0D) {
					drawTooltip(context, mouseX, mouseY, "MAN rate", "Direct crafts/sec control",
						"Click to switch to automatic machine calculation");
				} else {
					drawTooltip(context, mouseX, mouseY, "MAN rate", "Recipe duration was not detected",
						"Right-click Duration and enter seconds before enabling AUTO");
				}
				return;
			}
			if (row.machineProfile.contains(mouseX, mouseY)) {
				MachineProfile profile = entry.getMachineProfile();
				List<MachineProfile> compatible = ProductionPlanner.getCompatibleMachineProfiles(entry);
				List<String> lines = new ArrayList<>();
				lines.add("Machine: " + profile.displayName());
				lines.add(profile.description());
				lines.add(profile.parallelDescription());
				lines.add(profile.allowsPerfectOc() ? "Perfect OC: selectable" : "Perfect OC: unavailable for this profile");
				lines.addAll(profile.modifierDescriptions());
				if (profile.coilEfficiencyPerTier() > 0.0D) {
					lines.add("Selected coils: " + entry.getCoilName() + " (x" + formatExactRate(entry.getCoilMultiplier()) + ")");
				}
				if (profile.parallelControl()) {
					lines.add("Parallel Control setting: " + entry.getParallel());
				}
				lines.add("Left-click: open machine selector");
				lines.add("Right-click: Generic GT");
				lines.add("Compatible profiles: " + compatible.size());
				drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				return;
			}
			if (row.machineConfig.contains(mouseX, mouseY) && entry.getMachineProfile().hasConfigurableSettings()) {
				List<String> lines = new ArrayList<>();
				lines.add("Machine-specific settings");
				if (entry.getMachineProfile().coilEfficiencyPerTier() > 0.0D) {
					lines.add("Coils: " + entry.getCoilName() + " (tier +" + entry.getCoilTier() + ")");
				}
				if (entry.getMachineProfile().parallelControl()) {
					lines.add(PlannerText.tr("machine.parallel_control", "Parallel Control") + ": " + entry.getParallel());
				}
				for (MachineSettingSpec spec : ProductionPlanner.getMachineSettingSpecs(entry)) {
					lines.add(PlannerText.tr(spec.labelKey(), spec.englishLabel()) + ": " + entry.getMachineSettingDisplayValue(spec));
				}
				lines.add("Click to configure this machine");
				drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				return;
			}
			if (row.machinesLock.contains(mouseX, mouseY)) {
				drawTooltip(context, mouseX, mouseY,
					"Fixed machine count: " + (entry.isMachinesFixed() ? "ON" : "OFF"),
					"Left-click to toggle",
					entry.isMachinesFixed() ? "BALANCE will keep MACH at " + entry.getMachines() : "BALANCE may resize MACH automatically");
				return;
			}
			if (row.parallelLock.contains(mouseX, mouseY)) {
				drawTooltip(context, mouseX, mouseY,
					"Fixed parallel: " + (entry.isParallelFixed() ? "ON" : "OFF"),
					"Left-click to toggle",
					entry.isParallelFixed() ? "BALANCE will keep PAR at " + entry.getParallel() : "BALANCE may resize PAR automatically");
				return;
			}
			if (row.machineArea().contains(mouseX, mouseY)) {
				if (activeLine.isBalanceEnabled()) {
					List<String> lines = new ArrayList<>();
					lines.add("Machines: " + entry.getMachines());
					addMachineSizingTooltip(lines, entry, activeLine.getEffectiveRate(entry));
					lines.add(entry.isMachinesFixed() ? "MACH is fixed for BALANCE" : "Use the F button to fix MACH during BALANCE");
					drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				} else {
					drawTooltip(context, mouseX, mouseY, "Machines: " + entry.getMachines(),
						"Use +/- or mouse wheel", "Shift changes by 10", "Right-click the number to type an exact value");
				}
				return;
			}
			if (row.parallelArea().contains(mouseX, mouseY)) {
				int configuredMaxParallel = entry.getConfiguredMaxParallel();
				if (activeLine.isBalanceEnabled()) {
					List<String> lines = new ArrayList<>();
					lines.add("Parallel per machine: " + entry.getParallel());
					if (configuredMaxParallel > 0) {
						lines.add("Configured max parallel: " + configuredMaxParallel);
					}
					addMachineSizingTooltip(lines, entry, activeLine.getEffectiveRate(entry));
					lines.add(entry.isParallelFixed() ? "PAR is fixed for BALANCE" : "Use the F button to fix PAR during BALANCE");
					drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				} else if (configuredMaxParallel > 0) {
					drawTooltip(context, mouseX, mouseY, "Parallel per machine: " + entry.getParallel(),
						"Configured max parallel: " + configuredMaxParallel, "Use +/- or mouse wheel",
						"Shift changes by 10", "Right-click the number to type an exact value");
				} else {
					drawTooltip(context, mouseX, mouseY, "Parallel per machine: " + entry.getParallel(),
						"Use +/- or mouse wheel", "Shift changes by 10", "Right-click the number to type an exact value");
				}
				return;
			}
			if (row.voltage.contains(mouseX, mouseY)) {
				if (entry.getRecipeEUt() > 0L) {
					List<String> lines = new ArrayList<>();
					lines.add("Machine voltage: " + entry.getVoltageName() + " (" + entry.getSelectedVoltage() + " V)");
					lines.add("Recipe: " + entry.getRecipeEUt() + " EU/t, tier " + entry.getRecipeTier());
					lines.add("Overclocks: " + entry.getOverclockCount());
					if (entry.isVoltageFixedByMachine()) {
						lines.add("VOLT: fixed by selected machine profile");
					} else {
						lines.add(entry.isVoltageOverridden() ? "VOLT: individual row override" : "VOLT: inherited from Line Standard");
						lines.add("Left-click: open voltage selector");
						lines.add("Right-click: reset to Line Standard");
					}
					lines.add("Machine profile: " + entry.getMachineProfileName());
					drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				} else {
					drawTooltip(context, mouseX, mouseY, "GT EU/t was not detected for this recipe",
						"Voltage overclocking is disabled for this row");
				}
				return;
			}
			if (row.oc.contains(mouseX, mouseY)) {
				String behavior = switch (entry.getOcMode()) {
					case NONE -> "No voltage overclocking";
					case STANDARD -> Math.abs(entry.getOcDurationMultiplierPerStep() - 0.5D) > EPSILON
						? "Each OC: 4x EU/t, x" + formatExactRate(entry.getOcDurationMultiplierPerStep()) + " duration"
						: "Each OC: 4x EU/t, 2x speed";
					case PERFECT -> "Each OC: 4x EU/t, 4x speed";
				};
				drawTooltip(context, mouseX, mouseY, "OC mode: " + entry.getOcDisplayLabel(), behavior,
					"Left-click: next mode", "Right-click: previous mode",
					entry.getMachineProfile().allowsPerfectOc() ? "Perfect OC is allowed by this profile" : "This machine profile does not allow Perfect OC");
				return;
			}
			if (row.duration.contains(mouseX, mouseY)) {
				List<String> lines = new ArrayList<>();
				double baseSeconds = entry.getDurationSeconds();
				double processedSeconds = entry.getProcessedDurationSeconds();
				lines.add(baseSeconds > 0.0D ? "Base duration: " + formatExactRate(baseSeconds) + " s" : "Base duration: not detected");
				if (processedSeconds > 0.0D && entry.getOverclockCount() > 0) {
					lines.add("After " + entry.getOverclockCount() + " OC: " + formatExactRate(processedSeconds) + " s");
				}
				if (entry.getProcessedEUt() > 0L) {
					lines.add("Processed power: " + entry.getProcessedEUt() + " EU/t");
				}
				if (entry.getMachineProfile().coilEfficiencyPerTier() > 0.0D) {
					lines.add("Coils: " + entry.getCoilName() + " -> x" + formatExactRate(entry.getCoilMultiplier()) + " duration/EU");
				}
				if (Math.abs(entry.getMachineSettingDurationMultiplier() - 1.0D) > EPSILON) {
					lines.add("Machine settings -> x" + formatExactRate(entry.getMachineSettingDurationMultiplier()) + " duration");
				}
				if (Math.abs(entry.getMachineSettingThroughputMultiplier() - 1.0D) > EPSILON) {
					lines.add("Machine settings -> x" + formatExactRate(entry.getMachineSettingThroughputMultiplier()) + " throughput");
				}
				if (entry.isDurationOverridden()) {
					double detected = entry.getDetectedDurationSeconds();
					lines.add(detected > 0.0D ? "Recipe duration: " + formatExactRate(detected) + " s" : "Recipe duration unavailable");
					lines.add("* Manual base-duration override is active");
				}
				lines.add("Right-click to type base duration in seconds");
				lines.add("Middle-click to reset to recipe duration");
				drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				return;
			}
			if (row.rate.contains(mouseX, mouseY)) {
				if (activeLine.isBalanceEnabled()) {
					double balancedRate = activeLine.getEffectiveRate(entry);
					List<String> lines = new ArrayList<>();
					lines.add("Balanced crafts per second: " + formatExactRate(balancedRate));
					addMachineSizingTooltip(lines, entry, balancedRate);
					if (entry.getRecipeEUt() > 0L) {
						lines.add("Average power: " + formatExactRate(entry.getAveragePowerEUt(balancedRate)) + " EU/t");
					}
					lines.add("Resource flow stays at the exact BAL rate; spare machine capacity is not treated as extra output");
					lines.add("Click BAL mode to return to AUTO/MAN");
					drawTooltip(context, mouseX, mouseY, lines.toArray(String[]::new));
				} else if (entry.isAutomatic()) {
					double seconds = entry.getProcessedDurationSeconds();
					drawTooltip(context, mouseX, mouseY, "Crafts per second: " + formatExactRate(entry.getEffectiveRate()),
						entry.getMachines() + " machines x " + entry.getParallel() + " parallel / " + formatExactRate(seconds) + " s",
						entry.getOverclockCount() + " overclock(s), " + entry.getProcessedEUt() + " EU/t",
						"Switch to MAN to edit crafts/sec directly");
				} else {
					drawTooltip(context, mouseX, mouseY, "Manual crafts per second: " + formatExactRate(entry.getRate()),
						"Mouse wheel: 1", "Shift: 10   Ctrl: 0.1   Alt: 0.01", "Right-click to type an exact value");
				}
				return;
			}
			if (row.remove.contains(mouseX, mouseY)) {
				drawTooltip(context, mouseX, mouseY, "Remove recipe from line");
				return;
			}
		}
		for (FlowHitbox hitbox : flowHitboxes) {
			if (hitbox.bounds.contains(mouseX, mouseY)) {
				Flow flow = hitbox.flow;
				List<TooltipComponent> tooltip = new ArrayList<>();
				tooltip.add(TooltipComponent.of(EmiPort.ordered(flow.stack.getName())));
				if (flow.input > EPSILON || flow.output > EPSILON || flow.internal > EPSILON || flow.external > EPSILON) {
					tooltip.add(line("Total consumed: " + formatExactRate(flow.input) + unitSuffix(flow.stack)));
					tooltip.add(line("Total produced: " + formatExactRate(flow.output) + unitSuffix(flow.stack)));
					tooltip.add(line("Internal flow: " + formatExactRate(flow.internal) + unitSuffix(flow.stack)));
					tooltip.add(line("External input: " + formatExactRate(flow.external) + unitSuffix(flow.stack)));
					tooltip.add(line("Net output: " + formatExactRate(Math.max(0, flow.output - flow.input)) + unitSuffix(flow.stack)));
				} else {
					tooltip.add(line("Rate: " + formatExactRate(flow.displayValue) + unitSuffix(flow.stack)));
				}
				if (flow.approximate) {
					tooltip.add(line("Expected value: chance or alternative ingredient involved"));
				}
				if (hitbox.targetCandidate) {
					tooltip.add(line(isTarget(ProductionPlanner.getOrCreateActiveLine(), flow.stack)
						? "Already selected as a Line target"
						: "Left-click: add as a Line Auto-Balance target"));
				}
				EmiRenderHelper.drawTooltip(this, context, tooltip, mouseX, mouseY);
				return;
			}
		}
	}

	private void addMachineSizingTooltip(List<String> lines, Entry entry, double craftsPerSecond) {
		MachineSizing sizing = entry.getMachineSizing(craftsPerSecond);
		if (!sizing.available()) {
			lines.add("Machine sizing unavailable for this row");
			return;
		}
		lines.add("Required effective parallel: " + formatExactRate(sizing.requiredEffectiveParallel()));
		String setupLabel = entry.isMachinesFixed() || entry.isParallelFixed() ? "Constrained setup: " : "Recommended setup: ";
		lines.add(setupLabel + sizing.machines() + " machine(s) x " + sizing.parallel() + " parallel");
		lines.add("Installed capacity: " + formatExactRate(sizing.capacityRate()) + " crafts/s");
		if (sizing.sufficient()) {
			lines.add("Headroom: " + formatExactRate(sizing.headroomPercent()) + "%");
		} else {
			lines.add("CAPACITY SHORTFALL: " + formatExactRate(sizing.shortfallPercent()) + "%");
		}
		if (entry.isMachinesFixed() || entry.isParallelFixed()) {
			lines.add("Locks: MACH " + (entry.isMachinesFixed() ? "FIXED" : "auto") + ", PAR " + (entry.isParallelFixed() ? "FIXED" : "auto"));
		}
		lines.add((sizing.exact() ? "Sizing: exact from detected machine limit" : "Sizing: provisional") + " - " + sizing.note());
	}

	private void drawTooltip(EmiDrawContext context, int mouseX, int mouseY, String... lines) {
		List<TooltipComponent> tooltip = new ArrayList<>();
		for (String value : lines) {
			tooltip.add(line(value));
		}
		EmiRenderHelper.drawTooltip(this, context, tooltip, mouseX, mouseY);
	}

	private TooltipComponent line(String value) {
		return TooltipComponent.of(EmiPort.ordered(EmiPort.literal(value)));
	}

	private boolean handleGroupsClick(Line line, int mx, int my, int button) {
		if (button == 0 && groupCloseButton.contains(mx, my)) {
			commitGroupRename();
			clearGroupDragState();
			groupsOpen = false;
			return true;
		}
		if (button == 0 && selectedGroup != null && groupRenameButton.contains(mx, my)) {
			startGroupRename(selectedGroup, groupSelectedNameBounds);
			return true;
		}
		if (button == 0 && selectedGroup != null && groupCollapseButton.contains(mx, my)) {
			ProductionPlanner.setGroupCollapsed(line, selectedGroup, !selectedGroup.isCollapsed());
			groupListScroll = 0;
			rowScroll = 0;
			return true;
		}
		for (GroupListHitbox hitbox : groupListHitboxes) {
			if (hitbox.group != null && button == 0 && hitbox.collapse.contains(mx, my)) {
				ProductionPlanner.setGroupCollapsed(line, hitbox.group, !hitbox.group.isCollapsed());
				selectedGroup = hitbox.group;
				groupListScroll = 0;
				rowScroll = 0;
				return true;
			}
			if (hitbox.bounds.contains(mx, my)) {
				if (button == 1 && hitbox.group != null) {
					selectedGroup = hitbox.group;
					startGroupRename(hitbox.group, hitbox.bounds);
					return true;
				}
				if (button == 0) {
					selectedGroup = hitbox.group;
					groupRecipeScroll = 0;
					groupLinkScroll = 0;
					if (hitbox.group != null) {
						pendingGroupDrag = hitbox.group;
						groupDragStartX = mx;
						groupDragStartY = my;
					}
					return true;
				}
			}
		}
		if (button == 0 && groupAddButton.contains(mx, my)) {
			selectedGroup = ProductionPlanner.createGroup(line, null);
			groupListScroll = 0;
			groupLinkScroll = 0;
			return true;
		}
		if (button == 0 && groupChildButton.contains(mx, my) && selectedGroup != null) {
			selectedGroup = ProductionPlanner.createGroup(line, selectedGroup);
			groupListScroll = 0;
			groupLinkScroll = 0;
			return true;
		}
		if (button == 0 && groupDeleteButton.contains(mx, my) && selectedGroup != null) {
			Group parent = selectedGroup.getParent(line);
			ProductionPlanner.removeGroup(line, selectedGroup);
			selectedGroup = parent;
			groupListScroll = 0;
			groupRecipeScroll = 0;
			groupLinkScroll = 0;
			rowScroll = 0;
			return true;
		}
		for (GroupRecipeHitbox hitbox : groupRecipeHitboxes) {
			if (button == 0 && hitbox.moveBounds.contains(mx, my)) {
				ProductionPlanner.setEntryGroup(line, hitbox.entry, selectedGroup);
				rowScroll = 0;
				return true;
			}
			if (button == 0 && hitbox.rowBounds.contains(mx, my)) {
				pendingRecipeDrag = hitbox.entry;
				recipeDragStartX = mx;
				recipeDragStartY = my;
				return true;
			}
		}
		for (GroupLinkHitbox hitbox : groupLinkHitboxes) {
			if (button == 0 && hitbox.bounds.contains(mx, my)) {
				ProductionPlanner.setGroupLinkMode(line, selectedGroup, hitbox.stack, hitbox.mode.toggled());
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int mx = (int) mouseX;
		int my = (int) mouseY;
		Line currentLine = ProductionPlanner.getOrCreateActiveLine();
		if (groupsOpen) {
			if (groupRenameField != null) {
				if (groupRenameField.mouseClicked(mouseX, mouseY, button)) {
					EmiPort.focus(groupRenameField, true);
					return true;
				}
				commitGroupRename();
			}
			return handleGroupsClick(currentLine, mx, my, button);
		}
		if (isDropdownOpen()) {
			if (coilMenuOpen && machineConfigEntry != null) {
				if (button == 0) {
					for (CoilOptionHitbox option : coilOptionHitboxes) {
						if (option.bounds.contains(mx, my)) {
							ProductionPlanner.setCoilTier(machineConfigEntry, option.tier);
							coilMenuOpen = false;
							activeDropdownBounds = EMPTY;
							return true;
						}
					}
				}
				if (activeDropdownBounds.contains(mx, my) || coilMenuAnchor.contains(mx, my)) {
					return true;
				}
				coilMenuOpen = false;
				activeDropdownBounds = EMPTY;
				return true;
			}
			if (machineConfigEntry != null) {
				if (button == 0 && configCoilMinus.contains(mx, my)) {
					ProductionPlanner.cycleCoilTier(machineConfigEntry, -1);
					return true;
				}
				if (button == 0 && configCoilPlus.contains(mx, my)) {
					ProductionPlanner.cycleCoilTier(machineConfigEntry, 1);
					return true;
				}
				if ((button == 0 || button == 1) && configCoilValue.contains(mx, my)) {
					if (button == 0) {
						coilMenuOpen = true;
						coilMenuAnchor = configCoilValue;
						activeDropdownBounds = EMPTY;
					} else {
						ProductionPlanner.cycleCoilTier(machineConfigEntry, -1);
					}
					return true;
				}
				if (button == 0 && configParallelMinus.contains(mx, my)) {
					ProductionPlanner.setParallel(machineConfigEntry, machineConfigEntry.getParallel() - countAdjustmentStep());
					return true;
				}
				if (button == 0 && configParallelPlus.contains(mx, my)) {
					ProductionPlanner.setParallel(machineConfigEntry, machineConfigEntry.getParallel() + countAdjustmentStep());
					return true;
				}
				for (MachineSettingControl control : configSpecialControls) {
					if (control.minus.contains(mx, my) && (button == 0 || button == 1)) {
						ProductionPlanner.cycleMachineSetting(machineConfigEntry, control.spec, -1);
						return true;
					}
					if (control.plus.contains(mx, my) && (button == 0 || button == 1)) {
						ProductionPlanner.cycleMachineSetting(machineConfigEntry, control.spec, 1);
						return true;
					}
					if (control.value.contains(mx, my) && (button == 0 || button == 1)) {
						ProductionPlanner.cycleMachineSetting(machineConfigEntry, control.spec, button == 0 ? 1 : -1);
						return true;
					}
				}
			}
			if (button == 0 && machineMenuEntry != null) {
				for (MachineOptionHitbox option : machineOptionHitboxes) {
					if (option.bounds.contains(mx, my)) {
						ProductionPlanner.setMachineProfile(machineMenuEntry, option.profile.id());
						closeDropdowns();
						return true;
					}
				}
			}
			if (button == 0 && (voltageMenuEntry != null || standardVoltageMenuOpen)) {
				for (VoltageOptionHitbox option : voltageOptionHitboxes) {
					if (option.bounds.contains(mx, my)) {
						if (standardVoltageMenuOpen) {
							ProductionPlanner.setLineStandardVoltage(currentLine, option.tier);
						} else {
							ProductionPlanner.setVoltageTier(voltageMenuEntry, option.tier);
						}
						closeDropdowns();
						return true;
					}
				}
			}
			Bounds anchor = machineConfigEntry != null ? machineConfigAnchor : machineMenuEntry != null ? machineMenuAnchor : standardVoltageMenuOpen ? standardVoltageBounds : voltageMenuAnchor;
			if (activeDropdownBounds.contains(mx, my) || anchor.contains(mx, my)) {
				if (anchor.contains(mx, my)) {
					closeDropdowns();
				}
				return true;
			}
			closeDropdowns();
		}
		if (editField != null && editField.mouseClicked(mouseX, mouseY, button)) {
			EmiPort.focus(editField, true);
			return true;
		}
		if (targetRateField != null && targetRateField.mouseClicked(mouseX, mouseY, button)) {
			EmiPort.focus(targetRateField, true);
			return true;
		}
		if (renameField != null && renameField.mouseClicked(mouseX, mouseY, button)) {
			EmiPort.focus(renameField, true);
			return true;
		}
		if (editField != null) {
			commitEntryEdit();
		}
		if (targetRateField != null) {
			commitTargetRateEdit();
		}
		if (renameField != null) {
			commitRename();
		}
		if (button == 0 && newLineButton.contains(mx, my)) {
			closeDropdowns();
			ProductionPlanner.createLine();
			rowScroll = 0;
			return true;
		}
		if (button == 0 && closeLineButton.contains(mx, my)) {
			closeDropdowns();
			ProductionPlanner.removeActiveLine();
			ProductionPlanner.getOrCreateActiveLine();
			rowScroll = 0;
			return true;
		}
		for (TabHitbox tab : tabHitboxes) {
			if (tab.bounds.contains(mx, my)) {
				if (button == 1) {
					startRename(tab);
				} else if (button == 0) {
					closeDropdowns();
					ProductionPlanner.setActiveIndex(tab.index);
					rowScroll = 0;
				}
				return true;
			}
		}
		Line line = ProductionPlanner.getOrCreateActiveLine();
		if ((button == 0 || button == 1) && standardVoltageBounds.contains(mx, my)) {
			if (button == 1) {
				ProductionPlanner.setLineStandardVoltage(line, -1);
			} else {
				openStandardVoltageMenu(line);
			}
			return true;
		}
		if (button == 0 && applyStandardVoltageBounds.contains(mx, my)) {
			ProductionPlanner.applyLineStandardVoltageToAll(line);
			return true;
		}
		if (button == 0 && groupsButton.contains(mx, my)) {
			closeDropdowns();
			groupsOpen = true;
			selectedGroup = null;
			groupListScroll = 0;
			groupRecipeScroll = 0;
			groupLinkScroll = 0;
			return true;
		}
		for (TargetHitbox hitbox : targetHitboxes) {
			if (button == 0 && hitbox.remove.contains(mx, my)) {
				ProductionPlanner.removeBalanceTarget(line, hitbox.target);
				return true;
			}
			if ((button == 0 || button == 1) && hitbox.rate.contains(mx, my)) {
				startTargetRateEdit(line, hitbox.target, hitbox.rate);
				return true;
			}
		}
		if (button == 0 && balanceButton.contains(mx, my)) {
			ProductionPlanner.balanceLine(line);
			return true;
		}
		if (button == 0 && clearTargetButton.contains(mx, my)) {
			ProductionPlanner.clearBalanceTarget(line);
			return true;
		}
		for (FlowHitbox hitbox : flowHitboxes) {
			if (button == 0 && hitbox.targetCandidate && hitbox.bounds.contains(mx, my)) {
				double defaultRate = hitbox.flow.stack.getKey() instanceof Fluid ? 1000.0D : 1.0D;
				ProductionPlanner.setBalanceTarget(line, hitbox.flow.stack, defaultRate);
				return true;
			}
		}
		for (GroupMainHitbox hitbox : groupMainHitboxes) {
			if (hitbox.bounds.contains(mx, my)) {
				if (button == 0) {
					ProductionPlanner.setGroupCollapsed(line, hitbox.group, !hitbox.group.isCollapsed());
					clampScroll();
					return true;
				}
				if (button == 1) {
					groupsOpen = true;
					selectedGroup = hitbox.group;
					groupListScroll = 0;
					groupRecipeScroll = 0;
					groupLinkScroll = 0;
					return true;
				}
			}
		}
		for (RowHitbox row : rowHitboxes) {
			Entry entry = row.entry;
			if (button == 0 && row.mode.contains(mx, my)) {
				if (line.isBalanceEnabled()) {
					ProductionPlanner.disableBalance(line);
				} else if (entry.isAutomatic()) {
					ProductionPlanner.setAutomatic(entry, false);
				} else if (!ProductionPlanner.setAutomatic(entry, true)) {
					startEntryEdit(row, EditKind.DURATION);
				}
				return true;
			}
			if ((button == 0 || button == 1 || button == 2) && row.machineProfile.contains(mx, my)) {
				if (button == 0) {
					openMachineMenu(row);
				} else {
					ProductionPlanner.setMachineProfile(entry, "generic");
				}
				return true;
			}
			if (button == 0 && row.machineConfig.contains(mx, my) && entry.getMachineProfile().hasConfigurableSettings()) {
				openMachineConfig(row);
				return true;
			}
			if (button == 0 && row.machinesLock.contains(mx, my)) {
				ProductionPlanner.setMachinesFixed(entry, !entry.isMachinesFixed());
				return true;
			}
			if (button == 0 && row.machinesMinus.contains(mx, my)) {
				ProductionPlanner.setMachines(entry, entry.getMachines() - countAdjustmentStep());
				return true;
			}
			if (button == 0 && row.machinesPlus.contains(mx, my)) {
				ProductionPlanner.setMachines(entry, entry.getMachines() + countAdjustmentStep());
				return true;
			}
			if (button == 1 && row.machinesValue.contains(mx, my)) {
				startEntryEdit(row, EditKind.MACHINES);
				return true;
			}
			if (button == 0 && row.parallelLock.contains(mx, my)) {
				ProductionPlanner.setParallelFixed(entry, !entry.isParallelFixed());
				return true;
			}
			if (button == 0 && row.parallelMinus.contains(mx, my)) {
				ProductionPlanner.setParallel(entry, entry.getParallel() - countAdjustmentStep());
				return true;
			}
			if (button == 0 && row.parallelPlus.contains(mx, my)) {
				ProductionPlanner.setParallel(entry, entry.getParallel() + countAdjustmentStep());
				return true;
			}
			if (button == 1 && row.parallelValue.contains(mx, my)) {
				startEntryEdit(row, EditKind.PARALLEL);
				return true;
			}
			if ((button == 0 || button == 1 || button == 2) && row.voltage.contains(mx, my)) {
				if (entry.isVoltageFixedByMachine()) {
					return true;
				}
				if (button == 0) {
					openVoltageMenu(row);
				} else {
					ProductionPlanner.resetVoltageToLine(line, entry);
				}
				return true;
			}
			if ((button == 0 || button == 1) && row.oc.contains(mx, my)) {
				ProductionPlanner.cycleOcMode(entry, button == 0 ? 1 : -1);
				return true;
			}
			if (button == 1 && row.duration.contains(mx, my)) {
				startEntryEdit(row, EditKind.DURATION);
				return true;
			}
			if (button == 2 && row.duration.contains(mx, my)) {
				ProductionPlanner.clearDurationOverride(entry);
				if (entry.isAutomatic() && entry.getDurationTicks() <= 0.0D) {
					ProductionPlanner.setAutomatic(entry, false);
				}
				return true;
			}
			if (button == 1 && row.rate.contains(mx, my) && !line.isBalanceEnabled() && !entry.isAutomatic()) {
				startEntryEdit(row, EditKind.RATE);
				return true;
			}
			if (button == 0 && row.remove.contains(mx, my)) {
				ProductionPlanner.removeEntry(line, entry);
				clampScroll();
				return true;
			}
			if (button == 0 && row.recipeBounds.contains(mx, my)) {
				EmiRecipe recipe = entry.getRecipe();
				if (recipe != null) {
					ProductionPlanner.save();
					EmiApi.displayRecipe(recipe);
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (groupsOpen && button == 0) {
			if (pendingGroupDrag != null) {
				double dx = mouseX - groupDragStartX;
				double dy = mouseY - groupDragStartY;
				if (groupDragActive || dx * dx + dy * dy >= 16.0D) {
					groupDragActive = true;
					return true;
				}
			}
			if (pendingRecipeDrag != null) {
				double dx = mouseX - recipeDragStartX;
				double dy = mouseY - recipeDragStartY;
				if (recipeDragActive || dx * dx + dy * dy >= 16.0D) {
					recipeDragActive = true;
					return true;
				}
			}
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (groupsOpen && button == 0) {
			Line line = ProductionPlanner.getOrCreateActiveLine();
			int mx = (int) mouseX;
			int my = (int) mouseY;
			if (groupDragActive && pendingGroupDrag != null) {
				applyGroupDrop(line, pendingGroupDrag, mx, my);
			} else if (recipeDragActive && pendingRecipeDrag != null) {
				applyRecipeDrop(line, pendingRecipeDrag, mx, my);
			}
			if (pendingGroupDrag != null || pendingRecipeDrag != null) {
				clearGroupDragState();
				return true;
			}
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		int mx = (int) mouseX;
		int my = (int) mouseY;
		if (groupsOpen) {
			Line line = ProductionPlanner.getOrCreateActiveLine();
			int direction = (int) -Math.signum(amount);
			if (groupListArea.contains(mx, my)) {
				int total = flattenGroups(line).size() + 1;
				int visible = Math.max(1, groupListArea.height() / 24);
				groupListScroll = Math.max(0, Math.min(groupListScroll + direction, Math.max(0, total - visible)));
				return true;
			}
			if (groupRecipeArea.contains(mx, my)) {
				int visible = Math.max(1, (groupRecipeArea.height() - 4) / 25);
				groupRecipeScroll = Math.max(0, Math.min(groupRecipeScroll + direction, Math.max(0, line.getEntries().size() - visible)));
				return true;
			}
			if (groupLinkArea.contains(mx, my)) {
				int total = ProductionPlanner.getGroupLinkCandidates(line, selectedGroup).size();
				int visible = Math.max(1, (groupLinkArea.height() - 4) / 25);
				groupLinkScroll = Math.max(0, Math.min(groupLinkScroll + direction, Math.max(0, total - visible)));
				return true;
			}
			return true;
		}
		if (isDropdownOpen() && activeDropdownBounds.contains(mx, my)) {
			if (machineConfigEntry != null) {
				int direction = (int) Math.signum(amount);
				if (configCoilValue.contains(mx, my) || configCoilMinus.contains(mx, my) || configCoilPlus.contains(mx, my)) {
					ProductionPlanner.cycleCoilTier(machineConfigEntry, direction);
					return true;
				}
				if (configParallelValue.contains(mx, my) || configParallelMinus.contains(mx, my) || configParallelPlus.contains(mx, my)) {
					ProductionPlanner.setParallel(machineConfigEntry, machineConfigEntry.getParallel() + direction * countAdjustmentStep());
					return true;
				}
				for (MachineSettingControl control : configSpecialControls) {
					if (control.minus.contains(mx, my) || control.value.contains(mx, my) || control.plus.contains(mx, my)) {
						ProductionPlanner.cycleMachineSetting(machineConfigEntry, control.spec, direction);
						return true;
					}
				}
				return true;
			}
			int direction = (int) -Math.signum(amount);
			if (machineMenuEntry != null) {
				int size = ProductionPlanner.getCompatibleMachineProfiles(machineMenuEntry).size();
				machineMenuScroll = Math.max(0, Math.min(machineMenuScroll + direction, Math.max(0, size - MENU_MAX_ROWS)));
			} else {
				int size = standardVoltageMenuOpen
					? ProductionPlanner.maxVoltageTier() + 2
					: Math.max(0, ProductionPlanner.maxVoltageTier() - Math.max(0, voltageMenuEntry.getRecipeTier()) + 1);
				if (standardVoltageMenuOpen) {
					standardVoltageMenuScroll = Math.max(0, Math.min(standardVoltageMenuScroll + direction, Math.max(0, size - MENU_MAX_ROWS)));
				} else {
					voltageMenuScroll = Math.max(0, Math.min(voltageMenuScroll + direction, Math.max(0, size - MENU_MAX_ROWS)));
				}
			}
			return true;
		}
		for (RowHitbox row : rowHitboxes) {
			Entry entry = row.entry;
			int direction = (int) Math.signum(amount);
			if (row.machinesLock.contains(mx, my) || row.parallelLock.contains(mx, my)) {
				return true;
			}
			if (row.machineArea().contains(mx, my)) {
				ProductionPlanner.setMachines(entry, entry.getMachines() + direction * countAdjustmentStep());
				return true;
			}
			if (row.parallelArea().contains(mx, my)) {
				ProductionPlanner.setParallel(entry, entry.getParallel() + direction * countAdjustmentStep());
				return true;
			}
			Line line = ProductionPlanner.getOrCreateActiveLine();
			if (row.rate.contains(mx, my) && !line.isBalanceEnabled() && !entry.isAutomatic()) {
				ProductionPlanner.setRate(entry, entry.getRate() + Math.signum(amount) * adjustmentStep());
				return true;
			}
		}
		if (my >= ROW_TOP) {
			rowScroll -= (int) Math.signum(amount);
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (groupRenameField != null && groupRenameField.isFocused()) {
			groupRenameField.charTyped(chr, modifiers);
			return true;
		}
		if (editField != null && editField.isFocused()) {
			editField.charTyped(chr, modifiers);
			return true;
		}
		if (targetRateField != null && targetRateField.isFocused()) {
			targetRateField.charTyped(chr, modifiers);
			return true;
		}
		if (renameField != null && renameField.isFocused()) {
			renameField.charTyped(chr, modifiers);
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (groupRenameField != null && groupRenameField.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				cancelGroupRename();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitGroupRename();
				return true;
			}
			groupRenameField.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
		if (groupsOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			clearGroupDragState();
			groupsOpen = false;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && isDropdownOpen()) {
			closeDropdowns();
			return true;
		}
		if (editField != null && editField.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				cancelEntryEdit();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitEntryEdit();
				return true;
			}
			editField.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
		if (targetRateField != null && targetRateField.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				cancelTargetRateEdit();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitTargetRateEdit();
				return true;
			}
			targetRateField.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
		if (renameField != null && renameField.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				cancelRename();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitRename();
				return true;
			}
			renameField.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE || client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void startEntryEdit(RowHitbox row, EditKind kind) {
		editEntry = row.entry;
		editKind = kind;
		Bounds bounds = switch (kind) {
			case RATE -> row.rate;
			case MACHINES -> row.machinesValue;
			case PARALLEL -> row.parallelValue;
			case DURATION -> row.duration;
		};
		String label = switch (kind) {
			case RATE -> "Crafts per second";
			case MACHINES -> "Machines";
			case PARALLEL -> "Parallel";
			case DURATION -> "Duration seconds";
		};
		editField = new TextFieldWidget(client.textRenderer, bounds.x() + 2, bounds.y() + 2,
			Math.max(20, bounds.width() - 4), bounds.height() - 4, EmiPort.literal(label));
		editField.setMaxLength(24);
		String value = switch (kind) {
			case RATE -> formatExactRate(row.entry.getRate());
			case MACHINES -> Integer.toString(row.entry.getMachines());
			case PARALLEL -> Integer.toString(row.entry.getParallel());
			case DURATION -> row.entry.getDurationSeconds() > 0.0D ? formatExactRate(row.entry.getDurationSeconds()) : "";
		};
		editField.setText(value);
		EmiPort.focus(editField, true);
	}

	private void commitEntryEdit() {
		if (editField != null && editEntry != null && editKind != null) {
			try {
				String text = editField.getText().trim().replace(',', '.');
				switch (editKind) {
					case RATE -> ProductionPlanner.setRate(editEntry, Double.parseDouble(text));
					case MACHINES -> ProductionPlanner.setMachines(editEntry, Integer.parseInt(text));
					case PARALLEL -> ProductionPlanner.setParallel(editEntry, Integer.parseInt(text));
					case DURATION -> {
						double seconds = text.isEmpty() ? 0.0D : Double.parseDouble(text);
						ProductionPlanner.setDurationOverrideSeconds(editEntry, seconds);
						if (editEntry.getDurationTicks() > 0.0D) {
							ProductionPlanner.setAutomatic(editEntry, true);
						}
					}
				}
			} catch (Throwable ignored) {
			}
		}
		cancelEntryEdit();
	}

	private void cancelEntryEdit() {
		if (editField != null) {
			EmiPort.focus(editField, false);
		}
		editField = null;
		editEntry = null;
		editKind = null;
	}

	private void startTargetRateEdit(Line line, Target target, Bounds bounds) {
		if (line == null || target == null || target.getStack() == null || target.getStack().isEmpty()) {
			return;
		}
		targetEditLine = line;
		targetEditTarget = target;
		targetEditBounds = bounds == null ? targetRateBounds : bounds;
		targetRateField = new TextFieldWidget(client.textRenderer, targetEditBounds.x() + 2, targetEditBounds.y() + 2,
			Math.max(20, targetEditBounds.width() - 4), targetEditBounds.height() - 4, EmiPort.literal("Target rate"));
		targetRateField.setMaxLength(24);
		targetRateField.setText(formatExactRate(target.getRate()));
		EmiPort.focus(targetRateField, true);
	}

	private void commitTargetRateEdit() {
		if (targetRateField != null && targetEditLine != null && targetEditTarget != null) {
			try {
				double rate = Double.parseDouble(targetRateField.getText().trim().replace(',', '.'));
				ProductionPlanner.setTargetRate(targetEditLine, targetEditTarget, rate);
			} catch (Throwable ignored) {
			}
		}
		cancelTargetRateEdit();
	}

	private void cancelTargetRateEdit() {
		if (targetRateField != null) {
			EmiPort.focus(targetRateField, false);
		}
		targetRateField = null;
		targetEditLine = null;
		targetEditTarget = null;
		targetEditBounds = EMPTY;
	}

	private void startRename(TabHitbox tab) {
		renameIndex = tab.index;
		renameField = new TextFieldWidget(client.textRenderer, tab.bounds.x() + 2, tab.bounds.y() + 2,
			Math.max(20, tab.bounds.width() - 4), tab.bounds.height() - 4, EmiPort.literal("Line name"));
		renameField.setMaxLength(48);
		renameField.setText(ProductionPlanner.displayName(tab.index));
		EmiPort.focus(renameField, true);
	}

	private void commitRename() {
		if (renameField != null && renameIndex >= 0) {
			ProductionPlanner.renameLine(renameIndex, renameField.getText());
		}
		cancelRename();
	}

	private void cancelRename() {
		if (renameField != null) {
			EmiPort.focus(renameField, false);
		}
		renameField = null;
		renameIndex = -1;
	}

	private void startGroupRename(Group group, Bounds bounds) {
		if (group == null || bounds == null) {
			return;
		}
		cancelGroupRename();
		groupRenameTarget = group;
		int x = bounds.x() + 2;
		int y = bounds.y() + 2;
		int w = Math.max(60, bounds.width() - 4);
		int h = Math.max(16, bounds.height() - 4);
		groupRenameField = new TextFieldWidget(client.textRenderer, x, y, w, h, EmiPort.literal(PlannerText.tr("groups.rename_label", "Group name")));
		groupRenameField.setMaxLength(64);
		String current = group.getName();
		groupRenameField.setText(current == null || current.isBlank()
			? group.getDisplayName(ProductionPlanner.getOrCreateActiveLine()) : current);
		EmiPort.focus(groupRenameField, true);
	}

	private void commitGroupRename() {
		if (groupRenameField != null && groupRenameTarget != null) {
			ProductionPlanner.renameGroup(ProductionPlanner.getOrCreateActiveLine(), groupRenameTarget, groupRenameField.getText());
		}
		cancelGroupRename();
	}

	private void cancelGroupRename() {
		if (groupRenameField != null) {
			EmiPort.focus(groupRenameField, false);
		}
		groupRenameField = null;
		groupRenameTarget = null;
	}

	private void clearGroupDragState() {
		pendingGroupDrag = null;
		pendingRecipeDrag = null;
		groupDragActive = false;
		recipeDragActive = false;
	}

	@Override
	public void close() {
		if (editField != null) {
			commitEntryEdit();
		}
		if (targetRateField != null) {
			commitTargetRateEdit();
		}
		if (renameField != null) {
			commitRename();
		}
		if (groupRenameField != null) {
			commitGroupRename();
		}
		ProductionPlanner.save();
		MinecraftClient.getInstance().setScreen(old);
	}

	@Override
	public void removed() {
		ProductionPlanner.save();
		super.removed();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private double adjustmentStep() {
		if (EmiInput.isShiftDown()) {
			return 10.0D;
		}
		if (EmiInput.isControlDown()) {
			return 0.1D;
		}
		if (EmiInput.isAltDown()) {
			return 0.01D;
		}
		return 1.0D;
	}

	private int countAdjustmentStep() {
		return EmiInput.isShiftDown() ? 10 : 1;
	}

	private int visibleRows() {
		return Math.max(1, (height - ROW_TOP - FOOTER_HEIGHT - 4) / ROW_HEIGHT);
	}

	private void clampScroll() {
		Line line = ProductionPlanner.getOrCreateActiveLine();
		int max = Math.max(0, buildPlannerDisplayRows(line).size() - visibleRows());
		rowScroll = Math.max(0, Math.min(rowScroll, max));
	}

	private int inputsColumnX() {
		return Math.max(780, width * 45 / 100);
	}

	private int outputsColumnX() {
		return Math.max(inputsColumnX() + 180, width * 72 / 100);
	}

	private String recipeName(EmiRecipe recipe) {
		for (EmiStack stack : recipe.getOutputs()) {
			if (!stack.isEmpty()) {
				return stack.getName().getString();
			}
		}
		return recipe.getId() == null ? recipe.getCategory().getName().getString() : recipe.getId().toString();
	}

	private PowerSummary calculatePower(Line line) {
		double averageEUt = 0.0D;
		int knownEntries = 0;
		int unknownEntries = 0;
		for (Entry entry : line.getEntries()) {
			double rate = line.getEffectiveRate(entry);
			if (rate <= EPSILON) {
				continue;
			}
			if (entry.getRecipeEUt() > 0L && entry.getProcessedDurationTicks() > 0.0D) {
				averageEUt += entry.getAveragePowerEUt(rate);
				knownEntries++;
			} else {
				unknownEntries++;
			}
		}
		return new PowerSummary(averageEUt, knownEntries, unknownEntries);
	}

	private PlanTotals calculate(Line line) {
		Map<EmiStack, MutableFlow> map = new LinkedHashMap<>();
		for (Entry entry : line.getEntries()) {
			EmiRecipe recipe = entry.getRecipe();
			if (recipe == null) {
				continue;
			}
			for (RateStack input : recipeInputs(recipe, line.getEffectiveRate(entry))) {
				MutableFlow flow = map.computeIfAbsent(normalize(input.stack), s -> new MutableFlow(s));
				flow.input += input.amount;
				flow.approximate |= input.approximate;
			}
			for (RateStack output : recipeOutputs(recipe, line.getEffectiveRate(entry))) {
				MutableFlow flow = map.computeIfAbsent(normalize(output.stack), s -> new MutableFlow(s));
				flow.output += output.amount;
				flow.approximate |= output.approximate;
			}
		}
		List<Flow> external = new ArrayList<>();
		List<Flow> internal = new ArrayList<>();
		List<Flow> outputs = new ArrayList<>();
		for (MutableFlow mutable : map.values()) {
			boolean rootIgnored = !line.hasTarget(mutable.stack) && line.getLinkMode(null, mutable.stack) == LinkMode.IGNORE;
			double internalAmount = rootIgnored ? 0.0D : Math.min(mutable.input, mutable.output);
			double externalAmount = rootIgnored ? mutable.input : Math.max(0, mutable.input - mutable.output);
			double outputAmount = rootIgnored ? mutable.output : Math.max(0, mutable.output - mutable.input);
			if (externalAmount > EPSILON) {
				external.add(new Flow(mutable.stack, mutable.input, mutable.output, internalAmount, externalAmount,
					mutable.approximate, formatCompactRate(externalAmount, mutable.approximate)));
			}
			if (internalAmount > EPSILON) {
				internal.add(new Flow(mutable.stack, mutable.input, mutable.output, internalAmount, externalAmount,
					mutable.approximate, formatCompactRate(internalAmount, mutable.approximate)));
			}
			if (outputAmount > EPSILON) {
				outputs.add(new Flow(mutable.stack, mutable.input, mutable.output, internalAmount, externalAmount,
					mutable.approximate, formatCompactRate(outputAmount, mutable.approximate)));
			}
		}
		return new PlanTotals(external, internal, outputs);
	}

	private List<RateStack> recipeInputs(EmiRecipe recipe, double rate) {
		List<RateStack> result = new ArrayList<>();
		for (EmiIngredient ingredient : recipe.getInputs()) {
			if (ingredient == null || ingredient.isEmpty()) {
				continue;
			}
			EmiStack stack = firstStack(ingredient);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			double chance = ingredient.getChance();
			double amount = ingredient.getAmount() * Math.max(0.0D, chance) * rate;
			boolean approximate = Math.abs(chance - 1.0F) > 0.0001F || ingredient.getEmiStacks().size() > 1;
			result.add(new RateStack(normalize(stack), amount, approximate));
		}
		return result;
	}

	private List<RateStack> recipeOutputs(EmiRecipe recipe, double rate) {
		List<RateStack> result = new ArrayList<>();
		for (EmiStack stack : recipe.getOutputs()) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			double chance = stack.getChance();
			double amount = stack.getAmount() * Math.max(0.0D, chance) * rate;
			boolean approximate = Math.abs(chance - 1.0F) > 0.0001F;
			result.add(new RateStack(normalize(stack), amount, approximate));
		}
		return result;
	}

	private EmiStack firstStack(EmiIngredient ingredient) {
		for (EmiStack stack : ingredient.getEmiStacks()) {
			if (!stack.isEmpty()) {
				return stack;
			}
		}
		return EmiStack.EMPTY;
	}

	private EmiStack normalize(EmiStack stack) {
		return stack.copy().setAmount(1).setChance(1);
	}

	private String formatRate(double value) {
		if (value >= 1000.0D) {
			return formatCompactRate(value, false);
		}
		return formatExactRate(value);
	}

	private String formatInteger(int value) {
		return value >= 1000 ? formatCompactRate(value, false) : Integer.toString(value);
	}

	private String formatDuration(double seconds) {
		if (seconds >= 1000.0D) {
			return formatCompactRate(seconds, false) + "s";
		}
		return formatExactRate(seconds) + "s";
	}

	private String formatCompactRate(double value, boolean approximate) {
		double abs = Math.abs(value);
		String[] suffixes = { "", "K", "M", "G", "T", "P", "E" };
		int unit = 0;
		while (abs >= 1000.0D && unit < suffixes.length - 1) {
			abs /= 1000.0D;
			unit++;
		}
		if (value < 0) {
			abs = -abs;
		}
		String number;
		if (Math.abs(abs) >= 100 || unit == 0 && Math.abs(abs) >= 10) {
			number = BigDecimal.valueOf(abs).setScale(0, RoundingMode.HALF_UP).toPlainString();
		} else if (Math.abs(abs) >= 10) {
			number = trimNumber(abs, 1);
		} else {
			number = trimNumber(abs, 2);
		}
		return (approximate ? "~" : "") + number + suffixes[unit];
	}

	private String formatExactRate(double value) {
		return trimNumber(value, 4);
	}

	private String trimNumber(double value, int scale) {
		return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}

	private String unitSuffix(EmiStack stack) {
		return stack.getKey() instanceof Fluid ? " mB/s" : " /s";
	}

	private boolean isTarget(Line line, EmiStack stack) {
		return line != null && stack != null && !stack.isEmpty() && line.hasTarget(stack);
	}

	private String unitSuffixShort(EmiStack stack) {
		return stack.getKey() instanceof Fluid ? "mB/s" : "/s";
	}

	private void drawValueBox(EmiDrawContext context, Bounds bounds, int mouseX, int mouseY, String label, boolean active) {
		drawValueBox(context, bounds, mouseX, mouseY, label, active, 0xFFFFFFFF);
	}

	private void drawValueBox(EmiDrawContext context, Bounds bounds, int mouseX, int mouseY, String label, boolean active, int textColor) {
		boolean hovered = bounds.contains(mouseX, mouseY);
		int color = active ? 0xFF263B36 : hovered ? HOVER_COLOR : 0xFF222229;
		context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
		drawBorder(context, bounds, hovered ? 0xFFD0D0D8 : BORDER_COLOR);
		String trimmed = textRenderer.trimToWidth(label, Math.max(4, bounds.width() - 4));
		context.drawCenteredText(EmiPort.literal(trimmed), bounds.x() + bounds.width() / 2, bounds.y() + 6, textColor);
	}

	private void drawMachineValueBox(EmiDrawContext context, Bounds bounds, int mouseX, int mouseY, MachineProfile profile, boolean active) {
		boolean hovered = bounds.contains(mouseX, mouseY);
		int color = active ? 0xFF263B36 : hovered ? HOVER_COLOR : 0xFF222229;
		context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
		drawBorder(context, bounds, hovered ? 0xFFD0D0D8 : BORDER_COLOR);
		int textX = bounds.x() + 4;
		if (profile != null && profile.hasIcon()) {
			context.drawStack(profile.icon(), bounds.x() + 3, bounds.y() + 2, EmiIngredient.RENDER_ICON);
			textX = bounds.x() + 22;
		}
		String label = profile == null ? "Generic GT" : profile.displayName();
		String trimmed = textRenderer.trimToWidth(label, Math.max(4, bounds.right() - textX - 3));
		context.drawTextWithShadow(EmiPort.literal(trimmed), textX, bounds.y() + 6, 0xFFFFFFFF);
	}

	private void drawButton(EmiDrawContext context, Bounds bounds, int mouseX, int mouseY, String label, boolean active) {
		boolean hovered = bounds.contains(mouseX, mouseY);
		int color = active ? ACTIVE_COLOR : hovered ? HOVER_COLOR : 0xFF2B2B32;
		context.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
		drawBorder(context, bounds, hovered ? 0xFFD0D0D8 : BORDER_COLOR);
		context.drawCenteredText(EmiPort.literal(label), bounds.x() + bounds.width() / 2, bounds.y() + 5, 0xFFFFFFFF);
	}

	private void drawBorder(EmiDrawContext context, Bounds bounds, int color) {
		context.fill(bounds.x(), bounds.y(), bounds.width(), 1, color);
		context.fill(bounds.x(), bounds.bottom() - 1, bounds.width(), 1, color);
		context.fill(bounds.x(), bounds.y(), 1, bounds.height(), color);
		context.fill(bounds.right() - 1, bounds.y(), 1, bounds.height(), color);
	}

	private static final class TabHitbox {
		private final Bounds bounds;
		private final int index;

		private TabHitbox(Bounds bounds, int index) {
			this.bounds = bounds;
			this.index = index;
		}
	}

	private enum EditKind {
		RATE, MACHINES, PARALLEL, DURATION
	}

	private record MachineOptionHitbox(Bounds bounds, MachineProfile profile) {
	}

	private record VoltageOptionHitbox(Bounds bounds, int tier) {
	}

	private record CoilOptionHitbox(Bounds bounds, int tier) {
	}

	private record MachineSettingControl(MachineSettingSpec spec, Bounds minus, Bounds value, Bounds plus) {
	}

	private record TargetHitbox(Target target, Bounds icon, Bounds rate, Bounds remove) {
	}

	private record GroupListHitbox(Bounds bounds, Bounds collapse, Group group) {
	}

	private record GroupRecipeHitbox(Bounds rowBounds, Bounds moveBounds, Entry entry) {
	}

	private record GroupLinkHitbox(Bounds bounds, EmiStack stack, LinkMode mode) {
	}

	private record GroupMainHitbox(Bounds bounds, Group group) {
	}

	private record PlannerDisplayRow(Entry entry, Group group, int depth) {
	}

	private static final class RowHitbox {
		private final Entry entry;
		private final Bounds mode;
		private final Bounds machineProfile;
		private final Bounds machineConfig;
		private final Bounds machinesLock;
		private final Bounds machinesMinus;
		private final Bounds machinesValue;
		private final Bounds machinesPlus;
		private final Bounds parallelLock;
		private final Bounds parallelMinus;
		private final Bounds parallelValue;
		private final Bounds parallelPlus;
		private final Bounds voltage;
		private final Bounds oc;
		private final Bounds duration;
		private final Bounds rate;
		private final Bounds remove;
		private final Bounds recipeBounds;

		private RowHitbox(Entry entry, Bounds mode, Bounds machineProfile, Bounds machineConfig, Bounds machinesLock, Bounds machinesMinus, Bounds machinesValue, Bounds machinesPlus,
				Bounds parallelLock, Bounds parallelMinus, Bounds parallelValue, Bounds parallelPlus, Bounds voltage, Bounds oc, Bounds duration, Bounds rate,
				Bounds remove, Bounds recipeBounds) {
			this.entry = entry;
			this.mode = mode;
			this.machineProfile = machineProfile;
			this.machineConfig = machineConfig;
			this.machinesLock = machinesLock;
			this.machinesMinus = machinesMinus;
			this.machinesValue = machinesValue;
			this.machinesPlus = machinesPlus;
			this.parallelLock = parallelLock;
			this.parallelMinus = parallelMinus;
			this.parallelValue = parallelValue;
			this.parallelPlus = parallelPlus;
			this.voltage = voltage;
			this.oc = oc;
			this.duration = duration;
			this.rate = rate;
			this.remove = remove;
			this.recipeBounds = recipeBounds;
		}

		private Bounds machineArea() {
			return new Bounds(machinesLock.x(), machinesLock.y(), machinesPlus.right() - machinesLock.x(), machinesLock.height());
		}

		private Bounds parallelArea() {
			return new Bounds(parallelLock.x(), parallelLock.y(), parallelPlus.right() - parallelLock.x(), parallelLock.height());
		}
	}

	private static final class FlowHitbox {
		private final Bounds bounds;
		private final Flow flow;
		private final boolean targetCandidate;

		private FlowHitbox(Bounds bounds, Flow flow, boolean targetCandidate) {
			this.bounds = bounds;
			this.flow = flow;
			this.targetCandidate = targetCandidate;
		}
	}

	private record PowerSummary(double averageEUt, int knownEntries, int unknownEntries) {
	}

	private static final class RateStack {
		private final EmiStack stack;
		private final double amount;
		private final boolean approximate;

		private RateStack(EmiStack stack, double amount, boolean approximate) {
			this.stack = stack;
			this.amount = amount;
			this.approximate = approximate;
		}
	}

	private static final class MutableFlow {
		private final EmiStack stack;
		private double input;
		private double output;
		private boolean approximate;

		private MutableFlow(EmiStack stack) {
			this.stack = stack;
		}
	}

	private static final class Flow {
		private final EmiStack stack;
		private final double input;
		private final double output;
		private final double internal;
		private final double external;
		private final boolean approximate;
		private final String displayAmount;
		private final double displayValue;

		private Flow(EmiStack stack, double input, double output, double internal, double external,
				boolean approximate, String displayAmount) {
			this(stack, input, output, internal, external, approximate, displayAmount,
				external > EPSILON ? external : internal > EPSILON ? internal : Math.max(0, output - input));
		}

		private Flow(EmiStack stack, double input, double output, double internal, double external,
				boolean approximate, String displayAmount, double displayValue) {
			this.stack = stack;
			this.input = input;
			this.output = output;
			this.internal = internal;
			this.external = external;
			this.approximate = approximate;
			this.displayAmount = displayAmount;
			this.displayValue = displayValue;
		}
	}

	private record PlanTotals(List<Flow> externalInputs, List<Flow> internalFlow, List<Flow> netOutputs) {
	}
}
