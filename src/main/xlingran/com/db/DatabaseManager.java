package xlingran.com.db;

import xlingran.com.Shan;
import xlingran.com.config.ConfigManager;
import xlingran.com.crop.PlotState;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite 数据访问层。
 *
 * <p>三张表：
 * <ul>
 *   <li>player_data —— 玩家总数（wheat_count / seed_count，后备虚拟仓库）</li>
 *   <li>farm_slots —— 农田分页索引，slot_index 为全局槽位 = page*28+local(0-27)</li>
 *   <li>crop_plots —— 二级生长 GUI 每格状态（stage/started_at/duration_sec）</li>
 * </ul>
 *
 * <p>连接按需开关（量小 + WAL 单写者），避免长期占用连接。
 */
public final class DatabaseManager {

    private final Shan plugin;
    private final String url;

    public DatabaseManager(Shan plugin, File dataFolder) {
        this.plugin = plugin;
        // 目录不存在则创建，否则 SQLite 无法在其中建库文件（SQLITE_CANTOPEN）
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("无法创建插件数据目录: " + dataFolder.getAbsolutePath());
        }
        File dbFile = new File(dataFolder, "data.db"); // TODO yml: storage.db-file
        this.url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        init();
    }

    // ================= 连接与建表 =================

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
        }
        return conn;
    }

    private void init() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS player_data (" +
                    "uuid TEXT PRIMARY KEY," +
                    "wheat_count INTEGER DEFAULT 0," +
                    "seed_count INTEGER DEFAULT 0," +
                    "bonemeal_count INTEGER DEFAULT 0," +
                    "bonemeal_unlocked INTEGER DEFAULT 1," +
                    "created_at INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS farm_slots (" +
                    "uuid TEXT NOT NULL," +
                    "crop_type TEXT NOT NULL," +
                    "slot_index INTEGER NOT NULL," +
                    "level INTEGER DEFAULT 1," +
                    "PRIMARY KEY (uuid, slot_index))");
            st.execute("CREATE TABLE IF NOT EXISTS crop_plots (" +
                    "uuid TEXT NOT NULL," +
                    "farm_slot INTEGER NOT NULL," +
                    "plot_index INTEGER NOT NULL," +
                    "stage INTEGER DEFAULT 0," +
                    "started_at INTEGER DEFAULT 0," +
                    "duration_sec INTEGER DEFAULT 0," +
                    "PRIMARY KEY (uuid, farm_slot, plot_index))");
            // 旧库迁移：补齐骨粉列
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(player_data)")) {
                boolean hasBonemeal = false;
                boolean hasUnlocked = false;
                while (rs.next()) {
                    String col = rs.getString("name");
                    if ("bonemeal_count".equals(col)) {
                        hasBonemeal = true;
                    }
                    if ("bonemeal_unlocked".equals(col)) {
                        hasUnlocked = true;
                    }
                }
                if (!hasBonemeal) {
                    st.execute("ALTER TABLE player_data ADD COLUMN bonemeal_count INTEGER DEFAULT 0");
                }
                if (!hasUnlocked) {
                    st.execute("ALTER TABLE player_data ADD COLUMN bonemeal_unlocked INTEGER DEFAULT 1");
                }
            }
            // 旧库迁移：farm_slots 补齐农田等级列与骨粉加速开关列
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(farm_slots)")) {
                boolean hasLevel = false;
                boolean hasFast = false;
                while (rs.next()) {
                    String col = rs.getString("name");
                    if ("level".equals(col)) {
                        hasLevel = true;
                    }
                    if ("bonemeal_fast".equals(col)) {
                        hasFast = true;
                    }
                }
                if (!hasLevel) {
                    st.execute("ALTER TABLE farm_slots ADD COLUMN level INTEGER DEFAULT 1");
                }
                if (!hasFast) {
                    st.execute("ALTER TABLE farm_slots ADD COLUMN bonemeal_fast INTEGER DEFAULT 0");
                }
            }
        } catch (SQLException e) {
            // 抛异常让 onEnable 自然失败，由 Bukkit 禁用插件；
            // 切勿在此调用 disablePlugin（enable 过程中禁用会把 jar 提前关闭，导致后续 zip file closed）
            throw new IllegalStateException("数据库初始化失败: " + e.getMessage(), e);
        }
    }

    private void logError(SQLException e, String op) {
        plugin.getLogger().warning("DB " + op + " failed: " + e.getMessage());
    }

    // ================= player_data（总数/后备） =================

    /**
     * 确保玩家数据行存在（INSERT OR IGNORE）。
     *
     * @return true 表示行已就绪；false 表示写入失败（调用方应视为本次操作失败，杜绝「免费入账/免费扣减」）
     */
    private boolean ensurePlayer(UUID uuid) {
        String sql = "INSERT OR IGNORE INTO player_data (uuid, created_at) VALUES (?, ?)";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logError(e, "ensurePlayer");
            return false;
        }
    }

    public long getWheat(UUID uuid) {
        return getCount(uuid, "wheat_count");
    }

    public long getSeed(UUID uuid) {
        return getCount(uuid, "seed_count");
    }

    private long getCount(UUID uuid, String column) {
        ensurePlayer(uuid);
        String sql = "SELECT " + column + " FROM player_data WHERE uuid=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(column) : 0L;
            }
        } catch (SQLException e) {
            logError(e, "getCount");
            return 0L;
        }
    }

    public boolean addWheat(UUID uuid, long delta) {
        return addCount(uuid, "wheat_count", delta);
    }

    public boolean addSeed(UUID uuid, long delta) {
        return addCount(uuid, "seed_count", delta);
    }

    /**
     * 总数增减。正数（入账/退还）直接累加；负数（扣减）为原子条件扣除——
     * 库存不足时整次失败返回 false，杜绝「不足仍成功→免费取出/负库存」。
     */
    private boolean addCount(UUID uuid, String column, long delta) {
        if (delta == 0) return true;
        if (!ensurePlayer(uuid)) {
            return false; // 玩家行写入失败，后续 UPDATE 必影响 0 行
        }
        if (delta > 0) {
            String sql = "UPDATE player_data SET " + column + " = " + column + " + ? WHERE uuid=?";
            try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, delta);
                ps.setString(2, uuid.toString());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                logError(e, "addCount");
                return false;
            }
        }
        // 原子条件扣除：库存 < 需求时影响 0 行 → 返回 false
        String sql = "UPDATE player_data SET " + column + " = " + column + " + ? WHERE uuid=? AND " + column + " >= ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, delta);
            ps.setString(2, uuid.toString());
            ps.setLong(3, -delta);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "addCount");
            return false;
        }
    }

    /**
     * 从种子仓库扣除，返回实际扣除数。
     *
     * <p>扣减写库失败（DB 锁/异常）时返回 0，让调用方走「不足/失败」分支，杜绝免费种植。
     */
    public int consumeSeed(UUID uuid, int need) {
        if (need <= 0) {
            return 0;
        }
        long seed = getSeed(uuid);
        int take = (int) Math.min(seed, need);
        if (take > 0 && !addSeed(uuid, -take)) {
            plugin.getLogger().warning("consumeSeed 扣减失败: uuid=" + uuid + " need=" + need);
            return 0;
        }
        // 夹紧非负：即使历史数据异常为负，也不让负数流出污染调用方运算
        return Math.max(0, take);
    }

    // ================= 骨粉（bonemeal） =================

    public long getBonemeal(UUID uuid) {
        return getCount(uuid, "bonemeal_count");
    }

    public boolean addBonemeal(UUID uuid, long delta) {
        return addCount(uuid, "bonemeal_count", delta);
    }

    /**
     * 从骨粉库存扣除，返回实际扣除数。
     *
     * <p>扣减写库失败（DB 锁/异常）时返回 0，让调用方走「未加速/失败」分支，杜绝免费加速。
     */
    public int consumeBonemeal(UUID uuid, int need) {
        if (need <= 0) {
            return 0;
        }
        long bm = getBonemeal(uuid);
        int take = (int) Math.min(bm, need);
        if (take > 0 && !addBonemeal(uuid, -take)) {
            plugin.getLogger().warning("consumeBonemeal 扣减失败: uuid=" + uuid + " need=" + need);
            return 0;
        }
        // 夹紧非负：即使历史数据异常为负，也不让负数流出污染调用方运算
        return Math.max(0, take);
    }

    /** 已解锁页数（默认 1，即第 1 页）。 */
    public int getUnlockedPages(UUID uuid) {
        ensurePlayer(uuid);
        String sql = "SELECT bonemeal_unlocked FROM player_data WHERE uuid=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("bonemeal_unlocked") : 1;
            }
        } catch (SQLException e) {
            logError(e, "getUnlockedPages");
            return 1;
        }
    }

    public boolean setUnlockedPages(UUID uuid, int pages) {
        ensurePlayer(uuid);
        String sql = "UPDATE player_data SET bonemeal_unlocked=? WHERE uuid=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            // 下限保护：至少 1 页（历史异常数据钳制，避免页数 0 导致 page=-1）
            ps.setInt(1, Math.max(1, pages));
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setUnlockedPages");
            return false;
        }
    }

    // ================= farm_slots（农田分页） =================

    /** 已占用的全部全局槽位索引（升序）。 */
    public List<Integer> getFarmSlotIndexes(UUID uuid) {
        List<Integer> list = new ArrayList<>();
        String sql = "SELECT slot_index FROM farm_slots WHERE uuid=? ORDER BY slot_index";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt("slot_index"));
                }
            }
        } catch (SQLException e) {
            logError(e, "getFarmSlotIndexes");
        }
        return list;
    }

    /** 全局槽位索引 -> 作物类型 映射（仅当前加载用，一般全量小）。 */
    public Map<Integer, String> getFarmSlots(UUID uuid) {
        Map<Integer, String> map = new LinkedHashMap<>();
        String sql = "SELECT slot_index, crop_type FROM farm_slots WHERE uuid=? ORDER BY slot_index";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getInt("slot_index"), rs.getString("crop_type"));
                }
            }
        } catch (SQLException e) {
            logError(e, "getFarmSlots");
        }
        return map;
    }

    public boolean hasFarmSlot(UUID uuid, int globalIndex) {
        String sql = "SELECT 1 FROM farm_slots WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, globalIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logError(e, "hasFarmSlot");
            return false;
        }
    }

    public String getFarmSlotCropType(UUID uuid, int globalIndex) {
        String sql = "SELECT crop_type FROM farm_slots WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, globalIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("crop_type") : null;
            }
        } catch (SQLException e) {
            logError(e, "getFarmSlotCropType");
            return null;
        }
    }

    /**
     * 创建农田槽位。
     *
     * @return true 表示新写入成功；false 表示槽位已存在（INSERT OR IGNORE）或 SQL 失败，调用方应中止流程
     */
    public boolean createFarmSlot(UUID uuid, int globalIndex, String cropType) {
        ensurePlayer(uuid);
        String sql = "INSERT OR IGNORE INTO farm_slots (uuid, crop_type, slot_index) VALUES (?, ?, ?)";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, cropType);
            ps.setInt(3, globalIndex);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "createFarmSlot");
            return false;
        }
    }

    /** 删除农田槽位（回滚/清理用）。 */
    public void removeFarmSlot(UUID uuid, int globalIndex) {
        String sql = "DELETE FROM farm_slots WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, globalIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError(e, "removeFarmSlot");
        }
    }

    /** 跨页搜索第一个空闲全局槽位（page*28+local 升序），所有页均满则返回占用总数（继续可扩容）。 */
    public int findFirstFreeFarmSlot(UUID uuid) {
        Set<Integer> occupied = new HashSet<>(getFarmSlotIndexes(uuid));
        int i = 0;
        while (occupied.contains(i)) {
            i++;
        }
        return i;
    }

    /** 某页 28 格是否全部被农田占用（翻页前提）。 */
    public boolean isFarmPageFull(UUID uuid, int page) {
        int base = page * ConfigManager.FARM_PAGE_SLOTS;
        for (int local = 0; local < ConfigManager.FARM_PAGE_SLOTS; local++) {
            if (!hasFarmSlot(uuid, base + local)) {
                return false;
            }
        }
        return true;
    }

    /** 农田升级等级（默认 1）。 */
    public int getFarmLevel(UUID uuid, int globalIndex) {
        String sql = "SELECT level FROM farm_slots WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, globalIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("level") : 1;
            }
        } catch (SQLException e) {
            logError(e, "getFarmLevel");
            return 1;
        }
    }

    public boolean setFarmLevel(UUID uuid, int globalIndex, int level) {
        String sql = "UPDATE farm_slots SET level=? WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setString(2, uuid.toString());
            ps.setInt(3, globalIndex);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setFarmLevel");
            return false;
        }
    }

    /** 该农田是否开启骨粉加速（默认关）。 */
    public boolean getFarmBonemealFast(UUID uuid, int globalIndex) {
        String sql = "SELECT bonemeal_fast FROM farm_slots WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, globalIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("bonemeal_fast") > 0;
            }
        } catch (SQLException e) {
            logError(e, "getFarmBonemealFast");
            return false;
        }
    }

    public boolean setFarmBonemealFast(UUID uuid, int globalIndex, boolean on) {
        String sql = "UPDATE farm_slots SET bonemeal_fast=? WHERE uuid=? AND slot_index=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, on ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.setInt(3, globalIndex);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setFarmBonemealFast");
            return false;
        }
    }

    // ================= crop_plots（生长状态） =================

    public List<PlotState> loadPlots(UUID uuid, int farmSlot) {
        List<PlotState> list = new ArrayList<>();
        String sql = "SELECT plot_index, stage, started_at, duration_sec FROM crop_plots " +
                "WHERE uuid=? AND farm_slot=? ORDER BY plot_index";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, farmSlot);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PlotState(
                            farmSlot,
                            rs.getInt("plot_index"),
                            rs.getInt("stage"),
                            rs.getLong("started_at"),
                            rs.getInt("duration_sec")));
                }
            }
        } catch (SQLException e) {
            logError(e, "loadPlots");
        }
        return list;
    }

    /**
     * 批量 upsert 某农田全部种植槽状态（收割重播/懒创建时调用）。
     *
     * @return true 表示写入成功；false 表示 SQL 失败（调用方应据此回滚已扣资源）
     */
    public boolean savePlots(UUID uuid, int farmSlot, List<PlotState> plots) {
        String sql = "INSERT INTO crop_plots (uuid, farm_slot, plot_index, stage, started_at, duration_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid, farm_slot, plot_index) DO UPDATE SET " +
                "stage=excluded.stage, started_at=excluded.started_at, duration_sec=excluded.duration_sec";
        // 单事务批量写入：任一失败整体回滚，杜绝「部分格子已写、部分未写」造成状态不完整
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (PlotState p : plots) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, farmSlot);
                    ps.setInt(3, p.plotIndex);
                    ps.setInt(4, p.stage);
                    ps.setLong(5, p.startedAt);
                    ps.setInt(6, p.durationSec);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            logError(e, "savePlots");
            return false;
        }
    }

    /**
     * 原子结算一农场的成熟收割：批量 upsert 槽位状态 + 总数入账（单事务）。
     *
     * <p>防止「槽位已重置但入账失败」（产量丢失）或「已入账但槽位仍成熟」
     * （下个 tick 重复收割刷产量）两种不一致。
     *
     * @return true 表示成功（产量已入账、槽位已重置）；false 表示失败（调用方应回滚已扣种子并跳过该农场）
     */
    public boolean settleHarvest(UUID uuid, int farmSlot, List<PlotState> plots, long wheatGain, long seedGain) {
        if (!ensurePlayer(uuid)) {
            plugin.getLogger().warning("settleHarvest 玩家行不可用，已放弃该农场: uuid=" + uuid + " farmSlot=" + farmSlot);
            return false;
        }
        String upsert = "INSERT INTO crop_plots (uuid, farm_slot, plot_index, stage, started_at, duration_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid, farm_slot, plot_index) DO UPDATE SET " +
                "stage=excluded.stage, started_at=excluded.started_at, duration_sec=excluded.duration_sec";
        // 入账恒为正数，直接累加即可（扣减侧由 addCount/consumeX 的原子条件扣除保证下限）
        String credit = "UPDATE player_data SET wheat_count = wheat_count + ?, seed_count = seed_count + ? WHERE uuid=?";
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                    for (PlotState p : plots) {
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, farmSlot);
                        ps.setInt(3, p.plotIndex);
                        ps.setInt(4, p.stage);
                        ps.setLong(5, p.startedAt);
                        ps.setInt(6, p.durationSec);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(credit)) {
                    ps.setLong(1, wheatGain);
                    ps.setLong(2, seedGain);
                    ps.setString(3, uuid.toString());
                    affected = ps.executeUpdate();
                }
                // 入账影响 0 行（玩家行缺失）：回滚并判失败，防止「槽位已重置但产物未入账」
                if (affected <= 0) {
                    throw new SQLException("入账影响 0 行，玩家行缺失");
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        } catch (SQLException e) {
            logError(e, "settleHarvest");
            return false;
        }
    }

    /** 删除某农田全部种植槽（回滚/清理用）。 */
    public void removePlots(UUID uuid, int farmSlot) {
        String sql = "DELETE FROM crop_plots WHERE uuid=? AND farm_slot=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, farmSlot);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError(e, "removePlots");
        }
    }

    /**
     * 原子删除农田：farm_slots + crop_plots 单事务。
     *
     * <p>两步任一失败整体回滚，杜绝「槽位已删、种植数据残留」→ 重新创建同槽位继承旧生长数据。
     *
     * @return true 表示两表均删除完成；false 表示失败（已回滚，数据完整保留）
     */
    public boolean deleteFarm(UUID uuid, int farmSlot) {
        String delSlot = "DELETE FROM farm_slots WHERE uuid=? AND slot_index=?";
        String delPlots = "DELETE FROM crop_plots WHERE uuid=? AND farm_slot=?";
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(delSlot)) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, farmSlot);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(delPlots)) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, farmSlot);
                    ps.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        } catch (SQLException e) {
            logError(e, "deleteFarm");
            return false;
        }
    }
}
