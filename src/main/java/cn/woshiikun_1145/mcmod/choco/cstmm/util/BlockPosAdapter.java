package cn.woshiikun_1145.mcmod.choco.cstmm.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import com.google.gson.*;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Type;

/**
 * 【作用】Gson 序列化适配器，用于将 BlockPos 序列化为 {x, y, z} JSON 对象。
 * 在 ConfigManager 中注册，使 maps.json 可正确读写 BlockPos 列表。
 * 【被谁使用】ConfigManager 与 NetworkHandler 各自注册到 GsonBuilder（服务端），
 * 覆盖地图出生点等所有 BlockPos 字段的 JSON 读写。
 */
public class BlockPosAdapter implements JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {

    // 序列化：BlockPos → {"x":..,"y":..,"z":..} JSON 对象
    @Override
    public JsonElement serialize(BlockPos src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", src.getX());
        obj.addProperty("y", src.getY());
        obj.addProperty("z", src.getZ());
        return obj;
    }

    // 反序列化：{"x":..,"y":..,"z":..} JSON 对象 → BlockPos；空 JSON 返回 null 由上层跳过
    @Override
    public BlockPos deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        // JSON null 直接返回 null，避免解析异常
        if (json == null || json.isJsonNull()) {
            return null;
        }
        try {
            JsonObject obj = json.getAsJsonObject();
            int x = obj.get("x").getAsInt();
            int y = obj.get("y").getAsInt();
            int z = obj.get("z").getAsInt();
            return new BlockPos(x, y, z);
        } catch (RuntimeException e) {
            // 键缺失/类型错误等坏数据：返回 null 让上层跳过该条，
            // 避免单条坐标损坏导致整个 maps.json 加载失败后被空配置回写
            Cstmm.LOGGER.warn("[CSTMM - BlockPosAdapter] Skipping malformed BlockPos entry: {}", json, e);
            return null;
        }
    }
}