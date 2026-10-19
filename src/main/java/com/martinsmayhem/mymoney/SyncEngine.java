package com.martinsmayhem.mymoney;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a sync (docs/SYNC-DESIGN.md), in three steps so the network is never
 * touched on the window's thread, and the database only on it:
 * <ol>
 *   <li>{@link #plan()} (window thread): the settings and the paired phones.</li>
 *   <li>{@link #fetch(Plan)} (background): reads and decrypts the phones' new
 *       change files and their receipt photos.</li>
 *   <li>{@link #apply(Fetched)} (window thread): applies them, then makes each
 *       phone's new snapshot.</li>
 *   <li>{@link #publish(Outgoing)} (background): writes the snapshots, and
 *       deletes the change files and photos that were applied.</li>
 * </ol>
 * Inside the file's folder in the mailbox, each phone has
 * devices/&lt;id&gt;/snapshot.mms, changes/&lt;seq&gt;.mms and receipts/&lt;id&gt;.mms.
 */
final class SyncEngine {

    /** What a sync will do: where, and for which phones. */
    record Plan(Mailbox mailbox, String fileId, List<SyncRepository.Device> devices) {
    }

    /** A change file read and decrypted. */
    record Batch(SyncRepository.Device device, long seq, String path, JsonObject json,
                 Map<String, byte[]> receipts) {
    }

    /** stale: change files applied already but still there (a sync stopped before deleting them). */
    record Fetched(Plan plan, List<Batch> batches, List<String> problems, List<String> stale) {
        /** Nothing from the phones: just new snapshots (when closing). */
        static Fetched nothing(Plan plan) {
            return new Fetched(plan, List.of(), List.of(), List.of());
        }
    }

    /** What's left to write and delete once the database work is done. */
    record Outgoing(Plan plan, Map<String, byte[]> writes, List<String> deletes, Summary summary) {
    }

    /** How a sync went, for the window. */
    record Summary(int applied, int conflicts, int rejected, List<String> problems) {
        boolean ok() {
            return problems.isEmpty();
        }
    }

    private final Database db;
    private final SyncRepository sync;
    private final SyncApplier applier;
    private final SyncSnapshot snapshots;

    SyncEngine(Database db, AttachmentRepository attachments) {
        this.db = db;
        this.sync = new SyncRepository(db);
        this.applier = new SyncApplier(db, attachments);
        this.snapshots = new SyncSnapshot(db);
    }

    // ----- Paths ------------------------------------------------------------

    /** A phone's path inside the file's folder: what each of its files is encrypted for. */
    static String devicePath(SyncRepository.Device d) {
        return "devices/" + d.uid();
    }

    static String snapshotPath(SyncRepository.Device d) {
        return devicePath(d) + "/snapshot.mms";
    }

    static String changePath(SyncRepository.Device d, long seq) {
        return devicePath(d) + "/changes/" + seq + ".mms";
    }

    static String receiptPath(SyncRepository.Device d, String receiptId) {
        return devicePath(d) + "/receipts/" + receiptId + ".mms";
    }

    private static String inMailbox(Plan plan, String path) {
        return plan.fileId() + "/" + path;
    }

    // ----- 1. Plan (window thread) -------------------------------------------

    /** The sync to run, or null if sync isn't set up. */
    Plan plan() throws SQLException {
        Mailbox m = SyncRepository.mailbox(sync.settings());
        return m == null ? null : new Plan(m, sync.fileId(), sync.devices());
    }

    // ----- 2. Fetch (background) ----------------------------------------------

    /**
     * Reads each phone's change files after the last one applied, in order,
     * with the photos they attach. A file that can't be read stops that
     * phone's sync there (so its changes are never applied out of order) and
     * is reported.
     */
    Fetched fetch(Plan plan) throws IOException {
        List<Batch> batches = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (SyncRepository.Device d : plan.devices()) {
            List<Long> seqs = new ArrayList<>();
            for (String name : plan.mailbox().list(inMailbox(plan, devicePath(d) + "/changes"))) {
                if (name.matches("\\d+\\.mms")) {
                    long seq = Long.parseLong(name.substring(0, name.length() - 4));
                    if (seq > d.appliedThrough()) {
                        seqs.add(seq);
                    } else {
                        stale.add(inMailbox(plan, changePath(d, seq)));
                    }
                }
            }
            seqs.sort(null);
            for (long seq : seqs) {
                String path = changePath(d, seq);
                try {
                    byte[] file = plan.mailbox().read(inMailbox(plan, path));
                    if (file == null) {
                        continue;                               // gone since it was listed
                    }
                    JsonObject json = JsonParser.parseString(new String(
                            SyncCrypto.decrypt(d.key(), path, file), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!d.uid().equals(str(json, "deviceId")) || !json.has("seq") || json.get("seq").getAsLong() != seq) {
                        throw new SyncCrypto.BadFileException("it isn't the change file it says it is");
                    }
                    batches.add(new Batch(d, seq, path, json, receiptsFor(plan, d, json)));
                } catch (SyncCrypto.BadFileException | RuntimeException e) {
                    problems.add("A change from " + d.name() + " couldn't be read (" + e.getMessage()
                            + "); it and any after it are waiting.");
                    break;
                }
            }
        }
        return new Fetched(plan, batches, problems, stale);
    }

    /** The photos a change file's attach ops need, decrypted (a missing one is left out). */
    private static Map<String, byte[]> receiptsFor(Plan plan, SyncRepository.Device d, JsonObject batch)
            throws IOException, SyncCrypto.BadFileException {
        Map<String, byte[]> receipts = new HashMap<>();
        for (JsonElement el : batch.getAsJsonArray("ops")) {
            JsonObject op = el.getAsJsonObject();
            String id = "attach".equals(str(op, "op")) ? str(op, "receiptId") : null;
            if (id != null && id.matches("[A-Za-z0-9-]+") && !receipts.containsKey(id)) {
                byte[] file = plan.mailbox().read(inMailbox(plan, receiptPath(d, id)));
                if (file != null) {
                    receipts.put(id, SyncCrypto.decrypt(d.key(), receiptPath(d, id), file));
                }
            }
        }
        return receipts;
    }

    // ----- 3. Apply (window thread) --------------------------------------------

    /**
     * Applies the change files, then makes every phone's new snapshot (a
     * phone's sync settings may have changed, so all of them, every time).
     */
    Outgoing apply(Fetched fetched) throws SQLException {
        Plan plan = fetched.plan();
        int applied = 0;
        int conflicts = 0;
        int rejected = 0;
        List<String> problems = new ArrayList<>(fetched.problems());
        List<String> deletes = new ArrayList<>(fetched.stale());
        java.util.Set<String> stopped = new java.util.HashSet<>();        // phones whose batch failed
        for (Batch b : fetched.batches()) {
            if (stopped.contains(b.device().uid())) {
                continue;                                   // its later ones wait, in order
            }
            List<SyncRepository.OpResult> results;
            try {
                results = applier.apply(b.device(), b.json(), id -> b.receipts().get(id));
            } catch (SQLException e) {
                problems.add("Changes from " + b.device().name() + " couldn't be applied (" + e.getMessage()
                        + "); they'll be tried again next time.");
                stopped.add(b.device().uid());
                continue;
            }
            for (SyncRepository.OpResult r : results) {
                switch (r.result()) {
                    case SyncRepository.OpResult.APPLIED -> applied++;
                    case SyncRepository.OpResult.CONFLICTS -> conflicts++;
                    default -> rejected++;
                }
            }
            deletes.add(inMailbox(plan, b.path()));
            for (String id : b.receipts().keySet()) {
                deletes.add(inMailbox(plan, receiptPath(b.device(), id)));
            }
        }
        sync.pruneOps();
        Map<String, byte[]> writes = new HashMap<>();
        writes.put(plan.fileId() + "/mailbox.json", mailboxJson(plan.fileId()));
        LocalDate today = LocalDate.now();
        for (SyncRepository.Device d : sync.devices()) {      // read again: appliedThrough has moved on
            String path = snapshotPath(d);
            byte[] json = snapshots.build(d, today).toString().getBytes(StandardCharsets.UTF_8);
            writes.put(inMailbox(plan, path), SyncCrypto.encrypt(d.key(), path, json));
        }
        return new Outgoing(plan, writes, deletes, new Summary(applied, conflicts, rejected, problems));
    }

    // ----- 4. Publish (background) ---------------------------------------------

    /** Writes the snapshots, then deletes what was applied. */
    Summary publish(Outgoing out) throws IOException {
        for (Map.Entry<String, byte[]> w : out.writes().entrySet()) {
            out.plan().mailbox().write(w.getKey(), w.getValue());
        }
        for (String path : out.deletes()) {
            out.plan().mailbox().delete(path);
        }
        return out.summary();
    }

    /** All four steps in a row, on this thread (for tests, and Sync Now's fallback). */
    Summary runNow() throws IOException, SQLException {
        Plan plan = plan();
        if (plan == null) {
            return new Summary(0, 0, 0, List.of("Sync isn't set up."));
        }
        return publish(apply(fetch(plan)));
    }

    /** What marks the file's folder in the mailbox as MyMoney's (the phone checks for it when pairing). */
    static byte[] mailboxJson(String fileId) {
        return ("{ \"format\": " + SyncSnapshot.FORMAT + ", \"fileId\": \"" + fileId + "\" }\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Writes mailbox.json now (when a phone is paired, before any sync has). */
    static void markMailbox(Plan plan) throws IOException {
        plan.mailbox().write(plan.fileId() + "/mailbox.json", mailboxJson(plan.fileId()));
    }

    /** Deletes a removed phone's folder from the mailbox (its key is gone from the file already). */
    static void removeDeviceFolder(Plan plan, SyncRepository.Device d) throws IOException {
        plan.mailbox().deleteFolder(inMailbox(plan, devicePath(d)));
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }
}
