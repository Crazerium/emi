package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.emi.emi.runtime.RecipeShareLink;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Style;

@Mixin(Screen.class)
public class ScreenMixin {

	@Inject(at = @At("RETURN"), method = "init(Lnet/minecraft/client/MinecraftClient;II)V")
	private void init(MinecraftClient client, int width, int height, CallbackInfo info) {
		if ((Object) this instanceof HandledScreen hs && client.currentScreen == hs) {
			EmiScreenManager.addWidgets(hs);
		}
	}

	@Inject(at = @At("RETURN"), method = "resize(Lnet/minecraft/client/MinecraftClient;II)V")
	private void resize(MinecraftClient client, int width, int height, CallbackInfo info) {
		if ((Object) this instanceof HandledScreen hs && client.currentScreen == hs) {
			EmiScreenManager.addWidgets(hs);
		}
	}

	@Inject(at = @At("HEAD"), method = "handleTextClick(Lnet/minecraft/text/Style;)Z", cancellable = true)
	private void handleRecipeShareClick(Style style, CallbackInfoReturnable<Boolean> info) {
		if (RecipeShareLink.handleClick(style)) {
			info.setReturnValue(true);
		}
	}
}
