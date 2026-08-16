package emu.grasscutter.game.world;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.GameDepot;
import emu.grasscutter.data.binout.SceneNpcBornEntry;
import emu.grasscutter.data.binout.routes.Route;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.codex.CodexAnimalData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.data.excels.world.WorldLevelData;
import emu.grasscutter.data.server.Grid;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.dungeons.DungeonSettleListener;
import emu.grasscutter.game.dungeons.challenge.WorldChallenge;
import emu.grasscutter.game.dungeons.enums.DungeonPassConditionType;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.managers.blossom.BlossomManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamInfo;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.QuestGroupSuite;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.net.proto.ChangeHpDebtsReasonOuterClass;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.SceneIndexManager;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.SceneBlock;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.event.entity.EntityCreationEvent;
import emu.grasscutter.server.event.player.PlayerTeleportEvent;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;
import emu.grasscutter.utils.algorithms.KahnsSort;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.*;

import static emu.grasscutter.GameConstants.ENTITY_ID_BIT_SHIFT;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

public class Scene {
    @Getter private final World world;
    @Getter private final SceneData sceneData;
    @Getter private final List<Player> players;
    @Getter private final Map<Integer, GameEntity> entities;
    @Getter private final Map<Integer, GameEntity> weaponEntities;
    @Getter private final Set<SpawnDataEntry> spawnedEntities;
    @Getter private final Set<SpawnDataEntry> deadSpawnedEntities;
    @Getter private final Set<SceneBlock> loadedBlocks;
    @Getter private final Set<SceneGroup> loadedGroups;
    /** Group ids that have been explicitly registered on the client via GroupSuiteNotify. */
    @Getter private final Set<Integer> clientKnownGroups;
    /** When each group was loaded (epoch ms), used to avoid unloading freshly-loaded groups too early. */
    private final Map<Integer, Long> groupLoadTimes;
    /** Grace period after a group is loaded before it may be unloaded again. */
    private static final long GROUP_UNLOAD_GRACE_PERIOD_MS = 5000;
    /** Maximum distance (metres) from a player for loading script groups. */
    private static final int MAX_ENTITY_LOAD_RANGE = 500;
    /**
     * Deduplication keys for world entities. Official group config ids are unique inside
     * a group; duplicate (group, config, class) entities corrupt the client's scene-node
     * arrays and are the actual trigger behind "node cnt out of index".
     */
    private final Set<String> entityIdentityKeys;
    @Getter private final BlossomManager blossomManager;
    private final HashSet<Integer> unlockedForces;
    private final long startWorldTime;
    @Getter @Setter DungeonManager dungeonManager;
    @Getter Int2ObjectMap<Route> sceneRoutes;
    private Set<SpawnDataEntry.GridBlockId> loadedGridBlocks;
    @Getter @Setter private boolean dontDestroyWhenEmpty;
    @Getter private final SceneScriptManager scriptManager;
    @Getter @Setter private WorldChallenge challenge;
    @Getter private List<DungeonSettleListener> dungeonSettleListeners;
    @Getter @Setter private int prevScene;
    @Getter @Setter private int prevScenePoint;
    @Getter @Setter private int killedMonsterCount;
    private Set<SceneNpcBornEntry> npcBornEntrySet;
    @Getter private boolean finishedLoading = false;
    @Getter protected int tickCount = 0;
    @Getter private boolean isPaused = false;

    private final List<Runnable> afterLoadedCallbacks = new ArrayList<>();
    private final List<Runnable> afterHostInitCallbacks = new ArrayList<>();

    @Getter private GameEntity sceneEntity;
    @Getter private final ServerTaskScheduler scheduler;

    public Scene(World world, SceneData sceneData) {
        this.world = world;
        this.sceneData = sceneData;
        this.players = new CopyOnWriteArrayList<>();
        this.entities = new ConcurrentHashMap<>();
        this.weaponEntities = new ConcurrentHashMap<>();

        this.prevScene = 3;
        this.sceneRoutes = GameData.getSceneRoutes(getId());

        this.startWorldTime = world.getWorldTime();

        this.spawnedEntities = ConcurrentHashMap.newKeySet();
        this.deadSpawnedEntities = ConcurrentHashMap.newKeySet();
        this.loadedBlocks = ConcurrentHashMap.newKeySet();
        this.loadedGroups = ConcurrentHashMap.newKeySet();
        this.clientKnownGroups = ConcurrentHashMap.newKeySet();
        this.groupLoadTimes = new ConcurrentHashMap<>();
        this.entityIdentityKeys = ConcurrentHashMap.newKeySet();
        this.loadedGridBlocks = new HashSet<>();
        this.npcBornEntrySet = ConcurrentHashMap.newKeySet();
        this.scriptManager = new SceneScriptManager(this);
        this.blossomManager = new BlossomManager(this);
        this.unlockedForces = new HashSet<>();
        this.sceneEntity = new EntityScene(this);
        this.scheduler = new ServerTaskScheduler();
    }

    public int getId() {
        return sceneData.getId();
    }

    public SceneType getSceneType() {
        return getSceneData().getSceneType();
    }

    public int getPlayerCount() {
        return this.getPlayers().size();
    }

    public Player getHost() {
        return this.getWorld().getHost();
    }

    public GameEntity getEntityById(int id) {

        if (id == 0x13800001) return this.sceneEntity;
        else if (id == this.getWorld().getLevelEntityId()) return this.getWorld().getEntity();

        var teamEntityPlayer =
                players.stream().filter(p -> p.getTeamManager().getEntity().getId() == id).findAny();
        if (teamEntityPlayer.isPresent()) return teamEntityPlayer.get().getTeamManager().getEntity();

        var entity = this.entities.get(id);
        if (entity == null) entity = this.weaponEntities.get(id);
        if (entity == null && (id >> ENTITY_ID_BIT_SHIFT) == EntityIdType.AVATAR.getId()) {
            for (var player : getPlayers()) {
                for (var avatar : player.getTeamManager().getActiveTeam()) {
                    if (avatar.getId() == id) return avatar;
                }
            }
        }

        if (entity == null && (id >> ENTITY_ID_BIT_SHIFT) == EntityIdType.WEAPON.getId()) {
            for (var player : this.getPlayers()) {
                for (var avatar : player.getTeamManager().getActiveTeam()) {
                    if (avatar.getWeaponEntityId() == id) return avatar;
                }
            }
        }

        return entity;
    }

    public GameEntity getFirstEntityByConfigId(int configId) {
        return this.entities.values().stream()
                .filter(x -> x.getConfigId() == configId)
                .findFirst()
                .orElse(null);
    }

    public GameEntity getEntityByConfigId(int configId, int groupId) {
        return this.entities.values().stream()
                .filter(x -> x.getConfigId() == configId && x.getGroupId() == groupId)
                .findFirst()
                .orElse(null);
    }

    @Nullable public Route getSceneRouteById(int routeId) {
        return sceneRoutes.get(routeId);
    }

    public void setPaused(boolean paused) {
        if (this.isPaused != paused) {
            this.isPaused = paused;
            this.broadcastPacket(new PacketSceneTimeNotify(this));
        }
    }

    public int getSceneTime() {
        return (int) (this.getWorld().getWorldTime() - this.startWorldTime);
    }

    public int getSceneTimeSeconds() {
        return this.getSceneTime() / 1000;
    }

    public void addDungeonSettleObserver(DungeonSettleListener dungeonSettleListener) {
        if (dungeonSettleListeners == null) {
            dungeonSettleListeners = new ArrayList<>();
        }

        dungeonSettleListeners.add(dungeonSettleListener);
    }

    public void triggerDungeonEvent(DungeonPassConditionType conditionType, int... params) {
        if (this.dungeonManager == null) return;
        this.dungeonManager.triggerEvent(conditionType, params);
    }

    public boolean isInScene(GameEntity entity) {
        return this.entities.containsKey(entity.getId());
    }

    public synchronized void addPlayer(Player player) {

        if (getPlayers().contains(player)) {
            return;
        }

        if (player.getScene() != null) {
            player.getScene().removePlayer(player);
        }

        getPlayers().add(player);
        player.setSceneId(this.getId());
        player.setScene(this);

        this.setupPlayerAvatars(player);
    }

    public synchronized void removePlayer(Player player) {

        if (this.getChallenge() != null && this.getChallenge().inProgress()) {
            player.sendPacket(new PacketDungeonChallengeFinishNotify(this.getChallenge()));
        }

        getPlayers().remove(player);
        player.setScene(null);

        this.removePlayerAvatars(player);

        for (EntityBaseGadget gadget : player.getTeamManager().getGadgets()) {
            this.removeEntity(gadget);
        }

        this.getEntities().values().stream()
                .filter(gameEntity -> gameEntity instanceof EntityVehicle)
                .map(gameEntity -> (EntityVehicle) gameEntity)
                .filter(entityVehicle -> entityVehicle.getOwner().equals(player))
                .forEach(entityVehicle -> this.removeEntity(entityVehicle, VisionType.VisionType_VISION_REMOVE));

        if (this.getPlayerCount() <= 0 && !this.dontDestroyWhenEmpty) {
            this.getScriptManager().onDestroy();
            this.getWorld().deregisterScene(this);
        }

        this.saveGroups();
    }

    private void setupPlayerAvatars(Player player) {

        player.getTeamManager().getActiveTeam().clear();

        TeamInfo teamInfo = player.getTeamManager().getCurrentTeamInfo();
        for (int avatarId : teamInfo.getAvatars()) {
            Avatar avatar = player.getAvatars().getAvatarById(avatarId);
            if (avatar == null) {
                if (player.getTeamManager().isUsingTrialTeam()) {
                    avatar = player.getTeamManager().getTrialAvatars().get(avatarId);
                }
                if (avatar == null) continue;
            }
            player
                    .getTeamManager()
                    .getActiveTeam()
                    .add(
                            EntityCreationEvent.call(
                                    EntityAvatar.class,
                                    new Class<?>[] {Scene.class, Avatar.class},
                                    new Object[] {player.getScene(), avatar}));
        }

        if (player.getTeamManager().getCurrentCharacterIndex()
                        >= player.getTeamManager().getActiveTeam().size()
                || player.getTeamManager().getCurrentCharacterIndex() < 0) {
            player
                    .getTeamManager()
                    .setCurrentCharacterIndex(player.getTeamManager().getCurrentCharacterIndex() - 1);
        }
    }

    private synchronized void removePlayerAvatars(Player player) {
        var team = player.getTeamManager().getActiveTeam();

        team.forEach(e -> removeEntity(e, VisionType.VisionType_VISION_REMOVE));
        team.clear();
    }

    public void spawnPlayer(Player player) {
        var teamManager = player.getTeamManager();
        if (this.isInScene(teamManager.getCurrentAvatarEntity())) {
            return;
        }

        if (teamManager.getCurrentAvatarEntity().getFightProperty(FightProperty.FIGHT_PROP_CUR_HP)
                <= 0f) {
            teamManager.getCurrentAvatarEntity().setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 1f);
        }

        this.addEntity(teamManager.getCurrentAvatarEntity());

        teamManager.getActiveTeam().stream()
                .map(EntityAvatar::getAvatar)
                .forEach(Avatar::sendSkillExtraChargeMap);
    }

    private synchronized boolean addEntityDirectly(GameEntity entity) {
        if (entity == null) return false;

        // In prevention mode, reject duplicate world entities for the same
        // (class, group, config). Repeated group refreshes have been observed
        // creating 4-6 copies of the same gadget/monster; those duplicate nodes
        // are what ultimately overflow the client's scene-node array.
        String identityKey = entityIdentityKey(entity);
        if (identityKey != null && !this.entityIdentityKeys.add(identityKey)) {
            Grasscutter.getLogger()
                    .warn(
                            "[Scene] duplicate entity skipped id={} class={} group={} config={}",
                            entity.getId(),
                            entity.getClass().getSimpleName(),
                            entity.getGroupId(),
                            entity.getConfigId());
            return false;
        }

        if (!canAddEntity(entity)) {
            if (identityKey != null) {
                this.entityIdentityKeys.remove(identityKey);
            }
            Grasscutter.getLogger()
                    .debug(
                            "Scene {} entity limit reached, skipped {} (id={})",
                            getId(),
                            entity.getClass().getSimpleName(),
                            entity.getId());
            return false;
        }

        Grasscutter.getLogger()
                .info(
                        "[Scene] addEntityDirectly id={} class={} group={} config={}",
                        entity.getId(),
                        entity.getClass().getSimpleName(),
                        entity.getGroupId(),
                        entity.getConfigId());
        getEntities().put(entity.getId(), entity);
        entity.onCreate();
        return true;
    }

    private String entityIdentityKey(GameEntity entity) {
        if (!GAME_OPTIONS.isPreventEntityError) return null;
        if (entity.getGroupId() == 0 && entity.getConfigId() == 0) return null;
        if (!(entity instanceof EntityMonster
                || entity instanceof EntityGadget
                || entity instanceof EntityNPC)) return null;
        return entity.getClass().getSimpleName() + ":" + entity.getGroupId() + ":" + entity.getConfigId();
    }

    /**
     * Hard scene-entity cap. Non-essential world entities are not added once the scene
     * reaches the configured limit; this prevents unbounded entity growth and the client
     * (1,1,2) crashes caused by excessively large scene syncs.
     */
    private boolean canAddEntity(GameEntity entity) {
        // Upstream-compatible mode: no entity cap at all.
        if (!GAME_OPTIONS.isPreventEntityError) return true;

        if (getEntities().size() < GAME_OPTIONS.sceneEntityLimit) return true;

        // Always allow core player/scene entities so gameplay doesn't break at the cap.
        return entity instanceof EntityAvatar
                || entity instanceof EntityTeam
                || entity instanceof EntityScene
                || entity instanceof EntityWeapon
                || entity instanceof EntityClientGadget;
    }

    public synchronized void addEntity(GameEntity entity) {
        if (this.addEntityDirectly(entity)) {
            this.broadcastPacket(new PacketSceneEntityAppearNotify(entity));
        }
    }

    public synchronized void addEntityToSingleClient(Player player, GameEntity entity) {
        if (this.addEntityDirectly(entity)) {
            player.sendPacket(new PacketSceneEntityAppearNotify(entity));
        }
    }

    public void addDropEntity(GameItem item, GameEntity bornForm, Player player, boolean share) {

        ItemData itemData = GameData.getItemDataMap().get(item.getItemId());
        if (itemData == null) return;
        if (itemData.isEquip()) {
            float range = (1.5f + (.05f * item.getCount()));
            for (int j = 0; j < item.getCount(); j++) {
                Position pos = bornForm.getPosition().nearby2d(range).addY(0.5f);
                EntityItem entity = new EntityItem(this, player, itemData, pos, item.getCount(), share);
                addEntity(entity);
            }
        } else {
            EntityItem entity =
                    new EntityItem(
                            this,
                            player,
                            itemData,
                            bornForm.getPosition().clone().addY(0.5f),
                            item.getCount(),
                            share);
            addEntity(entity);
        }
    }

    public void addEntities(Collection<? extends GameEntity> entities) {
        addEntities(entities, VisionType.VisionType_VISION_BORN);
    }

    public void updateEntity(GameEntity entity) {
        this.broadcastPacket(new PacketSceneEntityUpdateNotify(entity));
    }

    public void updateEntity(GameEntity entity, VisionType type) {
        this.broadcastPacket(new PacketSceneEntityUpdateNotify(Arrays.asList(entity), type));
    }

    private static <T> List<List<T>> chopped(List<T> list, final int L) {
        List<List<T>> parts = new ArrayList<List<T>>();
        final int N = list.size();
        for (int i = 0; i < N; i += L) {
            parts.add(new ArrayList<T>(list.subList(i, Math.min(N, i + L))));
        }
        return parts;
    }

    public synchronized void addEntities(
            Collection<? extends GameEntity> entities, VisionType visionType) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        List<GameEntity> added = new ArrayList<>();
        for (var entity : entities) {
            if (this.addEntityDirectly(entity)) {
                added.add(entity);
            }
        }

        for (var l : chopped(added, 100)) {
            this.broadcastPacket(new PacketSceneEntityAppearNotify(l, visionType));
        }
    }

    private GameEntity removeEntityDirectly(GameEntity entity) {
        var removed = getEntities().remove(entity.getId());
        if (removed != null) {
            String identityKey = entityIdentityKey(removed);
            if (identityKey != null) {
                this.entityIdentityKeys.remove(identityKey);
            }
            removed.onRemoved();
        }
        return removed;
    }

    public void removeEntity(GameEntity entity) {
        this.removeEntity(entity, VisionType.VisionType_VISION_DIE);
    }

    public synchronized void removeEntity(GameEntity entity, VisionType visionType) {
        GameEntity removed = this.removeEntityDirectly(entity);
        if (removed != null) {
            this.broadcastPacket(new PacketSceneEntityDisappearNotify(removed, visionType));
        }
    }

    public void removeEntities(List<GameEntity> entity, VisionType visionType) {
        var toRemove =
                entity.stream()
                        .filter(Objects::nonNull)
                        .map(this::removeEntityDirectly)
                        .filter(Objects::nonNull)
                        .toList();
        for (var l : chopped(new ArrayList<>(toRemove), 100)) {
            this.broadcastPacket(new PacketSceneEntityDisappearNotify(l, visionType));
        }
    }

    public synchronized void replaceEntity(EntityAvatar oldEntity, EntityAvatar newEntity) {
        this.removeEntityDirectly(oldEntity);
        if (this.addEntityDirectly(newEntity)) {
            this.broadcastPacket(
                    new PacketSceneEntityDisappearNotify(oldEntity, VisionType.VisionType_VISION_REPLACE));
            this.broadcastPacket(
                    new PacketSceneEntityAppearNotify(
                            newEntity, VisionType.VisionType_VISION_REPLACE, oldEntity.getId()));
        }
    }

    public void showOtherEntities(Player player) {
        GameEntity currentEntity = player.getTeamManager().getCurrentAvatarEntity();
        List<GameEntity> entities =
                this.getEntities().values().stream()
                        .filter(entity -> entity != currentEntity)
                        .filter(
                                gameEntity ->
                                        !(gameEntity instanceof Rebornable rebornable) || !rebornable.isInCD())
                        .toList();

        Grasscutter.getLogger()
                .info("[Scene] showOtherEntities player={} totalVisible={}", player.getUid(), entities.size());

        // Upstream-compatible mode: send one VISION_MEET packet, exactly like girluh/LunaGC.
        if (!GAME_OPTIONS.isPreventEntityError) {
            player.sendPacket(
                    new PacketSceneEntityAppearNotify(entities, VisionType.VisionType_VISION_MEET));
            return;
        }

        // The 6.7 client crashes (1,1,2 / ArgumentOutOfRangeException: index) when one
        // VISION_MEET notify contains too many entities at once (e.g. Wolvendom has 1100+).
        // Chunk the notify the same way addEntities does (100 entities per packet).
        final int chunkSize = 100;
        for (int i = 0; i < entities.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, entities.size());
            player.sendPacket(
                    new PacketSceneEntityAppearNotify(
                            entities.subList(i, end), VisionType.VisionType_VISION_MEET));
        }
    }

    public void handleAttack(AttackResult result) {
        GameEntity target = getEntityById(result.getDefenseId());
        ElementType attackType = ElementType.getTypeByValue(result.getElementType());

        if (target == null) {
            Grasscutter.getLogger().info("handleAttack: target not found defenseId={} attackerId={} damage={}", result.getDefenseId(), result.getAttackerId(), result.getDamage());
            Grasscutter.getLogger().info("handleAttack unknownFields (defense_id = the monster entityId): {}", result.getUnknownFields().toString().replaceAll("\\s+", " "));
            return;
        }
        if (target instanceof EntityAvatar) {
            if (((EntityAvatar) target).getPlayer().isInGodMode()) {
                return;
            }
        }

        GameEntity attacker = getEntityById(result.getAttackerId());
        if (attacker instanceof EntityClientGadget && target instanceof EntityAvatar) {
            if (target.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS) > 0f) return;
            float curHp = target.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            float capped = Math.min(result.getDamage(), curHp - 1f);
            if (capped > 0) target.damage(capped, result.getAttackerId(), attackType);
            return;
        }

        if (target instanceof EntityAvatar avatar) {
            if (avatar.getPlayer()
            .getAbilityManager()
            .isAbilityInvulnerable()) {
                return;
            }
        }

        target.damage(result.getDamage(), result.getAttackerId(), attackType);

        if (attacker instanceof EntityAvatar arlecAttacker
                && arlecAttacker.getAvatar().getAvatarId() == 10000096
                && !(target instanceof EntityAvatar)) {
            reduceArlecchinoBoL(arlecAttacker);
        }

        if (attacker instanceof EntityAvatar clorindeAttacker
                && clorindeAttacker.getAvatar().getAvatarId() == 10000098
                && !(target instanceof EntityAvatar)) {
            reduceClorindeBoL(clorindeAttacker);
        }
    }

    private void reduceClorindeBoL(EntityAvatar clorinde) {
        float curDebt = clorinde.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
        if (curDebt <= 0f) return;
        float reduction = curDebt * 0.015f;
        float newDebt = Math.max(0f, curDebt - reduction);
        float change = newDebt - curDebt;
        clorinde.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, newDebt);
        broadcastPacket(new PacketEntityFightPropUpdateNotify(clorinde, FightProperty.FIGHT_PROP_CUR_HP_DEBTS));
        var debtsReason = newDebt <= 0f
            ? ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY_FINISH
            : ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY;
        broadcastPacket(new PacketEntityFightPropChangeReasonNotify(
            clorinde,
            FightProperty.FIGHT_PROP_CUR_HP_DEBTS,
            change,
            PropChangeReasonOuterClass.PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,
            debtsReason
        ));
    }

    private void reduceArlecchinoBoL(EntityAvatar arlecchino) {
        float curDebt = arlecchino.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
        if (curDebt <= 0f) return;
        float reduction = curDebt * 0.024f;
        float newDebt = Math.max(0f, curDebt - reduction);
        float change = newDebt - curDebt;
        arlecchino.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, newDebt);
        broadcastPacket(new PacketEntityFightPropUpdateNotify(arlecchino, FightProperty.FIGHT_PROP_CUR_HP_DEBTS));
        var debtsReason = newDebt <= 0f
            ? ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY_FINISH
            : ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY;
        broadcastPacket(new PacketEntityFightPropChangeReasonNotify(
            arlecchino,
            FightProperty.FIGHT_PROP_CUR_HP_DEBTS,
            change,
            PropChangeReasonOuterClass.PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,
            debtsReason
        ));
    }

    public void killEntity(GameEntity target) {
        killEntity(target, 0);
    }

    public void killEntity(GameEntity target, int attackerId) {
        GameEntity attacker = null;

        if (attackerId > 0) {
            attacker = getEntityById(attackerId);
        }

        if (attacker != null) {

            if (attacker instanceof EntityClientGadget gadgetAttacker) {
                var clientGadgetOwner = getEntityById(gadgetAttacker.getOwnerEntityId());
                if (clientGadgetOwner instanceof EntityAvatar) {
                    ((EntityClientGadget) attacker)
                            .getOwner()
                            .getCodex()
                            .checkAnimal(target, CodexAnimalData.CountType.CODEX_COUNT_TYPE_KILL);
                }
            } else if (attacker instanceof EntityAvatar avatarAttacker) {
                avatarAttacker
                        .getPlayer()
                        .getCodex()
                        .checkAnimal(target, CodexAnimalData.CountType.CODEX_COUNT_TYPE_KILL);
            }
        }

        // Activity watcher + daily commissions: only count player-controlled kills.
        // Resolve the true owner so avatar-owned gadgets/projectiles (skills, elemental
        // reactions) also count — otherwise only direct normal-attack kills register.
        if (target instanceof EntityMonster monster) {
            GameEntity trueAttacker = attacker != null ? attacker.getTrueOwner() : null;
            Grasscutter.getLogger()
                    .info(
                            "[DailyCommission] killEntity: monster={} attackerId={} attacker={} trueAttacker={}",
                            monster.getMonsterData().getId(),
                            attackerId,
                            attacker == null ? "null" : attacker.getClass().getSimpleName(),
                            trueAttacker == null ? "null" : trueAttacker.getClass().getSimpleName());
            if (trueAttacker instanceof EntityAvatar avatarAttacker) {
                var monsterId = String.valueOf(monster.getMonsterData().getId());
                var activityManager = avatarAttacker.getPlayer().getActivityManager();
                activityManager.triggerWatcher(
                        WatcherTriggerType.TRIGGER_BATTLE_FOR_MONSTER_DIE_OR, monsterId);
                activityManager.triggerWatcher(
                        WatcherTriggerType.TRIGGER_KILL_MONSTERS_WITHOUT_VEHICLE, monsterId);
                // Daily commissions: advance kill-count commissions.
                if (avatarAttacker.getPlayer().getDailyCommissionManager() != null) {
                    avatarAttacker
                            .getPlayer()
                            .getDailyCommissionManager()
                            .onMonsterKilled(monster.getGroupId(), monster.getConfigId());
                }
            }
        }

        this.broadcastPacket(new PacketLifeStateChangeNotify(attackerId, target, LifeState.LIFE_DEAD));

        var world = this.getWorld();
        if (target instanceof EntityMonster monster && this.getSceneType() != SceneType.SCENE_DUNGEON) {
            if (monster.getMetaMonster() != null
                    && !world.getServer().getDropSystem().handleMonsterDrop(monster)) {
                Grasscutter.getLogger()
                        .debug(
                                "Can not solve monster drop: drop_id = {}, drop_tag = {}. Falling back to legacy drop system.",
                                monster.getMetaMonster().drop_id,
                                monster.getMetaMonster().drop_tag);
                world.getServer().getDropSystemLegacy().callDrop(monster);
            }
        }

        if (target instanceof EntityGadget gadget) {
            if (gadget.getMetaGadget() != null) {
                world
                        .getServer()
                        .getDropSystem()
                        .handleChestDrop(
                                gadget.getMetaGadget().drop_id, gadget.getMetaGadget().drop_count, gadget);
            }
        }

        this.removeEntity(target);

        if (target instanceof EntityClientGadget cg && cg.getOwner() != null) {
            cg.getOwner().getTeamManager().getGadgets().remove(cg);
        }

        target.onDeath(attackerId);
        this.triggerDungeonEvent(
                DungeonPassConditionType.DUNGEON_COND_KILL_MONSTER_COUNT, ++killedMonsterCount);
    }

    public void onTick() {

        if (this.getSceneType() == SceneType.SCENE_HOME_WORLD
                || this.getSceneType() == SceneType.SCENE_HOME_ROOM) {
            this.finishLoading();
            return;
        }

        if (!isPaused) {
            this.getScheduler().runTasks();
        }

        if (this.getScriptManager().isInit()) {

            this.checkGroups();
        } else {

            this.checkSpawns();
        }

        this.scriptManager.checkRegions();

        if (challenge != null) {
            challenge.onCheckTimeOut();
        }

        var sceneTime = getSceneTimeSeconds();

        var entities = Map.copyOf(this.getEntities());
        entities.forEach(
                (eid, e) -> {
                    if (!e.isAlive()) {
                        this.getEntities().remove(eid);
                    } else e.onTick(sceneTime);
                });

        blossomManager.onTick();

        var towerManager = getPlayers().get(0).getTowerManager();
        if (towerManager != null && towerManager.isInProgress()) {
            towerManager.onTick();
        }

        this.checkNpcGroup();

        this.finishLoading();
        this.checkPlayerRespawn();
        if (this.tickCount++ % 10 == 0) this.broadcastPacket(new PacketSceneTimeNotify(this));
    }

    protected void checkPlayerRespawn() {
        if (this.getScriptManager().getConfig() == null) return;
        var diePos = this.getScriptManager().getConfig().die_y;

        this.players.forEach(
                player -> {
                    if (this.getScriptManager().getConfig() == null) return;

                    if (diePos >= player.getPosition().getY()) {

                        this.respawnPlayer(player);
                    }
                });

        this.getEntities()
                .forEach(
                        (id, entity) -> {
                            if (diePos >= entity.getPosition().getY()) {
                                this.killEntity(entity);
                            }
                        });
    }

    public Position getDefaultLocation(Player player) {
        val defaultPosition = getScriptManager().getConfig().born_pos;
        return defaultPosition != null ? defaultPosition : player.getPosition();
    }

    public Position getDefaultRotation(Player player) {
        var defaultRotation = this.getScriptManager().getConfig().born_rot;
        return defaultRotation != null ? defaultRotation : player.getRotation();
    }

    private Position getRespawnLocation(Player player) {

        var lastCheckpointPos = dungeonManager != null ? dungeonManager.getRespawnLocation() : null;
        return lastCheckpointPos != null ? lastCheckpointPos : getDefaultLocation(player);
    }

    private Position getRespawnRotation(Player player) {
        var lastCheckpointRot =
                this.dungeonManager != null ? this.dungeonManager.getRespawnRotation() : null;
        return lastCheckpointRot != null ? lastCheckpointRot : this.getDefaultRotation(player);
    }

    public boolean respawnPlayer(Player player) {

        player.getTeamManager().applyVoidDamage();

        var targetPos = getRespawnLocation(player);
        var targetRot = getRespawnRotation(player);
        var teleportProps =
                TeleportProperties.builder()
                        .sceneId(getId())
                        .teleportTo(targetPos)
                        .teleportRot(targetRot)
                        .teleportType(PlayerTeleportEvent.TeleportType.INTERNAL)
                        .enterType(EnterTypeOuterClass.EnterType.EnterType_ENTER_GOTO)
                        .enterReason(
                                dungeonManager != null ? EnterReason.DungeonReviveOnWaypoint : EnterReason.Revival);

        return this.getWorld().transferPlayerToScene(player, teleportProps.build());
    }

    public void finishLoading() {
        if (this.finishedLoading) return;

        this.finishedLoading = true;
        this.afterLoadedCallbacks.forEach(Runnable::run);
        this.afterLoadedCallbacks.clear();
    }

    public void runWhenFinished(Runnable runnable) {
        if (this.isFinishedLoading()) {
            runnable.run();
            return;
        }

        this.afterLoadedCallbacks.add(runnable);
    }

    public void playerSceneInitialized(Player player) {

        if (!player.equals(this.getHost())) return;

        this.afterHostInitCallbacks.forEach(Runnable::run);
        this.afterHostInitCallbacks.clear();
    }

    public void runWhenHostInitialized(Runnable runnable) {
        if (this.isFinishedLoading()) {
            runnable.run();
            return;
        }

        this.afterHostInitCallbacks.add(runnable);
    }

    public int getEntityLevel(int baseLevel, int worldLevelOverride) {
        int level = worldLevelOverride > 0 ? worldLevelOverride + baseLevel - 22 : baseLevel;
        level = Math.min(level, 100);
        level = level <= 0 ? 1 : level;

        return level;
    }

    public int getLevelForMonster(int configId, int defaultLevel) {
        if (getDungeonManager() != null) {
            return getDungeonManager().getLevelForMonster(configId);
        } else if (getWorld().getWorldLevel() > 0) {
            var worldLevelData = GameData.getWorldLevelDataMap().get(getWorld().getWorldLevel());

            if (worldLevelData != null) {
                return worldLevelData.getMonsterLevel();
            }
        }
        return defaultLevel;
    }

    public void checkNpcGroup() {
        Set<SceneNpcBornEntry> npcBornEntries = ConcurrentHashMap.newKeySet();
        for (Player player : this.getPlayers()) {
            npcBornEntries.addAll(loadNpcForPlayer(player));
        }

        this.npcBornEntrySet = npcBornEntries;
    }

    public void checkSpawns() {
        Set<SpawnDataEntry.GridBlockId> loadedGridBlocks = new HashSet<>();
        for (Player player : this.getPlayers()) {
            Collections.addAll(
                    loadedGridBlocks,
                    SpawnDataEntry.GridBlockId.getAdjacentGridBlockIds(
                            player.getSceneId(), player.getPosition()));
        }

        Set<SpawnDataEntry.GridBlockId> leavingBlocks = new HashSet<>(this.loadedGridBlocks);
        leavingBlocks.removeAll(loadedGridBlocks);
        if (!leavingBlocks.isEmpty()) {
            this.getDeadSpawnedEntities().removeIf(entry -> leavingBlocks.contains(entry.getBlockId()));
        }
        if (this.loadedGridBlocks.containsAll(
                loadedGridBlocks)) {
            return;
        }
        this.loadedGridBlocks = loadedGridBlocks;
        var spawnLists = GameDepot.getSpawnLists();
        Set<SpawnDataEntry> visible = new HashSet<>();
        for (var block : loadedGridBlocks) {
            var spawns = spawnLists.get(block);
            if (spawns != null) {
                visible.addAll(spawns);
            }
        }

        WorldLevelData worldLevelData = GameData.getWorldLevelDataMap().get(getWorld().getWorldLevel());
        int worldLevelOverride = 0;

        if (worldLevelData != null) {
            worldLevelOverride = worldLevelData.getMonsterLevel();
        }

        List<GameEntity> toAdd = new ArrayList<>();
        List<GameEntity> toRemove = new ArrayList<>();
        var spawnedEntities = this.getSpawnedEntities();
        for (SpawnDataEntry entry : visible) {

            if (!spawnedEntities.contains(entry) && !this.getDeadSpawnedEntities().contains(entry)) {

                GameEntity entity = null;

                if (entry.getMonsterId() > 0) {
                    MonsterData data = GameData.getMonsterDataMap().get(entry.getMonsterId());
                    if (data == null) continue;

                    int level = this.getEntityLevel(entry.getLevel(), worldLevelOverride);

                    EntityMonster monster =
                            new EntityMonster(this, data, entry.getPos(), entry.getRot(), level);
                    monster.setGroupId(entry.getGroup().getGroupId());
                    monster.setPoseId(entry.getPoseId());
                    monster.setConfigId(entry.getConfigId());
                    monster.setSpawnEntry(entry);

                    entity = monster;
                } else if (entry.getGadgetId() > 0) {
                    EntityGadget gadget =
                            new EntityGadget(this, entry.getGadgetId(), entry.getPos(), entry.getRot());
                    gadget.setGroupId(entry.getGroup().getGroupId());
                    gadget.setConfigId(entry.getConfigId());
                    gadget.setSpawnEntry(entry);
                    int state = entry.getGadgetState();
                    if (state > 0) {
                        gadget.setState(state);
                    }
                    gadget.buildContent();

                    gadget.setFightProperty(FightProperty.FIGHT_PROP_BASE_HP, Float.POSITIVE_INFINITY);
                    gadget.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, Float.POSITIVE_INFINITY);
                    gadget.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, Float.POSITIVE_INFINITY);

                    entity = gadget;
                    blossomManager.initBlossom(gadget);
                }

                if (entity == null) continue;

                toAdd.add(entity);
            }
        }

        for (GameEntity entity : this.getEntities().values()) {
            var spawnEntry = entity.getSpawnEntry();
            if (spawnEntry != null
                    && !(entity instanceof EntityWeapon)
                    && !visible.contains(spawnEntry)) {
                toRemove.add(entity);
                spawnedEntities.remove(spawnEntry);
            }
        }

        if (!toAdd.isEmpty()) {
            List<GameEntity> added = new ArrayList<>();
            for (var entity : toAdd) {
                if (this.addEntityDirectly(entity)) {
                    added.add(entity);
                    if (entity.getSpawnEntry() != null) {
                        this.spawnedEntities.add(entity.getSpawnEntry());
                    }
                }
            }
            for (var l : chopped(added, 100)) {
                this.broadcastPacket(new PacketSceneEntityAppearNotify(l, VisionType.VisionType_VISION_BORN));
            }
        }

        if (!toRemove.isEmpty()) {
            List<GameEntity> removed = new ArrayList<>();
            for (var entity : toRemove) {
                if (this.removeEntityDirectly(entity) != null) {
                    removed.add(entity);
                }
            }
            for (var l : chopped(removed, 100)) {
                this.broadcastPacket(
                        new PacketSceneEntityDisappearNotify(l, VisionType.VisionType_VISION_REMOVE));
            }
            blossomManager.recycleGadgetEntity(toRemove);
        }
    }

    public List<SceneBlock> getPlayerActiveBlocks(Player player) {
        if (GAME_OPTIONS.isPreventEntityError) {
            return SceneIndexManager.queryNeighbors(
                    getScriptManager().getBlocksIndex(),
                    player.getPosition().toXZDoubleArray(),
                    MAX_ENTITY_LOAD_RANGE);
        }

        return SceneIndexManager.queryNeighbors(
                getScriptManager().getBlocksIndex(),
                player.getPosition().toXZDoubleArray(),
                Grasscutter.getConfig().server.game.loadEntitiesForPlayerRange);
    }

    public Set<SceneGroup> getPlayerActiveGroups(Player player) {
        Set<SceneGroup> activeGroups = new HashSet<>();
        Position playerPosition = player.getPosition();

        // Upstream-compatible mode: use the original grid-based vision loading.
        if (!GAME_OPTIONS.isPreventEntityError) {
            Set<Integer> activeGroupIds = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                Grid grid = getScriptManager().getGroupGrids().get(i);

                activeGroupIds.addAll(grid.getNearbyGroups(i, playerPosition));
            }

            for (int groupId : activeGroupIds) {
                for (var block : scriptManager.getBlocks().values()) {
                    loadBlock(block);
                    if (block.groups == null) continue;
                    SceneGroup group = block.groups.getOrDefault(groupId, null);
                    if (group != null && !group.dynamic_load) {
                        activeGroups.add(group);
                        break;
                    }
                }
            }
            return activeGroups;
        }

        // Prevention mode: do NOT scan the whole scene. Only scan the block(s) that
        // actually contain the player, then filter groups by a 500m XZ distance.
        var blocks = SceneIndexManager.queryNeighbors(
                getScriptManager().getBlocksIndex(),
                playerPosition.toXZDoubleArray(),
                0);
        for (var block : blocks) {
            if (block.groups == null) {
                this.loadBlock(block);
            }
            if (block.groups == null) continue;

            for (var group : block.groups.values()) {
                if (group.dynamic_load || group.pos == null) continue;
                double dx = playerPosition.getX() - group.pos.getX();
                double dz = playerPosition.getZ() - group.pos.getZ();
                if (dx * dx + dz * dz <= (double) MAX_ENTITY_LOAD_RANGE * MAX_ENTITY_LOAD_RANGE) {
                    activeGroups.add(group);
                }
            }
        }
        return activeGroups;
    }

    public boolean loadBlock(SceneBlock block) {
        if (this.loadedBlocks.contains(block)) return false;

        this.onLoadBlock(block, this.players);
        this.loadedBlocks.add(block);
        return true;
    }

    public void checkGroups() {
        if (!GAME_OPTIONS.isPreventEntityError) {
            checkGroupsUpstream();
            return;
        }

        // Collect candidate groups only from the player's current block (500m max distance).
        Map<Integer, SceneGroup> candidatesById = new HashMap<>();
        for (Player player : this.players) {
            for (SceneGroup group : getPlayerActiveGroups(player)) {
                candidatesById.putIfAbsent(group.id, group);
            }
        }

        List<SceneGroup> candidates = new ArrayList<>(candidatesById.values());
        candidates.sort(Comparator.comparingDouble(this::minDistanceSqToPlayers));

        // Greedily keep the nearest groups until the scene entity budget is reached.
        Set<SceneGroup> visible = new HashSet<>();
        int estimatedEntities = 0;
        for (SceneGroup group : candidates) {
            int groupSize = estimateGroupEntityCount(group);
            if (estimatedEntities + groupSize > GAME_OPTIONS.sceneEntityLimit && !visible.isEmpty()) {
                break;
            }
            visible.add(group);
            estimatedEntities += groupSize;
        }

        // Avoid unloading groups while a player is still entering the scene. The client
        // has not finished registering those groups yet, so an early GroupUnloadNotify
        // produces "invalid group" spam and can corrupt the client's scene-node state
        // (ArgumentOutOfRangeException / 112 right after EnterScenePostFinish).
        boolean anyPlayerStillLoading =
                this.players.stream()
                        .anyMatch(p -> p.getSceneLoadState() != Player.SceneLoadState.LOADED);
        if (!anyPlayerStillLoading) {
            for (var group : this.loadedGroups) {
                if (!visible.contains(group) && !group.dynamic_load && !group.dontUnload) {
                    long loadTime = this.groupLoadTimes.getOrDefault(group.id, 0L);
                    if (System.currentTimeMillis() - loadTime < GROUP_UNLOAD_GRACE_PERIOD_MS) {
                        continue;
                    }
                    unloadGroup(scriptManager.getBlocks().get(group.block_id), group.id);
                }
            }
        }

        var toLoad =
                visible.stream()
                        .filter(g -> this.loadedGroups.stream().noneMatch(gr -> gr.id == g.id))
                        .filter(g -> !g.dynamic_load)
                        .toList();

        this.onLoadGroup(toLoad);
        if (!toLoad.isEmpty()) this.onRegisterGroups();
    }

    private void checkGroupsUpstream() {
        Set<Integer> visible =
                this.players.stream()
                        .map(this::getPlayerActiveGroups)
                        .flatMap(Collection::stream)
                        .map(group -> group.id)
                        .collect(Collectors.toSet());

        // Avoid unloading groups while a player is still entering the scene. The client
        // has not finished registering those groups yet, so an early GroupUnloadNotify
        // produces "invalid group" spam and can corrupt the client's scene-node state
        // (ArgumentOutOfRangeException / 112 right after EnterScenePostFinish).
        boolean anyPlayerStillLoading =
                this.players.stream()
                        .anyMatch(p -> p.getSceneLoadState() != Player.SceneLoadState.LOADED);
        if (!anyPlayerStillLoading) {
            for (var group : this.loadedGroups) {
                if (!visible.contains(group.id) && !group.dynamic_load && !group.dontUnload) {
                    long loadTime = this.groupLoadTimes.getOrDefault(group.id, 0L);
                    if (System.currentTimeMillis() - loadTime < GROUP_UNLOAD_GRACE_PERIOD_MS) {
                        continue;
                    }
                    unloadGroup(scriptManager.getBlocks().get(group.block_id), group.id);
                }
            }
        }

        var toLoad =
                visible.stream()
                        .filter(g -> this.loadedGroups.stream().noneMatch(gr -> gr.id == g))
                        .map(
                                g -> {
                                    for (var b : scriptManager.getBlocks().values()) {
                                        loadBlock(b);
                                        if (b.groups == null) continue;
                                        SceneGroup group = b.groups.getOrDefault(g, null);
                                        if (group != null && !group.dynamic_load) return group;
                                    }

                                    return null;
                                })
                        .filter(Objects::nonNull)
                        .toList();

        this.onLoadGroup(toLoad);
        if (!toLoad.isEmpty()) this.onRegisterGroups();
    }

    private double minDistanceSqToPlayers(SceneGroup group) {
        if (group.pos == null) return Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (Player player : this.players) {
            double dx = player.getPosition().getX() - group.pos.getX();
            double dz = player.getPosition().getZ() - group.pos.getZ();
            min = Math.min(min, dx * dx + dz * dz);
        }
        return min;
    }

    private int estimateGroupEntityCount(SceneGroup group) {
        int count = 0;
        if (group.monsters != null) count += group.monsters.size();
        if (group.gadgets != null) count += group.gadgets.size();
        if (group.npcs != null) count += group.npcs.size();
        return Math.max(1, count);
    }

    public void onLoadBlock(SceneBlock block, List<Player> players) {
        this.getScriptManager().loadBlockFromScript(block);
        scriptManager.getLoadedGroupSetPerBlock().put(block.id, new HashSet<>());

        Grasscutter.getLogger().trace("Scene {} block {} loaded.", this.getId(), block.id);
    }

    public int loadDynamicGroup(int group_id) {
        SceneGroup group = getScriptManager().getGroupById(group_id);
        if (group == null) return -1;

        this.onLoadGroup(new ArrayList<>(List.of(group)));

        if (GameData.getGroupReplacements().containsKey(group_id)) onRegisterGroups();

        if (group.init_config == null) return -1;
        return group.init_config.suite;
    }

    public boolean unregisterDynamicGroup(int groupId) {
        var group = getScriptManager().getGroupById(groupId);
        if (group == null) return false;

        var block = getScriptManager().getBlocks().get(group.block_id);
        this.unloadGroup(block, groupId);
        return true;
    }

    public void onRegisterGroups() {
        var sceneGroups = this.loadedGroups;
        var sceneGroupMap =
                sceneGroups.stream().collect(Collectors.toMap(item -> item.id, item -> item));
        var sceneGroupsIds = sceneGroups.stream().map(group -> group.id).toList();
        var dynamicGroups =
                sceneGroups.stream().filter(group -> group.dynamic_load).map(group -> group.id).toList();

        var nodes = new ArrayList<KahnsSort.Node>();
        var groupList = new ArrayList<Integer>();
        GameData.getGroupReplacements().values().stream()
                .filter(replacement -> dynamicGroups.contains(replacement.id))
                .forEach(
                        replacement -> {
                            Grasscutter.getLogger().debug("Graph ordering replacement {}", replacement);
                            replacement.replace_groups.forEach(
                                    group -> {
                                        nodes.add(new KahnsSort.Node(replacement.id, group));
                                        if (!groupList.contains(group)) groupList.add(group);
                                    });

                            if (!groupList.contains(replacement.id)) groupList.add(replacement.id);
                        });

        KahnsSort.Graph graph = new KahnsSort.Graph(nodes, groupList);
        List<Integer> dynamicGroupsOrdered = KahnsSort.doSort(graph);

        dynamicGroupsOrdered.forEach(
                group -> {
                    if (GameData.getGroupReplacements().containsKey((int) group)) {
                        var data = GameData.getGroupReplacements().get((int) group);
                        var sceneGroupReplacement =
                                this.loadedGroups.stream().filter(g -> g.id == group).findFirst().orElseThrow();
                        if (sceneGroupReplacement.is_replaceable != null) {
                            var it = data.replace_groups.iterator();
                            while (it.hasNext()) {
                                var replace_group = it.next();
                                if (!sceneGroupsIds.contains(replace_group)) continue;

                                SceneGroup sceneGroup = sceneGroupMap.get(replace_group);
                                if (sceneGroup != null
                                        && sceneGroup.is_replaceable != null
                                        && ((sceneGroup.is_replaceable.value
                                                        && sceneGroup.is_replaceable.version
                                                                <= sceneGroupReplacement.is_replaceable.version)
                                                || sceneGroup.is_replaceable.new_bin_only)) {
                                    this.unloadGroup(
                                            scriptManager.getBlocks().get(sceneGroup.block_id), replace_group);
                                    it.remove();
                                    Grasscutter.getLogger().debug("Graph ordering: unloaded {}", replace_group);
                                }
                            }
                        }
                    }
                });
    }

    public void loadTriggerFromGroup(SceneGroup group, String triggerName) {

        this.getScriptManager()
                .registerTrigger(
                        group.triggers.values().stream()
                                .filter(p -> p.getName().contains(triggerName))
                                .toList());
        group.regions.values().stream()
                .filter(q -> q.config_id == Integer.parseInt(triggerName.substring(13)))
                .map(region -> new EntityRegion(this, region))
                .forEach(getScriptManager()::registerRegion);
    }

    public void onLoadGroup(List<SceneGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return;
        }

        for (var group : groups) {
            if (this.loadedGroups.contains(group)) continue;

            this.getScriptManager().loadGroupFromScript(group);
            if (!this.scriptManager.getLoadedGroupSetPerBlock().containsKey(group.block_id))
                this.onLoadBlock(scriptManager.getBlocks().get(group.block_id), players);
            this.scriptManager.getLoadedGroupSetPerBlock().get(group.block_id).add(group);
        }

        var entities = new ArrayList<GameEntity>();
        for (var group : groups) {
            if (this.loadedGroups.contains(group)) continue;

            if (group.init_config == null) {
                continue;
            }

            var groupInstance = this.getScriptManager().getGroupInstanceById(group.id);
            var cachedInstance = this.getScriptManager().getCachedGroupInstanceById(group.id);
            if (cachedInstance != null) {
                cachedInstance.setLuaGroup(group);
                groupInstance = cachedInstance;
            }

            this.getScriptManager()
                    .refreshGroup(groupInstance, 0, false);

            this.loadedGroups.add(group);
            this.groupLoadTimes.put(group.id, System.currentTimeMillis());
        }

        this.scriptManager.meetEntities(entities);
        groups.forEach(
                g -> scriptManager.callEvent(new ScriptArgs(g.id, EventType.EVENT_GROUP_LOAD, g.id)));

        Grasscutter.getLogger().trace("Scene {} loaded {} group(s)", this.getId(), groups.size());
    }

    public void registerClientGroup(int groupId) {
        this.clientKnownGroups.add(groupId);
    }

    public void registerClientGroups(Collection<Integer> groupIds) {
        this.clientKnownGroups.addAll(groupIds);
    }

    public void unloadGroup(SceneBlock block, int group_id) {
        // Block metadata (SceneBlock.groups) may not be loaded yet for freshly-created
        // accounts. Be null-safe so a failed unload doesn't abort the surrounding
        // daily-commission group refresh (previously NPE: block.groups is null).
        List<GameEntity> toRemove =
                this.getEntities().values().stream()
                        .filter(
                                e ->
                                        e != null
                                                && (block == null || e.getBlockId() == block.id)
                                                && e.getGroupId() == group_id)
                        .toList();

        if (!toRemove.isEmpty()) {
            List<GameEntity> removed = new ArrayList<>();
            for (var entity : toRemove) {
                if (this.removeEntityDirectly(entity) != null) {
                    removed.add(entity);
                }
            }
            for (var l : chopped(removed, 100)) {
                this.broadcastPacket(
                        new PacketSceneEntityDisappearNotify(l, VisionType.VisionType_VISION_REMOVE));
            }
        }

        SceneGroup group = block == null || block.groups == null ? null : block.groups.get(group_id);
        if (group == null) {
            group =
                    this.loadedGroups.stream()
                            .filter(loaded -> loaded.id == group_id)
                            .findFirst()
                            .orElse(null);
        }

        if (group != null) {
            if (group.triggers != null) {
                group.triggers.values().forEach(getScriptManager()::deregisterTrigger);
            }
            if (group.regions != null) {
                group.regions.values().forEach(getScriptManager()::deregisterRegion);
            }
            if (challenge != null && group.id == challenge.getGroup().id) {
                challenge.fail();
            }
        }

        if (block != null) {
            var loadedGroupsForBlock = this.scriptManager.getLoadedGroupSetPerBlock().get(block.id);
            if (loadedGroupsForBlock != null) {
                loadedGroupsForBlock.removeIf(loaded -> loaded.id == group_id);
                if (loadedGroupsForBlock.isEmpty()) {
                    this.scriptManager.getLoadedGroupSetPerBlock().remove(block.id);
                    Grasscutter.getLogger()
                            .trace("Scene {} block {} is unloaded.", this.getId(), block.id);
                }
            }
        }

        this.loadedGroups.removeIf(loaded -> loaded.id == group_id);
        this.groupLoadTimes.remove(group_id);

        // Upstream-compatible mode sends GroupUnloadNotify unconditionally.
        // Prevention mode only sends it for groups explicitly registered on the client
        // through GroupSuiteNotify. Normal big-world groups are never registered that way,
        // so sending GroupUnloadNotify for them makes the client log
        // "LightWeightInstanceManager::UnregisterModularGroupInternal, invalid group"
        // and can contribute to "node cnt out of index" crashes during scene entry.
        if (group != null
                && (!GAME_OPTIONS.isPreventEntityError || this.clientKnownGroups.contains(group_id))) {
            if (GAME_OPTIONS.isPreventEntityError) {
                this.clientKnownGroups.remove(group_id);
            }
            this.broadcastPacket(new PacketGroupUnloadNotify(List.of(group_id)));
            this.scriptManager.unregisterGroup(group);
        }
    }

    public void onPlayerCreateGadget(EntityClientGadget gadget) {
        var owner = gadget.getOwner();

        if (this.addEntityDirectly(gadget)) {
            owner.getTeamManager().getGadgets().add(gadget);

            for (var player : this.getPlayers()) {
                if (player != owner) {
                    player.getSession().send(new PacketSceneEntityAppearNotify(gadget));
                }
            }
        }
    }

    public void onPlayerDestroyGadget(int entityId) {
        GameEntity entity = getEntities().get(entityId);

        if (!(entity instanceof EntityClientGadget gadget)) {
            for (var player : this.getPlayers()) {
                player.getTeamManager().getGadgets().removeIf(g -> g.getId() == entityId);
            }
            return;
        }

        this.removeEntityDirectly(gadget);

        var owner = gadget.getOwner();
        owner.getTeamManager().getGadgets().remove(gadget);

        this.broadcastPacket(
                new PacketSceneEntityDisappearNotify(gadget, VisionType.VisionType_VISION_DIE));
    }

    public void broadcastPacket(BasePacket packet) {

        for (Player player : this.getPlayers()) {
            player.getSession().send(packet);
        }
    }

    public void broadcastPacketToOthers(Player excludedPlayer, BasePacket packet) {

        if (this.getPlayerCount() == 1 && this.getPlayers().get(0) == excludedPlayer) {
            return;
        }

        for (Player player : this.getPlayers()) {
            if (player == excludedPlayer) {
                continue;
            }

            player.getSession().send(packet);
        }
    }

    /**
     * Relocate an entity for co-op peers using a single "replace" appear notify.
     *
     * <p>The 6.7 client ignores CombatInvocations-notify movement, so position sync for peers is
     * done by replacing the entity in place. Only an appear is sent (no preceding disappear) to
     * minimise packet count and avoid the born/remove flicker.
     */
    public void broadcastRelocateToOthers(Player excludedPlayer, GameEntity entity) {
        if (entity == null) {
            return;
        }

        this.broadcastPacketToOthers(
                excludedPlayer,
                new PacketSceneEntityAppearNotify(
                        entity, VisionType.VisionType_VISION_REPLACE, entity.getId()));
    }

    public void addItemEntity(int itemId, int amount, GameEntity bornForm) {
        ItemData itemData = GameData.getItemDataMap().get(itemId);
        if (itemData == null) {
            return;
        }
        if (itemData.isEquip()) {
            float range = (1.5f + (.05f * amount));
            for (int i = 0; i < amount; i++) {
                Position pos = bornForm.getPosition().nearby2d(range).addZ(.9f);
                EntityItem entity = new EntityItem(this, null, itemData, pos, 1);
                addEntity(entity);
            }
        } else {
            EntityItem entity =
                    new EntityItem(
                            this, null, itemData, bornForm.getPosition().clone().addZ(.9f), amount);
            addEntity(entity);
        }
    }

    public void loadNpcForPlayerEnter(Player player) {
        this.npcBornEntrySet.addAll(loadNpcForPlayer(player));
    }

    private List<SceneNpcBornEntry> loadNpcForPlayer(Player player) {
        var pos = player.getPosition();
        var data = GameData.getSceneNpcBornData().get(getId());
        if (data == null) {
            return List.of();
        }

        var npcList =
                SceneIndexManager.queryNeighbors(
                        data.getIndex(),
                        pos.toDoubleArray(),
                        Grasscutter.getConfig().server.game.loadEntitiesForPlayerRange);

        var sceneNpcBornCanidates =
                npcList.stream().filter(i -> !this.npcBornEntrySet.contains(i)).toList();

        List<SceneNpcBornEntry> sceneNpcBornEntries = new ArrayList<>();
        sceneNpcBornCanidates.forEach(
                i -> {
                    var groupInstance = scriptManager.getGroupInstanceById(i.getGroupId());
                    if (groupInstance == null) return;
                    if (i.getSuiteIdList() != null
                            && !i.getSuiteIdList().contains(groupInstance.getActiveSuiteId())) return;
                    sceneNpcBornEntries.add(i);
                });

        if (sceneNpcBornEntries.size() > 0) {
            this.registerClientGroups(
                    sceneNpcBornEntries.stream().map(SceneNpcBornEntry::getGroupId).toList());
            this.broadcastPacket(new PacketGroupSuiteNotify(sceneNpcBornEntries));
            Grasscutter.getLogger().trace("Loaded Npc Group Suite {}", sceneNpcBornEntries);
        }

        return npcList.stream()
                .filter(i -> this.npcBornEntrySet.contains(i) || sceneNpcBornEntries.contains(i))
                .toList();
    }

    public void loadGroupForQuest(List<QuestGroupSuite> sceneGroupSuite) {
        if (!scriptManager.isInit()) {
            return;
        }

        sceneGroupSuite.forEach(
                i -> {
                    var group = scriptManager.getGroupById(i.getGroup());
                    if (group == null) return;

                    var groupInstance = scriptManager.getGroupInstanceById(i.getGroup());
                    var suite = group.getSuiteByIndex(i.getSuite());
                    if (suite == null || groupInstance == null) {
                        return;
                    }

                    scriptManager.refreshGroup(groupInstance, i.getSuite(), false);
                });
    }

    public void unlockForce(int force) {
        this.unlockedForces.add(force);
        this.broadcastPacket(new PacketSceneForceUnlockNotify(force, true));
    }

    public void lockForce(int force) {
        this.unlockedForces.remove(force);
        this.broadcastPacket(new PacketSceneForceLockNotify(force));
    }

    public void selectWorktopOptionWith(SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq req) {
        GameEntity entity = getEntityById(req.getGadgetEntityId());
        if (entity == null) {
            return;
        }

        if (entity instanceof EntityGadget gadget) {
            if (gadget.getContent() instanceof GadgetWorktop worktop) {
                boolean shouldDelete = worktop.onSelectWorktopOption(req);
                if (shouldDelete) {
                    entity.getScene().removeEntity(entity, VisionType.VisionType_VISION_REMOVE);
                }
            }
        }
    }

    public void saveGroups() {
        this.getScriptManager().getCachedGroupInstances().values().forEach(SceneGroupInstance::save);
    }
}
