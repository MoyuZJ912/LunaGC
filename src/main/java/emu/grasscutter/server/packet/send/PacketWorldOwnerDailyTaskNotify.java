package emu.grasscutter.server.packet.send;

import emu.grasscutter.data.excels.daily.DailyTaskData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import java.io.ByteArrayOutputStream;

/**
 * 6.7 client daily-commission panel data: pushes today's 4 commissions.
 *
 * <p>The 6.7 protocol snapshot in this repository lacks the generated DailyTask messages, so the
 * protobuf payload is hand-encoded from the field numbers reverse-engineered from the 6.7 proto
 * dump (see docs/daily-commission-plan.md). Field numbers are the wire numbers.
 */
public class PacketWorldOwnerDailyTaskNotify extends BasePacket {

    // DailyTaskInfo field numbers (wire). daily_task_id=7 confirmed via client test.
    static final int F_DAILY_TASK_ID = 7; // daily_task_id (confirmed)
    static final int F_PROGRESS = 2; // progress (guess)
    static final int F_REWARD_ID = 8; // reward_id (guess)
    static final int F_FINISH_PROGRESS = 10; // finish_progress (guess)
    static final int F_IS_FINISHED = 4; // is_finished (bool)

    // WorldOwnerDailyTaskNotify field numbers (wire).
    static final int F_TASK_LIST = 14; // repeated DailyTaskInfo
    static final int F_FILTER_CITY_ID = 9; // filter_city_id
    static final int F_FINISHED_DAILY_TASK_NUM = 2; // finished_daily_task_num

    public PacketWorldOwnerDailyTaskNotify(Player player) {
        super(PacketOpcodes.WorldOwnerDailyTaskNotify);

        var manager = player.getDailyCommissionManager();
        var data = new ByteArrayOutputStream();

        for (var task : manager.getActiveTasks()) {
            byte[] info =
                    encodeDailyTaskInfo(
                            task, manager.getProgress(task.getId()), manager.isFinished(task.getId()));
            writeBytesField(data, F_TASK_LIST, info);
        }
        writeVarintField(data, F_FILTER_CITY_ID, manager.getFilterCityId());
        writeVarintField(data, F_FINISHED_DAILY_TASK_NUM, player.getFinishedDailyTaskCount());

        this.setData(data.toByteArray());
    }

    /**
     * Test-mode constructor: writes {@code daily_task_id} at the given field number and omits every
     * other DailyTaskInfo field, so the caller can probe which wire field number is daily_task_id.
     */
    public PacketWorldOwnerDailyTaskNotify(Player player, int dailyTaskIdField) {
        super(PacketOpcodes.WorldOwnerDailyTaskNotify);

        var manager = player.getDailyCommissionManager();
        var data = new ByteArrayOutputStream();

        for (var task : manager.getActiveTasks()) {
            byte[] info = encodeDailyTaskInfoFieldTest(task, dailyTaskIdField);
            writeBytesField(data, F_TASK_LIST, info);
        }
        writeVarintField(data, F_FILTER_CITY_ID, manager.getFilterCityId());
        writeVarintField(data, F_FINISHED_DAILY_TASK_NUM, player.getFinishedDailyTaskCount());

        this.setData(data.toByteArray());
    }

    /** Encodes one DailyTaskInfo message (test mode: only daily_task_id, omitting other fields). */
    static byte[] encodeDailyTaskInfoFieldTest(DailyTaskData task, int dailyTaskIdField) {
        var out = new ByteArrayOutputStream();
        writeVarintField(out, dailyTaskIdField, task.getId());
        return out.toByteArray();
    }

    /** Encodes one DailyTaskInfo message. */
    static byte[] encodeDailyTaskInfo(DailyTaskData task, int progress, boolean finished) {
        var out = new ByteArrayOutputStream();
        writeVarintField(out, F_DAILY_TASK_ID, task.getId());
        writeVarintField(out, F_PROGRESS, progress);
        writeVarintField(out, F_REWARD_ID, task.getTaskRewardId());
        writeVarintField(out, F_FINISH_PROGRESS, task.getFinishProgress());
        writeVarintField(out, F_IS_FINISHED, finished ? 1 : 0);
        return out.toByteArray();
    }

    // --- minimal protobuf wire encoding ---

    static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    static void writeVarintField(ByteArrayOutputStream out, int fieldNumber, long value) {
        writeVarint(out, ((long) fieldNumber << 3) | 0);
        writeVarint(out, value);
    }

    static void writeBytesField(ByteArrayOutputStream out, int fieldNumber, byte[] bytes) {
        writeVarint(out, ((long) fieldNumber << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
