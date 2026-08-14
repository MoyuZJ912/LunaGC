package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.ChangeGameTimeReqOuterClass.ChangeGameTimeReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketChangeGameTimeRsp;

@Opcodes(PacketOpcodes.ChangeGameTimeReq)
public class HandlerChangeGameTimeReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = ChangeGameTimeReq.parseFrom(payload);

        var player = session.getPlayer();
        var world = player.getWorld();
        world.changeTime(req.getGameTime(), req.getExtraDays());
        // Broadcast the new time so the client's clock and scene actually update.
        world.updateTime();
        player.sendPacket(new PacketChangeGameTimeRsp(player, req.getExtraDays()));
    }
}
