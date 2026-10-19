package com.martinsmayhem.mymoney;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A mailbox on a WebDAV server: the Synology's WebDAV Server package,
 * Nextcloud, and the like. Plain HTTP requests: PROPFIND to list, GET, PUT,
 * MOVE (for all-at-once writes), DELETE and MKCOL.
 */
final class WebDavMailbox implements Mailbox {

    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>";

    private final String base;              // "https://nas:5006/MyMoney-Sync", no trailing slash
    private final String auth;              // the Basic authorization header, or null
    private final HttpClient http;
    /**
     * Set once a MOVE has failed: behind some reverse proxies (a path prefix,
     * or HTTPS in front of HTTP) the server refuses a MOVE whose Destination
     * names the proxy's address, often with 502. Files are then written in
     * place instead (a reader that catches one half-written can't decrypt it,
     * so leaves it for next time).
     */
    private volatile boolean moveRefused;

    /**
     * @param url  the server, like "https://thehive:5006"
     * @param path the mailbox's folder on it, like "/MyMoney-Sync"
     */
    WebDavMailbox(String url, String path, String user, String password) {
        String u = url.trim().replaceAll("/+$", "");
        String p = path == null ? "" : path.trim().replaceAll("^/+|/+$", "");
        this.base = p.isEmpty() ? u : u + "/" + encodePath(p);
        this.auth = user == null || user.isBlank() ? null : "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static String encodePath(String path) {
        StringBuilder b = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append('/');
            }
            b.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return b.toString();
    }

    private URI uri(String path, boolean folder) {
        String p = encodePath(path);
        return URI.create(base + (p.isEmpty() ? "" : "/" + p) + (folder ? "/" : ""));
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return b;
    }

    private HttpResponse<byte[]> send(HttpRequest r) throws IOException {
        try {
            HttpResponse<byte[]> res = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 401) {
                throw new IOException("the WebDAV server refused the user name or password");
            }
            return res;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void check(HttpResponse<?> res, String what) throws IOException {
        int s = res.statusCode();
        if (s < 200 || s >= 300) {
            throw new IOException(what + ": the WebDAV server answered " + s);
        }
    }

    @Override
    public List<String> list(String folder, boolean withTemp) throws IOException {
        HttpResponse<byte[]> res = send(request(uri(folder, true)).header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY)).build());
        if (res.statusCode() == 404) {
            return new ArrayList<>();
        }
        check(res, "listing " + folder);
        return fileNames(res.body(), withTemp);
    }

    /** The plain files (not folders; "*.tmp" ones only if withTemp) in a PROPFIND answer. */
    static List<String> fileNames(byte[] xml, boolean withTemp) throws IOException {
        List<String> names = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList responses = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml))
                    .getElementsByTagNameNS("DAV:", "response");
            for (int i = 0; i < responses.getLength(); i++) {
                Element r = (Element) responses.item(i);
                if (r.getElementsByTagNameNS("DAV:", "collection").getLength() > 0) {
                    continue;                                   // a folder (the listed one itself, too)
                }
                NodeList href = r.getElementsByTagNameNS("DAV:", "href");
                if (href.getLength() == 0) {
                    continue;
                }
                String h = href.item(0).getTextContent().trim().replaceAll("/+$", "");
                String name = URLDecoder.decode(h.substring(h.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                if (!name.isEmpty() && (withTemp || !name.endsWith(".tmp"))) {
                    names.add(name);
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("the WebDAV server's listing couldn't be read: " + e.getMessage(), e);
        }
        names.sort(null);
        return names;
    }

    @Override
    public byte[] read(String path) throws IOException {
        HttpResponse<byte[]> res = send(request(uri(path, false)).GET().build());
        if (res.statusCode() == 404) {
            return null;
        }
        check(res, "reading " + path);
        return res.body();
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        makeFolders(path);
        if (moveRefused) {
            put(uri(path, false), data, path);
            return;
        }
        URI tmp = uri(path + ".tmp", false);
        put(tmp, data, path);
        HttpResponse<byte[]> moved = send(request(tmp).header("Destination", uri(path, false).toString())
                .header("Overwrite", "T").method("MOVE", HttpRequest.BodyPublishers.noBody()).build());
        int s = moved.statusCode();
        if (s >= 200 && s < 300) {
            return;
        }
        // The server (or a proxy in front of it) won't MOVE: write it in place, from now on too.
        moveRefused = true;
        put(uri(path, false), data, path);
        send(request(tmp).DELETE().build());
    }

    private void put(URI uri, byte[] data, String path) throws IOException {
        check(send(request(uri).PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build()), "writing " + path);
    }

    /** True once the server has refused a MOVE (files are written in place since). */
    boolean writesInPlace() {
        return moveRefused;
    }

    /**
     * Makes the mailbox's own folder and each folder above path (MKCOL; one
     * that's already there is fine).
     */
    private void makeFolders(String path) throws IOException {
        makeFolder("");
        String[] parts = path.split("/");
        StringBuilder folder = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            folder.append(folder.length() == 0 ? "" : "/").append(parts[i]);
            makeFolder(folder.toString());
        }
    }

    private void makeFolder(String folder) throws IOException {
        HttpResponse<byte[]> res = send(request(uri(folder, true))
                .method("MKCOL", HttpRequest.BodyPublishers.noBody()).build());
        int s = res.statusCode();
        if (s != 201 && s != 405 && s != 301 && s != 200) {        // 405: it's there already
            throw new IOException("making the folder " + (folder.isEmpty() ? base : folder)
                    + ": the WebDAV server answered " + s);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        HttpResponse<byte[]> res = send(request(uri(path, false)).DELETE().build());
        if (res.statusCode() != 404) {
            check(res, "deleting " + path);
        }
    }

    @Override
    public void deleteFolder(String folder) throws IOException {
        HttpResponse<byte[]> res = send(request(uri(folder, true)).DELETE().build());
        if (res.statusCode() != 404) {
            check(res, "deleting " + folder);
        }
    }

    @Override
    public String describe() {
        return "WebDAV at " + base;
    }
}
