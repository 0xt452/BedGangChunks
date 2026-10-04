package dev.bedgang;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class BedGangChunks extends JavaPlugin implements Listener, TabCompleter {
    record ChunkKey(UUID world, int x, int z) {}
    record Loader(UUID owner, int slot, UUID world, double x, double y, double z,
                          float yaw, int radius, String name) {
        Loader(UUID owner, int slot, UUID world, double x, double y, double z, float yaw, int radius) {
            this(owner, slot, world, x, y, z, yaw, radius, BotNames.DEFAULT);
        }
        Loader withName(String name) { return new Loader(owner, slot, world, x, y, z, yaw, radius, name); }
        String id() { return owner + ":" + slot; }
        int cx() { return ((int) Math.floor(x)) >> 4; }
        int cz() { return ((int) Math.floor(z)) >> 4; }
        List<ChunkKey> chunks() {
            List<ChunkKey> result = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++)
                    result.add(new ChunkKey(world, cx() + dx, cz() + dz));
            return result;
        }
    }
    private final Map<String, Loader> loaders = new LinkedHashMap<>();
    private final TicketReferences<ChunkKey> references = new TicketReferences<>();
    private final Set<ChunkKey> applied = new HashSet<>();
    private final Queue<ChunkKey> pending = new ArrayDeque<>();
    private final Map<String, Mannequin> mannequins = new HashMap<>();
    private int maxRadius, batch;
    private File data;
    private Integer previousPauseTime;

    @Override public void onEnable() {
        saveDefaultConfig();
        maxRadius = Math.clamp(getConfig().getInt("max-radius", 4), 0, 16);
        batch = Math.clamp(getConfig().getInt("chunks-per-tick", 4), 1, 64);
        data = new File(getDataFolder(), "loaders.yml");
        try { readData(); }
        catch (Exception ex) {
            getLogger().severe("Cannot read loaders.yml; refusing to overwrite it: " + ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("bedgang")).setTabCompleter(this);
        loaders.values().forEach(this::retain);
        previousPauseTime = getServer().getPauseWhenEmptyTime();
        getServer().setPauseWhenEmptyTime(-1);
        getServer().getScheduler().runTaskTimer(this, this::loadBatch, 1, 1);
        getServer().getScheduler().runTaskTimer(this, this::repairMannequins, 20, 100);
        getLogger().info("Loaded " + loaders.size() + " loaders. Everyone can use /bedgang.");
    }

    @Override public void onDisable() {
        mannequins.values().forEach(Entity::remove);
        mannequins.clear();
        for (World world : Bukkit.getWorlds()) world.removePluginChunkTickets(this);
        applied.clear(); references.clear(); pending.clear();
        if (previousPauseTime != null && getServer().getPauseWhenEmptyTime() == -1)
            getServer().setPauseWhenEmptyTime(previousPauseTime);
    }

    private void retain(Loader l) {
        for (ChunkKey key : l.chunks()) {
            if (references.retain(key)) pending.add(key);
        }
    }

    private void release(Loader l) {
        Mannequin m = mannequins.remove(l.id());
        if (m != null) m.remove();
        for (ChunkKey key : l.chunks()) {
            if (references.release(key)) {
                if (applied.remove(key)) {
                    World w = Bukkit.getWorld(key.world);
                    if (w != null) w.removePluginChunkTicket(key.x, key.z, this);
                }
            }
        }
    }

    private void loadBatch() {
        for (int n = 0; n < batch && !pending.isEmpty(); n++) {
            ChunkKey key = pending.remove();
            if (!references.contains(key) || applied.contains(key)) continue;
            World world = Bukkit.getWorld(key.world);
            if (world == null) continue;
            try {
                world.addPluginChunkTicket(key.x, key.z, this);
                applied.add(key);
            } catch (RuntimeException ex) {
                getLogger().warning("Retrying chunk " + key + ": " + ex.getMessage());
                pending.add(key);
                break;
            }
        }
    }

    private void repairMannequins() {
        for (Loader l : loaders.values()) {
            World w = Bukkit.getWorld(l.world);
            if (w == null || !applied.contains(new ChunkKey(l.world, l.cx(), l.cz()))) continue;
            Mannequin old = mannequins.get(l.id());
            if (old != null && old.isValid()) continue;
            try {
                Mannequin m = w.spawn(new Location(w, l.x, l.y, l.z, l.yaw, 0), Mannequin.class, e -> {
                    e.customName(BotNames.render(l.name));
                    e.setCustomNameVisible(true);
                    e.setDescription(null);
                    e.setPersistent(false);
                    e.setInvulnerable(true);
                    e.setGravity(false);
                    e.setImmovable(true);
                    e.setSilent(true);
                    e.setCollidable(false);
                    if (getConfig().getBoolean("skin-resource-pack", true)) {
                        e.setProfile(ResolvableProfile.resolvableProfile()
                            .skinPatch(ResolvableProfile.SkinPatch.skinPatch()
                                .body(Key.key("bedgang", "entity/t4xan"))
                                .model(PlayerTextures.SkinModel.CLASSIC).build()).build());
                    }
                });
                mannequins.put(l.id(), m);
            } catch (RuntimeException ex) {
                getLogger().warning("Cannot spawn mannequin for " + l.id() + ": " + ex.getMessage());
            }
        }
    }

    @EventHandler(ignoreCancelled = true) public void damage(EntityDamageEvent event) {
        if (mannequins.containsValue(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEntityEvent event) {
        if (mannequins.containsValue(event.getRightClicked())) event.setCancelled(true);
    }
    @EventHandler public void worldLoad(WorldLoadEvent event) {
        references.keys().stream().filter(k -> k.world.equals(event.getWorld().getUID())).forEach(pending::add);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        mannequins.entrySet().removeIf(entry -> {
            if (!entry.getValue().getWorld().getUID().equals(id)) return false;
            entry.getValue().remove(); return true;
        });
        event.getWorld().removePluginChunkTickets(this);
        applied.removeIf(k -> k.world.equals(id));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0) { help(sender); return true; }
            if (args[0].equalsIgnoreCase("admin")) { admin(sender, args); return true; }
            if (!(sender instanceof Player p)) { sender.sendMessage("Use this in game, or /bedgang admin list."); return true; }
            UUID owner = p.getUniqueId();
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> {
                    if (args.length < 2) { help(sender); break; }
                    int radius = Integer.parseInt(args[1]);
                    if (radius < 0 || radius > maxRadius) {
                        sender.sendMessage("Radius must be 0-" + maxRadius + " chunks."); break;
                    }
                    int slot = 1;
                    while (slot <= 5 && loaders.containsKey(owner + ":" + slot)) slot++;
                    if (slot > 5) { sender.sendMessage("You already have 5 loaders. Remove one first."); break; }
                    Location at = p.getLocation();
                    Loader l = new Loader(owner, slot, p.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), at.getYaw(), radius);
                    if (args.length > 2) {
                        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                        String error = BotNames.error(name);
                        if (error != null) { sender.sendMessage(error); break; }
                        l = l.withName(name);
                    }
                    loaders.put(l.id(), l);
                    try { writeData(); } catch (IOException ex) { loaders.remove(l.id()); throw ex; }
                    retain(l);
                    sender.sendMessage("Created loader " + slot + ". Loading " + l.chunks().size()
                        + " chunks (radius " + radius + "). It stays active when you leave.");
                }
                case "list" -> list(sender, owner);
                case "rename" -> {
                    if (args.length < 3) { help(sender); break; }
                    int slot = Integer.parseInt(args[1]);
                    if (slot < 1 || slot > 5) { sender.sendMessage("Slot must be 1-5."); break; }
                    String id = owner + ":" + slot;
                    Loader old = loaders.get(id);
                    if (old == null) { sender.sendMessage("No loader in your slot " + slot + "."); break; }
                    String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    String error = BotNames.error(name);
                    if (error != null) { sender.sendMessage(error); break; }
                    loaders.put(id, old.withName(name));
                    try { writeData(); } catch (IOException ex) { loaders.put(id, old); throw ex; }
                    Mannequin m = mannequins.get(id);
                    if (m != null && m.isValid()) m.customName(BotNames.render(name));
                    sender.sendMessage(Component.text("Renamed loader #" + slot + " to ").append(BotNames.render(name)));
                }
                case "remove" -> {
                    if (args.length != 2) { help(sender); break; }
                    remove(sender, owner, args[1]);
                }
                default -> help(sender);
            }
        } catch (NumberFormatException ex) { sender.sendMessage("Enter a whole number in range."); }
        catch (IllegalArgumentException ex) { sender.sendMessage("Invalid player UUID or slot (use 1-5 or all)."); }
        catch (IOException ex) {
            sender.sendMessage("Could not save loader data; no change was made. Check server logs.");
            getLogger().severe("Saving loaders failed: " + ex);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("/bedgang spawn <0-" + maxRadius + "> [name] - radius in chunks, 1 = 3x3");
        s.sendMessage("/bedgang list | /bedgang remove <1-5|all>");
        s.sendMessage("/bedgang rename <1-5> <name> - supports & colors, e.g. &4&lBedgang");
        if (s.hasPermission("bedgang.admin")) s.sendMessage("/bedgang admin list | /bedgang admin remove <owner-uuid> <1-5|all>");
    }
    private void admin(CommandSender s, String[] args) throws IOException {
        if (!s.hasPermission("bedgang.admin")) { s.sendMessage("Admin permission required."); return; }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) list(s, null);
        else if (args.length == 4 && args[1].equalsIgnoreCase("remove")) remove(s, UUID.fromString(args[2]), args[3]);
        else help(s);
    }
    private void list(CommandSender s, UUID owner) {
        int found = 0;
        for (Loader l : loaders.values()) if (owner == null || owner.equals(l.owner)) {
            World w = Bukkit.getWorld(l.world);
            long ready = l.chunks().stream().filter(applied::contains).count();
            s.sendMessage(Component.text("#" + l.slot + " ").append(BotNames.render(l.name))
                .append(Component.text(" | " + (w == null ? l.world + " (unavailable)" : w.getName())
                + " @ " + (int)Math.floor(l.x) + ", " + (int)Math.floor(l.y) + ", " + (int)Math.floor(l.z)
                + " radius=" + l.radius + " ready=" + ready + "/" + l.chunks().size()
                + (owner == null ? " owner=" + l.owner : ""))));
            found++;
        }
        if (found == 0) s.sendMessage("No loaders found.");
    }
    private void remove(CommandSender s, UUID owner, String value) throws IOException {
        int slot = value.equalsIgnoreCase("all") ? 0 : Integer.parseInt(value);
        if (slot < 0 || slot > 5 || (slot == 0 && !value.equalsIgnoreCase("all"))) throw new IllegalArgumentException();
        List<Loader> removed = loaders.values().stream()
            .filter(l -> l.owner.equals(owner) && (slot == 0 || l.slot == slot)).toList();
        if (removed.isEmpty()) { s.sendMessage("No matching loader."); return; }
        removed.forEach(l -> loaders.remove(l.id()));
        try { writeData(); } catch (IOException ex) { removed.forEach(l -> loaders.put(l.id(), l)); throw ex; }
        removed.forEach(this::release);
        s.sendMessage("Removed " + removed.size() + " loader(s).");
    }
    private void readData() throws Exception {
        if (!data.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(data);
        for (Map<?, ?> row : yaml.getMapList("loaders")) {
            Loader l = new Loader(UUID.fromString(row.get("owner").toString()), ((Number)row.get("slot")).intValue(),
                UUID.fromString(row.get("world").toString()), ((Number)row.get("x")).doubleValue(),
                ((Number)row.get("y")).doubleValue(), ((Number)row.get("z")).doubleValue(),
                ((Number)row.get("yaw")).floatValue(), ((Number)row.get("radius")).intValue(), BotNames.fromRow(row));
            if (l.slot < 1 || l.slot > 5 || l.radius < 0 || l.radius > 16
                || !Double.isFinite(l.x) || !Double.isFinite(l.y) || !Double.isFinite(l.z)
                || !Float.isFinite(l.yaw) || Math.abs(l.x) > 30000000 || Math.abs(l.z) > 30000000
                || loaders.putIfAbsent(l.id(), l) != null) throw new IOException("Invalid/duplicate loader " + l.id());
        }
    }
    private void writeData() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Loader l : loaders.values()) rows.add(Map.of("owner", l.owner.toString(), "slot", l.slot,
            "world", l.world.toString(), "x", l.x, "y", l.y, "z", l.z, "yaw", l.yaw, "radius", l.radius, "name", l.name));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("loaders", rows);
        Path temp = data.toPath().resolveSibling("loaders.yml.tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        try { Files.move(temp, data.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, data.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) { options.addAll(List.of("spawn", "list", "remove", "rename")); if (s.hasPermission("bedgang.admin")) options.add("admin"); }
        if (args.length == 2 && args[0].equalsIgnoreCase("rename") && s instanceof Player p)
            loaders.values().stream().filter(l -> l.owner.equals(p.getUniqueId())).forEach(l -> options.add("" + l.slot));
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) for (int i = 0; i <= maxRadius; i++) options.add("" + i);
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) options.addAll(List.of("1", "2", "3", "4", "5", "all"));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(v -> v.startsWith(prefix)).toList();
    }
}
