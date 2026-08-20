package xlingran.com.crop;

import org.bukkit.Material;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 作物类型定义（作物注册表条目）。
 *
 * <p>每个作物包含：标识、图标、名称、生长时长区间（秒）、收割产量、是否消耗种子。
 */
public final class CropType {

    private final String id;
    private final Material icon;
    private final String name;
    private final int growMinSec;
    private final int growMaxSec;
    private final int yieldWheat;
    private final int yieldSeed;
    private final boolean consumeSeed;

    public CropType(String id, Material icon, String name,
                    int growMinSec, int growMaxSec,
                    int yieldWheat, int yieldSeed, boolean consumeSeed) {
        this.id = id;
        this.icon = icon;
        this.name = name;
        this.growMinSec = growMinSec;
        this.growMaxSec = growMaxSec;
        this.yieldWheat = yieldWheat;
        this.yieldSeed = yieldSeed;
        this.consumeSeed = consumeSeed;
    }

    public String getId() { return id; }

    public Material getIcon() { return icon; }

    public String getName() { return name; }

    public int getGrowMinSec() { return growMinSec; }

    public int getGrowMaxSec() { return growMaxSec; }

    public int getYieldWheat() { return yieldWheat; }

    public int getYieldSeed() { return yieldSeed; }

    public boolean isConsumeSeed() { return consumeSeed; }

    /** 随机生成一个本周期生长总时长（秒），区间 [min, max]。 */
    public int randomDurationSec() {
        if (growMaxSec <= growMinSec) {
            return growMaxSec;
        }
        return growMinSec + ThreadLocalRandom.current().nextInt(growMaxSec - growMinSec + 1);
    }
}
