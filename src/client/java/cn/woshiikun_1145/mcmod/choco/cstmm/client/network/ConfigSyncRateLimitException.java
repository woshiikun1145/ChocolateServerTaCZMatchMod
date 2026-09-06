package cn.woshiikun_1145.mcmod.choco.cstmm.client.network;

/**
 * 客户端每秒配置同步请求超过 50 次时抛出。
 * 未捕获异常会导致游戏崩溃（反滥用保护）。
 */
public class ConfigSyncRateLimitException extends RuntimeException {
    public ConfigSyncRateLimitException(String message) {
        super(message);
    }
}
