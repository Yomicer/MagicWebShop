package com.magic.webshop.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

/**
 * Resolves item textures from a server resource pack that the admin unzipped
 * into a local folder. Supports plain vanilla-named textures and, for items
 * that use CustomModelData, follows the pack's item model overrides
 * (predicate.custom_model_data -> model -> textures.layer0) to the real PNG.
 */
public class ResourcePackResolver {

    private final File packDir;
    private final Gson gson;
    private final Logger logger;

    public ResourcePackResolver(File packDir, Gson gson, Logger logger) {
        this.packDir = packDir;
        this.gson = gson;
        this.logger = logger;
    }

    public boolean available() {
        return packDir != null && packDir.isDirectory()
                && new File(packDir, "assets").isDirectory();
    }

    /** Returns PNG bytes from the pack, or null if not found there. */
    public byte[] resolve(String material, Integer cmd, String itemModel) {
        if (!available()) return null;
        String name = normalize(material);
        if (itemModel != null && !itemModel.isBlank()) {
            byte[] byModel = resolveByItemModel(itemModel);
            if (byModel != null) return byModel;
        }
        if (cmd != null) {
            byte[] custom = resolveByModel(name, cmd);
            if (custom != null) return custom;
        }
        byte[] item = readTexture("minecraft", "item/" + name);
        if (item != null) return item;
        return readTexture("minecraft", "block/" + name);
    }

    /** Resolve a 1.21.4+ item_model id (e.g. "namespace:path") to a PNG. */
    private byte[] resolveByItemModel(String itemModel) {
        String[] np = splitId(itemModel);
        String modelId = null;
        // 1.21.4+ item definition: assets/<ns>/items/<path>.json
        JsonObject def = parse(new File(packDir, "assets/" + np[0] + "/items/" + np[1] + ".json"));
        if (def != null && def.has("model")) modelId = findModelId(def.get("model"));
        if (modelId == null) modelId = itemModel; // older packs: treat as a model path

        String texId = textureFromModel(modelId, 0);
        if (texId != null) {
            byte[] b = readTextureId(texId);
            if (b != null) return b;
        }
        return readTextureId(itemModel); // last resort: itemModel might name a texture
    }

    /** Depth-first search for the first {"model":"ns:path"} string in an item definition. */
    private String findModelId(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("model") && o.get("model").isJsonPrimitive()) return o.get("model").getAsString();
            for (var e : o.entrySet()) {
                String r = findModelId(e.getValue());
                if (r != null) return r;
            }
        } else if (el.isJsonArray()) {
            for (JsonElement c : el.getAsJsonArray()) {
                String r = findModelId(c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private byte[] resolveByModel(String name, int cmd) {
        JsonObject model = parse(new File(packDir, "assets/minecraft/models/item/" + name + ".json"));
        if (model == null) return null;
        JsonArray overrides = model.getAsJsonArray("overrides");
        if (overrides == null) return null;
        String modelId = null;
        for (JsonElement el : overrides) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            JsonObject pred = o.getAsJsonObject("predicate");
            if (pred != null && pred.has("custom_model_data")
                    && pred.get("custom_model_data").getAsInt() == cmd && o.has("model")) {
                modelId = o.get("model").getAsString();
                break;
            }
        }
        if (modelId == null) return null;
        String texId = textureFromModel(modelId, 0);
        return texId == null ? null : readTextureId(texId);
    }

    private String textureFromModel(String modelId, int depth) {
        if (depth > 5 || modelId == null) return null;
        String[] np = splitId(modelId);
        JsonObject m = parse(new File(packDir, "assets/" + np[0] + "/models/" + np[1] + ".json"));
        if (m == null) return null;
        JsonObject textures = m.getAsJsonObject("textures");
        if (textures != null && textures.size() > 0) {
            JsonElement layer0 = textures.has("layer0") ? textures.get("layer0")
                    : textures.entrySet().iterator().next().getValue();
            if (layer0 != null && layer0.isJsonPrimitive()) return layer0.getAsString();
        }
        if (m.has("parent")) return textureFromModel(m.get("parent").getAsString(), depth + 1);
        return null;
    }

    private byte[] readTextureId(String texId) {
        String[] np = splitId(texId);
        return readTexture(np[0], np[1]);
    }

    private byte[] readTexture(String namespace, String path) {
        File f = new File(packDir, "assets/" + namespace + "/textures/" + path + ".png");
        if (!f.isFile()) return null;
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            logger.fine("Could not read pack texture " + f + ": " + e.getMessage());
            return null;
        }
    }

    private String[] splitId(String id) {
        String s = id.trim();
        if (!s.contains(":")) return new String[] { "minecraft", s };
        int i = s.indexOf(':');
        return new String[] { s.substring(0, i), s.substring(i + 1) };
    }

    private JsonObject parse(File f) {
        if (!f.isFile()) return null;
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            JsonElement el = gson.fromJson(r, JsonElement.class);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalize(String material) {
        return material == null ? "stone" : material.toLowerCase().replace("minecraft:", "").trim();
    }
}
