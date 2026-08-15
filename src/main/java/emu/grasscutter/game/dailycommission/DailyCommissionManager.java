package emu.grasscutter.game.dailycommission;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.RewardPreviewData;
import emu.grasscutter.data.excels.daily.DailyTaskData;
import emu.grasscutter.data.excels.daily.DailyTaskLevelData;
import emu.grasscutter.data.excels.daily.DailyTaskRewardData;
import emu.grasscutter.game.player.BasePlayerManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.packet.send.PacketDailyTaskProgressNotify;
import emu.grasscutter.server.packet.send.PacketWorldOwnerDailyTaskNotify;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
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

    // Rewards are config-driven (DailyTaskLevelData -> DailyTaskRewardData -> RewardPreviewData),
    // mirroring the official level-band -> drop-vec -> preview-reward chain. No hardcoded values.
    private static final String FINISH_MONSTER_NUM = "DAILY_FINISH_MONSTER_NUM";
    private static final String SUPPORTED_TASK_TYPE = "DAILY_TASK_SCENE";
    private static final int TEYVAT_SCENE_ID = 3;
    private static final double DAILY_GROUP_LOAD_RADIUS = 500.0;
    private static final double DAILY_GROUP_UNLOAD_RADIUS = 1200.0;

    /*
     * Daily tasks deliberately disabled until their required gameplay mechanics are properly
     * supported. 31240 = Perilous Watersport (needs Waverider access).
     */
    private static final Set<Integer> EXCLUDED_DAILY_TASK_IDS = Set.of(31240);

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
    public void resetDailyTasks(boolean force) {
        var player = getPlayer();
        if (!force && !this.hasResetPassed()) {
            return;
        }

        // Candidate pool: scene-event commissions (DAILY_TASK_SCENE + DAILY_FINISH_MONSTER_NUM)
        // with usable Lua group resources. These spawn a monster group in the world and complete
        // by killing it — the official daily-commission flow.
        //
        // Selection only touches static GameData + on-disk Lua resources (hasUsableGroupResources
        // does file IO), so do it BEFORE taking the lock — we must not hold the manager lock while
        // reading files.
        var candidates = new ArrayList<DailyTaskData>();
        for (var task : GameData.getDailyTaskDataMap().values()) {
            if (task.getCityId() == DEFAULT_CITY_ID && isSupportedTask(task)) {
                candidates.add(task);
            }
        }
        Collections.shuffle(candidates);
        int take = Math.min(TASK_COUNT, candidates.size());
        var selected = new ArrayList<>(candidates.subList(0, take));
        var ids = selected.stream().map(DailyTaskData::getId).toList();

        List<Integer> oldIds;
        Set<Integer> previousGroupIds;
        synchronized (this) {
            // Re-check under the lock: another thread may have refreshed while we selected.
            if (!force && !this.hasResetPassed()) {
                return;
            }

            // Capture the previous commissions' group ids (before swapping) for later unload.
            previousGroupIds = this.collectTaskGroupIds(false);
            oldIds = player.getActiveDailyTaskIds();

            // State transition only — cheap, no IO, no scene work.
            player.setActiveDailyTaskIds(new ArrayList<>(ids));
            player.setDailyTaskProgress(new HashMap<>());
            player.setFinishedDailyTaskIds(new HashSet<>());
            player.setDefeatedDailyMonsterKeys(new HashSet<>());
            player.setFinishedDailyTaskCount(0);
            player.setDailyScoreRewardTaken(false);
            player.setLastCommissionResetDayKey(currentCommissionDayKey());
        }

        // --- Heavy work outside the lock (never hold the manager lock during IO/scene/quest) ---

        // Remove previous commissions' quests so they don't accumulate on refresh.
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

        // Swap the scene groups: unload the old commission groups, then load the new ones so
        // the monster groups actually appear in the world.
        var scene = player.getScene();
        if (scene != null) {
            try {
                this.unloadGroups(scene, previousGroupIds);
                // Do not force-load all four commission groups at once: loading groups
                // thousands of metres away right before a scene transition produces a
                // huge entity appear/disappear burst and has been implicated in client
                // (1,1,2) crashes. Only groups within DAILY_GROUP_LOAD_RADIUS are loaded
                // now; the rest are streamed in later from Player.onTick() via
                // updateActiveGroups() as the player approaches them.
                this.updateActiveGroups(scene);
            } catch (Exception e) {
                // Scene-group streaming is best-effort: never block login/refresh on it.
                Grasscutter.getLogger()
                        .warn("[DailyCommission] Failed to (re)load commission groups for uid {}: {}",
                                player.getUid(), e.toString());
            }
        }

        this.save();
        this.sendInfoNotify();
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
        boolean completed = false;
        DailyTaskData progressedTask = null;
        int updatedProgress = 0;
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
            completed = updated >= target && target > 0;
            progressedTask = task;
            updatedProgress = updated;
        }

        // Tell the client panel to update this commission's progress immediately.
        var player = getPlayer();
        if (progressedTask != null && player.getSession() != null) {
            player.sendPacket(
                    new PacketDailyTaskProgressNotify(
                            progressedTask,
                            this.getRewardId(progressedTask.getTaskRewardId()),
                            updatedProgress,
                            completed));
        }

        // Complete outside the lock: completeTask does inventory + save + scene-group unload.
        if (completed) {
            this.completeTask(taskId);
        }
    }

    /**
     * Advances kill-count commissions (DAILY_FINISH_MONSTER_NUM) by one kill each.
     *
     * <p>A monster is identified by its {@code (groupId, configId)} pair; each can count only
     * once per commission day, so a re-created monster (teleport away/back, relog, restart)
     * cannot be farmed twice.
     */
    public void onMonsterKilled(int groupId, int configId) {
        if (groupId <= 0 || configId <= 0) {
            Grasscutter.getLogger()
                    .warn("[DailyCommission] ignored kill with groupId={} configId={}", groupId, configId);
            return;
        }
        var player = getPlayer();
        long key = ((long) groupId << 32) | (configId & 0xffffffffL);

        // Snapshot the matching commissions under the lock so the defeated-key dedup is atomic.
        List<DailyTaskData> matchedTasks;
        synchronized (this) {
            var defeated = player.getDefeatedDailyMonsterKeys();
            if (defeated == null) {
                defeated = new HashSet<>();
                player.setDefeatedDailyMonsterKeys(defeated);
            }
            if (!defeated.add(key)) {
                Grasscutter.getLogger()
                        .debug("[DailyCommission] duplicate kill groupId={} configId={}", groupId, configId);
                return; // already defeated today
            }
            matchedTasks = new ArrayList<>();
            for (var task : this.getActiveTasks()) {
                if (!FINISH_MONSTER_NUM.equals(task.getFinishType())) {
                    continue;
                }
                // Only advance commissions whose scene group actually contains the defeated monster.
                if (task.getNewGroupVec() == null || !task.getNewGroupVec().contains(groupId)) {
                    continue;
                }
                matchedTasks.add(task);
            }
        }

        for (var task : matchedTasks) {
            this.onTaskProgress(task.getId(), 1);
            Grasscutter.getLogger()
                    .info("[DailyCommission] uid {} kill groupId={} configId={} -> task {} progress {}/{}",
                            player.getUid(), groupId, configId, task.getId(),
                            this.getProgress(task.getId()), task.getFinishProgress());
        }
        if (matchedTasks.isEmpty()) {
            Grasscutter.getLogger()
                    .info("[DailyCommission] uid {} kill groupId={} configId={} matched no active task",
                            player.getUid(), groupId, configId);
        }
    }

    /** Called when a quest finishes; completes commissions linked to that quest. */
    public void onQuestFinish(int questId) {
        List<Integer> matchedIds;
        synchronized (this) {
            matchedIds = this.getActiveTasks().stream()
                    .filter(task -> task.getQuestId() == questId)
                    .map(DailyTaskData::getId)
                    .toList();
        }
        for (var taskId : matchedIds) {
            this.completeTask(taskId);
        }
    }

    /** Completes a commission: grants its reward and counts it. Idempotent. */
    public void completeTask(int taskId) {
        DailyTaskData task;
        boolean grantReward;
        boolean claimScore;
        synchronized (this) {
            var player = getPlayer();
            if (!player.getActiveDailyTaskIds().contains(taskId)) {
                return;
            }
            task = GameData.getDailyTaskDataMap().get(taskId);
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

            // State transition only — the heavy work happens below, outside the lock.
            progress.put(taskId, task.getFinishProgress());
            this.finishedIds().add(taskId);
            player.setFinishedDailyTaskCount(player.getFinishedDailyTaskCount() + 1);
            grantReward = true;
            claimScore = player.getFinishedDailyTaskCount() >= TASK_COUNT;
            Grasscutter.getLogger()
                    .info("Player {} completed daily commission {}", player.getUid(), taskId);
        }

        // --- Heavy work outside the lock ---
        // Never hold the manager lock during inventory grants, persistence, or scene-group
        // streaming: those take scene/DB locks and would invert lock order with the scene thread.
        if (grantReward) {
            this.grantTaskReward(task);
            this.save();

            // Unload the completed commission's scene group so its monsters despawn.
            if (getPlayer().getScene() != null) {
                this.unloadTaskGroups(getPlayer().getScene(), task);
            }

            if (claimScore) {
                this.claimScoreReward();
            }

            // Refresh the client panel: progress for this task is already covered by
            // PacketDailyTaskProgressNotify, but finished_daily_task_num only comes from
            // WorldOwnerDailyTaskNotify.
            this.sendInfoNotify();
        }
    }

    /** Grants the reward of a single commission from the level-band reward table. */
    private void grantTaskReward(DailyTaskData task) {
        this.grantPreviewReward(this.getRewardId(task.getTaskRewardId()), ActionReason.DailyTaskHost);
    }

    /** Grants the all-clear bonus once (idempotent via dailyScoreRewardTaken). */
    public void claimScoreReward() {
        var player = getPlayer();
        int rewardId;
        boolean claim;
        synchronized (this) {
            if (player.getFinishedDailyTaskCount() < TASK_COUNT || player.isDailyScoreRewardTaken()) {
                return;
            }
            rewardId = this.getScoreRewardId();
            claim = rewardId > 0;
            if (claim) {
                // Mark intent under the lock to stay idempotent; grant happens outside.
                player.setDailyScoreRewardTaken(true);
            }
        }

        if (!claim) {
            return;
        }
        // Inventory grant + persistence are heavy — do them outside the lock.
        if (this.grantPreviewReward(rewardId, ActionReason.DailyTaskScore)) {
            this.save();
            Grasscutter.getLogger()
                    .info("Player {} claimed the all-clear daily commission reward", player.getUid());
        } else {
            // Roll back the intent mark so a later attempt can retry.
            synchronized (this) {
                player.setDailyScoreRewardTaken(false);
            }
        }
    }

    /** Resolves the preview-reward id for a task reward id at the player's current level band. */
    public int getRewardId(int taskRewardId) {
        var levelData = this.getCurrentLevelData();
        if (levelData == null) {
            return 0;
        }
        DailyTaskRewardData rewardData = GameData.getDailyTaskRewardDataMap().get(taskRewardId);
        if (rewardData == null || rewardData.getDropVec() == null) {
            return 0;
        }
        // DailyTaskLevel ids are 1..N and each reward's dropVec lists one entry per band, in order.
        int rewardIndex = levelData.getId() - 1;
        if (rewardIndex < 0 || rewardIndex >= rewardData.getDropVec().size()) {
            return 0;
        }
        return rewardData.getDropVec().get(rewardIndex).getPreviewRewardId();
    }

    /** Resolves the all-clear (4/4) bonus preview-reward id at the player's current level band. */
    public int getScoreRewardId() {
        var levelData = this.getCurrentLevelData();
        return levelData == null ? 0 : levelData.getScorePreviewRewardId();
    }

    /** The daily-commission level band covering the player's current level, or null. */
    private DailyTaskLevelData getCurrentLevelData() {
        var player = getPlayer();
        if (player == null) {
            return null;
        }
        return GameData.getDailyTaskLevelDataByPlayerLevel(player.getLevel());
    }

    /** Grants the items of a preview reward by id. Returns false when the reward is unavailable. */
    private boolean grantPreviewReward(int rewardPreviewId, ActionReason reason) {
        var player = getPlayer();
        if (player == null || rewardPreviewId <= 0) {
            return false;
        }
        RewardPreviewData reward = GameData.getRewardPreviewDataMap().get(rewardPreviewId);
        if (reward == null) {
            Grasscutter.getLogger().warn("RewardPreview {} was not found.", rewardPreviewId);
            return false;
        }
        ItemParamData[] items = reward.getPreviewItems();
        if (items == null || items.length == 0) {
            return false;
        }
        player.getInventory().addItemParamDatas(Arrays.asList(items), reason);
        return true;
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

    // --- scene-event group support (mirrors Rafs-kk/LunaGC-6.6 DailyTaskManager) ---

    /** True when the task is a scene-event monster-count commission with usable Lua resources. */
    private static boolean isSupportedTask(DailyTaskData data) {
        if (data == null
                || !SUPPORTED_TASK_TYPE.equals(data.getType())
                || !FINISH_MONSTER_NUM.equals(data.getFinishType())
                || data.getFinishProgress() <= 0
                || data.getTaskRewardId() <= 0
                || data.getNewGroupVec() == null
                || data.getNewGroupVec().isEmpty()) {
            return false;
        }
        if (EXCLUDED_DAILY_TASK_IDS.contains(data.getId())) {
            return false;
        }
        return data.getNewGroupVec().stream().allMatch(DailyCommissionManager::hasUsableGroupResources);
    }

    /** Verifies a group's block/group Lua scripts exist and the block declares the group. */
    private static boolean hasUsableGroupResources(int groupId) {
        if (groupId <= 0) {
            return false;
        }
        int blockId = getBlockIdFromGroupId(groupId);
        var sceneMeta = ScriptLoader.getSceneMeta(TEYVAT_SCENE_ID);
        if (sceneMeta == null || sceneMeta.blocks == null || !sceneMeta.blocks.containsKey(blockId)) {
            return false;
        }
        String blockScript = "Scene/%d/scene%d_block%d.lua".formatted(TEYVAT_SCENE_ID, TEYVAT_SCENE_ID, blockId);
        String groupScript = "Scene/%d/scene%d_group%d.lua".formatted(TEYVAT_SCENE_ID, TEYVAT_SCENE_ID, groupId);
        if (!Files.isRegularFile(FileUtils.getScriptPath(blockScript))
                || !Files.isRegularFile(FileUtils.getScriptPath(groupScript))) {
            return false;
        }
        try {
            String blockContents = Files.readString(FileUtils.getScriptPath(blockScript));
            return Pattern.compile("\\bid\\s*=\\s*" + groupId + "\\b").matcher(blockContents).find();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Group IDs encode their Scene 3 block in the middle digits
     * (e.g. 133002267 -> block 3002, 133314321 -> block 3314).
     */
    private static int getBlockIdFromGroupId(int groupId) {
        return (groupId / 1000) % 10000;
    }

    /** Collects the group ids of all (or only unfinished) active commissions. */
    private Set<Integer> collectTaskGroupIds(boolean unfinishedOnly) {
        Set<Integer> groupIds = new LinkedHashSet<>();
        for (var task : this.getActiveTasks()) {
            if (unfinishedOnly && this.isFinished(task.getId())) {
                continue;
            }
            var data = GameData.getDailyTaskDataMap().get(task.getId());
            if (!isSupportedTask(data) || data.getNewGroupVec() == null) {
                continue;
            }
            groupIds.addAll(data.getNewGroupVec());
        }
        return groupIds;
    }

    /** The group ids of all unfinished active commissions. */
    public Set<Integer> getActiveGroupIds() {
        synchronized (this) {
            return Set.copyOf(this.collectTaskGroupIds(true));
        }
    }

    /** Loads every unfinished commission's scene group into the current scene. */
    public int loadActiveGroups(Scene scene) {
        if (scene == null
                || scene.getId() != TEYVAT_SCENE_ID
                || scene.getScriptManager() == null
                || !scene.getScriptManager().isInit()) {
            return 0;
        }
        // Snapshot group ids under the lock; scene.loadDynamicGroup must run outside it.
        List<Integer> groupIds;
        synchronized (this) {
            groupIds = new ArrayList<>(this.collectTaskGroupIds(true));
        }
        int ready = 0;
        for (int groupId : groupIds) {
            if (this.isGroupLoaded(scene, groupId)) {
                ready++;
                continue;
            }
            int suiteId = scene.loadDynamicGroup(groupId);
            if (suiteId > 0) {
                ready++;
                Grasscutter.getLogger()
                        .info("[DailyCommission] Activated group {} with suite {} for uid {}.", groupId, suiteId, getPlayer().getUid());
            } else {
                Grasscutter.getLogger()
                        .warn("[DailyCommission] Failed to activate group {} for uid {}.", groupId, getPlayer().getUid());
            }
        }
        return ready;
    }

    /** Streams commission groups by player distance: load nearby, unload distant. */
    public void updateActiveGroups(Scene scene) {
        if (scene == null
                || scene.getId() != TEYVAT_SCENE_ID
                || scene.getScriptManager() == null
                || !scene.getScriptManager().isInit()) {
            return;
        }
        // Snapshot group ids under the lock; scene load/unregister must run outside it.
        List<Integer> groupIds;
        synchronized (this) {
            groupIds = new ArrayList<>(this.collectTaskGroupIds(true));
        }
        for (int groupId : groupIds) {
            var group = scene.getScriptManager().getGroupById(groupId);
            if (group == null || group.pos == null) {
                continue;
            }
            boolean loaded = this.isGroupLoaded(scene, groupId);
            if (!loaded) {
                if (!this.isAnyPlayerWithinHorizontalDistance(scene, group.pos, DAILY_GROUP_LOAD_RADIUS)) {
                    continue;
                }
                scene.loadDynamicGroup(groupId);
            } else if (!this.isAnyPlayerWithinHorizontalDistance(scene, group.pos, DAILY_GROUP_UNLOAD_RADIUS)) {
                scene.unregisterDynamicGroup(groupId);
            }
        }
    }

    /** Unloads the given groups from the scene (no fake monster deaths). */
    private void unloadGroups(Scene scene, Collection<Integer> groupIds) {
        if (scene == null || groupIds == null || groupIds.isEmpty()) {
            return;
        }
        for (int groupId : groupIds) {
            if (this.isGroupLoaded(scene, groupId)) {
                scene.unregisterDynamicGroup(groupId);
            }
        }
    }

    /** Unloads a single finished commission's groups. */
    private void unloadTaskGroups(Scene scene, DailyTaskData data) {
        if (scene == null || data == null || data.getNewGroupVec() == null) {
            return;
        }
        this.unloadGroups(scene, data.getNewGroupVec());
    }

    private boolean isGroupLoaded(Scene scene, int groupId) {
        return scene != null
                && scene.getLoadedGroups().stream().anyMatch(group -> group.id == groupId);
    }

    private boolean isAnyPlayerWithinHorizontalDistance(Scene scene, Position position, double radius) {
        if (scene == null || position == null) {
            return false;
        }
        double radiusSquared = radius * radius;
        for (var scenePlayer : scene.getPlayers()) {
            if (scenePlayer == null || scenePlayer.getPosition() == null) {
                continue;
            }
            double dx = scenePlayer.getPosition().getX() - position.getX();
            double dz = scenePlayer.getPosition().getZ() - position.getZ();
            if ((dx * dx) + (dz * dz) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Commission day key": the UTC+8 date shifted by -4 hours, i.e. the day flips at 04:00 UTC+8.
     * Returns a stable, monotonically increasing int (LocalDate epoch day).
     */
    private static int currentCommissionDayKey() {
        return (int) ZonedDateTime.now(RESET_ZONE).minusHours(4).toLocalDate().toEpochDay();
    }
}
