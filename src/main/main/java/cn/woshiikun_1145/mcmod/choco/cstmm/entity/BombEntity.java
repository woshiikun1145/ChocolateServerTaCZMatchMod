package cn.woshiikun_1145.mcmod.choco.cstmm.entity;

import cn.woshiikun_1145.mcmod.choco.cstmm.manager.BombInteractionManager;
import cn.woshiikun_1145.mcmod.choco.cstmm.sound.ModSounds;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.C4DamageCalculator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * 【作用】已安放的 C4 炸弹实体（绿色长方体占位外观）：安放成功后生成，固定不动、不可推动、
 *         不可被打；携带所属对局 id（matchId）用于回合重置/爆炸/拆除时精确清理
 *         （避免同维度多场爆破对局误删其他局的炸弹）。
 *         两种引爆驱动：
 *         ① 对局内炸弹（matchId = 对局 id）：爆炸倒计时由 MatchSession 会话状态驱动，
 *            实体 fuseTicks = -1 不自行倒计时；
 *         ② 自由炸弹（matchId 为空，/cstmm debug allow_c4 to_be_planted_freely 对局外安放）：
 *            fuseTicks 自行倒计时，归零先播 c4_detonation 引信音（与对局内同口径），
 *            播完经 C4DamageCalculator 对附近全员结算伤害后自毁。
 * 【被谁使用】BombInteractionManager（安放生成/拆除/爆炸清理、拆除范围判定）、
 *           BombRoundManager（回合重置清理、detonateBomb）、客户端 BombEntityRenderer（绿色长方体渲染）。
 */
public class BombEntity extends Entity {

    /** 所属对局 id（MatchSession.sessionId；自由炸弹为空串。NBT 持久化） */
    private String matchId = "";
    /** 引信剩余 tick：-1 = 对局内炸弹（由会话驱动，不自行倒计时）；>0 = 自由炸弹倒计时中 */
    private int fuseTicks = -1;
    /** 哔声冷却（tick）：归零时播放一声并按剩余时间重置间隔（越接近爆炸响得越快） */
    private int beepCooldown = 0;
    /** 消失倒计时（tick）：>0 = 已拆除等进入移除流程，归零自毁（瞬态，不持久化） */
    private int despawnTicks = -1;

    public BombEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = false;
    }

    /**
     * 【作用】进入"已拆除待消失"状态：停止引信（不再倒计时/哔声/自爆），
     *         despawnSeconds 秒后自行消失（对局外拆除的 C4 保留 10 秒供查看）。
     * 【被谁使用】BombInteractionManager（对局外自由炸弹拆除成功）。
     */
    public void beginDespawn(int despawnSeconds) {
        this.fuseTicks = -1;
        this.beepCooldown = 0;
        this.despawnTicks = Math.max(1, despawnSeconds * 20);
    }

    public String getMatchId() { return matchId; }

    public void setMatchId(String matchId) { this.matchId = matchId == null ? "" : matchId; }

    /** 是否自由炸弹（对局外安放，matchId 为空，自行倒计时自爆） */
    public boolean isFree() { return matchId.isEmpty(); }

    /** 是否已被拆除（进入待消失状态）：拆除目标判定需排除，防止对已拆除的 C4 再次拆除 */
    public boolean isDefused() { return despawnTicks > 0; }

    public int getFuseTicks() { return fuseTicks; }

    public void setFuseTicks(int fuseTicks) { this.fuseTicks = fuseTicks; }

    /** 静止实体：自由炸弹在此自行倒计时（哔声节奏 + 归零先播引信音、播完再自爆）；对局内炸弹不倒计时。 */
    @Override
    public void tick() {
        super.tick();
        // 已拆除待消失：倒计时归零自毁（实体留在原地供查看，不再倒计时/哔声）
        if (despawnTicks > 0 && --despawnTicks == 0) {
            discard();
            return;
        }
        if (fuseTicks > 0) {
            fuseTicks--;
            if (getWorld().isClient) return; // 哔声与自爆仅在服务端触发（playSound 服务端广播）
            if (fuseTicks == 0) {
                beginFreeFuse();
                return;
            }
            if (--beepCooldown <= 0) {
                int remainingSeconds = Math.max(1, (fuseTicks + 19) / 20);
                playBeep(remainingSeconds);
                beepCooldown = BombInteractionManager.beepIntervalTicks(remainingSeconds);
            }
        }
    }

    /**
     * 【作用】自由炸弹引信点燃（倒计时归零）：与对局内起爆序列同口径——先在炸弹位置
     *         世界广播 c4_detonation 引信音（音量 = 默认爆炸可闻半径/16），
     *         播完 MatchSoundTimings.DETONATION_LEAD_MILLIS 后才真正爆炸；
     *         引信窗口内实体被移除或已拆除则跳过起爆。仅服务端调用。
     * 【被谁使用】tick（fuseTicks 递减归零）。仅服务端。
     */
    private void beginFreeFuse() {
        var settings = new cn.woshiikun_1145.mcmod.choco.cstmm.data.config.MapConfig.BombSettings();
        getWorld().playSound(null, getX(), getY(), getZ(),
                ModSounds.C4_DETONATION.value(), net.minecraft.sound.SoundCategory.BLOCKS,
                settings.getExplosionSoundRange() / 16.0f, 1.0f);
        cn.woshiikun_1145.mcmod.choco.cstmm.manager.MatchScheduler.scheduleDelayed(
                cn.woshiikun_1145.mcmod.choco.cstmm.sound.MatchSoundTimings.DETONATION_LEAD_MILLIS,
                () -> {
                    // 引信播完：实体仍存在且未被拆除才真正爆炸（窗口内被拆/被清则跳过）
                    if (!isRemoved() && !isDefused()) {
                        C4DamageCalculator.detonateFree(this);
                    }
                });
    }

    /** 在炸弹位置播放一声哔声（音量 2.0 ≈ 32 格可闻，音高固定不升调）。 */
    private void playBeep(int remainingSeconds) {
        getWorld().playSound(null, getX(), getY(), getZ(),
                ModSounds.C4_BEEP.value(), net.minecraft.sound.SoundCategory.BLOCKS,
                2.0f, BombInteractionManager.beepPitch(remainingSeconds));
    }

    /** 不可被推动（玩家碰撞/活塞等）。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void pushAwayFrom(Entity entity) {
        // 不可推动：忽略一切碰撞推挤
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        // 无同步数据：客户端仅渲染绿色长方体，倒计时等经 HUD 通道
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        this.matchId = nbt.getString("MatchId");
        this.fuseTicks = nbt.getInt("FuseTicks");
        // 停服恰好落在引信窗口（fuseTicks=0 已播引信音未起爆）：重置为 1 tick，
        // 下一 tick 重新走"引信音 → 延迟起爆"序列，避免炸弹卡死不再爆炸
        if (this.fuseTicks == 0) {
            this.fuseTicks = 1;
        }
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("MatchId", this.matchId);
        nbt.putInt("FuseTicks", this.fuseTicks);
    }
}
