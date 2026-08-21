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
 *   <li>/xlr crop create [&lt;名称&gt;] —— 打开创建农田 GUI / 直接创建农田</li>
 *   <li>/xlr crop farm —— 打开农田 GUI</li>
 *   <li>/xlr crop gui —— 打开农作物仓库 GUI</li>
 *   <li>/xlr crop bone —— 打开骨粉储存器 GUI</li>
 *   <li>/xlr crop menu —— 打开主菜单 GUI</li>
 *   <li>/xlr crop update bone &lt;玩家ID&gt; &lt;解锁页数&gt; —— 骨粉页数叠加解锁（管理员）</li>
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
            player.sendMessage("§e用法: /xlr crop [create|farm|gui|bone|menu|update]");
            return true;
        }
        UUID uuid = player.getUniqueId();
        // 仅保留 /xlr crop 前缀（旧 /xlr farm 等已删除）
        if (!"crop".equalsIgnoreCase(args[0])) {
            player.sendMessage("§c未知子指令，用法: /xlr crop [create|farm|gui|bone|menu|update]");
            return true;
        }
        handleCrop(player, uuid, args);
        return true;
    }

    private void handleCrop(Player player, UUID uuid, String[] args) {
        if (args.length == 1) {
            player.sendMessage("§e用法: /xlr crop [create|farm|gui|bone|menu|update]");
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
            case "update" -> {
                if (!player.hasPermission("xlr.admin")) {
                    player.sendMessage(ConfigManager.MSG_NO_PERM);
                    return;
                }
                if (args.length < 5 || !"bone".equalsIgnoreCase(args[2])) {
                    player.sendMessage("§c用法: /xlr crop update bone <玩家ID> <解锁页数>");
                    return;
                }
                updateBonemealPages(player, args[3], args[4]);
            }
            default -> player.sendMessage("§c未知 crop 子指令，用法: /xlr crop [create|farm|gui|bone|menu|update]");
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

    /** 骨粉页数叠加解锁：/xlr crop update bone <玩家ID> <页数>。 */
    private void updateBonemealPages(Player sender, String targetName, String countStr) {
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
            // 按名直接取（O(1)），用 hasPlayedBefore 过滤从未上过线的虚构 UUID，避免写脏数据
            OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
            if (!op.hasPlayedBefore()) {
                sender.sendMessage(ConfigManager.MSG_PLAYER_NOT_FOUND.replace("%player%", targetName));
                return;
            }
            targetUuid = op.getUniqueId();
        }
        int current = db.getUnlockedPages(targetUuid);
        int total = Math.max(1, current) + delta;
        if (!db.setUnlockedPages(targetUuid, total)) {
            sender.sendMessage(ConfigManager.MSG_DB_ERROR);
            return;
        }
        sender.sendMessage(ConfigManager.MSG_GFUEN_UPDATE_DONE
                .replace("%player%", targetName)
                .replace("%count%", String.valueOf(delta))
                .replace("%total%", String.valueOf(total)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("crop"), args[0]);
        }
        if ("crop".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return filter(List.of("create", "farm", "gui", "bone", "menu", "update"), args[1]);
            }
            if (args.length == 3 && "create".equalsIgnoreCase(args[1])) {
                // 创建指令只接受英文作物 id
                return filter(new ArrayList<>(CropRegistry.all().keySet()), args[2]);
            }
            if (args.length == 3 && "update".equalsIgnoreCase(args[1])) {
                return filter(List.of("bone"), args[2]);
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
