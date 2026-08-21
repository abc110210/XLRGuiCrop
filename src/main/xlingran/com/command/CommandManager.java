package xlingran.com.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
            gui.openCropMenu(player, 0);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> {
                if (!player.hasPermission("xlr.crop.create")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                if (args.length < 3) {
                    // 无参数：打开创建农田 GUI
                    gui.openCreateCrop(player);
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
                gui.openCropMenu(player, 0);
            }
            case "bone" -> {
                if (!player.hasPermission("xlr.crop")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                gui.openBonemeal(player, 0);
            }
            case "menu" -> {
                if (!player.hasPermission("xlr.crop")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                gui.openMenu(player);
            }
            case "gufen" -> {
                if (!player.hasPermission("xlr.admin")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                if (args.length < 5 || !"update".equalsIgnoreCase(args[2])) {
                    player.sendMessage("§c用法: /xlr crop gufen update <玩家ID> <解锁页数>");
                    return;
                }
                gufenUpdate(player, args[3], args[4]);
            }
            default -> player.sendMessage("§c未知 crop 子指令，用法: /xlr crop [create|farm|gui|bone|menu|gufen]");
        }
    }

    private void createCrop(Player player, UUID uuid, String typeId) {
        CropType ct = CropRegistry.get(typeId);
        if (ct == null) {
            player.sendMessage("§c未知作物: " + typeId + " §7（当前支持: " + String.join("、", CropRegistry.all().keySet()) + "）");
            return;
        }
        // 创建农田：扣种子（背包优先→仓库），有几颗种几格
        int globalIndex = cropManager.createFarm(player, ct);
        if (globalIndex < 0) {
            player.sendMessage(ConfigManager.MSG_NO_SEED);
            return;
        }
        int planted = CropManager.PLOT_COUNT - cropManager.countEmptyPlots(uuid, globalIndex);
        int page = globalIndex / ConfigManager.FARM_PAGE_SLOTS + 1;
        int slot = globalIndex % ConfigManager.FARM_PAGE_SLOTS + 1;
        player.sendMessage(ConfigManager.MSG_CROP_CREATED
                .replace("%page%", String.valueOf(page))
                .replace("%slot%", String.valueOf(slot))
                .replace("%replant%", String.valueOf(planted)));
        // 创建后跳转到对应农田页查看
        gui.openFarm(player, globalIndex / ConfigManager.FARM_PAGE_SLOTS);
    }

    /** 骨粉页数叠加解锁：/xlr crop gufen update <玩家ID> <页数>。 */
    private void gufenUpdate(Player sender, String targetName, String countStr) {
        int delta;
        try {
            delta = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c解锁页数必须为数字。");
            return;
        }
        if (delta <= 0) {
            sender.sendMessage("§c解锁页数必须大于 0。");
            return;
        }
        Player online = Bukkit.getPlayerExact(targetName);
        UUID targetUuid;
        if (online != null) {
            targetUuid = online.getUniqueId();
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(targetName);
            if (op == null) {
                sender.sendMessage(ConfigManager.MSG_PLAYER_NOT_FOUND.replace("%player%", targetName));
                return;
            }
            targetUuid = op.getUniqueId();
        }
        int current = db.getUnlockedPages(targetUuid);
        int total = Math.max(1, current) + delta;
        db.setUnlockedPages(targetUuid, total);
        sender.sendMessage(ConfigManager.MSG_GFUEN_UPDATE_DONE
                .replace("%player%", targetName)
                .replace("%count%", String.valueOf(delta))
                .replace("%total%", String.valueOf(total)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("farm", "crop"), args[0]);
        }
        if ("crop".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return filter(List.of("create", "farm", "gui", "bone", "menu", "gufen"), args[1]);
            }
            if (args.length == 3 && "create".equalsIgnoreCase(args[1])) {
                // 创建指令只接受英文作物 id
                return filter(new ArrayList<>(CropRegistry.all().keySet()), args[2]);
            }
            if (args.length == 3 && "gufen".equalsIgnoreCase(args[1])) {
                return filter(List.of("update"), args[2]);
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
