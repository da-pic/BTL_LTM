package com.bombit.common.protocol;

/** Lỗi có mã ổn định; tầng transport quyết định đóng socket hay chỉ từ chối yêu cầu. */
public final class ProtocolRejection extends IllegalArgumentException {
    private final String code;
    public ProtocolRejection(String code) { super(code); this.code=java.util.Objects.requireNonNull(code); }
    public String code() { return code; }
}
