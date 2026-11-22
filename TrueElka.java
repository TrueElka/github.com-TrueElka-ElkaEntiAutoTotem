package ma.trueelka;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TrueElka - detects AutoTotem.
 *
 * Idea: after a totem pop the offhand is empty. A real player needs reaction time
 * (see the pop, open inventory / press F, click) which is always > ~100ms after
 * subtracting ping. AutoTotem refills the offhand almost instantly.
 * We measure the time between the pop and the next totem placed in the offhand.
 */
public class TrueElka extends JavaPlugin implements Listener {

    private final Map<UUID, Long> popTime = new HashMap<>();
    private final Map<UUID, Double> violations = new HashMap<>();

    private double minDelayMs;
    private double vlPerFlag;
    private double decayPerMinute;
    private int punishVl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);

        // VL decay every minute
        new BukkitRunnable() {
            @Override
            public void run() {
                violations.replaceAll((id, vl) -> Math.max(0, vl - decayPerMinute));
                violations.values().removeIf(v -> v <= 0);
            }
        }.runTaskTimer(this, 1200L, 1200L);

        getLogger().info("TrueElka enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        minDelayMs = getConfig().getDouble("min-delay-ms", 110);
        vlPerFlag = getConfig().getDouble("vl-per-flag", 1.0);
        decayPerMinute = getConfig().getDouble("vl-decay-per-minute", 0.5);
        punishVl = getConfig().getInt("punish-vl", 0);
    }

    private boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }

    // 1) A totem popped: start the timer
    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player)) return;
        if (player.hasPermission("trueelka.bypass")) return;

        final UUID id = player.getUniqueId();
        popTime.put(id, System.nanoTime());

        // Fallback: if nothing triggers an event, watch the offhand for 10 ticks.
        // This catches AutoTotem implementations that don't fire a click/swap event.
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !popTime.containsKey(id) || ++ticks > 10) {
                    cancel();
                    return;
                }
                if (isTotem(player.getInventory().getItemInOffHand())) {
                    check(player, "offhand-poll", System.nanoTime());
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // 2) Player moved a totem to offhand via the inventory
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!popTime.containsKey(player.getUniqueId())) return;

        boolean toOffhand =
                (event.getClick() == ClickType.SWAP_OFFHAND && isTotem(event.getCurrentItem()))
                || (event.getSlot() == 40 && isTotem(event.getCursor()))
                || (event.getSlot() == 40 && event.getClick() == ClickType.NUMBER_KEY
                    && isTotem(player.getInventory().getItem(event.getHotbarButton())));
        if (!toOffhand) return;

        final long when = System.nanoTime();
        // Check next tick, once the item has really moved
        Bukkit.getScheduler().runTask(this, () -> {
            if (isTotem(player.getInventory().getItemInOffHand())) {
                check(player, "inventory-click", when);
            }
        });
    }

    // 3) Player pressed F (swap hands) with a totem in main hand
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!popTime.containsKey(player.getUniqueId())) return;
        if (isTotem(event.getOffHandItem())) { // item that goes to offhand
            check(player, "hand-swap", System.nanoTime());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        popTime.remove(event.getPlayer().getUniqueId());
    }

    private void check(Player player, String source, long nowNanos) {
        Long pop = popTime.remove(player.getUniqueId());
        if (pop == null) return;

        double delayMs = (nowNanos - pop) / 1_000_000.0;
        int ping = player.getPing();
        double adjusted = delayMs - ping; // remove network round-trip

        if (adjusted >= minDelayMs) return; // looks human

        double add = vlPerFlag + (adjusted < minDelayMs / 3 ? 1.0 : 0.0);
        double vl = violations.merge(player.getUniqueId(), add, Double::sum);

        String msg = getConfig().getString("alert-message", "&c%player% AutoTotem")
                .replace("%player%", player.getName())
                .replace("%delay%", String.format("%.0f", delayMs))
                .replace("%ping%", String.valueOf(ping))
                .replace("%vl%", String.format("%.1f", vl))
                .replace("%source%", source);
        msg = ChatColor.translateAlternateColorCodes('&', msg);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("trueelka.alerts")) staff.sendMessage(msg);
        }
        getLogger().info(ChatColor.stripColor(msg));

        if (punishVl > 0 && vl >= punishVl) {
            violations.remove(player.getUniqueId());
            for (String cmd : getConfig().getStringList("punish-commands")) {
                String c = ChatColor.translateAlternateColorCodes('&',
                        cmd.replace("%player%", player.getName()));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage(ChatColor.GREEN + "TrueElka config reloaded.");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("vl")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            sender.sendMessage(ChatColor.GRAY + target.getName() + " VL: " + ChatColor.RED
                    + String.format("%.1f", violations.getOrDefault(target.getUniqueId(), 0.0)));
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "/trueelka reload | /trueelka vl <player>");
        return true;
    }
}
