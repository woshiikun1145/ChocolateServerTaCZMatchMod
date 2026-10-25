package cn.woshiikun_1145.mcmod.choco.cstmm.client.cache;

import cn.woshiikun_1145.mcmod.choco.cstmm.network.payload.ShopDataS2CPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店数据缓存
 * 保存最近一次 ShopDataS2CPayload 下发的数据（购买资格 + 购买页网格布局 + 爆破经济信息），
 * 供 ShopScreen 读取。列结构：爆破图 columns[0] = "装备"列（拆弹装备购买项由 hasDefuser 决定，
 * T 方空列），columns[1..4] = 分类列；普通图 columns[0..4] = 分类列。
 * 【被谁使用】ClientNetworkHandler 的 ShopDataS2CPayload 接收器写入、DISCONNECT 回调清空；
 *             ShopScreen#init/render 读取渲染商城界面（5 列网格布局/余额/购买窗口）。
 */
@Environment(EnvType.CLIENT)
public class ShopDataCache {
    // 懒加载单例实例
    private static ShopDataCache instance;

    // 当前玩家是否满足购买资格（服务端判定）
    private boolean eligible = false;
    // 是否爆破图商店（走经济系统：余额/购买窗口/第 1 列固定拆弹装备）
    private boolean bombMode = false;
    // 爆破图第 1 列是否显示拆弹装备购买项（CT=true；T=false 显示空列）
    private boolean hasDefuser = false;
    /** null 表示尚未收到服务端下发的商店数据 */
    private List<ShopDataS2CPayload.ColumnData> columns = null;
    // 当前匹配内货币余额（仅爆破图有意义）
    private int money = 0;
    // 购买窗口是否开启（冻结时间 + 开局后 N 秒；炸弹安放后禁买）
    private boolean buyWindowOpen = false;
    // 拆弹装备价格（爆破图购买页第 1 列固定项）
    private int defuserPrice = 0;

    // 单例，禁止外部实例化
    private ShopDataCache() {}

    // 懒加载单例入口
    public static ShopDataCache getInstance() {
        if (instance == null) {
            instance = new ShopDataCache();
        }
        return instance;
    }

    // 覆盖写入完整商店数据（购买页网格布局 + 爆破经济信息）
    public void update(boolean eligible, boolean bombMode, boolean hasDefuser,
                       int money, boolean buyWindowOpen, int defuserPrice,
                       List<ShopDataS2CPayload.ColumnData> columns) {
        this.eligible = eligible;
        this.bombMode = bombMode;
        this.hasDefuser = hasDefuser;
        this.money = Math.max(0, money);
        this.buyWindowOpen = buyWindowOpen;
        this.defuserPrice = Math.max(0, defuserPrice);
        this.columns = columns != null ? List.copyOf(columns) : new ArrayList<>();
    }

    // 当前玩家是否可购买
    public boolean isEligible() { return eligible; }

    /** 是否已收到服务端下发的商店数据（区分"未收到"与"收到但不可购买"） */
    public boolean hasData() { return columns != null; }

    // 是否爆破图商店（ShopScreen 按此切换第 1 列拆弹装备与余额显示）
    public boolean isBombMode() { return bombMode; }

    // 爆破图第 1 列是否显示拆弹装备购买项（CT 方）
    public boolean hasDefuser() { return hasDefuser; }

    // 购买页列数据副本（未收到数据时返回空列表）
    public List<ShopDataS2CPayload.ColumnData> getColumns() {
        return columns != null ? new ArrayList<>(columns) : new ArrayList<>();
    }

    // 当前匹配内货币余额（爆破图）
    public int getMoney() { return money; }

    // 购买窗口是否开启（爆破图）
    public boolean isBuyWindowOpen() { return buyWindowOpen; }

    // 拆弹装备价格（爆破图第 1 列固定项）
    public int getDefuserPrice() { return defuserPrice; }

    // 断线时清空缓存
    public void clear() {
        eligible = false;
        bombMode = false;
        hasDefuser = false;
        columns = null;
        money = 0;
        buyWindowOpen = false;
        defuserPrice = 0;
    }
}
