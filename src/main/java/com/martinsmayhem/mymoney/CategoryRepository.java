package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and creates categories. A category is written as its full path:
 * "Food" for a parent, "Food:Groceries" for a child. Only one level of
 * children is allowed.
 */
public class CategoryRepository {

    public static final String SEPARATOR = ":";

    private final Connection conn;

    public CategoryRepository(Database db) {
        this.conn = db.getConnection();
    }

    /**
     * Checks and tidies a typed category path: trims spaces around each part.
     * "food : groceries" -> "food:groceries".
     *
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static String normalize(String path) {
        String[] parts = path.split(SEPARATOR, -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException(
                    "A category can only have one level below it, like Food:Groceries.");
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (parts[i].isEmpty()) {
                throw new IllegalArgumentException(
                        "\"" + path + "\" is missing a name before or after the colon.");
            }
        }
        return String.join(SEPARATOR, parts);
    }

    /** Every category as a full path, each parent followed by its children. */
    public List<String> findAllPaths() throws SQLException {
        String sql = """
            SELECT c.name, p.name
            FROM category c
            LEFT JOIN category p ON p.id = c.parent_id
            ORDER BY COALESCE(p.name, c.name) COLLATE NOCASE,
                     c.parent_id IS NOT NULL,
                     c.name COLLATE NOCASE
            """;
        List<String> paths = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                String parent = rs.getString(2);
                paths.add(parent == null ? name : parent + SEPARATOR + name);
            }
        }
        return paths;
    }

    /**
     * Finds a category by path (ignoring upper/lower case), creating the
     * parent and/or child if they don't exist yet. Null for a blank path.
     */
    public Long findOrCreate(String path) throws SQLException {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = normalize(path).split(SEPARATOR);
        Long id = findOrCreate(parts[0], null);
        if (parts.length == 2) {
            id = findOrCreate(parts[1], id);
        }
        return id;
    }

    // ---------------------------------------------------------------------

    private long findOrCreate(String name, Long parentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM category WHERE name = ? COLLATE NOCASE AND parent_id IS ?")) {
            ps.setString(1, name);
            setId(ps, 2, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO category (name, parent_id) VALUES (?, ?)")) {
            ps.setString(1, name);
            setId(ps, 2, parentId);
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void setId(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }
}
