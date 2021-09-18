package me.bkrmt.bkcore.input;

import me.bkrmt.bkcore.BkPlugin;
import me.bkrmt.bkcore.bkgui.page.Page;
import me.bkrmt.bkcore.bkgui.page.PageUtils;
import me.fixeddev.ezchat.event.AsyncEzChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerInput {
    private static final ConcurrentHashMap<UUID, PlayerInput> inputs = new ConcurrentHashMap<>();
    private final UUID uuid;

    private final BkPlugin plugin;
    private final InputRunnable runGo;
    private final InputRunnable runCancel;
    private InputRunnable runTimeout;
    private BukkitTask taskId;
    private List<Listener> listeners;
    private final boolean inputMode;
    private String title;
    private boolean acceptSlash;
    private String subtitle;
    private final Player player;
    private final Page previousPage;
    private int timeout;
    private boolean isCancellable;

    public PlayerInput(BkPlugin plugin, Player player, Page previousPage, InputRunnable correct, InputRunnable cancel) {
        this.plugin = plugin;
        this.player = player;
        this.isCancellable = true;
        this.acceptSlash = false;
        this.uuid = player.getUniqueId();
        this.inputMode = true;
        this.previousPage = previousPage;
        this.runGo = correct;
        this.runCancel = cancel;
        this.listeners = new ArrayList<>();
        this.runTimeout = null;
        this.timeout = 0;
        this.title = "§4Enter the value";
        this.subtitle = "§cType 'cancel' to cancel";
    }

    public PlayerInput setCancellable(boolean cancellable) {
        this.isCancellable = cancellable;
        return this;
    }

    public PlayerInput setTitle(String title) {
        this.title = title;
        return this;
    }

    public PlayerInput setSubTitle(String subtitle) {
        this.subtitle = subtitle;
        return this;
    }

    public PlayerInput setTimeout(int timeout, InputRunnable runTimeout) {
        this.runTimeout = runTimeout;
        this.timeout = timeout;
        return this;
    }

    public boolean isAcceptSlash() {
        return acceptSlash;
    }

    public PlayerInput setAcceptSlash(boolean acceptSlash) {
        this.acceptSlash = acceptSlash;
        return this;
    }

    public void sendInput() {
        player.closeInventory();
        this.taskId = new BukkitRunnable() {
            private int timesRun = 0;
            private boolean first = true;
            private final String actionBarMessage = plugin.getLangFile().get("info.input.countdown");

            public void run() {
                if (timeout > 0) {
                    if (timesRun >= timeout) {
                        PlayerInput current = inputs.get(uuid);
                        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> current.runTimeout.run(""), 3);
                        current.unregister();
                        return;
                    }
                }
                int fade = 11;
                int stay = 10;
                if (first) first = false;
                else {
                    fade = 0;
                    stay = 25;
                }
                plugin.sendTitle(
                        player,
                        fade, stay, 0,
                        title,
                        subtitle
                );
                if (timeout > 0) {
                    plugin.sendActionBar(player, actionBarMessage.replace("{seconds}", String.valueOf(timeout - timesRun)));
                    timesRun++;
                }
            }
        }.runTaskTimer(this.plugin, 0, 20);
        this.listeners.add(new Listener() {
            @EventHandler(ignoreCancelled = true)
            public void onLeave(PlayerQuitEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (previousPage != null) {
                    if (previousPage.getGuiSettings().getPageWipeLeaveResponse() != null)
                        previousPage.getGuiSettings().getPageWipeLeaveResponse().event(event);
                    PageUtils.wipeLinkedPages(previousPage);
                }
                if (inputs.containsKey(uuid)) {
                    PlayerInput current = inputs.get(uuid);
                    if (current.inputMode) {
                        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> current.runCancel.run(""), 3);
                        current.unregister();
                    }
                }
            }

            @EventHandler(priority = EventPriority.HIGHEST)
            public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (inputs.containsKey(uuid)) {
                    PlayerInput current = inputs.get(uuid);
                    if (current.inputMode) {
                        event.setCancelled(true);
                        if (acceptSlash) {
                            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> current.runGo.run(event.getMessage()), 3);
                            current.unregister();
                        } else {
                            event.getPlayer().sendMessage(plugin.getLangFile().get("error.input.awaiting-input").replace("{cancel-input}", plugin.getConfigManager().getConfig().getString("cancel-input")));
                        }
                    }
                }
            }

            @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
            public void onPlayerChat(AsyncPlayerChatEvent event) {
                defaultChat(event);
            }
        });

        Plugin ezChat = Bukkit.getPluginManager().getPlugin("EzChat");
        if (ezChat != null && ezChat.isEnabled()) {
            listeners.add(new Listener() {
                @EventHandler
                public void onPlayerChat(AsyncEzChatEvent event) {
                    ezChat(event);
                }
            });
        }

        this.register();
    }

    private void defaultChat(AsyncPlayerChatEvent event) {
        String input = event.getMessage();
        UUID uuid = event.getPlayer().getUniqueId();
        if (inputs.containsKey(uuid)) {
            PlayerInput current = inputs.get(uuid);
            if (current.inputMode) {
                event.setCancelled(true);
                processMessage(input, current);
            }
        }
    }

    private void ezChat(AsyncEzChatEvent event) {
        String input = event.getMessage();
        UUID uuid = event.getPlayer().getUniqueId();
        if (inputs.containsKey(uuid)) {
            PlayerInput current = inputs.get(uuid);
            if (current.inputMode) {
                event.setCancelled(true);
                processMessage(input, current);
            }
        }
    }

    private void processMessage(String input, PlayerInput current) {
        try {
            if (isCancellable && input.equalsIgnoreCase(plugin.getConfigManager().getConfig().getString("cancel-input"))) {
                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> current.runCancel.run(input), 3);
                current.unregister();
                return;
            }
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> current.runGo.run(input), 3);
            current.unregister();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void register() {
        for (Listener listener : listeners) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
        inputs.put(this.uuid, this);
    }

    private void unregister() {
        plugin.sendTitle(player, 0, 1, 0, "", "");
        plugin.sendActionBar(player, "");
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        inputs.get(uuid).taskId.cancel();
        inputs.remove(this.uuid);
    }
}