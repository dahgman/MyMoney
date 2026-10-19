package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Undo and redo for everything written to the data file.
 *
 * Temporary triggers record, for every insert, update and delete on the data
 * tables, the SQL statement that reverses it. The app calls checkpoint(label)
 * after each user action, which groups everything recorded since the last
 * checkpoint into one undo step. Undo runs a step's statements in reverse
 * order; doing so records the statements for redo.
 *
 * The log lives in a TEMP table, so it is never saved in the file and starts
 * empty each time a database is opened.
 * (This is the technique described at https://www.sqlite.org/undoredo.html.)
 */
final class UndoLog {

    /** The tables whose changes can be undone. */
    private static final String[] TABLES = {"account", "category", "payee", "tag", "txn", "split",
                                            "reconciliation", "attachment", "payee_rename",
                                            "schedule"};
    private static final int MAX_STEPS = 200;

    /** One undoable user action: the log rows first..last. */
    private record Step(long first, long last, String label) {
    }

    private final Connection conn;
    private final Deque<Step> undoSteps = new ArrayDeque<>();
    private final Deque<Step> redoSteps = new ArrayDeque<>();
    private long lastSeq;

    UndoLog(Database db) throws SQLException {
        this.conn = db.getConnection();
        install();
        lastSeq = lastAssignedSeq();
    }

    /** Where the log has got to, for dropSince. */
    long mark() throws SQLException {
        return lastAssignedSeq();
    }

    /**
     * Drops what was logged since mark: those changes stay done, but no
     * undo step will reverse them. For changes made alongside files on disk
     * that Undo can't put back (attachments added, removed, renamed, moved),
     * so it never leaves a listing pointing at a file that isn't there.
     * Anything else in the same action can still be undone.
     */
    void dropSince(long mark) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM temp.undolog WHERE seq > ?")) {
            ps.setLong(1, mark);
            ps.executeUpdate();
        }
    }

    /** Groups every change since the last checkpoint into one undo step named label. */
    void checkpoint(String label) throws SQLException {
        long current = lastAssignedSeq();
        if (current <= lastSeq) {
            return;                                     // nothing changed
        }
        if (!anyLogged(lastSeq + 1, current)) {
            lastSeq = current;                          // only changes that were dropped (dropSince)
            return;
        }
        undoSteps.push(new Step(lastSeq + 1, current, label));
        lastSeq = current;
        for (Step s : redoSteps) {                      // a new change ends the redo chain
            deleteRange(s);
        }
        redoSteps.clear();
        while (undoSteps.size() > MAX_STEPS) {
            deleteRange(undoSteps.removeLast());
        }
    }

    /**
     * Reverses everything changed since the last checkpoint, as if it had
     * never happened: nothing is left to undo or redo for it (Cancel in
     * Reconcile Account). Earlier undo steps are untouched.
     */
    void revertToCheckpoint() throws SQLException {
        long current = lastAssignedSeq();
        if (current <= lastSeq || !anyLogged(lastSeq + 1, current)) {
            lastSeq = current;
            return;
        }
        Step reversal = replay(new Step(lastSeq + 1, current, ""));
        deleteRange(reversal);                          // what replaying it logged: no redo
    }

    /** The name of the action Undo would reverse, or null if there is none. */
    String undoLabel() {
        return undoSteps.isEmpty() ? null : undoSteps.peek().label();
    }

    String redoLabel() {
        return redoSteps.isEmpty() ? null : redoSteps.peek().label();
    }

    /** Reverses the most recent step. If it fails, nothing changes. */
    void undo() throws SQLException {
        if (undoSteps.isEmpty()) {
            return;
        }
        Step step = undoSteps.pop();
        try {
            redoSteps.push(replay(step));
        } catch (SQLException e) {
            undoSteps.push(step);
            throw e;
        }
    }

    /** Re-applies the most recently undone step. */
    void redo() throws SQLException {
        if (redoSteps.isEmpty()) {
            return;
        }
        Step step = redoSteps.pop();
        try {
            undoSteps.push(replay(step));
        } catch (SQLException e) {
            redoSteps.push(step);
            throw e;
        }
    }

    // ---------------------------------------------------------------------

    /**
     * Runs a step's statements newest-first, as one database transaction.
     * The triggers record the opposite statements, which become the returned
     * step (redo for an undo, undo for a redo).
     */
    private Step replay(Step step) throws SQLException {
        List<String> statements = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sql FROM temp.undolog WHERE seq BETWEEN ? AND ? ORDER BY seq DESC")) {
            ps.setLong(1, step.first());
            ps.setLong(2, step.last());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    statements.add(rs.getString(1));
                }
            }
        }
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA defer_foreign_keys = ON");   // rows come back in any order
            deleteRange(step);
            long before = lastAssignedSeq();
            for (String sql : statements) {
                st.execute(sql);
            }
            long after = lastAssignedSeq();
            conn.commit();
            lastSeq = after;
            return new Step(before + 1, after, step.label());
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void deleteRange(Step s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM temp.undolog WHERE seq BETWEEN ? AND ?")) {
            ps.setLong(1, s.first());
            ps.setLong(2, s.last());
            ps.executeUpdate();
        }
    }

    private boolean anyLogged(long first, long last) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM temp.undolog WHERE seq BETWEEN ? AND ?)")) {
            ps.setLong(1, first);
            ps.setLong(2, last);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** The last sequence number handed out (AUTOINCREMENT never reuses one). */
    private long lastAssignedSeq() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE((SELECT seq FROM temp.sqlite_sequence WHERE name = 'undolog'), 0)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Creates the log table and, for each data table, three triggers built from its columns. */
    private void install() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE IF NOT EXISTS undolog ("
                    + "seq INTEGER PRIMARY KEY AUTOINCREMENT, sql TEXT NOT NULL)");
            for (String table : TABLES) {
                List<String> cols = columns(table);
                StringBuilder set = new StringBuilder();
                StringBuilder names = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (String c : cols) {
                    if (set.length() > 0) {
                        set.append("||','||");
                        names.append(',');
                        values.append("||','||");
                    }
                    set.append("'").append(c).append("='||quote(OLD.").append(c).append(")");
                    names.append(c);
                    values.append("quote(OLD.").append(c).append(")");
                }
                st.execute("DROP TRIGGER IF EXISTS temp.undo_" + table + "_insert");
                st.execute("DROP TRIGGER IF EXISTS temp.undo_" + table + "_update");
                st.execute("DROP TRIGGER IF EXISTS temp.undo_" + table + "_delete");
                st.execute("CREATE TEMP TRIGGER undo_" + table + "_insert AFTER INSERT ON main." + table
                        + " BEGIN INSERT INTO undolog (sql) VALUES ("
                        + "'DELETE FROM " + table + " WHERE id='||NEW.id); END");
                st.execute("CREATE TEMP TRIGGER undo_" + table + "_update AFTER UPDATE ON main." + table
                        + " BEGIN INSERT INTO undolog (sql) VALUES ("
                        + "'UPDATE " + table + " SET '||" + set + "||' WHERE id='||OLD.id); END");
                st.execute("CREATE TEMP TRIGGER undo_" + table + "_delete BEFORE DELETE ON main." + table
                        + " BEGIN INSERT INTO undolog (sql) VALUES ("
                        + "'INSERT INTO " + table + " (" + names + ") VALUES ('||" + values + "||')'); END");
            }
        }
    }

    private List<String> columns(String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA main.table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }
}
