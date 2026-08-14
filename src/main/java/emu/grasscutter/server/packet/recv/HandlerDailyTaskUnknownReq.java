package emu.grasscutter.server.packet.recv;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.net.packet.Opcodes;
import emu.grasscutter.net.packet.PacketHandler;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketDailyTaskUnknownRsp;

/**
 * Handles the unknown-but-required request (opcode 28078) that the 6.7 client sends during
 * daily-commission interaction. Without a response the client crashes with (1,1,2).
 *
 * <p>The request is {@code IAMJEMAMMCC { uint32 field_6 }} (hand-parsed; field names are
 * obfuscated in the 6.7 dump). We log the value to identify the semantics and reply retcode=0.
 */
@Opcodes(PacketOpcodes.DailyTaskUnknownReq)
public class HandlerDailyTaskUnknownReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        int value = 0;
        try {
            int idx = 0;
            while (idx < payload.length) {
                int tag = readVarint(payload, idx);
                idx = readVarintEnd;
                int fieldNumber = tag >>> 3;
                int wireType = tag & 0x7;
                if (wireType == 0) {
                    int v = readVarint(payload, idx);
                    idx = readVarintEnd;
                    if (fieldNumber == 6) {
                        value = v;
                    }
                } else if (wireType == 2) {
                    int len = readVarint(payload, idx);
                    idx = readVarintEnd + len;
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {
            // best-effort parse; fall through to responding anyway
        }

        Grasscutter.getLogger()
                .info("DailyTaskUnknownReq(28078) field6={}", value);

        session.send(new PacketDailyTaskUnknownRsp());
    }

    private int readVarintEnd;

    private int readVarint(byte[] buf, int start) {
        long result = 0;
        int shift = 0;
        int idx = start;
        while (true) {
            byte b = buf[idx++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        readVarintEnd = idx;
        return (int) result;
    }
}
