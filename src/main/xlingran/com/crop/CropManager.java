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
            int fromWarehouse = db.consumeSeed(uuid, need);
            int remain = need - fromWarehouse;
            if (remain > 0 && player != null) {
                return fromWarehouse + consumeBackpackSeeds(player, remain);
            }
            return fromWarehouse;
        }
        int fromBackpack = player != null ? consumeBackpackSeeds(player, need) : 0;
        int remain = need - fromBackpack;
        if (remain > 0) {
            return fromBackpack + db.consumeSeed(uuid, remain);
        }
        return fromBackpack;
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
        db.createFarmSlot(uuid, globalIndex, ct.getId());
        int plant = (int) Math.min(available, PLOT_COUNT);
        int consumed = tryConsumeSeeds(player, uuid, plant, false);
        plantFirstSlots(uuid, globalIndex, consumed);
        return globalIndex;
    }

    /** 把前 count 个空槽设为种植中（创建时使用）。 */
    public void plantFirstSlots(UUID uuid, int farmSlot, int count) {
        if (count <= 0) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        CropType ct = CropRegistry.get(db.getFarmSlotCropType(uuid, farmSlot));
        if (ct == null) {
            return;
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
        db.savePlots(uuid, farmSlot, plots);
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
        int consumed = tryConsumeSeeds(player, uuid, empty, true);
        if (consumed <= 0) {
            return -1;
        }
        int replanted = 0;
        for (PlotState p : plots) {
            if (replanted >= consumed) {
                break;
            }
            if (p.stage == STAGE_EMPTY) {
                p.stage = 0;
                p.startedAt = now;
                p.durationSec = ct.randomDurationSec();
                replanted++;
            }
        }
        db.savePlots(uuid, farmSlot, plots);
        return replanted;
    }

    // ================= 定时结算 =================

    /** 60s 定时结算：成熟槽自动收割入总数（小麦+1、种子+2），随后自动补种重播，最后刷新已打开 GUI。 */
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
                List<PlotState> plots = db.loadPlots(uuid, fs);
                if (plots.isEmpty()) {
                    continue;
                }
                boolean slotChanged = false;
                for (PlotState p : plots) {
                    if (calcStage(p, now) >= 7) {
                        // 收割入总数（基础产量 1 小麦 + 2 种子；农田等级加成：Lv2 +1 小麦、Lv3 +2 小麦）
                        int level = db.getFarmLevel(uuid, fs);
                        int wheatYield = ct.getYieldWheat() + Math.max(0, level - 1);
                        db.addWheat(uuid, wheatYield);
                        db.addSeed(uuid, ct.getYieldSeed());
                        // 自动补种重播：从种子仓库→背包扣 1 粒，不足则留空
                        int got = tryConsumeSeeds(player, uuid, ConfigManager.REPLANT_COST_SEED, true);
                        if (got >= ConfigManager.REPLANT_COST_SEED) {
                            p.stage = 0;
                            p.startedAt = now;
                            int duration = ct.randomDurationSec();
                            // 骨粉加速：消耗 1 骨粉，成熟时长缩短 20%
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
                }
                if (slotChanged) {
                    db.savePlots(uuid, fs, plots);
                    changed = true;
                }
            }
            if (changed) {
                gui.refresh(player);
            }
        }
    }
}
