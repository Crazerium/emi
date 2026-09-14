package dev.emi.emi.runtime;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.emi.emi.bom.BoM;
import net.minecraft.util.JsonHelper;

public class EmiPersistentData {
	public static final File FILE = new File("emi.json");
	public static final Gson GSON = new Gson().newBuilder().setPrettyPrinting().create();
	
	public static void save() {
		try {
			JsonObject json = new JsonObject();
			json.add("favorites", EmiFavorites.save());
			json.addProperty("favorite_page_count", EmiFavorites.getFavoritePageCount());
			json.add("favorite_groups", EmiFavoriteGroups.save());
			EmiSidebars.save(json);
			json.add("recipe_defaults", BoM.saveAdded());
			json.add("hidden_stacks", EmiHidden.save());
			FileWriter writer = new FileWriter(FILE);
			GSON.toJson(json, writer);
			writer.close();
		} catch (Exception e) {
			EmiLog.error("Failed to write persistent data", e);
		}
	}

	public static void load() {
		if (!FILE.exists()) {
			return;
		}
		try {
			boolean trimFavoritePages = false;
			JsonObject json = GSON.fromJson(new FileReader(FILE), JsonObject.class);
			if (JsonHelper.hasArray(json, "favorites")) {
				EmiFavorites.load(JsonHelper.getArray(json, "favorites"));
			}
			EmiFavorites.setFavoritePageCount(JsonHelper.getInt(json, "favorite_page_count", 1));
			if (JsonHelper.hasArray(json, "favorite_groups")) {
				EmiFavoriteGroups.load(JsonHelper.getArray(json, "favorite_groups"));
				EmiFavorites.takeEmbeddedFavoriteGroups();
			} else {
				JsonArray embeddedGroups = EmiFavorites.takeEmbeddedFavoriteGroups();
				EmiFavoriteGroups.load(embeddedGroups == null ? new JsonArray() : embeddedGroups);
			}
			trimFavoritePages = EmiFavorites.trimTrailingEmptyFavoritePages();
			EmiSidebars.load(json);
			if (JsonHelper.hasJsonObject(json, "recipe_defaults")) {
				BoM.loadAdded(JsonHelper.getObject(json, "recipe_defaults"));
			}
			if (JsonHelper.hasArray(json, "hidden_stacks")) {
				EmiHidden.load(JsonHelper.getArray(json, "hidden_stacks"));
			}
			if (trimFavoritePages) {
				save();
			}
		} catch (Exception e) {
			EmiLog.error("Failed to parse persistent data", e);
		}
	}
}
