package cn.woshiikun_1145.mcmod.choco.cstmm.item;

import cn.woshiikun_1145.mcmod.choco.cstmm.entity.GrenadeType;
import cn.woshiikun_1145.mcmod.choco.cstmm.util.GrenadeHooks;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * 【作用】CS 手雷物品基类（移植自 CounterStrikeGrenades 的 CounterStrikeGrenadeItem）。
 *         承担：类型绑定（grenadeType）、拔销音（切换到该槽位时本地播放一次）、
 *         攻击速度属性（+1000 使左键几乎不挥动，投掷手感由客户端 ThrowActionHandler 接管）。
 *         投掷本身不通过 Item.use()——原 mod 的投掷是客户端输入拦截 + C2S 投掷包，
 *         见 client/grenade/input/ThrowActionHandler 与 GrenadeThrownPayload。
 * 【被谁使用】HEGrenadeItem / FlashBangItem / SmokeGrenadeItem（阶段 1）、
 *           ThrowActionHandler（读取 grenadeType 触发投掷）、
 *           GrenadeThrownPayload（服务端校验手持物品类型后扣减数量）。
 */
public class CounterStrikeGrenadeItem extends Item {

    /** 记录上次播放拔销音的槽位，避免每 tick 重复播放（原 mod 同款实现，客户端使用） */
    private static int drawSoundPlayedSlot = -1;

    /** 该手雷对应的投掷物类型（服务端据此生成实体、客户端据此发起投掷包） */
    public final GrenadeType grenadeType;

    /** 拔销音（切换手持时本地播放） */
    public final SoundEvent drawSound;

    public CounterStrikeGrenadeItem(GrenadeType type, SoundEvent drawSound, Settings settings) {
        super(settings);
        this.grenadeType = type;
        this.drawSound = drawSound;
    }

    /**
     * 【作用】构造"攻击速度 +1000"属性组件：手持手雷时左键挥动速度变为不可感知，
     *         模拟 CS 中左键用于投掷而非攻击的手感（原 mod getAttributeModifiers 移植，
     *         1.21.1 以数据组件形式在物品 Settings 上声明）。
     * 【被谁使用】ModItems（三个手雷物品的 Settings 构造）。
     */
    public static AttributeModifiersComponent preventSwingModifiers() {
        return new AttributeModifiersComponent(
                java.util.List.of(new AttributeModifiersComponent.Entry(
                        EntityAttributes.GENERIC_ATTACK_SPEED,
                        new EntityAttributeModifier(
                                Identifier.of("cstmm", "grenade_attack_speed"),
                                1000.0,
                                EntityAttributeModifier.Operation.ADD_VALUE),
                        AttributeModifierSlot.MAINHAND)),
                true);
    }

    // 【作用】物品在玩家物品栏每 tick 回调：首次选中该槽位时本地播放拔销音（仅客户端）
    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world.isClient && entity instanceof PlayerEntity player) {
            if (selected && drawSoundPlayedSlot != slot && GrenadeHooks.client != null) {
                GrenadeHooks.client.playDrawSound(drawSound);
                drawSoundPlayedSlot = slot;
            }
            // 【作用】玩家选中槽位已离开拔销音槽位 → 允许下次重新播放
            if (player.getInventory().selectedSlot != drawSoundPlayedSlot) {
                drawSoundPlayedSlot = -1;
            }
        }
        super.inventoryTick(stack, world, entity, slot, selected);
    }
}
