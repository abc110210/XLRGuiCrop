package xlingran.com.crop;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xlingran.com.Shan;
import xlingran.com.config.ConfigManager;
import xlingran.com.db.DatabaseManager;
import xlingran.com.gui.GuiManager;

import java.util.List;
import java.util.UUID;

/**
 * 生长状态机与自动收割管理。
 *
 * <p>生长机制：真实时间 + 懒计算（DB 存 started_at + duration_sec，按时间差推进阶段，
 * 离线也生长）；全局 60s 定时器结算在线玩家成熟槽位 → 收割入总数 → 自动重播 → 刷新已打开 GUI。
 */
public final class CropManager {

    /** 二级生长 GUI 种植槽总数（54 格）。 */
    public static final int PLOT_COUNT = 54;

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

    /** 按真实时间差懒计算当前生长阶段（0-7，7=成熟）。 */
    public int calcStage(PlotState plot, long nowSec) {
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

    /**
     * 获取某农田全部 54 个种植槽状态。
     *
     * <p>不存在时懒创建（自动种植：stage=0、随机时长、立即开始），并计算当前阶段。
     */
    public List<PlotState> getPlots(UUID uuid, int farmSlot) {
        long now = System.currentTimeMillis() / 1000;
        List<PlotState> plots = db.loadPlots(uuid, farmSlot);
        if (plots.isEmpty()) {
            CropType ct = CropRegistry.get(db.getFarmSlotCropType(uuid, farmSlot));
            if (ct == null) {
                ct = CropRegistry.get("wheat");
            }
            for (int i = 0; i < PLOT_COUNT; i++) {
                plots.add(new PlotState(farmSlot, i, 0, now, ct.randomDurationSec()));
            }
            db.savePlots(uuid, farmSlot, plots);
        }
        for (PlotState p : plots) {
            p.stage = calcStage(p, now);
        }
        return plots;
    }

    /** 60s 定时结算：成熟槽位自动收割入总数并自动重播，随后刷新已打开 GUI。 */
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
                        db.addWheat(uuid, ct.getYieldWheat());
                        db.addSeed(uuid, ct.getYieldSeed());
                        p.stage = 0;
                        p.startedAt = now;
                        p.durationSec = ct.randomDurationSec();
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
