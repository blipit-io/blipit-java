package io.blipit;

public enum Level {
    FATAL("fatal"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug");

    public final String wire;

    Level(String wire) {
        this.wire = wire;
    }
}
