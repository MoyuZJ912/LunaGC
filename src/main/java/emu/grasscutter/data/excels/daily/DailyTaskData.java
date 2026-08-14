package emu.grasscutter.data.excels.daily;

import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.ToString;

/**
 * One entry of the official daily-commission pool
 * (resources/ExcelBinOutput/DailyTaskExcelConfigData.json).
 *
 * <p>Each entry is either a scene-event commission (monster kill / challenge / gadget
 * interaction, tracked via {@link #getFinishType()} / {@link #getFinishProgress()}) or a
 * quest-linked commission (see {@link #getQuestId()}).
 */
@ResourceType(name = "DailyTaskExcelConfigData.json")
@Getter
@ToString
public class DailyTaskData extends GameResource {
    private int id;
    private int cityId;
    private int poolId;
    private String type; // e.g. DAILY_TASK_SCENE
    private int rarity;
    private List<Integer> oldGroupVec;
    private List<Integer> newGroupVec;
    private String finishType; // DAILY_FINISH_MONSTER_NUM / DAILY_FINISH_CHALLENGE / DAILY_FINISH_GADGET_ID_NUM / null
    private int finishProgress;
    private int finishParam1;
    private int finishParam2;
    private int taskRewardId;
    private String centerPosition; // scene event point name, e.g. "Event_10100V2"
    private int enterDistance;
    private int exitDistance;
    private long titleTextMapHash;
    private long descriptionTextMapHash;
    private long targetTextMapHash;
    private int radarRadius;
    private int questId; // quest-linked commission (0 when none)

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void onLoad() {
        if (this.oldGroupVec == null) this.oldGroupVec = Collections.emptyList();
        if (this.newGroupVec == null) this.newGroupVec = Collections.emptyList();
    }
}
