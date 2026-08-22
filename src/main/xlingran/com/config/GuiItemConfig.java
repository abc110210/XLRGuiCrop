package xlingran.com.config;

import org.bukkit.Material;

import java.util.List;

/**
 * gui.yml 单个 GUI 条目的配置（材质 / 槽位 / 名称 / Lore）。
 *
 * <p>材质或名称为空时由调用方回退代码默认值；slot = -1 表示动态槽位（翻页/内部格）。
 */
public final class GuiItemConfig {

    private final Material material;
    private final int slot;
    private final String name;
    private final List<String> lore;

    public GuiItemConfig(Material material, int slot, String name, List<String> lore) {
        this.material = material;
        this.slot = slot;
        this.name = name;
        this.lore = lore;
    }

    public Material getMaterial() { return material; }

    /** 固定槽位；-1 表示动态。 */
    public int getSlot() { return slot; }

    public String getName() { return name; }

    public List<String> getLore() { return lore; }
}
