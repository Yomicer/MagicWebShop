package com.magic.webshop.web;

import com.magic.webshop.config.PluginConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Standalone HTTP server (JDK built-in) that serves the web shop on its own
 * port. All request handling is delegated to {@link RequestRouter}, which is
 * shared with the optional shared-port Netty handler.
 */
public class WebServer {

    private final PluginConfig config;
    private final RequestRouter router;
    private final Logger logger;

    private HttpServer server;
    private int boundPort;

    public WebServer(PluginConfig config, RequestRouter router, Logger logger) {
        this.config = config;
        this.router = router;
        this.logger = logger;
    }

    public int getBoundPort() {
        return boundPort;
    }

    public void start() throws IOException {
        int desired = config.getWebPort();
        boolean auto = desired <= 0;
        try {
            server = HttpServer.create(new InetSocketAddress(config.getBind(), auto ? 0 : desired), 0);
        } catch (IOException e) {
            if (auto) throw e;
            logger.warning("Web port " + desired + " is unavailable (" + e.getMessage()
                    + "); automatically selecting a free port.");
            server = HttpServer.create(new InetSocketAddress(config.getBind(), 0), 0);
        }
        boundPort = server.getAddress().getPort();
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", this::handle);
        server.start();
        logger.info("Web server listening on " + config.getBind() + ":" + boundPort);
        if (boundPort != config.getWebPort()) {
            logger.warning("NOTE: using auto-selected port " + boundPort
                    + ". For cross-server trading set a fixed, reachable web.port so peers can find this server.");
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
                if (!e.getValue().isEmpty()) headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) { body = in.readAllBytes(); }
            Map<String, String> query = RequestRouter.parseQuery(ex.getRequestURI().getRawQuery());

            RequestRouter.Response r = router.handle(ex.getRequestMethod(),
                    ex.getRequestURI().getPath(), query, headers, body);

            ex.getResponseHeaders().set("Content-Type", r.contentType);
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            r.headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            ex.sendResponseHeaders(r.status, r.body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(r.body); }
        } catch (Exception e) {
            logger.warning("HTTP handler error on " + ex.getRequestURI() + ": " + e.getMessage());
            try {
                byte[] b = "{\"ok\":false,\"error\":\"Server error\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            } catch (IOException ignored) { }
        }
    }
}
