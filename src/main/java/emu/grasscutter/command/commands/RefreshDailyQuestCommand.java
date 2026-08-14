package emu.grasscutter.command.commands;

import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.player.Player;
import java.util.List;

/**
 * Immediately re-dispatches a player's 4 daily commissions, bypassing the 04:00 UTC+8 boundary.
 *
 * <p>Usage: {@code /rdq [@UID] [fieldNumber]} — without @UID the command targets the sender. An
 * optional numeric argument probes the daily_task_id wire field number (test mode).
 */
@Command(
        label = "rdq",
        aliases = {"refreshdailyquest", "refreshdaily", "dailyrefresh"},
        usage = "[@UID] [dailyTaskIdField]",
        permission = "server.dailyquest",
        permissionTargeted = "server.dailyquest.others",
        targetRequirement = Command.TargetRequirement.ONLINE)
public final class RefreshDailyQuestCommand implements CommandHandler {

    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        if (targetPlayer == null) {
            CommandHandler.sendMessage(sender, "请指定目标玩家 @UID。");
            return;
        }

        targetPlayer.getDailyCommissionManager().resetDailyTasks(true);

        String name = targetPlayer.getNickname();
        if (!args.isEmpty()) {
            try {
                int field = Integer.parseInt(args.get(0).trim());
                targetPlayer.getDailyCommissionManager().sendInfoNotify(field);
                CommandHandler.sendMessage(
                        sender, "已为 " + name + " 刷新委托，并以字段号 " + field + " 下发 daily_task_id 测试包。");
                return;
            } catch (NumberFormatException e) {
                CommandHandler.sendMessage(sender, "字段号必须是数字。");
                return;
            }
        }

        targetPlayer.getDailyCommissionManager().sendInfoNotify();
        CommandHandler.sendMessage(sender, "已为 " + name + " 刷新每日委托（重新派发 4 个委托，进度与计数已清零）。");
    }
}
