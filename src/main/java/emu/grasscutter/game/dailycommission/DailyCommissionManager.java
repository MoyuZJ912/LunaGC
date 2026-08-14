package emu.grasscutter.game.dailycommission;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.daily.DailyTaskData;
import emu.grasscutter.game.player.BasePlayerManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/**
 * Headless daily-commission (每日委托) manager.
 *
 * <p>Dispatches 4 random commissions per "commission day" (which flips at 04:00 UTC+8), tracks
 * progress/completion, grants rewards, and supports a forced refresh via
 * {@code /refreshdailyquest} (see {@link #resetDailyTasks(boolean)}).
 *
 * <p>Persisted state lives on the {@link Player} (activeDailyTaskIds, dailyTaskProgress,
 * finishedDailyTaskCount, dailyScoreRewardTaken, lastCommissionResetDayKey), so no separate
 * database collection is needed.
 *
 * <p>Client-visible panel (Route A) is intentionally out of scope: the 6.7 protocol snapshot in
 * this repository contains no daily-task messages. All logic here is reusable once the protocol
 * layer is added.
 */
public final class DailyCommissionManager extends BasePlayerManager {

    /** Commission-day boundary: 04:00 UTC+8 (no DST, stable). */
    private static final ZoneId RESET_ZONE = ZoneId.of("Asia/Shanghai");

    private static final int TASK_COUNT = 4;
    /** Commissions are dispatched from one city's pool; 1 = Mondstadt. */
    private static final int DEFAULT_CITY_ID = 1;

    // MVP hardcoded rewards (per single commission).
    private static final int REWARD_PRIMOGEMS = 10; // 201
    private static final int REWARD_MORA = 3000; // 202
    private static final int REWARD_ADVENTURE_EXP = 100; // 102
    private static final int REWARD_COMPANIONSHIP_EXP = 10; // 105

    // All-clear bonus (all 4 finished).
    private static final int BONUS_PRIMOGEMS = 20;
    private static final int BONUS_MORA = 5000;
    private static final int BONUS_ADVENTURE_EXP = 150;
    private static final int BONUS_COMPANIONSHIP_EXP = 20;
    private static final int BONUS_LEGENDARY_KEY = 1; // 107

    private static final String FINISH_MONSTER_NUM = "DAILY_FINISH_MONSTER_NUM";

    public DailyCommissionManager(@NonNull Player player) {
        super(player);
    }

    /** Called on player login: refresh if the 04:00 boundary was crossed, then notify. */
    public void onLogin() {
        this.onTick();
        this.sendInfoNotify();
    }

    /** Called every server tick (via {@code Player.onTick()}): checks the 04:00 boundary. */
    public void onTick() {
        if (this.hasResetPassed()) {
            this.resetDailyTasks(false);
        }
    }

    /**
     * (Re-)dispatches today's 4 commissions.
     *
     * @param force if true, always refresh (used by {@code /refreshdailyquest}, bypassing the
     *     04:00 boundary); if false, only refreshes when the commission day has flipped.
     */
    public synchronized void resetDailyTasks(boolean force) {
        var player = getPlayer();
        if (!force && !this.hasResetPassed()) {
            return;
        }

        // Candidate pool: quest-linked commissions of the default city.
        // Scene-event commissions (finishType != null) are invisible without the daily-task
        // protocol (6.7 proto snapshot lacks those messages), so we dispatch only quest-linked
        // commissions, which show up in the quest panel and complete via NPC talk.
        var candidates = new ArrayList<DailyTaskData>();
        for (var task : GameData.getDailyTaskDataMap().values()) {
            if (task.getCityId() == DEFAULT_CITY_ID && task.getQuestId() > 0) {
                candidates.add(task);
            }
        }
        Collections.shuffle(candidates);
        int take = Math.min(TASK_COUNT, candidates.size());
        var selected = new ArrayList<>(candidates.subList(0, take));
        var ids = selected.stream().map(DailyTaskData::getId).toList();

        player.setActiveDailyTaskIds(new ArrayList<>(ids));
        player.setDailyTaskProgress(new HashMap<>());
        player.setFinishedDailyTaskCount(0);
        player.setDailyScoreRewardTaken(false);
        player.setLastCommissionResetDayKey(currentCommissionDayKey());

        // Quest-linked commissions: accept their quests so they show in the quest panel.
        for (var task : selected) {
            if (task.getQuestId() > 0) {
                try {
                    player.getQuestManager().addQuest(task.getQuestId());
                } catch (Exception e) {
                    Grasscutter.getLogger()
                            .debug("Failed to accept quest {} for daily commission {}", task.getQuestId(), task.getId(), e);
                }
            }
        }

        this.save();
        Grasscutter.getLogger()
                .info("Player {} daily commissions refreshed: {}", player.getUid(), ids);
    }

    /** Current active commission definitions (skips entries missing from the data). */
    public List<DailyTaskData> getActiveTasks() {
        var ids = getPlayer().getActiveDailyTaskIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(GameData.getDailyTaskDataMap()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Adds progress to a commission; completes it once {@code finishProgress} is reached. */
    public void onTaskProgress(int taskId, int delta) {
        synchronized (this) {
            var player = getPlayer();
            var task = GameData.getDailyTaskDataMap().get(taskId);
            if (task == null || !player.getActiveDailyTaskIds().contains(taskId)) {
                return;
            }
            var progress = player.getDailyTaskProgress();
            if (progress == null) {
                progress = new HashMap<>();
                player.setDailyTaskProgress(progress);
            }
            int current = progress.getOrDefault(taskId, 0);
            int target = task.getFinishProgress();
            progress.put(taskId, Math.min(target, current + delta));
            if (progress.get(taskId) >= target && target > 0) {
                this.completeTask(taskId);
            }
        }
    }

    /** Advances kill-count commissions (DAILY_FINISH_MONSTER_NUM) by one kill each. */
    public void onMonsterKilled() {
        for (var task : this.getActiveTasks()) {
            if (FINISH_MONSTER_NUM.equals(task.getFinishType())) {
                this.onTaskProgress(task.getId(), 1);
            }
        }
    }

    /** Called when a quest finishes; completes commissions linked to that quest. */
    public void onQuestFinish(int questId) {
        for (var task : this.getActiveTasks()) {
            if (task.getQuestId() == questId) {
                this.completeTask(task.getId());
            }
        }
    }

    /** Completes a commission: grants its reward and counts it. Idempotent. */
    public void completeTask(int taskId) {
        synchronized (this) {
            var player = getPlayer();
            if (!player.getActiveDailyTaskIds().contains(taskId)) {
                return;
            }
            var task = GameData.getDailyTaskDataMap().get(taskId);
            if (task == null) {
                return;
            }
            var progress = player.getDailyTaskProgress();
            if (progress == null) {
                progress = new HashMap<>();
                player.setDailyTaskProgress(progress);
            }
            // Idempotency: a task already at its target progress counts as completed.
            if (progress.getOrDefault(taskId, 0) >= task.getFinishProgress()
                    && progress.getOrDefault(taskId, 0) > 0) {
                return;
            }

            progress.put(taskId, task.getFinishProgress());
            this.grantTaskReward(task);
            player.setFinishedDailyTaskCount(player.getFinishedDailyTaskCount() + 1);
            this.save();
            Grasscutter.getLogger()
                    .info("Player {} completed daily commission {}", player.getUid(), taskId);

            if (player.getFinishedDailyTaskCount() >= TASK_COUNT) {
                this.claimScoreReward();
            }
        }
    }

    /** Grants the reward of a single commission (MVP hardcoded values). */
    private void grantTaskReward(DailyTaskData task) {
        var inventory = getPlayer().getInventory();
        inventory.addItem(201, REWARD_PRIMOGEMS, ActionReason.DailyTaskScore);
        inventory.addItem(202, REWARD_MORA, ActionReason.DailyTaskScore);
        inventory.addItem(102, REWARD_ADVENTURE_EXP, ActionReason.DailyTaskScore);
        inventory.addItem(105, REWARD_COMPANIONSHIP_EXP, ActionReason.DailyTaskScore);
    }

    /** Grants the all-clear bonus once (idempotent via dailyScoreRewardTaken). */
    public void claimScoreReward() {
        synchronized (this) {
            var player = getPlayer();
            if (player.getFinishedDailyTaskCount() < TASK_COUNT || player.isDailyScoreRewardTaken()) {
                return;
            }
            var inventory = player.getInventory();
            inventory.addItem(201, BONUS_PRIMOGEMS, ActionReason.DailyTaskScore);
            inventory.addItem(202, BONUS_MORA, ActionReason.DailyTaskScore);
            inventory.addItem(102, BONUS_ADVENTURE_EXP, ActionReason.DailyTaskScore);
            inventory.addItem(105, BONUS_COMPANIONSHIP_EXP, ActionReason.DailyTaskScore);
            inventory.addItem(107, BONUS_LEGENDARY_KEY, ActionReason.DailyTaskExchangeLegendaryKey);
            player.setDailyScoreRewardTaken(true);
            this.save();
            Grasscutter.getLogger().info("Player {} claimed the all-clear daily commission reward", player.getUid());
        }
    }

    /** Whether the 04:00 (UTC+8) commission-day boundary has been crossed since last refresh. */
    public boolean hasResetPassed() {
        return currentCommissionDayKey() != getPlayer().getLastCommissionResetDayKey();
    }

    /** Headless notification: logs the active commissions (Route A would push a panel notify). */
    public void sendInfoNotify() {
        var tasks = this.getActiveTasks();
        Grasscutter.getLogger().info("Player {} active daily commissions: {}", getPlayer().getUid(), tasks);
    }

    /**
     * "Commission day key": the UTC+8 date shifted by -4 hours, i.e. the day flips at 04:00 UTC+8.
     * Returns a stable, monotonically increasing int (LocalDate epoch day).
     */
    private static int currentCommissionDayKey() {
        return (int) ZonedDateTime.now(RESET_ZONE).minusHours(4).toLocalDate().toEpochDay();
    }
}
