package dev.journalsmp.client;

import dev.journalsmp.network.JumpscarePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class JournalNetworkingClient {
    private JournalNetworkingClient() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(JumpscarePayload.TYPE, (payload, context) ->
                JumpscareOverlay.activate(payload.durationMillis()));
    }
}
