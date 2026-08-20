package xlingran.com.crop;

import org.bukkit.Material;
import xlingran.com.config.ConfigManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作物注册表：集中注册全部作物类型。
 *
 * <p>新增作物只需在 {@link #registerDefaults()} 中追加一条注册项即可；
 * 参数由 ConfigManager 常量提供（均为 TODO yml 迁移点）。
 */
public final class CropRegistry {

    private static final Map<String, CropType> CROPS = new LinkedHashMap<>();

    private CropRegistry() {}

    /** 注册内置作物（当前硬编码，后续迁移 config.yml）。 */
    public static void registerDefaults() {
        // TODO yml: crops.wheat.* 从 config 读取
        register(new CropType(
                "wheat",
                Material.WHEAT,
                "小麦农田",
                ConfigManager.GROW_MIN_HOUR * 3600,
                ConfigManager.GROW_MAX_HOUR * 3600,
                ConfigManager.YIELD_WHEAT,
                ConfigManager.YIELD_SEED,
                ConfigManager.CONSUME_SEED));
    }

    public static void register(CropType type) {
        CROPS.put(type.getId().toLowerCase(), type);
    }

    /** 按 id（大小写不敏感）查询，未知返回 null。 */
    public static CropType get(String id) {
        return CROPS.get(id == null ? "" : id.toLowerCase());
    }

    public static Map<String, CropType> all() {
        return Collections.unmodifiableMap(CROPS);
    }
}
