package no.julian.quietframe;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import no.julian.quietframe.config.QuietFrameConfig;

import java.io.IOException;

final class QuietFrameCommand {
    private QuietFrameCommand() {
    }

    static void register(QuietFrameClient client) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("quietframe")
                        .executes(command -> status(client, command.getSource()))
                        .then(ClientCommands.literal("status").executes(command -> status(client, command.getSource())))
                        .then(ClientCommands.literal("toggle").executes(command -> toggle(client, command.getSource())))
                        .then(ClientCommands.literal("reload").executes(command -> reload(client, command.getSource())))
                        .then(ClientCommands.literal("profile")
                                .executes(command -> feedback(command.getSource(), "quietframe.command.profiles"))
                                .then(ClientCommands.literal("balanced")
                                        .executes(command -> profile(client, command.getSource(), false)))
                                .then(ClientCommands.literal("quiet")
                                        .executes(command -> profile(client, command.getSource(), true))))));
    }

    private static int status(QuietFrameClient client, FabricClientCommandSource source) {
        source.sendFeedback(client.status(source.getClient()));
        return 1;
    }

    static Component toggle(QuietFrameClient client) {
        QuietFrameConfig next = client.config().toggled();
        try {
            client.save(next);
            return Component.translatable(next.enabled() ? "quietframe.command.enabled" : "quietframe.command.disabled");
        } catch (IOException error) {
            QuietFrameClient.logConfigError(error);
            return Component.translatable("quietframe.command.save_failed");
        }
    }

    private static int toggle(QuietFrameClient client, FabricClientCommandSource source) {
        if (client.conflict() != null) {
            return conflict(client, source);
        }
        QuietFrameConfig previous = client.config();
        Component result = toggle(client);
        if (previous == client.config()) {
            source.sendError(result);
            return 0;
        }
        source.sendFeedback(result);
        return 1;
    }

    private static int reload(QuietFrameClient client, FabricClientCommandSource source) {
        try {
            client.reload();
            return feedback(source, "quietframe.command.reloaded");
        } catch (IOException error) {
            QuietFrameClient.logConfigError(error);
            source.sendError(Component.translatable("quietframe.command.reload_failed"));
            return 0;
        }
    }

    private static int profile(QuietFrameClient client, FabricClientCommandSource source, boolean quiet) {
        if (client.conflict() != null) {
            return conflict(client, source);
        }
        try {
            client.save(client.config().withProfile(quiet));
            return feedback(source, quiet ? "quietframe.command.profile.quiet" : "quietframe.command.profile.balanced");
        } catch (IOException error) {
            QuietFrameClient.logConfigError(error);
            source.sendError(Component.translatable("quietframe.command.save_failed"));
            return 0;
        }
    }

    private static int feedback(FabricClientCommandSource source, String key) {
        source.sendFeedback(Component.translatable(key));
        return 1;
    }

    private static int conflict(QuietFrameClient client, FabricClientCommandSource source) {
        source.sendError(Component.translatable("quietframe.command.conflict", client.conflict()));
        return 0;
    }
}
