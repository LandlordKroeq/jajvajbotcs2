package com.example.cs2bot.utils;

import com.example.cs2bot.db.MongoUtil;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class PriceUpdater implements Runnable {

    private final long delayBetweenCallsMs;
    private final int threadCount;
    private final int threadIndex;

    public PriceUpdater(long delayBetweenCallsMs, int threadCount, int threadIndex) {
        this.delayBetweenCallsMs = delayBetweenCallsMs;
        this.threadCount = threadCount;
        this.threadIndex = threadIndex;
    }

    @Override
    public void run() {
        try {
            MongoCollection<Document> skins = MongoUtil.getDB().getCollection("skins");

            // ✅ Pre-fix all ? → ★ names before starting
            long fixedNames = skins.updateMany(
                    new Document("name", new Document("$regex", "^\\?")),
                    new Document("$set", new Document("nameFix", true))
            ).getModifiedCount();

            if (fixedNames > 0) {
                System.out.println("[Thread " + threadIndex + "] 🪄 Fixed " + fixedNames + " names (replacing ? → ★)");
            }

            // 🧩 Load all skins
            long total = skins.countDocuments();
            AtomicInteger updated = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();
            Random random = new Random();

            String[] wears = {
                    "Factory New",
                    "Minimal Wear",
                    "Field-Tested",
                    "Well-Worn",
                    "Battle-Scarred"
            };

            System.out.println("[Thread " + threadIndex + "] 🧩 Starting price update... (" + total + " total skins)");

            try (MongoCursor<Document> cur = skins.find().iterator()) {
                int index = 0;
                while (cur.hasNext()) {
                    Document d = cur.next();
                    if (index % threadCount != threadIndex) {
                        index++;
                        continue;
                    }

                    String name = d.getString("name");
                    if (name == null || name.isBlank()) {
                        index++;
                        continue;
                    }

                    // ✅ Clean up name inside code
                    name = name.replace("?", "★").trim();

                    // 🎲 Assign random wear + float
                    String wear = wears[random.nextInt(wears.length)];
                    double wearFloat = generateFloatForWear(wear);

                    // 🧠 Get rarity
                    String rarity = SteamSchemaAPI.getRarity(name);
                    if (rarity == null || rarity.isBlank()) rarity = "Unknown";

                    // 💡 Try multiple name formats for Steam API
                    String[] nameVariants = {
                            name + " (" + wear + ")",                        // with wear
                            name,                                            // base name
                            name.replace("★", "").trim() + " (" + wear + ")",// without star
                            name.replace("★", "").trim()                     // plain
                    };

                    double price = 0.0;
                    for (String variant : nameVariants) {
                        price = SteamMarketAPI.getPriceEUR(variant);
                        if (price > 0) break;
                        Thread.sleep(200);
                    }

                    if (price <= 0) {
                        skipped.incrementAndGet();
                        System.out.println("[Thread " + threadIndex + "] ⚠️ Skipped " + name + " (" + wear + ")");
                    } else {
                        // ✅ Save results back to Mongo
                        d.put("name", name);
                        d.put("wear", wear);
                        d.put("float", wearFloat);
                        d.put("rarity", rarity);
                        d.put("price", price);

                        skins.replaceOne(new Document("_id", d.get("_id")), d);
                        SteamPriceCache.put(name + " (" + wear + ")", price);

                        updated.incrementAndGet();
                        System.out.println("[Thread " + threadIndex + "] ✅ Updated " + name + " (" + wear + ") | €"
                                + String.format("%.2f", price) + " | " + rarity
                                + " | Float: " + String.format("%.4f", wearFloat));
                    }

                    double progress = 100.0 * (updated.get() + skipped.get()) / total;
                    if ((index + 1) % 50 == 0) {
                        System.out.printf("[Thread %d] ⏳ Progress: %.1f%% (%d/%d processed)%n",
                                threadIndex, progress, (updated.get() + skipped.get()), total);
                    }

                    index++;
                    Thread.sleep(delayBetweenCallsMs);
                }
            }

            double successRate = 100.0 * updated.get() / (updated.get() + skipped.get());
            System.out.printf("[Thread %d] ✅ Finished. %d updated, %d skipped (%.1f%% success)%n",
                    threadIndex, updated.get(), skipped.get(), successRate);

        } catch (Exception e) {
            System.err.println("[Thread " + threadIndex + "] ❌ Error: " + e.getMessage());
        }
    }

    private double generateFloatForWear(String wear) {
        Random r = new Random();
        return switch (wear) {
            case "Factory New" -> 0.00 + (0.07 - 0.00) * r.nextDouble();
            case "Minimal Wear" -> 0.07 + (0.15 - 0.07) * r.nextDouble();
            case "Field-Tested" -> 0.15 + (0.38 - 0.15) * r.nextDouble();
            case "Well-Worn" -> 0.38 + (0.45 - 0.38) * r.nextDouble();
            case "Battle-Scarred" -> 0.45 + (1.00 - 0.45) * r.nextDouble();
            default -> 0.0;
        };
    }
}
