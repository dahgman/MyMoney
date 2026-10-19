# MyMoney Sync: design

How MyMoney on the desktop and the MyMoney mobile app (Android first, iOS
later) keep each other up to date. This is the agreed design, written before
any code. Both sides are built against it, so changes to the formats here
come first, then the code.

---

## Contents

1. [Goals and decisions](#1-goals-and-decisions)
2. [The big picture](#2-the-big-picture)
3. [The mailbox](#3-the-mailbox)
4. [Pairing a phone](#4-pairing-a-phone)
5. [Encryption](#5-encryption)
6. [What the phone gets: the snapshot](#6-what-the-phone-gets-the-snapshot)
7. [What the phone sends: changes](#7-what-the-phone-sends-changes)
8. [Receipt photos](#8-receipt-photos)
9. [How the desktop applies changes](#9-how-the-desktop-applies-changes)
10. [When each side syncs](#10-when-each-side-syncs)
11. [Desktop changes](#11-desktop-changes)
12. [The mobile app](#12-the-mobile-app)
13. [Reaching the NAS from away](#13-reaching-the-nas-from-away)
14. [Phases](#14-phases)
15. [Testing](#15-testing)
16. [Not in the first version](#16-not-in-the-first-version)

> **Status:** phase 1 (the desktop) is built: schema 21, `SyncCrypto`,
> `Mailbox` (`FolderMailbox`, `WebDavMailbox`), `SyncSnapshot`, `SyncApplier`,
> `SyncEngine`, `SyncDialog` (**File > Sync Settings…**, not a Preferences tab,
> since the settings belong to the data file) and **File > Sync Now** (F5).
> Tests: `SyncTest`, with a pretend phone. Edits are checked field by field
> against the `old` values the phone sends, so rows need no "changed" stamp.

---

## 1. Goals and decisions

| Question | Decision |
|---|---|
| What is the phone for? | Mostly **entering transactions**: add, edit and delete, with receipt photos. Checking balances. |
| Must it sync away from home? | **Yes.** Neither side may depend on the other being online. |
| Which transactions go to the phone? | For each **synced account**: every **unreconciled** transaction (editable), plus the last **60 days of reconciled** ones (read-only). |
| Which accounts? | Only those ticked for sync. Every account's **name** goes to the phone (so transfers work), but balances and transactions only for synced ones. |
| Envelopes | **Shown**, with balances, under their parent account. An envelope under a synced account is synced with it. |
| Receipt photos | **Yes**, from the phone to the desktop. |
| Scheduled transactions | **Optional**, per phone: shown as "upcoming", and entered with a tap. |
| Deleting from the phone | **Allowed**, for unreconciled transactions. |
| Where data travels | An **encrypted mailbox** in a shared folder. WebDAV on the Synology first; other services later. |
| Phone platforms | **Android** first; built so **iOS** can follow with most code shared. |

**One rule above all: the desktop is the authority.** The phone never
calculates anything that matters (loan interest, the other side of a transfer,
envelope balances). It shows what the desktop sent and asks the desktop for
changes. The desktop applies them with the same code the ledger uses, then
sends back the result.

---

## 2. The big picture

```
                         NAS (or any mailbox)
                  ┌──────────────────────────────┐
  Phone ──────────▶ devices/<phone>/changes/     │◀────────── Desktop reads,
       (its edits)│ devices/<phone>/receipts/    │            applies, deletes
                  │                              │
  Phone ◀──────── │ devices/<phone>/snapshot     │◀────────── Desktop writes
    (reads state) └──────────────────────────────┘
```

- **Desktop → phone:** one **snapshot** per phone, replaced each time: everything
  the phone shows, plus the result of each change it sent. A full snapshot every
  time (not just "what changed") means the phone can never drift out of step.
  It's small: a few hundred transactions.
- **Phone → desktop:** **change files**: "add this", "change these fields",
  "delete that". Each change has its own id, so applying it twice does nothing.
  Receipt photos travel as separate files.
- Until the desktop confirms a change, the phone shows it **on top of** the
  snapshot, marked *pending*. Your entries appear at once, and balances include them.

---

## 3. The mailbox

The mailbox is a folder that both sides can reach. Nothing in it is readable
without the key (see [Encryption](#5-encryption)).

### Layout

```
MyMoney-Sync/<file id>/                     one folder per MyMoney data file
    mailbox.json                           not encrypted: { "format": 1, "fileId": "…" }
    devices/<device id>/
        snapshot.mms                       desktop → phone: the current snapshot
        changes/<seq>.mms                  phone → desktop: a batch of changes (seq = 1, 2, 3, …)
        receipts/<receipt id>.mms          phone → desktop: a photo
```

- `<file id>` and `<device id>` are UUIDs, so names never clash and say nothing
  about you.
- **Writes are all-or-nothing:** a file is written as `<name>.tmp`, then renamed
  (WebDAV `MOVE`). Readers skip `*.tmp`, so a half-written file is never read.
- **Who deletes what:**
  - The **desktop** deletes change files and receipts once they're safely
    applied and filed.
  - The **phone** never deletes anything except its own `*.tmp` leftovers.

### Kinds of mailbox

Both apps talk to the mailbox through the same small interface, so adding a
service later means writing one class, not changing the sync:

```java
interface Mailbox {
    List<String> list(String folder);            // names in a folder
    byte[] read(String path);                    // null if it isn't there
    void write(String path, byte[] data);        // all-or-nothing (temp file, then rename)
    void delete(String path);
}
```

| Kind | Desktop | Phone | When |
|---|---|---|---|
| **Folder** | ✓ | – | First. A folder on disk: the Synology share as the desktop already mounts it, or a folder kept in step by Synology Drive, Dropbox, Nextcloud, etc. Also used by the tests. |
| **WebDAV** | ✓ | ✓ | First. The Synology's WebDAV Server package; also Nextcloud and others. |
| Dropbox, Google Drive, OneDrive, S3 | later | later | Each needs its sign-in flow, so they come later. |

The desktop can use the **Folder** kind on the NAS share it already mounts,
while the phone reaches the **same folder** over WebDAV. The desktop doesn't
need WebDAV itself unless it's away from home too.

---

## 4. Pairing a phone

1. Desktop: **Edit > Preferences > Sync > Pair a Phone…**. It makes:
   - a device id;
   - a random **256-bit key**, one per phone, so a lost phone can be cut off
     without re-pairing the others;
   - the phone's folder in the mailbox.
2. The desktop shows a **QR code** with:

   ```json
   {
     "v": 1,
     "fileId": "0b6c…",
     "fileName": "Household",
     "deviceId": "7f21…",
     "key": "base64 of the 32-byte key",
     "mailbox": { "type": "webdav", "url": "https://thehive.example:5006", "path": "/MyMoney-Sync" }
   }
   ```

3. The phone scans it and asks for the **mailbox sign-in** (the Synology user
   name and password). These are not in the QR code. **Use a Synology user just
   for this, allowed only into the MyMoney-Sync shared folder.**
4. The phone stores the key in the Android Keystore (iOS: the Keychain) and
   syncs for the first time.

**Removing a phone** (Preferences > Sync) deletes its folder and forgets its
key. Anything it sends after that is ignored.

---

## 5. Encryption

Every `.mms` file is the same:

```
"MMS1"  (4 bytes: what this is, and its version)
nonce   (12 random bytes, new for every file)
data    (AES-256-GCM: the contents, gzip-compressed, then encrypted; ends with the 16-byte tag)
```

- **The key** is the phone's own key from pairing.
- **The file's path** (like `devices/7f21…/changes/12.mms`) is fed in as GCM's
  "additional data". A file copied or renamed to another place won't decrypt,
  so files can't be swapped around.
- **What's inside** is JSON (photos: the JPEG bytes).
- If a file doesn't decrypt, it's **left alone and reported**, never applied.
- Java (`javax.crypto`), Android and iOS (CryptoKit) all have AES-GCM built in,
  so there are no extra crypto libraries.

On the phone, the local database is encrypted too (SQLCipher), and the app can
be locked with a fingerprint or face.

---

## 6. What the phone gets: the snapshot

`devices/<id>/snapshot.mms`, written by the desktop after each sync. All money
is in **cents**, all dates are **ISO** (`2026-10-19`), and all ids are **UUIDs**
(see [Desktop changes](#11-desktop-changes)).

```json
{
  "format": 1,
  "snapshotSeq": 418,
  "made": "2026-10-19T14:05:11Z",
  "fileName": "Household",
  "appliedThrough": 12,
  "results": [
    { "opId": "c3d1…", "result": "applied" },
    { "opId": "a9e0…", "result": "applied-with-conflicts", "conflicts": ["memo"],
      "message": "The memo was also changed on the computer; the computer's was kept." },
    { "opId": "55b2…", "result": "rejected",
      "message": "Rent (10/01/2026) was reconciled on the computer, so it can't be changed." }
  ],
  "settings": { "showScheduled": true, "dateFormat": "MM/dd/yyyy", "currency": "USD" },

  "accounts": [
    { "uid": "…", "name": "Checking", "type": "CHECKING", "parentUid": null, "closed": false,
      "synced": true,
      "balanceCents": 523412, "clearedCents": 498000, "todayBalanceCents": 512000,
      "ledgerStartCents": 301122, "ledgerStartDate": "2026-08-20" },
    { "uid": "…", "name": "Vacation", "type": "ENVELOPE", "parentUid": "<Savings uid>",
      "synced": true, "balanceCents": 45000 },
    { "uid": "…", "name": "Car Loan", "type": "LOAN", "synced": false }
  ],

  "categories": [ { "uid": "…", "path": "Food:Groceries", "income": false } ],
  "payees":     [ "Walmart", "Shell", "…" ],
  "tags":       [ { "name": "Household", "color": "#3B7DD8" } ],
  "types":      [ "POS", "CHECK", "…" ],

  "transactions": [
    { "uid": "…", "accountUid": "…", "date": "2026-10-12", "bankDate": null,
      "num": "", "payee": "Walmart", "categoryUid": "…", "transferAccountUid": null,
      "split": false, "tag": null, "memo": "", "description": "", "type": "POS",
      "amountCents": -8450, "status": "C", "editable": true, "receipts": 1 }
  ]
}
```

Notes:

- **Balances are worked out on the desktop.** `balanceCents` is every
  transaction, `clearedCents` the cleared ones, and `todayBalanceCents` those
  dated today or earlier. The phone's ledger runs its balance from
  `ledgerStartCents` (the balance just before its first transaction).
- **Accounts that aren't synced** come with only their name and type, for
  transfers.
- **`editable: false`** marks transactions the phone may not change:
  - reconciled ones (status `R`), or being reconciled (`I`);
  - a loan's calculated interest charges;
  - split transactions (shown, with their total; splits are edited on the
    desktop for now).
- **Scheduled transactions:** the desktop already puts these in the ledger ahead
  of time with status `S`. With `showScheduled` on, they're included and the
  phone lists them under **Upcoming**. "Enter" on one is an ordinary edit: the
  status changes (to None or Cleared), and the amount and date if they differ.
  With it off, `S` transactions are left out.
- **`results`** answers the phone's changes, so it can drop pending ones and show
  what didn't go through. `appliedThrough` is the last change file applied.

---

## 7. What the phone sends: changes

`devices/<id>/changes/<seq>.mms`: one file per sync, `seq` counting up from 1.

```json
{
  "format": 1,
  "deviceId": "7f21…",
  "seq": 13,
  "baseSnapshot": 418,
  "ops": [ … ]
}
```

Each op has a unique `opId`. There are four kinds.

**Add**: the phone makes the new transaction's `uid` itself, so it can edit or
delete it before the desktop has even seen it:

```json
{ "op": "add", "opId": "…", "uid": "…", "accountUid": "…",
  "date": "2026-10-19", "num": "", "payee": "Starbucks",
  "category": { "uid": "…" },                  // or { "path": "Food:Coffee" } for a new one,
                                               // or { "transferAccountUid": "…" }, or null
  "tag": "Household", "memo": "", "amountCents": -575, "status": "U" }
```

**Edit**: only the fields changed, each with the value the phone **started
from** and the new one:

```json
{ "op": "edit", "opId": "…", "uid": "…",
  "changes": { "payee":  { "old": "STARBUCKS #123", "new": "Starbucks" },
               "amountCents": { "old": -575, "new": -625 } } }
```

**Delete:**

```json
{ "op": "delete", "opId": "…", "uid": "…" }
```

**Attach** a receipt photo (see [Receipt photos](#8-receipt-photos)):

```json
{ "op": "attach", "opId": "…", "uid": "…", "receiptId": "…", "fileName": "receipt.jpg" }
```

Names typed on the phone (payees, tags, new categories by `path`) are created
on the desktop if they don't exist yet, just as typing them in the ledger would.

---

## 8. Receipt photos

- Taken with the camera or picked from the gallery.
- Shrunk on the phone to at most **1600 pixels** on the long side, as a JPEG at
  quality 80 (roughly 200–400 KB).
- Written as `receipts/<receipt id>.mms` **before** the change file that
  mentions it, so the desktop never sees an `attach` without its photo.
- The desktop files each one as a normal transaction attachment
  (`attachments/transactions/<Account>/<YYYY>/<MM>/<DD>/<txn id>-<payee>.jpg`),
  then deletes it from the mailbox.
- The phone keeps its own copy until the desktop confirms the `attach`, then
  keeps only a small thumbnail.
- Photos only go **phone → desktop**. The snapshot just says how many receipts
  a transaction has.

---

## 9. How the desktop applies changes

For each phone, the desktop reads change files in `seq` order, starting after
the last one applied. Each file is applied as **one database transaction**:
all of it, or none of it.

For each op:

1. **Already applied?** If its `opId` is in the applied-ops list, it's skipped,
   and the earlier result is repeated in the next snapshot.
2. **Add:** inserted through `TransactionRepository`, the same as the ledger, so:
   - a transfer gets its other side;
   - a payment to a loan gets its interest;
   - the account's default category applies when none was given.
3. **Edit:** each field is compared against its `old` value.
   - **If the desktop still has `old`,** the new value is applied.
   - **If the desktop already has `new`,** nothing is needed.
   - **If it has something else,** it was changed on both sides. **The desktop
     keeps its value** and reports the conflict; the other fields still apply.
4. **Delete:** the transaction is deleted, with its other side if it's a transfer.
5. **Refused** (the result is `rejected`, with a reason the phone shows):
   - the transaction is reconciled, or being reconciled;
   - it was already deleted;
   - its account is no longer synced;
   - it's a split, or a loan's calculated charge.
6. **Then:**
   - loans are recalculated and attachments filed, as after any change;
   - the whole sync becomes **one undo step**, "Sync from *phone name*";
   - the applied change file is deleted;
   - a new snapshot is written for every paired phone.

Bank downloads stay on the desktop. A transaction entered on the phone is an
ordinary transaction by then, so the next download **matches** it, just like
one typed in.

**Only one desktop syncs a data file.** The sync settings and keys are kept
**inside the data file** (which is encrypted), so they go wherever the file goes.
MyMoney already allows the file to be open on only one computer at a time.

---

## 10. When each side syncs

| | When |
|---|---|
| **Desktop** | When a file is opened; every **5 minutes** while it's open; **File > Sync Now**; and when closing. Skipped while you're typing in a cell (it waits). |
| **Phone** | When the app opens; right after saving a transaction (if online); a **Sync** button; and in the background about every 15 minutes (Android's shortest interval). |

Offline is normal: the phone keeps its changes until it can send them.

---

## 11. Desktop changes

**Database (schema 21):**

| Change | Why |
|---|---|
| `uid TEXT UNIQUE` on `account`, `category`, `payee`, `tag`, `txn`, `schedule`. Existing rows get random UUIDs. | Ids both sides agree on. The desktop's own numbers stay as they are. |
| `account.sync INTEGER NOT NULL DEFAULT 0` | Which accounts go to the phone. |
| `sync_device (id, uid, name, key, include_scheduled, applied_through, last_seen)` | Paired phones. |
| `sync_op (device_id, op_id UNIQUE, applied_at, result, message)` | Changes already applied, so none is applied twice. Kept 90 days. |
| `meta`: mailbox type, address, path, user name and password; the file's `fileId` | Where the mailbox is. These live in the (encrypted) data file. |

**New code:**

- `Mailbox` with `FolderMailbox` and `WebDavMailbox` (plain HTTP:
  `PROPFIND`, `GET`, `PUT`, `MOVE`, `DELETE`; no library needed).
- `SyncCrypto` (the file format in [Encryption](#5-encryption)).
- `SnapshotWriter`, `ChangeApplier` and `SyncEngine` (runs a sync, on a
  background thread, applying on the Swing thread).

**Screens:**

- **Preferences > Sync:**
  - the mailbox, with **Test**;
  - the accounts to sync, as tick boxes;
  - the paired phones, with **Pair a Phone…** (the QR code) and **Remove**.
- **File > Sync Now**, and a small status in the window ("Synced 2 min ago" or
  "Sync failed: …").

---

## 12. The mobile app

**Built with Kotlin Multiplatform and Compose Multiplatform.**

- Kotlin reads much like Java, and Android Studio is free.
- Almost everything is written once and shared between Android and iOS:
  - the sync, encryption and data code;
  - most of the screens.
- Only small parts are written per platform: the camera, secure key storage,
  and background scheduling.

| Piece | Android | iOS (later) |
|---|---|---|
| Screens | Compose Multiplatform (shared) | same |
| Local database | SQLDelight + SQLCipher (shared) | same |
| Sync, JSON, WebDAV | Ktor + kotlinx.serialization (shared) | same |
| AES-GCM | `javax.crypto` | CryptoKit |
| Key storage | Android Keystore | Keychain |
| Background sync | WorkManager | BGTaskScheduler |
| Camera / photos | CameraX / photo picker | PhotosUI |

**Screens:**

1. **Accounts:** synced accounts with their balances, and envelopes under their
   parents. Totals at the top.
2. **Ledger:** one account's transactions, newest first, with a running balance.
   Pending ones are marked. Read-only ones are greyed.
3. **Add / Edit transaction:**
   - date, payee, amount (payment or deposit), category or transfer, tag, memo
     and status;
   - autocomplete from the snapshot's lists;
   - a camera button for receipts;
   - delete.
4. **Upcoming:** scheduled transactions (if turned on), with **Enter**.
5. **Sync & settings:**
   - last sync, and what didn't go through;
   - pairing;
   - the app lock.

The phone keeps two things in its database:
- the last **snapshot**, which it replaces whenever a new one arrives;
- its **pending ops**, removed as `results` confirm them.

What you see is the snapshot with the pending ops applied on top.

---

## 13. Reaching the NAS from away

Two ways, with the same app either way:

1. **Tailscale (recommended).** Install it on the Synology (from Package Center)
   and on the phone. The phone reaches the NAS as if at home, from anywhere,
   and **nothing is opened to the internet**. The mailbox address is the NAS's
   Tailscale name.
2. **WebDAV published to the internet.** Synology DDNS, a Let's Encrypt
   certificate, and the WebDAV HTTPS port (5006) forwarded on the router. It
   works without a VPN app, but the NAS is reachable from the internet, so
   the dedicated, folder-only Synology user matters more.

The files are encrypted either way, but keeping the NAS off the internet is
still the safer choice.

---

## 14. Phases

1. **Desktop:**
   - schema 21;
   - the `Mailbox`, `SyncCrypto`, snapshot and change-applying code;
   - Preferences > Sync, pairing and Sync Now.

   Tested with a Folder mailbox and a pretend phone in JUnit, before any phone
   app exists.
2. **Android, part 1:**
   - pairing (QR);
   - reading snapshots;
   - the accounts, envelope and ledger screens;
   - adding, editing and deleting transactions;
   - background sync.
3. **Receipt photos,** both sides.
4. **Upcoming (scheduled)** on the phone.
5. **iOS.**
6. **More mailboxes** (Dropbox, Google Drive, …), as wanted.

---

## 15. Testing

- **Shared test files:** a folder of sample snapshots and change files, with
  their encrypted forms under a fixed test key.
  - Both the desktop tests (JUnit) and the phone tests (Kotlin) read and write
    them.
  - So the two sides can't quietly disagree about the format.
- **Desktop tests** cover:
  - each kind of op;
  - conflicts and refusals;
  - applying the same op twice;
  - a file that doesn't decrypt;
  - a sync interrupted halfway (nothing applied);
  - undo.
- **Phone tests** cover:
  - pending ops shown on top of a snapshot;
  - results clearing them;
  - working offline.

---

## 16. Not in the first version

- Editing **splits** on the phone (they're shown, read-only).
- **Bank downloads, reconciling, loans' terms, scheduling rules, account setup**:
  all stay on the desktop.
- Photos **from the desktop to the phone**.
- **Several desktops** syncing one data file.
- **Mailboxes** other than Folder and WebDAV.
