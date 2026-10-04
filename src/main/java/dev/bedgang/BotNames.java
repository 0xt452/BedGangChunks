package dev.bedgang;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class BotNames {
    static final String DEFAULT = "t4xan BedGang";
    static Component render(String name) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(name);
    }
    static String error(String name) {
        if (name.length() > 128) return "Name must be at most 128 characters including color codes.";
        if (name.codePoints().anyMatch(Character::isISOControl)) return "Name cannot contain control characters.";
        String plain = PlainTextComponentSerializer.plainText().serialize(render(name));
        if (plain.isBlank()) return "Name must contain visible text, not just color codes.";
        if (plain.codePointCount(0, plain.length()) > 48) return "Name must be at most 48 visible characters.";
        return null;
    }
    static String fromRow(Map<?, ?> row) {
        Object value = row.get("name");
        return value instanceof String name && error(name) == null ? name : DEFAULT;
    }
}
