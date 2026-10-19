package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the data file keeps about syncing with phones (docs/SYNC-DESIGN.md):
 * the mailbox settings, which accounts sync, the paired phones, and the
 * changes already applied. It's all in the (encrypted) data file, so it goes
 * wherever the file goes.
 */
final class SyncRepository {

    /**
     * Where the mailbox is. kind is "folder" (the desktop reaches it as a
     * folder on disk), "webdav" or "" (sync isn't set up). The phone always
     * uses WebDAV: phoneUrl and phonePath are where it finds the same
     * mailbox (for a WebDAV desktop, usually the same as url and path).
     * minutes: how often to sync while the file is open.
     */
    record Settings(String kind, String folder, String url, String path, String user, String password,
                    String phoneUrl, String phonePath, int minutes) {

        static final Settings NONE = new Settings("", "", "", "/MyMoney-Sync", "", "", "", "/MyMoney-Sync", 5);

        boolean isSetUp() {
            return "folder".equals(kind) && !folder.isBlank() || "webdav".equals(kind) && !url.isBlank();
        }
    }

    /** A paired phone. */
    record Device(long id, String uid, String name, byte[] key, boolean includeScheduled, long appliedThrough,
                  String lastSeen) {
    }

    /** What applying one of a phone's changes came to. */
    record OpResult(String opId, String result, List<String> conflicts, String message) {
        static final String APPLIED = "applied";
        static final String CONFLICTS = "applied-with-conflicts";
        static final String REJECTED = "rejected";
    }

    private final Connection conn;

    SyncRepository(Database db) {
        this.conn = db.getConnection();
    }

    // ----- Settings ----------------------------------------------------------

    private String meta(String key, String def) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.executeUpdate();
        }
    }

    /** This data file's id: its folder in the mailbox. */
    String fileId() throws SQLException {
        String id = meta("sync_file_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            setMeta("sync_file_id", id);
        }
        return id;
    }

    Settings settings() throws SQLException {
        Settings d = Settings.NONE;
        int minutes;
        try {
            minutes = Integer.parseInt(meta("sync_minutes", String.valueOf(d.minutes())));
        } catch (NumberFormatException e) {
            minutes = d.minutes();
        }
        return new Settings(meta("sync_kind", d.kind()), meta("sync_folder", d.folder()), meta("sync_url", d.url()),
                meta("sync_path", d.path()), meta("sync_user", d.user()), meta("sync_password", d.password()),
                meta("sync_phone_url", d.phoneUrl()), meta("sync_phone_path", d.phonePath()), minutes);
    }

    void saveSettings(Settings s) throws SQLException {
        setMeta("sync_kind", s.kind());
        setMeta("sync_folder", s.folder());
        setMeta("sync_url", s.url());
        setMeta("sync_path", s.path());
        setMeta("sync_user", s.user());
        setMeta("sync_password", s.password());
        setMeta("sync_phone_url", s.phoneUrl());
        setMeta("sync_phone_path", s.phonePath());
        setMeta("sync_minutes", String.valueOf(s.minutes()));
    }

    /** The mailbox the settings describe, or null if sync isn't set up. */
    static Mailbox mailbox(Settings s) {
        if (!s.isSetUp()) {
            return null;
        }
        return "folder".equals(s.kind()) ? new FolderMailbox(java.nio.file.Path.of(s.folder()))
                : new WebDavMailbox(s.url(), s.path(), s.user(), s.password());
    }

    /** The next snapshot's number (each snapshot written gets a higher one). */
    long nextSnapshotSeq() throws SQLException {
        long next = Long.parseLong(meta("sync_snapshot_seq", "0")) + 1;
        setMeta("sync_snapshot_seq", String.valueOf(next));
        return next;
    }

    // ----- Accounts ------------------------------------------------------------

    /** The accounts ticked to sync (not counting envelopes, which follow their parent). */
    Set<Long> syncedAccounts() throws SQLException {
        Set<Long> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM account WHERE sync = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    void setSynced(long accountId, boolean sync) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE account SET sync = ? WHERE id = ?")) {
            ps.setInt(1, sync ? 1 : 0);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        }
    }

    // ----- Phones ---------------------------------------------------------------

    List<Device> devices() throws SQLException {
        List<Device> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, uid, name, key, include_scheduled,"
                + " applied_through, last_seen FROM sync_device ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Device(rs.getLong(1), rs.getString(2), rs.getString(3),
                        SyncCrypto.fromBase64(rs.getString(4)), rs.getInt(5) == 1, rs.getLong(6), rs.getString(7)));
            }
        }
        return list;
    }

    /** Pairs a new phone: a new device id and key. */
    Device addDevice(String name) throws SQLException {
        String uid = UUID.randomUUID().toString();
        byte[] key = SyncCrypto.newKey();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sync_device (uid, name, key, paired_at)"
                + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setString(2, name);
            ps.setString(3, SyncCrypto.toBase64(key));
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
        return devices().stream().filter(d -> d.uid().equals(uid)).findFirst().orElseThrow();
    }

    /** Forgets a phone (and the record of its changes). */
    void removeDevice(long deviceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_op WHERE device_id = ?")) {
            ps.setLong(1, deviceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_device WHERE id = ?")) {
            ps.setLong(1, deviceId);
            ps.executeUpdate();
        }
    }

    void setDeviceOptions(long deviceId, String name, boolean includeScheduled) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sync_device SET name = ?, include_scheduled = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, includeScheduled ? 1 : 0);
            ps.setLong(3, deviceId);
            ps.executeUpdate();
        }
    }

    /** Records that a phone's change files up to seq are applied. */
    void setAppliedThrough(long deviceId, long seq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sync_device SET applied_through = MAX(applied_through, ?), last_seen = ? WHERE id = ?")) {
            ps.setLong(1, seq);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, deviceId);
            ps.executeUpdate();
        }
    }

    // ----- Changes applied ------------------------------------------------------

    /** What an op came to when it was applied, or null if it hasn't been. */
    OpResult findOp(String opId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT op_id, result, conflicts, message FROM sync_op WHERE op_id = ?")) {
            ps.setString(1, opId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? result(rs) : null;
            }
        }
    }

    void recordOp(long deviceId, OpResult r) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO sync_op"
                + " (device_id, op_id, applied_at, result, conflicts, message) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, deviceId);
            ps.setString(2, r.opId());
            ps.setString(3, Instant.now().toString());
            ps.setString(4, r.result());
            ps.setString(5, r.conflicts().isEmpty() ? null : String.join(",", r.conflicts()));
            ps.setString(6, r.message());
            ps.executeUpdate();
        }
    }

    /** A phone's results from the past 30 days, for its snapshot. */
    List<OpResult> recentResults(long deviceId) throws SQLException {
        List<OpResult> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT op_id, result, conflicts, message FROM sync_op"
                + " WHERE device_id = ? AND applied_at >= ? ORDER BY id")) {
            ps.setLong(1, deviceId);
            ps.setString(2, Instant.now().minus(30, ChronoUnit.DAYS).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(result(rs));
                }
            }
        }
        return list;
    }

    /** Forgets ops applied over 90 days ago (a phone never resends one that old). */
    void pruneOps() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_op WHERE applied_at < ?")) {
            ps.setString(1, Instant.now().minus(90, ChronoUnit.DAYS).toString());
            ps.executeUpdate();
        }
    }

    private static OpResult result(ResultSet rs) throws SQLException {
        String c = rs.getString(3);
        return new OpResult(rs.getString(1), rs.getString(2),
                c == null || c.isBlank() ? List.of() : List.of(c.split(",")), rs.getString(4));
    }
}
