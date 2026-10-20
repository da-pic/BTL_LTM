package common.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Mã hóa packet theo framing [length:int32][type:int16][payload], big-endian. */
public final class PacketEncoder {
    /** Length tính cả 2 byte type và payload, không tính chính 4 byte length. */
    public static final int MAX_PACKET_LENGTH = 1_048_576;
    private PacketEncoder() { }

    public static byte[] encode(Packet packet) throws ProtocolException {
        Objects.requireNonNull(packet, "packet");
        byte[] payload = packet.getJsonPayload().getBytes(StandardCharsets.UTF_8);
        int length = Short.BYTES + payload.length;
        validateLength(length);
        byte[] frame = new byte[Integer.BYTES + length];
        frame[0] = (byte) (length >>> 24); frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8); frame[3] = (byte) length;
        frame[4] = (byte) (packet.getType() >>> 8); frame[5] = (byte) packet.getType();
        System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    /** Ghi trọn một frame và flush để message sẵn sàng được gửi qua socket. */
    public static void encode(Packet packet, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        byte[] frame = encode(packet);
        output.write(frame);
        output.flush();
    }

    /** Tiện ích cho caller đã dùng DataOutputStream. */
    public static void encode(Packet packet, DataOutputStream output) throws IOException {
        encode(packet, (OutputStream) output);
    }

    static void validateLength(int length) throws ProtocolException {
        if (length < Short.BYTES || length > MAX_PACKET_LENGTH) {
            throw new ProtocolException("Độ dài packet không hợp lệ: " + length);
        }
    }
}
