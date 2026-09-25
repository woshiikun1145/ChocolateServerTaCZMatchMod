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

/**
 * 【作用】模组服务端主入口：Fabric 初始化时注册网络、命令、事件监听与服务器生命周期/每 tick 回调，
 * 并持有当前服务器实例供各管理器获取。
 * 【被谁使用】由 Fabric Loader 在服务端启动时反射调用 onInitialize（外部 API 入口）；
 * getServer() 被 MatchManager、QueueManager、VoteManager、BoundaryChecker、PlayerDataManager、
 * ClanManager、ModCommands、NetworkHandler 等大量服务端类调用；LOGGER/MOD_ID 被全项目引用。
 */
public class Cstmm implements ModInitializer {
    // 模组 ID（与 fabric.mod.json 一致），供 NetworkHandler 获取 ModContainer 等使用
    public static final String MOD_ID = "chocolateservertaczmatchmod";
    // 全模组统一日志器，被各管理器/监听器/网络层引用
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 已初始化标记，防止重复初始化
    private static boolean initialized = false;
    // 当前运行的服务器实例，SERVER_STARTED 时赋值、SERVER_STOPPED 时清空
    private static MinecraftServer serverInstance = null;

    // Fabric ModInitializer 入口：仅服务端生效（客户端加载会因缺少服务端逻辑而空实现）
    @Override
    public void onInitialize() {
        // 防重复初始化：重复进入直接跳过
        if (initialized) {
            LOGGER.warn("[CSTMM - Main] Cstmm already initialized, skipping duplicate initialization.");
            return;
        }
        initialized = true;

        LOGGER.info("[CSTMM - Main] Initializing mod...");

        // 按序注册各子系统：网络通道 → 指令 → 事件监听 → 生命周期/每 tick 回调
        NetworkHandler.register();
        registerCommands();
        EventListener.register();
        registerServerEvents();
        registerTickListener();

        LOGGER.info("[CSTMM - Main] Mod initialized successfully!");
    }

    // 注册 /match 等服务端指令（ModCommands::register 为具体实现）
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register(ModCommands::register);
    }

    // 注册服务器生命周期回调：启动时加载配置与数据，停服时持久化保存
    private void registerServerEvents() {
        // 服务器启动完成：缓存实例并加载全局配置、玩家数据、背包存储
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            LOGGER.info("[CSTMM - Main] Server started, loading configurations...");
            ConfigManager.getInstance().load();
            PlayerDataManager.getInstance().loadAll();
            // initialize 内部会设置 RegistryLookup 并调用 loadBags，无需单独调用
            InventoryManager.initialize(server.getRegistryManager());
        });

        // 服务器即将关闭：持久化玩家数据与背包存储，防止数据丢失
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[CSTMM - Main] Server stopping, saving all data...");
            PlayerDataManager.getInstance().saveAll();
            InventoryManager.getInstance().saveAll();
        });

        // 服务器已完全停止：清除缓存实例，避免悬挂引用
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            serverInstance = null;
        });
    }

    // 注册每服务器 tick 回调：驱动对局调度器（计时/开局/结算）
    private void registerTickListener() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MatchScheduler.getInstance().onTick(server);
            // 修复P0-2：VoteManager.tick() 已移至 MatchScheduler.onSecondTick()
        });
    }

    /** 【作用】获取当前服务器实例，供各管理器访问玩家/世界/注册表（服务端）。 */
    public static MinecraftServer getServer() {
        return serverInstance;
    }

    /** 【作用】查询模组是否已完成初始化（当前项目内暂无调用方，保留的状态查询入口）。 */
    public static boolean isInitialized() {
        return initialized;
    }
}