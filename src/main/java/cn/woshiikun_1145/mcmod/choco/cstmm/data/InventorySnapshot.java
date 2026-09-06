package cn.woshiikun_1145.mcmod.choco.cstmm.data;

import net.minecraft.item.ItemStack;

import java.util.Arrays;

/**
 * 玩家背包快照（内存存储 + 持久化）
 * 对应原 inventory/ 箱子存储
 */
public class InventorySnapshot {
    private ItemStack[] mainInventory;      // 主背包 0-35
    private ItemStack[] armor;              // 盔甲 36-39
    private ItemStack offhand;              // 副手 40
    private long savedAt;

    public InventorySnapshot() {
        this.mainInventory = new ItemStack[36];
        this.armor = new ItemStack[4];
        this.offhand = ItemStack.EMPTY;
        this.savedAt = System.currentTimeMillis();
    }

    public InventorySnapshot(ItemStack[] mainInventory, ItemStack[] armor, ItemStack offhand) {
        this.mainInventory = mainInventory;
        this.armor = armor;
        this.offhand = offhand;
        this.savedAt = System.currentTimeMillis();
    }

    // Gson 反序列化会用 JSON 中的 null 直接覆盖字段，getter 内兜底为空数组，避免下游 NPE
    public ItemStack[] getMainInventory() { return mainInventory != null ? mainInventory : new ItemStack[0]; }
    public ItemStack[] getArmor() { return armor != null ? armor : new ItemStack[0]; }
    public ItemStack getOffhand() { return offhand != null ? offhand : ItemStack.EMPTY; }
    public long getSavedAt() { return savedAt; }

    public void setMainInventory(ItemStack[] mainInventory) { this.mainInventory = mainInventory; }
    public void setArmor(ItemStack[] armor) { this.armor = armor; }
    public void setOffhand(ItemStack offhand) { this.offhand = offhand; }
    public void setSavedAt(long savedAt) { this.savedAt = savedAt; }

    public boolean isEmpty() {
        for (ItemStack stack : getMainInventory()) {
            if (stack != null && !stack.isEmpty()) return false;
        }
        for (ItemStack stack : getArmor()) {
            if (stack != null && !stack.isEmpty()) return false;
        }
        return getOffhand().isEmpty();
    }

    @Override
    public String toString() {
        return "InventorySnapshot{" +
                "mainInventory=" + Arrays.toString(mainInventory) +
                ", armor=" + Arrays.toString(armor) +
                ", offhand=" + offhand +
                ", savedAt=" + savedAt +
                '}';
    }
}