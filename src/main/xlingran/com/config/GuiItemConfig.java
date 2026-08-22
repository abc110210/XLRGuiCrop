package xlingran.com.config;

import java.util.List;

/**
 * gui.yml 单个 GUI 条目的配置（材质 / 槽位 / 名称 / Lore）。
 *
 * <p>材质保存原始字符串（可能是 `%icon%` 这类占位符，渲染时由调用方解析）；
 * 材质或名称为空时由调用方回退代码默认值；slot = -1 表示动态槽位（翻页/内部格）。
 */
public final class GuiItemConfig {

    /** 材质原始值（可为空 / 固定材质名 / %占位符%）。 */
    private final String rawMaterial;
    private final int slot;
    private final String name;
    private final List<String> lore;

    public GuiItemConfig(String rawMaterial, int slot, String name, List<String> lore) {
        this.rawMaterial = rawMaterial;
        this.slot = slot;
        this.name = name;
        this.lore = lore;
    }

    public String getRawMaterial() { return rawMaterial; }

    /** 固定槽位；-1 表示动态。 */
    public int getSlot() { return slot; }

    public String getName() { return name; }

    public List<String> getLore() { return lore; }
}
