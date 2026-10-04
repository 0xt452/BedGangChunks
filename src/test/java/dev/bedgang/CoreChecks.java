package dev.bedgang;

import java.util.*;

/** Run with java -ea; no running Minecraft server required. */
public final class CoreChecks {
    public static void main(String[] args) {
        UUID owner = UUID.randomUUID(), world = UUID.randomUUID();
        var zero = new BedGangChunks.Loader(owner, 1, world, -0.1, 64, -16.1, 0, 0);
        assert zero.cx() == -1 && zero.cz() == -2 : "Negative coordinate flooring";
        assert zero.chunks().equals(List.of(new BedGangChunks.ChunkKey(world, -1, -2)));
        for (int r = 0; r <= 16; r++) {
            var l = new BedGangChunks.Loader(owner, 1, world, 0, 64, 0, 0, r);
            assert l.chunks().size() == (2*r+1)*(2*r+1) : "Radius coverage";
            assert new HashSet<>(l.chunks()).size() == l.chunks().size() : "No duplicate chunks";
        }
        var left = new BedGangChunks.Loader(owner, 1, world, 0, 64, 0, 0, 1);
        var right = new BedGangChunks.Loader(owner, 2, world, 16, 64, 0, 0, 1);
        TicketReferences<BedGangChunks.ChunkKey> refs = new TicketReferences<>();
        Set<BedGangChunks.ChunkKey> tickets = new HashSet<>();
        for (var l : List.of(left, right)) for (var k : l.chunks()) if (refs.retain(k)) tickets.add(k);
        assert tickets.size() == 12 : "Overlapping regions share six tickets";
        for (var k : left.chunks()) if (refs.release(k)) tickets.remove(k);
        assert tickets.equals(new HashSet<>(right.chunks())) : "Removing left preserves right";
        for (var k : right.chunks()) if (refs.release(k)) tickets.remove(k);
        assert tickets.isEmpty() && refs.keys().isEmpty() : "Final removal releases all";
        assert !refs.release(right.chunks().getFirst()) : "Repeated release is harmless";
        var otherWorld = new BedGangChunks.ChunkKey(UUID.randomUUID(), 0, 0);
        var thisWorld = new BedGangChunks.ChunkKey(world, 0, 0);
        assert refs.retain(otherWorld) && refs.retain(thisWorld) && refs.keys().size() == 2;
        refs.clear(); assert refs.keys().isEmpty();
        assert BotNames.render("&4&lBedgang").equals(net.kyori.adventure.text.Component.text("Bedgang")
            .color(net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)) : "Dark red bold name";
        assert BotNames.error("&4&l") != null : "Reject formatting-only names";
        assert BotNames.error("x".repeat(49)) != null : "Visible length limit";
        assert BotNames.error("&aFarm &rOne") == null : "Spaces and reset accepted";
        assert BotNames.fromRow(Map.of()).equals(BotNames.DEFAULT) : "Migrate existing loaders";
        var renamed = left.withName("&4&lBedgang");
        assert renamed.id().equals(left.id()) && renamed.chunks().equals(left.chunks()) : "Rename preserves chunk ownership";
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("loaders", List.of(Map.of("name", renamed.name())));
        var restored = new org.bukkit.configuration.file.YamlConfiguration();
        try { restored.loadFromString(yaml.saveToString()); }
        catch (Exception ex) { throw new AssertionError(ex); }
        assert BotNames.fromRow(restored.getMapList("loaders").getFirst()).equals("&4&lBedgang") : "Persist literal color codes";
        System.out.println("PASS: colored bold names, validation, old-data migration, rename identity, YAML name round trip.");
        System.out.println("PASS: radius 0-16, negative coordinates, overlap removal, repeated release, world isolation, cleanup.");
    }
}
