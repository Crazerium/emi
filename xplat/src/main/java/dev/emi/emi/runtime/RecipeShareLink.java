package dev.emi.emi.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.jetbrains.annotations.Nullable;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public final class RecipeShareLink {
	public static final String PREFIX = "https://emi-bookmark.invalid/r/";

	private RecipeShareLink() {
	}

	public static String create(EmiRecipe recipe) {
		if (recipe == null || recipe.getId() == null) {
			return "";
		}
		String id = recipe.getId().toString();
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
		return PREFIX + payload;
	}

	public static boolean handleClick(@Nullable Style style) {
		if (style == null || style.getClickEvent() == null) {
			return false;
		}
		ClickEvent click = style.getClickEvent();
		if (click.getAction() != ClickEvent.Action.OPEN_URL) {
			return false;
		}
		return importLink(click.getValue());
	}

	public static boolean importLink(String value) {
		EmiRecipe recipe = recipeFromLink(value);
		if (recipe == null) {
			return false;
		}
		boolean added = RecipeFavoriteActions.saveSharedRecipe(recipe);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			String name = recipeName(recipe);
			if (added) {
				client.player.sendMessage(EmiPort.literal("Added EMI bookmark: " + name).formatted(Formatting.GREEN), false);
			} else {
				client.player.sendMessage(EmiPort.literal("EMI bookmark already exists: " + name).formatted(Formatting.YELLOW), false);
			}
		}
		return true;
	}

	public static Text decorateChatMessage(Text message) {
		if (message == null) {
			return Text.empty();
		}
		String raw = message.getString();
		int start = raw.indexOf(PREFIX);
		if (start < 0) {
			return message;
		}
		int end = start;
		while (end < raw.length() && !Character.isWhitespace(raw.charAt(end))) {
			end++;
		}
		String link = raw.substring(start, end);
		EmiRecipe recipe = recipeFromLink(link);
		if (recipe == null) {
			return message;
		}

		MutableText result = Text.literal(raw.substring(0, start));
		MutableText clickable = EmiPort.literal("[Click to add bookmark: " + recipeName(recipe) + "]");
		clickable.setStyle(Style.EMPTY
			.withColor(Formatting.GOLD)
			.withUnderline(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link)));
		result.append(clickable);
		if (end < raw.length()) {
			result.append(Text.literal(raw.substring(end)));
		}
		return result;
	}

	private static @Nullable EmiRecipe recipeFromLink(String value) {
		if (value == null || !value.startsWith(PREFIX)) {
			return null;
		}
		try {
			String payload = value.substring(PREFIX.length());
			int query = payload.indexOf('?');
			if (query >= 0) {
				payload = payload.substring(0, query);
			}
			String idText = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
			Identifier id = new Identifier(idText);
			return EmiApi.getRecipeManager().getRecipe(id);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String recipeName(EmiRecipe recipe) {
		if (recipe == null || recipe.getOutputs().isEmpty() || recipe.getOutputs().get(0).isEmpty()) {
			return recipe != null && recipe.getId() != null ? recipe.getId().toString() : "recipe";
		}
		return recipe.getOutputs().get(0).getName().getString();
	}
}
