package dev.emi.emi.platform.forge;

import java.util.Arrays;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.ConfigScreen;
import dev.emi.emi.screen.EmiScreenBase;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.StackBatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.ForgeRenderTypes;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "emi", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class EmiClientForge {
	private static final KeyBinding PRODUCTION_PLANNER_KEY = new KeyBinding(
		"Production Planner", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "EMI");
	
	@SubscribeEvent
	public static void clientInit(FMLClientSetupEvent event) {
		StackBatcher.EXTRA_RENDER_LAYERS.addAll(Arrays.stream(ForgeRenderTypes.values()).map(f -> f.get()).toList());
		EmiClient.init();
		EmiNetwork.initClient(packet -> EmiPacketHandler.CHANNEL.sendToServer(packet));
		MinecraftForge.EVENT_BUS.addListener(EmiClientForge::recipesReloaded);
		MinecraftForge.EVENT_BUS.addListener(EmiClientForge::tagsReloaded);
		MinecraftForge.EVENT_BUS.addListener(EmiClientForge::renderScreenForeground);
		MinecraftForge.EVENT_BUS.addListener(EmiClientForge::postRenderScreen);
		MinecraftForge.EVENT_BUS.addListener(EmiClientForge::clientTick);
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
			() -> new ConfigScreenHandler.ConfigScreenFactory((client, last) -> new ConfigScreen(last)));
	}

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(PRODUCTION_PLANNER_KEY);
	}

	public static void clientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		while (PRODUCTION_PLANNER_KEY.wasPressed()) {
			EmiApi.viewProductionPlanner();
		}
	}

	@SubscribeEvent
	public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
		MinecraftClient client = MinecraftClient.getInstance();
		EmiTags.registerTagModels(client.getResourceManager(), event::register);
	}

	@SubscribeEvent
	public static void registerResourceReloaders(RegisterClientReloadListenersEvent event) {
		EmiData.init(reloader -> event.registerReloadListener(reloader));
	}

	public static void recipesReloaded(RecipesUpdatedEvent event) {
		EmiReloadManager.reloadRecipes();
	}

	public static void tagsReloaded(TagsUpdatedEvent event) {
		EmiReloadManager.reloadTags();
	}

	public static void renderScreenForeground(ContainerScreenEvent.Render.Foreground event) {
		EmiDrawContext context = EmiDrawContext.wrap(event.getGuiGraphics());
		HandledScreen<?> screen = event.getContainerScreen();
		EmiScreenBase base = EmiScreenBase.of(screen);
		if (base != null) {
			MinecraftClient client = MinecraftClient.getInstance();
			context.push();
			context.matrices().translate(-screen.getGuiLeft(), -screen.getGuiTop(), 0.0);
			EmiPort.setPositionTexShader();
			EmiScreenManager.render(context, event.getMouseX(), event.getMouseY(), client.getTickDelta());
			context.pop();
		}
	}

	public static void postRenderScreen(ScreenEvent.Render.Post event) {
		EmiDrawContext context = EmiDrawContext.wrap(event.getGuiGraphics());
		Screen screen = event.getScreen();
		if (!(screen instanceof HandledScreen<?>)) {
			return;
		}
		EmiScreenBase base = EmiScreenBase.of(screen);
		if (base != null) {
			MinecraftClient client = MinecraftClient.getInstance();
			context.push();
			EmiPort.setPositionTexShader();
			EmiScreenManager.drawForeground(context, event.getMouseX(), event.getMouseY(), client.getTickDelta());
			context.pop();
		}
	}
}
