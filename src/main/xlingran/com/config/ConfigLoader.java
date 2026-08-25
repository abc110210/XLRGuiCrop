package xlingran.com.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import xlingran.com.Shan;
import xlingran.com.crop.CropRegistry;
import xlingran.com.crop.CropType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置加载器：把 jar 内 config.yml / gui.yml 释放到数据目录并解析。
 *
 * <p>加载顺序：{@link ConfigManager#apply} 填充通用配置（GUI 布局来自 gui.yml，其余来自 config.yml），
 * 随后 {@link #parseCrops} 解析 crops 段注册全部作物。
 * 颜色代码统一 & → §。
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    public static void load(Shan plugin) {
        File folder = plugin.getDataFolder();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(extract(plugin, folder, "config.yml"));
        YamlConfiguration gui = YamlConfiguration.loadConfiguration(extract(plugin, folder, "gui.yml"));
        ConfigManager.apply(cfg, gui);
        CropRegistry.registerAll(parseCrops(plugin, cfg, gui));
        plugin.getLogger().info("Loaded config.yml + gui.yml, crops: " + CropRegistry.all().size());
    }

    /** jar 内资源不存在于数据目录时释放一份，返回数据目录中的文件。 */
    private static File extract(Shan plugin, File folder, String name) {
        File f = new File(folder, name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }
        return f;
    }

    /**
     * 解析 config.yml 的 crops 段为作物注册项。
     *
     * <p>产量（yield-product / yield-seed）由 gui.yml 的 FarmUpdate.&lt;id&gt;.LV1.Drop / SeedDrop 决定，
     * 该作物未在 FarmUpdate 配置时回退默认 1；缺省字段用安全默认值。
     */
    private static List<CropType> parseCrops(Shan plugin, YamlConfiguration cfg, YamlConfiguration gui) {
        List<CropType> list = new ArrayList<>();
        ConfigurationSection sec = cfg.getConfigurationSection("crops");
        if (sec == null) {
            return list;
        }
        for (String key : sec.getKeys(false)) {
            // 统一小写：避免与 DB/注册表/等级配置大小写不一致导致配置失效
            String id = key.toLowerCase();
            ConfigurationSection c = sec.getConfigurationSection(key);
            if (c == null) {
                continue;
            }
            try {
                // 初始产量（LV1）以 gui.yml FarmUpdate 为准；未配置该作物时默认 1
                int yieldProduct = Math.max(0, gui.getInt("FarmUpdate." + id + ".LV1.Drop", 1));
                int yieldSeed = Math.max(0, gui.getInt("FarmUpdate." + id + ".LV1.SeedDrop", 1));
                // 生长时长：先按 long 计算避免 int 溢出，再钳制并保证 max >= min
                int[] grow = growSecRange(c);
                // 种子素材：支持 seed-materials 列表（多种素材，各自独立仓库）或 seed-material 单值（列表单元素）
                List<Material> seedMats = seedMaterials(c);
                // 收割入仓材质：优先取 config 的 harvest-material（未配置为 null）→
                // 否则查内置映射（如西瓜成熟展示 MELON、入仓 MELON_SLICE）→ 再否则=产物材质
                Material productMat = material(c, "product-material", Material.WHEAT);
                Material harvestMat = material(c, "harvest-material", null);
                if (harvestMat == null) {
                    harvestMat = builtinHarvestMaterial(productMat);
                    if (harvestMat == null) {
                        harvestMat = productMat;
                    }
                }
                list.add(new CropType(
                        id,
                        seedMats,
                        productMat,
                        harvestMat,
                        str(c, "name", id),
                        str(c, "farm-name", str(c, "name", id) + "农田"),
                        grow[0],
                        grow[1],
                        yieldProduct,
                        yieldSeed,
                        c.getBoolean("consume-seed", true),
                        c.getBoolean("show-stage-change", false),
                        Math.max(1, c.getInt("show-product-stage", 3))));
            } catch (IllegalArgumentException e) {
                // 单个作物配置非法：跳过该作物，不影响其余
                plugin.getLogger().warning("crop '" + id + "' config invalid, skipped: " + e.getMessage());
            }
        }
        return list;
    }

    /**
     * 生长时长区间（秒）：按 long 计算防 int 溢出，钳制到 [1, Integer.MAX_VALUE]，
     * 并保证 max >= min（min > max 时 max 提升为 min）。
     */
    private static int[] growSecRange(ConfigurationSection c) {
        long min = c.getLong("grow-min-hour", 2) * 3600L;
        long max = c.getLong("grow-max-hour", 4) * 3600L;
        int growMin = (int) Math.max(1, Math.min(min, Integer.MAX_VALUE));
        int growMax = (int) Math.max(growMin, Math.min(max, Integer.MAX_VALUE));
        return new int[]{growMin, growMax};
    }

    private static Material material(ConfigurationSection c, String path, Material def) {
        String s = c.getString(path);
        if (s == null || s.isBlank()) {
            return def;
        }
        Material m = Material.matchMaterial(s.trim());
        return m != null ? m : def;
    }

    /**
     * 解析种子素材列表：优先 seed-materials（列表），否则回退 seed-material（单值），均无法解析时用小麦种子兜底。
     * 非法项跳过。返回顺序即播种自动消耗的优先顺序（靠前者先消耗）。
     */
    private static List<Material> seedMaterials(ConfigurationSection c) {
        List<String> names = c.getStringList("seed-materials");
        if (names.isEmpty()) {
            String single = c.getString("seed-material");
            if (single != null && !single.isBlank()) {
                names = List.of(single);
            }
        }
        List<Material> list = new ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            Material m = Material.matchMaterial(n.trim());
            if (m != null && !list.contains(m)) {
                list.add(m);
            }
        }
        if (list.isEmpty()) {
            list.add(Material.WHEAT_SEEDS);
        }
        return list;
    }

    /**
     * 内置「展示材质 → 收割入仓材质」映射（无需在 config.yml 写 harvest-material）：
     * 西瓜成熟展示 MELON，收割入仓为西瓜片 MELON_SLICE；未映射返回 null。
     */
    private static Material builtinHarvestMaterial(Material product) {
        if (product == Material.MELON) {
            return Material.MELON_SLICE;
        }
        return null;
    }

    private static String str(ConfigurationSection c, String path, String def) {
        String s = c.getString(path);
        return (s == null || s.isBlank()) ? def : s.replace('&', '§');
    }
}
