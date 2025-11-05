package com.example.cs2bot.utils;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import io.github.cdimascio.dotenv.Dotenv;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class PriceUpdater implements Runnable {

    private static final String SKINPORT_URL = "https://api.skinport.com/v1/items?app_id=730&currency=EUR";
    private static String SKINPORT_API_KEY = null;
    private static MongoCollection<Document> priceCollection;

    private static final Map<String, Double> skinportMap = new ConcurrentHashMap<>();
    private static volatile long skinportLastLoad = 0L;
    private static final long SKINPORT_TTL_MS = 10 * 60 * 1000; // 10 min cache

    private static final Semaphore steamLimiter = new Semaphore(1);
    private static final Random rand = new Random();

    private final int refreshInterval;
    private final int totalThreads;
    private final int threadIndex;

    public PriceUpdater(int refreshInterval, int totalThreads, int threadIndex) {
        this.refreshInterval = refreshInterval;
        this.totalThreads = totalThreads;
        this.threadIndex = threadIndex;
        initMongo();
    }

    public PriceUpdater() {
        this(600, 1, 0);
    }

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

            // Initialize MongoDB
            String mongoUri = dotenv.get("MONGO_URI");
            if (mongoUri != null && !mongoUri.isBlank()) {
                MongoClient client = MongoClients.create(mongoUri);
                MongoDatabase db = client.getDatabase("cs2_case_bot");
                priceCollection = db.getCollection("prices");
                System.out.println("🗄️ Connected to MongoDB for price cache");
            }

        } catch (Exception e) {
            System.err.println("[PriceProvider] ⚠️ Could not initialize MongoDB or .env: " + e.getMessage());
        }
    }

    private static void initMongo() {
        if (priceCollection == null) {
            try {
                Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
                String mongoUri = dotenv.get("MONGO_URI");
                if (mongoUri != null && !mongoUri.isBlank()) {
                    MongoClient client = MongoClients.create(mongoUri);
                    MongoDatabase db = client.getDatabase("cs2_case_bot");
                    priceCollection = db.getCollection("prices");
                }
            } catch (Exception e) {
                System.err.println("[PriceProvider] ⚠️ Mongo init failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void run() {
        try {
            System.out.printf("[Thread-%d] 🌀 Starting price updater (interval=%d ms)%n",
                    threadIndex, refreshInterval);

            while (true) {
                loadSkinportIfStale();
                Thread.sleep(refreshInterval);
            }

        } catch (InterruptedException e) {
            System.err.printf("[Thread-%d] ⚠️ Interrupted%n", threadIndex);
        } catch (Exception e) {
            System.err.printf("[Thread-%d] ❌ Unexpected error: %s%n", threadIndex, e.getMessage());
        }
    }

    public static double getPriceEUR(String marketHashName) {
        if (marketHashName == null || marketHashName.isBlank()) return 0.0;

        String normalized = normalizeName(marketHashName);
        loadSkinportIfStale();

        Double sp = skinportMap.get(normalized);
        if (sp != null && sp > 0) return sp;

        if (priceCollection != null) {
            Document cached = priceCollection.find(Filters.eq("_id", normalized)).first();
            if (cached != null && cached.containsKey("price"))
                return cached.getDouble("price");
        }

        double steam = steamPriceOverview(normalized);
        if (steam > 0) savePrice(normalized, steam);
        return steam;
    }

    /**
     * Load prices from Skinport and save to MongoDB.
     */
    private static void loadSkinportIfStale() {
        long now = Instant.now().toEpochMilli();
        if (now - skinportLastLoad < SKINPORT_TTL_MS && !skinportMap.isEmpty()) return;

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(SKINPORT_URL).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CS2PriceBot");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (SKINPORT_API_KEY != null && !SKINPORT_API_KEY.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + SKINPORT_API_KEY);
            }

            int code = conn.getResponseCode();
            if (code == 429) {
                System.err.println("[PriceProvider] ⚠️ Skinport rate limit hit — waiting 3 min...");
                Thread.sleep(180_000);
                return;
            }
            if (code != 200) {
                System.err.println("[PriceProvider] ⚠️ Skinport HTTP " + code);
                return;
            }

            JsonReader reader = new JsonReader(new InputStreamReader(conn.getInputStream()));
            reader.setLenient(true);
            JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();

            List<JsonElement> all = new ArrayList<>();
            arr.forEach(all::add);
            Collections.shuffle(all);
            List<JsonElement> subset = all.subList(0, Math.min(100, all.size()));

            Map<String, Double> temp = new HashMap<>();
            int total = subset.size();
            int processed = 0;
            long startTime = System.currentTimeMillis();

            System.out.printf("[Skinport] 🚀 Updating %d random skin prices...%n", total);

            for (JsonElement el : subset) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("market_hash_name")) continue;

                String name = o.get("market_hash_name").getAsString();
                String n = normalizeName(name);
                double price = safeDouble(o, "lowest_price");
                if (price <= 0) price = safeDouble(o, "min_price");

                if (price > 0) {
                    temp.put(n, price);
                    savePrice(n, price); // ✅ save directly to MongoDB
                }

                processed++;
                if (processed % 10 == 0 || processed == total) {
                    double percent = (processed / (double) total) * 100;
                    System.out.printf("[Skinport] ⏳ %d/%d (%.0f%%)%n", processed, total, percent);
                }

                Thread.sleep(200);
            }

            long duration = System.currentTimeMillis() - startTime;
            if (!temp.isEmpty()) {
                skinportMap.clear();
                skinportMap.putAll(temp);
                skinportLastLoad = now;
                System.out.printf("[PriceProvider] ✅ Loaded %d Skinport prices in %.1fs%n",
                        temp.size(), duration / 1000.0);
            }

        } catch (Exception e) {
            System.err.println("[PriceProvider] ⚠️ Skinport fetch issue: " + e.getMessage());
        }
    }

    private static void savePrice(String name, double price) {
        try {
            if (priceCollection == null) return;
            Document doc = new Document("_id", name)
                    .append("price", price)
                    .append("updated", new Date());
            priceCollection.replaceOne(Filters.eq("_id", name), doc, new ReplaceOptions().upsert(true));
        } catch (Exception e) {
            System.err.println("[PriceProvider] ⚠️ Mongo save failed for " + name + ": " + e.getMessage());
        }
    }

    private static double steamPriceOverview(String marketHashName) {
        String url = "https://steamcommunity.com/market/priceoverview/"
                + "?currency=3&appid=730&market_hash_name=" + encode(marketHashName);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                steamLimiter.acquire();
                Thread.sleep(200 + rand.nextInt(400));

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (CS2PriceBot)");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() != 200) return 0.0;

                JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream()))
                        .getAsJsonObject();

                if (!json.has("lowest_price") || json.get("lowest_price").isJsonNull()) return 0.0;

                String priceStr = json.get("lowest_price").getAsString()
                        .replace("€", "").replace(",", ".").replace(" ", "").trim();

                if (!priceStr.matches("[0-9.]+")) return 0.0;

                double price = Double.parseDouble(priceStr);
                savePrice(marketHashName, price); // ✅ store successful Steam prices
                System.out.printf("[Steam] 💰 %s = %.2f€%n", marketHashName, price);
                return price;

            } catch (Exception ignored) {
            } finally {
                new Timer(true).schedule(new TimerTask() {
                    @Override public void run() { steamLimiter.release(); }
                }, 1000);
            }
        }
        return 0.0;
    }

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
