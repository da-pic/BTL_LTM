package com.bombit.server.room;

import com.bombit.common.dto.MapSelection;
import com.bombit.common.dto.MatchConfiguration;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Room {
    public enum State { WAITING, PLAYING }

    private final String roomId;
    private final String roomName;
    private int maxPlayers;
    private MatchConfiguration configuration;
    private long configurationRevision=1;
    private final java.util.Map<String,Long> readyRevisions=new java.util.HashMap<>();
    private final List<Session> players = new ArrayList<>();
    private String hostPlayerId;
    private State state = State.WAITING;
    // Slot của trận đang chạy không được dồn lại khi một session hết grace.
    private final java.util.Map<String,Integer> playingSlots=new java.util.HashMap<>();
    private MapSelection mapSelection = MapSelection.RANDOM;

    public Room(String roomId, String roomName, int maxPlayers, Session host) {
        this(roomId,roomName,maxPlayers,host,null);
    }
    public Room(String roomId,String roomName,int maxPlayers,Session host,MatchConfiguration configuration){
        this.roomId = Objects.requireNonNull(roomId);
        this.roomName = Objects.requireNonNull(roomName);
        if (maxPlayers < 2) {
            throw new IllegalArgumentException("A room needs at least two slots");
        }
        if (configuration != null && configuration.isTeamMatch() && maxPlayers != 4) {
            throw new IllegalArgumentException("Team room requires four slots");
        }
        this.maxPlayers = maxPlayers;
        this.hostPlayerId = host.getPlayerId();
        players.add(host);
        this.configuration=configuration;
    }

    public synchronized boolean addPlayer(Session session) {
        if (state != State.WAITING || players.size() >= maxPlayers
                || players.stream().anyMatch(p -> p.getPlayerId().equals(session.getPlayerId()))) {
            return false;
        }
        if (isTeamMatch()) invalidateTeamRoster();
        players.add(session);
        return true;
    }

    public synchronized boolean removePlayer(String playerId) {
        // Roster preview chỉ đổi ở WAITING; trận đang chạy giữ roster riêng đã chốt.
        if (state == State.WAITING && isTeamMatch()
                && players.stream().anyMatch(p -> p.getPlayerId().equals(playerId))) invalidateTeamRoster();
        boolean removed = players.removeIf(player -> player.getPlayerId().equals(playerId));
        readyRevisions.remove(playerId);
        if (removed && playerId.equals(hostPlayerId) && !players.isEmpty()) {
            hostPlayerId = players.get(0).getPlayerId();
        }
        return removed;
    }

    public synchronized boolean canStartGame() {
        if (players.size() < 2 || state != State.WAITING) {
            return false;
        }
        if (isTeamMatch() && (players.size() != 4 || players.stream().anyMatch(player ->
                player.getPlayerId().equals(hostPlayerId) && player.getState() != SessionState.IN_ROOM
                        && player.getState() != SessionState.READY))) return false;
        return players.stream().allMatch(player ->
                player.getPlayerId().equals(hostPlayerId)
                        || player.getState() == SessionState.READY&&(configuration==null
                            ||Long.valueOf(configurationRevision).equals(readyRevisions.get(player.getPlayerId()))));
    }

    public synchronized List<Session> getPlayersSnapshot() {
        return List.copyOf(players);
    }

    public synchronized void setState(State state) {
        Objects.requireNonNull(state);
        if(this.state!=state){
            // Về phòng sau forfeit có roster mới: READY của roster trước không còn hợp lệ.
            if(this.state==State.PLAYING&&state==State.WAITING&&isTeamMatch()&&playingSlots.size()!=players.size())invalidateTeamRoster();
            playingSlots.clear();
            if(state==State.PLAYING)for(int i=0;i<players.size();i++)playingSlots.put(players.get(i).getPlayerId(),i+1);
        }
        this.state = state;
    }
    public synchronized int slotOf(String sessionId){
        if(state==State.PLAYING){var slot=playingSlots.get(sessionId);if(slot==null)throw new IllegalArgumentException("Not in match roster");return slot;}
        for(int i=0;i<players.size();i++)if(players.get(i).getPlayerId().equals(sessionId))return i+1;
        throw new IllegalArgumentException("Not in room");
    }

    public String getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public synchronized int getMaxPlayers() { return maxPlayers; }
    public synchronized java.util.Optional<MatchConfiguration> configuration(){return java.util.Optional.ofNullable(configuration);}
    public synchronized long configurationRevision(){return configurationRevision;}
    public synchronized Long readyRevision(String session){return readyRevisions.get(session);}
    private boolean isTeamMatch() {
        return configuration != null && configuration.isTeamMatch();
    }
    /** Join/leave đấu đội có thể đổi đội preview: hủy READY để không xác nhận roster cũ. */
    private void invalidateTeamRoster() {
        if (configurationRevision == Long.MAX_VALUE) throw new IllegalStateException("Revision exhausted");
        configurationRevision++;
        readyRevisions.clear();
        for (var player : players) if (player.getState() == SessionState.READY) player.setState(SessionState.IN_ROOM);
    }
    /** Validate trước khi gọi; commit config/capacity/revision/READY trong cùng monitor room. */
    public synchronized boolean changeConfiguration(MatchConfiguration next){
        Objects.requireNonNull(next);
        if(state!=State.WAITING)throw new IllegalStateException("Room playing");
        if(configuration!=null&&configuration.gameMode()==next.gameMode()&&configuration.rulesVersion().equals(next.rulesVersion())
                &&configuration.presetId()==next.presetId()&&configuration.matchFormat()==next.matchFormat()&&configuration.effectiveRules().equals(next.effectiveRules()))return false;
        if(configurationRevision==Long.MAX_VALUE)throw new IllegalStateException("Revision exhausted");
        configuration=next;if(next.isTeamMatch())maxPlayers=4;
        configurationRevision++;readyRevisions.clear();
        for(var player:players)if(player.getState()==SessionState.READY)player.setState(SessionState.IN_ROOM);
        return true;
    }
    public synchronized boolean setReady(Session player,boolean ready,long revision){
        if(revision!=configurationRevision)throw new com.bombit.common.protocol.ProtocolRejection("CONFIGURATION_STALE");
        if(state!=State.WAITING||!players.contains(player)||player.getState()!=SessionState.IN_ROOM&&player.getState()!=SessionState.READY)return false;
        player.setState(ready?SessionState.READY:SessionState.IN_ROOM);
        if(ready)readyRevisions.put(player.getPlayerId(),revision);else readyRevisions.remove(player.getPlayerId());return true;
    }
    public synchronized String getHostPlayerId() { return hostPlayerId; }
    public synchronized State getState() { return state; }
    public synchronized MapSelection getMapSelection() { return mapSelection; }
    public synchronized boolean setMapSelection(MapSelection selection) {
        if (state != State.WAITING) return false;
        mapSelection = Objects.requireNonNull(selection);
        return true;
    }
    public synchronized boolean isEmpty() { return players.isEmpty(); }
}
