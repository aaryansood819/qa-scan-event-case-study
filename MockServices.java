package qa.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import qa.util.MiniJson;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-process MOCK of (1) the rewards-app backend scan API and (2) a CDP /
 * downstream-destination query API. ILLUSTRATIVE only: no real endpoints,
 * credentials or customer data.
 *
 * Fault injection (test-only) lets us prove the tests CATCH the incident:
 *   "none"            healthy behaviour
 *   "drop_ios_mkt_b"  event silently never reaches destination for ios + MKT_B
 *   "duplicate"       destination receives every event twice
 */
public final class MockServices {
    public static final int DAILY_CAP = 3;                 // assumption
    public static final long DELIVERY_DELAY_MS = 400;      // simulated async CDP latency

    private record Reply(int status, Map<String, Object> body) {}

    private final Object lock = new Object();
    private volatile String fault;
    private final Map<String, Map<String, String>> scansByUser = new HashMap<>(); // user -> code -> status
    private final Map<String, Reply> idempotency = new HashMap<>();
    private final List<Map<String, Object>> destination = new ArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    private HttpServer server;

    public String start(String fault) throws IOException {
        this.fault = fault == null || fault.isBlank() ? "none" : fault;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/scans", this::onScan);
        server.createContext("/destination/v1/events", this::onEvents);
        server.createContext("/_admin/fault", this::onFault);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        scheduler.shutdownNow();
    }

    // ----------------------------- handlers -----------------------------
    private void onScan(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, Map.of("error", "METHOD_NOT_ALLOWED")); return; }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) { send(ex, 401, Map.of("error", "UNAUTHENTICATED")); return; }
        String idemKey = ex.getRequestHeaders().getFirst("Idempotency-Key");
        Map<String, Object> body = MiniJson.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Reply r = handleScan(idemKey, body);
        send(ex, r.status(), r.body());
    }

    private Reply handleScan(String idemKey, Map<String, Object> body) {
        synchronized (lock) {
            if (idemKey != null && idempotency.containsKey(idemKey)) return idempotency.get(idemKey);
            String code = str(body.get("code")), user = str(body.get("userId"));
            String market = str(body.get("market")), platform = str(body.get("platform"));
            Reply reply;
            boolean valid = !code.isEmpty() && !user.isEmpty()
                    && (market.equals("MKT_A") || market.equals("MKT_B"))
                    && (platform.equals("android") || platform.equals("ios"))
                    && (code.startsWith("VALID-") || code.startsWith("INELIGIBLE-"));
            if (!valid) {
                reply = new Reply(400, Map.of("error", "INVALID_REQUEST"));
            } else {
                Map<String, String> seen = scansByUser.computeIfAbsent(user, k -> new HashMap<>());
                long accepted = seen.values().stream().filter("ACCEPTED"::equals).count();
                if (seen.containsKey(code)) {
                    reply = rejected("DUPLICATE");
                } else if (code.startsWith("INELIGIBLE-")) {
                    seen.put(code, "REJECTED");
                    reply = rejected("NOT_ELIGIBLE");
                } else if (accepted >= DAILY_CAP) {
                    seen.put(code, "REJECTED");
                    reply = rejected("DAILY_CAP_REACHED");
                } else {
                    seen.put(code, "ACCEPTED");
                    String scanId = UUID.randomUUID().toString(), eventId = UUID.randomUUID().toString();
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("scanId", scanId);
                    resp.put("status", "ACCEPTED");
                    resp.put("points", 10);
                    resp.put("eventId", eventId);
                    reply = new Reply(200, resp);
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("eventId", eventId);
                    ev.put("event", "Scan Completed");
                    ev.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
                    ev.put("userId", user);
                    ev.put("market", market);
                    ev.put("platform", platform);
                    ev.put("scanId", scanId);
                    ev.put("outcome", "ACCEPTED");
                    deliverLater(ev);
                }
            }
            if (idemKey != null) idempotency.put(idemKey, reply);
            return reply;
        }
    }

    private Reply rejected(String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("scanId", UUID.randomUUID().toString());
        b.put("status", "REJECTED");
        b.put("reason", reason);
        b.put("points", 0);
        return new Reply(200, b);
    }

    private void deliverLater(Map<String, Object> event) {
        scheduler.schedule(() -> {
            synchronized (lock) {
                if ("drop_ios_mkt_b".equals(fault)
                        && "ios".equals(event.get("platform")) && "MKT_B".equals(event.get("market"))) {
                    return; // silently lost: the production incident pattern
                }
                destination.add(event);
                if ("duplicate".equals(fault)) destination.add(new LinkedHashMap<>(event));
            }
        }, DELIVERY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void onEvents(HttpExchange ex) throws IOException {
        String userId = "";
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String kv : q.split("&")) {
            String[] p = kv.split("=", 2);
            if (p[0].equals("userId") && p.length == 2) userId = URLDecoder.decode(p[1], StandardCharsets.UTF_8);
        }
        List<Object> mine = new ArrayList<>();
        synchronized (lock) {
            for (Map<String, Object> e : destination) if (userId.equals(e.get("userId"))) mine.add(e);
        }
        send(ex, 200, Map.of("events", mine));
    }

    private void onFault(HttpExchange ex) throws IOException {
        Map<String, Object> body = MiniJson.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        fault = str(body.getOrDefault("fault", "none"));
        send(ex, 200, Map.of("fault", fault));
    }

    // ------------------------------ utils --------------------------------
    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static void send(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] data = MiniJson.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        try (var os = ex.getResponseBody()) { os.write(data); }
    }
}
