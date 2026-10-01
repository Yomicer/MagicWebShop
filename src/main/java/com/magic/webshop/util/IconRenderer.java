package com.magic.webshop.util;

import java.nio.charset.StandardCharsets;

/**
 * Generates a clean, deterministic SVG "item tile" used as a fallback icon when
 * no real texture CDN is configured (or when a texture image fails to load).
 * Same material always yields the same colours, so the catalog looks coherent.
 */
public final class IconRenderer {

    private IconRenderer() {}

    public static byte[] renderSvg(String material) {
        String name = material == null ? "UNKNOWN" : material;
        int hash = Math.abs(name.hashCode());

        int hue = hash % 360;
        String top = "hsl(" + hue + ",70%,58%)";
        String bottom = "hsl(" + ((hue + 28) % 360) + ",68%,42%)";
        String label = shortLabel(ItemSerializer.readableName(name));

        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='128' height='128' viewBox='0 0 128 128'>"
                + "<defs><linearGradient id='g' x1='0' y1='0' x2='0' y2='1'>"
                + "<stop offset='0' stop-color='" + top + "'/>"
                + "<stop offset='1' stop-color='" + bottom + "'/></linearGradient></defs>"
                + "<rect x='8' y='8' width='112' height='112' rx='18' fill='url(#g)'/>"
                + "<rect x='8' y='8' width='112' height='112' rx='18' fill='none' stroke='rgba(255,255,255,0.35)' stroke-width='2'/>"
                + "<text x='64' y='78' font-family='Segoe UI,Arial,sans-serif' font-size='44' font-weight='700'"
                + " fill='rgba(255,255,255,0.95)' text-anchor='middle'>" + escape(label) + "</text>"
                + "</svg>";
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    private static String shortLabel(String readable) {
        String[] words = readable.split("\\s+");
        if (words.length >= 2) {
            return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase();
        }
        String w = words.length == 1 ? words[0] : "?";
        return w.substring(0, Math.min(2, w.length())).toUpperCase();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
