package com.theecho;

import com.theecho.block.EchoMirrorBlock;
import com.theecho.block.EchoOriginalBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

public final class ModBlocks {
    public static final Block ECHO_MIRROR = Registry.register(Registries.BLOCK, TheEcho.id("echo_mirror"),
            new EchoMirrorBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.LIGHT_BLUE_GRAY)
                    .strength(0.6f)
                    .sounds(BlockSoundGroup.GLASS)
                    .nonOpaque()
                    .luminance(s -> 2)
                    .pistonBehavior(PistonBehavior.DESTROY)));

    public static final Block ECHO_ORIGINAL = Registry.register(Registries.BLOCK, TheEcho.id("echo_original"),
            new EchoOriginalBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.BLACK)
                    .strength(-1.0f, 3600000.0f)
                    .sounds(BlockSoundGroup.AMETHYST_BLOCK)
                    .luminance(s -> 5)
                    .dropsNothing()));

    public static final Item ECHO_MIRROR_ITEM = Registry.register(Registries.ITEM, TheEcho.id("echo_mirror"),
            new BlockItem(ECHO_MIRROR, new Item.Settings()));

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(e -> e.add(ECHO_MIRROR_ITEM));
    }
}
