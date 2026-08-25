package xlingran.com;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import xlingran.com.command.CommandManager;
import xlingran.com.config.ConfigLoader;
import xlingran.com.crop.CropManager;
import xlingran.com.db.DatabaseManager;
import xlingran.com.economy.EconomyManager;
import xlingran.com.gui.GuiManager;

import java.util.UUID;

/**
 * XLRGuiCrop 插件主类（开发核心）。
 *
 * <p>启动时按序接入：作物注册表 → 数据库 → GUI 监听 → 指令 → 生长定时器。
 * 设计依据见 docs/PLAN.md；维护说明见 docs/HANDOVER.md。
 * 版本：Spigot API 26.2 / Java 25。
 */
public final class Shan extends JavaPlugin {

    private DatabaseManager db;
    private GuiManager gui;
    private CropManager cropManager;

    @Override
    public void onEnable() {
        // 1. 加载配置（config.yml + gui.yml → ConfigManager；crops 段 → CropRegistry 注册全部作物）
        ConfigLoader.load(this);

        // 2. 数据库（player_data / farm_slots / crop_plots / crop_stock，WAL）
        db = new DatabaseManager(this, getDataFolder());

        // 3. 经济（Vault，可选）
        EconomyManager economy = new EconomyManager();
        if (economy.isEnabled()) {
            getLogger().info("Vault economy detected.");
        } else {
            getLogger().warning("Vault economy not found, bone-meal page unlock will be disabled.");
        }

        // 4. GUI 监听（各 GUI + 点击分发 + 防复制）
        gui = new GuiManager(this, db, economy);
        getServer().getPluginManager().registerEvents(gui, this);

        // 5. 生长管理（先建，再注入 gui 以便刷新）
        cropManager = new CropManager(this, db, gui);
        gui.setCropManager(cropManager);

        // 6. 指令
        CommandManager commandManager = new CommandManager(this, db, gui, cropManager);
        if (getCommand("xlr") != null) {
            getCommand("xlr").setExecutor(commandManager);
            getCommand("xlr").setTabCompleter(commandManager);
        }

        // 7. 60s 定时结算
        cropManager.start();

        // 8. 经济操作恢复 + 补偿自动重放（延迟执行，确保 Vault 服务已注册；主线程串行防并发写）
        Bukkit.getScheduler().runTaskLater(this, () -> {
            recoverEconomicOps(economy);
            replayCompensations();
        }, 40L);

        getLogger().info("Welcome to use the Xlr Crop plugin. Author: shan. Plugin loaded successfully!");
    }

    @Override
    public void onDisable() {
        if (cropManager != null) {
            cropManager.stop();
        }
        getLogger().info("XLRGuiCrop disabled.");
    }

    /**
     * 启动恢复 PENDING 经济操作（升级/解锁的崩溃窗口兜底）：
     * 按「当前余额相对 before−cost 是否已扣」判定——钱已扣则幂等补写 DB 后标记 PAID，
     * 钱未扣则直接标记 ROLLED_BACK（落库发生在扣款之后，DB 尚未写入无需回滚）。
     */
    private void recoverEconomicOps(EconomyManager economy) {
        if (db == null || economy == null || !economy.isEnabled()) {
            if (db != null && !db.getPendingEconomicOps().isEmpty()) {
                getLogger().warning("Vault 不可用，无法自动恢复 PENDING 经济操作，请用 economic_ops 表人工对账。");
            }
            return;
        }
        for (DatabaseManager.EconomicOp op : db.getPendingEconomicOps()) {
            double balance = balanceOf(economy, op.uuid);
            if (balance < 0) {
                getLogger().warning("PENDING 经济操作无法查询余额（离线且不支持离线余额），保持待处理: opId=" + op.opId);
                continue;
            }
            boolean paid;
            if (balance <= op.balanceBefore - op.cost + 1e-6) {
                paid = true;   // 钱已扣（余额 ≤ before−cost）
            } else if (balance >= op.balanceBefore - 1e-6) {
                paid = false;  // 钱未扣（余额 ≈ before）
            } else {
                getLogger().warning("PENDING 经济操作余额无法判定（期间有其它收支），保持待处理: opId=" + op.opId);
                continue;
            }
            if (paid) {
                // 钱已扣：幂等补写 DB（已写入则无需再动），完成后标记 PAID
                boolean done;
                if ("FARM_UPGRADE".equals(op.kind)) {
                    done = db.getFarmLevel(op.uuid, op.farmSlot) >= op.targetValue
                            || db.setFarmLevelAtLeast(op.uuid, op.farmSlot, op.targetValue);
                } else if ("BONE_UNLOCK".equals(op.kind)) {
                    done = db.getUnlockedPages(op.uuid) >= op.targetValue
                            || db.setUnlockedPagesAtLeast(op.uuid, op.targetValue);
                } else if ("FARM_UNLOCK".equals(op.kind)) {
                    // farm_slot 字段复用为农田页 index
                    done = db.getUnlockedCount(op.uuid, op.farmSlot) >= op.targetValue
                            || db.setUnlockedCountAtLeast(op.uuid, op.farmSlot, op.targetValue);
                } else {
                    done = false;
                }
                if (done) {
                    db.finishEconomicOp(op.opId, "PAID");
                    getLogger().info("经济操作恢复完成（钱已扣，已确认/补写 DB）: opId=" + op.opId);
                } else {
                    getLogger().warning("经济操作恢复补写 DB 失败，保持 PENDING 待人工处理: opId=" + op.opId);
                }
            } else {
                // 钱未扣：落库发生在扣款之后，DB 尚未写入，直接标记回滚
                db.finishEconomicOp(op.opId, "ROLLED_BACK");
                getLogger().info("经济操作恢复回滚（钱未扣，无需回写）: opId=" + op.opId);
            }
        }
    }

    /** 查询玩家当前余额；在线优先，离线需经济插件支持离线查询，否则返回 -1（无法判定）。 */
    private double balanceOf(EconomyManager economy, UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return economy.getBalance(online);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        if (economy.hasAccount(offline)) {
            return economy.getBalance(offline);
        }
        return -1;
    }

    /** 启动自动重放 PENDING 补偿台账（入账成功标记 PROCESSED；失败保持 PENDING 待人工）。 */
    private void replayCompensations() {
        if (db == null) {
            return;
        }
        for (DatabaseManager.CompensationRecord c : db.getCompensations("PENDING", 100)) {
            db.replayCompensation(c.id);
        }
    }

    /**
     * 重载配置文件（config.yml + gui.yml）：重新 apply 配置并注册作物；
     * 若定时结算间隔变化则重启定时器。DB 与已打开的 GUI 不受影响（下次打开生效）。
     * 配置语法/槽位冲突等异常向上抛出，由调用方提示玩家。
     */
    public void reloadConfig() {
        ConfigLoader.load(this);
        if (cropManager != null) {
            cropManager.stop();
            cropManager.start();
        }
        getLogger().info("XLRGuiCrop 配置已重载。");
    }
}
