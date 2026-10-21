package dev.journalsmp.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class JournalNetworking {
    private JournalNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(JumpscarePayload.TYPE, JumpscarePayload.STREAM_CODEC);
    }
}
