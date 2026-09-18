import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strava auto-kudo.
 *
 * Flow:
 *   1. GET /dashboard/feed?feed_type=following&cursor=<c>   (list activities)
 *   2. For each activity id in the response, POST /feed/activity/<id>/kudo
 *   3. Read next cursor from the response, repeat.
 *
 * Compile & run (JDK 11+, no external deps):
 *   javac src/AutoKudo.java
 *   java  -cp src AutoKudo --dry-run
 *   java  -cp src AutoKudo --max-pages 5
 */
public class AutoKudo {

    // -------------------------------------------------------------------
    // Auth material — loaded from ../auth.curl at startup (paste the full
    // curl from Chrome DevTools whenever cookies expire).
    // -------------------------------------------------------------------
    private static final String AUTH_FILE = "auth.curl";
    private static String COOKIE;
    private static String CSRF_TOKEN;

    private static void loadAuth() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(AUTH_FILE);
        if (!java.nio.file.Files.exists(p)) {
            // Try project root relative to the class file location.
            p = java.nio.file.Paths.get(System.getProperty("user.dir"), AUTH_FILE);
        }
        if (!java.nio.file.Files.exists(p)) {
            throw new RuntimeException("auth.curl not found. Create it in the project root "
                    + "with your latest curl command from Chrome DevTools.");
        }
        String text = java.nio.file.Files.readString(p);
        // Strip lines starting with # so example text in comments doesn't get picked up.
        text = text.replaceAll("(?m)^\\s*#.*$", "");

        Matcher mCookie = Pattern.compile("-b\\s+'([^']+)'").matcher(text);
        if (!mCookie.find()) {
            mCookie = Pattern.compile("-b\\s+\"([^\"]+)\"").matcher(text);
            if (!mCookie.find()) throw new RuntimeException("No -b '<cookies>' found in auth.curl");
        }
        COOKIE = mCookie.group(1).trim();

        Matcher mCsrf = Pattern.compile("(?i)x-csrf-token:\\s*([^'\"\\s]+)").matcher(text);
        if (!mCsrf.find()) throw new RuntimeException("No X-CSRF-Token header found in auth.curl");
        CSRF_TOKEN = mCsrf.group(1).trim();

        System.out.println("Loaded auth from " + p.toAbsolutePath()
                + " (cookie=" + COOKIE.length() + " chars, csrf=" + CSRF_TOKEN.length() + " chars)");
    }

    private static final String BASE = "https://www.strava.com";
    private static final String FEED_URL = BASE + "/dashboard/feed";

    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36";

    // No more flat regexes for activity IDs / hasKudoed — we walk each JSON
    // entry object and pull fields from within the correct nested sub-object.
    // Only kept: patterns used inside a single entry substring.
    private static final Pattern P_ENTITY       = Pattern.compile("\"entity\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Pattern P_ID_STR       = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern P_HAS_KUDOED   = Pattern.compile("\"has[_]?[Kk]udoed\"\\s*:\\s*(true|false)");
    private static final Pattern P_CAN_KUDO     = Pattern.compile("\"can[_]?[Kk]udo\"\\s*:\\s*(true|false)");
    private static final Pattern P_ATHLETE_NAME = Pattern.compile("\"athleteName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ORIG_NAME    = Pattern.compile("\"originator_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ENTITY_ID    = Pattern.compile("\"entity_id\"\\s*:\\s*(\\d+)");
    private static final Pattern P_RANK         = Pattern.compile("\"rank\"\\s*:\\s*(\\d{10,16})");

    private final HttpClient http = buildHttpClient();

    /** Uses system proxy (same one Chrome uses) unless -Dhttps.proxyHost is set. */
    private static HttpClient buildHttpClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);

        String host = System.getProperty("https.proxyHost");
        String port = System.getProperty("https.proxyPort");
        if (host != null && port != null) {
            b.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(host, Integer.parseInt(port))));
            System.out.println("Using proxy " + host + ":" + port);
        } else {
            b.proxy(java.net.ProxySelector.getDefault());
            System.out.println("Using system default proxy selector");
        }
        return b.build();
    }

    // -------------------------------------------------------------------
    // Entry point / CLI
    // -------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        loadAuth();

        String  startCursor = null;
        int     maxPages    = 20;
        long    sleepMs     = 1500;
        boolean dryRun      = false;
        boolean debug       = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cursor":    startCursor = args[++i];                    break;
                case "--max-pages": maxPages    = Integer.parseInt(args[++i]);  break;
                case "--sleep":     sleepMs     = Long.parseLong(args[++i]);    break;
                case "--dry-run":   dryRun      = true;                         break;
                case "--debug":     debug       = true;                         break;
                default: System.err.println("Unknown arg: " + args[i]); System.exit(2);
            }
        }

        new AutoKudo().run(startCursor, maxPages, sleepMs, dryRun, debug);
    }

    // -------------------------------------------------------------------
    // Main loop: page through feed, kudo each activity.
    // -------------------------------------------------------------------
    private void run(String startCursor, int maxPages, long sleepMs, boolean dryRun, boolean debug)
            throws Exception {

        String cursor          = startCursor;
        int    pagesFetched    = 0;
        int    totalChecked    = 0;
        int    totalAlready    = 0;
        int    totalKudos      = 0;
        int    totalFailed     = 0;
        int    consecutiveEmpty= 0;
        final int  maxEmptyProbes = 5;              // stop after 5 empty responses in a row
        final long probeStepMs    = 6L * 60 * 60 * 1000; // jump 6h back when hasMore=false
        String error = null;

        try {
        for (int page = 1; page <= maxPages; page++) {
            System.out.println("Fetching page " + page + " with cursor=" + cursor);
            String body = fetchFeed(cursor);
            pagesFetched++;

            List<FeedEntry> entries = parseFeed(body);
            String nextCursor = extractNextCursor(body, cursor);

            int already = 0;
            for (FeedEntry e : entries) if (e.hasKudoed) already++;

            System.out.printf(
                "=== Page %d: %d kudo-able entries (%d already kudoed) — next cursor: %s%n",
                page, entries.size(), already, nextCursor);

            if (debug || nextCursor == null) {
                System.out.println("--- body sample (first 800 chars) ---");
                System.out.println(body.substring(0, Math.min(800, body.length())));
                System.out.println("--- end sample ---");
            }

            totalChecked += entries.size();
            totalAlready += already;

            for (FeedEntry e : entries) {
                String tag = "[" + e.kind + " " + e.id + " " + e.name + "]";
                if (e.hasKudoed) {
                    System.out.println("  · skip " + tag + " already kudoed");
                    continue;
                }
                if (!e.canKudo) {
                    System.out.println("  · skip " + tag + " can't kudo");
                    continue;
                }
                if (dryRun) {
                    System.out.println("  - would kudo " + tag);
                    continue;
                }
                if (giveKudo(e)) {
                    System.out.println("  \u2713 kudo " + tag);
                    totalKudos++;
                } else {
                    totalFailed++;
                }
                Thread.sleep(sleepMs + (long)(Math.random() * 700));
            }

            if (nextCursor == null || nextCursor.equals(cursor)) {
                // Strava says no more (hasMore:false). Jump backwards in time
                // and keep probing — this is what the Chrome plugin does.
                consecutiveEmpty++;
                if (consecutiveEmpty >= maxEmptyProbes) {
                    System.out.println("Stopping after " + consecutiveEmpty
                        + " consecutive empty probes.");
                    break;
                }
                long base;
                try { base = Long.parseLong(cursor); }
                catch (Exception e) { base = System.currentTimeMillis(); }
                long jumped = base - probeStepMs;
                cursor = String.valueOf(jumped);
                System.out.println("  (no next cursor — jumping back "
                    + probeStepMs + " ms to " + cursor
                    + "; empty streak=" + consecutiveEmpty + ")");
                continue;
            }
            consecutiveEmpty = 0;
            cursor = nextCursor;
        }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            System.out.println("Run aborted: " + error);
        }

        System.out.println();
        String summary =
              "========== Summary ==========\n"
            + "Pages fetched          : " + pagesFetched + "\n"
            + "Total entries seen     : " + totalChecked + "\n"
            + "Already kudoed         : " + totalAlready + "\n"
            + "Kudos given" + (dryRun ? " (dry run)" : "") + "   : " + totalKudos + "\n"
            + "Kudos failed           : " + totalFailed + "\n"
            + (error != null ? "Error                  : " + error + "\n" : "")
            + "=============================\n";
        System.out.print(summary);
        writeReport(summary, totalKudos, totalAlready, totalFailed, dryRun);
    }

    /** Save report to reports/YYYY-MM-DD_HH-mm-ss.txt and show a macOS notification. */
    private void writeReport(String summary, int given, int already, int failed, boolean dryRun) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("reports");
            java.nio.file.Files.createDirectories(dir);
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            java.nio.file.Path file = dir.resolve(ts + ".txt");
            java.nio.file.Files.writeString(file,
                    "Strava Auto-Kudo run at " + ts + "\n\n" + summary);

            // Keep a rolling "latest" pointer.
            java.nio.file.Path latest = dir.resolve("latest.txt");
            java.nio.file.Files.writeString(latest,
                    "Strava Auto-Kudo run at " + ts + "\n\n" + summary);

            // macOS notification.
            String title = "Strava Auto-Kudo" + (dryRun ? " (dry run)" : "");
            String msg = given + " kudos given, " + already + " already, " + failed + " failed";
            String script = "display notification \"" + msg.replace("\"", "\\\"")
                    + "\" with title \"" + title + "\"";
            new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start();
            System.out.println("Report saved to " + file.toAbsolutePath());
        } catch (Exception ex) {
            System.out.println("Report write failed: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // HTTP calls
    // -------------------------------------------------------------------
    /** Always uses the JSON feed API (same as the Chrome plugin). */
    private String fetchFeed(String cursor) throws Exception {
        String url = FEED_URL + "?feed_type=following"
                   + (cursor != null ? "&cursor=" + cursor : "");
        HttpRequest req = baseRequest(url)
                .header("accept",
                    "text/javascript, application/javascript, application/ecmascript, application/x-ecmascript")
                .GET().build();
        System.out.println(req.uri().toURL());
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Feed HTTP " + resp.statusCode()
                    + " — cookie/CSRF likely expired. Body: "
                    + resp.body().substring(0, Math.min(200, resp.body().length())));
        }
        return resp.body();
    }

    private boolean giveKudo(FeedEntry entry) throws Exception {
        if ("Post".equals(entry.kind)) {
            System.out.printf("  · skip Post %s (endpoint unknown)%n", entry.id);
            return false;
        }
        String url = BASE + "/feed/activity/" + entry.id + "/kudo";

        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest req = baseRequest(url)
                    .header("accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 200 || sc == 201) return true;

            if (sc == 429 || sc == 503) {
                long backoff = 30_000L * attempt;
                String ra = resp.headers().firstValue("retry-after").orElse(null);
                if (ra != null) try { backoff = Long.parseLong(ra.trim()) * 1000L; } catch (Exception ignore) {}
                System.out.printf("  ! HTTP %d (rate-limited). Backing off %ds (attempt %d/3)…%n",
                        sc, backoff / 1000, attempt);
                Thread.sleep(backoff);
                continue;
            }

            System.out.printf("  ! kudo failed for %s %s: HTTP %d %s%n",
                entry.kind, entry.id, sc,
                resp.body().substring(0, Math.min(120, resp.body().length())));
            return false;
        }
        System.out.printf("  ! kudo giving up on %s %s after 3 rate-limited retries%n",
                entry.kind, entry.id);
        return false;
    }

    /** Common headers shared by feed + kudo requests. */
    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("user-agent",       USER_AGENT)
                .header("cookie",           COOKIE)
                .header("x-csrf-token",     CSRF_TOKEN)
                .header("x-requested-with", "XMLHttpRequest")
                .header("referer",          BASE + "/dashboard")
                .header("accept-language",  "en-IN,en;q=0.9")
                .header("sec-fetch-dest",   "empty")
                .header("sec-fetch-mode",   "cors")
                .header("sec-fetch-site",   "same-origin");
    }

    // -------------------------------------------------------------------
    // Response parsing — walks the JSON entries array and pulls fields from
    // the right nested object per entry type.
    // -------------------------------------------------------------------

    /** One kudo target parsed from the feed. */
    static class FeedEntry {
        final String kind;       // "Activity" or "Post"
        final String id;         // activity id or post id
        final String name;       // athlete/originator display name
        final boolean hasKudoed;
        final boolean canKudo;
        FeedEntry(String kind, String id, String name, boolean hasKudoed, boolean canKudo) {
            this.kind = kind; this.id = id; this.name = name;
            this.hasKudoed = hasKudoed; this.canKudo = canKudo;
        }
    }

    /** Splits the top-level "entries":[ ... ] array into individual entry-object JSON substrings. */
    private static List<String> splitEntries(String body) {
        List<String> out = new ArrayList<>();
        int keyIdx = body.indexOf("\"entries\"");
        if (keyIdx < 0) return out;
        int arrStart = body.indexOf('[', keyIdx);
        if (arrStart < 0) return out;

        int depth = 0;                // brace depth inside the array
        int entryStart = -1;
        boolean inStr = false;
        boolean escape = false;

        for (int i = arrStart + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                if (escape)      escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"')  inStr = false;
                continue;
            }
            if (c == '"')       { inStr = true; continue; }
            if (c == '{')       { if (depth == 0) entryStart = i; depth++; }
            else if (c == '}')  { depth--; if (depth == 0 && entryStart >= 0) {
                                    out.add(body.substring(entryStart, i + 1));
                                    entryStart = -1;
                                } }
            else if (c == ']' && depth == 0) break;
        }
        return out;
    }

    /** Sub-object matching a top-level key inside a JSON substring. Returns null if not found. */
    private static String subObject(String s, String key) {
        int k = s.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int open = s.indexOf('{', k);
        if (open < 0) return null;
        int depth = 0;
        boolean inStr = false, escape = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (escape)      escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"')  inStr = false;
                continue;
            }
            if (c == '"')       inStr = true;
            else if (c == '{')  depth++;
            else if (c == '}')  { depth--; if (depth == 0) return s.substring(open, i + 1); }
        }
        return null;
    }

    private static String find(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Parse all kudo-able entries (Activity, Post) from the feed response. */
    private static List<FeedEntry> parseFeed(String body) {
        List<FeedEntry> out = new ArrayList<>();
        for (String entry : splitEntries(body)) {
            String entity = find(P_ENTITY, entry);
            if (entity == null) continue;

            if ("Activity".equals(entity)) {
                String activityObj = subObject(entry, "activity");
                if (activityObj == null) continue;
                String id       = find(P_ID_STR, activityObj);
                String kudosObj = subObject(activityObj, "kudosAndComments");
                String hk       = find(P_HAS_KUDOED, kudosObj != null ? kudosObj : activityObj);
                String ck       = find(P_CAN_KUDO,   kudosObj != null ? kudosObj : activityObj);
                String name     = find(P_ATHLETE_NAME, activityObj);
                if (id != null) {
                    out.add(new FeedEntry("Activity", id, name != null ? name : "?",
                            "true".equals(hk), !"false".equals(ck)));
                }
            } else if ("Post".equals(entity)) {
                String postObj = subObject(entry, "post");
                if (postObj == null) continue;
                String id   = find(P_ENTITY_ID, postObj);
                String hk   = find(P_HAS_KUDOED, postObj);
                String ck   = find(P_CAN_KUDO,   postObj);
                String name = stripHtml(find(P_ORIG_NAME, postObj));
                if (id != null) {
                    out.add(new FeedEntry("Post", id, name != null ? name : "?",
                            "true".equals(hk), !"false".equals(ck)));
                }
            }
            // Challenge / AthleteFeedEntry etc. are intentionally skipped.
        }
        return out;
    }

    /** Next cursor = smallest "rank":<millis> found in body, strictly less than current. */
    private static String extractNextCursor(String body, String currentCursor) {
        Matcher m = P_RANK.matcher(body);
        String min = null;
        while (m.find()) {
            String v = m.group(1);
            // "rank" values are floats like 1789279884000.0 — strip the trailing dot if group didn't
            if (v.length() > 13) v = v.substring(0, 13);
            if (currentCursor != null && compareNumeric(v, currentCursor) >= 0) continue;
            if (min == null || compareNumeric(v, min) < 0) min = v;
        }
        return min;
    }

    private static int compareNumeric(String a, String b) {
        if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
        return a.compareTo(b);
    }

    private static String stripHtml(String s) {
        return s == null ? null : s.replaceAll("<[^>]+>", "").trim();
    }
}
