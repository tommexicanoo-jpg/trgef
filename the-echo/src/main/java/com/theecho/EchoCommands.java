package com.theecho;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Comandos de depuración (requieren permiso nivel 2). */
public final class EchoCommands {
    private EchoCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("echo")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("status").executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            ctx.getSource().sendFeedback(() -> Text.literal(EchoManager.status(p)), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("dread")
                                .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0, 1000))
                                        .executes(ctx -> {
                                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                                            double v = DoubleArgumentType.getDouble(ctx, "value");
                                            EchoManager.setDread(p, v);
                                            ctx.getSource().sendFeedback(() -> Text.literal("Miedo = " + v), false);
                                            return 1;
                                        })))
                        .then(CommandManager.literal("spawn").executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            boolean ok = EchoManager.debugSpawn(p);
                            ctx.getSource().sendFeedback(() -> Text.literal(ok ? "El Eco te observa." : "No hay sitio visible para el Eco."), false);
                            return ok ? 1 : 0;
                        }))
                        .then(CommandManager.literal("hijack").executes(ctx -> {
                            EchoManager.forceHijack(ctx.getSource().getPlayerOrThrow());
                            return 1;
                        }))
                        .then(CommandManager.literal("escape").executes(ctx -> {
                            EchoManager.leaveRealm(ctx.getSource().getPlayerOrThrow(), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("reset").executes(ctx -> {
                            EchoManager.reset(ctx.getSource().getPlayerOrThrow());
                            ctx.getSource().sendFeedback(() -> Text.literal("Estado del Eco reiniciado."), false);
                            return 1;
                        }))));
    }
}
