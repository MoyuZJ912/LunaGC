package emu.grasscutter.data.excels.daily;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

/**
 * Daily-commission reward definition
 * (resources/ExcelBinOutput/DailyTaskRewardExcelConfigData.json).
 *
 * <p>Each reward id maps to a list of {@link DropVecEntry} (one per player-level band),
 * each referencing a drop table ({@code dropId}) and a preview reward ({@code previewRewardId}).
 */
@ResourceType(name = "DailyTaskRewardExcelConfigData.json")
@Getter
@ToString
public class DailyTaskRewardData extends GameResource {
    @SerializedName("ID")
    private int id;

    private List<DropVecEntry> dropVec;

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void onLoad() {
        if (this.dropVec == null) this.dropVec = Collections.emptyList();
    }

    @Data
    public static class DropVecEntry {
        private int dropId;
        private int previewRewardId;
    }
}
