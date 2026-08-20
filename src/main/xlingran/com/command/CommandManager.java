package xlingran.com.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xlingran.com.config.ConfigManager;
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
 *   <li>/xlr farm —— 打开农田 GUI（第 1 页）</li>
 *   <li>/xlr crop —— 打开农作物仓库 GUI</li>
 *   <li>/xlr crop wheat —— 创建小麦农田（跨页分配第一个空位，全部页满则提示）</li>
 * </ul>
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private final DatabaseManager db;
    private final GuiManager gui;

    public CommandManager(DatabaseManager db, GuiManager gui) {
        this.db = db;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该指令仅限玩家使用。");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§e用法: /xlr <farm|crop|crop <作物类型>>");
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
            case "crop" -> {
                if (args.length == 1) {
                    if (!player.hasPermission("xlr.crop")) {
                        player.sendMessage(ConfigManager.MSG_NO_PERM);
                        return true;
                    }
                    gui.openCropMenu(player);
                } else {
                    if (!player.hasPermission("xlr.crop.create")) {
                        player.sendMessage(ConfigManager.MSG_NO_PERM);
                        return true;
                    }
                    createCrop(player, uuid, args[1]);
                }
            }
            default -> player.sendMessage("§c未知子指令，用法: /xlr <farm|crop|crop <作物类型>>");
        }
        return true;
    }

    private void createCrop(Player player, UUID uuid, String typeId) {
        CropType ct = CropRegistry.get(typeId);
        if (ct == null) {
            player.sendMessage("§c未知作物类型: " + typeId + " §7（当前支持: " + String.join("、", CropRegistry.all().keySet()) + "）");
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
        if (args.length == 2 && "crop".equalsIgnoreCase(args[0])) {
            return filter(new ArrayList<>(CropRegistry.all().keySet()), args[1]);
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
