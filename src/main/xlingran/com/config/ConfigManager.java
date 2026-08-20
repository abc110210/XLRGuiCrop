package xlingran.com.config;

import org.bukkit.Material;

/**
 * 全局硬编码配置集中管理。
 *
 * <p>所有可配置项集中于此，后续迁移 config.yml 时逐项替换（每项已标注 TODO yml）。
 * 版本：Spigot API 26.2 / Java 25。
 */
public final class ConfigManager {

    private ConfigManager() {}

    // ===== GUI 标题 =====
    /** 农田 GUI 标题 */ // TODO yml: gui.farm.title
    public static final String GUI_FARM_TITLE = "农田";
    /** 二级生长 GUI 标题 */ // TODO yml: gui.farm.growth-title
    public static final String GUI_GROWTH_TITLE = "小麦农田";
    /** 农作物仓库 GUI 标题 */ // TODO yml: gui.warehouse.title
    public static final String GUI_CROP_MENU_TITLE = "农作物仓库";
    /** 多页仓库 GUI 标题前缀 */ // TODO yml: gui.warehouse.page-title
    public static final String GUI_WAREHOUSE_TITLE = "仓库";

    // ===== 材质 =====
    /** 外框黑玻璃 */ // TODO yml: gui.frame-material
    public static final Material FRAME_MATERIAL = Material.BLACK_STAINED_GLASS_PANE;
    /** 空农田位占位灰玻璃 */ // TODO yml: gui.farm.empty-slot-material
    public static final Material EMPTY_FARM_MATERIAL = Material.GRAY_STAINED_GLASS_PANE;

    // ===== 农田 GUI（分页）=====
    /** 每页农田格数 */ // TODO yml: gui.farm.page-slots
    public static final int FARM_PAGE_SLOTS = 28;
    /** 上一页按钮槽位（第6行第3格） */ // TODO yml: gui.farm.prev-slot
    public static final int FARM_PREV_SLOT = 47;
    /** 下一页按钮槽位（第6行第5格，箱子，固定展示） */ // TODO yml: gui.farm.next-slot
    public static final int FARM_NEXT_SLOT = 49;

    // ===== 农作物仓库 GUI =====
    /** 小麦种子仓库入口槽位（第2行第2格） */ // TODO yml: gui.crop-menu.seed-slot
    public static final int CROP_MENU_SEED_SLOT = 10;
    /** 小麦仓库入口槽位（第2行第3格） */ // TODO yml: gui.crop-menu.wheat-slot
    public static final int CROP_MENU_WHEAT_SLOT = 11;

    // ===== 多页仓库 GUI =====
    /** 每页展示格数 */ // TODO yml: gui.warehouse.page-slots
    public static final int WAREHOUSE_PAGE_SLOTS = 28;
    /** 单格容量 */ // TODO yml: gui.warehouse.stack-size
    public static final int WAREHOUSE_STACK = 64;
    /** 填充按钮槽位（第6行第5格，箱子，固定） */ // TODO yml: gui.warehouse.fill-slot
    public static final int WAREHOUSE_FILL_SLOT = 49;
    /** 上一页槽位（第6行第3格） */ // TODO yml: gui.warehouse.prev-slot
    public static final int WAREHOUSE_PREV_SLOT = 47;
    /** 下一页槽位（第6行第7格） */ // TODO yml: gui.warehouse.next-slot
    public static final int WAREHOUSE_NEXT_SLOT = 51;

    // ===== 作物参数（wheat 占位值）=====
    /** 生长随机时长下限（小时） */ // TODO yml: crops.wheat.grow-min-hour
    public static final int GROW_MIN_HOUR = 2;
    /** 生长随机时长上限（小时） */ // TODO yml: crops.wheat.grow-max-hour
    public static final int GROW_MAX_HOUR = 4;
    /** 收割产量：小麦 */ // TODO yml: crops.wheat.yield-wheat
    public static final int YIELD_WHEAT = 1;
    /** 收割产量：种子 */ // TODO yml: crops.wheat.yield-seed
    public static final int YIELD_SEED = 1;
    /** 是否消耗种子 */ // TODO yml: crops.wheat.consume-seed
    public static final boolean CONSUME_SEED = false;

    // ===== 调度 =====
    /** 定时器间隔（秒） */ // TODO yml: plugin.tick-interval
    public static final int TICK_INTERVAL_SEC = 60;

    // ===== 玩家提示文案 =====
    /** 创建农田成功 */ // TODO yml: msg.crop-created
    public static final String MSG_CROP_CREATED = "§a成功创建 §f小麦农田 §a（第 %page% 页 · 第 %slot% 格）！";
    /** 农田全部页已满 */ // TODO yml: msg.farm-full
    public static final String MSG_FARM_FULL = "§c所有农田页已满，无法继续创建农田！";
    /** 翻页前提未满足 */ // TODO yml: msg.page-locked
    public static final String MSG_PAGE_LOCKED = "§c当前页 28 格农田已满后才能进入下一页！";
    /** 点击空农田位 */ // TODO yml: msg.empty-farm-click
    public static final String MSG_EMPTY_FARM_CLICK = "§e该农田位为空，请使用 §b/xlr crop wheat §e创建农田。";
    /** 取出成功 */ // TODO yml: msg.take-success
    public static final String MSG_TAKE_SUCCESS = "§a已取出 %qty% 个到背包。";
    /** 背包已满 */ // TODO yml: msg.inv-full
    public static final String MSG_INV_FULL = "§c背包已满，无法取出物品！";
    /** 后备库存为空 */ // TODO yml: msg.no-backup
    public static final String MSG_NO_BACKUP = "§c后备库存为空，无需填充。";
    /** 无权限 */ // TODO yml: msg.no-perm
    public static final String MSG_NO_PERM = "§c你没有权限使用该指令。";
}
