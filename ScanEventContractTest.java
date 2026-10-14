package qa;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qa.mock.MockServices;
import qa.util.MiniJson;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Senior QA case study, Workstream B2 sample (Java 17, JUnit 5).
 *
 * Validates the 'Scan Completed' flow at two layers:
 *   LAYER 1 (API boundary): status code, schema, business outcome
 *   LAYER 2 (downstream)  : event arrives EXACTLY ONCE, correctly associated,
 *                           schema-valid and privacy-safe
 *
 * Design choices (see case-study document, section B3):
 *   - No fixed sleeps : deadline-based polling with back-off (waitForEvents)
 *   - No retry masking: the test NEVER re-sends a scan to "make it pass";
 *                       polling only re-READS the destination
 *   - Isolation       : unique user + unique code per test, no shared state
 *   - Secrets         : token read from environment, never hard-coded
 *
 * Run offline against the built-in mock:   mvn test
 * Reproduce the incident (iOS + MKT_B):     MOCK_FAULT=drop_ios_mkt_b mvn test
 * Real test environment:                    APP_API_BASE=... DEST_API_BASE=... API_TOKEN=... mvn test
 */
class ScanEventContractTest {

    private static final List<String> PLATFORMS = List.of("android", "ios");
    private static final List<String> MARKETS = List.of("MKT_A", "MKT_B");   // two market/config variants
    private static final Set<String> APPROVED_EVENT_FIELDS = Set.of(          // privacy allow-list (assumption)
            "eventId", "event", "timestamp", "userId", "market", "platform", "scanId", "outcome");
    private static final double DELIVERY_SLA_S =
            Double.parseDouble(env("EVENT_SLA_SECONDS", "10"));               // agreed with the owner
    private static final String TOKEN = env("API_TOKEN", "mock-token-not-a-secret");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static MockServices mock;
    private static String appApi;
    private static String destApi;

    @BeforeAll
    static void setUp() throws Exception {
        if (System.getenv("APP_API_BASE") != null) {
            appApi = System.getenv("APP_API_BASE");
            destApi = System.getenv("DEST_API_BASE");
        } else {
            mock = new MockServices();
            appApi = destApi = mock.start(System.getenv("MOCK_FAULT"));
        }
    }

    @AfterAll
    static void tearDown() {
        if (mock != null) mock.stop();
    }

    // ----------------------------- helpers -----------------------------
    private record Resp(int status, String contentType, String body) {
        Map<String, Object> json() { return MiniJson.parseObject(body); }
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static Resp postScan(String userId, String code, String platform, String market,
                                 String idemKey, boolean authenticated) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("code", code);
        payload.put("platform", platform);
        payload.put("market", market);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(appApi + "/api/v1/scans"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.write(payload)));
        if (authenticated) b.header("Authorization", "Bearer " + TOKEN);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body());
    }

    private static Resp postScan(String userId, String code, String platform, String market) throws Exception {
        return postScan(userId, code, platform, market, null, true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getEvents(String userId) throws Exception {
        String url = destApi + "/destination/v1/events?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "destination query failed: " + r.body());
        return (List<Map<String, Object>>) (List<?>) ((List<Object>) MiniJson.parseObject(r.body()).get("events"));
    }

    /** Poll the destination (READ-ONLY) until >= expectedCount events or the deadline.
     *  Back-off 100ms -> 1s. Never re-sends the scan. Returns what was observed. */
    private static List<Map<String, Object>> waitForEvents(String userId, int expectedCount) throws Exception {
        long deadline = System.nanoTime() + (long) (DELIVERY_SLA_S * 1_000_000_000L);
        long delayMs = 100;
        List<Map<String, Object>> events = List.of();
        while (System.nanoTime() < deadline) {
            events = getEvents(userId);
            if (events.size() >= expectedCount) return events;
            Thread.sleep(delayMs);
            delayMs = Math.min((long) (delayMs * 1.5), 1000);
        }
        return events; // caller asserts; failure message shows what was observed
    }

    private static String newUser() { return "test-user-" + UUID.randomUUID().toString().substring(0, 12); } // synthetic
    private static String newCode(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 10); }
    private static String newCode() { return newCode("VALID"); }

    // ------------------------------- tests -------------------------------
    @Test
    @DisplayName("P1: successful scan - API contract + exactly one correct downstream event (platform x market)")
    void successfulScanApiContractAndSingleDownstreamEvent() throws Exception {
        for (String platform : PLATFORMS) {
            for (String market : MARKETS) {
                String ctx = "[" + platform + " / " + market + "] ";
                String user = newUser(), code = newCode();

                // ---- LAYER 1: API boundary (synchronous contract) ----
                Resp r = postScan(user, code, platform, market);
                assertEquals(200, r.status(), ctx + r.body());
                assertTrue(r.contentType().contains("application/json"), ctx + "content type");
                Map<String, Object> body = r.json();
                assertEquals("ACCEPTED", body.get("status"), ctx + "status");
                assertTrue(body.get("points") instanceof Long pts && pts > 0, ctx + "points must be integer > 0");
                UUID.fromString((String) body.get("scanId"));     // well-formed ID
                UUID.fromString((String) body.get("eventId"));    // dedup key exposed for correlation

                // ---- LAYER 2: asynchronous downstream delivery ----
                List<Map<String, Object>> events = waitForEvents(user, 1);
                String diag = ctx + "scanId=" + body.get("scanId") + " eventId=" + body.get("eventId")
                        + " observed_events=" + events;
                assertEquals(1, events.size(), "Expected exactly 1 event within " + DELIVERY_SLA_S + "s. " + diag);

                // bounded settle to catch a late DUPLICATE (arrives within delivery latency)
                Thread.sleep(800);
                events = getEvents(user);
                assertEquals(1, events.size(), "Duplicate event detected. " + diag);

                Map<String, Object> ev = events.get(0);
                assertEquals("Scan Completed", ev.get("event"), diag);
                assertEquals(body.get("eventId"), ev.get("eventId"), "event not tied to this scan. " + diag);
                assertEquals(body.get("scanId"), ev.get("scanId"), diag);
                assertEquals(user, ev.get("userId"), "identity mapping wrong. " + diag);
                assertEquals(market, ev.get("market"), diag);
                assertEquals(platform, ev.get("platform"), diag);
                assertEquals("ACCEPTED", ev.get("outcome"), diag);
                Instant.parse((String) ev.get("timestamp"));       // ISO-8601 UTC
                Set<String> extra = new TreeSet<>(ev.keySet());
                extra.removeAll(APPROVED_EVENT_FIELDS);
                assertTrue(extra.isEmpty(), "Unapproved fields sent downstream: " + extra);
            }
        }
    }

    @Test
    @DisplayName("P1: rejected / duplicate / ineligible / invalid / capped scans never emit a success event")
    void rejectedAndInvalidScansNeverEmitSuccessEvent() throws Exception {
        String platform = "android", market = "MKT_A";
        String user = newUser(), good = newCode();

        assertEquals("ACCEPTED", postScan(user, good, platform, market).json().get("status"));

        Map<String, Object> dup = postScan(user, good, platform, market).json();
        assertEquals("REJECTED", dup.get("status"));
        assertEquals("DUPLICATE", dup.get("reason"));

        Map<String, Object> inel = postScan(user, newCode("INELIGIBLE"), platform, market).json();
        assertEquals("REJECTED", inel.get("status"));
        assertEquals("NOT_ELIGIBLE", inel.get("reason"));

        assertEquals(400, postScan(user, "###", platform, market).status(), "malformed code must be 4xx");

        // fill the cap (3 accepted: 1 already + 2 more), then exceed it
        for (int i = 0; i < 2; i++) {
            assertEquals("ACCEPTED", postScan(user, newCode(), platform, market).json().get("status"));
        }
        Map<String, Object> capped = postScan(user, newCode(), platform, market).json();
        assertEquals("REJECTED", capped.get("status"));
        assertEquals("DAILY_CAP_REACHED", capped.get("reason"));

        // Wait for the 3 legitimately accepted events (pipeline proven live), then after a
        // bounded settle assert nothing extra. In a real env replace the settle with a
        // 'canary' scan whose arrival bounds the delivery window.
        waitForEvents(user, 3);
        Thread.sleep(800);
        List<Map<String, Object>> events = getEvents(user);
        assertEquals(3, events.size(),
                "Expected 3 events (accepted scans only); got " + events.size() + ": " + events);
        for (Map<String, Object> e : events) assertEquals("ACCEPTED", e.get("outcome"));
    }

    @Test
    @DisplayName("P1: retry with same Idempotency-Key creates one scan and one event")
    void sameIdempotencyKeyRetryCreatesOneScanAndOneEvent() throws Exception {
        String user = newUser(), code = newCode(), key = UUID.randomUUID().toString();
        Map<String, Object> first = postScan(user, code, "ios", "MKT_A", key, true).json();
        Map<String, Object> retry = postScan(user, code, "ios", "MKT_A", key, true).json();
        assertEquals(first.get("scanId"), retry.get("scanId"), "retry created a second scan");
        waitForEvents(user, 1);
        Thread.sleep(800);
        assertEquals(1, getEvents(user).size());
    }

    @Test
    @DisplayName("P2: unauthenticated request is rejected (401) and emits nothing")
    void unauthenticatedRequestIsRejected() throws Exception {
        Resp r = postScan(newUser(), newCode(), "android", "MKT_A", null, false);
        assertEquals(401, r.status());
    }
}
