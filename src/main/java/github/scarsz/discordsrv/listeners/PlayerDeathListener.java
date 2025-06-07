/*
 * DiscordSRV - https://github.com/DiscordSRV/DiscordSRV
 *
 * Copyright (C) 2016 - 2024 Austin "Scarsz" Shapiro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package github.scarsz.discordsrv.listeners;

import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.events.DeathMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.DeathMessagePreProcessEvent;
import github.scarsz.discordsrv.objects.MessageFormat;
import github.scarsz.discordsrv.util.*;
import io.github.ilightwas.proxysrv.ProxySRV;
import io.github.ilightwas.proxysrv.ProxySRV.ProxyEvent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;

import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class PlayerDeathListener implements Listener, PluginMessageListener {

    private static final Method DEATH_MESSAGE;
    private static final Method GSON;
    private static final Method SERIALIZE;
    private static final Method DESERIALIZE;
    private static final MethodHandle BROADCAST;
    private static final Function<PlayerDeathEvent, byte[]> handleDeathMessageOut;
    private static final Consumer<String> handleDeathMessageIn;

    static {
        Method tDeathMessage = null;
        Method tGson = null;
        Method tSerialize = null;
        Method tDeserialize = null;
        MethodHandle tBroadcast = null;
        Function<PlayerDeathEvent, byte[]> tHandleDeathMessageOut = null;
        Consumer<String> tHandleDeathMessageIn = null;

        try {
            Class<?> playerDeathEventClass = Class.forName("org.bukkit.event.entity.PlayerDeathEvent");
            tDeathMessage = playerDeathEventClass.getMethod("deathMessage");

            // Avoid the relocation -_-
            String noReloc = "et.kyori.adventure.text.serializer.gson.GsonComponentSerializer";
            Class<?> gsonSerializer = Class.forName("n" + noReloc);
            tGson = gsonSerializer.getMethod("gson");

            Class<?> unshadedComponent = tDeathMessage.getReturnType();
            tSerialize = gsonSerializer.getMethod("serialize", unshadedComponent);
            tDeserialize = gsonSerializer.getMethod("deserialize", Object.class);

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType methodType = MethodType.methodType(int.class, unshadedComponent);
            tBroadcast = lookup.findStatic(Bukkit.class, "broadcast", methodType)
                    .asType(MethodType.methodType(int.class, Object.class));

            tHandleDeathMessageOut = PlayerDeathListener::getDeathPluginMessageBytes;
            tHandleDeathMessageIn = PlayerDeathListener::broadcastDeathPluginMessage;
        } catch (Exception ex) {
            ex.printStackTrace();
            tHandleDeathMessageOut = e -> null;
            tHandleDeathMessageIn = e -> {};
        }

        DEATH_MESSAGE = tDeathMessage;
        GSON = tGson;
        SERIALIZE = tSerialize;
        DESERIALIZE = tDeserialize;
        BROADCAST = tBroadcast;
        handleDeathMessageOut = tHandleDeathMessageOut;
        handleDeathMessageIn = tHandleDeathMessageIn;
    }

    private static byte[] getDeathPluginMessageBytes(PlayerDeathEvent e) {
        byte[] msgBytes = null;
        try {
            Object component = DEATH_MESSAGE.invoke(e);
            Object gson = GSON.invoke(null);
            String serialized = (String) SERIALIZE.invoke(gson, component);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeByte(ProxyEvent.DEATH.opcode());
            out.writeUTF(serialized);
            msgBytes = out.toByteArray();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return msgBytes;
    }

    private static void broadcastDeathPluginMessage(String gson) {
        try {
            Object g = GSON.invoke(null);
            Object c = DESERIALIZE.invoke(g, gson);
            /*
             * Must cast return to hint the compiler, stuff about type descriptors with
             * invokeExact
             * https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandle.html
             */
            int ignored = (int) BROADCAST.invokeExact(c);
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(ProxySRV.CHANNEL)) {
            return;
        }

        ByteArrayDataInput bIn = ByteStreams.newDataInput(message);
        ProxyEvent e = ProxyEvent.fromOpcode(bIn.readByte());
        if (e != ProxyEvent.DEATH) {
            return;
        }
        handleDeathMessageIn.accept(bIn.readUTF());
    }

    public PlayerDeathListener() {
        DiscordSRV discordSRV = DiscordSRV.getPlugin();
        Bukkit.getPluginManager().registerEvents(this, discordSRV);
        Messenger messenger = discordSRV.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(discordSRV, ProxySRV.CHANNEL, this);
        messenger.registerOutgoingPluginChannel(discordSRV, ProxySRV.CHANNEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getEntityType() != EntityType.PLAYER) return;

        Player player = event.getEntity();

        // respect invisibility plugins
        if (PlayerUtil.isVanished(player)) return;

        byte[] msg = handleDeathMessageOut.apply(event);
        if (msg != null)
            player.sendPluginMessage(DiscordSRV.getPlugin(), ProxySRV.CHANNEL, msg);

        String message = event.getDeathMessage();
        SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(), () -> runAsync(event, player, message));
    }

    private void runAsync(PlayerDeathEvent event, Player player, String deathMessage) {
        if (StringUtils.isBlank(deathMessage)) {
            DiscordSRV.debug("Not sending death message for " + player.getName() + ", the death message is null");
            return;
        }

        String channelName = DiscordSRV.getPlugin().getOptionalChannel("deaths");
        MessageFormat messageFormat = DiscordSRV.getPlugin().getMessageFromConfiguration("MinecraftPlayerDeathMessage");
        if (messageFormat == null) return;

        DeathMessagePreProcessEvent preEvent = DiscordSRV.api.callEvent(new DeathMessagePreProcessEvent(channelName, messageFormat, player, deathMessage, event));
        if (preEvent.isCancelled()) {
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "DeathMessagePreProcessEvent was cancelled, message send aborted");
            return;
        }

        // Update from event in case any listeners modified parameters
        channelName = preEvent.getChannel();
        messageFormat = preEvent.getMessageFormat();
        deathMessage = preEvent.getDeathMessage();

        if (messageFormat == null) return;

        String finalDeathMessage = StringUtils.isNotBlank(deathMessage) ? deathMessage : "";
        String avatarUrl = DiscordSRV.getAvatarUrl(event.getEntity());
        String botAvatarUrl = DiscordUtil.getJda().getSelfUser().getEffectiveAvatarUrl();
        String botName = DiscordSRV.getPlugin().getMainGuild() != null ? DiscordSRV.getPlugin().getMainGuild().getSelfMember().getEffectiveName() : DiscordUtil.getJda().getSelfUser().getName();
        String displayName = StringUtils.isNotBlank(player.getDisplayName()) ? MessageUtil.strip(player.getDisplayName()) : "";

        TextChannel destinationChannel = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(channelName);
        BiFunction<String, Boolean, String> translator = (content, needsEscape) -> {
            if (content == null) return null;
            content = content
                    .replaceAll("%time%|%date%", TimeUtil.timeStamp())
                    .replace("%username%", needsEscape ? DiscordUtil.escapeMarkdown(player.getName()) : player.getName())
                    .replace("%displayname%", needsEscape ? DiscordUtil.escapeMarkdown(displayName) : displayName)
                    .replace("%usernamenoescapes%", player.getName())
                    .replace("%displaynamenoescapes%", displayName)
                    .replace("%world%", player.getWorld().getName())
                    .replace("%deathmessage%", MessageUtil.strip(needsEscape ? DiscordUtil.escapeMarkdown(finalDeathMessage) : finalDeathMessage))
                    .replace("%deathmessagenoescapes%", MessageUtil.strip(finalDeathMessage))
                    .replace("%embedavatarurl%", avatarUrl)
                    .replace("%botavatarurl%", botAvatarUrl)
                    .replace("%botname%", botName);
            if (destinationChannel != null) content = DiscordUtil.translateEmotes(content, destinationChannel.getGuild());
            content = PlaceholderUtil.replacePlaceholdersToDiscord(content, player);
            return content;
        };
        Message discordMessage = DiscordSRV.translateMessage(messageFormat, translator);
        if (discordMessage == null) return;

        String webhookName = translator.apply(messageFormat.getWebhookName(), false);
        String webhookAvatarUrl = translator.apply(messageFormat.getWebhookAvatarUrl(), false);

        if (DiscordSRV.getLength(discordMessage) < 3) {
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Not sending death message, because it's less than three characters long. Message: " + messageFormat);
            return;
        }

        DeathMessagePostProcessEvent postEvent = DiscordSRV.api.callEvent(new DeathMessagePostProcessEvent(channelName, discordMessage, player, deathMessage, event, messageFormat.isUseWebhooks(), webhookName, webhookAvatarUrl, preEvent.isCancelled()));
        if (postEvent.isCancelled()) {
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "DeathMessagePostProcessEvent was cancelled, message send aborted");
            return;
        }

        // Update from event in case any listeners modified parameters
        channelName = postEvent.getChannel();
        discordMessage = postEvent.getDiscordMessage();

        TextChannel textChannel = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(channelName);
        if (postEvent.isUsingWebhooks()) {
            WebhookUtil.deliverMessage(textChannel, postEvent.getWebhookName(), postEvent.getWebhookAvatarUrl(),
                    discordMessage.getContentRaw(), discordMessage.getEmbeds().stream().findFirst().orElse(null));
        } else {
            DiscordUtil.queueMessage(textChannel, discordMessage, true);
        }
    }

}
