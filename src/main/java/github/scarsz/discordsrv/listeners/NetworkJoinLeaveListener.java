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

import java.util.UUID;
import java.util.regex.Matcher;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.objects.managers.GroupSynchronizationManager;
import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.util.GamePermissionUtil;
import github.scarsz.discordsrv.util.MessageUtil;
import github.scarsz.discordsrv.util.NMSUtil;
import github.scarsz.discordsrv.util.PlayerUtil;
import github.scarsz.discordsrv.util.SchedulerUtil;
import io.github.ilightwas.proxysrv.ProxySRV;
import io.github.ilightwas.proxysrv.ProxySRV.ProxyEvent;

public class NetworkJoinLeaveListener implements Listener, PluginMessageListener {

    public NetworkJoinLeaveListener() {
        Bukkit.getPluginManager().registerEvents(this, DiscordSRV.getPlugin());
        DiscordSRV.getPlugin().getServer().getMessenger().registerIncomingPluginChannel(DiscordSRV.getPlugin(),
                ProxySRV.CHANNEL,
                this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        // if player is OP & update is available tell them
        if (GamePermissionUtil.hasPermission(player, "discordsrv.updatenotification") && DiscordSRV.updateIsAvailable) {
            MessageUtil.sendMessage(player, DiscordSRV.getPlugin().getDescription().getVersion().endsWith("-SNAPSHOT")
                    ? ChatColor.GRAY
                            + "There is a newer development build of DiscordSRV available. Download it at https://snapshot.discordsrv.com/"
                    : ChatColor.AQUA
                            + "An update to DiscordSRV is available. Download it at https://www.spigotmc.org/resources/discordsrv.18494/ or https://get.discordsrv.com");
        }

        if (DiscordSRV.getPlugin().isGroupRoleSynchronizationEnabled()) {
            // trigger a synchronization for the player
            SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(),
                    () -> DiscordSRV.getPlugin().getGroupSynchronizationManager().resync(
                            player,
                            GroupSynchronizationManager.SyncDirection.AUTHORITATIVE,
                            true,
                            GroupSynchronizationManager.SyncCause.PLAYER_JOIN));
        }

        if (PlayerUtil.isVanished(player)) {
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Not sending a join message for " + event.getPlayer().getName()
                    + " because a vanish plugin reported them as vanished");
            return;
        }

        // if enabled, set the player's discord nickname as their ign
        if (DiscordSRV.config().getBoolean("NicknameSynchronizationEnabled")) {
            SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(), () -> {
                final String discordId = DiscordSRV.getPlugin().getAccountLinkManager()
                        .getDiscordId(player.getUniqueId());
                DiscordSRV.getPlugin().getNicknameUpdater().setNickname(DiscordUtil.getMemberById(discordId), player);
            });
        }

        if (event.getPlayer().hasPlayedBefore())
            return; // only handle first join messages

        // schedule command to run in a second to be able to capture display name
        String message = event.getJoinMessage();
        SchedulerUtil.runTaskLaterAsynchronously(DiscordSRV.getPlugin(),
                () -> DiscordSRV.getPlugin().sendJoinMessage(event.getPlayer(), message), 20);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(ProxySRV.CHANNEL)) {
            return;
        }

        SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(), () -> {
            ProxySRV.EventData data = new ProxySRV.EventData();
            try {
                ByteArrayDataInput in = ByteStreams.newDataInput(message);
                data.proxyEvent = ProxyEvent.fromOpcode(in.readByte());
                data.userName = in.readUTF();
                data.uuidMSB = in.readLong();
                data.uuidLSB = in.readLong();
                int length = in.readInt();
                byte[] texture = new byte[length];
                in.readFully(texture);
                data.texture = texture;
            } catch (IllegalStateException ex) {
                DiscordSRV.getPlugin().getLogger()
                        .warning("Something went wrong while reading data from proxySRV");
                return;
            }

            Matcher m = NMSUtil.TEXTURE_URL_PATTERN.matcher(new String(data.texture));
            String texture = m.find() ? m.group("texture") : "";

            if (data.proxyEvent == ProxyEvent.JOIN) {
                DiscordSRV.getPlugin().sendJoinMessage(data.userName,
                        new UUID(data.uuidMSB, data.uuidLSB), "", texture);
            } else if (data.proxyEvent == ProxyEvent.QUIT) {
                DiscordSRV.getPlugin().sendLeaveMessage(data.userName,
                        new UUID(data.uuidMSB, data.uuidLSB), "", texture);
            }
        });
    }
}
