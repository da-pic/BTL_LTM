package com.bombit.common.dto;

/** Concrete visual theme selected once by the authoritative server for a match. */
public enum MapTheme {
    CLASSIC("CỔ ĐIỂN"),
    FOREST("RỪNG XANH"),
    OCEAN("BIỂN XANH"),
    ICE("BĂNG GIÁ"),
    FIRE("NÚI LỬA");

    private final String label;

    MapTheme(String label) { this.label = label; }

    public String label() { return label; }

}
