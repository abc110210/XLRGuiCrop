package xlingran.com;

import org.bukkit.plugin.java.JavaPlugin;
import xlingran.com.command.CommandManager;
import xlingran.com.crop.CropManager;
import xlingran.com.crop.CropRegistry;
import xlingran.com.db.DatabaseManager;
import xlingran.com.gui.GuiManager;

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
        // 1. 作物注册表（wheat，参数取自 ConfigManager 常量，后续 config.yml 化）
        CropRegistry.registerDefaults();

        // 2. 数据库（player_data / farm_slots / crop_plots，WAL）
        db = new DatabaseManager(this, getDataFolder());

        // 3. GUI 监听（四类 GUI + 点击分发 + 防复制）
        gui = new GuiManager(this, db);
        getServer().getPluginManager().registerEvents(gui, this);

        // 4. 生长管理（先建，再注入 gui 以便刷新）
        cropManager = new CropManager(this, db, gui);
        gui.setCropManager(cropManager);

        // 5. 指令
        CommandManager commandManager = new CommandManager(db, gui);
        if (getCommand("xlr") != null) {
            getCommand("xlr").setExecutor(commandManager);
            getCommand("xlr").setTabCompleter(commandManager);
        }

        // 6. 60s 定时结算
        cropManager.start();

        getLogger().info("XLRGuiCrop enabled (v" + getPluginMeta().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        if (cropManager != null) {
            cropManager.stop();
        }
        getLogger().info("XLRGuiCrop disabled.");
    }
}
