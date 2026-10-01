package com.magic.webshop.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Maps vanilla material ids to Chinese display names using Minecraft's own
 * language file (bundled zh_cn.json), so items with no custom name show e.g.
 * "钻石" instead of "Diamond". A server resource pack's zh_cn.json, if present,
 * is overlaid on top.
 */
public final class Translations {

    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();
    private static final Gson GSON = new Gson();
    private static volatile Map<String, String> lang = new HashMap<>();

    private Translations() {}

    public static void init(File packDir, Logger logger) {
        Map<String, String> merged = new HashMap<>();
        // 1) bundled vanilla zh_cn.json
        try (InputStream in = Translations.class.getResourceAsStream("/lang/zh_cn.json")) {
            if (in != null) {
                try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                    Map<String, String> m = GSON.fromJson(r, MAP_TYPE);
                    if (m != null) merged.putAll(m);
                }
            }
        } catch (Exception e) {
            logger.warning("Could not load bundled zh_cn.json: " + e.getMessage());
        }
        // 2) resource pack override, if any
        if (packDir != null) {
            File f = new File(packDir, "assets/minecraft/lang/zh_cn.json");
            if (f.isFile()) {
                try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                    Map<String, String> m = GSON.fromJson(r, MAP_TYPE);
                    if (m != null) merged.putAll(m);
                } catch (Exception e) {
                    logger.fine("Could not load pack zh_cn.json: " + e.getMessage());
                }
            }
        }
        lang = merged;
        logger.info("Loaded " + merged.size() + " Chinese translations.");
    }

    /** Chinese name for a material id, or null if unknown. */
    public static String chinese(String materialId) {
        if (materialId == null) return null;
        String id = materialId.toLowerCase().replace("minecraft:", "").trim();
        String zh = lang.get("item.minecraft." + id);
        if (zh == null) zh = lang.get("block.minecraft." + id);
        return zh;
    }

    /** Chinese name if known, otherwise the readable English name. */
    public static String displayName(String materialId) {
        String zh = chinese(materialId);
        return zh != null ? zh : ItemSerializer.readableName(materialId);
    }
}
