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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 四类 GUI 的构建与点击分发：
 * <ol>
 *   <li>农田 GUI（分页）：54 格，内部 28 农田位；第6行第5格固定箱子「下一页」（当前页 28 格占满才可翻页），
 *       第6行第3格「上一页」（仅非首页显示）</li>
 *   <li>二级生长 GUI：54 格全自动种植槽（纯展示，自动收割）</li>
 *   <li>农作物仓库 GUI（45 格）：小麦/种子仓库入口</li>
 *   <li>多页仓库 GUI：28 展示格 + 第6行 上一页/填充/下一页</li>
 * </ol>
 *
 * <p>防复制：仓库为虚拟展示层——取走即扣总数并清格；未取走的物品随 GUI 关闭销毁、总数不变，
 * 天然实现「关闭/翻页自动回收至后备」。
 */
public final class GuiManager implements Listener {

    /** GUI 类型。 */
    public enum GuiType { FARM, GROWTH, CROP_MENU, WAREHOUSE }

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

    /** 内部 28 农田/展示格（第2~5行第2~8列）的 local 索引 -> 原始槽位。 */
    private static final int[] INNER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Shan plugin;
    private final DatabaseManager db;
    private CropManager cropManager;

    public GuiManager(Shan plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
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

    /** 打开二级生长 GUI（farmSlot 为全局槽位索引）。 */
    public void openGrowth(Player player, int farmSlot) {
        UUID uuid = player.getUniqueId();
        String cropId = db.getFarmSlotCropType(uuid, farmSlot);
        if (cropId == null || cropManager == null) {
            player.sendMessage("§c该农田不存在或系统未就绪。");
            return;
        }
        GuiHolder h = new GuiHolder(GuiType.GROWTH, uuid, 0, farmSlot, null);
        Inventory inv = Bukkit.createInventory(h, 54, ConfigManager.GUI_GROWTH_TITLE);
        h.setInventory(inv);
        renderGrowth(inv, h);
        player.openInventory(inv);
    }

    /** 打开农作物仓库 GUI（45 格）。 */
    public void openCropMenu(Player player) {
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.CROP_MENU, uuid, 0, -1, null);
        Inventory inv = Bukkit.createInventory(h, 45, ConfigManager.GUI_CROP_MENU_TITLE);
        h.setInventory(inv);
        renderCropMenu(inv);
        player.openInventory(inv);
    }

    /** 打开多页仓库 GUI 指定页（resource 指定小麦/种子）。 */
    public void openWarehouse(Player player, WarehouseResource resource, int page) {
        if (page < 0) {
            page = 0;
        }
        UUID uuid = player.getUniqueId();
        GuiHolder h = new GuiHolder(GuiType.WAREHOUSE, uuid, page, -1, resource);
        Inventory inv = Bukkit.createInventory(h, 54, warehouseTitle(resource, page));
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
            case WAREHOUSE -> renderWarehouse(inv, h);
            default -> { /* CROP_MENU 无动态数据 */ }
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
            contents[raw] = cropId != null ? farmIcon(CropRegistry.get(cropId)) : emptyFarmItem();
        }
        contents[ConfigManager.FARM_PREV_SLOT] = h.getPage() > 0 ? prevArrow() : frame();
        contents[ConfigManager.FARM_NEXT_SLOT] = nextChest();
        inv.setContents(contents);
    }

    private void renderGrowth(Inventory inv, GuiHolder h) {
        if (cropManager == null) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        List<PlotState> plots = cropManager.getPlots(h.getUuid(), h.getFarmSlot());
        ItemStack[] contents = new ItemStack[54];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = plots.isEmpty() ? frame()
                    : growthItem(plots.get(Math.min(i, plots.size() - 1)), now);
        }
        inv.setContents(contents);
    }

    private void renderCropMenu(Inventory inv) {
        ItemStack[] contents = new ItemStack[45];
        Arrays.fill(contents, frame());
        contents[ConfigManager.CROP_MENU_WHEAT_SLOT] = menuEntry(Material.WHEAT, "§6小麦仓库",
                List.of("§7点击查看小麦库存"));
        contents[ConfigManager.CROP_MENU_SEED_SLOT] = menuEntry(Material.WHEAT_SEEDS, "§6小麦种子仓库",
                List.of("§7点击查看小麦种子库存"));
        inv.setContents(contents);
    }

    private void renderWarehouse(Inventory inv, GuiHolder h) {
        WarehouseResource res = h.getResource();
        long total = res == WarehouseResource.WHEAT ? db.getWheat(h.getUuid()) : db.getSeed(h.getUuid());
        long start = (long) h.getPage() * ConfigManager.WAREHOUSE_PAGE_SLOTS * ConfigManager.WAREHOUSE_STACK;
        long remaining = Math.max(0L, total - start);

        ItemStack[] contents = new ItemStack[54];
        Arrays.fill(contents, frame());
        for (int local = 0; local < ConfigManager.WAREHOUSE_PAGE_SLOTS; local++) {
            int raw = INNER_SLOTS[local];
            if (remaining <= 0) {
                contents[raw] = null;
                continue;
            }
            int put = (int) Math.min(ConfigManager.WAREHOUSE_STACK, remaining);
            ItemStack item = new ItemStack(res.getMaterial());
            item.setAmount(put);
            contents[raw] = item;
            remaining -= put;
        }
        contents[ConfigManager.WAREHOUSE_PREV_SLOT] = h.getPage() > 0 ? prevArrow() : frame();
        contents[ConfigManager.WAREHOUSE_FILL_SLOT] = fillChest();
        contents[ConfigManager.WAREHOUSE_NEXT_SLOT] = nextArrow();
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
        // 只处理点击自定义 GUI 本体（top），玩家背包部分直接拦截（防放回复制）
        if (!e.getClickedInventory().equals(top)) {
            return;
        }
        switch (h.getType()) {
            case FARM -> handleFarmClick(player, e, h);
            case GROWTH -> { /* 纯展示，无交互 */ }
            case CROP_MENU -> handleCropMenuClick(player, e);
            case WAREHOUSE -> handleWarehouseClick(player, e, h);
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
        if (raw == ConfigManager.FARM_NEXT_SLOT) {
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
        int local = rawToLocal(raw);
        if (local < 0) {
            return;
        }
        int globalIndex = h.getPage() * ConfigManager.FARM_PAGE_SLOTS + local;
        if (db.hasFarmSlot(uuid, globalIndex)) {
            scheduleOpen(() -> openGrowth(player, globalIndex));
        } else {
            player.sendMessage(ConfigManager.MSG_EMPTY_FARM_CLICK);
        }
    }

    private void handleCropMenuClick(Player player, InventoryClickEvent e) {
        int raw = e.getSlot();
        if (raw == ConfigManager.CROP_MENU_WHEAT_SLOT) {
            scheduleOpen(() -> openWarehouse(player, WarehouseResource.WHEAT, 0));
        } else if (raw == ConfigManager.CROP_MENU_SEED_SLOT) {
            scheduleOpen(() -> openWarehouse(player, WarehouseResource.SEED, 0));
        }
    }

    private void handleWarehouseClick(Player player, InventoryClickEvent e, GuiHolder h) {
        int raw = e.getSlot();
        if (raw == ConfigManager.WAREHOUSE_PREV_SLOT && h.getPage() > 0) {
            scheduleOpen(() -> openWarehouse(player, h.getResource(), h.getPage() - 1));
            return;
        }
        if (raw == ConfigManager.WAREHOUSE_NEXT_SLOT) {
            scheduleOpen(() -> openWarehouse(player, h.getResource(), h.getPage() + 1));
            return;
        }
        if (raw == ConfigManager.WAREHOUSE_FILL_SLOT) {
            fillPage(player, e.getInventory(), h);
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
        takeItem(player, e, h);
    }

    // ================= 仓库操作 =================

    /** 取走一组物品：发给玩家真实物品并扣总数，格子清空/减量。 */
    private void takeItem(Player player, InventoryClickEvent e, GuiHolder h) {
        ItemStack cur = e.getCurrentItem().clone();
        int qty = cur.getAmount();
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(cur);
        int accepted = qty - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (accepted <= 0) {
            player.sendMessage(ConfigManager.MSG_INV_FULL);
            return;
        }
        if (h.getResource() == WarehouseResource.WHEAT) {
            db.addWheat(h.getUuid(), -accepted);
        } else {
            db.addSeed(h.getUuid(), -accepted);
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

    private String warehouseTitle(WarehouseResource res, int page) {
        return res.getTitle() + " §8· 第 " + (page + 1) + " 页";
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

    private ItemStack emptyFarmItem() {
        ItemStack item = new ItemStack(ConfigManager.EMPTY_FARM_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7空农田位");
            meta.setLore(List.of("§7使用 §b/xlr crop wheat §7创建农田"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack farmIcon(CropType ct) {
        ItemStack item = new ItemStack(ct == null ? Material.WHEAT : ct.getIcon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b" + (ct == null ? "农田" : ct.getName()));
            meta.setLore(List.of("§7点击进入生长管理"));
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

    private ItemStack nextChest() {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a下一页");
            meta.setLore(List.of("§7点击进入下一页", "§7（需当前页 28 格农田全部创建）"));
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

    private ItemStack growthItem(PlotState p, long now) {
        if (p.stage >= 7) {
            ItemStack item = new ItemStack(Material.WHEAT);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e已成熟");
                meta.setLore(List.of("§7等待自动收割…", "§7产量：小麦+1 · 种子+1"));
                item.setItemMeta(meta);
            }
            return item;
        }
        Material mat = p.stage < 3 ? Material.WHEAT_SEEDS : Material.WHEAT;
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
