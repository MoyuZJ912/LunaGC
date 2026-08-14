package emu.grasscutter.command.commands;

import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.player.Player;
import java.util.List;

/**
 * Immediately re-dispatches a player's 4 daily commissions, bypassing the 04:00 UTC+8 boundary.
 *
 * <p>Usage: {@code /refreshdailyquest [@UID]} — without @UID the command targets the sender.
 */
@Command(
        label = "refreshdailyquest",
        aliases = {"rdq", "refreshdaily", "dailyrefresh"},
        usage = "[@UID]",
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
        CommandHandler.sendMessage(sender, "已为 " + name + " 刷新每日委托（重新派发 4 个委托，进度与计数已清零）。");
    }
}
