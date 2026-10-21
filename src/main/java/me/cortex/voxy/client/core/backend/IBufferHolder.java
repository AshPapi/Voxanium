package me.cortex.voxy.client.core.backend;

public interface IBufferHolder {
    long rawHandle();
    long size();
    default int id() {
        return (int) rawHandle();
    }
    default void free() {}
}
