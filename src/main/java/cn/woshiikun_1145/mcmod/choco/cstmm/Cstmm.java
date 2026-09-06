package cn.woshiikun_1145.mcmod.choco.cstmm;

import cn.woshiikun_1145.mcmod.choco.cstmm.command.ModCommands;
import cn.woshiikun_1145.mcmod.choco.cstmm.listener.EventListener;
import cn.woshiikun_1145.mcmod.choco.cstmm.manager.*;
import cn.woshiikun_1145.mcmod.choco.cstmm.network.NetworkHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cstmm implements ModInitializer {
    public static final String MOD_ID = "chocolateservertaczmatchmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static boolean initialized = false;
    private static MinecraftServer serverInstance = null;

    @Override
    public void onInitialize() {
        if (initialized) {
            LOGGER.warn("[CSTMM - Main] Cstmm already initialized, skipping duplicate initialization.");
            return;
        }
        initialized = true;

        LOGGER.info("[CSTMM - Main] Initializing mod...");

        NetworkHandler.register();
        registerCommands();
        EventListener.register();
        registerServerEvents();
        registerTickListener();

        LOGGER.info("[CSTMM - Main] Mod initialized successfully!");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register(ModCommands::register);
    }

    private void registerServerEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            LOGGER.info("[CSTMM - Main] Server started, loading configurations...");
            ConfigManager.getInstance().load();
            PlayerDataManager.getInstance().loadAll();
            // initialize 内部会设置 RegistryLookup 并调用 loadBags，无需单独调用
            InventoryManager.initialize(server.getRegistryManager());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[CSTMM - Main] Server stopping, saving all data...");
            PlayerDataManager.getInstance().saveAll();
            InventoryManager.getInstance().saveAll();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            serverInstance = null;
        });
    }

    private void registerTickListener() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MatchScheduler.getInstance().onTick(server);
            // 修复P0-2：VoteManager.tick() 已移至 MatchScheduler.onSecondTick()
        });
    }

    public static MinecraftServer getServer() {
        return serverInstance;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}