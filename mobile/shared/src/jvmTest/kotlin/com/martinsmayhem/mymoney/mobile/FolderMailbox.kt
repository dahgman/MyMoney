package com.martinsmayhem.mymoney.mobile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A mailbox in a folder, as the desktop's FolderMailbox: for tests on the computer. */
class FolderMailbox(private val root: Path) : Mailbox {
    override suspend fun list(folder: String, withTemp: Boolean): List<String> {
        val dir = root.resolve(folder)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }
                .filter { withTemp || !it.endsWith(".tmp") }.sorted().toList()
        }
    }

    override suspend fun read(path: String): ByteArray? = root.resolve(path).let { if (Files.isRegularFile(it)) Files.readAllBytes(it) else null }

    override suspend fun write(path: String, data: ByteArray) {
        val p = root.resolve(path)
        Files.createDirectories(p.parent)
        val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
        Files.write(tmp, data)
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun delete(path: String) {
        Files.deleteIfExists(root.resolve(path))
    }
}
