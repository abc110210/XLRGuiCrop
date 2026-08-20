package xlingran.com.db;

import org.bukkit.Bukkit;
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
                    "created_at INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS farm_slots (" +
                    "uuid TEXT NOT NULL," +
                    "crop_type TEXT NOT NULL," +
                    "slot_index INTEGER NOT NULL," +
                    "PRIMARY KEY (uuid, slot_index))");
            st.execute("CREATE TABLE IF NOT EXISTS crop_plots (" +
                    "uuid TEXT NOT NULL," +
                    "farm_slot INTEGER NOT NULL," +
                    "plot_index INTEGER NOT NULL," +
                    "stage INTEGER DEFAULT 0," +
                    "started_at INTEGER DEFAULT 0," +
                    "duration_sec INTEGER DEFAULT 0," +
                    "PRIMARY KEY (uuid, farm_slot, plot_index))");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to init database: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(plugin);
        }
    }

    private void logError(SQLException e, String op) {
        plugin.getLogger().warning("DB " + op + " failed: " + e.getMessage());
    }

    // ================= player_data（总数/后备） =================

    private void ensurePlayer(UUID uuid) {
        String sql = "INSERT OR IGNORE INTO player_data (uuid, created_at) VALUES (?, ?)";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError(e, "ensurePlayer");
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

    public void addWheat(UUID uuid, int delta) {
        addCount(uuid, "wheat_count", delta);
    }

    public void addSeed(UUID uuid, int delta) {
        addCount(uuid, "seed_count", delta);
    }

    private void addCount(UUID uuid, String column, int delta) {
        if (delta == 0) return;
        ensurePlayer(uuid);
        String sql = "UPDATE player_data SET " + column + " = " + column + " + ? WHERE uuid=?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logError(e, "addCount");
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

    public void createFarmSlot(UUID uuid, int globalIndex, String cropType) {
        ensurePlayer(uuid);
        String sql = "INSERT OR IGNORE INTO farm_slots (uuid, crop_type, slot_index) VALUES (?, ?, ?)";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, cropType);
            ps.setInt(3, globalIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError(e, "createFarmSlot");
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

    /** 批量 upsert 某农田全部种植槽状态（收割重播/懒创建时调用）。 */
    public void savePlots(UUID uuid, int farmSlot, List<PlotState> plots) {
        String sql = "INSERT INTO crop_plots (uuid, farm_slot, plot_index, stage, started_at, duration_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid, farm_slot, plot_index) DO UPDATE SET " +
                "stage=excluded.stage, started_at=excluded.started_at, duration_sec=excluded.duration_sec";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        } catch (SQLException e) {
            logError(e, "savePlots");
        }
    }
}
