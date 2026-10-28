package com.bombit.common.dto;

/** Room preference. RANDOM is resolved to a concrete MapTheme when a match starts. */
public enum MapSelection {
    RANDOM("NGẪU NHIÊN"),
    CLASSIC("CỔ ĐIỂN"),
    FOREST("RỪNG"),
    OCEAN("BIỂN"),
    ICE("BĂNG"),
    FIRE("LỬA");

    private final String label;

    MapSelection(String label) { this.label = label; }

    public String label() { return label; }

    @Override public String toString() { return label; }
}
