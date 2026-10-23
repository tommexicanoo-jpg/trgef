package com.theecho.block;

import com.mojang.serialization.MapCodec;
import com.theecho.EchoManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** "El Original": la única salida de la réplica oscura. */
public class EchoOriginalBlock extends Block {
    public static final MapCodec<EchoOriginalBlock> CODEC = createCodec(EchoOriginalBlock::new);

    public EchoOriginalBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient && player instanceof ServerPlayerEntity sp) {
            EchoManager.leaveRealm(sp, true);
        }
        return ActionResult.success(world.isClient);
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, net.minecraft.util.math.random.Random random) {
        if (random.nextInt(3) == 0) {
            world.addParticle(ParticleTypes.END_ROD,
                    pos.getX() + random.nextDouble(), pos.getY() + 1.0, pos.getZ() + random.nextDouble(),
                    0.0, 0.03, 0.0);
        }
    }
}
