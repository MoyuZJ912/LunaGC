package emu.grasscutter.data.excels.daily;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import lombok.Getter;
import lombok.ToString;

/**
 * Player-level band for daily commissions
 * (resources/ExcelBinOutput/DailyTaskLevelExcelConfigData.json).
 */
@ResourceType(name = "DailyTaskLevelExcelConfigData.json")
@Getter
@ToString
public class DailyTaskLevelData extends GameResource {
    @SerializedName("ID")
    private int id;

    private int minPlayerLevel;
    private int maxPlayerLevel;
    private int groupReviseLevel;
    private int scoreDropId;
    private int scorePreviewRewardId;

    @Override
    public int getId() {
        return id;
    }
}
