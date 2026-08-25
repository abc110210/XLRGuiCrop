package xlingran.com.crop;

import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 作物类型定义（作物注册表条目）。
 *
 * <p>每个作物包含：标识、种子素材列表（可多种，均作为播种素材且各自独立仓库）、产物材料、名称、农田名、
 * 生长时长区间（秒）、收割产量、是否消耗种子、是否按生长阶段变化显示。
 * 定义由 config.yml 的 crops 段驱动，见 {@code ConfigLoader}。
 */
public final class CropType {

    private final String id;
    /** 种子素材列表（可多种，如苹果可用多种树苗）；第 1 个为主素材，用作播种/种下图标。 */
    private final List<Material> seedMaterials;
    private final Material productMaterial;
    /** 收割入仓库/取出的产物材质（如西瓜：成熟展示 MELON、入仓为 MELON_SLICE）；缺省=productMaterial。 */
    private final Material harvestMaterial;
    private final String name;
    private final String farmName;
    private final int growMinSec;
    private final int growMaxSec;
    private final int yieldProduct;
    private final int yieldSeed;
    private final boolean consumeSeed;
    private final boolean showStageChange;
    /** 生长界面从该阶段起展示 product-material 图标（此前展示 seed-material）。 */
    private final int showProductStage;

    public CropType(String id, List<Material> seedMaterials, Material productMaterial, Material harvestMaterial,
                    String name, String farmName,
                    int growMinSec, int growMaxSec,
                    int yieldProduct, int yieldSeed, boolean consumeSeed,
                    boolean showStageChange, int showProductStage) {
        this.id = id;
        this.seedMaterials = seedMaterials.isEmpty()
                ? List.of(harvestMaterial != null ? harvestMaterial : Material.WHEAT_SEEDS)
                : seedMaterials;
        this.productMaterial = productMaterial;
        this.harvestMaterial = harvestMaterial != null ? harvestMaterial : productMaterial;
        this.name = name;
        this.farmName = farmName;
        this.growMinSec = growMinSec;
        this.growMaxSec = growMaxSec;
        this.yieldProduct = yieldProduct;
        this.yieldSeed = yieldSeed;
        this.consumeSeed = consumeSeed;
        this.showStageChange = showStageChange;
        this.showProductStage = Math.max(1, showProductStage);
    }

    public String getId() { return id; }

    /**
     * 全部种子素材（播种可用的材质）。玩家播种时按此顺序自动消耗（背包优先、各素材仓库其次）。
     */
    public List<Material> getSeedMaterials() { return seedMaterials; }

    /**
     * 主种子素材（第 1 个）。图标 / 生长前期展示 / 收割回种默认用主素材。
     */
    public Material getSeedMaterial() { return seedMaterials.get(0); }

    /**
     * GUI 图标（种子素材，即刚种下/未成熟的图标）。原 icon 配置已废弃。
     */
    public Material getIcon() { return seedMaterials.get(0); }

    /** 成熟/产物展示材质（生长界面、成熟图标用）。 */
    public Material getProductMaterial() { return productMaterial; }

    /** 收割入仓/取出材质（虚拟仓库展示/取出；如西瓜=西瓜片 MELON_SLICE）。 */
    public Material getHarvestMaterial() { return harvestMaterial; }

    /** 作物显示名（如「小麦」）。 */
    public String getName() { return name; }

    /** 农田名（如「小麦农田」）。 */
    public String getFarmName() { return farmName; }

    public int getGrowMinSec() { return growMinSec; }

    public int getGrowMaxSec() { return growMaxSec; }

    /** 每周期产物收获数。 */
    public int getYieldProduct() { return yieldProduct; }

    /** 每周期种子收获数。 */
    public int getYieldSeed() { return yieldSeed; }

    /**
     * 是否有独立种子素材（主种子素材材质 ≠ 产物材质）。
     * 主素材与产物相同（如土豆/胡萝卜用本体当种子）时，仓库不单独出种子入口。
     */
    public boolean hasSeed() {
        return getSeedMaterial() != productMaterial;
    }

    public boolean isConsumeSeed() { return consumeSeed; }

    /** 生长界面是否按阶段变化显示（false 则始终显示成品图标）。 */
    public boolean isShowStageChange() { return showStageChange; }

    /** 生长界面从该阶段起展示成品图标（此前展示种子图标）。 */
    public int getShowProductStage() { return showProductStage; }

    /** 随机生成一个本周期生长总时长（秒），区间 [min, max]。 */
    public int randomDurationSec() {
        if (growMaxSec <= growMinSec) {
            return growMaxSec;
        }
        return growMinSec + ThreadLocalRandom.current().nextInt(growMaxSec - growMinSec + 1);
    }

    @Override
    public String toString() {
        return "CropType{id=" + id + ", seeds=" + seedMaterials + ", product=" + productMaterial + "}";
    }
}