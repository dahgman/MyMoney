package com.martinsmayhem.mymoney.mobile

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import io.ktor.http.decodeURLPart
import kotlin.io.encoding.Base64

/**
 * A mailbox on a WebDAV server (the Synology's WebDAV Server package, Nextcloud,
 * ...), like the desktop's WebDavMailbox: PROPFIND, GET, PUT, MOVE, DELETE and
 * MKCOL. If the server (or a reverse proxy in front of it) refuses MOVE, files
 * are written in place from then on.
 */
class WebDavMailbox(
    private val http: HttpClient,
    url: String,
    path: String,
    user: String,
    password: String,
) : Mailbox {

    private val base: String
    private val auth: String? = if (user.isBlank()) null
    else "Basic " + Base64.encode("$user:$password".encodeToByteArray())
    private var moveRefused = false

    init {
        val u = url.trim().trimEnd('/')
        val p = path.trim().trim('/')
        base = if (p.isEmpty()) u else "$u/${encodePath(p)}"
    }

    private fun encodePath(path: String) = path.split('/').filter { it.isNotEmpty() }
        .joinToString("/") { it.encodeURLPathPart() }

    private fun url(path: String, folder: Boolean): String {
        val p = encodePath(path)
        return base + (if (p.isEmpty()) "" else "/$p") + (if (folder) "/" else "")
    }

    private suspend fun send(method: String, url: String, body: ByteArray? = null,
                             headers: Map<String, String> = emptyMap()): HttpResponse {
        val res = http.request(url) {
            this.method = HttpMethod(method)
            auth?.let { header("Authorization", it) }
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) setBody(body)
        }
        if (res.status.value == 401) throw MailboxAuthException("The NAS refused the user name or password.")
        return res
    }

    private fun check(res: HttpResponse, what: String) {
        if (res.status.value !in 200..299) throw MailboxException("$what: the WebDAV server answered ${res.status.value}")
    }

    override suspend fun list(folder: String, withTemp: Boolean): List<String> {
        val res = send("PROPFIND", url(folder, true), PROPFIND.encodeToByteArray(),
            mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"))
        if (res.status.value == 404) return emptyList()
        check(res, "listing $folder")
        return fileNames(res.bodyAsBytes().decodeToString(), withTemp)
    }

    override suspend fun read(path: String): ByteArray? {
        val res = send("GET", url(path, false))
        if (res.status.value == 404) return null
        check(res, "reading $path")
        return res.bodyAsBytes()
    }

    override suspend fun write(path: String, data: ByteArray) {
        makeFolders(path)
        if (moveRefused) {
            check(send("PUT", url(path, false), data), "writing $path")
            return
        }
        val tmp = url("$path.tmp", false)
        check(send("PUT", tmp, data), "writing $path")
        val moved = send("MOVE", tmp, headers = mapOf("Destination" to url(path, false), "Overwrite" to "T"))
        if (moved.status.value in 200..299) return
        moveRefused = true
        check(send("PUT", url(path, false), data), "writing $path")
        send("DELETE", tmp)
    }

    private suspend fun makeFolders(path: String) {
        makeFolder("")
        val parts = path.split('/').filter { it.isNotEmpty() }.dropLast(1)
        for (i in parts.indices) makeFolder(parts.subList(0, i + 1).joinToString("/"))
    }

    private suspend fun makeFolder(folder: String) {
        val s = send("MKCOL", url(folder, true)).status.value
        if (s != 201 && s != 405 && s != 301 && s != 200) {
            throw MailboxException("making the folder ${folder.ifEmpty { base }}: the WebDAV server answered $s")
        }
    }

    override suspend fun delete(path: String) {
        val res = send("DELETE", url(path, false))
        if (res.status.value != 404) check(res, "deleting $path")
    }

    companion object {
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

        private val RESPONSE = Regex("<(?:[A-Za-z0-9]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?response>",
            RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("<(?:[A-Za-z0-9]+:)?href\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?href>",
            RegexOption.DOT_MATCHES_ALL)
        private val COLLECTION = Regex("<(?:[A-Za-z0-9]+:)?collection\\b")

        /** The plain files in a PROPFIND answer (whatever prefix the server gives the DAV: namespace). */
        fun fileNames(xml: String, withTemp: Boolean): List<String> =
            RESPONSE.findAll(xml).mapNotNull { r ->
                val body = r.groupValues[1]
                if (COLLECTION.containsMatchIn(body)) return@mapNotNull null
                val href = HREF.find(body)?.groupValues?.get(1)?.trim()?.trimEnd('/') ?: return@mapNotNull null
                href.substringAfterLast('/').decodeURLPart().takeIf { it.isNotEmpty() && (withTemp || !it.endsWith(".tmp")) }
            }.sorted().toList()
    }
}
