package emu.grasscutter.game.dailycommission;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.daily.DailyTaskData;
import emu.grasscutter.game.player.BasePlayerManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.server.packet.send.PacketDailyTaskProgressNotify;
import emu.grasscutter.server.packet.send.PacketWorldOwnerDailyTaskNotify;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** Called on player login: refresh if the 04:00 boundary was crossed. */
    public void onLogin() {
        this.onTick();
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

        // Remove previous commissions' quests so they don't accumulate on refresh.
        var oldIds = player.getActiveDailyTaskIds();
        if (oldIds != null) {
            for (var oldId : oldIds) {
                var oldTask = GameData.getDailyTaskDataMap().get(oldId);
                if (oldTask != null && oldTask.getQuestId() > 0) {
                    try {
                        var quest = player.getQuestManager().getQuestById(oldTask.getQuestId());
                        if (quest != null) {
                            var mainQuest = quest.getMainQuest();
                            quest.clearProgress(true); // sends QuestDelNotify to the client
                            player.getQuestManager().getMainQuests().remove(mainQuest.getParentQuestId());
                            mainQuest.delete(); // remove from database
                        }
                    } catch (Exception e) {
                        Grasscutter.getLogger()
                                .debug("Failed to clear old commission quest {}", oldTask.getQuestId(), e);
                    }
                }
            }
        }

        player.setActiveDailyTaskIds(new ArrayList<>(ids));
        player.setDailyTaskProgress(new HashMap<>());
        player.setFinishedDailyTaskIds(new HashSet<>());
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
            int updated = Math.min(target, current + delta);
            progress.put(taskId, updated);
            if (updated >= target && target > 0) {
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
            if (this.finishedIds().contains(taskId)) {
                return; // already completed
            }
            var progress = player.getDailyTaskProgress();
            if (progress == null) {
                progress = new HashMap<>();
                player.setDailyTaskProgress(progress);
            }

            progress.put(taskId, task.getFinishProgress());
            this.finishedIds().add(taskId);
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

    /** Pushes the 4 commissions to the client panel. */
    public void sendInfoNotify() {
        var player = getPlayer();
        if (player.getSession() != null) {
            player.sendPacket(new PacketWorldOwnerDailyTaskNotify(player));
        }
        Grasscutter.getLogger()
                .info("Player {} active daily commissions: {}", player.getUid(), this.getActiveTasks());
    }

    /** Test-mode: push the panel with daily_task_id written at the given wire field number. */
    public void sendInfoNotify(int dailyTaskIdField) {
        var player = getPlayer();
        if (player.getSession() != null) {
            player.sendPacket(new PacketWorldOwnerDailyTaskNotify(player, dailyTaskIdField));
        }
        Grasscutter.getLogger()
                .info("Player {} panel test: daily_task_id at field {}", player.getUid(), dailyTaskIdField);
    }

    /** Current progress (0..finishProgress) of a commission. */
    public int getProgress(int taskId) {
        var progress = getPlayer().getDailyTaskProgress();
        return progress == null ? 0 : progress.getOrDefault(taskId, 0);
    }

    /** Whether a commission has been completed. */
    public boolean isFinished(int taskId) {
        return this.finishedIds().contains(taskId);
    }

    /** Lazily-initialized set of completed commission ids. */
    private Set<Integer> finishedIds() {
        var player = getPlayer();
        if (player.getFinishedDailyTaskIds() == null) {
            player.setFinishedDailyTaskIds(new HashSet<>());
        }
        return player.getFinishedDailyTaskIds();
    }

    /** The city id whose pool commissions are drawn from. */
    public int getFilterCityId() {
        return DEFAULT_CITY_ID;
    }

    /**
     * "Commission day key": the UTC+8 date shifted by -4 hours, i.e. the day flips at 04:00 UTC+8.
     * Returns a stable, monotonically increasing int (LocalDate epoch day).
     */
    private static int currentCommissionDayKey() {
        return (int) ZonedDateTime.now(RESET_ZONE).minusHours(4).toLocalDate().toEpochDay();
    }
}
