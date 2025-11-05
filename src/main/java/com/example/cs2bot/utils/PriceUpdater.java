package com.example.cs2bot.utils;

import com.google.gson.*;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Unified price provider:
 * 1) Skinport public or authenticated API (EUR)
 * 2) SteamPriceCache (your local cache)
 * 3) Steam priceoverview fallback
 */
public class PriceProvider {

    private static final String PUBLIC_URL = "https://api.skinport.com/v1/items?app_id=730&currency=EUR";
    private static String SKINPORT_API_KEY = null;

    private static final Map<String, Double> skinportMap = new ConcurrentHashMap<>();
    private static volatile long skinportLastLoad = 0L;
    private static final long SKINPORT_TTL_MS = 10 * 60 * 1000; // refresh every 10 minutes

    private static final Semaphore steamLimiter = new Semaphore(1);
    private static final Random rand = new Random();

    static {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(System.getProperty("user.dir"))
                    .ignoreIfMissing()
                    .ignoreIfMalformed()
                    .load();
            SKINPORT_API_KEY = dotenv.get("SKINPORT_API_KEY");
            if (SKINPORT_API_KEY != null && !SKINPORT_API_KEY.isBlank()) {
                System.out.println("🔑 Using authenticated Skinport API mode");
            } else {
                System.out.println("🌍 Using public Skinport API mode");
            }
        } catch (Exception e) {
            System.err.println("[PriceProvider] ⚠️ Could not load API key: " + e.getMessage());
        }
    }

    /** Try Skinport → Cache → Steam. */
    public static double getPriceEUR(String marketHashName) {
        if (marketHashName == null || marketHashName.isBlank()) return 0.0;

        String normalized = normalizeName(marketHashName);

        // 1️⃣ Try Skinport
        loadSkinportIfStale();
        Double sp = skinportMap.get(normalized);
        if (sp != null && sp > 0) return sp;

        // 2️⃣ Cache
        try {
            Double cached = SteamPriceCache.get(normalized);
            if (cached != null && cached > 0) return cached;
        } catch (Throwable ignore) {}

        // 3️⃣ Fallback to Steam
        double steam = steamPriceOverview(normalized);
        if (steam > 0) {
            try { SteamPriceCache.put(normalized, steam); } catch (Throwable ignore) {}
        }
        return steam;
    }

    private static void loadSkinportIfStale() {
        long now = Instant.now().toEpochMilli();
        if (now - skinportLastLoad < SKINPORT_TTL_MS && !skinportMap.isEmpty()) return;

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(PUBLIC_URL).openConnection();
            conn.setRequestProperty("User-Agent", "CS2PriceBot/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (SKINPORT_API_KEY != null && !SKINPORT_API_KEY.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + SKINPORT_API_KEY);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[PriceProvider] ⚠️ Skinport HTTP " + code);
                return;
            }

            JsonArray arr = JsonParser.parseReader(new InputStreamReader(conn.getInputStream()))
                    .getAsJsonArray();

            Map<String, Double> temp = new HashMap<>();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("market_hash_name")) continue;
                String name = o.get("market_hash_name").getAsString();
                String n = normalizeName(name);

                double price = safeDouble(o, "lowest_price");
                if (price <= 0) price = safeDouble(o, "min_price");
                if (price > 0) temp.put(n, price);
            }

            if (!temp.isEmpty()) {
                skinportMap.clear();
                skinportMap.putAll(temp);
                skinportLastLoad = now;
                System.out.println("[PriceProvider] ✅ Loaded " + temp.size() + " Skinport prices.");
            }
        } catch (Exception e) {
            System.err.println("[PriceProvider] ❌ Skinport error: " + e.getMessage());
        }
    }

    private static double steamPriceOverview(String marketHashName) {
        String url = "https://steamcommunity.com/market/priceoverview/"
                + "?currency=3&appid=730&market_hash_name=" + encode(marketHashName);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                steamLimiter.acquire();
                Thread.sleep(200 + rand.nextInt(400));

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (CS2PriceBot)");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int code = conn.getResponseCode();
                if (code == 429) {
                    int wait = 6000 + rand.nextInt(4000);
                    System.err.printf("[PriceProvider] ⚠️ Steam 429 for %s — waiting %ds%n",
                            marketHashName, wait / 1000);
                    Thread.sleep(wait);
                    continue;
                }
                if (code != 200) {
                    System.err.printf("[PriceProvider] ⚠️ Steam HTTP %d for %s%n", code, marketHashName);
                    return 0.0;
                }

                JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream()))
                        .getAsJsonObject();

                if (!json.has("success") || !json.get("success").getAsBoolean()) return 0.0;
                if (!json.has("lowest_price") || json.get("lowest_price").isJsonNull()) return 0.0;

                String priceStr = json.get("lowest_price").getAsString()
                        .replace("€", "").replace(",", ".").trim();

                return Double.parseDouble(priceStr);
            } catch (Exception e) {
                System.err.printf("[PriceProvider] ❌ Steam error for %s: %s%n",
                        marketHashName, e.getMessage());
            } finally {
                new Timer(true).schedule(new TimerTask() {
                    @Override public void run() { steamLimiter.release(); }
                }, 1000);
            }
        }
        return 0.0;
    }

    // --- helpers ---
    private static double safeDouble(JsonObject o, String key) {
        try { return o.get(key).getAsDouble(); } catch (Exception e) { return 0.0; }
    }

    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        String n = name.trim();
        if (n.startsWith("? ")) n = "★ " + n.substring(2);
        if (n.startsWith("?")) n = "★ " + n.substring(1).trim();
        if ((n.contains("Gloves") || n.contains("Knife") || n.contains("Hand Wraps"))
                && !n.startsWith("★ ")) {
            n = "★ " + n;
        }
        return n;
    }
}