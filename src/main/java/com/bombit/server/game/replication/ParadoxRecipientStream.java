package com.bombit.server.game.replication;

import com.bombit.common.dto.*;

/** Baseline bị hủy khi đổi timeline. Delegate chỉ tính diff; không dùng simulationTick làm baseTick. */
public final class ParadoxRecipientStream implements RecipientStream {
    private final ClassicRecipientStream delegate;
    private ClassicSnapshotV6 candidate;
    private ParadoxSnapshotV6 pending;
    public ParadoxRecipientStream(MatchStartMetadata metadata){
        if(metadata.configuration().gameMode()!=GameMode.PARADOX)throw new IllegalArgumentException("Not Paradox");
        delegate=new ClassicRecipientStream(metadata);
    }
    public synchronized MatchStartMetadata metadata(){return delegate.metadata();}
    public synchronized boolean active(){return delegate.active();}
    public synchronized long barrier(){return delegate.barrier();}
    public synchronized void reset(long tick){delegate.reset(tick);candidate=null;pending=null;}
    public synchronized ParadoxSnapshotV6 build(WorldFrame frame){
        if(frame.paradox()==null||frame.paradox().timelineEpoch()!=metadata().stream().timelineEpoch())return null;
        candidate=delegate.build(frame);if(candidate==null)return null;
        return pending=new ParadoxSnapshotV6(candidate.update(),frame.paradox().simulationTick(),candidate.matchTimeMillis(),
                candidate.publicPlayers(),candidate.lastProcessedInput(),candidate.inputEnabled(),frame.paradox());
    }
    public synchronized void commit(RecipientSnapshotV6 update){
        if(update!=pending||candidate==null)throw new IllegalStateException("Wrong candidate");
        delegate.commit(candidate);pending=null;candidate=null;
    }
    public synchronized void close(){delegate.close();candidate=null;pending=null;}
}
