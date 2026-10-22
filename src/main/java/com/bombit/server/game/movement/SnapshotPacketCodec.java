package com.bombit.server.game.movement;

import com.bombit.common.protocol.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.TileType;
import java.io.*;
import java.util.*;
import com.bombit.common.dto.BombFrame;
import com.bombit.common.dto.MatchFrame;
import com.bombit.server.game.model.TilePosition;

/**
 * Codec snapshot đề xuất của TV2: dữ liệu nhị phân big-endian được bọc Base64 trong JSON.
 * PacketEncoder chung vẫn xử lý length/type/payload; codec này không tự gửi socket.
 * Thứ tự write và read phải đối xứng tuyệt đối; mỗi collection bắt đầu bằng count int32.
 * Enum lưu ordinal nên thay thứ tự enum hoặc layout phải đổi phiên bản.
 * Base64 là mã hóa biểu diễn, không phải thuật toán nén; phần giảm dữ liệu đến từ delta.
 * Envelope phải đúng dạng encode tạo ra; đây chưa phải bộ phân tích JSON tổng quát.
 */
public final class SnapshotPacketCodec {
    private static final String PREFIX = "{\"snapshotVersion\":2,\"data\":\"";
    private static final String PREFIX3 = "{\"snapshotVersion\":3,\"data\":\"";
    private static final String PREFIX4 = "{\"snapshotVersion\":4,\"data\":\"";
    private static final String PREFIX5 = "{\"snapshotVersion\":5,\"data\":\"";
    private static final String SUFFIX = "\"}";
    // Mục đích: Khởi tạo SnapshotPacketCodec và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    private SnapshotPacketCodec() { }

    // Mục đích: Ghi snapshot theo thứ tự cố định vào byte buffer, sau đó bọc Base64 trong Packet GAME_SNAPSHOT. IOException có thể xảy ra khi writeUTF vượt giới hạn chuỗi.
    public static Packet encode(GameStateUpdate u) throws IOException {
        if(u.getMatchFrame()!=null&&u.getBombFrame()==null)throw new IllegalArgumentException("Match frame requires bomb frame");
        // 1. Bộ đệm chỉ giữ payload snapshot; header length/type do PacketEncoder thêm sau.
        // DataOutputStream dùng big-endian; writeLong/writeDouble là 8 byte, writeInt là 4 byte.
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        // 2. Metadata giúp client biết cách ghép update vào phiên bản đang giữ.
        // baseTick = -1 đối với full; delta phải dựa trên một tick đã được publish trước đó.
        out.writeByte(u.isFullState() ? 0 : 1);
        out.writeLong(u.getTick()); out.writeLong(u.getBaseTick());
        out.writeInt(u.getMapWidth()); out.writeInt(u.getMapHeight()); out.writeInt(u.getTileSize());
        out.writeByte(u.getMatchState().ordinal()); out.writeBoolean(u.isMatchStateChanged());
        // 3. Hai lớp map: full truyền từng tile một byte, delta truyền x/y/type của ô đổi.
        // Khi ít ô thay đổi, bỏ toàn bộ map tiết kiệm dung lượng; khi rất nhiều ô đổi,
        // delta có thể lớn hơn full vì mỗi tile change còn có tọa độ (hiện chưa tự chọn loại nhỏ hơn).
        out.writeInt(u.mapTiles().size());
        for (var tile : u.mapTiles()) out.writeByte(tile.ordinal());
        out.writeInt(u.getTileChanges().size());
        for (var tile : u.getTileChanges()) {
            out.writeInt(tile.getX()); out.writeInt(tile.getY()); out.writeByte(tile.tileType().ordinal());
        }
        // 4. Dùng cùng layout cho player full và player thay đổi; loại update quyết định ý nghĩa.
        // Một player đổi bất kỳ trường nào sẽ gửi cả record player, chưa nén riêng từng field.
        var players = u.isFullState() ? u.getPlayers() : u.getPlayerChanges();
        out.writeInt(players.size());
        for (var p : players) {
            out.writeInt(p.getId()); out.writeDouble(p.getX()); out.writeDouble(p.getY());
            out.writeDouble(p.getWidth()); out.writeDouble(p.getHeight()); out.writeDouble(p.getSpeed());
            out.writeByte(p.direction().ordinal()); out.writeBoolean(p.isMoving()); out.writeBoolean(p.isAlive()); out.writeLong(p.getLastProcessedInput());
        }
        // 5. Danh sách xóa là cần thiết: chỉ thiếu một player trong delta không có nghĩa nó bị xóa.
        out.writeInt(u.getRemovedPlayerIds().size());
        for (int id : u.getRemovedPlayerIds()) out.writeInt(id);
        // 6. Bom/item tách khỏi map nền để cùng một ô có thể có nhiều lớp dữ liệu.
        // writeUTF dùng modified UTF-8 có length uint16, không phải UTF-8 thuần của PacketEncoder.
        out.writeInt(u.getEntityChanges().size());
        for (var e : u.getEntityChanges()) {
            out.writeByte(e.kind().ordinal()); out.writeInt(e.id()); out.writeInt(e.tileX()); out.writeInt(e.tileY());
            out.writeUTF(e.ownerId()); out.writeInt(e.variant()); out.writeLong(e.expiresAtMillis());
        }
        out.writeInt(u.getRemovedEntityKeys().size());
        for (String key : u.getRemovedEntityKeys()) out.writeUTF(key);
        if(u.getBombFrame()!=null) {
            var frame=u.getBombFrame();out.writeLong(frame.serverTimeMillis());
            out.writeInt(frame.explosions().size());
            for(var e:frame.explosions()) {
                out.writeInt(e.bombId());out.writeLong(e.startedAtMillis());out.writeLong(e.visibleUntilMillis());
                out.writeInt(e.cells().size());
                for(var cell:e.cells().stream().sorted(Comparator.comparingInt(TilePosition::y).thenComparingInt(TilePosition::x)).toList()) {
                    out.writeInt(cell.x());out.writeInt(cell.y());
                }
            }
        }
        if(u.getMatchFrame()!=null){
            var frame=u.getMatchFrame();out.writeInt(frame.remainingSeconds());out.writeInt(frame.players().size());
            for(var stats:frame.players()){
                out.writeInt(stats.playerId());out.writeInt(stats.bombLimit());out.writeInt(stats.range());
                out.writeInt(stats.lives());out.writeBoolean(stats.shield());out.writeInt(stats.respawns());
            }
            out.writeBoolean(frame.result()!=null);
            if(frame.result()!=null)out.writeUTF(GameplayPacketCodec.result(frame.result()).getJsonPayload());
        }
        // 7. Base64 giữ payload hợp lệ trong lớp JSON hiện có, đổi lại tăng dung lượng khoảng 1/3.
        // Benchmark đo sau bước này nên đã tính chi phí Base64 và JSON envelope.
        return new Packet(PacketType.GAME_SNAPSHOT, (u.getMatchFrame()!=null?PREFIX5:u.getBombFrame()==null?PREFIX:PREFIX3) + Base64.getEncoder().encodeToString(bytes.toByteArray()) + SUFFIX);
    }

    /** Full-sync response using v2/v3/v5 according to the update's optional gameplay frames. */
    public static Packet encodeFullSync(GameStateUpdate update) throws IOException {
        if (!update.isFullState()) throw new IllegalArgumentException("Full sync requires a full snapshot");
        return new Packet(PacketType.FULL_STATE_SYNC, encode(update).getJsonPayload());
    }

    // Mục đích: Kiểm tra envelope/phiên bản rồi đọc các trường theo đúng thứ tự encode. Từ chối header sai, collection bất hợp lệ, enum lạ và byte thừa; tọa độ tile được replica kiểm tra tiếp.
    public static GameStateUpdate decode(Packet packet) throws IOException {
        // 1. Chỉ chấp nhận đúng message và envelope v2/v3/v4 canonical mà codec này phát ra.
        // JSON tương đương nhưng thêm khoảng trắng/đổi thứ tự thuộc tính hiện cũng bị từ chối.
        String json = packet.getJsonPayload();
        boolean v5=json.startsWith(PREFIX5),v4=json.startsWith(PREFIX4)||v5,v3=json.startsWith(PREFIX3)||v4;
        if ((packet.getType() != PacketType.GAME_SNAPSHOT && packet.getType() != PacketType.FULL_STATE_SYNC)
                || json.length() > PacketEncoder.MAX_PACKET_LENGTH
                || (!json.startsWith(PREFIX) && !v3) || !json.endsWith(SUFFIX)) throw new IOException("Invalid snapshot envelope");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(
                    json.substring(PREFIX.length(), json.length() - SUFFIX.length()))));
            int type = in.readUnsignedByte();
            if (type > 1) throw new IOException("Unknown snapshot type");
            if (packet.getType() == PacketType.FULL_STATE_SYNC && type != 0) {
                throw new IOException("Full sync cannot contain a delta");
            }
            // 2. Xác thực kích thước trước khi dựng collection; ép long khi nhân để tránh tràn int.
            long tick = in.readLong(), base = in.readLong();
            int w = in.readInt(), h = in.readInt(), size = in.readInt();
            if (tick < 0 || w <= 0 || h <= 0 || size <= 0 || (long) w * h > 1_000_000
                    || (type == 1 && (base < 0 || base >= tick))) throw new IOException("Invalid snapshot header");
            var match = value(GameState.MatchState.values(), in.readUnsignedByte());
            boolean changed = in.readBoolean();
            // 3. Đọc từng collection theo đúng thứ tự encode; stream thiếu byte sẽ ném IOException.
            var map = new ArrayList<TileType>();
            for (int n = count(in); n > 0; n--) map.add(storedTile(in.readUnsignedByte()));
            var tiles = new ArrayList<GameStateUpdate.TileChange>();
            for (int n = count(in); n > 0; n--) tiles.add(new GameStateUpdate.TileChange(
                    in.readInt(), in.readInt(), storedTile(in.readUnsignedByte())));
            var players = new ArrayList<GameStateUpdate.PlayerData>();
            for (int n = count(in); n > 0; n--) {
                int id = in.readInt();
                double x = in.readDouble(), y = in.readDouble(), pw = in.readDouble(), ph = in.readDouble(), speed = in.readDouble();
                // Không để NaN/vô cực lọt vào render hoặc các phép tính vị trí của client.
                if (id <= 0 || !Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0
                        || !Double.isFinite(pw) || !Double.isFinite(ph) || !Double.isFinite(speed)
                        || pw <= 0 || ph <= 0 || speed <= 0) throw new IOException("Invalid player");
                players.add(new GameStateUpdate.PlayerData(id, x, y, pw, ph, speed,
                        value(com.bombit.server.game.model.Direction.values(), in.readUnsignedByte()), in.readBoolean(), in.readBoolean(), in.readLong()));
            }
            var removed = new ArrayList<Integer>();
            for (int n = count(in); n > 0; n--) removed.add(in.readInt());
            var entities = new ArrayList<EntityData>();
            for (int n = count(in); n > 0; n--) entities.add(new EntityData(
                    value(EntityData.Kind.values(), in.readUnsignedByte()), in.readInt(), in.readInt(), in.readInt(),
                    in.readUTF(), in.readInt(), in.readLong()));
            var removedEntities = new ArrayList<String>();
            for (int n = count(in); n > 0; n--) removedEntities.add(in.readUTF());
            BombFrame bombFrame=null;
            if(v3) {
                long serverTime=in.readLong();var effects=new ArrayList<BombFrame.Explosion>();
                for(int n=count(in);n>0;n--) {
                    int id=in.readInt();long start=in.readLong(),end=in.readLong();var cells=new HashSet<TilePosition>();
                    for(int c=count(in);c>0;c--) {
                        int x=in.readInt(),y=in.readInt();
                        if(x<0||y<0||x>=w||y>=h||!cells.add(new TilePosition(x,y))) throw new IOException("Invalid explosion cell");
                    }
                    effects.add(new BombFrame.Explosion(id,start,end,cells));
                }
                bombFrame=new BombFrame(serverTime,effects);
            }
            MatchFrame matchFrame=null;
            if(v4){
                int remaining=in.readInt();var stats=new ArrayList<MatchFrame.Stats>();
                for(int n=count(in);n>0;n--){
                    int id=in.readInt(),quota=in.readInt(),range=in.readInt();
                    stats.add(v5?new MatchFrame.Stats(id,quota,range,in.readInt(),in.readBoolean(),in.readInt()):new MatchFrame.Stats(id,quota,range));
                }
                var result=in.readBoolean()?GameplayPacketCodec.decodeResult(new Packet(PacketType.GAME_OVER,in.readUTF())):null;
                matchFrame=new MatchFrame(remaining,stats,result);
                if(result!=null&&(result.tick()>tick||match!=GameState.MatchState.FINISHED))throw new IOException("Invalid final result");
            }
            // 4. Kiểm tra tính nhất quán full/delta và không chấp nhận byte dư sau body.
            // Các giới hạn tọa độ tile/entity được ClientWorldState kiểm tra trước khi áp dụng.
            if (in.available() != 0 || (type == 0 && (map.size() != (long) w * h || base != -1
                    || !tiles.isEmpty() || !removed.isEmpty() || !removedEntities.isEmpty()))
                    || (type == 1 && !map.isEmpty())) throw new IOException("Invalid snapshot body");
            return (type == 0 ? GameStateUpdate.fullStateModel(tick, w, h, size, match, map, players)
                    : GameStateUpdate.delta(tick, base, w, h, size, match, changed, tiles, players, removed))
                    .withEntities(entities, removedEntities).withBombFrame(bombFrame).withMatchFrame(matchFrame);
        } catch (IllegalArgumentException e) { throw new IOException("Malformed snapshot", e); }
    }
    // Mục đích: Đọc số phần tử và giới hạn theo byte còn lại để ngăn count âm hoặc quá lớn. Đây là kiểm tra sơ bộ; đọc thiếu trường vẫn gây EOFException.
    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > in.available()) throw new IOException("Invalid collection length");
        return n;
    }
    private static TileType storedTile(int ordinal) throws IOException {
        TileType tile = value(TileType.values(), ordinal);
        if (tile == TileType.OUT_OF_BOUNDS) throw new IOException("Invalid stored tile");
        return tile;
    }
    private static <T> T value(T[] values, int index) throws IOException {
        if (index >= values.length) throw new IOException("Unknown enum value");
        return values[index];
    }
}
