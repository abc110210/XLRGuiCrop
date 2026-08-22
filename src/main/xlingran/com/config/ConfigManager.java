package xlingran.com.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局配置集中管理（默认值 + 从 config.yml / gui.yml 加载）。
 *
 * <p>加载由 {@link ConfigLoader} 调用 {@link #apply} 完成：GUI 标题/槽位/材质取自 gui.yml，
 * 数值与消息取自 config.yml（消息在独立 message 主键下）。未配置项回退此处默认值。
 * 版本：Spigot API 26.2 / Java 25。
 */
public final class ConfigManager {

    private ConfigManager() {}

    // ================= GUI 标题（gui.yml 可覆盖） =================
    /** 主菜单标题 */ public static String GUI_MENU_TITLE = "主菜单";
    /** 创建农田 GUI 标题 */ public static String GUI_CREATE_CROP_TITLE = "创建农田";
    /** 农田 GUI 标题（可含 %page% 占位符） */ public static String GUI_FARM_TITLE = "农田";
    /** 二级生长 GUI 标题（可含 %farmname%，回退值实际用作物名） */ public static String GUI_GROWTH_TITLE = "小麦农田";
    /** 农田管理 GUI 标题 */ public static String GUI_FARM_MANAGE_TITLE = "农田管理";
    /** 骨粉储存器 GUI 标题（可含 %page%） */ public static String GUI_BONEMEAL_TITLE = "骨粉储存器";
    /** 农作物仓库 GUI 标题（可含 %page%） */ public static String GUI_CROP_MENU_TITLE = "农作物仓库";
    /** 作物仓库 GUI 标题（可含 %Farmitem%） */ public static String GUI_WAREHOUSE_TITLE = "仓库";

    // ================= 材质（gui.yml 可覆盖） =================
    /** 外框黑玻璃 */ public static Material FRAME_MATERIAL = Material.BLACK_STAINED_GLASS_PANE;

    /** gui.yml 各 GUI 条目配置（材质/槽位/名称/Lore），渲染时读取。 */
    public static final Map<String, GuiItemConfig> GUI_ITEMS = new LinkedHashMap<>();

    // ================= 主菜单 GUI（gui.yml menu.*.slot 可覆盖） =================
    /** 创建农田入口槽位（第2行第2格） */ public static int MENU_CREATE_CROP_SLOT = 10;
    /** 农田入口槽位（第2行第4格） */ public static int MENU_FARM_SLOT = 12;
    /** 骨粉储存入口槽位（第2行第6格） */ public static int MENU_BONEMEAL_SLOT = 14;
    /** 农作物仓库入口槽位（第2行第8格） */ public static int MENU_CROP_MENU_SLOT = 16;

    // ================= 农田 GUI（gui.yml Farm.*.slot 可覆盖） =================
    /** 每页农田格数（布局固定 28，与内部 28 格一致，勿改） */ public static final int FARM_PAGE_SLOTS = 28;
    /** 上一页按钮槽位（第6行第3格） */ public static int FARM_PREV_SLOT = 47;
    /** 第 1 页下一页槽位（第6行第5格） */ public static int FARM_NEXT_SLOT_PAGE1 = 49;
    /** 第 2 页起下一页槽位（第6行第7格） */ public static int FARM_NEXT_SLOT_PAGE2 = 51;
    /** 骨粉储存器入口槽位（第6行第9格） */ public static int FARM_BONEMEAL_SLOT = 53;

    /** 农田 GUI 下一页按钮槽位：第 1 页在第 5 格，第 2 页起在第 7 格。 */
    public static int farmNextSlot(int page) {
        return page <= 0 ? FARM_NEXT_SLOT_PAGE1 : FARM_NEXT_SLOT_PAGE2;
    }

    // ================= 农田管理 GUI（gui.yml Farmmanage.*.slot 可覆盖） =================
    /** 补种按钮槽位（第2行第2格） */ public static int FARM_MANAGE_REPLANT_SLOT = 10;
    /** 农田升级按钮槽位（第2行第4格） */ public static int FARM_MANAGE_UPGRADE_SLOT = 12;
    /** 骨粉加速开关槽位（第2行第8格） */ public static int FARM_MANAGE_FAST_SLOT = 16;
    /** 删除农田槽位（第3行第5格） */ public static int FARM_MANAGE_DELETE_SLOT = 22;
    /** 返回农田槽位（第3行第1格） */ public static int FARM_MANAGE_BACK_SLOT = 18;

    /** 农田最高等级（由 gui.yml FarmUpdate 的 LVn 段数决定） */ public static int FARM_MAX_LEVEL = 3;
    /** 升级到 Lv.2 消耗金币 */ public static int FARM_UPGRADE_COST_2 = 1000;
    /** 升级到 Lv.3 消耗金币 */ public static int FARM_UPGRADE_COST_3 = 2000;
    /** 每级产量缓存：key="作物id:等级"，value=[产物, 种子]（gui.yml FarmUpdate） */
    private static final Map<String, int[]> FARM_DROPS = new HashMap<>();
    /** 升级价格缓存：key="作物id:目标等级"，value=金币（gui.yml FarmUpdate LVn.Money） */
    private static final Map<String, Integer> FARM_UPGRADE_COSTS = new HashMap<>();
    /** 等级成长时间缩短缓存：key="作物id:等级"，value=缩短百分比（gui.yml FarmUpdate LVn.TimeReduction，0=不缩短） */
    private static final Map<String, Integer> FARM_TIME_REDUCTIONS = new HashMap<>();
    /** 每种作物最高等级缓存（gui.yml FarmUpdate LVn 段数）；缺省回退全局 FARM_MAX_LEVEL */
    private static final Map<String, Integer> FARM_MAX_LEVELS = new HashMap<>();
    /** 玩家默认可拥有农田数（动态权限 xlr.crop.create.farm.<N> 可覆盖） */ public static int FARM_MAX_FARMS = 1;
    /** 查询某作物某等级产量 [产物, 种子]；未在 FarmUpdate 配置返回 null。 */
    public static int[] getFarmDrop(String cropId, int level) {
        return FARM_DROPS.get(cropId + ":" + level);
    }

    /** 查询升级到某等级的金币价格；未在 FarmUpdate 配置回退默认（Lv2 用 COST_2，其余用 COST_3）。 */
    public static int getFarmUpgradeCost(String cropId, int toLevel) {
        Integer v = FARM_UPGRADE_COSTS.get(cropId + ":" + toLevel);
        if (v != null) {
            return v;
        }
        return toLevel <= 2 ? FARM_UPGRADE_COST_2 : FARM_UPGRADE_COST_3;
    }

    /** 查询某作物某等级的成长时间缩短百分比（0=不缩短，100=直接成熟）。 */
    public static int getFarmTimeReduction(String cropId, int level) {
        Integer v = FARM_TIME_REDUCTIONS.get(cropId + ":" + level);
        return v == null ? 0 : Math.max(0, Math.min(100, v));
    }

    /** 查询某作物的最高等级（按其 FarmUpdate LVn 段数）；未配置回退全局 FARM_MAX_LEVEL。 */
    public static int getFarmMaxLevel(String cropId) {
        return FARM_MAX_LEVELS.getOrDefault(cropId, FARM_MAX_LEVEL);
    }

    /**
     * 玩家可拥有农田上限：动态权限 xlr.crop.create.farm.&lt;N&gt;（取拥有的最大 N），
     * 未拥有任何动态权限时用默认 {@link #FARM_MAX_FARMS}。
     */
    public static int allowedFarms(org.bukkit.entity.Player player) {
        for (int n = 100; n >= 1; n--) {
            if (player.hasPermission("xlr.crop.create.farm." + n)) {
                return n;
            }
        }
        return FARM_MAX_FARMS;
    }

    // ================= 创建农田 GUI（gui.yml Farmcreate.Wheat.slot 可覆盖） =================
    /** 作物展示起始槽位（第2行第2格） */ public static int CREATE_CROP_START_SLOT = 10;

    // ================= 农作物仓库 GUI（gui.yml Crop.*.slot 可覆盖） =================
    /** 小麦种子仓库入口槽位 */ public static int CROP_MENU_SEED_SLOT = 10;
    /** 小麦仓库入口槽位 */ public static int CROP_MENU_WHEAT_SLOT = 11;
    /** 导航槽位（第6行第5格，同格分页） */ public static int CROP_MENU_NEXT_SLOT = 49;
    /** 导航槽位（与 NEXT 同格，语义上的上一页） */ public static int CROP_MENU_PREV_SLOT = 49;

    // ================= 作物仓库 GUI（gui.yml CropStorage.*.slot 可覆盖） =================
    /** 填充按钮槽位（第6行第5格） */ public static int WAREHOUSE_FILL_SLOT = 49;
    /** 返回农作物仓库槽位（第6行第3格） */ public static int WAREHOUSE_BACK_SLOT = 47;
    /** 仓库每页展示格数（布局固定 28，勿改） */ public static final int WAREHOUSE_PAGE_SLOTS = 28;
    /** 单格容量 */ public static final int WAREHOUSE_STACK = 64;
    /** 每作物单类库存（种子/产物分别计算）上限，超出部分不再入账 */ public static long WAREHOUSE_MAX_STOCK = 100000;

    // ================= 骨粉储存器 GUI（gui.yml Bone.*.slot 可覆盖） =================
    /** 升级按钮槽位（第1页第6行第5格） */ public static int BONEMEAL_UNLOCK_SLOT = 49;
    /** 下一页槽位（第6行第7格） */ public static int BONEMEAL_NEXT_SLOT = 51;
    /** 上一页槽位（第6行第3格） */ public static int BONEMEAL_PREV_SLOT = 47;
    /** 返回槽位（第1页与上一页同格） */ public static int BONEMEAL_BACK_SLOT = 47;
    /** 每页展示格数（布局固定 28，勿改） */ public static final int BONEMEAL_PAGE_SLOTS = 28;
    /** 解锁下一页基础金币（第 N 页 = base × (N-1)） */ public static int BONEMEAL_UNLOCK_BASE = 1000;
    /** 骨粉加速系数（成熟时长 ×0.8） */ public static double BONEMEAL_FAST_FACTOR = 0.8;
    /** 骨粉加速每次消耗骨粉数 */ public static int BONEMEAL_FAST_COST = 1;

    // ================= 作物默认参数（config.yml crops 段驱动，此处为兜底） =================
    /** 生长随机时长下限（小时） */ public static final int GROW_MIN_HOUR = 2;
    /** 生长随机时长上限（小时） */ public static final int GROW_MAX_HOUR = 4;
    /** 每周期产物产量兜底 */ public static final int YIELD_PRODUCT = 1;
    /** 每周期种子产量兜底 */ public static final int YIELD_SEED = 2;
    /** 是否消耗种子兜底 */ public static final boolean CONSUME_SEED = true;

    // ================= 调度 =================
    /** 定时器间隔（秒） */ public static int TICK_INTERVAL_SEC = 60;
    /** 删除农田聊天确认超时（秒） */ public static int DELETE_CONFIRM_TIMEOUT_SEC = 60;
    /** 补种/重播一格消耗的种子数 */ public static int REPLANT_COST_SEED = 1;

    // ================= 文案变量（gui.yml Hours/Minutes/Seconds、BoneVariable） =================
    /** 时间单位 */ public static String TIME_HOURS = "小时";
    /** 时间单位 */ public static String TIME_MINUTES = "分";
    /** 时间单位 */ public static String TIME_SECONDS = "秒";
    /** 骨粉加速开关开 */ public static String BONE_ON = "开";
    /** 骨粉加速开关关 */ public static String BONE_OFF = "关";

    // ================= 玩家提示文案（config.yml message 段覆盖） =================
    public static String MSG_CROP_CREATED = "§a成功创建 §f%farmname% §a（第 %page% 页 · 第 %slot% 格）！已种植 %replant% 格。";
    public static String MSG_FARM_FULL = "§c所有农田页已满，无法继续创建农田！";
    public static String MSG_FARM_LIMIT = "§c你最多只能拥有 %max% 块农田！";
    public static String MSG_PAGE_LOCKED = "§c当前页 28 格农田已满后才能进入下一页！";
    public static String MSG_NO_SEED = "§c%seedname%不足（种子仓库 + 背包均不够）！";
    public static String MSG_REPLANT_DONE = "§a已补种 %count% 格，消耗 %seed% 粒%seedname%。";
    public static String MSG_REPLANT_EMPTY = "§e该农田没有需要补种的格子。";
    public static String MSG_TAKE_SUCCESS = "§a已取出 %qty% 个到背包。";
    public static String MSG_INV_FULL = "§c背包已满，无法取出物品！";
    public static String MSG_NO_BACKUP = "§c后备库存为空，无需填充。";
    public static String MSG_NO_PERM = "§c你没有权限使用该指令。";
    public static String MSG_NEXT_PAGE_LOCKED = "§c下一页尚未解锁，请先在第 1 页点击升级按钮解锁！";
    public static String MSG_UNLOCK_SUCCESS = "§a已花费 %cost% 金币解锁下一页！";
    public static String MSG_UNLOCK_FAIL_MONEY = "§c金币不足，解锁需要 %cost% 金币！";
    public static String MSG_NO_ECONOMY = "§c未安装 Vault 经济插件，无法使用金币功能。";
    public static String MSG_BONEMEAL_ADD = "§a已存入 %qty% 个骨粉。";
    public static String MSG_BONEMEAL_TAKE = "§a已取出 %qty% 个骨粉。";
    public static String MSG_BONEMEAL_MAX = "§e已解锁到当前最高页。";
    public static String MSG_FARM_UPGRADED = "§a农田已升级到 Lv.%level%（消耗 %cost% 金币）！";
    public static String MSG_FARM_MAX_LEVEL = "§c农田已达到最高等级（Lv.3）！";
    public static String MSG_FARM_UPGRADE_NO_MONEY = "§c金币不足，升级需要 %cost% 金币！";
    public static String MSG_GFUEN_UPDATE_DONE = "§a已为玩家 %player% 增加 %count% 页骨粉解锁（当前共 %total% 页）。";
    public static String MSG_PLAYER_NOT_FOUND = "§c找不到玩家 %player%。";
    public static String MSG_DB_ERROR = "§c数据库操作失败，请稍后重试。";
    public static String MSG_BONEMEAL_FAST_TOGGLED = "§a骨粉加速已%state%（仅自动重播生效）。";
    public static String MSG_DELETE_CONFIRM = "§e你确定要删除这个农田吗？如果确定，请输入 §a删除 §e；否则请输入 §c取消 §e。";
    public static String MSG_DELETE_DONE = "§a该农田已删除。";
    public static String MSG_DELETE_CANCELLED = "§e已取消删除该农田。";
    public static String MSG_DELETE_HINT = "§e请输入 §a删除 §e确认，或 §c取消 §e放弃。";
    /** 帮助（config.yml message.help 多行覆盖）；/xlr help 显示。 */
    public static List<String> MSG_HELP = List.of(
            "§6========== XLRGuiCrop 帮助 ==========",
            "§e/xlr crop menu §7- 打开主菜单",
            "§e/xlr crop farm §7- 打开农田（查看 / 生长 / 管理）",
            "§e/xlr crop create [作物id] §7- 打开创建界面或直接创建（消耗种子，几颗种几格）",
            "§e/xlr crop gui §7- 打开农作物仓库（查看 / 取出产物与种子）",
            "§e/xlr crop bone §7- 打开骨粉储存器（存入 / 取出 / 金币解锁页数）",
            "§e/xlr crop update bone <玩家ID> <页数> §7- 管理员叠加解锁骨粉页",
            "§e/xlr crop comp [list|replay <id>|done <id>] §7- 管理员处理补偿台账",
            "§e/xlr crop reload §7- 重载配置文件（管理员）",
            "§e/xlr crop help §7- 显示本帮助",
            "§7玩法：创建农田后作物自动生长，成熟自动收割入仓库；",
            "§7农田可金币升级提高产量，可开启骨粉加速缩短生长时长；",
            "§7创建农田消耗对应作物种子，补种与自动重播也从种子仓库扣除。");

    // ================= 加载 =================

    /**
     * 从 config.yml + gui.yml 覆盖默认值。
     *
     * @param cfg config.yml（数值 / 作物 / message 段）
     * @param gui gui.yml（GUI 标题 / 槽位 / 材质 / 升级价格 / 解锁价格）
     */
    public static void apply(YamlConfiguration cfg, YamlConfiguration gui) {
        // ---- 数值（config.yml） ----
        TICK_INTERVAL_SEC = Math.max(1, cfg.getInt("tick.interval-sec", TICK_INTERVAL_SEC));
        DELETE_CONFIRM_TIMEOUT_SEC = Math.max(1, cfg.getInt("farm.delete-confirm-timeout-sec", DELETE_CONFIRM_TIMEOUT_SEC));
        FARM_MAX_FARMS = Math.max(1, cfg.getInt("farm.default-max-farms", FARM_MAX_FARMS));
        WAREHOUSE_MAX_STOCK = Math.max(0, cfg.getLong("warehouse.max-stock", WAREHOUSE_MAX_STOCK));
        REPLANT_COST_SEED = Math.max(1, cfg.getInt("farm.replant-cost-seed", REPLANT_COST_SEED));
        BONEMEAL_FAST_FACTOR = Math.max(0.1, cfg.getDouble("bonemeal.fast-factor", BONEMEAL_FAST_FACTOR));
        BONEMEAL_FAST_COST = Math.max(1, cfg.getInt("bonemeal.fast-cost", BONEMEAL_FAST_COST));

        // ---- GUI 标题（gui.yml） ----
        GUI_MENU_TITLE = color(gui, "menu.name", GUI_MENU_TITLE);
        GUI_CREATE_CROP_TITLE = color(gui, "Farmcreate.name", GUI_CREATE_CROP_TITLE);
        GUI_FARM_TITLE = color(gui, "Farm.name", GUI_FARM_TITLE);
        GUI_GROWTH_TITLE = color(gui, "Farmplot.name", GUI_GROWTH_TITLE);
        GUI_FARM_MANAGE_TITLE = color(gui, "Farmmanage.name", GUI_FARM_MANAGE_TITLE);
        GUI_BONEMEAL_TITLE = color(gui, "Bone.name", GUI_BONEMEAL_TITLE);
        GUI_CROP_MENU_TITLE = color(gui, "Crop.name", GUI_CROP_MENU_TITLE);
        GUI_WAREHOUSE_TITLE = color(gui, "CropStorage.name", GUI_WAREHOUSE_TITLE);

        // ---- 文案变量（gui.yml） ----
        TIME_HOURS = color(gui, "Hours", TIME_HOURS);
        TIME_MINUTES = color(gui, "Minutes", TIME_MINUTES);
        TIME_SECONDS = color(gui, "Seconds", TIME_SECONDS);
        BONE_ON = color(gui, "BoneVariable.BoneOn", BONE_ON);
        BONE_OFF = color(gui, "BoneVariable.BoneOFF", BONE_OFF);

        // ---- GUI 槽位（gui.yml，空则保留默认；范围校验：3 行 GUI 限 0-26，其余 0-53） ----
        MENU_CREATE_CROP_SLOT = slot(gui, "menu.Farmcreate.slot", MENU_CREATE_CROP_SLOT, 27);
        MENU_FARM_SLOT = slot(gui, "menu.Farm.slot", MENU_FARM_SLOT, 27);
        MENU_BONEMEAL_SLOT = slot(gui, "menu.Bone.slot", MENU_BONEMEAL_SLOT, 27);
        MENU_CROP_MENU_SLOT = slot(gui, "menu.Crop.slot", MENU_CROP_MENU_SLOT, 27);
        FARM_PREV_SLOT = slot(gui, "Farm.Prvepage.slot", FARM_PREV_SLOT, 54);
        FARM_BONEMEAL_SLOT = slot(gui, "Farm.Bone.slot", FARM_BONEMEAL_SLOT, 54);
        FARM_MANAGE_REPLANT_SLOT = slot(gui, "Farmmanage.Seed.slot", FARM_MANAGE_REPLANT_SLOT, 27);
        FARM_MANAGE_UPGRADE_SLOT = slot(gui, "Farmmanage.Update.slot", FARM_MANAGE_UPGRADE_SLOT, 27);
        FARM_MANAGE_FAST_SLOT = slot(gui, "Farmmanage.Bone.slot", FARM_MANAGE_FAST_SLOT, 27);
        FARM_MANAGE_DELETE_SLOT = slot(gui, "Farmmanage.DeleFram.slot", FARM_MANAGE_DELETE_SLOT, 27);
        FARM_MANAGE_BACK_SLOT = slot(gui, "Farmmanage.Prvepage.slot", FARM_MANAGE_BACK_SLOT, 27);
        CREATE_CROP_START_SLOT = slot(gui, "Farmcreate.Wheat.slot", CREATE_CROP_START_SLOT, 54);
        CROP_MENU_SEED_SLOT = slot(gui, "Crop.WheatSeed.slot", CROP_MENU_SEED_SLOT, 54);
        CROP_MENU_WHEAT_SLOT = slot(gui, "Crop.Wheat.slot", CROP_MENU_WHEAT_SLOT, 54);
        CROP_MENU_NEXT_SLOT = slot(gui, "Crop.Nextpage.slot", CROP_MENU_NEXT_SLOT, 54);
        CROP_MENU_PREV_SLOT = CROP_MENU_NEXT_SLOT;
        WAREHOUSE_FILL_SLOT = slot(gui, "CropStorage.Restock.slot", WAREHOUSE_FILL_SLOT, 54);
        WAREHOUSE_BACK_SLOT = slot(gui, "CropStorage.Back.slot", WAREHOUSE_BACK_SLOT, 54);
        BONEMEAL_UNLOCK_SLOT = slot(gui, "Bone.Update.slot", BONEMEAL_UNLOCK_SLOT, 54);
        BONEMEAL_NEXT_SLOT = slot(gui, "Bone.Nextpage.slot", BONEMEAL_NEXT_SLOT, 54);
        BONEMEAL_PREV_SLOT = slot(gui, "Bone.Prvepage.slot", BONEMEAL_PREV_SLOT, 54);
        BONEMEAL_BACK_SLOT = BONEMEAL_PREV_SLOT;
        // 创建页点击依赖内部 28 格映射，起始槽必须是内部格，否则点击错位
        if (!isInnerSlot(CREATE_CROP_START_SLOT)) {
            CREATE_CROP_START_SLOT = 10;
            System.err.println("[XLRGuiCrop] Farmcreate.Wheat.slot 不是内部格，已回退默认 10");
        }
        // 按钮槽位冲突检测（同 GUI 内两按钮不得占同一格）
        checkSlotClash("主菜单", MENU_CREATE_CROP_SLOT, MENU_FARM_SLOT, MENU_BONEMEAL_SLOT, MENU_CROP_MENU_SLOT);
        checkSlotClash("农田管理", FARM_MANAGE_REPLANT_SLOT, FARM_MANAGE_UPGRADE_SLOT, FARM_MANAGE_FAST_SLOT,
                FARM_MANAGE_DELETE_SLOT, FARM_MANAGE_BACK_SLOT);
        checkSlotClash("骨粉储存器", BONEMEAL_UNLOCK_SLOT, BONEMEAL_NEXT_SLOT, BONEMEAL_PREV_SLOT);
        checkSlotClash("农作物仓库", CROP_MENU_SEED_SLOT, CROP_MENU_WHEAT_SLOT, CROP_MENU_NEXT_SLOT);
        checkSlotClash("作物仓库", WAREHOUSE_FILL_SLOT, WAREHOUSE_BACK_SLOT);

        // ---- 材质（gui.yml，空则保留默认） ----
        FRAME_MATERIAL = material(gui, "menu.Fill.material", FRAME_MATERIAL);

        // ---- 升级价格与等级上限（gui.yml FarmUpdate：LVn 段数决定最高等级，Money 为升级价） ----
        FARM_UPGRADE_COST_2 = Math.max(0, gui.getInt("FarmUpdate.wheat.LV2.Money", FARM_UPGRADE_COST_2));
        FARM_UPGRADE_COST_3 = Math.max(0, gui.getInt("FarmUpdate.wheat.LV3.Money", FARM_UPGRADE_COST_3));
        loadFarmUpdate(gui);

        // ---- 骨粉解锁价格（gui.yml UpdateBone.Page2.Money 作为递增基准） ----
        BONEMEAL_UNLOCK_BASE = Math.max(0, gui.getInt("UpdateBone.Page2.Money", BONEMEAL_UNLOCK_BASE));

        // ---- GUI 条目（gui.yml 各段：材质/槽位/名称/Lore，渲染时读取） ----
        GUI_ITEMS.clear();
        loadGuiItem(gui, "menu.Farmcreate");
        loadGuiItem(gui, "menu.Farm");
        loadGuiItem(gui, "menu.Bone");
        loadGuiItem(gui, "menu.Crop");
        loadGuiItem(gui, "Farmcreate.Wheat");
        loadGuiItem(gui, "Farm.Farmplot");
        loadGuiItem(gui, "Farm.Nextpage");
        loadGuiItem(gui, "Farm.Prvepage");
        loadGuiItem(gui, "Farm.Bone");
        loadGuiItem(gui, "Farmplot.CropGrowing");
        loadGuiItem(gui, "Farmplot.CropMature");
        loadGuiItem(gui, "Farmmanage.Seed");
        loadGuiItem(gui, "Farmmanage.Update");
        loadGuiItem(gui, "Farmmanage.Bone");
        loadGuiItem(gui, "Farmmanage.DeleFram");
        loadGuiItem(gui, "Farmmanage.Prvepage");
        loadGuiItem(gui, "Bone.Update");
        loadGuiItem(gui, "Bone.Nextpage");
        loadGuiItem(gui, "Bone.Prvepage");
        loadGuiItem(gui, "Crop.WheatSeed");
        loadGuiItem(gui, "Crop.Wheat");
        loadGuiItem(gui, "Crop.CropStorage");
        loadGuiItem(gui, "Crop.Nextpage");
        loadGuiItem(gui, "Crop.Prvepage");
        loadGuiItem(gui, "CropStorage");
        loadGuiItem(gui, "CropStorage.Restock");
        loadGuiItem(gui, "CropStorage.Back");

        // ---- 消息（config.yml message 段） ----
        ConfigurationSection m = cfg.getConfigurationSection("message");
        if (m != null) {
            MSG_CROP_CREATED = color(m, "crop-created", MSG_CROP_CREATED);
            MSG_FARM_FULL = color(m, "farm-full", MSG_FARM_FULL);
            MSG_FARM_LIMIT = color(m, "farm-limit", MSG_FARM_LIMIT);
            MSG_PAGE_LOCKED = color(m, "page-locked", MSG_PAGE_LOCKED);
            MSG_NO_SEED = color(m, "no-seed", MSG_NO_SEED);
            MSG_REPLANT_DONE = color(m, "replant-done", MSG_REPLANT_DONE);
            MSG_REPLANT_EMPTY = color(m, "replant-empty", MSG_REPLANT_EMPTY);
            MSG_TAKE_SUCCESS = color(m, "take-success", MSG_TAKE_SUCCESS);
            MSG_INV_FULL = color(m, "inv-full", MSG_INV_FULL);
            MSG_NO_BACKUP = color(m, "no-backup", MSG_NO_BACKUP);
            MSG_NO_PERM = color(m, "no-perm", MSG_NO_PERM);
            MSG_NEXT_PAGE_LOCKED = color(m, "bonemeal-next-locked", MSG_NEXT_PAGE_LOCKED);
            MSG_UNLOCK_SUCCESS = color(m, "bonemeal-unlock-success", MSG_UNLOCK_SUCCESS);
            MSG_UNLOCK_FAIL_MONEY = color(m, "bonemeal-unlock-fail-money", MSG_UNLOCK_FAIL_MONEY);
            MSG_NO_ECONOMY = color(m, "no-economy", MSG_NO_ECONOMY);
            MSG_BONEMEAL_ADD = color(m, "bonemeal-add", MSG_BONEMEAL_ADD);
            MSG_BONEMEAL_TAKE = color(m, "bonemeal-take", MSG_BONEMEAL_TAKE);
            MSG_BONEMEAL_MAX = color(m, "bonemeal-max", MSG_BONEMEAL_MAX);
            MSG_FARM_UPGRADED = color(m, "farm-upgraded", MSG_FARM_UPGRADED);
            MSG_FARM_MAX_LEVEL = color(m, "farm-max-level", MSG_FARM_MAX_LEVEL);
            MSG_FARM_UPGRADE_NO_MONEY = color(m, "farm-upgrade-no-money", MSG_FARM_UPGRADE_NO_MONEY);
            MSG_GFUEN_UPDATE_DONE = color(m, "gufen-update-done", MSG_GFUEN_UPDATE_DONE);
            MSG_PLAYER_NOT_FOUND = color(m, "player-not-found", MSG_PLAYER_NOT_FOUND);
            MSG_DB_ERROR = color(m, "db-error", MSG_DB_ERROR);
            MSG_BONEMEAL_FAST_TOGGLED = color(m, "bonemeal-fast-toggled", MSG_BONEMEAL_FAST_TOGGLED);
            MSG_DELETE_CONFIRM = color(m, "farm-delete-confirm", MSG_DELETE_CONFIRM);
            MSG_DELETE_DONE = color(m, "farm-delete-done", MSG_DELETE_DONE);
            MSG_DELETE_CANCELLED = color(m, "farm-delete-cancelled", MSG_DELETE_CANCELLED);
            MSG_DELETE_HINT = color(m, "farm-delete-hint", MSG_DELETE_HINT);
            MSG_HELP = colorList(m, "help", MSG_HELP);
        }
    }

    /** 读取多行消息（message.help 等 List<String> 键），未配置/为空时回退默认。 */
    private static List<String> colorList(ConfigurationSection c, String path, List<String> def) {
        List<String> raw = c.getStringList(path);
        if (raw.isEmpty()) {
            return def;
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            out.add(s.replace('&', '§'));
        }
        return out;
    }

    /** 解析 gui.yml FarmUpdate：等级上限取最大 LVn，每级产量/升级价/时间缩短缓存。 */
    private static void loadFarmUpdate(YamlConfiguration gui) {
        FARM_DROPS.clear();
        FARM_UPGRADE_COSTS.clear();
        FARM_TIME_REDUCTIONS.clear();
        FARM_MAX_LEVELS.clear();
        ConfigurationSection fu = gui.getConfigurationSection("FarmUpdate");
        if (fu == null) {
            return;
        }
        int maxLevel = 1;
        for (String key : fu.getKeys(false)) {
            // 统一小写：作物 key 与 config.yml crops id / DB 存储保持一致
            String cropId = key.toLowerCase();
            ConfigurationSection c = fu.getConfigurationSection(key);
            if (c == null) {
                continue;
            }
            int cropMax = 1;
            for (String k : c.getKeys(false)) {
                int lv = parseLv(k);
                if (lv <= 0) {
                    continue;
                }
                maxLevel = Math.max(maxLevel, lv);
                cropMax = Math.max(cropMax, lv);
                FARM_DROPS.put(cropId + ":" + lv, new int[]{
                        Math.max(0, c.getInt(k + ".Drop", 1)),
                        Math.max(0, c.getInt(k + ".SeedDrop", 1))});
                // LV1 为初始产量，不写 Money（升级价只作用于 LV2+）
                if (lv > 1 && c.contains(k + ".Money")) {
                    FARM_UPGRADE_COSTS.put(cropId + ":" + lv, Math.max(0, c.getInt(k + ".Money", 0)));
                }
                // 成长时间缩短百分比（可选，0 或缺省 = 不缩短）
                if (c.contains(k + ".TimeReduction")) {
                    FARM_TIME_REDUCTIONS.put(cropId + ":" + lv,
                            Math.max(0, Math.min(100, c.getInt(k + ".TimeReduction", 0))));
                }
            }
            FARM_MAX_LEVELS.put(cropId, cropMax);
        }
        FARM_MAX_LEVEL = Math.max(1, maxLevel);
    }

    private static int parseLv(String key) {
        String s = key.toUpperCase();
        if (s.startsWith("LV")) {
            try {
                return Integer.parseInt(s.substring(2));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static void loadGuiItem(YamlConfiguration gui, String path) {
        ConfigurationSection c = gui.getConfigurationSection(path);
        if (c == null) {
            return;
        }
        // 过滤空行 Lore（如 gui.yml 占位的 `- ""`），否则会覆盖代码默认文案；& → § 颜色转换
        List<String> lore = new ArrayList<>();
        for (String s : c.getStringList("Lore")) {
            if (s != null && !s.isBlank()) {
                lore.add(s.replace('&', '§'));
            }
        }
        GUI_ITEMS.put(path, new GuiItemConfig(
                c.getString("material"),   // 原始材质字符串，可为空/固定材质名/%icon% 等占位符（渲染时解析）
                c.getInt("slot", -1),
                color(c, "name", ""),
                lore));
    }

    private static String color(ConfigurationSection c, String path, String def) {
        String s = c.getString(path);
        return (s == null || s.isBlank()) ? def : s.replace('&', '§');
    }

    private static int slot(ConfigurationSection c, String path, int def, int size) {
        int v = c.getInt(path, def);
        return (v >= 0 && v < size) ? v : def;
    }

    /** 创建页点击依赖内部 28 格（第2~5行第2~8列），起始槽必须是其中之一。 */
    private static boolean isInnerSlot(int slot) {
        int[] inner = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };
        for (int i : inner) {
            if (i == slot) {
                return true;
            }
        }
        return false;
    }

    /** 同 GUI 内按钮槽位冲突检测：冲突直接抛异常禁用插件（仅警告会让后渲染按钮覆盖前一个，功能不可点击）。 */
    private static void checkSlotClash(String label, int... slots) {
        Set<Integer> seen = new HashSet<>();
        for (int s : slots) {
            if (!seen.add(s)) {
                throw new IllegalStateException("[XLRGuiCrop] " + label + " 存在按钮槽位冲突: slot=" + s
                        + "（两个按钮共用一格，后渲染的会覆盖前者导致功能不可点击）。请修正 gui.yml 后重启插件。");
            }
        }
    }

    private static Material material(ConfigurationSection c, String path, Material def) {
        String s = c.getString(path);
        if (s == null || s.isBlank()) {
            return def;
        }
        Material m = Material.matchMaterial(s.trim());
        return m != null ? m : def;
    }
}
