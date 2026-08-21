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
    /** 二级生长 GUI 标题（回退值，实际用作物名） */ // TODO yml: gui.farm.growth-title
    public static final String GUI_GROWTH_TITLE = "小麦农田";
    /** 农田管理 GUI 标题 */ // TODO yml: gui.farm-manage.title
    public static final String GUI_FARM_MANAGE_TITLE = "农田管理";
    /** 创建农田 GUI 标题 */ // TODO yml: gui.create-crop.title
    public static final String GUI_CREATE_CROP_TITLE = "创建农田";
    /** 农作物仓库 GUI 标题 */ // TODO yml: gui.warehouse.title
    public static final String GUI_CROP_MENU_TITLE = "农作物仓库";
    /** 仓库 GUI 标题前缀（单页） */ // TODO yml: gui.warehouse.page-title
    public static final String GUI_WAREHOUSE_TITLE = "仓库";
    /** 骨粉储存器 GUI 标题 */ // TODO yml: gui.bonemeal.title
    public static final String GUI_BONEMEAL_TITLE = "骨粉储存器";
    /** 主菜单 GUI 标题 */ // TODO yml: gui.menu.title
    public static final String GUI_MENU_TITLE = "主菜单";

    // ===== 主菜单 GUI（3 行 27 格）=====
    /** 创建农田入口槽位（第2行第2格） */ // TODO yml: gui.menu.create-slot
    public static final int MENU_CREATE_CROP_SLOT = 10;
    /** 农田入口槽位（第2行第4格） */ // TODO yml: gui.menu.farm-slot
    public static final int MENU_FARM_SLOT = 12;
    /** 骨粉储存入口槽位（第2行第6格） */ // TODO yml: gui.menu.bonemeal-slot
    public static final int MENU_BONEMEAL_SLOT = 14;
    /** 农作物仓库入口槽位（第2行第8格） */ // TODO yml: gui.menu.crop-menu-slot
    public static final int MENU_CROP_MENU_SLOT = 16;

    // ===== 材质 =====
    /** 外框黑玻璃 */ // TODO yml: gui.frame-material
    public static final Material FRAME_MATERIAL = Material.BLACK_STAINED_GLASS_PANE;

    // ===== 农田 GUI（分页）=====
    /** 每页农田格数 */ // TODO yml: gui.farm.page-slots
    public static final int FARM_PAGE_SLOTS = 28;
    /** 上一页按钮槽位（第6行第3格，箭） */ // TODO yml: gui.farm.prev-slot
    public static final int FARM_PREV_SLOT = 47;
    /** 第 1 页下一页槽位（第6行第5格，箭） */ // TODO yml: gui.farm.next-slot
    public static final int FARM_NEXT_SLOT_PAGE1 = 49;
    /** 第 2 页起下一页槽位（第6行第7格，箭） */ // TODO yml: gui.farm.next-slot-page2
    public static final int FARM_NEXT_SLOT_PAGE2 = 51;

    /** 骨粉储存器入口槽位（第6行第8格，骨粉图标） */ // TODO yml: gui.farm.bonemeal-slot
    public static final int FARM_BONEMEAL_SLOT = 52;

    /** 农田 GUI 下一页按钮槽位：第 1 页在第 5 格，第 2 页起在第 7 格。 */
    public static int farmNextSlot(int page) {
        return page <= 0 ? FARM_NEXT_SLOT_PAGE1 : FARM_NEXT_SLOT_PAGE2;
    }

    // ===== 农田管理 GUI（3 行 27 格）=====
    /** 补种按钮槽位（第2行第2格，小麦种子） */ // TODO yml: gui.farm-manage.replant-slot
    public static final int FARM_MANAGE_REPLANT_SLOT = 10;
    /** 农田升级按钮槽位（第2行第4格，漏斗） */ // TODO yml: gui.farm-manage.upgrade-slot
    public static final int FARM_MANAGE_UPGRADE_SLOT = 12;
    /** 农田最高等级 */ // TODO yml: farm.upgrade.max-level
    public static final int FARM_MAX_LEVEL = 3;
    /** 升级到 Lv.2 消耗金币 */ // TODO yml: farm.upgrade.cost-2
    public static final int FARM_UPGRADE_COST_2 = 1000;
    /** 升级到 Lv.3 消耗金币 */ // TODO yml: farm.upgrade.cost-3
    public static final int FARM_UPGRADE_COST_3 = 2000;

    // ===== 创建农田 GUI（6 行 54 格）=====
    /** 作物展示起始槽位（第2行第2格） */ // TODO yml: gui.create-crop.start-slot
    public static final int CREATE_CROP_START_SLOT = 10;

    // ===== 农作物仓库 GUI（6 行 54 格，多页）=====
    /** 小麦种子仓库入口槽位（第1页第2行第2格） */ // TODO yml: gui.crop-menu.seed-slot
    public static final int CROP_MENU_SEED_SLOT = 10;
    /** 小麦仓库入口槽位（第1页第2行第3格） */ // TODO yml: gui.crop-menu.wheat-slot
    public static final int CROP_MENU_WHEAT_SLOT = 11;
    /** 下一页槽位（第6行第5格，箭） */ // TODO yml: gui.crop-menu.next-slot
    public static final int CROP_MENU_NEXT_SLOT = 49;
    /** 上一页槽位（第6行第3格，箭） */ // TODO yml: gui.crop-menu.prev-slot
    public static final int CROP_MENU_PREV_SLOT = 47;

    // ===== 仓库 GUI（单页）=====
    /** 每页展示格数 */ // TODO yml: gui.warehouse.page-slots
    public static final int WAREHOUSE_PAGE_SLOTS = 28;
    /** 单格容量 */ // TODO yml: gui.warehouse.stack-size
    public static final int WAREHOUSE_STACK = 64;
    /** 填充按钮槽位（第6行第5格，箱子，固定） */ // TODO yml: gui.warehouse.fill-slot
    public static final int WAREHOUSE_FILL_SLOT = 49;

    // ===== 骨粉储存器 GUI（多页）=====
    /** 每页展示格数 */ // TODO yml: gui.bonemeal.page-slots
    public static final int BONEMEAL_PAGE_SLOTS = 28;
    /** 升级按钮槽位（第1页第6行第5格，箱子） */ // TODO yml: gui.bonemeal.unlock-slot
    public static final int BONEMEAL_UNLOCK_SLOT = 49;
    /** 下一页槽位（第6行第7格，箭） */ // TODO yml: gui.bonemeal.next-slot
    public static final int BONEMEAL_NEXT_SLOT = 51;
    /** 上一页槽位（第6行第3格，箭） */ // TODO yml: gui.bonemeal.prev-slot
    public static final int BONEMEAL_PREV_SLOT = 47;
    /** 解锁下一页基础金币（解锁第 N 页 = base × (N-1)） */ // TODO yml: gui.bonemeal.unlock-base
    public static final int BONEMEAL_UNLOCK_BASE = 1000;
    /** 骨粉加速系数（成熟时长 ×0.8） */ // TODO yml: gui.bonemeal.fast-factor
    public static final double BONEMEAL_FAST_FACTOR = 0.8;

    // ===== 作物参数（wheat 占位值）=====
    /** 生长随机时长下限（小时） */ // TODO yml: crops.wheat.grow-min-hour
    public static final int GROW_MIN_HOUR = 2;
    /** 生长随机时长上限（小时） */ // TODO yml: crops.wheat.grow-max-hour
    public static final int GROW_MAX_HOUR = 4;
    /** 收割产量：小麦（初始 1，预留升级系统） */ // TODO yml: crops.wheat.yield-wheat
    public static final int YIELD_WHEAT = 1;
    /** 收割产量：种子（初始 2，预留升级系统） */ // TODO yml: crops.wheat.yield-seed
    public static final int YIELD_SEED = 2;
    /** 是否消耗种子（创建农田 / 补种 / 自动重播） */ // TODO yml: crops.wheat.consume-seed
    public static final boolean CONSUME_SEED = true;
    /** 创建一块农田消耗的种子数 */ // TODO yml: crops.wheat.create-cost
    public static final int CREATE_COST_SEED = 1;
    /** 补种/重播一格消耗的种子数 */ // TODO yml: crops.wheat.replant-cost
    public static final int REPLANT_COST_SEED = 1;

    // ===== 调度 =====
    /** 定时器间隔（秒） */ // TODO yml: plugin.tick-interval
    public static final int TICK_INTERVAL_SEC = 60;

    // ===== 玩家提示文案 =====
    /** 创建农田成功 */ // TODO yml: msg.crop-created
    public static final String MSG_CROP_CREATED = "§a成功创建 §f小麦农田 §a（第 %page% 页 · 第 %slot% 格）！已种植 %replant% 格。";
    /** 农田全部页已满 */ // TODO yml: msg.farm-full
    public static final String MSG_FARM_FULL = "§c所有农田页已满，无法继续创建农田！";
    /** 翻页前提未满足 */ // TODO yml: msg.page-locked
    public static final String MSG_PAGE_LOCKED = "§c当前页 28 格农田已满后才能进入下一页！";
    /** 种子不足 */ // TODO yml: msg.no-seed
    public static final String MSG_NO_SEED = "§c小麦种子不足（种子仓库 + 背包均不够）！";
    /** 补种成功 */ // TODO yml: msg.replant-done
    public static final String MSG_REPLANT_DONE = "§a已补种 %count% 格，消耗 %seed% 粒小麦种子。";
    /** 无需补种 */ // TODO yml: msg.replant-empty
    public static final String MSG_REPLANT_EMPTY = "§e该农田没有需要补种的格子。";
    /** 取出成功 */ // TODO yml: msg.take-success
    public static final String MSG_TAKE_SUCCESS = "§a已取出 %qty% 个到背包。";
    /** 背包已满 */ // TODO yml: msg.inv-full
    public static final String MSG_INV_FULL = "§c背包已满，无法取出物品！";
    /** 后备库存为空 */ // TODO yml: msg.no-backup
    public static final String MSG_NO_BACKUP = "§c后备库存为空，无需填充。";
    /** 无权限 */ // TODO yml: msg.no-perm
    public static final String MSG_NO_PERM = "§c你没有权限使用该指令。";
    /** 下一页未解锁 */ // TODO yml: msg.bonemeal-next-locked
    public static final String MSG_NEXT_PAGE_LOCKED = "§c下一页尚未解锁，请先在第 1 页点击升级按钮解锁！";
    /** 解锁成功 */ // TODO yml: msg.bonemeal-unlock-success
    public static final String MSG_UNLOCK_SUCCESS = "§a已花费 %cost% 金币解锁下一页！";
    /** 金币不足 */ // TODO yml: msg.bonemeal-unlock-fail
    public static final String MSG_UNLOCK_FAIL_MONEY = "§c金币不足，解锁需要 %cost% 金币！";
    /** 未装经济 */ // TODO yml: msg.bonemeal-no-economy
    public static final String MSG_NO_ECONOMY = "§c未安装 Vault 经济插件，无法使用金币功能。";
    /** 存入骨粉 */ // TODO yml: msg.bonemeal-add
    public static final String MSG_BONEMEAL_ADD = "§a已存入 %qty% 个骨粉。";
    /** 取出骨粉 */ // TODO yml: msg.bonemeal-take
    public static final String MSG_BONEMEAL_TAKE = "§a已取出 %qty% 个骨粉。";
    /** 已是最后一页 */ // TODO yml: msg.bonemeal-max
    public static final String MSG_BONEMEAL_MAX = "§e已解锁到当前最高页。";
    /** 农田升级成功 */ // TODO yml: msg.farm-upgraded
    public static final String MSG_FARM_UPGRADED = "§a农田已升级到 Lv.%level%（消耗 %cost% 金币）！";
    /** 农田已满级 */ // TODO yml: msg.farm-max-level
    public static final String MSG_FARM_MAX_LEVEL = "§c农田已达到最高等级（Lv.3）！";
    /** 升级金币不足 */ // TODO yml: msg.farm-upgrade-no-money
    public static final String MSG_FARM_UPGRADE_NO_MONEY = "§c金币不足，升级需要 %cost% 金币！";
    /** 骨粉页数解锁成功 */ // TODO yml: msg.gufen-update-done
    public static final String MSG_GFUEN_UPDATE_DONE = "§a已为玩家 %player% 增加 %count% 页骨粉解锁（当前共 %total% 页）。";
    /** 玩家不存在 */ // TODO yml: msg.player-not-found
    public static final String MSG_PLAYER_NOT_FOUND = "§c找不到玩家 %player%。";
    /** 数据库操作失败 */ // TODO yml: msg.db-error
    public static final String MSG_DB_ERROR = "§c数据库操作失败，请稍后重试。";
}
