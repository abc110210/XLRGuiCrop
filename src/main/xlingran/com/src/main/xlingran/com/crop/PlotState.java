package xlingran.com.crop;

/**
 * 单个种植槽的生长状态（与 crop_plots 表一行对应）。
 *
 * <p>生长采用真实时间 + 懒计算：DB 只存 started_at（周期开始）与 duration_sec（周期总时长），
 * 展示/结算时按时间差推进阶段，离线也生长。
 */
public final class PlotState {

    /** 所属农田全局槽位索引（page*28+local）。 */
    public final int farmSlot;
    /** 二级生长 GUI 种植槽 0-53。 */
    public final int plotIndex;
    /** 当前生长阶段 0-7（7=成熟）。 */
    public int stage;
    /** 本周期开始时间戳（秒）。 */
    public long startedAt;
    /** 本周期随机总时长（秒）。 */
    public int durationSec;

    public PlotState(int farmSlot, int plotIndex, int stage, long startedAt, int durationSec) {
        this.farmSlot = farmSlot;
        this.plotIndex = plotIndex;
        this.stage = stage;
        this.startedAt = startedAt;
        this.durationSec = durationSec;
    }
}
