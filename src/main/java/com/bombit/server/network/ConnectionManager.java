package com.bombit.server.network;

import com.bombit.server.session.Session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionManager implements AutoCloseable {
    private static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final Map<String, SessionWriter> writers = new ConcurrentHashMap<>();

    public SessionWriter open(Session session) {
        SessionWriter writer = new SessionWriter(session, DEFAULT_QUEUE_CAPACITY);
        writers.put(session.getPlayerId(), writer);
        return writer;
    }

    public Optional<SessionWriter> find(String playerId) {
        return Optional.ofNullable(writers.get(playerId));
    }

    public void release(String playerId) {
        SessionWriter writer = writers.remove(playerId);
        if (writer != null) {
            writer.close();
        }
    }

    public int count() {
        return writers.size();
    }

    @Override
    public void close() {
        for (String playerId : writers.keySet()) {
            release(playerId);
        }
    }
}
