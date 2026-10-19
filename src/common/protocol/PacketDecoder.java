package common.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Đọc frame TCP chính xác theo length prefix, kể cả khi read chỉ trả về một phần dữ liệu. */
public final class PacketDecoder {
    private PacketDecoder() { }

    /** Đọc một frame. EOF sạch trước frame kế tiếp được trả về dưới dạng EOFException. */
    public static Packet decode(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream in = input instanceof DataInputStream data ? data : new DataInputStream(input);
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            throw new EOFException("Kết nối đóng trước khi nhận đủ length của packet");
        }
        PacketEncoder.validateLength(length);
        byte[] body = new byte[length];
        in.readFully(body); // readFully xử lý các lần đọc ngắn của TCP stream.
        short type = ByteBuffer.wrap(body, 0, Short.BYTES).order(ByteOrder.BIG_ENDIAN).getShort();
        String payload;
        try {
            payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body, Short.BYTES, length - Short.BYTES)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("Payload không phải UTF-8 hợp lệ", e);
        }
        return new Packet(type, payload);
    }

    /** Tương thích với API DataInputStream thường dùng ở socket handler. */
    public static Packet decode(DataInputStream input) throws IOException {
        return decode((InputStream) input);
    }
}
