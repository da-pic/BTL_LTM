package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.item.ItemType;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Binary v6 Classic/Shadow: cùng header, phần mode có kiểu riêng; không fallback sang v5. */
public final class SnapshotV6Codec {
    private SnapshotV6Codec() { }
    private static int entityCode(EntityData.Kind kind){return switch(kind){case BOMB->0;case ITEM->1;case SCAN_PENDING->2;case SCAN_BEACON->3;};}
    private static EntityData.Kind entityKind(int code)throws IOException{return switch(code){case 0->EntityData.Kind.BOMB;case 1->EntityData.Kind.ITEM;case 2->EntityData.Kind.SCAN_PENDING;case 3->EntityData.Kind.SCAN_BEACON;default->throw new IOException("Unknown entity");};}
    private static int tile(TileType t){return switch(t){case EMPTY->0;case HARD_WALL->1;case BREAKABLE_BLOCK->2;default->throw new IllegalArgumentException("Stored boundary tile");};}
    private static TileType tile(int n)throws IOException{return switch(n){case 0->TileType.EMPTY;case 1->TileType.HARD_WALL;case 2->TileType.BREAKABLE_BLOCK;default->throw new IOException("Unknown tile");};}
    private static int direction(Direction d){return switch(d){case UP->0;case DOWN->1;case LEFT->2;case RIGHT->3;};}
    private static Direction direction(int n)throws IOException{return switch(n){case 0->Direction.UP;case 1->Direction.DOWN;case 2->Direction.LEFT;case 3->Direction.RIGHT;default->throw new IOException("Unknown direction");};}
    private static int itemCode(int legacy){return switch(ItemType.values()[legacy]){case BOMB_UP->0;case FIRE_UP->1;case SPEED_UP->2;case LIFE_UP->3;case SHIELD->4;};}
    private static int legacyItem(int n)throws IOException{return switch(n){case 0->ItemType.BOMB_UP.ordinal();case 1->ItemType.FIRE_UP.ordinal();case 2->ItemType.SPEED_UP.ordinal();case 3->ItemType.LIFE_UP.ordinal();case 4->ItemType.SHIELD.ordinal();default->throw new IOException("Unknown item");};}
    private static void blob(DataOutputStream out,Object value)throws IOException{byte[] raw=JsonV3.encode(value).getBytes(StandardCharsets.UTF_8);if(raw.length>16384)throw new IOException("Blob limit");out.writeInt(raw.length);out.write(raw);}
    private static Map<String,Object> blob(DataInputStream in)throws IOException{int n=count(in,16384);byte[] raw=in.readNBytes(n);if(raw.length!=n)throw new EOFException();
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();return object(text,16384);}
        catch(java.nio.charset.CharacterCodingException e){throw new IOException("Bad UTF-8",e);}}
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max||n>in.available())throw new IOException("Count limit");return n;}
    private static boolean bool(DataInputStream in)throws IOException{int b=in.readUnsignedByte();if(b>1)throw new IOException("Bad boolean");return b==1;}
    private static int id(DataInputStream in)throws IOException{int id=in.readInt();if(id<=0)throw new IOException("Invalid ID");return id;}
    private static long relative(long value,long origin){return Math.max(0,value-origin);}

    /** Origin chỉ dùng khi encode frame server; dữ liệu decode đã dùng clock tương đối match. */
    public static Packet encode(RecipientSnapshotV6 frame,long epochOrigin,MatchContext context)throws IOException{
        var u=frame.update();var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(u.isFullState()?0:1);out.writeLong(u.getTick());out.writeLong(u.getBaseTick());out.writeLong(frame.simulationTick());out.writeLong(frame.matchTimeMillis());
        out.writeShort(u.getMapWidth());out.writeShort(u.getMapHeight());out.writeShort(u.getTileSize());out.writeByte(u.getMatchState()==GameState.MatchState.RUNNING?0:1);
        out.writeInt(u.mapTiles().size());for(var t:u.mapTiles())out.writeByte(tile(t));
        out.writeInt(u.getTileChanges().size());for(var t:u.getTileChanges()){out.writeShort(t.getX());out.writeShort(t.getY());out.writeByte(tile(t.tileType()));}
        out.writeByte(frame.publicPlayers().size());for(var p:frame.publicPlayers()){out.writeInt(p.playerId());out.writeByte(p.lifeState());out.writeByte(p.connectionState());}
        var players=u.isFullState()?u.getPlayers():u.getPlayerChanges();out.writeByte(players.size());
        for(var p:players){var stats=u.getMatchFrame().players().stream().filter(s->s.playerId()==p.getId()).findFirst().orElseThrow();
            out.writeInt(p.getId());out.writeDouble(p.getX());out.writeDouble(p.getY());out.writeDouble(p.getWidth());out.writeDouble(p.getHeight());out.writeDouble(p.getSpeed());out.writeByte(direction(p.direction()));out.writeBoolean(p.isMoving());
            out.writeInt(stats.bombLimit());out.writeInt(stats.range());out.writeInt(stats.lives());out.writeBoolean(stats.shield());out.writeInt(stats.respawns());}
        out.writeByte(u.getRemovedPlayerIds().size());for(int p:u.getRemovedPlayerIds())out.writeInt(p);
        out.writeInt(u.getEntityChanges().size());for(var e:u.getEntityChanges()){
            out.writeByte(entityCode(e.kind()));out.writeInt(e.id());out.writeShort(e.tileX());out.writeShort(e.tileY());
            out.writeInt(e.kind()==EntityData.Kind.ITEM?0:context.playerId(e.ownerId()).orElseThrow());out.writeInt(e.kind()==EntityData.Kind.ITEM?itemCode(e.variant()):e.variant());
            out.writeLong(e.kind()==EntityData.Kind.ITEM?-1:e.kind()==EntityData.Kind.BOMB?relative(e.expiresAtMillis(),epochOrigin):e.expiresAtMillis());}
        out.writeInt(u.getRemovedEntityKeys().size());for(var key:u.getRemovedEntityKeys()){var parts=key.split(":");out.writeByte(entityCode(EntityData.Kind.valueOf(parts[0])));out.writeInt(Integer.parseInt(parts[1]));}
        var explosions=u.getBombFrame().explosions();out.writeShort(explosions.size());
        for(var e:explosions){out.writeInt(e.bombId());out.writeLong(Math.min(frame.matchTimeMillis(),relative(e.startedAtMillis(),epochOrigin)));out.writeLong(Math.max(frame.matchTimeMillis()+1,relative(e.visibleUntilMillis(),epochOrigin)));out.writeShort(e.cells().size());
            for(var c:e.cells().stream().sorted(Comparator.comparingInt(TilePosition::y).thenComparingInt(TilePosition::x)).toList()){out.writeShort(c.x());out.writeShort(c.y());}}
        if(frame instanceof ShadowSnapshotV6 shadow){
            blob(out,ShadowModeCodec.mode(shadow));out.writeLong(frame.lastProcessedInput());out.writeBoolean(frame.inputEnabled());out.writeBoolean(true);out.writeLong(shadow.scanReadyAtMillis());
            var result=shadow.objectives().result();out.writeBoolean(result!=null);if(result!=null)blob(out,ShadowModeCodec.result(result));
        }else{
            if(u.getEntityChanges().stream().anyMatch(e->entityCode(e.kind())>1))throw new IOException("Scan in Classic");
            blob(out,frame instanceof ParadoxSnapshotV6 p?ParadoxCodec.frame(p.paradox()):Map.of("type","CLASSIC"));out.writeLong(frame.lastProcessedInput());out.writeBoolean(frame.inputEnabled());out.writeBoolean(false);
            var result=u.getMatchFrame().result();var team=u.getMatchFrame().teamResult();out.writeBoolean(result!=null||team!=null);
            var mode=frame instanceof ParadoxSnapshotV6?GameMode.PARADOX:GameMode.CLASSIC;
            if(team!=null)blob(out,GameplayV3Codec.teamResultBody(team,mode));else if(result!=null)blob(out,GameplayV3Codec.resultBody(result,mode));
        }
        if(bytes.size()>524288)throw new IOException("Snapshot limit");
        return packet(u.isFullState()?PacketType.FULL_STATE_SYNC:PacketType.GAME_SNAPSHOT,body("snapshotVersion",6,"data",Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }

    /** Entry point Classic giữ API cũ; entry point Shadow vẫn kiểm mode metadata trước decode. */
    public static ClassicSnapshotV6 decode(Packet packet,MatchStartMetadata metadata)throws IOException{
        if(metadata.configuration().gameMode()!=GameMode.CLASSIC)throw new IOException("Not Classic metadata");
        return (ClassicSnapshotV6)decodeRecipient(packet,metadata);
    }
    public static ShadowSnapshotV6 decodeShadow(Packet packet,MatchStartMetadata metadata)throws IOException{
        if(metadata.configuration().gameMode()!=GameMode.SHADOW_RELAY)throw new IOException("Not Shadow metadata");
        return (ShadowSnapshotV6)decodeRecipient(packet,metadata);
    }
    public static RecipientSnapshotV6 decodeRecipient(Packet packet,MatchStartMetadata metadata)throws IOException{
        try{
            boolean shadow=metadata.configuration().gameMode()==GameMode.SHADOW_RELAY;
            boolean paradox=metadata.configuration().gameMode()==GameMode.PARADOX;
            if(!shadow&&!paradox&&metadata.configuration().gameMode()!=GameMode.CLASSIC)throw new IOException("Unsupported mode");
            if(packet.getType()!=PacketType.GAME_SNAPSHOT&&packet.getType()!=PacketType.FULL_STATE_SYNC)throw new IOException("Wrong snapshot type");
            var json=fields(object(packet.getJsonPayload(),1048574),"snapshotVersion","data");if(integer(json,"snapshotVersion",0,99)!=6)throw new IOException("Unsupported snapshot");
            String data=text(json,"data",699052);byte[] raw=Base64.getDecoder().decode(data);
            if(raw.length>524288||!Base64.getEncoder().encodeToString(raw).equals(data))throw new IOException("Noncanonical/large Base64");
            var in=new DataInputStream(new ByteArrayInputStream(raw));int kind=in.readUnsignedByte();long tick=in.readLong(),base=in.readLong(),sim=in.readLong(),time=in.readLong();
            int w=in.readUnsignedShort(),h=in.readUnsignedShort(),size=in.readUnsignedShort(),stateCode=in.readUnsignedByte();
            if(kind>1||tick<1||sim<0||(paradox?sim>tick:sim!=tick)||time<0||w==0||h==0||size==0||(long)w*h>4096||stateCode>1||
                    (kind==0?base!=-1:base<0||base>=tick)||(kind==0)!=(packet.getType()==PacketType.FULL_STATE_SYNC))throw new IOException("Invalid header");
            var cfg=metadata.configuration().effectiveRules().common();if(w!=cfg.width()||h!=cfg.height()||size!=cfg.tilePixels())throw new IOException("Geometry mismatch");
            var state=stateCode==0?GameState.MatchState.RUNNING:GameState.MatchState.FINISHED;
            var tiles=new ArrayList<TileType>();for(int n=count(in,w*h);n>0;n--)tiles.add(tile(in.readUnsignedByte()));
            if(tiles.size()!=(kind==0?w*h:0))throw new IOException("Map length");
            var changes=new ArrayList<GameStateUpdate.TileChange>();var tileKeys=new HashSet<TilePosition>();
            for(int n=count(in,w*h);n>0;n--){int x=in.readUnsignedShort(),y=in.readUnsignedShort();if(x>=w||y>=h||!tileKeys.add(new TilePosition(x,y)))throw new IOException("Tile bounds/duplicate");changes.add(new GameStateUpdate.TileChange(x,y,tile(in.readUnsignedByte())));}
            var roster=new HashMap<Integer,ClassicSnapshotV6.PublicPlayer>();int count=in.readUnsignedByte();if(count<1||count>4)throw new IOException("Roster count");
            for(int i=0;i<count;i++){var p=new ClassicSnapshotV6.PublicPlayer(id(in),in.readUnsignedByte(),in.readUnsignedByte());if(roster.put(p.playerId(),p)!=null)throw new IOException("Duplicate player");}
            if(!roster.keySet().equals(metadata.players().stream().map(MatchStartMetadata.Member::playerId).collect(java.util.stream.Collectors.toSet())))throw new IOException("Roster mismatch");
            var players=new ArrayList<GameStateUpdate.PlayerData>();var stats=new ArrayList<MatchFrame.Stats>();var seen=new HashSet<Integer>();count=in.readUnsignedByte();if(count>4)throw new IOException("Player count");
            for(int i=0;i<count;i++){int id=id(in);double x=in.readDouble(),y=in.readDouble(),pw=in.readDouble(),ph=in.readDouble(),speed=in.readDouble();var dir=direction(in.readUnsignedByte());boolean moving=bool(in);
                int quota=in.readInt(),range=in.readInt(),lives=in.readInt();boolean shield=bool(in);int respawns=in.readInt();
                if(!roster.containsKey(id)||!seen.add(id)||!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||!Double.isFinite(pw)||!Double.isFinite(ph)||!Double.isFinite(speed)
                        ||pw!=cfg.hitboxPixels()||ph!=cfg.hitboxPixels()||speed<cfg.initialSpeed()||speed>cfg.maxSpeed()||x+pw>w*(double)size||y+ph>h*(double)size
                        ||quota<1||quota>cfg.maxBombQuota()||range<1||range>cfg.maxBombRange()||lives<0||lives>cfg.maxLives()||respawns<0
                        ||(roster.get(id).lifeState()==0)!=(lives>0))throw new IOException("Invalid player detail");
                players.add(new GameStateUpdate.PlayerData(id,x,y,pw,ph,speed,dir,moving,lives>0,0));stats.add(new MatchFrame.Stats(id,quota,range,lives,shield,respawns));}
            var removed=new ArrayList<Integer>();count=in.readUnsignedByte();if(count>4)throw new IOException("Removal count");var removedIds=new HashSet<Integer>();
            for(int i=0;i<count;i++){int id=id(in);if(!roster.containsKey(id)||seen.contains(id)||!removedIds.add(id))throw new IOException("Invalid removal");removed.add(id);}
            // M0 truyền đủ detail còn hiện hữu của Classic mỗi update; không giả stats unknown.
            var expected=roster.values().stream().filter(p->p.lifeState()!=2).map(ClassicSnapshotV6.PublicPlayer::playerId).collect(java.util.stream.Collectors.toSet());
            if(!shadow&&!seen.equals(expected))throw new IOException("Incomplete Classic details");
            var entities=new ArrayList<EntityData>();var entityKeys=new HashSet<String>();
            for(int n=count(in,1024);n>0;n--){int code=in.readUnsignedByte(),id=id(in),x=in.readUnsignedShort(),y=in.readUnsignedShort(),owner=in.readInt(),variant=in.readInt();long expiry=in.readLong();
                if(code>(shadow?3:1)||x>=w||y>=h||!entityKeys.add(code+":"+id)||code==1&&(owner!=0||expiry!=-1)||code!=1&&!roster.containsKey(owner)||code==0&&(variant<1||variant>cfg.maxBombRange()||expiry<0)||code>=2&&(variant!=0||expiry<=time))throw new IOException("Invalid entity");
                String ownerId=code==1?"":metadata.players().stream().filter(p->p.playerId()==owner).findFirst().orElseThrow().sessionId();
                entities.add(new EntityData(entityKind(code),id,x,y,ownerId,code==1?legacyItem(variant):variant,expiry));}
            var removedEntities=new ArrayList<String>();var removedKeys=new HashSet<String>();for(int n=count(in,1024);n>0;n--){int code=in.readUnsignedByte(),id=id(in);String key=code+":"+id;
                if(code>(shadow?3:1)||entityKeys.contains(key)||!removedKeys.add(key))throw new IOException("Invalid entity removal");removedEntities.add(entityKind(code).name()+":"+id);}
            var explosions=new ArrayList<BombFrame.Explosion>();var exploded=new HashSet<Integer>();count=in.readUnsignedShort();if(count>256)throw new IOException("Effect count");
            for(int i=0;i<count;i++){int id=id(in);long start=in.readLong(),end=in.readLong();int n=in.readUnsignedShort();if(!exploded.add(id)||start<0||end<=start||time<start||time>=end||n<1||n>w*h)throw new IOException("Invalid effect");var cells=new HashSet<TilePosition>();
                for(int j=0;j<n;j++){int x=in.readUnsignedShort(),y=in.readUnsignedShort();if(x>=w||y>=h||!cells.add(new TilePosition(x,y)))throw new IOException("Effect bounds");}explosions.add(new BombFrame.Explosion(id,start,end,cells));}
            var mode=blob(in);if(!shadow&&!paradox){fields(mode,"type");if(!"CLASSIC".equals(mode.get("type")))throw new IOException("Wrong mode frame");}
            long ack=in.readLong();boolean enabled=bool(in),hasCooldown=bool(in);if(ack<0||hasCooldown!=shadow)throw new IOException("Invalid self state");
            long cooldown=hasCooldown?in.readLong():0;if(cooldown<0)throw new IOException("Invalid cooldown");
            boolean hasResult=bool(in);boolean teamFormat=metadata.configuration().isTeamMatch()&&!shadow;
            var result=hasResult&&!shadow&&!teamFormat?GameplayV3Codec.result(blob(in),paradox?GameMode.PARADOX:GameMode.CLASSIC):null;
            var matchTeamResult=hasResult&&teamFormat?GameplayV3Codec.teamResult(blob(in),metadata.configuration().gameMode()):null;
            var teamResult=hasResult&&shadow?ShadowModeCodec.result(blob(in)):null;
            if((stateCode==1)!=hasResult||result!=null&&result.tick()>tick||matchTeamResult!=null&&matchTeamResult.tick()>tick||teamResult!=null&&teamResult.tick()>tick||in.available()!=0)throw new IOException("Invalid result/trailing bytes");
            int local=metadata.stream().recipientPlayerId();players.replaceAll(p->new GameStateUpdate.PlayerData(p.getId(),p.getX(),p.getY(),p.getWidth(),p.getHeight(),p.getSpeed(),p.direction(),p.isMoving(),p.isAlive(),p.getId()==local?ack:0));
            if(enabled&&(stateCode!=0||roster.get(local).lifeState()!=0||roster.get(local).connectionState()!=0))throw new IOException("Invalid input gate");
            if(kind==0&&(!changes.isEmpty()||!removed.isEmpty()||!removedEntities.isEmpty()))throw new IOException("Invalid full changes");
            var update=(kind==0?GameStateUpdate.fullStateModel(tick,w,h,size,state,tiles,players):GameStateUpdate.delta(tick,base,w,h,size,state,true,changes,players,removed))
                    .withEntities(entities,removedEntities).withBombFrame(new BombFrame(time,explosions)).withMatchFrame(new MatchFrame((int)Math.max(0,(cfg.durationMillis()-time+999)/1000),stats,result,matchTeamResult));
            var publicPlayers=roster.values().stream().sorted(Comparator.comparingInt(ClassicSnapshotV6.PublicPlayer::playerId)).toList();
            if(shadow){
                var parsed=ShadowModeCodec.mode(mode,tick,time,w,h,cfg.durationMillis(),teamResult);
                TeamId team=metadata.players().stream().filter(p->p.playerId()==local).findFirst().orElseThrow().team();
                for(var member:metadata.players())if(member.team()==team&&roster.get(member.playerId()).lifeState()!=2&&!seen.contains(member.playerId()))throw new IOException("Missing teammate");
                for(var p:players){var member=metadata.players().stream().filter(m->m.playerId()==p.getId()).findFirst().orElseThrow();
                    if(member.team()!=team&&!parsed.vision().contains(TilePosition.containing(p.getX()+p.getWidth()/2,p.getY()+p.getHeight()/2,size)))throw new IOException("Hidden enemy detail");}
                for(var entity:entities)if(entity.kind()==EntityData.Kind.BOMB){
                    var owner=metadata.players().stream().filter(p->p.sessionId().equals(entity.ownerId())).findFirst().orElseThrow();
                    if(owner.team()!=team&&!parsed.vision().contains(new TilePosition(entity.tileX(),entity.tileY())))throw new IOException("Hidden enemy bomb");
                }
                return new ShadowSnapshotV6(update,sim,time,publicPlayers,ack,enabled,parsed.frame(),parsed.vision(),cooldown);
            }
            if(paradox)return new ParadoxSnapshotV6(update,sim,time,publicPlayers,ack,enabled,ParadoxCodec.frame(mode,sim,metadata));
            return new ClassicSnapshotV6(update,sim,time,publicPlayers,ack,enabled);
        }catch(IllegalArgumentException|NoSuchElementException error){throw new IOException("Malformed v6 snapshot",error);}
    }
}
