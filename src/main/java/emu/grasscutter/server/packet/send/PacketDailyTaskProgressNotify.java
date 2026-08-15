package emu.grasscutter.server.packet.send;

import emu.grasscutter.data.excels.daily.DailyTaskData;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import java.io.ByteArrayOutputStream;

/**
 * 6.7 client daily-commission progress update for a single task.
 *
 * <p>Hand-encoded protobuf (see {@link PacketWorldOwnerDailyTaskNotify}).
 */
public class PacketDailyTaskProgressNotify extends BasePacket {

    static final int F_INFO = 3; // DailyTaskInfo info

    public PacketDailyTaskProgressNotify(DailyTaskData task, int rewardId, int progress, boolean finished) {
        super(PacketOpcodes.DailyTaskProgressNotify);

        var data = new ByteArrayOutputStream();
        byte[] info = PacketWorldOwnerDailyTaskNotify.encodeDailyTaskInfo(task, rewardId, progress, finished);
        PacketWorldOwnerDailyTaskNotify.writeBytesField(data, F_INFO, info);

        this.setData(data.toByteArray());
    }
}
