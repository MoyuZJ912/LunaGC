package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import java.io.ByteArrayOutputStream;

/**
 * Response for the unknown-but-required request {@code DailyTaskUnknownReq} (opcode 28078).
 *
 * <p>The 6.7 client crashes with (1,1,2) if this request goes unanswered, so we reply with a
 * minimal {@code retcode=0} response (wire field 9). Hand-encoded protobuf, same as
 * {@link PacketWorldOwnerDailyTaskNotify}.
 */
public class PacketDailyTaskUnknownRsp extends BasePacket {

    public PacketDailyTaskUnknownRsp() {
        super(PacketOpcodes.DailyTaskUnknownRsp);

        var data = new ByteArrayOutputStream();
        // retcode = 0 (field 9)
        PacketWorldOwnerDailyTaskNotify.writeVarintField(data, 9, 0);

        this.setData(data.toByteArray());
    }
}
