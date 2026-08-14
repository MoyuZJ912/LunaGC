package emu.grasscutter.server.packet.send;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.EntityMoveInfoOuterClass.EntityMoveInfo;
import emu.grasscutter.net.proto.MotionInfoOuterClass.MotionInfo;
import emu.grasscutter.net.proto.SceneEntityMoveNotifyOuterClass.SceneEntityMoveNotify;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;
import java.io.ByteArrayOutputStream;

public class PacketSceneEntityMoveNotify extends BasePacket {

    public PacketSceneEntityMoveNotify(EntityMoveInfo moveInfo) {
        super(PacketOpcodes.SceneEntityMoveNotify, true);

        SceneEntityMoveNotify.Builder proto =
                SceneEntityMoveNotify.newBuilder()
                        .setMotionInfo(moveInfo.getMotionInfo())
                        .setEntityId(moveInfo.getEntityId())
                        .setSceneTime(moveInfo.getSceneTime())
                        .setReliableSeq(moveInfo.getReliableSeq());

        // 6.7 SceneEntityMoveNotify carries field 7 (KEMOPAIJJIG / 6.6 ICFJCKPMDGJ):
        // an int-coordinate motion snapshot the client actually uses to move the
        // entity. The generated class is missing this field, so inject it manually
        // (same encoding as GameEntity.injectIntMotionInfo, coords * 1000).
        injectIntMotionSnapshot(proto, moveInfo);

        this.setData(proto.build());
    }

    private static void injectIntMotionSnapshot(
            SceneEntityMoveNotify.Builder proto, EntityMoveInfo moveInfo) {
        try {
            MotionInfo motionInfo = moveInfo.getMotionInfo();
            Vector pos = motionInfo.getPos();
            Vector rot = motionInfo.getRot();

            ByteArrayOutputStream posOut = new ByteArrayOutputStream();
            CodedOutputStream posCos = CodedOutputStream.newInstance(posOut);
            posCos.writeInt32(1, Math.round(pos.getX() * 1000f));
            posCos.writeInt32(2, Math.round(pos.getY() * 1000f));
            posCos.writeInt32(3, Math.round(pos.getZ() * 1000f));
            posCos.flush();

            ByteArrayOutputStream rotOut = new ByteArrayOutputStream();
            CodedOutputStream rotCos = CodedOutputStream.newInstance(rotOut);
            rotCos.writeInt32(1, Math.round(rot.getX() * 1000f));
            rotCos.writeInt32(2, Math.round(rot.getY() * 1000f));
            rotCos.writeInt32(3, Math.round(rot.getZ() * 1000f));
            rotCos.flush();

            ByteArrayOutputStream msgOut = new ByteArrayOutputStream();
            CodedOutputStream msgCos = CodedOutputStream.newInstance(msgOut);
            msgCos.writeUInt32(1, moveInfo.getEntityId());
            msgCos.writeBytes(2, ByteString.copyFrom(posOut.toByteArray()));
            msgCos.writeBytes(3, ByteString.copyFrom(rotOut.toByteArray()));
            msgCos.writeEnum(4, motionInfo.getState().getNumber());
            msgCos.writeUInt32(5, moveInfo.getSceneTime());
            msgCos.flush();

            proto.mergeUnknownFields(
                    UnknownFieldSet.newBuilder()
                            .addField(
                                    7,
                                    UnknownFieldSet.Field.newBuilder()
                                            .addLengthDelimited(
                                                    ByteString.copyFrom(msgOut.toByteArray()))
                                            .build())
                            .build());
        } catch (Exception e) {
            // Fall back to float MotionInfo only; never break movement handling.
        }
    }
}
