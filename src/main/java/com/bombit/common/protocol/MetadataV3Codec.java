package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import java.util.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Schema v3 strict cho config/lobby/started; không đọc GameState hoặc tạo runtime. */
public final class MetadataV3Codec {
    private MetadataV3Codec() { }
    private static final String[] COMMON={"width","height","tilePixels","tickTargetMillis","inputStepMillis","hitboxPixels","initialSpeed","speedIncrement","maxSpeed","initialLives","maxLives","normalFuseMillis","initialBombQuota","maxBombQuota","initialBombRange","maxBombRange","itemDropProbability","durationMillis","sessionGraceMillis","clientRecoveryBudgetMillis"};
    private static int n(Map<String,Object> m,String key){return (int)integer(m,key,0,Integer.MAX_VALUE);}
    /** Payload cũ không có matchFormat nhận mặc định theo mode; null/giá trị lạ vẫn bị từ chối. */
    public static MatchFormat matchFormat(Map<String,Object> fields,GameMode mode){
        return fields.containsKey("matchFormat")?MatchFormat.valueOf(text(fields,"matchFormat",16)):MatchFormat.defaultFor(mode);
    }
    private static Map<String,Object> formatFields(Object raw){
        var normalized=new LinkedHashMap<>(map(raw));var mode=GameMode.valueOf(text(normalized,"gameMode",32));
        normalized.putIfAbsent("matchFormat",matchFormat(normalized,mode).name());return normalized;
    }
    public static Map<String,Object> configuration(MatchConfiguration c){
        return body("configurationId",c.configurationId().toString(),"gameMode",c.gameMode().name(),"matchFormat",c.matchFormat().name(),"rulesVersion",c.rulesVersion(),
                "presetId",c.presetId().name(),"profile",c.profile().name(),"layoutId",c.layoutId(),"effectiveRules",c.effectiveRules().toWireFields());
    }
    public static MatchConfiguration configuration(Object raw){
        var c=fields(formatFields(raw),"configurationId","gameMode","matchFormat","rulesVersion","presetId","profile","layoutId","effectiveRules");
        var mode=GameMode.valueOf(text(c,"gameMode",32));
        return new MatchConfiguration(UUID.fromString(uuid(c,"configurationId")),mode,text(c,"rulesVersion",64),
                MatchPreset.valueOf(text(c,"presetId",64)),MatchProfile.valueOf(text(c,"profile",16)),text(c,"layoutId",64),rules(mode,c.get("effectiveRules")),matchFormat(c,mode));
    }
    private static EffectiveRules rules(GameMode mode,Object value){
        var m=map(value);var keys=new HashSet<>(List.of(COMMON));
        if(mode==GameMode.SHADOW_RELAY)keys.addAll(Set.of("layoutId","players","teamSize","friendlyFire","normalVisionRadius","captureRadius","captureTicks","scorePeriodTicks","targetScore","scan"));
        if(!m.keySet().equals(keys))throw new IllegalArgumentException("Invalid rule fields");
        var common=new EffectiveRules.Common(n(m,"width"),n(m,"height"),n(m,"tilePixels"),n(m,"tickTargetMillis"),n(m,"inputStepMillis"),
                number(m,"hitboxPixels"),number(m,"initialSpeed"),number(m,"speedIncrement"),number(m,"maxSpeed"),n(m,"initialLives"),n(m,"maxLives"),
                integer(m,"normalFuseMillis",1,Long.MAX_VALUE),n(m,"initialBombQuota"),n(m,"maxBombQuota"),n(m,"initialBombRange"),n(m,"maxBombRange"),
                number(m,"itemDropProbability"),integer(m,"durationMillis",1,Long.MAX_VALUE),n(m,"sessionGraceMillis"),n(m,"clientRecoveryBudgetMillis"));
        if(mode==GameMode.CLASSIC)return new EffectiveRules.Classic(common);
        if(mode==GameMode.PARADOX)return new EffectiveRules.Paradox(common);
        if(mode!=GameMode.SHADOW_RELAY)throw new IllegalArgumentException("Unavailable rules");
        if(!text(m,"layoutId",64).equals(MatchPreset.SHADOW_RELAY_V1.layoutId()))throw new IllegalArgumentException("Invalid layout");
        var scan=fields(m.get("scan"),"fuseMillis","activeMillis","radius","cooldownMillis");
        return new EffectiveRules.ShadowRelay(common,n(m,"players"),n(m,"teamSize"),bool(m,"friendlyFire"),n(m,"normalVisionRadius"),n(m,"captureRadius"),
                n(m,"captureTicks"),n(m,"scorePeriodTicks"),n(m,"targetScore"),new EffectiveRules.Scan(integer(scan,"fuseMillis",1,Long.MAX_VALUE),
                integer(scan,"activeMillis",1,Long.MAX_VALUE),n(scan,"radius"),integer(scan,"cooldownMillis",1,Long.MAX_VALUE)));
    }
    public static Map<String,Object> descriptor(ModeDescriptor d){
        var c=d.capabilities();
        return body("gameMode",d.gameMode().name(),"rulesVersion",d.rulesVersion(),"available",d.available(),"unavailableReason",d.unavailableReason()==null?null:d.unavailableReason().name(),
                "minPlayers",d.minPlayers(),"maxPlayers",d.maxPlayers(),"teamSize",d.teamSize(),"requiredFeature",d.requiredFeature(),
                "capabilities",body("teams",c.teams(),"recipientVisibility",c.recipientVisibility(),"scan",c.scan(),"checkpoint",c.checkpoint(),"rewind",c.rewind(),"workerFailover",c.workerFailover()));
    }
    private static Integer optionalInt(Map<String,Object> m,String key){return m.get(key)==null?null:n(m,key);}
    public static ModeDescriptor descriptor(Object raw){
        var m=fields(raw,"gameMode","rulesVersion","available","unavailableReason","minPlayers","maxPlayers","teamSize","requiredFeature","capabilities");
        var c=fields(m.get("capabilities"),"teams","recipientVisibility","scan","checkpoint","rewind","workerFailover");
        return new ModeDescriptor(GameMode.valueOf(text(m,"gameMode",32)),m.get("rulesVersion")==null?null:text(m,"rulesVersion",64),bool(m,"available"),
                m.get("unavailableReason")==null?null:ModeDescriptor.UnavailableReason.valueOf(text(m,"unavailableReason",32)),optionalInt(m,"minPlayers"),optionalInt(m,"maxPlayers"),optionalInt(m,"teamSize"),
                m.get("requiredFeature")==null?null:text(m,"requiredFeature",64),new ModeCapabilities(bool(c,"teams"),bool(c,"recipientVisibility"),bool(c,"scan"),bool(c,"checkpoint"),bool(c,"rewind"),bool(c,"workerFailover")));
    }
    public static Map<String,Object> room(RoomStateView r){
        return body("roomId",r.roomId(),"roomName",r.roomName(),"hostId",r.hostId(),"state",r.state(),"maxPlayers",r.maxPlayers(),"mapSelection",r.mapSelection().name(),
                "configurationRevision",r.configurationRevision(),"configuration",configuration(r.configuration()),"players",r.players().stream().map(p->body("sessionId",p.sessionId(),"username",p.username(),"state",p.state(),"slot",p.slot(),"team",p.team()==null?null:p.team().name(),"readyRevision",p.readyRevision())).toList());
    }
    public static RoomStateView room(Object raw){
        var m=fields(raw,"roomId","roomName","hostId","state","maxPlayers","mapSelection","configurationRevision","configuration","players");
        var members=new ArrayList<RoomStateView.Member>();
        for(var item:list(m.get("players"),4)){
            var p=fields(item,"sessionId","username","state","slot","team","readyRevision");
            members.add(new RoomStateView.Member(uuid(p,"sessionId"),text(p,"username",32),text(p,"state",32),n(p,"slot"),p.get("team")==null?null:TeamId.valueOf(text(p,"team",1)),
                    p.get("readyRevision")==null?null:integer(p,"readyRevision",1,Long.MAX_VALUE)));
        }
        return new RoomStateView(uuid(m,"roomId"),text(m,"roomName",64),uuid(m,"hostId"),text(m,"state",16),n(m,"maxPlayers"),MapSelection.valueOf(text(m,"mapSelection",16)),
                integer(m,"configurationRevision",1,Long.MAX_VALUE),configuration(m.get("configuration")),members);
    }
    public static Packet roomState(RoomStateView r){return packet(PacketType.ROOM_STATE,room(r));}
    public static RoomStateView decodeRoom(Packet p){if(p.getType()!=PacketType.ROOM_STATE)throw new IllegalArgumentException("Wrong type");return room(object(p.getJsonPayload(),262144));}
    public static Packet rooms(List<RoomStateView> rooms,List<ModeDescriptor> modes){return packet(PacketType.LIST_ROOMS,body("rooms",rooms.stream().map(MetadataV3Codec::room).toList(),"modes",modes.stream().map(MetadataV3Codec::descriptor).toList()));}
    public record RoomList(List<RoomStateView> rooms,List<ModeDescriptor> modes){public RoomList{rooms=List.copyOf(rooms);modes=List.copyOf(modes);}}
    public static RoomList decodeRooms(Packet p){
        if(p.getType()!=PacketType.LIST_ROOMS)throw new IllegalArgumentException("Wrong type");var m=fields(object(p.getJsonPayload(),262144),"rooms","modes");
        return new RoomList(list(m.get("rooms"),32).stream().map(MetadataV3Codec::room).toList(),list(m.get("modes"),4).stream().map(MetadataV3Codec::descriptor).toList());
    }
    public static Packet started(MatchStartMetadata m){
        var c=m.configuration();var s=m.stream();
        return packet(PacketType.GAME_STARTED,body("roomId",m.identity().roomId(),"matchId",m.identity().matchId().toString(),"gameMode",c.gameMode().name(),"matchFormat",c.matchFormat().name(),"rulesVersion",c.rulesVersion(),
                "configurationRevision",m.configurationRevision(),"configurationId",c.configurationId().toString(),"presetId",c.presetId().name(),"profile",c.profile().name(),
                "tickMillis",c.effectiveRules().common().tickTargetMillis(),"inputStepMillis",c.effectiveRules().common().inputStepMillis(),"layoutId",c.layoutId(),"mapTheme",m.identity().mapTheme().name(),"snapshotVersion",6,
                "players",m.players().stream().map(p->body("sessionId",p.sessionId(),"playerId",p.playerId(),"slot",p.slot(),"username",p.username(),"team",p.team()==null?null:p.team().name())).toList(),
                "stream",body("recipientPlayerId",s.recipientPlayerId(),"viewEpoch",s.viewEpoch(),"timelineEpoch",s.timelineEpoch(),"authorityEpoch",s.authorityEpoch()),"effectiveRules",c.effectiveRules().toWireFields()));
    }
    public static MatchStartMetadata decodeStarted(Packet p){
        if(p.getType()!=PacketType.GAME_STARTED)throw new IllegalArgumentException("Wrong type");
        var m=fields(formatFields(object(p.getJsonPayload(),16384)),"roomId","matchId","gameMode","matchFormat","rulesVersion","configurationRevision","configurationId","presetId","profile","tickMillis","inputStepMillis","layoutId","mapTheme","snapshotVersion","players","stream","effectiveRules");
        if(integer(m,"snapshotVersion",1,99)!=6)throw new ProtocolRejection("UNSUPPORTED_SNAPSHOT");
        var mode=GameMode.valueOf(text(m,"gameMode",32));
        var c=new MatchConfiguration(UUID.fromString(uuid(m,"configurationId")),mode,text(m,"rulesVersion",64),MatchPreset.valueOf(text(m,"presetId",64)),MatchProfile.valueOf(text(m,"profile",16)),text(m,"layoutId",64),rules(mode,m.get("effectiveRules")),matchFormat(m,mode));
        if(integer(m,"tickMillis",1,100)!=c.effectiveRules().common().tickTargetMillis()||integer(m,"inputStepMillis",1,100)!=c.effectiveRules().common().inputStepMillis())throw new IllegalArgumentException("Step mismatch");
        var members=new ArrayList<MatchStartMetadata.Member>();var ids=new ArrayList<MatchContext.PlayerIdentity>();
        for(var item:list(m.get("players"),4)){var v=fields(item,"sessionId","playerId","slot","username","team");String id=uuid(v,"sessionId");int numeric=n(v,"playerId");ids.add(new MatchContext.PlayerIdentity(id,numeric));
            members.add(new MatchStartMetadata.Member(id,numeric,n(v,"slot"),text(v,"username",32),v.get("team")==null?null:TeamId.valueOf(text(v,"team",1))));}
        var s=fields(m.get("stream"),"recipientPlayerId","viewEpoch","timelineEpoch","authorityEpoch");
        return new MatchStartMetadata(new MatchContext(uuid(m,"roomId"),UUID.fromString(uuid(m,"matchId")),MapTheme.valueOf(text(m,"mapTheme",16)),ids),c,integer(m,"configurationRevision",1,Long.MAX_VALUE),members,
                new StreamContext(n(s,"recipientPlayerId"),integer(s,"viewEpoch",1,Long.MAX_VALUE),integer(s,"timelineEpoch",0,Long.MAX_VALUE),integer(s,"authorityEpoch",0,0)));
    }
}
