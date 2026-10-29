package dev.autohome;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AutoHomeClient implements ClientModInitializer {

    private enum State { IDLE, WAIT_RESPAWN, WAIT_ALIVE }

    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("autohome.properties");

    private static boolean enabled = false;
    private static String command = "home hom";
    private static int respawnDelayTicks = 30;
    private static int commandDelayTicks = 20;

    private static State state = State.IDLE;
    private static int ticks = 0;
    private static int waited = 0;

    @Override
    public void onInitializeClient() {
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(AutoHomeClient::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommandManager.literal("autohome")
                    .executes(ctx -> {
                        enabled = !enabled;
                        state = State.IDLE;
                        saveConfig();
                        ctx.getSource().sendFeedback(Component.literal(
                            "AutoHome: " + (enabled ? "ВКЛ" : "ВЫКЛ") + " (команда: /" + command + ")"));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("cmd")
                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String text = StringArgumentType.getString(ctx, "text").trim();
                                if (text.startsWith("/")) text = text.substring(1);
                                command = text;
                                saveConfig();
                                ctx.getSource().sendFeedback(Component.literal(
                                    "AutoHome: команда теперь /" + command));
                                return 1;
                            })))
            ));
    }

    private static void tick(Minecraft mc) {
        if (!enabled || mc.player == null) {
            state = State.IDLE;
            return;
        }

        switch (state) {
            case IDLE -> {
                if (mc.screen instanceof DeathScreen) {
                    state = State.WAIT_RESPAWN;
                    ticks = 0;
                }
            }
            case WAIT_RESPAWN -> {
                if (!(mc.screen instanceof DeathScreen)) {
                    state = State.WAIT_ALIVE;
                    ticks = 0;
                    waited = 0;
                } else if (++ticks >= respawnDelayTicks) {
                    mc.player.respawn();
                    state = State.WAIT_ALIVE;
                    ticks = 0;
                    waited = 0;
                }
            }
            case WAIT_ALIVE -> {
                boolean alive = !(mc.screen instanceof DeathScreen) && !mc.player.isDeadOrDying();
                if (!alive) {
                    ticks = 0;
                    if (++waited > 200) state = State.IDLE;
                } else if (++ticks >= commandDelayTicks) {
                    mc.player.connection.sendCommand(command);
                    state = State.IDLE;
                }
            }
        }
    }

    private static void loadConfig() {
        Properties p = new Properties();
        if (Files.exists(CONFIG)) {
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
        command = p.getProperty("command", "home hom").trim();
        if (command.startsWith("/")) command = command.substring(1);
        respawnDelayTicks = parse(p.getProperty("respawnDelayTicks"), 30);
        commandDelayTicks = parse(p.getProperty("commandDelayTicks"), 20);
        saveConfig();
    }

    private static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("command", command);
        p.setProperty("respawnDelayTicks", Integer.toString(respawnDelayTicks));
        p.setProperty("commandDelayTicks", Integer.toString(commandDelayTicks));
        try (OutputStream out = Files.newOutputStream(CONFIG)) {
            p.store(out, "AutoHome: 20 ticks = 1 second");
        } catch (IOException ignored) {
        }
    }

    private static int parse(String s, int def) {
        try {
            return Math.max(1, Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return def;
        }
    }
}
