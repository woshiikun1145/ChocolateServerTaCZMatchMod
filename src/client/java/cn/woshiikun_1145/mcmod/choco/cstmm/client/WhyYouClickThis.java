package cn.woshiikun_1145.mcmod.choco.cstmm.client;

/**
 * 【作用】"千万别点"按钮的崩溃异常。
 * 携带玩家遗言作为异常消息，随崩溃报告一同记录。
 * 【被谁使用】仅 LastWordsScreen#throwIfCrashPending 抛出（由 CstmmClient 客户端 tick 调用）。
 */
public class WhyYouClickThis extends RuntimeException {

    // 【作用】构造：遗言文本作为异常消息
    public WhyYouClickThis(String message) {
        super(message);
    }
}
