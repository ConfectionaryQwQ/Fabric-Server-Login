package fr.ninjagoku4560.loginguard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.ninjagoku4560.loginguard.utils.FileUtil;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class LoginGuard implements ModInitializer {
    public static final String MOD_ID = "loginguard";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // Set to keep track of immobilized players
    private static final Set<ServerPlayerEntity> immobilizedPlayers = new HashSet<>();

    @Override
    public void onInitialize() {
        // Log a message when the mod is initialized
        LOGGER.info("Initializing LoginGuard");

        FileUtil.createFolder("password");
        try {
            FileUtil.createFolder("positions");
        } catch (Exception e) {
            LOGGER.error(e);
        }
        // Register the player join and leave events
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::onPlayerLeave);

        // Register server tick event to handle immobilized players
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void onPlayerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();
        String playerName = player.getName().getString();
        String positionFileName = "positions"+ File.separator + playerName + ".txt";
        if (!FileUtil.fileExists(positionFileName)) {
            BlockPos spawnO = server.getOverworld().getSpawnPos();
            FileUtil.savePlayerPosition(positionFileName,spawnO.getX(),spawnO.getY(),spawnO.getZ());
        }
        // Immobilize the player
        immobilizedPlayers.add(player);
        if (!FileUtil.fileExists("password"+ File.separator + playerName + ".txt")) {
            FileUtil.writeToFile("password"+ File.separator + playerName + ".txt", "");
        }
        player.sendMessage(Text.translatable("请使用/register <密码> <密码>来注册,/login <密码> <密码>来登陆"));
        // Load and teleport to the saved position
        double[] position = FileUtil.loadPlayerPosition(positionFileName);
        if (position != null) {
            player.teleport(position[0], position[1], position[2], false);
        }
    }

    private void onPlayerLeave(ServerPlayNetworkHandler handler, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();
        String playerName = player.getName().getString();
        String positionFileName = "positions"+ File.separator + playerName + ".txt";

        // Save the player's current position
        BlockPos pos = player.getBlockPos();
        FileUtil.savePlayerPosition(positionFileName, pos.getX(), pos.getY(), pos.getZ());
    }

    private void onServerTick(MinecraftServer server) {
        // Iterate over immobilized players and prevent movement

        for (ServerPlayerEntity player : immobilizedPlayers) {
            player.setVelocity(0, 0, 0);
            double[] pos = FileUtil.loadPlayerPosition("positions"+ File.separator + player.getName().getString() + ".txt");
            if (pos != null) {
                player.teleport(pos[0], pos[1], pos[2], false);
            } else {
                LOGGER.error("pos == null for "+player.getName().getString());
            }
        }
    }

    public static void releasePlayer(ServerPlayerEntity player) {
        immobilizedPlayers.remove(player);
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess commandRegistryAccess, CommandManager.RegistrationEnvironment registrationEnvironment) {
        dispatcher.register(CommandManager.literal("register")
                .then(CommandManager.argument("password", StringArgumentType.word())
                        .then(CommandManager.argument("confirmPassword", StringArgumentType.word())
                                .executes(context -> {
                                    String password = StringArgumentType.getString(context, "password");
                                    String confirmPassword = StringArgumentType.getString(context, "confirmPassword");
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    assert player != null && password != null && confirmPassword != null;

                                    String playerFileName = "password"+ File.separator + player.getName().getString() + ".txt";

                                    // Verify if the player is already registered
                                    if (FileUtil.fileExists(playerFileName) && FileUtil.FileNotEmpty(playerFileName)) {
                                        player.sendMessage(Text.translatable("AlreadyRegister"));
                                        return 1;
                                    }
                                    if (password.equals(confirmPassword)) {
                                        boolean registered = Registering.register(player, password);
                                        if (registered) {
                                            // Player can move
                                            releasePlayer(player);
                                            player.sendMessage(Text.translatable("注册完成"));
                                        }
                                    } else {
                                        player.sendMessage(Text.translatable("不同的密码"));
                                        player.sendMessage(Text.translatable("请重试"));
                                    }
                                    return 1;
                                })
                        )
                )
        );

        dispatcher.register(CommandManager.literal("login")
                .then(CommandManager.argument("password", StringArgumentType.word())
                        .executes(context -> {
                            String password = StringArgumentType.getString(context, "password");
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            assert player != null && password != null;
                            // 0 = Wrong Password
                            // 1 = Login Done
                            // 2 = Not Register
                            int LoginCode = Login.check(player, password);
                            if (LoginCode == 1) {
                                // Player can move
                                releasePlayer(player);
                                player.sendMessage(Text.translatable("登陆完成"));
                            } else if (LoginCode == 0) {
                                player.sendMessage(Text.translatable("密码错误"));
                                player.sendMessage(Text.translatable("请重试"));
                            } else if (LoginCode == 2) {
                                player.sendMessage(Text.translatable("未注册"));
                            }
                            return 1;
                        })
                )
        );
    }
}
