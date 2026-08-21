package xlingran.com.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济封装。
 *
 * <p>运行时通过 Bukkit 服务管理器获取 Vault Economy；未安装 Vault 时 {@link #isEnabled()} 返回 false，
 * 骨粉页解锁功能将不可用（其余功能不受影响）。
 */
public final class EconomyManager {

    private final Economy economy;

    public EconomyManager() {
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        this.economy = rsp == null ? null : rsp.getProvider();
    }

    public boolean isEnabled() {
        return economy != null;
    }

    public boolean has(Player player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    /** 尝试扣款，返回是否成功。 */
    public boolean withdraw(Player player, double amount) {
        return economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
    }
}
