package cn.woshiikun_1145.mcmod.choco.cstmm.client.network;

/**
 * 客户端每秒配置同步请求超过 50 次时抛出。
 * 未捕获异常会导致游戏崩溃（反滥用保护）。
 * 【被谁使用】ClientNetworkHandler#sendConfigRequest 触发（客户端自检），无捕获方，故意令游戏崩溃。
 */
public class ConfigSyncRateLimitException extends RuntimeException {
    // 构造异常并携带提示信息
    public ConfigSyncRateLimitException(String message) {
        super(message);
    }
}
