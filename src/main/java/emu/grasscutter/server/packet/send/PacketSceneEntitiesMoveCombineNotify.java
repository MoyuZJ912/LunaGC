package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.EntityMoveInfoOuterClass.EntityMoveInfo;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 6.7 co-op movement forward: server pushes a batch of EntityMoveInfo to peers.
 *
 * <p>The 6.7 client no longer consumes forwarded CombatInvocationsNotify movement — it receives
 * peer movement through SceneEntitiesMoveCombineNotify (opcode 1679). The generated classes for
 * this message are absent from the repo, so the payload is hand-encoded from the 6.7 proto dump.
 *
 * <p>6.7 wire layout (GKBJJBGPGEA): {@code repeated EntityMoveInfo = 6; uint32 client_sequence_id
 * = 12;}. EntityMoveInfo field numbers are identical to the generated EntityMoveInfoOuterClass
 * (entity_id=1, motion_info=2, scene_time=3, reliable_seq=4, is_reliable=5), so the generated
 * {@link EntityMoveInfo} bytes are reused verbatim.
 */
public class PacketSceneEntitiesMoveCombineNotify extends BasePacket {

    static final int F_ENTITY_MOVE_INFO_LIST = 6; // repeated EntityMoveInfo
    static final int F_CLIENT_SEQUENCE_ID = 12; // uint32 client_sequence_id

    public PacketSceneEntitiesMoveCombineNotify(EntityMoveInfo moveInfo) {
        super(PacketOpcodes.SceneEntitiesMoveCombineNotify, true);
        var data = new ByteArrayOutputStream();
        writeBytesField(data, F_ENTITY_MOVE_INFO_LIST, moveInfo.toByteArray());
        this.setData(data.toByteArray());
    }

    public PacketSceneEntitiesMoveCombineNotify(List<EntityMoveInfo> moveInfos) {
        super(PacketOpcodes.SceneEntitiesMoveCombineNotify, true);
        var data = new ByteArrayOutputStream();
        for (var moveInfo : moveInfos) {
            writeBytesField(data, F_ENTITY_MOVE_INFO_LIST, moveInfo.toByteArray());
        }
        this.setData(data.toByteArray());
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
