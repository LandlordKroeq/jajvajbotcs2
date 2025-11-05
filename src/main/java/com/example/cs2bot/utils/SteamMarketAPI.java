package com.example.cs2bot.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import java.util.concurrent.Semaphore;

public class SteamMarketAPI {

    // Global limiter: only 1 request allowed per second across all threads
    private static final Semaphore limiter = new Semaphore(1);
    private static final Random random = new Random();

    /**
     * Fetch Steam Market price (EUR) for a given skin name.
     * Handles 429s, spacing, and retries globally.
     */
    public static double getPriceEUR(String marketHashName) {
        if (marketHashName == null || marketHashName.isBlank()) return 0.0;

        String url = String.format(
                "https://steamcommunity.com/market/priceoverview/?currency=3&appid=730&market_hash_name=%s",
                encode(marketHashName)
        );

        int attempts = 0;
        while (attempts < 3) {
            attempts++;

            try {
                // 🧱 Acquire global permit (1 request at a time)
                limiter.acquire();

                // 🕒 Random delay before request (simulate natural access)
                Thread.sleep(200 + random.nextInt(400));

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (CS2PriceBot)");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int code = conn.getResponseCode();

                if (code == 429) {
                    int wait = 6000 + random.nextInt(4000); // 6–10 seconds
                    System.err.printf("[SteamMarketAPI] ⚠️  429 Rate limit hit for %s — waiting %ds%n",
                            marketHashName, wait / 1000);
                    Thread.sleep(wait);
                    continue;
                }

                if (code != 200) {
                    System.err.printf("[SteamMarketAPI] ⚠️  HTTP %d for %s%n", code, marketHashName);
                    return 0.0;
                }

                JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();

                if (!json.has("success") || !json.get("success").getAsBoolean()) {
                    System.err.printf("[SteamMarketAPI] ⚠️  Invalid response for %s%n", marketHashName);
                    return 0.0;
                }

                String priceStr = json.get("lowest_price").getAsString()
                        .replace("€", "")
                        .replace(",", ".")
                        .trim();

                try {
                    return Double.parseDouble(priceStr);
                } catch (NumberFormatException e) {
                    System.err.printf("[SteamMarketAPI] ⚠️  Bad price format '%s' for %s%n", priceStr, marketHashName);
                    return 0.0;
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return 0.0;
            } catch (Exception e) {
                System.err.printf("[SteamMarketAPI] ❌ Error fetching %s: %s%n", marketHashName, e.getMessage());
                try {
                    Thread.sleep(2000 + random.nextInt(2000)); // wait 2–4s on failure
                } catch (InterruptedException ignored) {}
            } finally {
                // Always release permit after 1 second
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        limiter.release();
                    } catch (InterruptedException ignored) {}
                }).start();
            }
        }

        System.err.printf("[SteamMarketAPI] ⚠️  Skipped %s after 3 failed attempts.%n", marketHashName);
        return 0.0;
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
