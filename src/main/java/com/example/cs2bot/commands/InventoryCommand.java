package com.example.cs2bot.commands;

import com.example.cs2bot.db.MongoUtil;
import com.mongodb.client.MongoCollection;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;

import java.awt.*;
import java.util.List;

public class InventoryCommand extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("inventory")) return;

        MongoCollection<Document> inventories = MongoUtil.getDB().getCollection("inventories");

        String targetId = event.getOption("user") != null
                ? event.getOption("user").getAsUser().getId()
                : event.getUser().getId();

        Document userInv = inventories.find(new Document("_id", targetId)).first();

        if (userInv == null || !userInv.containsKey("items")) {
            event.reply("📦 This user has no items yet!").setEphemeral(true).queue();
            return;
        }

        List<Document> items = userInv.getList("items", Document.class);

        if (items.isEmpty()) {
            event.reply("📦 This user has an empty inventory.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(event.getUser().getName() + "'s Inventory 🎒")
                .setColor(Color.ORANGE);

        StringBuilder desc = new StringBuilder();
        int limit = Math.min(items.size(), 10); // Show up to 10 items

        for (int i = 0; i < limit; i++) {
            Document item = items.get(i);
            String name = item.getString("name");
            String rarity = item.getString("rarity");
            String wear = item.getString("wear");
            Double price = item.getDouble("price");
            Double fl = item.getDouble("float");

            Color rarityColor = switch (rarity == null ? "" : rarity) {
                case "Consumer Grade" -> new Color(211, 211, 211);
                case "Industrial Grade" -> new Color(94, 152, 217);
                case "Mil-Spec" -> new Color(75, 105, 255);
                case "Restricted" -> new Color(136, 71, 255);
                case "Classified" -> new Color(211, 44, 230);
                case "Covert" -> new Color(235, 75, 75);
                case "Extraordinary" -> new Color(255, 215, 0);
                default -> Color.WHITE;
            };

            embed.setColor(rarityColor);

            desc.append(String.format(
                    "🎯 **%s** (%s)\n💧 Float: %.4f | 💶 €%.2f | ⭐ %s\n\n",
                    name, wear, fl != null ? fl : 0.0, price != null ? price : 0.0, rarity
            ));
        }

        embed.setDescription(desc.toString());
        event.replyEmbeds(embed.build()).queue();
    }
}
