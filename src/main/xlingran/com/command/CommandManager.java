package xlingran.com.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xlingran.com.config.ConfigManager;
import xlingran.com.crop.CropManager;
import xlingran.com.crop.CropRegistry;
import xlingran.com.crop.CropType;
import xlingran.com.db.DatabaseManager;
import xlingran.com.gui.GuiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /xlr 指令分发与 Tab 补全。
 *
 * <ul>
 *   <li>/xlr farm —— 打开农田 GUI（第 1 页，兼容旧指令）</li>
 *   <li>/xlr crop —— 打开农作物仓库 GUI（兼容旧指令）</li>
 *   <li>/xlr crop create &lt;名称&gt; —— 创建农田（消耗 1 粒种子：种子仓库→背包）</li>
 *   <li>/xlr crop farm —— 打开农田 GUI</li>
 *   <li>/xlr crop gui —— 打开农作物仓库 GUI</li>
 * </ul>
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private final DatabaseManager db;
    private final GuiManager gui;
    private final CropManager cropManager;

    public CommandManager(DatabaseManager db, GuiManager gui, CropManager cropManager) {
        this.db = db;
        this.gui = gui;
        this.cropManager = cropManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用。");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§e用法: /xlr farm | /xlr crop [create|farm|gui]");
            return true;
        }
        UUID uuid = player.getUniqueId();
        switch (args[0].toLowerCase()) {
            case "farm" -> {
                if (!player.hasPermission("xlr.farm")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return true;
                }
                gui.openFarm(player, 0);
            }
            case "crop" -> handleCrop(player, uuid, args);
            default -> player.sendMessage("§c未知子指令，用法: /xlr farm | /xlr crop [create|farm|gui]");
        }
        return true;
    }

    private void handleCrop(Player player, UUID uuid, String[] args) {
        if (args.length == 1) {
            // 兼容旧指令：/xlr crop 打开农作物仓库
            if (!player.hasPermission("xlr.crop")) {
                player.sendMessage(ConfigManager.MSG_NO_PERM);
                return;
            }
            gui.openCropMenu(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> {
                if (!player.hasPermission("xlr.crop.create")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                if (args.length < 3) {
                    player.sendMessage("§c用法: /xlr crop create <作物名称>");
                    return;
                }
                createCrop(player, uuid, args[2]);
            }
            case "farm" -> {
                if (!player.hasPermission("xlr.farm")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                gui.openFarm(player, 0);
            }
            case "gui" -> {
                if (!player.hasPermission("xlr.crop")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                gui.openCropMenu(player);
            }
            default -> player.sendMessage("§c未知 crop 子指令，用法: /xlr crop [create|farm|gui]");
        }
    }

    private void createCrop(Player player, UUID uuid, String typeId) {
        CropType ct = CropRegistry.get(typeId);
        if (ct == null) {
            player.sendMessage("§c未知作物: " + typeId + " §7（当前支持: " + String.join("、", CropRegistry.all().keySet()) + "）");
            return;
        }
        // 创建农田消耗 1 粒种子（优先种子仓库，不足扣背包）
        int cost = ConfigManager.CREATE_COST_SEED;
        int consumed = cropManager.tryConsumeSeeds(player, uuid, cost);
        if (consumed < cost) {
            player.sendMessage(ConfigManager.MSG_NO_SEED);
            return;
        }
        int globalIndex = db.findFirstFreeFarmSlot(uuid);
        db.createFarmSlot(uuid, globalIndex, ct.getId());
        int page = globalIndex / ConfigManager.FARM_PAGE_SLOTS + 1;
        int slot = globalIndex % ConfigManager.FARM_PAGE_SLOTS + 1;
        player.sendMessage(ConfigManager.MSG_CROP_CREATED
                .replace("%page%", String.valueOf(page))
                .replace("%slot%", String.valueOf(slot)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("farm", "crop"), args[0]);
        }
        if ("crop".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return filter(List.of("create", "farm", "gui"), args[1]);
            }
            if (args.length == 3 && "create".equalsIgnoreCase(args[1])) {
                // 创建指令只接受英文作物 id
                return filter(new ArrayList<>(CropRegistry.all().keySet()), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> candidates, String prefix) {
        List<String> result = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase().startsWith(prefix.toLowerCase())) {
                result.add(c);
            }
        }
        return result;
    }
}
