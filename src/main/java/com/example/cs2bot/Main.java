package com.example.cs2bot;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import com.example.cs2bot.db.MongoUtil;
import com.example.cs2bot.listeners.SlashCommandListener;
import com.example.cs2bot.listeners.ButtonListener;
import com.example.cs2bot.utils.PriceUpdater;
import com.example.cs2bot.utils.SteamSchemaAPI;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {

        // ✅ Load .env safely
        Dotenv dotenv = Dotenv.configure()
                .directory(System.getProperty("user.dir"))
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();

        String token = dotenv.get("BOT_TOKEN");
        String mongoUri = dotenv.get("MONGO_URI");
        String steamKey = dotenv.get("STEAM_API_KEY");

        // 🟡 Debug print for sanity check
        System.out.println("🔍 Environment check:");
        System.out.println(" - BOT_TOKEN loaded? " + (token != null && !token.isBlank()));
        System.out.println(" - MONGO_URI loaded? " + (mongoUri != null && !mongoUri.isBlank()));
        System.out.println(" - STEAM_API_KEY: " + (steamKey != null && !steamKey.isBlank() ? steamKey : "❌ MISSING"));

        if (token == null || token.isBlank() || mongoUri == null || mongoUri.isBlank()) {
            System.err.println("❌ Missing BOT_TOKEN or MONGO_URI in .env file!");
            System.exit(1);
        }

        // ✅ Init MongoDB
        MongoUtil.init(mongoUri, "cs2_case_bot");

        // ✅ Load rarities from Steam (requires STEAM_API_KEY)
        SteamSchemaAPI.loadRarities();

        // ✅ Build Discord bot
        var jda = JDABuilder.createDefault(token,
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.MESSAGE_CONTENT)
                .setActivity(Activity.playing("CS2 Case Bot"))
                .addEventListeners(
                        new SlashCommandListener(),
                        new ButtonListener(),
                        new com.example.cs2bot.commands.InventoryCommand()
                )
                .build()
                .awaitReady();

        // ✅ Register slash commands
        String guildId = dotenv.get("GUILD_ID");
        if (guildId != null && !guildId.isBlank()) {
            jda.updateCommands().addCommands(
                    Commands.slash("case", "Open the CS2 case menu"),
                    Commands.slash("inventory", "View your inventory")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "User to view", false),
                    Commands.slash("trade", "Start a trade with another user")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "User to trade with", true),
                    Commands.slash("refreshprices", "Admin: refresh all skin prices from Steam now")
            ).queue();
            System.out.println("✅ Registered guild commands for guild: " + guildId);
        } else {
            jda.updateCommands().addCommands(
                    Commands.slash("case", "Open the CS2 case menu"),
                    Commands.slash("inventory", "View your inventory")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "User to view", false),
                    Commands.slash("trade", "Start a trade with another user")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.USER, "user", "User to trade with", true),
                    Commands.slash("refreshprices", "Admin: refresh all skin prices from Steam now")
            ).queue();
            System.out.println("✅ Registered global commands (may take up to 1 hour).");
        }

        System.out.println("🚀 Bot started.");

        // ✅ One single-threaded updater (no 429s)
        System.out.println("🧩 Starting single-threaded PriceUpdater (3500ms delay between calls)");
        new PriceUpdater(3500, 1, 0).run();

        // If you’d prefer it to rerun automatically every 6 hours, uncomment below:
        /*
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            try {
                new PriceUpdater(3500, 1, 0).run();
            } catch (Exception e) {
                System.err.println("[Updater] ❌ " + e.getMessage());
            }
        }, 5, 6 * 60 * 60, TimeUnit.SECONDS);
        */
    }
}
