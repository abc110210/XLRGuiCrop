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

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 生长状态机、种子消耗与自动收割管理。
 *
 * <p>状态约定：stage 0-7 生长中（7=成熟）；stage=-1 表示空槽（未种植，等待补种）。
 * 创建农田/补种/收割后自动重播均消耗小麦种子（优先种子仓库，不足扣背包）。
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
        int stage = (int) (elapsed * 8 / plot.durationSec);
        return Math.min(7, Math.max(0, stage));
    }

    // ================= 种子消耗 =================

    /**
     * 消耗种子，返回实际消耗数。
     *
     * @param warehouseFirst true = 先扣种子仓库、不足扣背包（补种/自动重播）；
     *                       false = 先扣背包、不足扣种子仓库（创建农田）
     */
    public int tryConsumeSeeds(Player player, UUID uuid, int need, boolean warehouseFirst) {
        if (need <= 0) {
            return 0;
        }
        if (warehouseFirst) {
            int[] split = consumeSeedsSplit(player, uuid, need);
            return split[0] + split[1];
        }
        int fromBackpack = player != null ? consumeBackpackSeeds(player, need) : 0;
        int remain = need - fromBackpack;
        if (remain > 0) {
            return fromBackpack + db.consumeSeed(uuid, remain);
        }
        return fromBackpack;
    }

    /** 消耗种子并返回 [仓库部分, 背包部分]，便于落库失败时精确回滚仓库部分。 */
    private int[] consumeSeedsSplit(Player player, UUID uuid, int need) {
        int fromWarehouse = db.consumeSeed(uuid, need);
        int fromBackpack = 0;
        if (fromWarehouse < need && player != null) {
            fromBackpack = consumeBackpackSeeds(player, need - fromWarehouse);
        }
        return new int[]{fromWarehouse, fromBackpack};
    }

    private int consumeBackpackSeeds(Player player, int need) {
        HashMap<Integer, ItemStack> leftover = player.getInventory()
                .removeItem(new ItemStack(Material.WHEAT_SEEDS, need));
        int notTaken = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        return need - notTaken;
    }

    /** 统计玩家背包中小麦种子数量。 */
    public int countBackpackSeeds(Player player) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.WHEAT_SEEDS) {
                count += item.getAmount();
            }
        }
        return count;
    }

    // ================= 创建农田 =================

    /**
     * 创建农田并种植：消耗全部可用种子（背包优先→种子仓库），有几颗种几格（最多 54 格）。
     *
     * @return 全局槽位索引；-1 表示无种子、创建失败
     */
    public int createFarm(Player player, CropType ct) {
        UUID uuid = player.getUniqueId();
        long available = (long) countBackpackSeeds(player) + db.getSeed(uuid);
        if (available <= 0) {
            return -1;
        }
        int globalIndex = db.findFirstFreeFarmSlot(uuid);
        // 槽位写入失败（已占用或 SQL 异常）：不扣种子，直接中止，避免种子凭空消失
        if (!db.createFarmSlot(uuid, globalIndex, ct.getId())) {
            plugin.getLogger().warning("创建农田槽位失败: uuid=" + uuid + " slot=" + globalIndex);
            return -1;
        }
        int plant = (int) Math.min(available, PLOT_COUNT);
        int consumed = tryConsumeSeeds(player, uuid, plant, false);
        if (consumed <= 0) {
            // 种子扣取异常（理论不可达）：回滚槽位
            db.removeFarmSlot(uuid, globalIndex);
            return -1;
        }
        int planted = plantFirstSlots(uuid, globalIndex, consumed);
        if (planted < 0) {
            // 种植落库失败：退还全部已扣种子并清理槽位与种植槽，避免"种子消失、农田空转"
            db.addSeed(uuid, consumed);
            db.removeFarmSlot(uuid, globalIndex);
            db.removePlots(uuid, globalIndex);
            return -1;
        }
        if (planted < consumed) {
            // 理论不可达（新农田恒为 54 空槽），防御性退还差额
            db.addSeed(uuid, consumed - planted);
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

    /** 获取某农田全部种植槽；不存在则懒创建 54 个空槽（stage=-1）。 */
    public List<PlotState> loadOrCreatePlots(UUID uuid, int farmSlot) {
        List<PlotState> plots = db.loadPlots(uuid, farmSlot);
        if (plots.isEmpty()) {
            for (int i = 0; i < PLOT_COUNT; i++) {
                plots.add(new PlotState(farmSlot, i, STAGE_EMPTY, 0, 0));
            }
            db.savePlots(uuid, farmSlot, plots);
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
        int empty = 0;
        for (PlotState p : plots) {
            if (p.stage == STAGE_EMPTY) {
                empty++;
            }
        }
        if (empty == 0) {
            return 0;
        }
        int[] consumed = consumeSeedsSplit(player, uuid, empty);
        int consumedTotal = consumed[0] + consumed[1];
        if (consumedTotal <= 0) {
            return -1;
        }
        int replanted = 0;
        for (PlotState p : plots) {
            if (replanted >= consumedTotal) {
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
        // 落库失败：退还已扣仓库种子，避免种子凭空消失
        plugin.getLogger().warning("补种落库失败，已退还种子: uuid=" + uuid + " farmSlot=" + farmSlot);
        db.addSeed(uuid, consumed[0]);
        return -1;
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
                CropType ct = CropRegistry.get(db.getFarmSlotCropType(uuid, fs));
                if (ct == null) {
                    continue;
                }
                int level = db.getFarmLevel(uuid, fs);
                long wheatYield = (long) ct.getYieldWheat() + Math.max(0, level - 1);
                long seedYield = ct.getYieldSeed();
                List<PlotState> plots = db.loadPlots(uuid, fs);
                if (plots.isEmpty()) {
                    continue;
                }
                boolean slotChanged = false;
                long wheatGain = 0L;
                long seedGain = 0L;
                int seedsFromWarehouse = 0;
                for (PlotState p : plots) {
                    if (p.stage == STAGE_EMPTY) {
                        continue;
                    }
                    long elapsed = now - p.startedAt;
                    if (elapsed < p.durationSec) {
                        continue; // 未成熟
                    }
                    // 多周期补算：自 started_at 起已完成的完整周期数（至少 1，防御异常数据）
                    int cycles = (int) (elapsed / Math.max(1, p.durationSec));
                    if (cycles < 1) {
                        cycles = 1;
                    }
                    wheatGain += wheatYield * cycles;
                    seedGain += seedYield * cycles;
                    // 重播下一周期：消耗 1 粒种子（仓库→背包），不足则留空
                    int[] consumed = consumeSeedsSplit(player, uuid, ConfigManager.REPLANT_COST_SEED);
                    seedsFromWarehouse += consumed[0];
                    if (consumed[0] + consumed[1] >= ConfigManager.REPLANT_COST_SEED) {
                        // 保留本周期未完成的剩余时间，继续推进新周期
                        long remainder = elapsed % p.durationSec;
                        p.stage = 0;
                        p.startedAt = now - remainder;
                        int duration = ct.randomDurationSec();
                        // 骨粉加速：消耗 1 骨粉，成熟时长缩短 20%（仅自动重播生效）
                        if (db.consumeBonemeal(uuid, 1) > 0) {
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
                if (slotChanged) {
                    // 原子结算：槽位 upsert + 产量入账 单事务；失败则退还仓库种子并跳过，防重复收割
                    if (db.settleHarvest(uuid, fs, plots, wheatGain, seedGain)) {
                        changed = true;
                    } else {
                        db.addSeed(uuid, seedsFromWarehouse);
                        plugin.getLogger().warning("收割结算落库失败，已回滚该农场: uuid=" + uuid + " farmSlot=" + fs);
                    }
                }
            }
            if (changed) {
                gui.refresh(player);
            }
        }
    }
}
