package com.example.cs2bot.listeners;

import com.example.cs2bot.db.MongoUtil;
import com.mongodb.client.MongoCollection;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;

import java.awt.*;
import java.util.Random;

public class ButtonListener extends ListenerAdapter {

    private final Random random = new Random();

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();

        switch (id) {
            case "get_key" -> event.reply("🗝️ You received a key! Use it to open a case.")
                    .setEphemeral(true).queue();

            case "open_case", "open_prisma2", "open_revolution", "open_dreams" -> {
                String caseName = switch (id) {
                    case "open_prisma2" -> "🎨 Prisma 2 Case";
                    case "open_revolution" -> "⚡ Revolution Case";
                    case "open_dreams" -> "💤 Dreams & Nightmares Case";
                    default -> "Mystery Case";
                };

                MongoCollection<Document> skins = MongoUtil.getDB().getCollection("skins");
                long count = skins.countDocuments();

                if (count == 0) {
                    event.reply("⚠️ No skins available in the database!")
                            .setEphemeral(true).queue();
                    return;
                }

                int randomIndex = random.nextInt((int) count);
                Document skin = skins.find().skip(randomIndex).first();
                if (skin == null) {
                    event.reply("⚠️ Error fetching skin.")
                            .setEphemeral(true).queue();
                    return;
                }

                String name = skin.getString("name");
                String wear = skin.getString("wear");
                double price = skin.getDouble("price");
                double wearFloat = skin.containsKey("float") ? skin.getDouble("float") : 0.0;
                String rarity = skin.getString("rarity");
                String image = skin.getString("image");

                if (name != null) name = name.replace("?", "★").trim();
                if (rarity == null) rarity = "Unknown";

                Color embedColor = switch (rarity) {
                    case "Consumer Grade" -> new Color(211, 211, 211);
                    case "Industrial Grade" -> new Color(94, 152, 217);
                    case "Mil-Spec" -> new Color(75, 105, 255);
                    case "Restricted" -> new Color(136, 71, 255);
                    case "Classified" -> new Color(211, 44, 230);
                    case "Covert" -> new Color(235, 75, 75);
                    case "Extraordinary" -> new Color(255, 215, 0);
                    default -> Color.WHITE;
                };

                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("🎁 You opened a " + caseName + "!")
                        .setDescription("You unboxed a **" + rarity + "** skin:\n\n" +
                                "🪙 **" + name + "** (" + wear + ")\n" +
                                "💶 Price: €" + String.format("%.2f", price) + "\n" +
                                "🧮 Float: " + String.format("%.4f", wearFloat))
                        .setColor(embedColor);

                if (image != null && !image.isBlank())
                    embed.setThumbnail(image);

                event.replyEmbeds(embed.build()).queue();
            }

            case "inventory" ->
                    event.reply("📦 Opening inventory... (coming soon!)").setEphemeral(true).queue();

            case "trade" ->
                    event.reply("💱 Starting trade... (coming soon!)").setEphemeral(true).queue();

            default -> event.reply("⚠️ Unknown button action: `" + id + "`")
                    .setEphemeral(true).queue();
        }
    }
}
