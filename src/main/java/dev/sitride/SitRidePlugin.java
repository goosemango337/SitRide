package dev.sitride;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SitRidePlugin extends JavaPlugin implements Listener, TabExecutor {

    /** One active seat: the invisible armor stand, where to put the player afterwards, and the block it sits on. */
    private record Seat(ArmorStand stand, Location exit, Location block) {}

    private final Map<UUID, Seat> seats = new HashMap<>();
    private double seatOffset;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        seatOffset = getConfig().getDouble("seat-height-offset", -0.2);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sit").setExecutor(this);
        getCommand("piggyback").setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Seat> en : new ArrayList<>(seats.entrySet())) {
            Player p = getServer().getPlayer(en.getKey());
            if (p != null) p.teleport(en.getValue().exit());
            en.getValue().stand().remove();
        }
        seats.clear();
    }

    // ------------------------------------------------------------------ sitting

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (p.isSneaking() || p.isInsideVehicle()) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        if (!p.hasPermission("sitride.sit")) return;

        double surface;   // height of the sitting surface inside the block
        double exitY;     // where the player is put when they stand up
        BlockData data = b.getBlockData();
        if (data instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM) {
            surface = 0.5;
            exitY = 1.0;
        } else if (data instanceof Slab s && s.getType() == Slab.Type.BOTTOM) {
            surface = 0.5;
            exitY = 0.5;
        } else if (b.getType().name().endsWith("_CARPET")) {
            surface = 0.0625;
            exitY = 0.0625;
        } else {
            return;
        }

        if (!b.getRelative(BlockFace.UP).isPassable()) return;
        for (Seat s : seats.values()) {
            if (s.block().equals(b.getLocation())) return; // already taken
        }

        Location seatLoc = b.getLocation().add(0.5, surface + seatOffset, 0.5);
        Location exit = b.getLocation().add(0.5, exitY, 0.5);
        if (sit(p, seatLoc, exit, b.getLocation())) {
            e.setCancelled(true);
        }
    }

    private boolean sit(Player p, Location seatLoc, Location exit, Location block) {
        ArmorStand stand = seatLoc.getWorld().spawn(seatLoc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false); // never saved to disk
            as.setBasePlate(false);
        });
        if (!stand.addPassenger(p)) {
            stand.remove();
            return false;
        }
        seats.put(p.getUniqueId(), new Seat(stand, exit, block));
        return true;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Seat seat = seats.get(p.getUniqueId());
        if (seat == null || !seat.stand().equals(e.getDismounted())) return;
        seats.remove(p.getUniqueId());

        Location standLoc = seat.stand().getLocation();
        Location exit = seat.exit().clone();
        getServer().getScheduler().runTask(this, () -> {
            seat.stand().remove();
            // Only move them if they weren't teleported far away (e.g. by /tp)
            if (p.isOnline() && p.getWorld().equals(standLoc.getWorld())
                    && p.getLocation().distanceSquared(standLoc) < 9) {
                exit.setYaw(p.getLocation().getYaw());
                exit.setPitch(p.getLocation().getPitch());
                p.teleport(exit);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Seat seat = seats.remove(e.getPlayer().getUniqueId());
        if (seat != null) {
            e.getPlayer().teleport(seat.exit());
            seat.stand().remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Location loc = e.getBlock().getLocation();
        for (Map.Entry<UUID, Seat> en : new ArrayList<>(seats.entrySet())) {
            if (en.getValue().block().equals(loc)) {
                Player p = getServer().getPlayer(en.getKey());
                if (p != null) p.leaveVehicle(); // triggers onDismount
            }
        }
    }

    // ----------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "Only players can use this command.", NamedTextColor.RED);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("sit")) {
            sitCommand(p);
        } else {
            piggybackCommand(p, args);
        }
        return true;
    }

    private void sitCommand(Player p) {
        if (!p.hasPermission("sitride.sit")) {
            msg(p, "You don't have permission.", NamedTextColor.RED);
            return;
        }
        if (p.isInsideVehicle()) {
            msg(p, "You are already sitting or riding something.", NamedTextColor.RED);
            return;
        }
        if (!p.isOnGround()) {
            msg(p, "You can't sit in mid-air.", NamedTextColor.RED);
            return;
        }
        Location loc = p.getLocation();
        Location seatLoc = loc.clone().add(0, seatOffset, 0);
        if (!sit(p, seatLoc, loc, loc.getBlock().getLocation())) {
            msg(p, "You can't sit here.", NamedTextColor.RED);
        }
    }

    private void piggybackCommand(Player p, String[] args) {
        if (!p.hasPermission("sitride.piggyback")) {
            msg(p, "You don't have permission.", NamedTextColor.RED);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("off")) {
            if (p.isInsideVehicle()) {
                p.leaveVehicle();
                msg(p, "You got off.", NamedTextColor.YELLOW);
            } else {
                msg(p, "You aren't riding anything.", NamedTextColor.RED);
            }
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("drop")) {
            if (p.getPassengers().isEmpty()) {
                msg(p, "Nobody is riding you.", NamedTextColor.RED);
            } else {
                new ArrayList<>(p.getPassengers()).forEach(Entity::leaveVehicle);
                msg(p, "You dropped your passengers.", NamedTextColor.YELLOW);
            }
            return;
        }

        Entity target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                msg(p, "Player not found.", NamedTextColor.RED);
                return;
            }
        } else {
            target = p.getTargetEntity(5);
            if (target == null) {
                msg(p, "Look at a player or mob (within 5 blocks), or use /piggyback <player>.", NamedTextColor.RED);
                return;
            }
        }

        if (target.equals(p)) {
            msg(p, "You can't ride yourself.", NamedTextColor.RED);
            return;
        }
        if (target instanceof Player tp && tp.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            msg(p, "You can't ride a spectator.", NamedTextColor.RED);
            return;
        }
        // Prevent loops (A rides B while B already rides A, directly or through a chain)
        for (Entity cur = target; cur != null; cur = cur.getVehicle()) {
            if (cur.equals(p)) {
                msg(p, "You can't ride someone who is riding you.", NamedTextColor.RED);
                return;
            }
        }

        if (p.isInsideVehicle()) p.leaveVehicle();
        if (target.addPassenger(p)) {
            msg(p, "You are now riding " + target.getName() + ". Sneak (Shift) to get off.", NamedTextColor.GREEN);
            if (target instanceof Player tp) {
                msg(tp, p.getName() + " is riding you. Use /piggyback drop to throw them off.", NamedTextColor.YELLOW);
            }
        } else {
            msg(p, "You can't ride that.", NamedTextColor.RED);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("piggyback") || args.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("off", "drop"));
        getServer().getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        String prefix = args[0].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(prefix));
        return out;
    }

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }
}
