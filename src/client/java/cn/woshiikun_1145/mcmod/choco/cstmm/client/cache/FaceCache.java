package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 【作用】客户端"自己头像绑定"缓存：服务端随档案 JSON（PlayerProfilePayload 的
 *         avatarType/avatarId 字段）下发，供履历页头像、个性化页回显读取。
 *         战队成员绑定不在此缓存（随 ClanCache.MemberEntry 下发），
 *         队列页自己的绑定随 QueueStatusCache.Snapshot.Own 下发。
 *         头像图片不在此解析——统一由 FaceImageCache 按 (type, id) 自行获取。
 * 【被谁使用】ClientNetworkHandler 的 PlayerProfilePayload 接收器写入、DISCONNECT 回调清空；
 *             MatchMenuScreen#drawProfile、PersonalizeTabPanel 读取。
 */
@Environment(EnvType.CLIENT)
public final class FaceCache {

    // 单例工具类，禁止实例化
    private FaceCache() {}

    // 自己的头像绑定（"qq"/"bili" + 账号数字 ID；空 type = 未设置；volatile：网络线程回调写、渲染线程读）
    private static volatile String ownType = "";
    private static volatile String ownId = "";

    /** 覆盖写入自己的头像绑定（服务端档案同步携带；空 type 表示未设置/已清除） */
    public static void setOwn(String type, String id) {
        ownType = type == null ? "" : type;
        ownId = id == null ? "" : id;
    }

    /** 读取自己的头像绑定平台（"qq"/"bili"；空串 = 未设置；永不为 null） */
    public static String getOwnType() {
        return ownType;
    }

    /** 读取自己的头像绑定账号 ID（QQ号 / B站 UID） */
    public static String getOwnId() {
        return ownId;
    }

    /** 自己是否已设置头像 */
    public static boolean hasOwn() {
        return !ownType.isEmpty() && !ownId.isEmpty();
    }

    /** 断线时清空（绑定按服务器存储，重连后由档案同步重新下发） */
    public static void clear() {
        ownType = "";
        ownId = "";
    }
}
