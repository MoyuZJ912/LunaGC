package emu.grasscutter.command.commands;

import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.World;
import java.util.List;

@Command(
        label = "time",
        usage = {"set <HH:mm>", "step <HH:mm>", "get"},
        permission = "player.time",
        permissionTargeted = "player.time.others")
public final class TimeCommand implements CommandHandler {

    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        if (args.size() < 1) {
            this.sendUsageMessage(sender);
            return;
        }

        switch (args.get(0).toLowerCase()) {
            case "get" -> {
                int minutes = targetPlayer.getWorld().getGameTime();
                CommandHandler.sendMessage(
                        sender,
                        "当前游戏内时间: " + formatTime(minutes) + " (" + minutes + " 分钟)");
            }
            case "set" -> {
                if (args.size() < 2) {
                    this.sendUsageMessage(sender);
                    return;
                }
                int[] hm = parseTime(args.get(1));
                if (hm == null) {
                    CommandHandler.sendMessage(sender, "时间格式错误，应为 HH:mm（如 10:00）。");
                    return;
                }

                World world = targetPlayer.getWorld();
                world.changeTime(hm[0] * 60 + hm[1], 0);
                world.updateTime();
                CommandHandler.sendMessage(sender, "时间已设置为 " + formatTime(hm[0] * 60 + hm[1]) + "。");
            }
            case "step" -> {
                if (args.size() < 2) {
                    this.sendUsageMessage(sender);
                    return;
                }
                int[] hm = parseTime(args.get(1));
                if (hm == null) {
                    CommandHandler.sendMessage(sender, "时长格式错误，应为 HH:mm（如 00:10 或 10:00）。");
                    return;
                }
                int step = hm[0] * 60 + hm[1];
                if (step == 0) {
                    CommandHandler.sendMessage(sender, "步进时长为 0，未做修改。");
                    return;
                }

                World world = targetPlayer.getWorld();
                int target = (world.getGameTime() + step) % 1440;
                world.changeTime(target, 0);
                world.updateTime();
                CommandHandler.sendMessage(
                        sender,
                        "时间已步进 " + formatTime(step) + "，当前为 " + formatTime(target) + "。");
            }
            default -> this.sendUsageMessage(sender);
        }
    }

    /** Formats a minute-of-day (0-1439) value as {@code HH:mm}. */
    private static String formatTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Parses {@code HH:mm}, returning {@code [hours, minutes]} or {@code null} on failure. */
    private static int[] parseTime(String s) {
        String[] parts = s.split(":");
        if (parts.length < 2) return null;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[] {h, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
