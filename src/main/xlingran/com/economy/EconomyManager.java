package xlingran.com.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济封装。
 *
 * <p>每次调用动态探测 Vault 服务（支持 Vault 晚于本插件加载的场景，无需重载插件）；
 * 未安装 Vault 时 {@link #isEnabled()} 返回 false，骨粉页解锁功能将不可用（其余功能不受影响）。
 */
public final class EconomyManager {

    private Economy economy;

    public EconomyManager() {
        // 不在构造时探测：Vault 可能晚于本插件加载，改为首次调用时获取并缓存
    }

    private Economy current() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        }
        return economy;
    }

    public boolean isEnabled() {
        return current() != null;
    }

    public boolean has(Player player, double amount) {
        Economy e = current();
        return e != null && e.has(player, amount);
    }

    /** 尝试扣款，返回是否成功。 */
    public boolean withdraw(Player player, double amount) {
        Economy e = current();
        return e != null && e.withdrawPlayer(player, amount).transactionSuccess();
    }

    /** 尝试退款，返回是否成功（扣款成功但落库失败时回滚用）。 */
    public boolean deposit(Player player, double amount) {
        Economy e = current();
        return e != null && e.depositPlayer(player, amount).transactionSuccess();
    }

    /** 查询在线玩家余额。 */
    public double getBalance(Player player) {
        Economy e = current();
        return e == null ? 0 : e.getBalance(player);
    }

    /** 查询离线玩家余额（需经济插件支持离线查询）。 */
    public double getBalance(OfflinePlayer player) {
        Economy e = current();
        return e == null ? 0 : e.getBalance(player);
    }

    /** 离线账户是否可查询（Vault 标准方法 hasAccount，启动恢复经济操作时用）。 */
    public boolean hasAccount(OfflinePlayer player) {
        Economy e = current();
        return e != null && e.hasAccount(player);
    }
}
