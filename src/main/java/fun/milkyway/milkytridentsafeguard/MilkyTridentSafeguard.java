package fun.milkyway.milkytridentsafeguard;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MilkyTridentSafeguard extends JavaPlugin implements Listener {
    private static final double MAX_DISTANCE = 60.0;
    private static final Component MESSAGE_TRIDENT_NO_SPACE
            = MiniMessage.miniMessage().deserialize("<bold><#FF8E00>!!! </bold><#FFDB65>Освободите место для трезубца в инвертаре!");

    private NamespacedKey tridentOwnerKey;
    private List<Trident> tridentList;

    private LoadingCache<UUID, UUID> messageCache;

    @Override
    public void onEnable() {
        messageCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.SECONDS).build(new CacheLoader<UUID, UUID>() {
            @Override
            public @NotNull UUID load(@NotNull UUID key) {
                return key;
            }
        });
        tridentOwnerKey = new NamespacedKey(this, "trident_owner");
        tridentList = new LinkedList<>();
        getServer().getPluginManager().registerEvents(this, this);
        startTridentTask();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    //listens for trident use which is enchanted with loyalty
    @EventHandler
    public void onTridentUse(PlayerInteractEvent event) {
        if (event.getHand() == null || !event.getHand().equals(EquipmentSlot.HAND)) {
            return;
        }
        var item = event.getPlayer().getInventory().getItemInMainHand();

        if (!item.getType().equals(Material.TRIDENT)) {
            return;
        }
        var itemMeta = item.getItemMeta();

        if (!hasLoyalty(item)) {
            removeOwner(itemMeta.getPersistentDataContainer(), event.getPlayer());
            return;
        }

        applyOwner(itemMeta.getPersistentDataContainer(), event.getPlayer());
        item.setItemMeta(itemMeta);
    }

    @EventHandler(ignoreCancelled = true)
    public void tridentAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }
        if (!isTrackable(trident)) {
            return;
        }

        tridentList.add(trident);
    }

    @EventHandler(ignoreCancelled = true)
    public void tridentRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }

        if (!isTrackable(trident)) {
            return;
        }

        tridentList.remove(trident);
        getLogger().info("Trident removed from " + trident.getWorld().getName() + " at: " + trident.getLocation().getBlockX() + " " + trident.getLocation().getBlockY() + " " + trident.getLocation().getBlockZ());
    }

    @EventHandler
    public void tridentEnterPortal(EntityPortalEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }

        event.setCancelled(true);
    }

    private void startTridentTask() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            var iterator = tridentList.iterator();
            while (iterator.hasNext()) {
                var trident = iterator.next();

                if (trident.isDead() || !trident.isValid()) {
                    iterator.remove();
                    continue;
                }
                var player = shouldBeReturned(trident);

                if (player != null && giveTrident(player, trident.getItem())) {
                    getLogger().info("Returned trident to " + player.getName());
                    iterator.remove();
                    trident.remove();
                }
            }
        }, 0, 1);
    }

    private boolean isTrackable(@NotNull Trident trident) {
        var item = trident.getItem();

        if (!hasLoyalty(item)) {
            return false;
        }

        var owner = getOwner(item.getItemMeta().getPersistentDataContainer());

        if (owner == null) {
            return false;
        }

        return true;
    }

    private boolean hasLoyalty(@NotNull ItemStack item) {
        return item.getEnchantments().containsKey(Enchantment.LOYALTY);
    }

    private boolean giveTrident(@NotNull Player player, @NotNull ItemStack item) {
        var giveResult = player.getInventory().addItem(item);
        player.updateInventory();
        var result = giveResult.isEmpty();
        if (!result && messageCache.getIfPresent(player.getUniqueId()) == null) {
            player.sendMessage(MESSAGE_TRIDENT_NO_SPACE);
            messageCache.put(player.getUniqueId(), player.getUniqueId());
        }
        return result;
    }

    private @Nullable Player shouldBeReturned(@NotNull Trident trident) {
        var item = trident.getItem();

        var owner = getOwner(item.getItemMeta().getPersistentDataContainer());

        if (owner == null) {
            //getLogger().info("Trident has no owner");
            return null;
        }

        var player = getServer().getPlayer(owner);

        if (player == null ||
                !player.isOnline() ||
                !player.isValid() ||
                player.isDead() ||
                player.getGameMode().equals(GameMode.SPECTATOR)||
                player.getGameMode().equals(GameMode.CREATIVE)) {
            //getLogger().info("Trident owner is offline or dead");
            return null;
        }

        if (!player.getWorld().equals(trident.getWorld())) {
            //getLogger().info("Trident owner is in different world");
            return player;
        }

        if (trident.getLocation().getY() < trident.getWorld().getMinHeight() - 8) {
            //getLogger().info("Trident is too low");
            return player;
        }

        if (projectionDistanceSquared(player.getLocation(), trident.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
            //getLogger().info("Trident owner is too far away");
            return player;
        }

        //getLogger().info("Trident is not eligible for return");
        return null;
    }

    private double projectionDistanceSquared(@NotNull Location location1, @NotNull Location location2) {
        var x = location1.getX() - location2.getX();
        var z = location1.getZ() - location2.getZ();
        return x * x + z * z;
    }

    private void applyOwner(@NotNull PersistentDataContainer persistentDataContainer, @NotNull Player player) {
        persistentDataContainer.set(tridentOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
    }

    private void removeOwner(@NotNull PersistentDataContainer persistentDataContainer, @NotNull Player player) {
        persistentDataContainer.remove(tridentOwnerKey);
    }

    private @Nullable UUID getOwner(@NotNull PersistentDataContainer persistentDataContainer) {
        var owner = persistentDataContainer.get(tridentOwnerKey, PersistentDataType.STRING);
        if (owner == null) {
            return null;
        }
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
