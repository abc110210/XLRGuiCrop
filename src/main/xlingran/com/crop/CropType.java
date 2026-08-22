package xlingran.com.crop;

import org.bukkit.Material;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 作物类型定义（作物注册表条目）。
 *
 * <p>每个作物包含：标识、图标、名称、农田名、种子/产物材料、生长时长区间（秒）、
 * 收割产量、是否消耗种子、是否按生长阶段变化显示。
 * 定义由 config.yml 的 crops 段驱动，见 {@code ConfigLoader}。
 */
public final class CropType {

    private final String id;
    private final Material icon;
    private final Material seedMaterial;
    private final Material productMaterial;
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

    public CropType(String id, Material icon, Material seedMaterial, Material productMaterial,
                    String name, String farmName,
                    int growMinSec, int growMaxSec,
                    int yieldProduct, int yieldSeed, boolean consumeSeed,
                    boolean showStageChange, int showProductStage) {
        this.id = id;
        this.icon = icon;
        this.seedMaterial = seedMaterial;
        this.productMaterial = productMaterial;
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

    /** GUI 展示图标（农田/生长/创建条目）。 */
    public Material getIcon() { return icon; }

    /** 种植/补种/重播消耗的种子材料（背包侧）。 */
    public Material getSeedMaterial() { return seedMaterial; }

    /** 收割产物材料（虚拟仓库展示/取出）。 */
    public Material getProductMaterial() { return productMaterial; }

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
}
