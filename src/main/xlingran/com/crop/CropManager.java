package xlingran.com.crop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xlingran.com.Shan;
import xlingran.com.config.ConfigManager;
import xlingran.com.db.DatabaseManager;
import xlingran.com.gui.GuiManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 生长状态机、种子消耗与自动收割管理。
 *
 * <p>状态约定：stage 0-7 生长中（7=成熟）；stage=-1 表示空槽（未种植，等待补种）。
 * 创建农田/补种/收割后自动重播均消耗对应作物种子（优先该作物种子仓库，不足扣背包对应材料）。
 */
public final class CropManager {

    /** 二级生长种植槽总数（54 格）。 */
    public static final int PLOT_COUNT = 54;
    /** 空槽 stage 标记。 */
    public static final int STAGE_EMPTY = -1;

    private final Shan plugin;
    private final DatabaseManager db;
    private final GuiManager gui;
    private BukkitTask task;

    public CropManager(Shan plugin, DatabaseManager db, GuiManager gui) {
        this.plugin = plugin;
        this.db = db;
        this.gui = gui;
    }

    public void start() {
        long ticks = ConfigManager.TICK_INTERVAL_SEC * 20L;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                tickSettle();
            }
        }.runTaskTimer(plugin, ticks, ticks);
        plugin.getLogger().info("CropManager started (interval " + ConfigManager.TICK_INTERVAL_SEC + "s).");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 按真实时间差懒计算当前生长阶段（0-7，7=成熟）；空槽恒为 STAGE_EMPTY。 */
    public int calcStage(PlotState plot, long nowSec) {
        if (plot.stage == STAGE_EMPTY) {
            return STAGE_EMPTY;
        }
        long elapsed = nowSec - plot.startedAt;
        if (elapsed <= 0) {
            return 0;
        }
        if (plot.durationSec <= 0) {
            return 7;
        }
        // 防极端数据溢出：elapsed 钳制到 duration*8 以内（超出即已成熟），避免乘 8 后异常
        long maxElapsed = Math.max(1L, (long) plot.durationSec * 8);
        long safeElapsed = Math.min(elapsed, maxElapsed);
        int stage = (int) (safeElapsed * 8 / Math.max(1, plot.durationSec));
        return Math.min(7, Math.max(0, stage));
    }

    // ================= 种子消耗（多素材自动按序） =================

    /** 单次消耗中「一种素材」的明细：来自仓库/背包各多少（便于落库失败时精确回滚）。 */
    public static final class SeedPart {
        public final Material material;
        public int warehouse;
        public int backpack;

        SeedPart(Material material, int warehouse, int backpack) {
            this.material = material;
            this.warehouse = warehouse;
            this.backpack = backpack;
        }
    }

    /** 一次种子消耗的聚合结果：总消耗 + 按素材明细。 */
    public static final class SeedConsumption {
        public final List<SeedPart> parts;
        public final int total;

        SeedConsumption(List<SeedPart> parts, int total) {
            this.parts = parts;
            this.total = total;
        }
    }

    /**
     * 消耗某作物的种子素材（可为多种，按 {@code ct.getSeedMaterials()} 顺序自动消耗）。
     *
     * <p>对每种素材：按 {@code warehouseFirst} 决定先扣仓库还是先扣背包，直到凑够 {@code need}
     * 或素材耗尽。返回总消耗与按素材明细（回滚用）。
     *
     * @param warehouseFirst true = 先扣仓库、不足扣背包（补种/自动重播）；
     *                       false = 先扣背包、不足扣仓库（创建农田）
     */
    public SeedConsumption consumeSeeds(Player player, UUID uuid, CropType ct, int need, boolean warehouseFirst) {
        List<SeedPart> parts = new ArrayList<>();
        int total = 0;
        int remain = need;
        for (Material mat : ct.getSeedMaterials()) {
            if (remain <= 0) {
                break;
            }
            int fromWarehouse = 0;
            int fromBackpack = 0;
            if (warehouseFirst) {
                fromWarehouse = db.consumeSeed(uuid, ct.getId(), mat.name(), remain);
                int rem = remain - fromWarehouse;
                if (rem > 0 && player != null) {
                    fromBackpack = consumeBackpackSeeds(player, rem, mat);
                }
            } else {
                fromBackpack = player != null ? consumeBackpackSeeds(player, remain, mat) : 0;
                int rem = remain - fromBackpack;
                if (rem > 0) {
                    fromWarehouse = db.consumeSeed(uuid, ct.getId(), mat.name(), rem);
                }
            }
            int got = fromWarehouse + fromBackpack;
            if (got > 0) {
                parts.add(new SeedPart(mat, fromWarehouse, fromBackpack));
                total += got;
                remain -= got;
            }
            if (remain <= 0) {
                break;
            }
        }
        return new SeedConsumption(parts, total);
    }

    private int consumeBackpackSeeds(Player player, int need, Material seedMaterial) {
        HashMap<Integer, ItemStack> leftover = player.getInventory()
                .removeItem(new ItemStack(seedMaterial, need));
        int notTaken = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        return need - notTaken;
    }

    /** 统计玩家背包中某材质数量。 */
    public int countBackpackSeeds(Player player, Material seedMaterial) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == seedMaterial) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** 统计某作物全部种子素材在玩家背包的总数。 */
    public int countBackpackAll(Player player, CropType ct) {
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (Material m : ct.getSeedMaterials()) {
            total += countBackpackSeeds(player, m);
        }
        return total;
    }

    /** 统计某作物全部种子素材在种子仓库的总数（各素材独立仓库累加）。 */
    public long seedStockAll(UUID uuid, CropType ct) {
        long total = 0;
        for (Material m : ct.getSeedMaterials()) {
            total += db.getCropStock(uuid, ct.getId(), m.name());
        }
        return total;
    }

    /** 退还一次种子消耗：仓库部分退回对应素材仓库，背包部分退回背包（装不下转存仓库），绝不丢失。 */
    public void refundSeeds(Player player, UUID uuid, CropType ct, SeedConsumption c) {
        if (c == null || c.total <= 0) {
            return;
        }
        for (SeedPart part : c.parts) {
            if (part.warehouse > 0) {
                if (!db.addCropStock(uuid, ct.getId(), part.material.name(), part.warehouse)) {
                    db.addCompensation(uuid, "SEED", ct.getId(), part.material.name(), part.warehouse, "refundSeeds-warehouse");
                }
            }
            if (part.backpack > 0) {
                int notRefunded = 0;
                if (player != null) {
                    HashMap<Integer, ItemStack> leftover = player.getInventory()
                            .addItem(new ItemStack(part.material, part.backpack));
                    notRefunded = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                } else {
                    notRefunded = part.backpack;
                }
                if (notRefunded > 0) {
                    if (!db.addCropStock(uuid, ct.getId(), part.material.name(), notRefunded)) {
                        db.addCompensation(uuid, "SEED", ct.getId(), part.material.name(), notRefunded, "refundSeeds-backpack");
                    }
                }
            }
        }
    }

    // ================= 创建农田 =================

    /**
     * 在指定农田格创建农田并种植：消耗所有种子素材（背包优先→各素材仓库），有几颗种几格（最多 54 格）。
     *
     * @param globalIndex 目标农田全局槽位（须已在农田页解锁且空闲，由调用方校验）
     * @return 全局槽位索引；-1 表示无种子、创建失败
     */
    public int createFarm(Player player, CropType ct, int globalIndex) {
        UUID uuid = player.getUniqueId();
        long available;
        if (ct.isConsumeSeed()) {
            available = (long) countBackpackAll(player, ct) + seedStockAll(uuid, ct);
            if (available <= 0) {
                return -1;
            }
        } else {
            // 不消耗种子的作物：不依赖种子，直接满种 54 格
            available = PLOT_COUNT;
        }
        int plant = (int) Math.min(available, PLOT_COUNT);
        // 1. 先扣素材（背包优先→各素材仓库），失败直接中止（未建任何数据，无需回滚）
        SeedConsumption consumed = ct.isConsumeSeed()
                ? consumeSeeds(player, uuid, ct, plant, false) : new SeedConsumption(new ArrayList<>(), 0);
        if (ct.isConsumeSeed() && consumed.total <= 0) {
            return -1;
        }
        int plantCount = ct.isConsumeSeed() ? consumed.total : plant;
        // 2. 准备种植槽：前 plantCount 个空槽设为种植中，其余留空
        long now = System.currentTimeMillis() / 1000;
        List<PlotState> plots = new ArrayList<>(PLOT_COUNT);
        for (int i = 0; i < PLOT_COUNT; i++) {
            plots.add(i < plantCount
                    ? new PlotState(-1, i, 0, now, ct.randomDurationSec())
                    : new PlotState(-1, i, STAGE_EMPTY, 0, 0));
        }
        // 3. 单事务：建槽 + 写全部地块，原子落库；失败退还已扣素材（无「有槽无地块」残留）
        if (!db.createFarmTransaction(uuid, globalIndex, ct.getId(), plots)) {
            plugin.getLogger().warning("创建农田落库失败，已退还种子: uuid=" + uuid + " slot=" + globalIndex);
            refundSeeds(player, uuid, ct, consumed);
            return -1;
        }
        return globalIndex;
    }

    /**
     * 把前 count 个空槽设为种植中（创建时使用）。
     *
     * @return 实际种植数；-1 表示落库失败（调用方应回滚已扣资源）
     */
    public int plantFirstSlots(UUID uuid, int farmSlot, int count) {
        if (count <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis() / 1000;
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(uuid, farmSlot));
        if (ct == null) {
            return 0;
        }
        List<PlotState> plots = loadOrCreatePlots(uuid, farmSlot);
        if (plots.isEmpty()) {
            return -1; // 懒创建落库失败：按 DB 错误处理，不回写假地块
        }
        int planted = 0;
        for (PlotState p : plots) {
            if (planted >= count) {
                break;
            }
            if (p.stage == STAGE_EMPTY) {
                p.stage = 0;
                p.startedAt = now;
                p.durationSec = ct.randomDurationSec();
                planted++;
            }
        }
        return db.savePlots(uuid, farmSlot, plots) ? planted : -1;
    }

    /** 统计某农田当前空槽数（用于计算已种植数）。 */
    public int countEmptyPlots(UUID uuid, int farmSlot) {
        int empty = 0;
        for (PlotState p : db.loadPlots(uuid, farmSlot)) {
            if (p.stage == STAGE_EMPTY) {
                empty++;
            }
        }
        return empty;
    }

    // ================= 补种 =================

    /**
     * 获取某农田全部种植槽；不存在则懒创建 54 个空槽（stage=-1）。
     *
     * @return 种植槽列表；懒创建落库失败时返回空列表（调用方应按 DB 错误处理，
     *         避免「内存有地块、数据库没有」的假地块被后续写入污染）
     */
    public List<PlotState> loadOrCreatePlots(UUID uuid, int farmSlot) {
        List<PlotState> plots = db.loadPlots(uuid, farmSlot);
        if (plots.isEmpty()) {
            for (int i = 0; i < PLOT_COUNT; i++) {
                plots.add(new PlotState(farmSlot, i, STAGE_EMPTY, 0, 0));
            }
            if (!db.savePlots(uuid, farmSlot, plots)) {
                plugin.getLogger().warning("懒创建地块落库失败: uuid=" + uuid + " farmSlot=" + farmSlot);
                return new ArrayList<>();
            }
        }
        return plots;
    }

    /** 获取某农田全部种植槽并懒计算当前阶段（供生长 GUI 渲染；空槽 stage=-1）。 */
    public List<PlotState> getPlots(UUID uuid, int farmSlot) {
        long now = System.currentTimeMillis() / 1000;
        List<PlotState> plots = loadOrCreatePlots(uuid, farmSlot);
        for (PlotState p : plots) {
            p.stage = calcStage(p, now);
        }
        return plots;
    }

    /**
     * 补种：扫描空槽，从种子仓库→背包扣种子，每扣 1 粒补 1 格，直到补满或种子耗尽。
     *
     * @return 补种格数；0 表示无需补种；-1 表示有空格但种子不足
     */
    public int replant(Player player, UUID uuid, int farmSlot) {
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(uuid, farmSlot));
        if (ct == null) {
            return 0;
        }
        long now = System.currentTimeMillis() / 1000;
        List<PlotState> plots = loadOrCreatePlots(uuid, farmSlot);
        if (plots.isEmpty()) {
            return -1; // 懒创建落库失败：按 DB 错误处理
        }
        int empty = 0;
        for (PlotState p : plots) {
            if (p.stage == STAGE_EMPTY) {
                empty++;
            }
        }
        if (empty == 0) {
            return 0;
        }
        // 不消耗种子的作物：直接补满，无需扣种
        int seedCost = ct.isConsumeSeed() ? empty : 0;
        SeedConsumption consumed = new SeedConsumption(new ArrayList<>(), 0);
        if (seedCost > 0) {
            consumed = consumeSeeds(player, uuid, ct, seedCost, true);
            if (consumed.total <= 0) {
                return -1;
            }
        }
        int replantTarget = ct.isConsumeSeed() ? consumed.total : empty;
        int replanted = 0;
        for (PlotState p : plots) {
            if (replanted >= replantTarget) {
                break;
            }
            if (p.stage == STAGE_EMPTY) {
                p.stage = 0;
                p.startedAt = now;
                p.durationSec = ct.randomDurationSec();
                replanted++;
            }
        }
        if (db.savePlots(uuid, farmSlot, plots)) {
            return replanted;
        }
        // 落库失败：退还已扣仓库素材（对应素材仓库）+ 背包素材（真实物品，否则会凭空消失）；
        // 背包放不下部分按素材转存对应素材仓库，绝不让真实物品丢失
        plugin.getLogger().warning("补种落库失败，已退还种子: uuid=" + uuid + " farmSlot=" + farmSlot);
        refundSeeds(player, uuid, ct, consumed);
        return -1;
    }

    /** 退还已扣种子：优先退回背包（同 tick 刚 removeItem 腾出空间），装不下的退回对应作物种子仓库，绝不丢失。 */
    private void refundSeeds(Player player, UUID uuid, CropType ct, int count) {
        refundSeeds(player, uuid, ct, countParts(ct, count));
    }

    /** 按主素材构建一个单素材消耗明细（兼容旧调用：只退回主素材 count 个）。 */
    private SeedConsumption countParts(CropType ct, int count) {
        List<SeedPart> parts = new ArrayList<>();
        if (count > 0) {
            parts.add(new SeedPart(ct.getSeedMaterial(), count, 0));
        }
        return new SeedConsumption(parts, count);
    }

    // ================= 定时结算 =================

    /**
     * 60s 定时结算在线玩家的成熟收割（多周期补算）+ 自动重播。
     *
     * <p>多周期补算：槽位存 started_at+duration_sec，离线期间每满一个生长周期都入账一次
     * （离线收益与在线一致）；收割入总数后再消耗 1 粒种子重播下一周期，不足则槽位留空。
     *
     * <p>防不一致：槽位重置 + 产量入账走 {@link DatabaseManager#settleHarvest} 单事务；
     * 落库失败则退还已扣仓库种子并跳过该农场，避免下个 tick 对仍成熟槽位重复收割刷产量。
     */
    public void tickSettle() {
        long now = System.currentTimeMillis() / 1000;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            List<Integer> farmSlots = db.getFarmSlotIndexes(uuid);
            boolean changed = false;
            for (int fs : farmSlots) {
                // 单块农田异常隔离：不影响本玩家其他农田与整个 tick 结算
                try {
                    changed = settleFarm(player, uuid, fs, now) || changed;
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("农田结算异常已隔离: uuid=" + uuid + " farmSlot=" + fs + " ex=" + ex);
                }
            }
            if (changed) {
                gui.refresh(player);
            }
        }
    }

    /**
     * 结算单个农田：处理成熟槽位（多周期补算）、扣种重播/骨粉加速、原子落库。
     *
     * <p>防不一致：槽位重置 + 产量入账走 {@link DatabaseManager#settleHarvest} 单事务；
     * 落库失败则退还已扣仓库/背包种子与骨粉并跳过该农场，避免下个 tick 对仍成熟槽位重复收割刷产量。
     *
     * @return true 表示该农田发生了状态变化（需要刷新 GUI）
     */
    private boolean settleFarm(Player player, UUID uuid, int fs, long now) {
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(uuid, fs));
        if (ct == null) {
            return false;
        }
        int level = db.getFarmLevel(uuid, fs);
        // 每级产量以 gui.yml FarmUpdate.<作物>.LV<等级>.Drop / SeedDrop 为准；未配置回退基础产量
        int[] drop = ConfigManager.getFarmDrop(ct.getId(), level);
        long productYield = drop != null ? drop[0] : (long) ct.getYieldProduct() + Math.max(0, level - 1);
        long seedYield = drop != null ? drop[1] : ct.getYieldSeed();
        List<PlotState> plots = db.loadPlots(uuid, fs);
        if (plots.isEmpty()) {
            return false;
        }
        boolean slotChanged = false;
        long productGain = 0L;
        long seedGain = 0L;
        int consumedBonemeal = 0;
        // 本次结算为自动重播而消耗的全部种子素材汇总（按素材累加仓库/背包部分，回滚用）
        HashMap<Material, int[]> consumedMap = new HashMap<>();
        for (PlotState p : plots) {
            if (p.stage == STAGE_EMPTY) {
                continue;
            }
            long elapsed = now - p.startedAt;
            if (elapsed < p.durationSec) {
                continue; // 未成熟
            }
            // 多周期补算：自 started_at 起已完成的完整周期数（至少 1，防御异常数据）
            long cyclesL = elapsed / Math.max(1, p.durationSec);
            if (cyclesL < 1) {
                cyclesL = 1;
            }
            // 防溢出：异常数据/超长离线时单次结算周期数封顶，避免 int 溢出产生负收益
            if (cyclesL > 1_000_000L) {
                cyclesL = 1_000_000L;
                plugin.getLogger().warning("多周期补算封顶 100 万周期: uuid=" + uuid + " farmSlot=" + fs);
            }
            int cycles = (int) cyclesL;
            productGain += productYield * cycles;
            seedGain += seedYield * cycles;
            // 重播下一周期：消耗 1 粒种子素材（仓库→背包，多素材按序），不足则留空；不消耗种子的作物直接重播
            int seedCost = ct.isConsumeSeed() ? ConfigManager.REPLANT_COST_SEED : 0;
            SeedConsumption c = new SeedConsumption(new ArrayList<>(), 0);
            if (seedCost > 0) {
                c = consumeSeeds(player, uuid, ct, seedCost, true);
                for (SeedPart sp : c.parts) {
                    int[] a = consumedMap.computeIfAbsent(sp.material, k -> new int[2]);
                    a[0] += sp.warehouse;
                    a[1] += sp.backpack;
                }
            }
            if (c.total >= seedCost) {
                // 保留本周期未完成的剩余时间，继续推进新周期
                // 防御：duration_sec=0 的异常数据不可取模（除零），按 max(1) 处理
                long remainder = elapsed % Math.max(1, p.durationSec);
                p.stage = 0;
                p.startedAt = now - remainder;
                int duration = ct.randomDurationSec();
                // 等级成长时间缩短（gui.yml FarmUpdate LV<等级>.TimeReduction，百分比）
                int reduction = ConfigManager.getFarmTimeReduction(ct.getId(), level);
                if (reduction > 0) {
                    duration = (int) Math.max(1, duration * (100 - reduction) / 100);
                }
                // 骨粉加速：仅当该农田开启「骨粉加速」开关时，消耗骨粉缩短时长（仅自动重播生效）
                if (db.getFarmBonemealFast(uuid, fs) && db.consumeBonemeal(uuid, ConfigManager.BONEMEAL_FAST_COST) > 0) {
                    consumedBonemeal++;
                    duration = (int) Math.max(1, duration * ConfigManager.BONEMEAL_FAST_FACTOR);
                }
                p.durationSec = duration;
            } else {
                p.stage = STAGE_EMPTY;
                p.startedAt = 0;
                p.durationSec = 0;
            }
            slotChanged = true;
        }
        if (!slotChanged) {
            return false;
        }
        // 原子结算：槽位 upsert + 产物入 PRODUCT、种子回主素材（seed-material[0]）单事务；失败退还重播素材并跳过，防重复收割
        String mainSeedMat = ct.getSeedMaterial().name();
        if (db.settleHarvest(uuid, fs, ct.getId(), mainSeedMat, plots, productGain, seedGain)) {
            return true;
        }
        // 落库失败：退还为自动重播而扣的所有种子素材（仓库部分回对应素材仓库、背包部分退回背包）+ 已扣骨粉；
        // 背包放不下部分按素材转存对应素材仓库，绝不让真实物品丢失
        int settleTotal = 0;
        List<SeedPart> rollbackParts = new ArrayList<>();
        for (var e : consumedMap.entrySet()) {
            rollbackParts.add(new SeedPart(e.getKey(), e.getValue()[0], e.getValue()[1]));
            settleTotal += e.getValue()[0] + e.getValue()[1];
        }
        refundSeeds(player, uuid, ct, new SeedConsumption(rollbackParts, settleTotal));
        if (consumedBonemeal > 0 && !db.addBonemeal(uuid, consumedBonemeal)) {
            db.addCompensation(uuid, "BONEMEAL", null, null, consumedBonemeal, "settle-rollback-bonemeal");
        }
        plugin.getLogger().warning("收割结算落库失败，已回滚该农场: uuid=" + uuid + " farmSlot=" + fs);
        return false;
    }
}
