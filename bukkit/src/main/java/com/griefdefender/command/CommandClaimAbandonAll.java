/*
 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.griefdefender.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.griefdefender.GDPlayerData;
import com.griefdefender.GriefDefenderPlugin;
import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.permission.option.Options;
import com.griefdefender.cache.MessageCache;
import com.griefdefender.cache.PermissionHolderCache;
import com.griefdefender.claim.GDClaim;
import com.griefdefender.claim.GDClaimManager;
import com.griefdefender.configuration.MessageStorage;
import com.griefdefender.event.GDCauseStackManager;
import com.griefdefender.event.GDRemoveClaimEvent;
import com.griefdefender.permission.GDPermissionManager;
import com.griefdefender.permission.GDPermissionUser;
import com.griefdefender.permission.GDPermissions;
import com.griefdefender.text.action.GDCallbackHolder;
import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.adapter.bukkit.TextAdapter;
import net.kyori.text.event.ClickEvent;
import net.kyori.text.event.HoverEvent;
import net.kyori.text.format.TextColor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CommandAlias("%griefdefender")
@CommandPermission(GDPermissions.COMMAND_ABANDON_ALL_CLAIMS)
public class CommandClaimAbandonAll extends BaseCommand {

    @CommandCompletion("@gdworlds @gddummy")
    @CommandAlias("abandonall|abandonallclaims")
    @Description("%abandon-all")
    @Subcommand("abandon all")
    public void execute(Player player, @Optional String worldName) {
        final GDPermissionUser user = PermissionHolderCache.getInstance().getOrCreateUser(player);
        int originalClaimCount = user.getInternalPlayerData().getInternalClaims().size();
        World world = null;
        if (worldName != null) {
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                TextAdapter.sendComponent(player, MessageStorage.MESSAGE_DATA.getMessage(MessageStorage.COMMAND_WORLD_NOT_FOUND,
                        ImmutableMap.of("world", worldName)));
                return;
            }
            final GDClaimManager claimManager = GriefDefenderPlugin.getInstance().dataStore.getClaimWorldManager(world.getUID());
            final Set<Claim> claims = claimManager.getPlayerClaims(player.getUniqueId());
            if (claims == null || claims.isEmpty()) {
                originalClaimCount = 0;
            }
        }

        if (originalClaimCount == 0) {
            try {
                throw new CommandException(MessageCache.getInstance().CLAIM_NO_CLAIMS);
            } catch (CommandException e) {
                TextAdapter.sendComponent(player, e.getText());
                return;
            }
        }

        final boolean autoSchematicRestore = GriefDefenderPlugin.getActiveConfig(player.getWorld().getUID()).getConfig().claim.claimAutoSchematicRestore;
        Component message = null;
        if (world != null) {
            if (autoSchematicRestore) {
                message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.SCHEMATIC_ABANDON_ALL_RESTORE_WARNING_WORLD, ImmutableMap.of(
                        "world", world.getName()));
            } else {
                message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ABANDON_ALL_WARNING_WORLD, ImmutableMap.of(
                        "world", world.getName()));
            }
        } else {
            message = autoSchematicRestore ? MessageCache.getInstance().SCHEMATIC_ABANDON_ALL_RESTORE_WARNING : MessageCache.getInstance().ABANDON_ALL_WARNING;
        }
        final Component confirmationText = TextComponent.builder()
                .append(message)
                .append(TextComponent.builder()
                    .append("\n[")
                    .append(MessageCache.getInstance().LABEL_CONFIRM.color(TextColor.GREEN))
                    .append("]\n")
                    .clickEvent(ClickEvent.runCommand(GDCallbackHolder.getInstance().createCallbackRunCommand(player, createConfirmationConsumer(user, world), true)))
                    .hoverEvent(HoverEvent.showText(MessageCache.getInstance().UI_CLICK_CONFIRM)).build())
                .build();
        TextAdapter.sendComponent(player, confirmationText);
    }

    private static Consumer<CommandSender> createConfirmationConsumer(GDPermissionUser user, World world) {
        return confirm -> {
            Set<Claim> allowedClaims = new HashSet<>();
            Set<Claim> delayedClaims = new HashSet<>();
            final int abandonDelay = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(Integer.class), user, Options.ABANDON_DELAY);
            final Player player = user.getOnlinePlayer();
            final GDPlayerData playerData = user.getInternalPlayerData();
            Set<Claim> claims = new HashSet<>();
            if (world != null) {
                final GDClaimManager claimManager = GriefDefenderPlugin.getInstance().dataStore.getClaimWorldManager(world.getUID());
                claims = claimManager.getPlayerClaims(player.getUniqueId());
            } else {
                claims = playerData.getInternalClaims();
            }
            for (Claim claim : claims) {
                if (world != null && !claim.getWorldUniqueId().equals(world.getUID())) {
                    continue;
                }
                if (abandonDelay > 0) {
                    final Instant localNow = Instant.now();
                    final Instant dateCreated = ((GDClaim) claim).getInternalClaimData().getDateCreated();
                    final Instant dateExpires = dateCreated.plus(Duration.ofDays(abandonDelay));
                    final boolean delayActive = !dateExpires.isBefore(localNow);
                    if (delayActive) {
                        delayedClaims.add(claim);
                    } else {
                        allowedClaims.add(claim);
                    }
                } else {
                    allowedClaims.add(claim);
                }
            }

            if (allowedClaims.isEmpty() && delayedClaims.isEmpty()) {
                TextAdapter.sendComponent(user.getOnlinePlayer(), MessageCache.getInstance().CLAIM_NO_CLAIMS);
                return;
            }
            if (!allowedClaims.isEmpty()) {
                GDCauseStackManager.getInstance().pushCause(user);
                GDRemoveClaimEvent.Abandon event = new GDRemoveClaimEvent.Abandon(ImmutableList.copyOf(allowedClaims));
                GriefDefender.getEventManager().post(event);
                GDCauseStackManager.getInstance().popCause();
                if (event.cancelled()) {
                    TextAdapter.sendComponent(user.getOnlinePlayer(), event.getMessage().orElse(MessageCache.getInstance().PLUGIN_EVENT_CANCEL).color(TextColor.RED));
                    return;
                }

                double refund = 0;
                // adjust claim blocks
                for (Claim claim : allowedClaims) {
                    if (claim.isSubdivision() || claim.isAdminClaim() || claim.isWilderness()) {
                        continue;
                    }
                    final double abandonReturnRatio = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(Double.class), user, Options.ABANDON_RETURN_RATIO, claim);
                    if (GriefDefenderPlugin.getInstance().isEconomyModeEnabled()) {
                        final double requiredClaimBlocks = claim.getClaimBlocks() * abandonReturnRatio;
                        refund += requiredClaimBlocks * ((GDClaim) claim).getOwnerEconomyBlockCost();
                    } else {
                        playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - ((int) Math.ceil(claim.getClaimBlocks() * (1 - abandonReturnRatio))));
                    }
                }
    
                playerData.useRestoreSchematic = event.isRestoring();
                GriefDefenderPlugin.getInstance().dataStore.abandonClaimsForPlayer(user, allowedClaims);
                playerData.useRestoreSchematic = false;
                playerData.onClaimDelete();
                
                SurvivalProfile profile = SurvivalProfile.getByUUID(user.getOfflinePlayer().getUniqueId());
                if (profile == null) return;
    
                final boolean result = profile.getStatistics().deposit(user.getOfflinePlayer(), refund);
                if (result.transactionSuccess()) {
                    Component message = null;
                    if (world != null) message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_CLAIM_ABANDON_SUCCESS_WORLD, ImmutableMap.of("world", world.getName(), "amount", TextComponent.of(String.valueOf(refund))));
                    else message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.ECONOMY_CLAIM_ABANDON_SUCCESS, ImmutableMap.of("amount", TextComponent.of(String.valueOf(refund))));
                    TextAdapter.sendComponent(player, message);
                }
            }

            if (!delayedClaims.isEmpty()) {
                TextAdapter.sendComponent(player, MessageCache.getInstance().ABANDON_ALL_DELAY_WARNING);
                CommandHelper.showClaims(player, delayedClaims, 0, true);
            }
        };
    }
}
