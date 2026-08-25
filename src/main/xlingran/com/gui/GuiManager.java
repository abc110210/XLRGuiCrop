package xlingran.com.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xlingran.com.Shan;
import xlingran.com.config.ConfigManager;
import xlingran.com.config.GuiItemConfig;
import xlingran.com.crop.CropManager;
import xlingran.com.crop.CropRegistry;
import xlingran.com.crop.CropType;
import xlingran.com.crop.PlotState;
import xlingran.com.db.DatabaseManager;
import xlingran.com.economy.EconomyManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多类 GUI 的构建与点击分发：
 * <ol>
 *   <li>农田 GUI（分页）：54 格，内部 28 农田位（空位留空）；第6行第3格「上一页」箭（非首页）、
 *       「下一页」箭（第 1 页在第 5 格、第 2 页起在第 7 格，当前页 28 格占满才可翻页）；
 *       农田格左键进生长、右键进管理；第6行第9格「骨粉储存器」入口</li>
 *   <li>二级生长 GUI（54 格）：展示作物生长状态，空槽留空，按作物配置决定是否分阶段显示</li>
 *   <li>农田管理 GUI（3 行）：第2行第2格小麦种子「点击补种」、第2行第4格「农田升级」、
 *       第2行第8格「骨粉加速」拉杆开关、第3行第5格「删除农田」（聊天二次确认）、第3行第1格「返回农田」</li>
 *   <li>创建农田 GUI（6 行）：从第2行第2格起展示作物，点击创建</li>
 *   <li>农作物仓库 GUI（6 行，共 2 页）：第 1 页小麦/种子仓库入口，第6行第5格导航（第 1 页下一页 / 第 2 页上一页）</li>
 *   <li>骨粉储存器 GUI（多页）：默认解锁第 1 页；第 1 页第6行第5格「升级解锁」、第6行第7格「下一页」；
 *       第 2 页起第6行第3格「上一页」，第 1 页同格「返回农田」；可放入/取出骨粉</li>
 *   <li>仓库 GUI（单页）：28 展示格 + 第6行第5格箱子「点击填充」+ 第6行第3格「返回农作物仓库」，只能取出不能放入</li>
 * </ol>
 *
 * <p>防复制：虚拟展示层——取走即扣总数并清格；未取走的物品随 GUI 关闭销毁、总数不变。
 */
public final class GuiManager implements Listener {

    /** GUI 类型。 */
    public enum GuiType { FARM, GROWTH, FARM_MANAGE, CREATE_CROP, CROP_MENU, BONEMEAL, WAREHOUSE, MENU }

    /** 作物仓库条目：某作物的 具体素材种子(材质名) 或 产物(PRODUCT) 库存。 */
    public static final class WarehouseResource {
        private final String cropId;
        private final String itemType;   // "PRODUCT" 或具体种子素材材质名（如 "MELON_SEEDS"），对应 crop_stock.item_type
        private final Material material; // 展示/存入/取出材质

        private WarehouseResource(String cropId, String itemType, Material material) {
            this.cropId = cropId;
            this.itemType = itemType;
            this.material = material;
        }

        static WarehouseResource seedOf(String cropId, Material material) {
            return new WarehouseResource(cropId, material.name(), material);
        }

        static WarehouseResource productOf(String cropId) {
            CropType ct = CropRegistry.get(cropId);
            Material m = ct == null ? Material.WHEAT : ct.getHarvestMaterial();
            return new WarehouseResource(cropId, "PRODUCT", m);
        }

        public String getCropId() { return cropId; }

        /** 仓库存储类型：产物为 "PRODUCT"，种子素材为该素材材质名。 */
        public String getItemType() { return itemType; }

        public boolean isProduct() { return "PRODUCT".equals(itemType); }

        /** 展示 / 取出 / 填充材质。 */
        public Material getMaterial() { return material; }

        /** 仓库标题（如「苹果种子仓库」/「苹果仓库」）。 */
        public String getTitle() {
            CropType ct = CropRegistry.get(cropId);
            String name = ct == null ? cropId : ct.getName();
            return name + (isProduct() ? "仓库" : "种子仓库");
        }
    }

    /** 自定义 GUI 持有者，用于识别界面类型与携带上下文。 */
    public static final class GuiHolder implements InventoryHolder {
        private final GuiType type;
        private final UUID uuid;
        private final int page;
        private final int farmSlot;
        private final WarehouseResource resource;
        /** 创建农田视图：选中介质后要落到的目标农田格（全局槽位）；-1 表示自动取首个空闲已解锁格。 */
        private final int createSlot;
        /** 骨粉 GUI 是否从农田进入（翻页/解锁后重开页面时保留返回目标）。 */
        private final boolean farmOrigin;
        /** 返回导航：ESC 或羽毛「返回上一个菜单」时执行的打开动作（可为 null=根级关闭）。 */
        private Runnable back;

        GuiHolder(GuiType type, UUID uuid, int page, int farmSlot, WarehouseResource resource) {
            this(type, uuid, page, farmSlot, resource, -1, false);
        }

        GuiHolder(GuiType type, UUID uuid, int page, int farmSlot, WarehouseResource resource, int createSlot) {
            this(type, uuid, page, farmSlot, resource, createSlot, false);
        }

        GuiHolder(GuiType type, UUID uuid, int page, int farmSlot, WarehouseResource resource, int createSlot, boolean farmOrigin) {
            this.type = type;
            this.uuid = uuid;
            this.page = page;
            this.farmSlot = farmSlot;
            this.resource = resource;
            this.createSlot = createSlot;
            this.farmOrigin = farmOrigin;
        }

        public GuiType getType() { return type; }

        public UUID getUuid() { return uuid; }

        public int getPage() { return page; }

        public int getFarmSlot() { return farmSlot; }

        public WarehouseResource getResource() { return resource; }

        public int getCreateSlot() { return createSlot; }

        public boolean isFromFarm() { return farmOrigin; }

        public Runnable getBack() { return back; }

        void setBack(Runnable back) { this.back = back; }

        void setInventory(Inventory inv) { this.inventory = inv; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    /** 内部 28 格（第2~5行第2~8列）的 local 索引 -> 原始槽位。 */
    private static final int[] INNER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Shan plugin;
    private final DatabaseManager db;
    private final EconomyManager economy;
    private CropManager cropManager;
    /** 待确认删除的农田（聊天二次确认，带超时防误删）。 */
    private static final class PendingDelete {
        final int farmSlot;
        final long expireAtSec;
        PendingDelete(int farmSlot, long expireAtSec) {
            this.farmSlot = farmSlot;
            this.expireAtSec = expireAtSec;
        }
    }
    private final Map<UUID, PendingDelete> pendingDelete = new ConcurrentHashMap<>();

    public GuiManager(Shan plugin, DatabaseManager db, EconomyManager economy) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
    }

    /** 由 Shan 注入（CropManager 构造依赖 GuiManager，需事后注入避免循环构造）。 */
    public void setCropManager(CropManager cropManager) {
        this.cropManager = cropManager;
    }

    // ================= 打开入口 =================

    /** 打开农田 GUI 指定页（第 1 页 = 0；需 xlr.crop.farm）。含页解锁守卫、第 1 格免费解锁、满页自解锁下一页、返回主菜单导航。 */
    public void openFarm(Player player, int page) {
        if (!player.hasPermission("xlr.crop.farm")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        int unlockedPages = db.getFarmUnlockedPages(uuid);
        if (page < 0) {
            page = 0;
        }
        if (page > unlockedPages - 1) {
            // 仅能进入已解锁页（未解锁的下一页点按钮会有提示，不进入）
            page = Math.max(0, unlockedPages - 1);
        }
        // 每页第 1 格免费解锁（绿色可种植）
        if (db.getUnlockedCount(uuid, page) < ConfigManager.FARM_UNLOCK_FREE) {
            db.setUnlockedCountAtLeast(uuid, page, ConfigManager.FARM_UNLOCK_FREE);
        }
        // 当前页 28 格全部解锁后才自动解锁下一页（未确定项，采用「满页→自动解锁下一页」）
        if (db.getUnlockedCount(uuid, page) >= ConfigManager.FARM_PAGE_SLOTS) {
            db.setFarmUnlockedPagesAtLeast(uuid, page + 2);
        }
        GuiHolder h = new GuiHolder(GuiType.FARM, uuid, page, -1, null);
        h.setBack(() -> openMenu(player));
        Inventory inv = Bukkit.createInventory(h, 54, farmTitle(page));
        h.setInventory(inv);
        renderFarm(inv, h);
        player.openInventory(inv);
    }

    /** 打开二级生长 GUI（farmSlot 为全局槽位索引；需 xlr.crop.farm）。ESC 返回农田页。 */
    public void openGrowth(Player player, int farmSlot) {
        if (!player.hasPermission("xlr.crop.farm")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        String cropId = db.getFarmSlotCropType(uuid, farmSlot);
        if (cropId == null || cropManager == null) {
            player.sendMessage("§c该农田不存在或系统未就绪。");
            return;
        }
        CropType ct = CropRegistry.get(cropId);
        GuiHolder h = new GuiHolder(GuiType.GROWTH, uuid, 0, farmSlot, null);
        h.setBack(() -> openFarm(player, farmSlot / ConfigManager.FARM_PAGE_SLOTS));
        String growthTitle = ConfigManager.GUI_GROWTH_TITLE.contains("%farmname%")
                ? ConfigManager.GUI_GROWTH_TITLE.replace("%farmname%", ct == null ? "" : ct.getFarmName())
                : (ct == null ? ConfigManager.GUI_GROWTH_TITLE : ct.getFarmName());
        Inventory inv = Bukkit.createInventory(h, 54, growthTitle);
        h.setInventory(inv);
        renderGrowth(inv, h);
        player.openInventory(inv);
    }

    /** 打开农田管理 GUI（farmSlot 为全局槽位索引；需 xlr.crop.farm）。ESC 返回农田页。 */
    public void openFarmManage(Player player, int farmSlot) {
        if (!player.hasPermission("xlr.crop.farm")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        if (db.getFarmSlotCropType(uuid, farmSlot) == null || cropManager == null) {
            player.sendMessage("§c该农田不存在或系统未就绪。");
            return;
        }
        GuiHolder h = new GuiHolder(GuiType.FARM_MANAGE, uuid, 0, farmSlot, null);
        h.setBack(() -> openFarm(player, farmSlot / ConfigManager.FARM_PAGE_SLOTS));
        Inventory inv = Bukkit.createInventory(h, 27, ConfigManager.GUI_FARM_MANAGE_TITLE);
        h.setInventory(inv);
        renderFarmManage(inv, h);
        player.openInventory(inv);
    }

    /** 打开主菜单 GUI（需 xlr.crop.menu）。根级，无返回。 */
    public void openMenu(Player player) {
        if (!player.hasPermission("xlr.crop.menu")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.MENU, uuid, 0, -1, null);
        Inventory inv = Bukkit.createInventory(h, 27, ConfigManager.GUI_MENU_TITLE);
        h.setInventory(inv);
        renderMenu(inv);
        player.openInventory(inv);
    }

    /** 打开创建农田 GUI（无目标槽位时自动取首个空闲已解锁格；需 xlr.crop.create）。ESC 返回对应农田页。 */
    public void openCreateCrop(Player player) {
        openCreateCrop(player, -1);
    }

    /**
     * 打开创建农田 GUI。
     *
     * @param createSlot 选中介质后落到指定的农田全局槽位（点击农田页绿格时传入）；-1 表示自动取首个空闲已解锁格
     */
    public void openCreateCrop(Player player, int createSlot) {
        if (!player.hasPermission("xlr.crop.create")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.CREATE_CROP, uuid, 0, -1, null, createSlot);
        h.setBack(() -> openFarm(player, createSlot < 0 ? 0 : createSlot / ConfigManager.FARM_PAGE_SLOTS));
        Inventory inv = Bukkit.createInventory(h, 54, ConfigManager.GUI_CREATE_CROP_TITLE);
        h.setInventory(inv);
        renderCreateCrop(inv);
        player.openInventory(inv);
    }

    /** 打开农作物仓库 GUI（每页 28 格，入口见 cropMenuEntries；需 xlr.crop.gui）。ESC 返回主菜单。 */
    public void openCropMenu(Player player, int page) {
        if (!player.hasPermission("xlr.crop.gui")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        if (page < 0) {
            page = 0;
        }
        // 页数由入口数决定（每页 28 格）
        int entries = cropMenuEntries().size();
        int maxPage = Math.max(0, (entries - 1) / ConfigManager.WAREHOUSE_PAGE_SLOTS);
        if (page > maxPage) {
            page = maxPage;
        }
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.CROP_MENU, uuid, page, -1, null);
        h.setBack(() -> openMenu(player));
        Inventory inv = Bukkit.createInventory(h, 54, cropMenuTitle(page));
        h.setInventory(inv);
        renderCropMenu(inv, h);
        player.openInventory(inv);
    }

    /** 打开骨粉储存器 GUI（多页，页数受解锁限制；默认视为从主菜单进入）。 */
    public void openBonemeal(Player player, int page) {
        openBonemeal(player, page, false);
    }

    /**
     * 打开骨粉储存器 GUI（需 xlr.crop.bone）。
     *
     * @param fromFarm true = 从农田进入（返回时回农田第 1 页）；false = 从主菜单进入（返回主菜单）
     */
    public void openBonemeal(Player player, int page, boolean fromFarm) {
        if (!player.hasPermission("xlr.crop.bone")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        int unlocked = db.getUnlockedPages(uuid);
        if (page < 0) {
            page = 0;
        }
        if (page >= unlocked) {
            // 下限钳制：历史异常数据 unlocked=0 时不落到 -1
            page = Math.max(0, unlocked - 1);
        }
        GuiHolder h = new GuiHolder(GuiType.BONEMEAL, uuid, page, -1, null, -1, fromFarm);
        h.setBack(fromFarm ? () -> openFarm(player, 0) : () -> openMenu(player));
        Inventory inv = Bukkit.createInventory(h, 54, pageTitle(ConfigManager.GUI_BONEMEAL_TITLE, page));
        h.setInventory(inv);
        renderBonemeal(inv, h);
        player.openInventory(inv);
    }

    /** 打开仓库 GUI（单页；需 xlr.crop.gui）。itemType 为 "PRODUCT" 或具体种子素材材质名。ESC 返回农作物仓库。 */
    public void openWarehouse(Player player, String cropId, String itemType) {
        if (!player.hasPermission("xlr.crop.gui")) {
            player.sendMessage(ConfigManager.MSG_NO_PERM);
            return;
        }
        pendingDelete.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        WarehouseResource resource = "PRODUCT".equals(itemType)
                ? WarehouseResource.productOf(cropId)
                : WarehouseResource.seedOf(cropId, Material.matchMaterial(itemType));
        GuiHolder h = new GuiHolder(GuiType.WAREHOUSE, uuid, 0, -1, resource);
        h.setBack(() -> openCropMenu(player, 0));
        Inventory inv = Bukkit.createInventory(h, 54, ConfigManager.GUI_WAREHOUSE_TITLE.replace("%Farmitem%", resource.getTitle()));
        h.setInventory(inv);
        renderWarehouse(inv, h);
        player.openInventory(inv);
    }

    /** 定时结算后刷新玩家当前打开的自定义 GUI。 */
    public void refresh(Player player) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        if (!(inv.getHolder() instanceof GuiHolder h)) {
            return;
        }
        switch (h.getType()) {
            case FARM -> renderFarm(inv, h);
            case GROWTH -> renderGrowth(inv, h);
            case BONEMEAL -> renderBonemeal(inv, h);
            case WAREHOUSE -> renderWarehouse(inv, h);
            default -> { /* CREATE_CROP / CROP_MENU / FARM_MANAGE 无动态数据 */ }
        }
    }

    // ================= 渲染 =================

    private void renderFarm(Inventory inv, GuiHolder h) {
        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        UUID uuid = h.getUuid();
        int page = h.getPage();
        int unlocked = db.getUnlockedCount(uuid, page);
        Map<Integer, String> slots = db.getFarmSlots(uuid);
        for (int local = 0; local < ConfigManager.FARM_PAGE_SLOTS; local++) {
            int raw = INNER_SLOTS[local];
            int globalIndex = page * ConfigManager.FARM_PAGE_SLOTS + local;
            String cropId = slots.get(globalIndex);
            if (cropId != null) {
                // 该格已种植农田
                contents[raw] = farmIcon(CropRegistry.get(cropId), db.getFarmLevel(uuid, globalIndex));
            } else if (local < unlocked) {
                // 绿色：已解锁可种植格（第 1 格为免费种植格）
                contents[raw] = freeTile(local == 0);
            } else if (unlocked < ConfigManager.FARM_PAGE_SLOTS
                    && local < unlocked + ConfigManager.FARM_UNLOCK_BATCH) {
                // 黄色：当前待解锁批（付费购买这批）
                contents[raw] = yellowTile();
            } else {
                // 红色：前置未解锁，需先解锁前一批
                contents[raw] = redTile();
            }
        }
        // 返回上一个菜单（第6行第1格，羽毛）
        contents[ConfigManager.FARM_BACK_SLOT] = backFeather("Farm.PrveBack", "§a返回上一个菜单");
        // 上一页（第6行第4格，仅第 2 页起）
        contents[ConfigManager.FARM_PREV_SLOT] = page > 0
                ? guiItem("Farm.Prvepage", Material.ARROW, "§a上一页", List.of()) : frame();
        // 下一页（第6行第6格；是否可进入由点击时判断页解锁）
        contents[ConfigManager.FARM_NEXT_SLOT] = guiItem("Farm.Nextpage", Material.ARROW, "§a下一页", List.of());
        // 第6行第9格：骨粉储存器入口
        contents[ConfigManager.FARM_BONEMEAL_SLOT] = guiItem("Farm.Bone", Material.BONE_MEAL, "§a骨粉储存器",
                List.of("§7点击打开骨粉储存器"));
        inv.setContents(contents);
    }

    private void renderFarmManage(Inventory inv, GuiHolder h) {
        ItemStack[] contents = new ItemStack[27];
        Arrays.fill(contents, frame());
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(h.getUuid(), h.getFarmSlot()));
        contents[ConfigManager.FARM_MANAGE_REPLANT_SLOT] = replantItem(ct);
        contents[ConfigManager.FARM_MANAGE_UPGRADE_SLOT] = upgradeItem(ct, db.getFarmLevel(h.getUuid(), h.getFarmSlot()));
        contents[ConfigManager.FARM_MANAGE_FAST_SLOT] = bonemealFastItem(db.getFarmBonemealFast(h.getUuid(), h.getFarmSlot()));
        contents[ConfigManager.FARM_MANAGE_DELETE_SLOT] = deleteFarmItem();
        contents[ConfigManager.FARM_MANAGE_BACK_SLOT] = guiItem("Farmmanage.Prvepage", Material.ARROW, "§a返回农田", List.of());
        inv.setContents(contents);
    }

    private void renderMenu(Inventory inv) {
        ItemStack[] contents = new ItemStack[27];
        Arrays.fill(contents, frame());
        contents[ConfigManager.MENU_FARM_SLOT] = guiItem("menu.Farm", Material.GRASS_BLOCK, "§6农田",
                List.of("§7点击进入自己的农田"));
        contents[ConfigManager.MENU_BONEMEAL_SLOT] = guiItem("menu.Bone", Material.BONE_MEAL, "§6骨粉储存",
                List.of("§7点击进入骨粉储存器"));
        contents[ConfigManager.MENU_CROP_MENU_SLOT] = guiItem("menu.Crop", Material.CHEST, "§6农作物仓库",
                List.of("§7点击进入农作物仓库"));
        inv.setContents(contents);
    }

    private void renderCreateCrop(Inventory inv, GuiHolder h) {
        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        // 只按内部 28 格顺序填充（第2~5行第2~8列），不占外圈黑玻璃；
        // 顺序与 handleCreateCropClick 的 rawToLocal 映射一致（local i ↔ INNER_SLOTS[i]）
        List<CropType> crops = new ArrayList<>(CropRegistry.all().values());
        for (int i = 0; i < crops.size() && i < INNER_SLOTS.length; i++) {
            contents[INNER_SLOTS[i]] = createEntry(crops.get(i));
        }
        // 返回上一个菜单（第6行第1格，羽毛）
        contents[ConfigManager.FARM_BACK_SLOT] = backFeather("Farm.PrveBack", "§a返回上一个菜单");
        inv.setContents(contents);
    }

    private void renderCropMenu(Inventory inv, GuiHolder h) {
        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        // 按作物动态生成入口：有独立种子作物 2 格（种子仓库在前、产物仓库在后），
        // 无种子作物（土豆/胡萝卜/竹子等，用本体当种子）只 1 格产物仓库，避免出现两个一模一样的东西；
        // 条目配置共用 gui.yml Crop.CropStorage 段，材质/名称/Lore 支持 %icon%/%name%/%Farmitem% 变量
        List<WarehouseResource> entries = cropMenuEntries();
        int start = h.getPage() * ConfigManager.WAREHOUSE_PAGE_SLOTS;
        int local = 0;
        for (int i = start; i < Math.min(entries.size(), start + ConfigManager.WAREHOUSE_PAGE_SLOTS); i++) {
            WarehouseResource res = entries.get(i);
            CropType ct = CropRegistry.get(res.getCropId());
            boolean seed = !res.isProduct();
            Material mat = res.getMaterial();
            String entryName = "§6" + ct.getName() + (seed ? "种子仓库" : "仓库");
            contents[INNER_SLOTS[local++]] = guiItem("Crop.CropStorage",
                    mat,
                    entryName,
                    List.of("§7点击查看" + ct.getName() + (seed ? "种子" : "") + "库存"),
                    "%Farmitem%", ct.getName(),
                    "%icon%", mat.name(),
                    "%name%", ct.getName());
        }
        // 导航同格：有下一页显示下一页，否则显示上一页（末页无按钮）
        boolean hasMore = entries.size() > (h.getPage() + 1) * ConfigManager.WAREHOUSE_PAGE_SLOTS;
        if (h.getPage() > 0 || hasMore) {
            contents[ConfigManager.CROP_MENU_NEXT_SLOT] = hasMore
                    ? guiItem("Crop.Nextpage", Material.ARROW, "§a下一页", List.of())
                    : guiItem("Crop.Prvepage", Material.ARROW, "§a上一页", List.of());
        }
        // 返回主菜单（第6行第1格，羽毛）
        contents[ConfigManager.CROP_MENU_BACK_SLOT] = backFeather("Crop.Back", "§a返回主菜单");
        inv.setContents(contents);
    }

    private void renderBonemeal(Inventory inv, GuiHolder h) {
        long total = db.getBonemeal(h.getUuid());
        long start = (long) h.getPage() * ConfigManager.BONEMEAL_PAGE_SLOTS * ConfigManager.WAREHOUSE_STACK;
        long remaining = Math.max(0L, total - start);

        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        for (int local = 0; local < ConfigManager.BONEMEAL_PAGE_SLOTS; local++) {
            int raw = INNER_SLOTS[local];
            if (remaining <= 0) {
                contents[raw] = null;
                continue;
            }
            int put = (int) Math.min(ConfigManager.WAREHOUSE_STACK, remaining);
            ItemStack item = new ItemStack(Material.BONE_MEAL);
            item.setAmount(put);
            contents[raw] = item;
            remaining -= put;
        }
        if (h.getPage() > 0) {
            contents[ConfigManager.BONEMEAL_PREV_SLOT] = guiItem("Bone.Prvepage", Material.ARROW, "§a上一页", List.of());
        }
        // 下一页（第6行第5格；第 2 页起是否可进入由点击时判断页解锁）
        contents[ConfigManager.BONEMEAL_NEXT_SLOT] = guiItem("Bone.Nextpage", Material.ARROW, "§a下一页", List.of());
        if (h.getPage() == 0) {
            contents[ConfigManager.BONEMEAL_UNLOCK_SLOT] = unlockChest(db.getUnlockedPages(h.getUuid()));
        }
        // 返回上一个菜单（第6行第1格，羽毛）
        contents[ConfigManager.BONEMEAL_BACK_SLOT] = backFeather("Bone.Back", "§a返回上一个菜单");
        inv.setContents(contents);
    }

    private void renderWarehouse(Inventory inv, GuiHolder h) {
        WarehouseResource res = h.getResource();
        long total = db.getCropStock(h.getUuid(), res.getCropId(), res.getItemType());

        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        for (int local = 0; local < ConfigManager.WAREHOUSE_PAGE_SLOTS; local++) {
            int raw = INNER_SLOTS[local];
            if (total <= 0) {
                contents[raw] = null;
                continue;
            }
            int put = (int) Math.min(ConfigManager.WAREHOUSE_STACK, total);
            ItemStack item = new ItemStack(res.getMaterial());
            item.setAmount(put);
            contents[raw] = item;
            total -= put;
        }
        contents[ConfigManager.WAREHOUSE_FILL_SLOT] = guiItem("CropStorage.Restock", Material.CHEST, "§a点击填充",
                List.of("§7从后备库存补充本页空格"));
        contents[ConfigManager.WAREHOUSE_BACK_SLOT] = backFeather("CropStorage.Back", "§a返回农作物仓库");
        inv.setContents(contents);
    }

    private void renderGrowth(Inventory inv, GuiHolder h) {
        if (cropManager == null) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(h.getUuid(), h.getFarmSlot()));
        int level = db.getFarmLevel(h.getUuid(), h.getFarmSlot());
        List<PlotState> plots = cropManager.getPlots(h.getUuid(), h.getFarmSlot());
        ItemStack[] contents = new ItemStack[54];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = i < plots.size() ? growthItem(plots.get(i), now, ct, level) : null;
        }
        inv.setContents(contents);
    }

    // ================= 点击分发 =================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 纵深防御：点击者必须是 GUI 所有者，杜绝跨玩家操作导致虚拟库存/物品串联
        if (!h.getUuid().equals(player.getUniqueId())) {
            return;
        }
        if (e.getClickedInventory() == null) {
            return;
        }
        // 点击玩家背包：仅骨粉 GUI 允许放入骨粉，其余一律拦截（仓库只能取出不能放入）
        if (!e.getClickedInventory().equals(top)) {
            if (h.getType() == GuiType.BONEMEAL
                    && e.getCurrentItem() != null
                    && e.getCurrentItem().getType() == Material.BONE_MEAL) {
                depositBonemeal(player, e);
            }
            return;
        }
        switch (h.getType()) {
            case FARM -> handleFarmClick(player, e, h);
            case GROWTH -> { /* 纯展示，无交互 */ }
            case FARM_MANAGE -> handleFarmManageClick(player, e, h);
            case CREATE_CROP -> handleCreateCropClick(player, e, h);
            case CROP_MENU -> handleCropMenuClick(player, e, h);
            case BONEMEAL -> handleBonemealClick(player, e, h);
            case WAREHOUSE -> handleWarehouseClick(player, e, h);
            case MENU -> handleMenuClick(player, e);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder) {
            e.setCancelled(true);
        }
    }

    /**
     * 删除农田二次确认：玩家在聊天栏输入「删除」确认删除，「取消」放弃。
     * 异步线程仅读取输入与待删标记，实际删库/发消息/打开 GUI 均调度回主线程执行。
     * 超时（{@link ConfigManager#DELETE_CONFIRM_TIMEOUT_SEC}）或掉线均自动作废待确认态。
     */
    @EventHandler
    public void onDeleteConfirmChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        PendingDelete pd = pendingDelete.get(uuid);
        if (pd == null) {
            return; // 无待确认的删除，正常聊天放行
        }
        // 超时保护：确认窗口过期则作废，防闲置/掉线后重上线误删
        if (System.currentTimeMillis() / 1000 > pd.expireAtSec) {
            pendingDelete.remove(uuid);
            return;
        }
        String msg = e.getMessage().trim();
        // 仅对「删除」「取消」取消聊天事件，其余消息一律放行（确认期间玩家仍可正常聊天）
        if ("删除".equals(msg)) {
            e.setCancelled(true);
            pendingDelete.remove(uuid);
            int farmSlot = pd.farmSlot;
            Bukkit.getScheduler().runTask(plugin, () -> {
                // 二次校验：确认时农田仍存在才执行删除（防确认期间状态漂移）
                if (!db.hasFarmSlot(uuid, farmSlot)) {
                    player.sendMessage(ConfigManager.MSG_DELETE_CANCELLED);
                    return;
                }
                // 原子删除两表，失败必须提示，绝不假装成功
                if (!db.deleteFarm(uuid, farmSlot)) {
                    player.sendMessage(ConfigManager.MSG_DB_ERROR);
                    return;
                }
                player.sendMessage(ConfigManager.MSG_DELETE_DONE);
                openFarm(player, farmSlot / ConfigManager.FARM_PAGE_SLOTS);
            });
        } else if ("取消".equals(msg)) {
            e.setCancelled(true);
            pendingDelete.remove(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ConfigManager.MSG_DELETE_CANCELLED));
        }
        // 其他内容：放行（正常聊天广播不受影响），确认状态保留至输入 删除/取消、超时或打开任意 GUI
    }

    /** 玩家掉线清理待确认删除态，防重上线聊天误删。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        pendingDelete.remove(e.getPlayer().getUniqueId());
    }

    /**
     * ESC / 背包键关闭自定义 GUI 时返回「上一个菜单」（需求4）。
     *
     * <p>仅当玩家主动关闭（Reason.PLAYER）且该界面设置了 back 时才返回；
     * 插件主动打开新界面触发的是 OPEN_NEW，不在此列，因此不会连环误触发。
     */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder h)) {
            return;
        }
        Runnable back = h.getBack();
        if (back == null) {
            return; // 根级（主菜单）无需返回
        }
        if (e.getReason() == InventoryCloseEvent.Reason.OPEN_NEW
                || e.getReason() == InventoryCloseEvent.Reason.DEATH
                || e.getReason() == InventoryCloseEvent.Reason.DISCONNECT
                || e.getReason() == InventoryCloseEvent.Reason.TELEPORT) {
            return;
        }
        if (e.getPlayer() instanceof Player player) {
            Runnable task = back;
            Bukkit.getScheduler().runTask(plugin, () -> task.run());
        }
    }

    private void handleFarmClick(Player player, InventoryClickEvent e, GuiHolder h) {
        int raw = e.getSlot();
        UUID uuid = h.getUuid();
        int page = h.getPage();
        // 返回上一个菜单（羽毛）
        if (raw == ConfigManager.FARM_BACK_SLOT) {
            Runnable back = h.getBack();
            if (back != null) {
                scheduleOpen(back::run);
            }
            return;
        }
        // 下一页：判断下一页是否已解锁
        if (raw == ConfigManager.FARM_NEXT_SLOT) {
            int unlockedPages = db.getFarmUnlockedPages(uuid);
            if (page + 1 < unlockedPages) {
                scheduleOpen(() -> openFarm(player, page + 1));
            } else {
                player.sendMessage(ConfigManager.MSG_FARM_PAGE_LOCKED.replace("%page%", String.valueOf(page + 2)));
            }
            return;
        }
        // 上一页（第 2 页起）
        if (raw == ConfigManager.FARM_PREV_SLOT && page > 0) {
            scheduleOpen(() -> openFarm(player, page - 1));
            return;
        }
        if (raw == ConfigManager.FARM_BONEMEAL_SLOT) {
            scheduleOpen(() -> openBonemeal(player, 0, true));
            return;
        }
        int local = rawToLocal(raw);
        if (local < 0) {
            return;
        }
        int globalIndex = page * ConfigManager.FARM_PAGE_SLOTS + local;
        if (db.hasFarmSlot(uuid, globalIndex)) {
            // 已种植：左键进入生长，右键进入管理
            if (e.isRightClick()) {
                scheduleOpen(() -> openFarmManage(player, globalIndex));
            } else {
                scheduleOpen(() -> openGrowth(player, globalIndex));
            }
            return;
        }
        int unlocked = db.getUnlockedCount(uuid, page);
        if (local < unlocked) {
            // 绿色已解锁空格：进入创建农田视图（在该格创建）
            scheduleOpen(() -> openCreateCrop(player, globalIndex));
        } else if (unlocked < ConfigManager.FARM_PAGE_SLOTS
                && local < unlocked + ConfigManager.FARM_UNLOCK_BATCH) {
            // 黄色：付费解锁本批种植格
            handleFarmUnlock(player, uuid, page);
        } else {
            // 红色：前置未解锁
            player.sendMessage(ConfigManager.MSG_FARM_UNLOCK_RED);
        }
    }

    /** 农田管理：补种 / 升级 / 骨粉加速开关 / 删除农田（聊天二次确认）/ 返回农田。 */
    private void handleFarmManageClick(Player player, InventoryClickEvent e, GuiHolder h) {
        if (cropManager == null) {
            return;
        }
        int raw = e.getSlot();
        if (raw == ConfigManager.FARM_MANAGE_REPLANT_SLOT) {
            CropType ct = CropRegistry.get(db.getFarmSlotCropType(h.getUuid(), h.getFarmSlot()));
            String seedName = ct == null ? "种子" : ct.getName() + "种子";
            int replanted = cropManager.replant(player, h.getUuid(), h.getFarmSlot());
            boolean consumeSeed = ct == null || ct.isConsumeSeed();
            if (replanted > 0) {
                if (consumeSeed) {
                    player.sendMessage(ConfigManager.MSG_REPLANT_DONE
                            .replace("%count%", String.valueOf(replanted))
                            .replace("%seed%", String.valueOf(replanted * ConfigManager.REPLANT_COST_SEED))
                            .replace("%seedname%", seedName));
                } else {
                    // 不消耗种子的作物：只报补种格数
                    player.sendMessage("§a已补种 " + replanted + " 格。");
                }
            } else if (replanted == 0) {
                player.sendMessage(ConfigManager.MSG_REPLANT_EMPTY);
            } else {
                // -1：有空格但种子不足（消耗种子作物）/ 落库失败（不消耗种子作物）
                player.sendMessage(consumeSeed
                        ? ConfigManager.MSG_NO_SEED.replace("%seedname%", seedName)
                        : ConfigManager.MSG_DB_ERROR);
            }
        } else if (raw == ConfigManager.FARM_MANAGE_UPGRADE_SLOT) {
            handleFarmUpgrade(player, h);
        } else if (raw == ConfigManager.FARM_MANAGE_FAST_SLOT) {
            toggleFarmFast(player, h);
        } else if (raw == ConfigManager.FARM_MANAGE_DELETE_SLOT) {
            // 关闭 GUI 并进入聊天二次确认（带超时）
            player.closeInventory();
            pendingDelete.put(h.getUuid(), new PendingDelete(h.getFarmSlot(),
                    System.currentTimeMillis() / 1000 + ConfigManager.DELETE_CONFIRM_TIMEOUT_SEC));
            player.sendMessage(ConfigManager.MSG_DELETE_CONFIRM);
        } else if (raw == ConfigManager.FARM_MANAGE_BACK_SLOT) {
            scheduleOpen(() -> openFarm(player, h.getFarmSlot() / ConfigManager.FARM_PAGE_SLOTS));
        }
    }

    /** 切换该农田的骨粉加速开关。 */
    private void toggleFarmFast(Player player, GuiHolder h) {
        boolean on = !db.getFarmBonemealFast(h.getUuid(), h.getFarmSlot());
        if (!db.setFarmBonemealFast(h.getUuid(), h.getFarmSlot(), on)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        player.sendMessage(ConfigManager.MSG_BONEMEAL_FAST_TOGGLED.replace("%state%", on ? "开启" : "关闭"));
        scheduleOpen(() -> openFarmManage(player, h.getFarmSlot()));
    }

    /** 主菜单点击分发（槽位可配置，用 if 判断）。 */
    private void handleMenuClick(Player player, InventoryClickEvent e) {
        int raw = e.getSlot();
        if (raw == ConfigManager.MENU_FARM_SLOT) {
            scheduleOpen(() -> openFarm(player, 0));
        } else if (raw == ConfigManager.MENU_BONEMEAL_SLOT) {
            scheduleOpen(() -> openBonemeal(player, 0, false));
        } else if (raw == ConfigManager.MENU_CROP_MENU_SLOT) {
            scheduleOpen(() -> openCropMenu(player, 0));
        }
    }

    /** 农田升级：Lv1→2（1000 金币）、Lv2→3（2000 金币），满级 Lv3 封顶。 */
    private void handleFarmUpgrade(Player player, GuiHolder h) {
        UUID uuid = h.getUuid();
        int level = db.getFarmLevel(uuid, h.getFarmSlot());
        int maxLevel = ConfigManager.getFarmMaxLevel(db.getFarmSlotCropType(uuid, h.getFarmSlot()));
        if (level >= maxLevel) {
            player.sendMessage(ConfigManager.MSG_FARM_MAX_LEVEL
                    .replace("%max-level%", String.valueOf(maxLevel)));
            return;
        }
        if (economy == null || !economy.isEnabled()) {
            player.sendMessage(ConfigManager.MSG_NO_ECONOMY);
            return;
        }
        // 升级价格以 gui.yml FarmUpdate.<作物>.LV<目标等级>.Money 为准，未配置回退默认
        double cost = ConfigManager.getFarmUpgradeCost(db.getFarmSlotCropType(uuid, h.getFarmSlot()), level + 1);
        String costText = String.valueOf((long) cost);
        if (!economy.has(player, cost)) {
            player.sendMessage(ConfigManager.MSG_FARM_UPGRADE_NO_MONEY.replace("%cost%", costText));
            return;
        }
        // 幂等经济操作：登记 PENDING → 扣 Vault 金币 → 落库(幂等 at-least) → 标记 PAID。
        // 崩溃窗口（扣款前 / 扣款后落库前 / 落库后未标记）由启动恢复按「余额是否已扣」补写/回滚，
        // 杜绝「DB 已写、钱未扣」的免费升级，也杜绝「钱已扣、等级没升」。
        double balanceBefore = economy.getBalance(player);
        String opId = db.beginEconomicOp(uuid, "FARM_UPGRADE",
                "slot=" + h.getFarmSlot() + " toLv=" + (level + 1) + " cost=" + costText,
                cost, balanceBefore, level + 1, h.getFarmSlot());
        if (opId == null) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        if (!economy.withdraw(player, cost)) {
            // 扣款失败：DB 尚未写入，直接标记回滚（无需回写）
            db.finishEconomicOp(opId, "ROLLED_BACK");
            player.sendMessage(ConfigManager.MSG_FARM_UPGRADE_NO_MONEY.replace("%cost%", costText));
            return;
        }
        if (db.setFarmLevelAtLeast(uuid, h.getFarmSlot(), level + 1)) {
            db.finishEconomicOp(opId, "PAID");
            player.sendMessage(ConfigManager.MSG_FARM_UPGRADED
                    .replace("%level%", String.valueOf(level + 1))
                    .replace("%cost%", costText));
        } else {
            // 扣款成功但落库失败：先尝试退款；退款也失败则保持 PENDING，由启动恢复按「钱已扣」补写等级
            if (economy.deposit(player, cost)) {
                db.finishEconomicOp(opId, "ROLLED_BACK");
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
            } else {
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
                plugin.getLogger().warning("升级扣款成功但退款与落库均失败，操作保持 PENDING 等待启动恢复: uuid="
                        + uuid + " opId=" + opId + " cost=" + costText);
            }
        }
        scheduleOpen(() -> openFarmManage(player, h.getFarmSlot()));
    }

    /** 创建农田 GUI：点击作物条目创建对应农田。 */
    private void handleCreateCropClick(Player player, InventoryClickEvent e, GuiHolder h) {
        // 返回上一个菜单（羽毛）
        if (e.getSlot() == ConfigManager.FARM_BACK_SLOT) {
            Runnable back = h.getBack();
            if (back != null) {
                scheduleOpen(back::run);
            }
            return;
        }
        int local = rawToLocal(e.getSlot());
        if (local < 0 || cropManager == null) {
            return;
        }
        List<CropType> crops = new ArrayList<>(CropRegistry.all().values());
        if (local >= crops.size()) {
            return;
        }
        // 在农田页选中的目标格（createSlot）上创建
        createCrop(player, crops.get(local), h.getCreateSlot());
    }

    private void handleCropMenuClick(Player player, InventoryClickEvent e, GuiHolder h) {
        int raw = e.getSlot();
        // 返回主菜单（羽毛）
        if (raw == ConfigManager.CROP_MENU_BACK_SLOT) {
            Runnable back = h.getBack();
            if (back != null) {
                scheduleOpen(back::run);
            }
            return;
        }
        // 导航同格：有下一页则下一页，否则上一页
        if (raw == ConfigManager.CROP_MENU_NEXT_SLOT) {
            int entries = cropMenuEntries().size();
            boolean hasMore = entries > (h.getPage() + 1) * ConfigManager.WAREHOUSE_PAGE_SLOTS;
            scheduleOpen(() -> openCropMenu(player, hasMore ? h.getPage() + 1 : h.getPage() - 1));
            return;
        }
        int local = rawToLocal(raw);
        if (local < 0) {
            return;
        }
        // 与渲染顺序一致：local → 条目列表索引（cropMenuEntries）
        int idx = h.getPage() * ConfigManager.WAREHOUSE_PAGE_SLOTS + local;
        List<WarehouseResource> entries = cropMenuEntries();
        if (idx >= entries.size()) {
            return;
        }
        WarehouseResource res = entries.get(idx);
        scheduleOpen(() -> openWarehouse(player, res.getCropId(), res.getItemType()));
    }

    /**
     * 农作物仓库条目列表（渲染与点击共用，顺序必须一致）：
     * 有独立种子的作物 = [每个种子素材独立一条, 产物仓库 1 条]；
     * 无种子作物（本体当种子，如土豆/胡萝卜/竹子/苹果）= 产物仓库 1 条。
     */
    private List<WarehouseResource> cropMenuEntries() {
        List<WarehouseResource> list = new ArrayList<>();
        for (CropType ct : CropRegistry.all().values()) {
            if (ct.hasSeed()) {
                for (Material m : ct.getSeedMaterials()) {
                    list.add(WarehouseResource.seedOf(ct.getId(), m));
                }
            }
            list.add(WarehouseResource.productOf(ct.getId()));
        }
        return list;
    }

    private void handleBonemealClick(Player player, InventoryClickEvent e, GuiHolder h) {
        UUID uuid = h.getUuid();
        int raw = e.getSlot();
        // 返回上一个菜单（羽毛）
        if (raw == ConfigManager.BONEMEAL_BACK_SLOT) {
            Runnable back = h.getBack();
            if (back != null) {
                scheduleOpen(back::run);
            }
            return;
        }
        if (raw == ConfigManager.BONEMEAL_NEXT_SLOT) {
            int unlocked = db.getUnlockedPages(uuid);
            if (h.getPage() + 1 < unlocked) {
                scheduleOpen(() -> openBonemeal(player, h.getPage() + 1, h.isFromFarm()));
            } else {
                player.sendMessage(ConfigManager.MSG_NEXT_PAGE_LOCKED);
            }
            return;
        }
        if (raw == ConfigManager.BONEMEAL_PREV_SLOT && h.getPage() > 0) {
            scheduleOpen(() -> openBonemeal(player, h.getPage() - 1, h.isFromFarm()));
            return;
        }
        if (raw == ConfigManager.BONEMEAL_UNLOCK_SLOT && h.getPage() == 0) {
            handleUnlock(player, uuid, h.isFromFarm());
            return;
        }
        int local = rawToLocal(raw);
        if (local < 0) {
            return;
        }
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType().isAir()) {
            return;
        }
        takeBonemeal(player, e, h);
    }

    private void handleWarehouseClick(Player player, InventoryClickEvent e, GuiHolder h) {
        if (e.getSlot() == ConfigManager.WAREHOUSE_BACK_SLOT) {
            Runnable back = h.getBack();
            if (back != null) {
                scheduleOpen(back::run);
            }
            return;
        }
        if (e.getSlot() == ConfigManager.WAREHOUSE_FILL_SLOT) {
            fillPage(player, e.getInventory(), h);
            return;
        }
        int local = rawToLocal(e.getSlot());
        if (local < 0) {
            return;
        }
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType().isAir()) {
            return;
        }
        takeItem(player, e, h);
    }

    // ================= 创建 / 骨粉 / 仓库操作 =================

    /** 创建农田：消耗全部种子素材（背包优先→各素材仓库）并在指定格/首个空闲已解锁格创建，成功后跳转农田页。 */
    private void createCrop(Player player, CropType ct, int createSlot) {
        UUID uuid = player.getUniqueId();
        int maxFarms = ConfigManager.allowedFarms(player);
        int farmCount = db.getFarmCount(uuid);
        if (farmCount < 0) {
            // 查询失败：阻止创建，防止 DB 故障时绕过农田上限
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        if (farmCount >= maxFarms) {
            player.sendMessage(ConfigManager.MSG_FARM_LIMIT.replace("%max%", String.valueOf(maxFarms)));
            return;
        }
        int target = createSlot;
        if (target < 0) {
            // 未指定目标格（/xlr crop create <type>）：自动取首个空闲已解锁格
            target = firstFreeUnlockedSlot(uuid);
            if (target < 0) {
                // 无明显可种格：交回农药野逻辑提示（已解锁空格均无）
                player.sendMessage(ConfigManager.MSG_FARM_FULL);
                return;
            }
        } else {
            // 指定格校验：须已解锁且空闲
            int page = target / ConfigManager.FARM_PAGE_SLOTS;
            int local = target % ConfigManager.FARM_PAGE_SLOTS;
            if (db.hasFarmSlot(uuid, target)) {
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
                return;
            }
            if (local >= db.getUnlockedCount(uuid, page)) {
                player.sendMessage(ConfigManager.MSG_FARM_UNLOCK_RED);
                return;
            }
        }
        int globalIndex = cropManager.createFarm(player, ct, target);
        if (globalIndex < 0) {
            player.sendMessage(ConfigManager.MSG_NO_SEED.replace("%seedname%", ct.getName() + "种子"));
            return;
        }
        int planted = CropManager.PLOT_COUNT - cropManager.countEmptyPlots(uuid, globalIndex);
        int page = globalIndex / ConfigManager.FARM_PAGE_SLOTS + 1;
        int slot = globalIndex % ConfigManager.FARM_PAGE_SLOTS + 1;
        player.sendMessage(ConfigManager.MSG_CROP_CREATED
                .replace("%farmname%", ct.getFarmName())
                .replace("%page%", String.valueOf(page))
                .replace("%slot%", String.valueOf(slot))
                .replace("%replant%", String.valueOf(planted)));
        scheduleOpen(() -> openFarm(player, globalIndex / ConfigManager.FARM_PAGE_SLOTS));
    }

    /**
     * 命令入口创建农田（/xlr crop create &lt;type&gt;）：在首个空闲已解锁格创建。
     * 上限/扣种子/消息统一走 {@link #createCrop(Player, CropType, int)}。
     */
    public void createCropCommand(Player player, CropType ct) {
        createCrop(player, ct, -1);
    }

    /**
     * 查找首个「已解锁且空闲」的农田全局槽位（跨已解锁页，local < 已解锁数）。
     *
     * @return 全局槽位；无可用返回 -1
     */
    private int firstFreeUnlockedSlot(UUID uuid) {
        int unlockedPages = db.getFarmUnlockedPages(uuid);
        for (int page = 0; page < unlockedPages; page++) {
            int count = db.getUnlockedCount(uuid, page);
            for (int local = 0; local < count; local++) {
                int gi = page * ConfigManager.FARM_PAGE_SLOTS + local;
                if (!db.hasFarmSlot(uuid, gi)) {
                    return gi;
                }
            }
        }
        return -1;
    }

    /**
     * 农田页黄格付费解锁下一批种植格（每批 FARM_UNLOCK_BATCH 个，价 FARM_UNLOCK_PRICE）。
     * 走经济操作状态机（PENDING→扣款→落库→PAID），崩溃窗口由启动恢复兜底。
     */
    private void handleFarmUnlock(Player player, UUID uuid, int page) {
        if (economy == null || !economy.isEnabled()) {
            player.sendMessage(ConfigManager.MSG_NO_ECONOMY);
            return;
        }
        int unlocked = db.getUnlockedCount(uuid, page);
        if (unlocked >= ConfigManager.FARM_PAGE_SLOTS) {
            return;
        }
        int target = Math.min(ConfigManager.FARM_PAGE_SLOTS, unlocked + ConfigManager.FARM_UNLOCK_BATCH);
        double cost = ConfigManager.FARM_UNLOCK_PRICE;
        String costText = String.valueOf((long) cost);
        if (!economy.has(player, cost)) {
            player.sendMessage(ConfigManager.MSG_FARM_UNLOCK_NO_MONEY.replace("%cost%", costText));
            return;
        }
        double balanceBefore = economy.getBalance(player);
        String opId = db.beginEconomicOp(uuid, "FARM_UNLOCK",
                "page=" + page + " count=" + target + " cost=" + costText,
                cost, balanceBefore, target, page);
        if (opId == null) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        if (!economy.withdraw(player, cost)) {
            db.finishEconomicOp(opId, "ROLLED_BACK");
            player.sendMessage(ConfigManager.MSG_FARM_UNLOCK_NO_MONEY.replace("%cost%", costText));
            return;
        }
        if (db.setUnlockedCountAtLeast(uuid, page, target)) {
            db.finishEconomicOp(opId, "PAID");
            int gained = target - unlocked;
            player.sendMessage(ConfigManager.MSG_FARM_UNLOCK_DONE
                    .replace("%cost%", costText)
                    .replace("%count%", String.valueOf(gained)));
        } else {
            if (economy.deposit(player, cost)) {
                db.finishEconomicOp(opId, "ROLLED_BACK");
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
            } else {
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
                plugin.getLogger().warning("农田解锁扣款成功但退款与落库均失败，保持 PENDING: uuid=" + uuid + " opId=" + opId);
            }
        }
        scheduleOpen(() -> openFarm(player, page));
    }

    /** 骨粉 GUI：点击背包骨粉物品放入库存。 */
    private void depositBonemeal(Player player, InventoryClickEvent e) {
        ItemStack item = e.getCurrentItem();
        int qty = item.getAmount();
        // 库存写入成功才清空物品，失败保留原物，避免物品凭空消失
        if (!db.addBonemeal(player.getUniqueId(), qty)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        e.setCurrentItem(null);
        player.sendMessage(ConfigManager.MSG_BONEMEAL_ADD.replace("%qty%", String.valueOf(qty)));
        // 刷新当前页显示（放入后库存变化）
        if (e.getInventory().getHolder() instanceof GuiHolder h) {
            int page = h.getPage();
            scheduleOpen(() -> openBonemeal(player, page, h.isFromFarm()));
        }
    }

    /** 骨粉 GUI：点击展示格取出骨粉到背包。 */
    private void takeBonemeal(Player player, InventoryClickEvent e, GuiHolder h) {
        int qty = e.getCurrentItem().getAmount();
        // 先扣虚拟库存（成功才发物），防止 DB 失败后物品已发 = 刷物品
        if (!db.addBonemeal(h.getUuid(), -qty)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        ItemStack give = new ItemStack(Material.BONE_MEAL, qty);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(give);
        int accepted = qty - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (accepted <= 0) {
            // 背包全满：退回虚拟库存（退回失败必须记录台账，否则骨粉凭空消失）
            if (!db.addBonemeal(h.getUuid(), qty)) {
                db.addCompensation(h.getUuid(), "BONEMEAL", null, null, qty, "takeBonemeal-full-rollback");
                plugin.getLogger().warning("骨粉退回库存失败: uuid=" + h.getUuid() + " qty=" + qty);
            }
            player.sendMessage(ConfigManager.MSG_INV_FULL);
            return;
        }
        if (accepted < qty) {
            // 装不下的部分退回虚拟库存
            int back = qty - accepted;
            if (!db.addBonemeal(h.getUuid(), back)) {
                db.addCompensation(h.getUuid(), "BONEMEAL", null, null, back, "takeBonemeal-partial-rollback");
                plugin.getLogger().warning("骨粉部分退回库存失败: uuid=" + h.getUuid() + " qty=" + back);
            }
        }
        if (accepted >= qty) {
            e.setCurrentItem(null);
        } else {
            ItemStack remain = new ItemStack(Material.BONE_MEAL, qty - accepted);
            e.setCurrentItem(remain);
        }
        player.sendMessage(ConfigManager.MSG_BONEMEAL_TAKE.replace("%qty%", String.valueOf(accepted)));
    }

    /** 骨粉升级：花费金币解锁下一页（所有人可点击，无需权限；金币不足仍会被拦住）。 */
    private void handleUnlock(Player player, UUID uuid, boolean fromFarm) {
        if (economy == null || !economy.isEnabled()) {
            player.sendMessage(ConfigManager.MSG_NO_ECONOMY);
            return;
        }
        int unlocked = db.getUnlockedPages(uuid);
        double cost = ConfigManager.BONEMEAL_UNLOCK_BASE * (double) unlocked;
        String costText = String.valueOf((long) cost);
        if (!economy.has(player, cost)) {
            player.sendMessage(ConfigManager.MSG_UNLOCK_FAIL_MONEY.replace("%cost%", costText));
            return;
        }
        // 幂等经济操作：登记 PENDING → 扣 Vault 金币 → 落库(幂等 at-least) → 标记 PAID（崩溃窗口由启动恢复兜底）
        double balanceBefore = economy.getBalance(player);
        String opId = db.beginEconomicOp(uuid, "BONE_UNLOCK",
                "pages=" + (unlocked + 1) + " cost=" + costText,
                cost, balanceBefore, unlocked + 1, -1);
        if (opId == null) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        if (!economy.withdraw(player, cost)) {
            // 扣款失败：DB 尚未写入，直接标记回滚
            db.finishEconomicOp(opId, "ROLLED_BACK");
            player.sendMessage(ConfigManager.MSG_UNLOCK_FAIL_MONEY.replace("%cost%", costText));
            return;
        }
        if (db.setUnlockedPagesAtLeast(uuid, unlocked + 1)) {
            db.finishEconomicOp(opId, "PAID");
            player.sendMessage(ConfigManager.MSG_UNLOCK_SUCCESS.replace("%cost%", costText));
        } else {
            // 扣款成功但落库失败：先尝试退款；退款也失败则保持 PENDING，由启动恢复补写页数
            if (economy.deposit(player, cost)) {
                db.finishEconomicOp(opId, "ROLLED_BACK");
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
            } else {
                player.sendMessage(ConfigManager.MSG_DB_ERROR);
                plugin.getLogger().warning("解锁扣款成功但退款与落库均失败，操作保持 PENDING 等待启动恢复: uuid="
                        + uuid + " opId=" + opId + " cost=" + costText);
            }
        }
        scheduleOpen(() -> openBonemeal(player, 0, fromFarm));
    }

    /** 取走一组物品：发给玩家真实物品并扣总数，格子清空/减量。 */
    private void takeItem(Player player, InventoryClickEvent e, GuiHolder h) {
        ItemStack cur = e.getCurrentItem().clone();
        int qty = cur.getAmount();
        WarehouseResource res = h.getResource();
        // 先扣虚拟库存（成功才发物），防止 DB 失败后物品已发 = 刷物品
        if (!db.addCropStock(h.getUuid(), res.getCropId(), res.getItemType(), -qty)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(cur);
        int accepted = qty - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (accepted <= 0) {
            // 背包全满：退回虚拟库存（退回失败必须记录台账，否则物品凭空消失）
            if (!db.addCropStock(h.getUuid(), res.getCropId(), res.getItemType(), qty)) {
                db.addCompensation(h.getUuid(), res.getItemType(), res.getCropId(), res.getItemType(), qty, "takeItem-full-rollback");
                plugin.getLogger().warning("取物退回库存失败: uuid=" + h.getUuid() + " qty=" + qty);
            }
            player.sendMessage(ConfigManager.MSG_INV_FULL);
            return;
        }
        if (accepted < qty) {
            // 装不下的部分退回虚拟库存
            int back = qty - accepted;
            if (!db.addCropStock(h.getUuid(), res.getCropId(), res.getItemType(), back)) {
                db.addCompensation(h.getUuid(), res.getItemType(), res.getCropId(), res.getItemType(), back, "takeItem-partial-rollback");
                plugin.getLogger().warning("取物部分退回库存失败: uuid=" + h.getUuid() + " qty=" + back);
            }
        }
        if (accepted >= qty) {
            e.setCurrentItem(null);
        } else {
            ItemStack remain = cur.clone();
            remain.setAmount(qty - accepted);
            e.setCurrentItem(remain);
        }
        player.sendMessage(ConfigManager.MSG_TAKE_SUCCESS.replace("%qty%", String.valueOf(accepted)));
    }

    /** 点击填充：扫描本页空格，从后备（总数 − 已展示）按 64/格补入。 */
    private void fillPage(Player player, Inventory inv, GuiHolder h) {
        WarehouseResource res = h.getResource();
        long total = db.getCropStock(h.getUuid(), res.getCropId(), res.getItemType());
        long displayed = 0L;
        for (int local = 0; local < ConfigManager.WAREHOUSE_PAGE_SLOTS; local++) {
            ItemStack item = inv.getItem(INNER_SLOTS[local]);
            if (item != null && !item.getType().isAir()) {
                displayed += item.getAmount();
            }
        }
        long backup = total - displayed;
        if (backup <= 0) {
            player.sendMessage(ConfigManager.MSG_NO_BACKUP);
            return;
        }
        for (int local = 0; local < ConfigManager.WAREHOUSE_PAGE_SLOTS; local++) {
            if (backup <= 0) {
                break;
            }
            int raw = INNER_SLOTS[local];
            ItemStack item = inv.getItem(raw);
            if (item == null || item.getType().isAir()) {
                int put = (int) Math.min(ConfigManager.WAREHOUSE_STACK, backup);
                ItemStack fill = new ItemStack(h.getResource().getMaterial());
                fill.setAmount(put);
                inv.setItem(raw, fill);
                backup -= put;
            }
        }
    }

    // ================= 辅助 =================

    private void scheduleOpen(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * 按 gui.yml 条目配置构建物品；缺失/空值回退默认，支持 %key% 占位符替换（成对传入）。
     *
     * @param key          gui.yml 条目路径（如 "menu.Farmcreate"）
     * @param fallbackMat  默认材质（gui.yml 缺失/占位时使用）
     * @param fallbackName 默认名称
     * @param fallbackLore 默认 Lore
     * @param kv           占位符替换对：%k1%, v1, %k2%, v2, ...
     */
    private ItemStack guiItem(String key, Material fallbackMat, String fallbackName, List<String> fallbackLore, String... kv) {
        GuiItemConfig c = ConfigManager.GUI_ITEMS.get(key);
        Material mat = resolveMaterial(c, fallbackMat, kv);
        String name = (c == null || c.getName() == null || c.getName().isBlank()) ? fallbackName : c.getName();
        List<String> lore = new ArrayList<>(c != null && c.getLore() != null && !c.getLore().isEmpty()
                ? c.getLore() : fallbackLore);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            name = name.replace(kv[i], kv[i + 1]);
            for (int j = 0; j < lore.size(); j++) {
                lore.set(j, lore.get(j).replace(kv[i], kv[i + 1]));
            }
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(name);
            }
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 解析条目材质：gui.yml 的 material 支持固定材质名或 %占位符%（如 %icon%/%item%，
     * 从 kv 中取对应值再 Material.matchMaterial）；空值/非法回退 fallback。
     */
    private Material resolveMaterial(GuiItemConfig c, Material fallback, String... kv) {
        String raw = c == null ? null : c.getRawMaterial();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        raw = raw.trim();
        if (raw.length() > 2 && raw.startsWith("%") && raw.endsWith("%")) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (raw.equalsIgnoreCase(kv[i])) {
                    Material m = Material.matchMaterial(kv[i + 1]);
                    return m != null ? m : fallback;
                }
            }
            return fallback;
        }
        Material m = Material.matchMaterial(raw);
        return m != null ? m : fallback;
    }

    /** 将原始槽位映射回内部 28 格 local 索引，非内部格返回 -1。 */
    private int rawToLocal(int raw) {
        for (int i = 0; i < INNER_SLOTS.length; i++) {
            if (INNER_SLOTS[i] == raw) {
                return i;
            }
        }
        return -1;
    }

    private String farmTitle(int page) {
        return pageTitle(ConfigManager.GUI_FARM_TITLE, page);
    }

    private String cropMenuTitle(int page) {
        return pageTitle(ConfigManager.GUI_CROP_MENU_TITLE, page);
    }

    /** 支持 %page% 占位符：标题已含则替换，否则追加「 · 第 N 页」。 */
    private String pageTitle(String base, int page) {
        return base.contains("%page%")
                ? base.replace("%page%", String.valueOf(page + 1))
                : base + " §8· 第 " + (page + 1) + " 页";
    }

    // ================= 物品构建 =================

    private ItemStack frame() {
        ItemStack item = new ItemStack(ConfigManager.FRAME_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 农田页已解锁格（绿色玻璃，可种植）。 */
    private ItemStack freeTile(boolean isFree) {
        return guiItem("Farm.Free", isFree ? Material.GREEN_STAINED_GLASS : Material.GREEN_STAINED_GLASS,
                "§a免费种植格 - 种植作物",
                List.of("§7点击选择要创建的作物"));
    }

    /** 农田页待解锁批（黄色玻璃，付费购买一批）。 */
    private ItemStack yellowTile() {
        String cost = String.valueOf(ConfigManager.FARM_UNLOCK_PRICE);
        String count = String.valueOf(ConfigManager.FARM_UNLOCK_BATCH);
        return guiItem("Farm.Yellow", Material.YELLOW_STAINED_GLASS,
                "§e花费 $" + cost + " 购买 " + count + " 个种植格",
                List.of("§7点击付费解锁下一批种植格"));
    }

    /** 农田页前置未解锁（红色玻璃，不可种植）。 */
    private ItemStack redTile() {
        return guiItem("Farm.Red", Material.RED_STAINED_GLASS,
                "§c请先解锁前置种植格，才可继续扩建",
                List.of());
    }

    /** 各 GUI 第6行第1格的「返回上一个菜单」羽毛按钮。 */
    private ItemStack backFeather(String key, String fallbackName) {
        return guiItem(key, Material.FEATHER, fallbackName, List.of("§7返回上一个菜单"));
    }

    private ItemStack farmIcon(CropType ct, int level) {
        String farmName = ct == null ? "农田" : ct.getFarmName();
        return guiItem("Farm.Farmplot", ct == null ? Material.WHEAT : ct.getIcon(),
                "§b" + farmName + " §7Lv." + level,
                List.of("§7左键：进入作物生长", "§7右键：进入农田管理"),
                "%farmname%", farmName,
                "%level%", String.valueOf(level),
                "%item%", ct == null ? "WHEAT" : ct.getIcon().name());
    }

    private ItemStack replantItem(CropType ct) {
        Material seedMat = ct == null ? Material.WHEAT_SEEDS : ct.getSeedMaterial();
        String seedName = ct == null ? "种子" : ct.getName() + "种子";
        return guiItem("Farmmanage.Seed", seedMat, "§a点击补种",
                List.of("§7补种该农田缺少的种植格",
                        "§7优先扣除种子仓库，不足扣背包",
                        "§7消耗：" + seedName));
    }

    private ItemStack upgradeItem(CropType ct, int level) {
        String cropId = ct == null ? "wheat" : ct.getId();
        String cropName = ct == null ? "作物" : ct.getName();
        int baseProduct = ct == null ? 1 : ct.getYieldProduct();
        int baseSeed = ct == null ? 1 : ct.getYieldSeed();
        if (level >= ConfigManager.getFarmMaxLevel(cropId)) {
            return guiItem("Farmmanage.Update", Material.HOPPER, "§7农田升级（已满级）",
                    List.of("§7当前 Lv." + level + "（最高）"),
                    "%level%", String.valueOf(level));
        }
        int cost = ConfigManager.getFarmUpgradeCost(cropId, level + 1);
        // 下一级产量以 FarmUpdate 为准（%lore% 占位），未配置回退基础产量；
        // 无种子作物（SeedDrop=0）不显示种子产量
        int[] d2 = ConfigManager.getFarmDrop(cropId, level + 1);
        int lvNextProd = d2 != null ? d2[0] : baseProduct + 1;
        int lvNextSeed = d2 != null ? d2[1] : baseSeed;
        String lvNext = "§7Lv." + (level + 1) + " 产量：" + lvNextProd + " " + cropName
                + (lvNextSeed > 0 ? " + " + lvNextSeed + " 种子" : "");
        String lv2 = "§7Lv.2 产量：" + (baseProduct + 1) + " " + cropName
                + (baseSeed > 0 ? " + " + baseSeed + " 种子" : "");
        String lv3 = "§7Lv.3 产量：" + (baseProduct + 2) + " " + cropName
                + (baseSeed > 0 ? " + " + baseSeed + " 种子" : "");
        return guiItem("Farmmanage.Update", Material.HOPPER, "§a农田升级",
                List.of("§7当前 Lv." + level,
                        "§7升级到 Lv." + (level + 1) + " 需 " + cost + " 金币",
                        lv2, lv3),
                "%level%", String.valueOf(level),
                "%money%", String.valueOf(cost),
                "%lore%", lvNext);
    }

    private ItemStack createEntry(CropType ct) {
        return guiItem("Farmcreate.Wheat", ct.getIcon(), "§b" + ct.getName(),
                List.of(
                        "§7点击创建" + ct.getFarmName(),
                        "§7创建时消耗背包/仓库中的" + ct.getName() + "种子（最多 54 粒）",
                        "§7消耗多少种子，农田里就种植多少格农作物"),
                "%farmname%", ct.getFarmName(),
                "%item%", ct.getIcon().name());
    }

    private ItemStack unlockChest(int unlocked) {
        long cost = ConfigManager.BONEMEAL_UNLOCK_BASE * (long) unlocked;
        return guiItem("Bone.Update", Material.CHEST, "§a升级解锁下一页",
                List.of("§7花费 " + cost + " 金币解锁下一页"),
                "%money%", String.valueOf(cost),
                "%page%", String.valueOf(unlocked + 1));
    }

    /** 骨粉加速开关（拉杆），Lore 随状态显示开/关，并注明仅自动重播生效。 */
    private ItemStack bonemealFastItem(boolean on) {
        return guiItem("Farmmanage.Bone", Material.LEVER, "§a骨粉加速",
                List.of("§7当前状态: " + (on ? "§a" + ConfigManager.BONE_ON : "§c" + ConfigManager.BONE_OFF),
                        "§7开启后自动重播消耗 1 骨粉缩短 20% 成熟时长",
                        "§7仅自动重播生效，手动补种不受影响"),
                "%BoneVariable%", on ? ConfigManager.BONE_ON : ConfigManager.BONE_OFF);
    }

    /** 删除农田按钮（屏障）。 */
    private ItemStack deleteFarmItem() {
        return guiItem("Farmmanage.DeleFram", Material.BARRIER, "§c删除农田",
                List.of("§7点击删除该农田（需二次确认）"));
    }

    private ItemStack growthItem(PlotState p, long now, CropType ct, int level) {
        if (p.stage < 0 || ct == null) {
            return null; // 空槽直接留空
        }
        if (p.stage >= 7) {
            // 成熟产量按农田当前等级展示（与实际收割一致）；无种子作物（SeedDrop=0）不显示种子
            int[] drop = ConfigManager.getFarmDrop(ct.getId(), level);
            int prod = drop != null ? drop[0] : ct.getYieldProduct();
            int seed = drop != null ? drop[1] : ct.getYieldSeed();
            return guiItem("Farmplot.CropMature", ct.getProductMaterial(), "§e已成熟",
                    List.of("§7等待自动收割…",
                            "§7产量：" + ct.getName() + "+" + prod + (seed > 0 ? " · 种子+" + seed : "")),
                    "%item%", ct.getProductMaterial().name());
        }
        // 按作物配置：true 分阶段变化显示（到 show-product-stage 前展示种子图标、之后展示成品图标），false 始终显示成品图标
        Material mat = ct.isShowStageChange()
                ? (p.stage < ct.getShowProductStage() ? ct.getSeedMaterial() : ct.getProductMaterial())
                : ct.getProductMaterial();
        return guiItem("Farmplot.CropGrowing", mat, "§a生长中 Lv." + p.stage,
                List.of("[%bar%]", "阶段: %currentstage% / %maxstage%", "剩余 %time%"),
                "%item%", mat.name(),
                "%stage%", String.valueOf(p.stage),
                "%bar%", growBar(p, now),
                "%currentstage%", String.valueOf(p.stage),
                "%maxstage%", "7",
                "%time%", formatTime(remainSec(p, now)));
    }

    /** 生长进度条（10 格）。 */
    private String growBar(PlotState p, long now) {
        long elapsed = Math.max(0L, now - p.startedAt);
        long duration = Math.max(1L, p.durationSec);
        // 防溢出：elapsed 先钳制到 duration*10（满格阈值），全程 long 运算后再转 int，
        // 避免异常 started_at（如 0/负数）导致 elapsed*10 超 int 上限转为负数、进度显示错误
        long capped = Math.min(elapsed, duration * 10L);
        int filled = (int) Math.min(10L, capped * 10L / duration);
        StringBuilder bar = new StringBuilder("§7[");
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? "§a■" : "§8□");
        }
        bar.append("§7]");
        return bar.toString();
    }

    /** 剩余生长秒数。 */
    private long remainSec(PlotState p, long now) {
        return Math.max(0L, p.durationSec - Math.max(0L, now - p.startedAt));
    }

    private String formatTime(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return h + " " + ConfigManager.TIME_HOURS + " " + m + " " + ConfigManager.TIME_MINUTES;
        }
        if (m > 0) {
            return m + " " + ConfigManager.TIME_MINUTES + " " + s + " " + ConfigManager.TIME_SECONDS;
        }
        return s + " " + ConfigManager.TIME_SECONDS;
    }
}
