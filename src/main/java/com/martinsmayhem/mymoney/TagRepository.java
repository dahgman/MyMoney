package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Reads and writes tags. Each tag has a unique name and a color. */
public class TagRepository {

    /** One tag, with how many transactions use it. */
    public record Tag(long id, String name, Color color, int usage) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** New tags get a color from this list that no other tag is using. */
    private static final String[] PALETTE = {
        "#E57373", "#64B5F6", "#81C784", "#FFB74D", "#BA68C8", "#4DB6AC",
        "#F06292", "#7986CB", "#AED581", "#FFD54F", "#9575CD", "#4DD0E1",
        "#FF8A65", "#4FC3F7", "#DCE775", "#A1887F", "#90A4AE", "#FFF176",
        "#C62828", "#1565C0", "#2E7D32", "#EF6C00", "#6A1B9A", "#00838F"
    };

    private static final Random RANDOM = new Random();

    private final Connection conn;

    public TagRepository(Database db) {
        this.conn = db.getConnection();
    }

    /**
     * Checks and trims a tag name typed by the user.
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static String normalize(String name) {
        String s = name == null ? "" : name.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Please enter a tag name.");
        }
        if (s.contains("/") || s.contains(":")) {
            throw new IllegalArgumentException("A tag name can't contain / or :.");
        }
        return s;
    }

    /** All tags, sorted by name. */
    public List<Tag> findAll() throws SQLException {
        String sql = "SELECT t.id, t.name, t.color,"
                + " (SELECT COUNT(*) FROM txn x WHERE x.tag_id = t.id)"
                + " FROM tag t ORDER BY t.name COLLATE NOCASE";
        List<Tag> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Tag(rs.getLong(1), rs.getString(2), fromHex(rs.getString(3)), rs.getInt(4)));
            }
        }
        return list;
    }

    /** Tag names, sorted, for the ledger's drop-down. */
    public List<String> findAllNames() throws SQLException {
        List<String> names = new ArrayList<>();
        for (Tag t : findAll()) {
            names.add(t.name());
        }
        return names;
    }

    /** The id of the tag with this name (ignoring case), or null. */
    public Long findId(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM tag WHERE name = ?")) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Finds a tag by name, creating it with an unused color if needed. Null for blank. */
    public Long findOrCreate(String name) throws SQLException {
        if (name == null || name.isBlank()) {
            return null;
        }
        String clean = normalize(name);
        Long id = findId(clean);
        return id != null ? id : create(clean, unusedColor());
    }

    public long create(String name, Color color) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO tag (name, color) VALUES (?, ?)")) {
            ps.setString(1, normalize(name));
            ps.setString(2, toHex(color));
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** True if a tag other than excludeId already has this name (ignoring case). */
    public boolean nameExists(String name, long excludeId) throws SQLException {
        Long id = findId(name);
        return id != null && id != excludeId;
    }

    public void rename(long id, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE tag SET name = ? WHERE id = ?")) {
            ps.setString(1, normalize(name));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void setColor(long id, Color color) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE tag SET color = ? WHERE id = ?")) {
            ps.setString(1, toHex(color));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Deletes a tag, removing it from any transactions that use it. */
    public void delete(long id) throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET tag_id = NULL WHERE tag_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tag WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /**
     * A random color no tag is using: one from the palette while any are
     * left, then random soft colors.
     */
    public Color unusedColor() throws SQLException {
        Set<String> used = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT color FROM tag")) {
            while (rs.next()) {
                used.add(rs.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        List<String> free = new ArrayList<>();
        for (String c : PALETTE) {
            if (!used.contains(c)) {
                free.add(c);
            }
        }
        if (!free.isEmpty()) {
            return fromHex(free.get(RANDOM.nextInt(free.size())));
        }
        for (int tries = 0; tries < 1000; tries++) {
            Color c = Color.getHSBColor(RANDOM.nextFloat(),
                    0.45f + RANDOM.nextFloat() * 0.35f, 0.70f + RANDOM.nextFloat() * 0.25f);
            if (!used.contains(toHex(c))) {
                return c;
            }
        }
        return Color.getHSBColor(RANDOM.nextFloat(), 0.6f, 0.85f);
    }

    public static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static Color fromHex(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException | NullPointerException e) {
            return Color.LIGHT_GRAY;
        }
    }
}
