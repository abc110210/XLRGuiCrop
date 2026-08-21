package xlingran.com.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xlingran.com.Shan;
import xlingran.com.config.ConfigManager;
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

/**
 * 多类 GUI 的构建与点击分发：
 * <ol>
 *   <li>农田 GUI（分页）：54 格，内部 28 农田位（空位留空）；第6行第3格「上一页」箭（非首页）、
 *       「下一页」箭（第 1 页在第 5 格、第 2 页起在第 7 格，当前页 28 格占满才可翻页）；
 *       农田格左键进生长、右键进管理</li>
 *   <li>二级生长 GUI（54 格）：展示作物生长状态，空槽留空，按作物配置决定是否分阶段显示</li>
 *   <li>农田管理 GUI（3 行）：第2行第2格小麦种子「点击补种」（仅该格响应）</li>
 *   <li>创建农田 GUI（6 行）：从第2行第2格起展示作物，点击创建</li>
 *   <li>农作物仓库 GUI（6 行，多页）：第 1 页小麦/种子仓库入口，第6行第5格「下一页」</li>
 *   <li>骨粉储存器 GUI（多页）：默认解锁第 1 页；第 1 页第6行第5格「升级解锁」、第6行第7格「下一页」；
 *       第 2 页起第6行第3格「上一页」；可放入/取出骨粉</li>
 *   <li>仓库 GUI（单页）：28 展示格 + 第6行第5格箱子「点击填充」，只能取出不能放入</li>
 * </ol>
 *
 * <p>防复制：虚拟展示层——取走即扣总数并清格；未取走的物品随 GUI 关闭销毁、总数不变。
 */
public final class GuiManager implements Listener {

    /** GUI 类型。 */
    public enum GuiType { FARM, GROWTH, FARM_MANAGE, CREATE_CROP, CROP_MENU, BONEMEAL, WAREHOUSE, MENU }

    /** 仓库资源类型。 */
    public enum WarehouseResource {
        WHEAT(Material.WHEAT, "小麦仓库"),
        SEED(Material.WHEAT_SEEDS, "小麦种子仓库");

        private final Material material;
        private final String title;

        WarehouseResource(Material material, String title) {
            this.material = material;
            this.title = title;
        }

        public Material getMaterial() { return material; }

        public String getTitle() { return title; }
    }

    /** 自定义 GUI 持有者，用于识别界面类型与携带上下文。 */
    public static final class GuiHolder implements InventoryHolder {
        private final GuiType type;
        private final UUID uuid;
        private final int page;
        private final int farmSlot;
        private final WarehouseResource resource;
        private Inventory inventory;

        GuiHolder(GuiType type, UUID uuid, int page, int farmSlot, WarehouseResource resource) {
            this.type = type;
            this.uuid = uuid;
            this.page = page;
            this.farmSlot = farmSlot;
            this.resource = resource;
        }

        public GuiType getType() { return type; }

        public UUID getUuid() { return uuid; }

        public int getPage() { return page; }

        public int getFarmSlot() { return farmSlot; }

        public WarehouseResource getResource() { return resource; }

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

    /** 打开农田 GUI 指定页（第 1 页 = 0）。 */
    public void openFarm(Player player, int page) {
        if (page < 0) {
            page = 0;
        }
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.FARM, uuid, page, -1, null);
        Inventory inv = Bukkit.createInventory(h, 54, farmTitle(page));
        h.setInventory(inv);
        renderFarm(inv, h);
        player.openInventory(inv);
    }

    /** 打开二级生长 GUI（farmSlot 为全局槽位索引，54 格展示作物生长状态）。 */
    public void openGrowth(Player player, int farmSlot) {
        UUID uuid = player.getUniqueId();
        String cropId = db.getFarmSlotCropType(uuid, farmSlot);
        if (cropId == null || cropManager == null) {
            player.sendMessage("§c该农田不存在或系统未就绪。");
            return;
        }
        CropType ct = CropRegistry.get(cropId);
        GuiHolder h = new GuiHolder(GuiType.GROWTH, uuid, 0, farmSlot, null);
        Inventory inv = Bukkit.createInventory(h, 54, ct == null ? ConfigManager.GUI_GROWTH_TITLE : ct.getName());
        h.setInventory(inv);
        renderGrowth(inv, h);
        player.openInventory(inv);
    }

    /** 打开农田管理 GUI（farmSlot 为全局槽位索引）。 */
    public void openFarmManage(Player player, int farmSlot) {
        UUID uuid = player.getUniqueId();
        if (db.getFarmSlotCropType(uuid, farmSlot) == null || cropManager == null) {
            player.sendMessage("§c该农田不存在或系统未就绪。");
            return;
        }
        GuiHolder h = new GuiHolder(GuiType.FARM_MANAGE, uuid, 0, farmSlot, null);
        Inventory inv = Bukkit.createInventory(h, 27, ConfigManager.GUI_FARM_MANAGE_TITLE);
        h.setInventory(inv);
        renderFarmManage(inv, h);
        player.openInventory(inv);
    }

    /** 打开主菜单 GUI。 */
    public void openMenu(Player player) {
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.MENU, uuid, 0, -1, null);
        Inventory inv = Bukkit.createInventory(h, 27, ConfigManager.GUI_MENU_TITLE);
        h.setInventory(inv);
        renderMenu(inv);
        player.openInventory(inv);
    }

    /** 打开创建农田 GUI。 */
    public void openCreateCrop(Player player) {
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.CREATE_CROP, uuid, 0, -1, null);
        Inventory inv = Bukkit.createInventory(h, 54, ConfigManager.GUI_CREATE_CROP_TITLE);
        h.setInventory(inv);
        renderCreateCrop(inv);
        player.openInventory(inv);
    }

    /** 打开农作物仓库 GUI（多页）。 */
    public void openCropMenu(Player player, int page) {
        if (page < 0) {
            page = 0;
        }
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.CROP_MENU, uuid, page, -1, null);
        Inventory inv = Bukkit.createInventory(h, 54, cropMenuTitle(page));
        h.setInventory(inv);
        renderCropMenu(inv, h);
        player.openInventory(inv);
    }

    /** 打开骨粉储存器 GUI（多页，页数受解锁限制）。 */
    public void openBonemeal(Player player, int page) {
        UUID uuid = player.getUniqueId();
        int unlocked = db.getUnlockedPages(uuid);
        if (page < 0) {
            page = 0;
        }
        if (page >= unlocked) {
            page = unlocked - 1;
        }
        GuiHolder h = new GuiHolder(GuiType.BONEMEAL, uuid, page, -1, null);
        Inventory inv = Bukkit.createInventory(h, 54, ConfigManager.GUI_BONEMEAL_TITLE + " §8· 第 " + (page + 1) + " 页");
        h.setInventory(inv);
        renderBonemeal(inv, h);
        player.openInventory(inv);
    }

    /** 打开仓库 GUI（单页）。 */
    public void openWarehouse(Player player, WarehouseResource resource) {
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.WAREHOUSE, uuid, 0, -1, resource);
        Inventory inv = Bukkit.createInventory(h, 54, resource.getTitle());
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
        Map<Integer, String> slots = db.getFarmSlots(h.getUuid());
        for (int local = 0; local < ConfigManager.FARM_PAGE_SLOTS; local++) {
            int raw = INNER_SLOTS[local];
            int globalIndex = h.getPage() * ConfigManager.FARM_PAGE_SLOTS + local;
            String cropId = slots.get(globalIndex);
            contents[raw] = cropId != null
                    ? farmIcon(CropRegistry.get(cropId), db.getFarmLevel(h.getUuid(), globalIndex))
                    : null;
        }
        contents[ConfigManager.FARM_PREV_SLOT] = h.getPage() > 0 ? prevArrow() : frame();
        // 第 1 页下一页在第 5 格，第 2 页起在第 7 格
        contents[ConfigManager.farmNextSlot(h.getPage())] = nextArrow();
        // 第6行第8格：骨粉储存器入口
        contents[ConfigManager.FARM_BONEMEAL_SLOT] = bonemealEntry();
        inv.setContents(contents);
    }

    private void renderFarmManage(Inventory inv, GuiHolder h) {
        ItemStack[] contents = new ItemStack[27];
        Arrays.fill(contents, frame());
        contents[ConfigManager.FARM_MANAGE_REPLANT_SLOT] = replantItem();
        contents[ConfigManager.FARM_MANAGE_UPGRADE_SLOT] = upgradeItem(db.getFarmLevel(h.getUuid(), h.getFarmSlot()));
        inv.setContents(contents);
    }

    private void renderMenu(Inventory inv) {
        ItemStack[] contents = new ItemStack[27];
        Arrays.fill(contents, frame());
        contents[ConfigManager.MENU_CREATE_CROP_SLOT] = menuEntry(Material.WHEAT, "§6创建农田",
                List.of("§7点击创建新的农田"));
        contents[ConfigManager.MENU_FARM_SLOT] = menuEntry(Material.GRASS_BLOCK, "§6农田",
                List.of("§7点击进入自己的农田"));
        contents[ConfigManager.MENU_BONEMEAL_SLOT] = menuEntry(Material.BONE_MEAL, "§6骨粉储存",
                List.of("§7点击进入骨粉储存器"));
        contents[ConfigManager.MENU_CROP_MENU_SLOT] = menuEntry(Material.CHEST, "§6农作物仓库",
                List.of("§7点击进入农作物仓库"));
        inv.setContents(contents);
    }

    private void renderCreateCrop(Inventory inv) {
        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        int slot = ConfigManager.CREATE_CROP_START_SLOT;
        for (CropType ct : CropRegistry.all().values()) {
            if (slot >= 54) {
                break;
            }
            contents[slot] = createEntry(ct);
            slot++;
        }
        inv.setContents(contents);
    }

    private void renderCropMenu(Inventory inv, GuiHolder h) {
        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        if (h.getPage() == 0) {
            contents[ConfigManager.CROP_MENU_WHEAT_SLOT] = menuEntry(Material.WHEAT, "§6小麦仓库",
                    List.of("§7点击查看小麦库存"));
            contents[ConfigManager.CROP_MENU_SEED_SLOT] = menuEntry(Material.WHEAT_SEEDS, "§6小麦种子仓库",
                    List.of("§7点击查看小麦种子库存"));
        }
        contents[ConfigManager.CROP_MENU_PREV_SLOT] = h.getPage() > 0 ? prevArrow() : frame();
        contents[ConfigManager.CROP_MENU_NEXT_SLOT] = nextArrow();
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
            contents[ConfigManager.BONEMEAL_PREV_SLOT] = prevArrow();
        }
        if (h.getPage() == 0) {
            contents[ConfigManager.BONEMEAL_UNLOCK_SLOT] = unlockChest(db.getUnlockedPages(h.getUuid()));
        }
        contents[ConfigManager.BONEMEAL_NEXT_SLOT] = nextArrow();
        inv.setContents(contents);
    }

    private void renderWarehouse(Inventory inv, GuiHolder h) {
        WarehouseResource res = h.getResource();
        long total = res == WarehouseResource.WHEAT ? db.getWheat(h.getUuid()) : db.getSeed(h.getUuid());

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
        contents[ConfigManager.WAREHOUSE_FILL_SLOT] = fillChest();
        inv.setContents(contents);
    }

    private void renderGrowth(Inventory inv, GuiHolder h) {
        if (cropManager == null) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(h.getUuid(), h.getFarmSlot()));
        List<PlotState> plots = cropManager.getPlots(h.getUuid(), h.getFarmSlot());
        ItemStack[] contents = new ItemStack[54];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = i < plots.size() ? growthItem(plots.get(i), now, ct) : null;
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
            case CREATE_CROP -> handleCreateCropClick(player, e);
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

    private void handleFarmClick(Player player, InventoryClickEvent e, GuiHolder h) {
        int raw = e.getSlot();
        UUID uuid = h.getUuid();
        if (raw == ConfigManager.farmNextSlot(h.getPage())) {
            if (db.isFarmPageFull(uuid, h.getPage())) {
                scheduleOpen(() -> openFarm(player, h.getPage() + 1));
            } else {
                player.sendMessage(ConfigManager.MSG_PAGE_LOCKED);
            }
            return;
        }
        if (raw == ConfigManager.FARM_PREV_SLOT && h.getPage() > 0) {
            scheduleOpen(() -> openFarm(player, h.getPage() - 1));
            return;
        }
        if (raw == ConfigManager.FARM_BONEMEAL_SLOT) {
            scheduleOpen(() -> openBonemeal(player, 0));
            return;
        }
        int local = rawToLocal(raw);
        if (local < 0) {
            return;
        }
        int globalIndex = h.getPage() * ConfigManager.FARM_PAGE_SLOTS + local;
        if (db.hasFarmSlot(uuid, globalIndex)) {
            // 左键进入作物生长界面，右键进入农田管理
            if (e.isRightClick()) {
                scheduleOpen(() -> openFarmManage(player, globalIndex));
            } else {
                scheduleOpen(() -> openGrowth(player, globalIndex));
            }
        }
    }

    /** 农田管理：第2行第2格补种、第2行第4格农田升级，其余不响应。 */
    private void handleFarmManageClick(Player player, InventoryClickEvent e, GuiHolder h) {
        if (cropManager == null) {
            return;
        }
        int raw = e.getSlot();
        if (raw == ConfigManager.FARM_MANAGE_REPLANT_SLOT) {
            int replanted = cropManager.replant(player, h.getUuid(), h.getFarmSlot());
            if (replanted > 0) {
                player.sendMessage(ConfigManager.MSG_REPLANT_DONE
                        .replace("%count%", String.valueOf(replanted))
                        .replace("%seed%", String.valueOf(replanted * ConfigManager.REPLANT_COST_SEED)));
            } else if (replanted == 0) {
                player.sendMessage(ConfigManager.MSG_REPLANT_EMPTY);
            } else {
                player.sendMessage(ConfigManager.MSG_NO_SEED);
            }
        } else if (raw == ConfigManager.FARM_MANAGE_UPGRADE_SLOT) {
            handleFarmUpgrade(player, h);
        }
    }

    /** 主菜单点击分发。 */
    private void handleMenuClick(Player player, InventoryClickEvent e) {
        switch (e.getSlot()) {
            case ConfigManager.MENU_CREATE_CROP_SLOT -> scheduleOpen(() -> openCreateCrop(player));
            case ConfigManager.MENU_FARM_SLOT -> scheduleOpen(() -> openFarm(player, 0));
            case ConfigManager.MENU_BONEMEAL_SLOT -> scheduleOpen(() -> openBonemeal(player, 0));
            case ConfigManager.MENU_CROP_MENU_SLOT -> scheduleOpen(() -> openCropMenu(player, 0));
            default -> { /* 黑玻璃等不响应 */ }
        }
    }

    /** 农田升级：Lv1→2（1000 金币）、Lv2→3（2000 金币），满级 Lv3 封顶。 */
    private void handleFarmUpgrade(Player player, GuiHolder h) {
        UUID uuid = h.getUuid();
        int level = db.getFarmLevel(uuid, h.getFarmSlot());
        if (level >= ConfigManager.FARM_MAX_LEVEL) {
            player.sendMessage(ConfigManager.MSG_FARM_MAX_LEVEL);
            return;
        }
        if (economy == null || !economy.isEnabled()) {
            player.sendMessage(ConfigManager.MSG_NO_ECONOMY);
            return;
        }
        double cost = level == 1 ? ConfigManager.FARM_UPGRADE_COST_2 : ConfigManager.FARM_UPGRADE_COST_3;
        String costText = String.valueOf((long) cost);
        if (!economy.has(player, cost)) {
            player.sendMessage(ConfigManager.MSG_FARM_UPGRADE_NO_MONEY.replace("%cost%", costText));
            return;
        }
        // 先写 DB 再扣钱：DB 失败不扣钱；扣钱失败回滚等级，避免「钱扣了等级没升」
        if (!db.setFarmLevel(uuid, h.getFarmSlot(), level + 1)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        if (!economy.withdraw(player, cost)) {
            db.setFarmLevel(uuid, h.getFarmSlot(), level);
            player.sendMessage(ConfigManager.MSG_FARM_UPGRADE_NO_MONEY.replace("%cost%", costText));
            return;
        }
        player.sendMessage(ConfigManager.MSG_FARM_UPGRADED
                .replace("%level%", String.valueOf(level + 1))
                .replace("%cost%", costText));
        scheduleOpen(() -> openFarmManage(player, h.getFarmSlot()));
    }

    /** 创建农田 GUI：点击作物条目创建对应农田。 */
    private void handleCreateCropClick(Player player, InventoryClickEvent e) {
        int local = rawToLocal(e.getSlot());
        if (local < 0 || cropManager == null) {
            return;
        }
        List<CropType> crops = new ArrayList<>(CropRegistry.all().values());
        if (local >= crops.size()) {
            return;
        }
        createCrop(player, crops.get(local));
    }

    private void handleCropMenuClick(Player player, InventoryClickEvent e, GuiHolder h) {
        int raw = e.getSlot();
        if (raw == ConfigManager.CROP_MENU_NEXT_SLOT) {
            scheduleOpen(() -> openCropMenu(player, h.getPage() + 1));
            return;
        }
        if (raw == ConfigManager.CROP_MENU_PREV_SLOT && h.getPage() > 0) {
            scheduleOpen(() -> openCropMenu(player, h.getPage() - 1));
            return;
        }
        if (h.getPage() != 0) {
            return;
        }
        if (raw == ConfigManager.CROP_MENU_WHEAT_SLOT) {
            scheduleOpen(() -> openWarehouse(player, WarehouseResource.WHEAT));
        } else if (raw == ConfigManager.CROP_MENU_SEED_SLOT) {
            scheduleOpen(() -> openWarehouse(player, WarehouseResource.SEED));
        }
    }

    private void handleBonemealClick(Player player, InventoryClickEvent e, GuiHolder h) {
        UUID uuid = h.getUuid();
        int raw = e.getSlot();
        if (raw == ConfigManager.BONEMEAL_NEXT_SLOT) {
            int unlocked = db.getUnlockedPages(uuid);
            if (h.getPage() + 1 < unlocked) {
                scheduleOpen(() -> openBonemeal(player, h.getPage() + 1));
            } else {
                player.sendMessage(ConfigManager.MSG_NEXT_PAGE_LOCKED);
            }
            return;
        }
        if (raw == ConfigManager.BONEMEAL_PREV_SLOT && h.getPage() > 0) {
            scheduleOpen(() -> openBonemeal(player, h.getPage() - 1));
            return;
        }
        if (raw == ConfigManager.BONEMEAL_UNLOCK_SLOT && h.getPage() == 0) {
            handleUnlock(player, uuid);
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

    /** 创建农田：扣种子（背包优先→仓库）并种植对应格数，成功后跳转到对应农田页。 */
    private void createCrop(Player player, CropType ct) {
        UUID uuid = player.getUniqueId();
        int globalIndex = cropManager.createFarm(player, ct);
        if (globalIndex < 0) {
            player.sendMessage(ConfigManager.MSG_NO_SEED);
            return;
        }
        int planted = CropManager.PLOT_COUNT - cropManager.countEmptyPlots(uuid, globalIndex);
        int page = globalIndex / ConfigManager.FARM_PAGE_SLOTS + 1;
        int slot = globalIndex % ConfigManager.FARM_PAGE_SLOTS + 1;
        player.sendMessage(ConfigManager.MSG_CROP_CREATED
                .replace("%page%", String.valueOf(page))
                .replace("%slot%", String.valueOf(slot))
                .replace("%replant%", String.valueOf(planted)));
        scheduleOpen(() -> openFarm(player, globalIndex / ConfigManager.FARM_PAGE_SLOTS));
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
            scheduleOpen(() -> openBonemeal(player, page));
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
            // 背包全满：退回虚拟库存
            db.addBonemeal(h.getUuid(), qty);
            player.sendMessage(ConfigManager.MSG_INV_FULL);
            return;
        }
        if (accepted < qty) {
            // 装不下的部分退回虚拟库存
            db.addBonemeal(h.getUuid(), qty - accepted);
        }
        if (accepted >= qty) {
            e.setCurrentItem(null);
        } else {
            ItemStack remain = new ItemStack(Material.BONE_MEAL, qty - accepted);
            e.setCurrentItem(remain);
        }
        player.sendMessage(ConfigManager.MSG_BONEMEAL_TAKE.replace("%qty%", String.valueOf(accepted)));
    }

    /** 骨粉升级：花费金币解锁下一页。 */
    private void handleUnlock(Player player, UUID uuid) {
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
        // 先写 DB 再扣钱：DB 失败不扣钱；扣钱失败回滚解锁页数，避免「钱扣了页数没升」
        if (!db.setUnlockedPages(uuid, unlocked + 1)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        if (!economy.withdraw(player, cost)) {
            db.setUnlockedPages(uuid, unlocked);
            player.sendMessage(ConfigManager.MSG_UNLOCK_FAIL_MONEY.replace("%cost%", costText));
            return;
        }
        player.sendMessage(ConfigManager.MSG_UNLOCK_SUCCESS.replace("%cost%", costText));
        scheduleOpen(() -> openBonemeal(player, 0));
    }

    /** 取走一组物品：发给玩家真实物品并扣总数，格子清空/减量。 */
    private void takeItem(Player player, InventoryClickEvent e, GuiHolder h) {
        ItemStack cur = e.getCurrentItem().clone();
        int qty = cur.getAmount();
        boolean isWheat = h.getResource() == WarehouseResource.WHEAT;
        // 先扣虚拟库存（成功才发物），防止 DB 失败后物品已发 = 刷物品
        if (isWheat ? !db.addWheat(h.getUuid(), -qty) : !db.addSeed(h.getUuid(), -qty)) {
            player.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(cur);
        int accepted = qty - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (accepted <= 0) {
            // 背包全满：退回虚拟库存
            if (isWheat) {
                db.addWheat(h.getUuid(), qty);
            } else {
                db.addSeed(h.getUuid(), qty);
            }
            player.sendMessage(ConfigManager.MSG_INV_FULL);
            return;
        }
        if (accepted < qty) {
            // 装不下的部分退回虚拟库存
            if (isWheat) {
                db.addWheat(h.getUuid(), qty - accepted);
            } else {
                db.addSeed(h.getUuid(), qty - accepted);
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
        long total = h.getResource() == WarehouseResource.WHEAT
                ? db.getWheat(h.getUuid()) : db.getSeed(h.getUuid());
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
        return ConfigManager.GUI_FARM_TITLE + " §8· 第 " + (page + 1) + " 页";
    }

    private String cropMenuTitle(int page) {
        return ConfigManager.GUI_CROP_MENU_TITLE + " §8· 第 " + (page + 1) + " 页";
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

    private ItemStack farmIcon(CropType ct, int level) {
        ItemStack item = new ItemStack(ct == null ? Material.WHEAT : ct.getIcon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b" + (ct == null ? "农田" : ct.getName()) + " §7Lv." + level);
            meta.setLore(List.of("§7左键：进入作物生长", "§7右键：进入农田管理"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack prevArrow() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a上一页");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack nextArrow() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a下一页");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack bonemealEntry() {
        ItemStack item = new ItemStack(Material.BONE_MEAL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a骨粉储存器");
            meta.setLore(List.of("§7点击打开骨粉储存器"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack replantItem() {
        ItemStack item = new ItemStack(Material.WHEAT_SEEDS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a点击补种");
            meta.setLore(List.of("§7补种该农田缺少的种植格", "§7优先扣除种子仓库，不足扣背包"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack upgradeItem(int level) {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (level >= ConfigManager.FARM_MAX_LEVEL) {
                meta.setDisplayName("§7农田升级（已满级）");
                meta.setLore(List.of("§7当前 Lv." + level + "（最高）"));
            } else {
                int cost = level == 1 ? ConfigManager.FARM_UPGRADE_COST_2 : ConfigManager.FARM_UPGRADE_COST_3;
                meta.setDisplayName("§a农田升级");
                meta.setLore(List.of(
                        "§7当前 Lv." + level,
                        "§7升级到 Lv." + (level + 1) + " 需 " + cost + " 金币",
                        "§7Lv.2 产量：2 小麦 + 2 种子",
                        "§7Lv.3 产量：3 小麦 + 2 种子"));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createEntry(CropType ct) {
        ItemStack item = new ItemStack(ct.getIcon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b" + ct.getName());
            meta.setLore(List.of("§7点击创建该作物农田", "§7消耗 1 粒种子并自动补种"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack unlockChest(int unlocked) {
        long cost = ConfigManager.BONEMEAL_UNLOCK_BASE * (long) unlocked;
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a升级解锁下一页");
            meta.setLore(List.of("§7花费 " + cost + " 金币解锁下一页"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack fillChest() {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a点击填充");
            meta.setLore(List.of("§7从后备库存补充本页空格"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack menuEntry(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack growthItem(PlotState p, long now, CropType ct) {
        if (p.stage < 0) {
            return null; // 空槽直接留空
        }
        if (p.stage >= 7) {
            ItemStack item = new ItemStack(ct == null ? Material.WHEAT : ct.getIcon());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e已成熟");
                meta.setLore(List.of("§7等待自动收割…",
                        "§7产量：小麦+" + ConfigManager.YIELD_WHEAT + " · 种子+" + ConfigManager.YIELD_SEED));
                item.setItemMeta(meta);
            }
            return item;
        }
        // 按作物配置：true 分阶段变化显示，false 始终显示成品图标（如胡萝卜）
        Material mat = (ct != null && !ct.isShowStageChange())
                ? ct.getIcon()
                : (p.stage < 3 ? Material.WHEAT_SEEDS : Material.WHEAT);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a生长中 Lv." + p.stage);
            meta.setLore(progressLore(p, now));
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> progressLore(PlotState p, long now) {
        long elapsed = Math.max(0L, now - p.startedAt);
        long remain = Math.max(0L, p.durationSec - elapsed);
        int filled = Math.min(10, (int) (elapsed * 10 / Math.max(1, p.durationSec)));
        StringBuilder bar = new StringBuilder("§7[");
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? "§a■" : "§8□");
        }
        bar.append("§7]");
        return List.of(
                bar.toString(),
                "§7阶段 " + p.stage + " / 7",
                "§7剩余 " + formatTime(remain));
    }

    private String formatTime(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return h + " 小时 " + m + " 分";
        }
        if (m > 0) {
            return m + " 分 " + s + " 秒";
        }
        return s + " 秒";
    }
}
