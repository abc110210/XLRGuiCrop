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
 * <p>四张表：
 * <ul>
 *   <li>player_data —— 玩家基础数据（骨粉库存/解锁页数；wheat_count/seed_count 为历史小麦列，多作物已迁移 crop_stock）</li>
 *   <li>farm_slots —— 农田分页索引，slot_index 为全局槽位 = page*28+local(0-27)</li>
 *   <li>crop_plots —— 二级生长 GUI 每格状态（stage/started_at/duration_sec）</li>
 *   <li>crop_stock —— 每作物 种子(SEED)/产物(PRODUCT) 库存（多作物收割入账与种子消耗）</li>
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
            // 多作物库存：每种作物的种子(SEED)/产物(PRODUCT) 总数（后续作物仓库按此存取）
            st.execute("CREATE TABLE IF NOT EXISTS crop_stock (" +
                    "uuid TEXT NOT NULL," +
                    "crop_id TEXT NOT NULL," +
                    "item_type TEXT NOT NULL," + // SEED / PRODUCT
                    "count INTEGER DEFAULT 0," +
                    "PRIMARY KEY (uuid, crop_id, item_type))");
            // 补偿持久化：资源补偿（退还种子/骨粉等）落库失败时记入，供管理员核对/重放
            st.execute("CREATE TABLE IF NOT EXISTS compensation (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "uuid TEXT NOT NULL," +
                    "kind TEXT NOT NULL," +          // SEED / BONEMEAL / WHEAT 等
                    "crop_id TEXT," +
                    "item_type TEXT," +
                    "amount INTEGER NOT NULL," +
                    "reason TEXT," +
                    "created_at INTEGER DEFAULT 0)");
            // 经济操作日志：升级/解锁扣款审计（DB+Vault 无法跨系统原子，供对账）
            st.execute("CREATE TABLE IF NOT EXISTS op_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "uuid TEXT NOT NULL," +
                    "kind TEXT NOT NULL," +          // FARM_UPGRADE / BONE_UNLOCK
                    "detail TEXT," +
                    "created_at INTEGER DEFAULT 0)");
            // 经济操作状态机：升级/解锁扣款（DB+Vault 跨系统非原子）崩溃窗口恢复用
            st.execute("CREATE TABLE IF NOT EXISTS economic_ops (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "op_id TEXT UNIQUE NOT NULL," +
                    "uuid TEXT NOT NULL," +
                    "kind TEXT NOT NULL," +          // FARM_UPGRADE / BONE_UNLOCK
                    "detail TEXT," +
                    "cost REAL DEFAULT 0," +
                    "balance_before REAL DEFAULT 0," +
                    "status TEXT DEFAULT 'PENDING'," + // PENDING / PAID / ROLLED_BACK
                    "target_value INTEGER DEFAULT 0," +// 目标等级 / 目标页数
                    "farm_slot INTEGER DEFAULT -1," +  // 农田全局槽位（非农田操作为 -1）
                    "created_at INTEGER DEFAULT 0," +
                    "updated_at INTEGER DEFAULT 0)");
            // 农田页内解锁进度：每玩家每页已解锁的种植格数（0-28，第 1 格免费内置 ≥1）
            st.execute("CREATE TABLE IF NOT EXISTS farm_unlocks (" +
                    "uuid TEXT NOT NULL," +
                    "page INTEGER NOT NULL," +
                    "count INTEGER DEFAULT 0," + // 该页已解锁种植格数（local 0..count-1 为可种植）
                    "PRIMARY KEY (uuid, page))");
            // 旧库迁移（单事务，防止「迁移成功但置 0 失败」导致重启重复累加）
            conn.setAutoCommit(false);
            try {
                st.executeUpdate("INSERT INTO crop_stock (uuid, crop_id, item_type, count) " +
                        "SELECT uuid, 'wheat', 'PRODUCT', wheat_count FROM player_data WHERE wheat_count > 0 " +
                        "ON CONFLICT(uuid, crop_id, item_type) DO UPDATE SET count = count + excluded.count");
                st.executeUpdate("UPDATE player_data SET wheat_count = 0");
                st.executeUpdate("INSERT INTO crop_stock (uuid, crop_id, item_type, count) " +
                        "SELECT uuid, 'wheat', 'SEED', seed_count FROM player_data WHERE seed_count > 0 " +
                        "ON CONFLICT(uuid, crop_id, item_type) DO UPDATE SET count = count + excluded.count");
                st.executeUpdate("UPDATE player_data SET seed_count = 0");
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
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
            // 旧库迁移：compensation 补齐处理状态列（历史记录默认 PENDING，启动恢复时自动重放）
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(compensation)")) {
                boolean hasStatus = false;
                while (rs.next()) {
                    if ("status".equals(rs.getString("name"))) {
                        hasStatus = true;
                    }
                }
                if (!hasStatus) {
                    st.execute("ALTER TABLE compensation ADD COLUMN status TEXT DEFAULT 'PENDING'");
                }
            }
            // 旧库迁移：player_data 补齐农田已解锁页数列（默认 1 页；历史异常数据清零时钳制回 1）
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(player_data)")) {
                boolean hasFarmPages = false;
                while (rs.next()) {
                    if ("farm_unlocked_pages".equals(rs.getString("name"))) {
                        hasFarmPages = true;
                    }
                }
                if (!hasFarmPages) {
                    st.execute("ALTER TABLE player_data ADD COLUMN farm_unlocked_pages INTEGER DEFAULT 1");
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

    // ================= crop_stock（多作物库存：SEED / PRODUCT） =================

    /** 查询某作物某项库存（itemType = SEED / PRODUCT）。 */
    public long getCropStock(UUID uuid, String cropId, String itemType) {
        ensurePlayer(uuid);
        String sql = "SELECT count FROM crop_stock WHERE uuid=? AND crop_id=? AND item_type=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, cropId);
            ps.setString(3, itemType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("count") : 0L;
            }
        } catch (SQLException e) {
            logError(e, "getCropStock");
            return 0L;
        }
    }

    /**
     * 作物库存增减。正数直接累加（按配置 clamp 上限）；负数（扣减）为原子条件扣除——
     * 库存不足整次失败返回 false。SQL 异常时轻量重试一次（SQLite 锁通常瞬时）。
     */
    public boolean addCropStock(UUID uuid, String cropId, String itemType, long delta) {
        if (delta == 0) return true;
        if (!ensurePlayer(uuid)) {
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            Boolean r = addCropStockOnce(uuid, cropId, itemType, delta);
            if (r != null) {
                return r; // 业务结果（true 成功 / false 不足等），不重试
            }
            // r == null 表示 SQL 异常，重试一次
        }
        return false;
    }

    /** 返回 true=成功，false=业务失败（如库存不足），null=SQL 异常（可重试）。 */
    private Boolean addCropStockOnce(UUID uuid, String cropId, String itemType, long delta) {
        if (delta > 0) {
            long max = ConfigManager.WAREHOUSE_MAX_STOCK;
            // 上限保护：max>0 时单类库存 clamp 到上限；max=0 表示不限制
            if (max <= 0) {
                String sql = "INSERT INTO crop_stock (uuid, crop_id, item_type, count) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(uuid, crop_id, item_type) DO UPDATE SET count = count + excluded.count";
                try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, cropId);
                    ps.setString(3, itemType);
                    ps.setLong(4, delta);
                    return ps.executeUpdate() > 0;
                } catch (SQLException e) {
                    logError(e, "addCropStock");
                    return null;
                }
            }
            String sql = "INSERT INTO crop_stock (uuid, crop_id, item_type, count) VALUES (?, ?, ?, MIN(?, ?)) " +
                    "ON CONFLICT(uuid, crop_id, item_type) DO UPDATE SET count = MIN(count + excluded.count, ?)";
            try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cropId);
                ps.setString(3, itemType);
                ps.setLong(4, delta);
                ps.setLong(5, max);
                ps.setLong(6, max);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                logError(e, "addCropStock");
                return null;
            }
        }
        String sql = "UPDATE crop_stock SET count = count + ? WHERE uuid=? AND crop_id=? AND item_type=? AND count >= ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, delta);
            ps.setString(2, uuid.toString());
            ps.setString(3, cropId);
            ps.setString(4, itemType);
            ps.setLong(5, -delta);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "addCropStock");
            return null;
        }
    }

    /**
     * 记录一条补偿（资源退还落库失败时的兜底台账）。
     * 尽力写入；SQL 异常（SQLite 锁通常瞬时）轻量重试一次，仍失败才放弃（调用方已记录日志）。
     */
    public void addCompensation(UUID uuid, String kind, String cropId, String itemType, long amount, String reason) {
        String sql = "INSERT INTO compensation (uuid, kind, crop_id, item_type, amount, reason, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, kind);
                ps.setString(3, cropId);
                ps.setString(4, itemType);
                ps.setLong(5, amount);
                ps.setString(6, reason);
                ps.setLong(7, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                logError(e, "addCompensation");
            }
        }
    }

    /** 记录一条经济/管理操作日志（升级/解锁审计，供对账）。 */
    public void addOpLog(UUID uuid, String kind, String detail) {
        String sql = "INSERT INTO op_log (uuid, kind, detail, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kind);
            ps.setString(3, detail);
            ps.setLong(4, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError(e, "addOpLog");
        }
    }

    // ================= 经济操作状态机（升级/解锁幂等） =================

    /**
     * 开始一笔经济操作（status=PENDING），返回唯一 op_id；连续写入失败返回 null（调用方应中止操作）。
     *
     * <p>流程：登记 PENDING → 扣 Vault 金币 → 写 DB（幂等 at-least）→ 标记 PAID。
     * 任一崩溃窗口由启动恢复 {@link #getPendingEconomicOps()} 按「余额是否已扣」判定补写/回滚。
     */
    public String beginEconomicOp(UUID uuid, String kind, String detail, double cost,
                                  double balanceBefore, int targetValue, int farmSlot) {
        String sql = "INSERT INTO economic_ops (op_id, uuid, kind, detail, cost, balance_before, status, target_value, farm_slot, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)";
        for (int attempt = 0; attempt < 3; attempt++) {
            String opId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis() / 1000;
            try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, opId);
                ps.setString(2, uuid.toString());
                ps.setString(3, kind);
                ps.setString(4, detail);
                ps.setDouble(5, cost);
                ps.setDouble(6, balanceBefore);
                ps.setInt(7, targetValue);
                ps.setInt(8, farmSlot);
                ps.setLong(9, now);
                ps.setLong(10, now);
                return ps.executeUpdate() > 0 ? opId : null;
            } catch (SQLException e) {
                logError(e, "beginEconomicOp");
            }
        }
        return null;
    }

    /** 结束一笔经济操作：成功标记 PAID，失败回滚标记 ROLLED_BACK。 */
    public boolean finishEconomicOp(String opId, String status) {
        String sql = "UPDATE economic_ops SET status=?, updated_at=? WHERE op_id=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis() / 1000);
            ps.setString(3, opId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "finishEconomicOp");
            return false;
        }
    }

    /** 查询全部 PENDING 经济操作（启动恢复用）。 */
    public List<EconomicOp> getPendingEconomicOps() {
        List<EconomicOp> list = new ArrayList<>();
        String sql = "SELECT op_id, uuid, kind, detail, cost, balance_before, status, target_value, farm_slot " +
                "FROM economic_ops WHERE status='PENDING' ORDER BY id";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new EconomicOp(
                        rs.getString("op_id"),
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("kind"),
                        rs.getString("detail"),
                        rs.getDouble("cost"),
                        rs.getDouble("balance_before"),
                        rs.getString("status"),
                        rs.getInt("target_value"),
                        rs.getInt("farm_slot")));
            }
        } catch (SQLException e) {
            logError(e, "getPendingEconomicOps");
        }
        return list;
    }

    /** 幂等升级：仅当当前等级低于目标时才更新（恢复补写可安全重入）。 */
    public boolean setFarmLevelAtLeast(UUID uuid, int globalIndex, int level) {
        String sql = "UPDATE farm_slots SET level=? WHERE uuid=? AND slot_index=? AND level < ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setString(2, uuid.toString());
            ps.setInt(3, globalIndex);
            ps.setInt(4, level);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setFarmLevelAtLeast");
            return false;
        }
    }

    /** 幂等解锁页数：仅当当前页数低于目标时才更新（恢复补写可安全重入）。 */
    public boolean setUnlockedPagesAtLeast(UUID uuid, int pages) {
        ensurePlayer(uuid);
        int target = Math.max(1, pages);
        String sql = "UPDATE player_data SET bonemeal_unlocked=? WHERE uuid=? AND bonemeal_unlocked < ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, target);
            ps.setString(2, uuid.toString());
            ps.setInt(3, target);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setUnlockedPagesAtLeast");
            return false;
        }
    }

    // ================= 补偿台账（自动重放 / 管理员处理） =================

    /** 标记补偿记录状态（PROCESSED=已处理）。 */
    public boolean markCompensation(long id, String status) {
        String sql = "UPDATE compensation SET status=? WHERE id=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "markCompensation");
            return false;
        }
    }

    /** 查询一条补偿记录；不存在返回 null。 */
    public CompensationRecord getCompensation(long id) {
        String sql = "SELECT id, uuid, kind, crop_id, item_type, amount, reason, created_at, status " +
                "FROM compensation WHERE id=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapCompensation(rs) : null;
            }
        } catch (SQLException e) {
            logError(e, "getCompensation");
            return null;
        }
    }

    /** 查询指定状态的最新补偿记录（管理员查看用，倒序）。 */
    public List<CompensationRecord> getCompensations(String status, int limit) {
        List<CompensationRecord> list = new ArrayList<>();
        String sql = "SELECT id, uuid, kind, crop_id, item_type, amount, reason, created_at, status " +
                "FROM compensation WHERE status=? ORDER BY id DESC LIMIT ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapCompensation(rs));
                }
            }
        } catch (SQLException e) {
            logError(e, "getCompensations");
        }
        return list;
    }

    private CompensationRecord mapCompensation(ResultSet rs) throws SQLException {
        return new CompensationRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("kind"),
                rs.getString("crop_id"),
                rs.getString("item_type"),
                rs.getLong("amount"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                rs.getString("status"));
    }

    /**
     * 重放一条补偿：按 kind 补回对应库存（BONEMEAL→骨粉库存，SEED/PRODUCT→对应作物库存），
     * 入账成功自动标记 PROCESSED。
     *
     * @return true 表示已成功重放并标记；false 表示无需/无法重放（记录保持原状态）
     */
    public boolean replayCompensation(long id) {
        CompensationRecord c = getCompensation(id);
        if (c == null || "PROCESSED".equals(c.status)) {
            return false;
        }
        boolean ok;
        switch (c.kind) {
            case "BONEMEAL" -> ok = addBonemeal(c.uuid, c.amount);
            case "SEED" -> ok = c.cropId != null && addCropStock(c.uuid, c.cropId, "SEED", c.amount);
            case "PRODUCT" -> ok = c.cropId != null && addCropStock(c.uuid, c.cropId, "PRODUCT", c.amount);
            default -> ok = false;
        }
        if (ok) {
            if (markCompensation(id, "PROCESSED")) {
                plugin.getLogger().info("补偿已重放: id=" + id + " kind=" + c.kind + " amount=" + c.amount);
            } else {
                plugin.getLogger().warning("补偿已入账但标记失败（下次重放会重复入账，请人工核对）: id=" + id);
            }
        } else {
            plugin.getLogger().warning("补偿重放失败: id=" + id + " kind=" + c.kind + " amount=" + c.amount);
        }
        return ok;
    }

    /**
     * 从某作物指定种子素材库存扣除，返回实际扣除数；扣减写库失败返回 0（杜绝免费种植）。
     *
     * @param seedMaterialName 种子素材材质名（如 "MELON_SEEDS"、"OAK_SAPLING"），对应 crop_stock.item_type
     */
    public int consumeSeed(UUID uuid, String cropId, String seedMaterialName, int need) {
        if (need <= 0) {
            return 0;
        }
        long stock = getCropStock(uuid, cropId, seedMaterialName);
        int take = (int) Math.min(stock, need);
        if (take > 0 && !addCropStock(uuid, cropId, seedMaterialName, -take)) {
            plugin.getLogger().warning("consumeSeed 扣减失败: uuid=" + uuid + " crop=" + cropId
                    + " seed=" + seedMaterialName + " need=" + need);
            return 0;
        }
        return Math.max(0, take);
    }

    /**
     * 从种子仓库扣除（小麦种子，兼容旧入口），返回实际扣除数。
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

    /**
     * 农田数量；-1 表示查询失败（调用方应阻止创建，防止 DB 故障时绕过农田上限）。
     */
    public int getFarmCount(UUID uuid) {
        String sql = "SELECT COUNT(*) FROM farm_slots WHERE uuid=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logError(e, "getFarmCount");
            return -1;
        }
    }

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

    /**
     * 原子创建农田：farm_slots 建槽 + crop_plots 批量写入（单事务）。
     * 任一失败整体回滚，杜绝「有槽位无地块 / 有地块无槽位」的残留；调用方应退还已扣种子。
     *
     * @param plots 需写入的种植槽（含 stage/started_at/duration_sec，通常 54 条）
     */
    public boolean createFarmTransaction(UUID uuid, int globalIndex, String cropType, List<PlotState> plots) {
        ensurePlayer(uuid);
        String insSlot = "INSERT INTO farm_slots (uuid, crop_type, slot_index) VALUES (?, ?, ?)";
        String insPlot = "INSERT INTO crop_plots (uuid, farm_slot, plot_index, stage, started_at, duration_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insSlot)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, cropType);
                    ps.setInt(3, globalIndex);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(insPlot)) {
                    for (PlotState p : plots) {
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, globalIndex);
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
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        } catch (SQLException e) {
            logError(e, "createFarmTransaction");
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

    /**
     * 玩家已解锁农田总页数（第 1 页默认解锁；钳制至少 1）。
     *
     * @return 页数；查询失败回退 1
     */
    public int getFarmUnlockedPages(UUID uuid) {
        return getUnlockedPages(uuid, "farm_unlocked_pages");
    }

    /** 幂等增加/保证农田已解锁页数（仅当当前低于目标时才更新，便于启动恢复与自动解锁可安全重入）。 */
    public boolean setFarmUnlockedPagesAtLeast(UUID uuid, int pages) {
        ensurePlayer(uuid);
        int target = Math.max(1, pages);
        String sql = "UPDATE player_data SET farm_unlocked_pages=? WHERE uuid=? AND farm_unlocked_pages < ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, target);
            ps.setString(2, uuid.toString());
            ps.setInt(3, target);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setFarmUnlockedPagesAtLeast");
            return false;
        }
    }
    private int getUnlockedPages(UUID uuid, String column) {
        ensurePlayer(uuid);
        String sql = "SELECT " + column + " FROM player_data WHERE uuid=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(1, rs.getInt(column)) : 1;
            }
        } catch (SQLException e) {
            logError(e, "getUnlockedPages");
            return 1;
        }
    }

    /**
     * 某页已解锁种植格数（0-28）。第 1 页内置至少 1 格（免费种植格）；查询失败回退默认。
     *
     * @return 已解锁格数（local 0..count-1 为可种植格）
     */
    public int getUnlockedCount(UUID uuid, int page) {
        String sql = "SELECT count FROM farm_unlocks WHERE uuid=? AND page=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            try (ResultSet rs = ps.executeQuery()) {
                int def = page == 0 ? 1 : 0;
                if (!rs.next()) {
                    return def;
                }
                return Math.max(def, Math.min(ConfigManager.FARM_PAGE_SLOTS, rs.getInt("count")));
            }
        } catch (SQLException e) {
            logError(e, "getUnlockedCount");
            return page == 0 ? 1 : 0;
        }
    }

    /**
     * 保证某页解锁格数至少为 count（第 1 页免费 1 格；幂等 upsert，可安全重入）。
     *
     * @return true 表示写入成功
     */
    public boolean setUnlockedCountAtLeast(UUID uuid, int page, int count) {
        int target = Math.max(page == 0 ? 1 : 0, Math.min(ConfigManager.FARM_PAGE_SLOTS, count));
        String sql = "INSERT INTO farm_unlocks (uuid, page, count) VALUES (?, ?, ?) " +
                "ON CONFLICT(uuid, page) DO UPDATE SET count = MAX(count, excluded.count)";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.setInt(3, target);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logError(e, "setUnlockedCountAtLeast");
            return false;
        }
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
        // 单事务批量写入：任一失败显式回滚，杜绝「部分格子已写、部分未写」造成状态不完整
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
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
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
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
    public boolean settleHarvest(UUID uuid, int farmSlot, String cropId, String seedMaterialName, List<PlotState> plots, long productGain, long seedGain) {
        if (!ensurePlayer(uuid)) {
            plugin.getLogger().warning("settleHarvest 玩家行不可用，已放弃该农场: uuid=" + uuid + " farmSlot=" + farmSlot);
            return false;
        }
        String upsert = "INSERT INTO crop_plots (uuid, farm_slot, plot_index, stage, started_at, duration_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid, farm_slot, plot_index) DO UPDATE SET " +
                "stage=excluded.stage, started_at=excluded.started_at, duration_sec=excluded.duration_sec";
        // 产物/种子入账到该作物的 crop_stock（入账恒为正数，扣减侧由原子条件扣除保证下限；上限按配置 clamp 或不限制）
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
                int p = creditStock(conn, cropId, "PRODUCT", productGain, uuid);
                int s = creditStock(conn, cropId, seedMaterialName, seedGain, uuid);
                // 入账影响 0 行（玩家行缺失）：回滚并判失败，防止「槽位已重置但产物未入账」
                if (p <= 0 || s <= 0) {
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

    /** 在给定事务连接上入账某作物库存；max<=0 时不限制上限，否则 clamp；达上限记录日志。 */
    private int creditStock(Connection conn, String cropId, String itemType, long amount, UUID uuid) throws SQLException {
        long max = ConfigManager.WAREHOUSE_MAX_STOCK;
        if (max <= 0) {
            String sql = "INSERT INTO crop_stock (uuid, crop_id, item_type, count) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(uuid, crop_id, item_type) DO UPDATE SET count = count + excluded.count";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, cropId);
                ps.setString(3, itemType);
                ps.setLong(4, amount);
                return ps.executeUpdate();
            }
        }
        // 达上限预警：查询当前（同连接可见未提交），超出部分将被 clamp 丢弃
        long cur = 0L;
        try (PreparedStatement q = conn.prepareStatement("SELECT count FROM crop_stock WHERE uuid=? AND crop_id=? AND item_type=?")) {
            q.setString(1, uuid.toString());
            q.setString(2, cropId);
            q.setString(3, itemType);
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) {
                    cur = rs.getLong("count");
                }
            }
        }
        if (cur + amount > max) {
            plugin.getLogger().warning("仓库库存达上限，超出部分未入账: uuid=" + uuid + " crop=" + cropId
                    + " type=" + itemType + " 超出=" + (cur + amount - max));
        }
        String sql = "INSERT INTO crop_stock (uuid, crop_id, item_type, count) VALUES (?, ?, ?, MIN(?, ?)) " +
                "ON CONFLICT(uuid, crop_id, item_type) DO UPDATE SET count = MIN(count + excluded.count, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, cropId);
            ps.setString(3, itemType);
            ps.setLong(4, amount);
            ps.setLong(5, max);
            ps.setLong(6, max);
            return ps.executeUpdate();
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

    /** 一笔进行中的经济操作（升级/解锁）：PENDING → PAID / ROLLED_BACK。 */
    public static final class EconomicOp {
        public final String opId;
        public final UUID uuid;
        public final String kind;
        public final String detail;
        public final double cost;
        public final double balanceBefore;
        public final String status;
        public final int targetValue;
        public final int farmSlot;

        EconomicOp(String opId, UUID uuid, String kind, String detail, double cost,
                   double balanceBefore, String status, int targetValue, int farmSlot) {
            this.opId = opId;
            this.uuid = uuid;
            this.kind = kind;
            this.detail = detail;
            this.cost = cost;
            this.balanceBefore = balanceBefore;
            this.status = status;
            this.targetValue = targetValue;
            this.farmSlot = farmSlot;
        }
    }

    /** 一条补偿台账记录（status：PENDING 待处理 / PROCESSED 已重放）。 */
    public static final class CompensationRecord {
        public final long id;
        public final UUID uuid;
        public final String kind;
        public final String cropId;
        public final String itemType;
        public final long amount;
        public final String reason;
        public final long createdAt;
        public final String status;

        CompensationRecord(long id, UUID uuid, String kind, String cropId, String itemType,
                           long amount, String reason, long createdAt, String status) {
            this.id = id;
            this.uuid = uuid;
            this.kind = kind;
            this.cropId = cropId;
            this.itemType = itemType;
            this.amount = amount;
            this.reason = reason;
            this.createdAt = createdAt;
            this.status = status;
        }
    }
}
