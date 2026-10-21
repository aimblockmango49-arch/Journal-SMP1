package dev.journalsmp.network;

import dev.journalsmp.JournalSmpMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JumpscarePayload(int durationMillis) implements CustomPacketPayload {
    public static final Type<JumpscarePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(JournalSmpMod.MOD_ID, "jumpscare"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JumpscarePayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, JumpscarePayload::durationMillis, JumpscarePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
