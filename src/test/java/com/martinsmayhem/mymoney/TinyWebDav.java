package com.martinsmayhem.mymoney;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

/** Just enough of a WebDAV server, over a folder, to test WebDavMailbox against. */
final class TinyWebDav implements AutoCloseable {

    private final HttpServer server;
    private final Path root;
    private final String auth;
    /** Answer MOVE with 502, as some reverse proxies make a server do. */
    volatile boolean refuseMove;
    /** Every request answered, as "METHOD path". */
    final java.util.List<String> requests = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    TinyWebDav(Path root, String user, String password) throws IOException {
        this.root = root;
        this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Path file(URI uri) {
        String p = URLDecoder.decode(uri.getRawPath(), StandardCharsets.UTF_8).replaceAll("^/+|/+$", "");
        return root.resolve(p).normalize();
    }

    private void handle(HttpExchange x) throws IOException {
        requests.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
        try (x) {
            if (!auth.equals(x.getRequestHeaders().getFirst("Authorization"))) {
                reply(x, 401, new byte[0]);
                return;
            }
            Path f = file(x.getRequestURI());
            switch (x.getRequestMethod()) {
                case "GET" -> {
                    if (Files.isRegularFile(f)) {
                        reply(x, 200, Files.readAllBytes(f));
                    } else {
                        reply(x, 404, new byte[0]);
                    }
                }
                case "PUT" -> {
                    if (!Files.isDirectory(f.getParent())) {
                        reply(x, 409, new byte[0]);
                        return;
                    }
                    Files.write(f, x.getRequestBody().readAllBytes());
                    reply(x, 201, new byte[0]);
                }
                case "MKCOL" -> {
                    if (Files.exists(f)) {
                        reply(x, 405, new byte[0]);
                    } else if (!Files.isDirectory(f.getParent())) {
                        reply(x, 409, new byte[0]);             // its parent isn't there
                    } else {
                        Files.createDirectory(f);
                        reply(x, 201, new byte[0]);
                    }
                }
                case "MOVE" -> {
                    if (refuseMove) {
                        reply(x, 502, new byte[0]);
                        return;
                    }
                    Path to = file(URI.create(x.getRequestHeaders().getFirst("Destination")));
                    Files.move(f, to, StandardCopyOption.REPLACE_EXISTING);
                    reply(x, 201, new byte[0]);
                }
                case "DELETE" -> {
                    if (!Files.exists(f)) {
                        reply(x, 404, new byte[0]);
                        return;
                    }
                    try (Stream<Path> s = Files.walk(f)) {
                        for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(p);
                        }
                    }
                    reply(x, 204, new byte[0]);
                }
                case "PROPFIND" -> {
                    if (!Files.isDirectory(f)) {
                        reply(x, 404, new byte[0]);
                        return;
                    }
                    StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">");
                    b.append(entry(x.getRequestURI().getRawPath(), true));
                    try (Stream<Path> s = Files.list(f)) {
                        for (Path p : s.toList()) {
                            String href = x.getRequestURI().getRawPath().replaceAll("/+$", "") + "/"
                                    + java.net.URLEncoder.encode(p.getFileName().toString(), StandardCharsets.UTF_8)
                                    .replace("+", "%20");
                            b.append(entry(href, Files.isDirectory(p)));
                        }
                    }
                    b.append("</D:multistatus>");
                    reply(x, 207, b.toString().getBytes(StandardCharsets.UTF_8));
                }
                default -> reply(x, 405, new byte[0]);
            }
        }
    }

    private static String entry(String href, boolean folder) {
        return "<D:response><D:href>" + href + "</D:href><D:propstat><D:prop><D:resourcetype>"
                + (folder ? "<D:collection/>" : "") + "</D:resourcetype></D:prop>"
                + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>";
    }

    private static void reply(HttpExchange x, int status, byte[] body) throws IOException {
        x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = x.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
