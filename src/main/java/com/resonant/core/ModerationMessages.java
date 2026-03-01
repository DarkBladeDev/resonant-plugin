package com.resonant.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ModerationMessages {

    private static final List<String> DEFAULT_LANGS = List.of("es", "en");
    private static final List<String> CATEGORIES = List.of("errors", "moderation", "commands", "status");

    private final JavaPlugin plugin;
    private final String lang;
    private final Map<String, Map<String, String>> messages;
    private final Gson gson = new Gson();

    public ModerationMessages(JavaPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.lang = config.getString("messages.lang", "es").toLowerCase(Locale.ROOT);
        this.messages = loadMessages();
    }

    public String get(String key) {
        String value = null;
        Map<String, String> primary = messages.get(lang);
        if (primary != null) {
            value = primary.get(key);
        }
        if (value == null) {
            Map<String, String> fallback = messages.get("es");
            if (fallback != null) {
                value = fallback.get(key);
            }
        }
        if (value == null) {
            value = key;
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private Map<String, Map<String, String>> loadMessages() {
        ensureLangResources();
        List<String> languages = resolveLanguages();
        Map<String, Map<String, String>> result = new HashMap<>();
        for (String language : languages) {
            Map<String, String> merged = new HashMap<>();
            for (String category : CATEGORIES) {
                Map<String, String> entries = loadCategory(language, category);
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    if (merged.containsKey(entry.getKey())) {
                        plugin.getLogger().warning("Clave duplicada en traducciones: " + language + ":" + entry.getKey());
                        continue;
                    }
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            result.put(language, merged);
        }
        validateConsistency(result, languages);
        return result;
    }

    private void ensureLangResources() {
        Path dataFolder = plugin.getDataFolder().toPath();
        if (!Files.exists(dataFolder)) {
            dataFolder.toFile().mkdirs();
        }
        for (String language : DEFAULT_LANGS) {
            for (String category : CATEGORIES) {
                String resourcePath = "lang/" + language + "/" + category + ".json";
                Path target = dataFolder.resolve(resourcePath);
                if (Files.exists(target)) {
                    continue;
                }
                try {
                    plugin.saveResource(resourcePath, false);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("No se pudo copiar recurso de traducción: " + resourcePath);
                }
            }
        }
    }

    private List<String> resolveLanguages() {
        Path langRoot = plugin.getDataFolder().toPath().resolve("lang");
        List<String> languages = new ArrayList<>();
        if (Files.isDirectory(langRoot)) {
            try (var stream = Files.list(langRoot)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                        .forEach(languages::add);
            } catch (IOException ex) {
                plugin.getLogger().warning("No se pudo leer el directorio de idiomas: " + ex.getMessage());
            }
        }
        if (languages.isEmpty()) {
            languages.addAll(DEFAULT_LANGS);
        }
        return languages;
    }

    private Map<String, String> loadCategory(String language, String category) {
        String resourcePath = "lang/" + language + "/" + category + ".json";
        Path filePath = plugin.getDataFolder().toPath().resolve(resourcePath);
        String json = null;
        if (Files.exists(filePath)) {
            try {
                json = Files.readString(filePath, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("No se pudo leer traducción: " + filePath + " - " + ex.getMessage());
            }
        }
        if (json == null) {
            try (InputStream input = plugin.getResource(resourcePath)) {
                if (input != null) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("No se pudo leer recurso de traducción: " + resourcePath + " - " + ex.getMessage());
            }
        }
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        Map<String, String> entries = new HashMap<>();
        if (obj == null) {
            return entries;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }
            entries.put(entry.getKey(), entry.getValue().getAsString());
        }
        return entries;
    }

    private void validateConsistency(Map<String, Map<String, String>> all, List<String> languages) {
        if (languages.isEmpty()) {
            return;
        }
        String baseLang = all.containsKey(lang) ? lang : languages.get(0);
        Set<String> baseKeys = new HashSet<>(all.getOrDefault(baseLang, Map.of()).keySet());
        for (String language : languages) {
            Set<String> keys = new HashSet<>(all.getOrDefault(language, Map.of()).keySet());
            Set<String> missing = new HashSet<>(baseKeys);
            missing.removeAll(keys);
            if (!missing.isEmpty()) {
                plugin.getLogger().warning("Faltan claves de traducción en " + language + ": " + String.join(", ", missing));
            }
            Set<String> extra = new HashSet<>(keys);
            extra.removeAll(baseKeys);
            if (!extra.isEmpty()) {
                plugin.getLogger().warning("Claves extra de traducción en " + language + ": " + String.join(", ", extra));
            }
        }
    }
}
