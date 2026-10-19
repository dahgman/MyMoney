package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Files on network shares: SQLite locks them with a dot-file instead of byte-range locks. */
class NetworkShareTest {

    private static final String DOTFILE = "multipleciphers-unix-dotfile";

    @Test
    void networkSharesOnLinuxAndMac() {
        for (String type : new String[] {"cifs", "smb3", "smbfs", "nfs", "nfs4", "afpfs", "webdav", "fuse.sshfs"}) {
            assertEquals(DOTFILE, Database.vfsForType(type, "Linux"), type);
        }
        assertEquals(DOTFILE, Database.vfsForType("smbfs", "Mac OS X"));
    }

    @Test
    void localDisksAndWindowsKeepTheUsualLocking() {
        for (String type : new String[] {"ext4", "btrfs", "xfs", "apfs", "tmpfs", "zfs", "", null}) {
            assertNull(Database.vfsForType(type, "Linux"), String.valueOf(type));
        }
        assertNull(Database.vfsForType("cifs", "Windows 11"), "Windows' own locking works over SMB");
    }

    /** An automounted share (like /mnt/thehive-home): Java says "autofs"; the mount table says "cifs". */
    @Test
    void theMountTableSeesPastAutofs() {
        List<String> mounts = List.of(
                "/dev/nvme0n1p2 / ext4 rw,relatime 0 0",
                "systemd-1 /mnt/thehive-home autofs rw,relatime,fd=49 0 0",
                "//thehive.asylum.martinsmayhem.com/home /mnt/thehive-home cifs rw,relatime,vers=3.1.1 0 0",
                "/dev/sdb1 /media/mike/USB\\040Stick vfat rw 0 0");
        assertEquals("cifs", Database.mountType(mounts, "/mnt/thehive-home/Money/Household/Household.MyMoney", "autofs"));
        assertEquals("cifs", Database.mountType(mounts, "/mnt/thehive-home", "autofs"));
        assertEquals("ext4", Database.mountType(mounts, "/home/mike/Documents/Household.MyMoney", "?"));
        assertEquals("vfat", Database.mountType(mounts, "/media/mike/USB Stick/Household.MyMoney", "?"),
                "spaces in mount points");
        assertEquals("ext4", Database.mountType(mounts, "/mnt/thehive-homework/x", "?"), "not a prefix match");
    }
}
