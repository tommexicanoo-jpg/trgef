package com.theecho.net;

import com.theecho.TheEcho;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Servidor -> cliente. dread = 0..100 (o -1 = sin cambio), scare = 0 nada, 1 susto corto, 2 susto fuerte. */
public record DreadPayload(int dread, int scare) implements CustomPayload {
    public static final CustomPayload.Id<DreadPayload> ID = new CustomPayload.Id<>(TheEcho.id("dread"));
    public static final PacketCodec<RegistryByteBuf, DreadPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, DreadPayload::dread,
            PacketCodecs.VAR_INT, DreadPayload::scare,
            DreadPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
