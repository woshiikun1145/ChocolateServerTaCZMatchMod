package cn.woshiikun_1145.mcmod.choco.cstmm.client;

/**
 * "千万别点"按钮的专属崩溃异常：谁让你点的？
 * 携带玩家遗言作为异常消息，随崩溃报告一同记录。
 */
public class WhyYouClickThis extends RuntimeException {

    public WhyYouClickThis(String message) {
        super(message);
    }
}
