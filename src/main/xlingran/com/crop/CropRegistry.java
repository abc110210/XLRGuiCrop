package xlingran.com.crop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作物注册表：全部作物类型由 config.yml 的 crops 段驱动。
 *
 * <p>{@link ConfigLoader} 启动时解析 config.yml 后调用 {@link #registerAll(List)} 注册；
 * 后续新增/修改作物只需编辑 config.yml，无需改代码。
 */
public final class CropRegistry {

    private static final Map<String, CropType> CROPS = new LinkedHashMap<>();

    private CropRegistry() {}

    /** 清空并注册 config.yml 解析出的全部作物。 */
    public static void registerAll(List<CropType> types) {
        CROPS.clear();
        if (types != null) {
            for (CropType t : types) {
                register(t);
            }
        }
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
