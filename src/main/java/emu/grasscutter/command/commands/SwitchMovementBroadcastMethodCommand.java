package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.World;
import emu.grasscutter.server.packet.recv.HandlerCombatInvocationsNotify;
import java.util.List;

/**
 * Switches the co-op movement broadcast method (host only).
 *
 * <p>Usage: {@code /smcm tp|normal} — without an argument the current method is shown.
 *
 * <ul>
 *   <li>{@code tp} (default): relocate peers every 100ms with a single
 *       SceneEntityAppearNotify(VISION_REPLACE) snap (the working fallback for 6.7).
 *   <li>{@code normal}: rebroadcast EntityMoveInfo to peers through the canonical
 *       CombatInvocationsNotify forward channel.
 * </ul>
 */
@Command(
        label = "smcm",
        aliases = {"switchmovement", "movementmethod", "syncmethod"},
        usage = "tp|normal",
        targetRequirement = Command.TargetRequirement.NONE)
public final class SwitchMovementBroadcastMethodCommand implements CommandHandler {

    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        World world = sender.getWorld();
        if (world == null || world.getHost() != sender) {
            CommandHandler.sendMessage(sender, "仅世界主机（1P）可以切换移动广播方式。");
            Grasscutter.getLogger()
                    .info("SMM: {} (uid={}) is not host, rejected", sender.getNickname(), sender.getUid());
            return;
        }

        if (args.isEmpty()) {
            CommandHandler.sendMessage(
                    sender,
                    "当前移动广播方式: "
                            + (HandlerCombatInvocationsNotify.useSceneEntityMoveNotify
                                    ? "normal (CombatInvocationsNotify forward)"
                                    : "tp (SceneEntityAppearNotify VISION_REPLACE)"));
            return;
        }

        switch (args.get(0).trim().toLowerCase()) {
            case "tp", "replace", "fallback" -> {
                HandlerCombatInvocationsNotify.useSceneEntityMoveNotify = false;
                Grasscutter.getLogger().info("SMM: host {} switched to tp (VISION_REPLACE)", sender.getUid());
                CommandHandler.sendMessage(
                        sender, "移动广播已切换为 tp：SceneEntityAppearNotify(VISION_REPLACE) 100ms 快照。");
            }
            case "normal", "move", "proto" -> {
                HandlerCombatInvocationsNotify.useSceneEntityMoveNotify = true;
                Grasscutter.getLogger()
                        .info("SMM: host {} switched to normal (CombatInvocationsNotify forward)", sender.getUid());
                CommandHandler.sendMessage(
                        sender, "移动广播已切换为 normal：CombatInvocationsNotify 转发通道。");
            }
            default -> CommandHandler.sendMessage(sender, "用法: /smcm tp|normal");
        }
    }
}
