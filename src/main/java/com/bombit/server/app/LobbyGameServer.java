package com.bombit.server.app;

import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.integration.GameEventSink;
import com.bombit.server.network.Server;
import com.bombit.server.session.Session;
import java.io.IOException;
import java.util.Optional;

/** Composition root: lobby chuẩn v3; closure legacy chỉ giữ endpoint chẩn đoán v2. */
public final class LobbyGameServer implements AutoCloseable,GameEventSink {
    private final CurrentLobbyGameServer current;
    private final LegacyLobbyGameServer legacy;
    private record Bootstrap(GameRuntimeManager games,PlayerMovementRules movement) { }
    private static Bootstrap standard(BombRules bombs,MatchRules match){var movement=PlayerMovementRules.defaults();return new Bootstrap(new GameRuntimeManager(new ModeRegistry(ClassicRulesAdapter.publicRules(bombs,match,movement))),movement);}
    public LobbyGameServer(int port)throws IOException{this(port,new BombRules(Long.getLong("bombit.bomb.fuseMillis",3000L),Integer.getInteger("bombit.bomb.range",1),Integer.getInteger("bombit.bomb.quota",1)));}
    public LobbyGameServer(int port,BombRules bombs)throws IOException{this(port,bombs,MatchRules.demo());}
    public LobbyGameServer(int port,BombRules bombs,MatchRules match)throws IOException{this(port,standard(bombs,match));}
    private LobbyGameServer(int port,Bootstrap b)throws IOException{this(port,b.games(),b.movement());}
    public LobbyGameServer(int port,GameRuntimeManager games)throws IOException{this(port,games,PlayerMovementRules.defaults());}
    public LobbyGameServer(int port,GameRuntimeManager games,PlayerMovementRules movement)throws IOException{
        if(games.modeRegistry().isPresent()){current=new CurrentLobbyGameServer(port,games,movement);legacy=null;}
        else {legacy=new LegacyLobbyGameServer(port,games,movement);current=null;}
    }
    public boolean isCurrentProtocol(){return current!=null;}
    /** Endpoint benchmark v2 được gọi đích danh; không phải fallback của listener public v3. */
    public static LobbyGameServer legacyDiagnostic(int port,BombRules bombs,MatchRules rules)throws IOException{
        var games=new GameRuntimeManager(id->com.bombit.server.game.map.ArenaGenerator.create(),(room,state,ids)->new MatchGameplayExtension(state,ids,bombs,rules,
                new com.bombit.server.game.item.RandomItemSpawnPolicy(rules.dropChance(),new java.util.Random()),e->{},r->{}));
        return new LobbyGameServer(port,games,PlayerMovementRules.defaults());
    }
    public void start()throws IOException{if(current!=null)current.start();else legacy.start();}
    public int port(){return transport().getPort();}
    public Server transport(){return current!=null?current.transport():legacy.transport();}
    public void setSeamlessRecovery(boolean value){if(current!=null)current.setSeamlessRecovery(value);else legacy.setSeamlessRecovery(value);}
    public boolean isSeamlessRecovery(){return current!=null?current.isSeamlessRecovery():legacy.isSeamlessRecovery();}
    public int activeMatchCount(){return current!=null?current.activeMatchCount():legacy.activeMatchCount();}
    public int snapshotWorkerCount(){return current!=null?current.snapshotWorkerCount():legacy.snapshotWorkerCount();}
    public Optional<Throwable> failure(){return current!=null?current.failure():legacy.failure();}
    public void onPlayerDisconnected(Session s){if(current!=null)current.onPlayerDisconnected(s);else legacy.onPlayerDisconnected(s);}
    public void onPlayerHardDropped(Session s){if(current!=null)current.onPlayerHardDropped(s);else legacy.onPlayerHardDropped(s);}
    public void onPlayerReconnected(Session s){if(current!=null)current.onPlayerReconnected(s);else legacy.onPlayerReconnected(s);}
    public void close(){if(current!=null)current.close();else legacy.close();}
}
