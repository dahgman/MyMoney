package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A mailbox that's a folder on disk: the NAS share as it's mounted, or a
 * folder kept in step by Synology Drive, Dropbox, Nextcloud and the like.
 */
final class FolderMailbox implements Mailbox {

    private final Path root;

    FolderMailbox(Path root) {
        this.root = root;
    }

    private Path resolve(String path) {
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root.normalize())) {
            throw new IllegalArgumentException("outside the mailbox: " + path);
        }
        return p;
    }

    @Override
    public List<String> list(String folder, boolean withTemp) throws IOException {
        Path dir = resolve(folder);
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> withTemp || !n.endsWith(".tmp")).sorted().forEach(names::add);
        }
        return names;
    }

    @Override
    public byte[] read(String path) throws IOException {
        Path p = resolve(path);
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        Path p = resolve(path);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        Files.deleteIfExists(resolve(path));
    }

    @Override
    public void deleteFolder(String folder) throws IOException {
        Path dir = resolve(folder);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public String describe() {
        return "the folder " + root;
    }
}
