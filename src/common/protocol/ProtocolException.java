package common.protocol;

import java.io.IOException;

/** Lỗi cấu trúc hoặc nội dung packet theo quy ước của ứng dụng. */
public class ProtocolException extends IOException {
    public ProtocolException(String message) { super(message); }
    public ProtocolException(String message, Throwable cause) { super(message, cause); }
}
