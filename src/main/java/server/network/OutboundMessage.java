package server.network;

public record OutboundMessage(short type, byte[] payload) {
    public OutboundMessage {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
