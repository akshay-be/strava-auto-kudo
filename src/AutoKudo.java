import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strava auto-kudo.
 * <p>
 * Flow:
 * 1. Read activities from the following feed and each joined club feed.
 * 2. For each activity id in the response, POST /feed/activity/<id>/kudo.
 * 3. Read the next cursor from the response and repeat.
 * <p>
 * Compile & run (JDK 11+, no external deps):
 * javac src/AutoKudo.java
 * java  -cp src AutoKudo --max-pages 5
 * java  -cp src AutoKudo
 * java  -cp src AutoKudo --following-only
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

    private static final Map<String, String> INCLUDED_CLUBS = new LinkedHashMap<>();
    static {
        INCLUDED_CLUBS.put("443383", "JJ HSR");
        INCLUDED_CLUBS.put("1309600", "Stride Run Club");
        INCLUDED_CLUBS.put("35294", "Walmart Running Club");
        INCLUDED_CLUBS.put("711442", "Walmart BLR Running Club");
        INCLUDED_CLUBS.put("185777", "Verifone");
        INCLUDED_CLUBS.put("184407", "Bengaluru runners");
    }
    private static final Set<String> EXCLUDED_CLUB_IDS = Set.of(
            "185777", //Verifone
            "184407", //Bengaluru runners
            "195748", //HDOR 100 days
            "443383", //JJ HSR
            "522055", //Cannondale
            "1400421", //Garmin
            "35294", //Walmart Running Club
            "711442", //Walmart BLR Running Club
            "81417", //Brooks Running
            "1174366", //ODONA BANNI
            "130202", //The Hell Race
            "1309600", //Stride Run Club
            "225339", //Jayanagar Jaguars
            "231407" //The Strava Club
    );

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36";

    // No more flat regexes for activity IDs / hasKudoed — we walk each JSON
    // entry object and pull fields from within the correct nested sub-object.
    // Only kept: patterns used inside a single entry substring.
    private static final Pattern P_ENTITY = Pattern.compile("\"entity\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Pattern P_ID_STR = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern P_HAS_KUDOED = Pattern.compile("\"has[_]?[Kk]udoed\"\\s*:\\s*(true|false)");
    private static final Pattern P_CAN_KUDO = Pattern.compile("\"can[_]?[Kk]udo\"\\s*:\\s*(true|false)");
    private static final Pattern P_SPORT_TYPE = Pattern.compile("\"sport_type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ACTIVITY_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_STAT_OBJECT = Pattern.compile(
            "\\{(?:[^{}\"]|\"(?:\\\\.|[^\"\\\\])*\")*\\}");
    private static final Pattern P_STAT_KEY = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_STAT_VALUE = Pattern.compile(
            "\"value\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern P_UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern P_DISTANCE_VALUE = Pattern.compile(
            "^([0-9]+(?:\\.[0-9]+)?)\\s*(km|mi|m)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_ATHLETE_NAME = Pattern.compile("\"athleteName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ORIG_NAME = Pattern.compile("\"originator_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ENTITY_ID = Pattern.compile("\"entity_id\"\\s*:\\s*(\\d+)");
    private static final Pattern P_RANK = Pattern.compile("\"rank\"\\s*:\\s*(\\d{10,16})");

    private final HttpClient http = buildHttpClient();

    /**
     * Uses system proxy (same one Chrome uses) unless -Dhttps.proxyHost is set.
     */
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

        String startCursor = null;
        int maxPages = 20;
        long sleepMs = 7500;
        boolean debug = false;
        boolean followingMode = true;
        boolean clubsMode = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cursor":
                    startCursor = args[++i];
                    break;
                case "--max-pages":
                    maxPages = Integer.parseInt(args[++i]);
                    break;
                case "--sleep":
                    sleepMs = Long.parseLong(args[++i]);
                    break;
                case "--debug":
                    debug = true;
                    break;
                case "--clubs":
                    followingMode = false;
                    clubsMode = true;
                    break;
                case "--following-only":
                    followingMode = true;
                    clubsMode = false;
                    break;
                default:
                    System.err.println("Unknown arg: " + args[i]);
                    System.exit(2);
            }
        }

        new AutoKudo().run(startCursor, maxPages, sleepMs, debug, followingMode, clubsMode);
    }

    // -------------------------------------------------------------------
    // Main loop: page through feed, kudo each activity.
    // -------------------------------------------------------------------
    private void run(String startCursor, int maxPages, long sleepMs, boolean debug,
                     boolean followingMode, boolean clubsMode) throws Exception {
        RunStats followingStats = new RunStats();
        RunStats clubsStats = new RunStats();
        Map<String, Integer> clubActivityCounts = new LinkedHashMap<>();
        Set<String> processedTargets = new HashSet<>();
        final int maxEmptyProbes = 5;
        final long probeStepMs = 6L * 60 * 60 * 1000;
        String error = null;

        try {
            List<ClubInfo> clubs = java.util.Collections.emptyList();
            int sourceCount = followingMode ? 1 : 0;
            if (clubsMode && !followingMode) {
                clubs = fetchClubs();
                System.out.println("Found " + clubs.size() + " clubs.");
                sourceCount += clubs.size();
            }

            for (int source = 0; source < sourceCount; source++) {
                boolean following = followingMode && source == 0;
                ClubInfo club = following ? null : clubs.get(source - (followingMode ? 1 : 0));
                String clubId = following ? null : club.id;
                String sourceName = following ? "following feed" : club.name + " (club " + clubId + ")";
                RunStats stats = following ? followingStats : clubsStats;
                Set<String> activityIds = new HashSet<>();
                String cursor = startCursor;
                int consecutiveEmpty = 0;
                System.out.println("Scanning " + sourceName);

                for (int page = 1; page <= maxPages; page++) {
                    System.out.println("Fetching page " + page + " with cursor=" + cursor);
                    String body = fetchFeed(cursor, clubId);
                    stats.pagesFetched++;

                    List<FeedEntry> entries = parseFeed(body);
                    String nextCursor = extractNextCursor(body, cursor);
                    int already = 0;
                    for (FeedEntry entry : entries) {
                        if (entry.hasKudoed) already++;
                        if ("Activity".equals(entry.kind)) activityIds.add(entry.id);
                    }
                    stats.entriesSeen += entries.size();
                    stats.alreadyKudoed += already;

                    System.out.printf(
                            "=== %s page %d: %d kudo-able entries (%d already kudoed) — next cursor: %s%n",
                            sourceName, page, entries.size(), already, nextCursor);

                    if (debug || nextCursor == null) {
                        System.out.println("--- body sample (first 800 chars) ---");
                        System.out.println(body.substring(0, Math.min(800, body.length())));
                        System.out.println("--- end sample ---");
                    }

                    for (FeedEntry entry : entries) {
                        String tag = "[" + entry.kind + " " + entry.id + " " + entry.name
                                + formatActivityDetails(entry) + "]";
                        if (!processedTargets.add(entry.kind + ":" + entry.id)) {
                            System.out.println("  · skip " + tag + " already seen in this run");
                            continue;
                        }
                        if (entry.hasKudoed) {
                            System.out.println("  · skip " + tag + " already kudoed");
                            continue;
                        }
                        if (!entry.canKudo) {
                            System.out.println("  · skip " + tag + " can't kudo");
                            continue;
                        }
                        if (isShortRunWalkOrRide(entry)) {
                            stats.skippedByRules++;
                            System.out.println("  · skip " + tag
                                    + " (run/walk/ride must be more than 3 km)");
                            continue;
                        }
                        if (isShortWeightTraining(entry)) {
                            stats.skippedByRules++;
                            System.out.println("  · skip " + tag
                                    + " (WeightTraining must be at least 20 minutes)");
                            continue;
                        }
                        boolean kudoed = false;
                        try {
                            kudoed = giveKudo1(entry);
                        } catch (RateLimitException rl) {
                            stats.kudosFailed++;
                            stats.activitiesSeen += activityIds.size();
                            if (!following) clubActivityCounts.put(club.name + " (" + clubId + ")", activityIds.size());
                            throw rl; // abort remaining processing; summary/report still written
                        }
                        if (kudoed) {
                            System.out.println("  \u2713 kudo " + tag);
                            stats.kudosGiven++;
                        } else {
                            stats.kudosFailed++;
                        }
                        Thread.sleep(sleepMs + (long) (Math.random() * 700));
                    }

                    if (nextCursor == null || nextCursor.equals(cursor)) {
                        consecutiveEmpty++;
                        if (consecutiveEmpty >= maxEmptyProbes) {
                            System.out.println("Stopping " + sourceName + " after "
                                    + consecutiveEmpty + " consecutive empty probes.");
                            break;
                        }
                        long base;
                        try {
                            base = Long.parseLong(cursor);
                        } catch (Exception e) {
                            base = System.currentTimeMillis();
                        }
                        cursor = String.valueOf(base - probeStepMs);
                        System.out.println("  (no next cursor — jumping back "
                                + probeStepMs + " ms to " + cursor
                                + "; empty streak=" + consecutiveEmpty + ")");
                        continue;
                    }
                    consecutiveEmpty = 0;
                    cursor = nextCursor;
                }
                stats.activitiesSeen += activityIds.size();
                if (!following) clubActivityCounts.put(club.name + " (" + clubId + ")", activityIds.size());

                if (following && clubsMode) {
                    clubs = fetchClubs();
                    System.out.println("Found " + clubs.size() + " clubs.");
                    sourceCount += clubs.size();
                }
            }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            System.out.println("Run aborted: " + error);
        }

        int pagesFetched = followingStats.pagesFetched + clubsStats.pagesFetched;
        int totalChecked = followingStats.entriesSeen + clubsStats.entriesSeen;
        int totalAlready = followingStats.alreadyKudoed + clubsStats.alreadyKudoed;
        int totalKudos = followingStats.kudosGiven + clubsStats.kudosGiven;
        int totalFailed = followingStats.kudosFailed + clubsStats.kudosFailed;
        System.out.println();
        String summary =
                "========== Summary ==========\n"
                        + "Mode                   : kudos enabled\n"
                        + "Following activities   : " + followingStats.activitiesSeen + "\n"
                        + "Club activities        : " + clubsStats.activitiesSeen + "\n"
                        + "Following kudos given  : " + followingStats.kudosGiven + "\n"
                        + "Club kudos given       : " + clubsStats.kudosGiven + "\n"
                        + formatClubActivityCounts(clubActivityCounts)
                        + "Pages fetched          : " + pagesFetched + "\n"
                        + "Total entries seen     : " + totalChecked + "\n"
                        + "Already kudoed         : " + totalAlready + "\n"
                        + "Skipped by rules       : "
                        + (followingStats.skippedByRules + clubsStats.skippedByRules) + "\n"
                        + "Kudos given            : " + totalKudos + "\n"
                        + "Kudos failed           : " + totalFailed + "\n"
                        + (error != null ? "Error                  : " + error + "\n" : "")
                        + "=============================\n";
        System.out.print(summary);
        writeReport(summary, totalKudos, totalAlready, totalFailed);
    }

    private static String formatClubActivityCounts(Map<String, Integer> clubActivityCounts) {
        StringBuilder counts = new StringBuilder("Activities per club:\n");
        if (clubActivityCounts.isEmpty()) return counts.append("  (no clubs scanned)\n").toString();
        for (Map.Entry<String, Integer> entry : clubActivityCounts.entrySet()) {
            counts.append("  ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append('\n');
        }
        return counts.toString();
    }

    private static class RateLimitException extends RuntimeException {
        RateLimitException(String message) {
            super(message);
        }
    }

    private static class ClubInfo {
        final String id;
        final String name;

        ClubInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class RunStats {
        int skippedByRules;
        int pagesFetched;
        int entriesSeen;
        int activitiesSeen;
        int alreadyKudoed;
        int kudosGiven;
        int kudosFailed;
    }

    /**
     * Save report to reports/YYYY-MM-DD_HH-mm-ss.txt and show a macOS notification.
     */
    private void writeReport(String summary, int given, int already, int failed) {
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
            String title = "Strava Auto-Kudo";
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

    /**
     * Uses Strava's authenticated JSON activity feeds.
     */
    private String fetchFeed(String cursor, String clubId) throws Exception {
        String url = clubId == null
                ? FEED_URL + "?feed_type=following"
                : BASE + "/clubs/" + clubId + "/feed?feed_type=club&club_id=" + clubId;
        if (cursor != null) {
            url += "&cursor=" + cursor;
            if (clubId != null) url += "&before=" + cursor;
        }
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

    /** Clubs come from the configured include list; no network call is made. */
    private List<ClubInfo> fetchClubs() {
        List<ClubInfo> clubs = new ArrayList<>();
        for (Map.Entry<String, String> e : INCLUDED_CLUBS.entrySet()) {
            clubs.add(new ClubInfo(e.getKey(), e.getValue()));
        }
        return clubs;
    }

    private boolean giveKudo(FeedEntry entry) throws Exception {
        return true;
    }
    private boolean giveKudo1(FeedEntry entry) throws Exception {
        if ("Post".equals(entry.kind)) {
            System.out.printf("  · skip Post %s (endpoint unknown)%n", entry.id);
            return false;
        }
        String url = BASE + "/feed/activity/" + entry.id + "/kudo";

        for (int attempt = 1; attempt <= 1; attempt++) {
            HttpRequest req = baseRequest(url)
                    .header("accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 200 || sc == 201) return true;

            if (sc == 429) {
                throw new RateLimitException("HTTP 429 (rate-limited) on kudo for "
                        + entry.kind + " " + entry.id + " — stopping run");
            }

            if (sc == 503) {
                long backoff = 90_000L * attempt;
                String ra = resp.headers().firstValue("retry-after").orElse(null);
                if (ra != null) try {
                    backoff = Long.parseLong(ra.trim()) * 1000L;
                } catch (Exception ignore) {
                }
                System.out.printf("  ! HTTP %d (rate-limited). Backing off %ds (attempt %d/3)…%n",
                        sc, backoff / 1000, attempt);
//                Thread.sleep(backoff);

                continue;
            }

            System.out.printf("  ! kudo failed for %s %s: HTTP %d %s%n",
                    entry.kind, entry.id, sc,
                    resp.body().substring(0, Math.min(120, resp.body().length())));
            return false;
        }
        System.out.printf("  ! kudo giving up on %s %s after 3 rate-limited retries%n",
                entry.kind, entry.id);
//        System.exit(1);
        return false;
    }

    /**
     * Common headers shared by feed + kudo requests.
     */
    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("user-agent", USER_AGENT)
                .header("cookie", COOKIE)
                .header("x-csrf-token", CSRF_TOKEN)
                .header("x-requested-with", "XMLHttpRequest")
                .header("referer", BASE + "/dashboard")
                .header("accept-language", "en-IN,en;q=0.9")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin");
    }

    // -------------------------------------------------------------------
    // Response parsing — walks the JSON entries array and pulls fields from
    // the right nested object per entry type.
    // -------------------------------------------------------------------

    /**
     * One kudo target parsed from the feed.
     */
    static class FeedEntry {
        final String kind;       // "Activity" or "Post"
        final String id;         // activity id or post id
        final String name;       // athlete/originator display name
        final boolean hasKudoed;
        final boolean canKudo;
        final String sportType;
        final double distanceMeters;
        final double durationSeconds;

        FeedEntry(String kind, String id, String name, boolean hasKudoed, boolean canKudo,
                  String sportType, double distanceMeters, double durationSeconds) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.hasKudoed = hasKudoed;
            this.canKudo = canKudo;
            this.sportType = sportType;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }
    }

    /**
     * Splits the top-level "entries":[ ... ] array into individual entry-object JSON substrings.
     */
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
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) entryStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && entryStart >= 0) {
                    out.add(body.substring(entryStart, i + 1));
                    entryStart = -1;
                }
            } else if (c == ']' && depth == 0) break;
        }
        return out;
    }

    /**
     * Sub-object matching a top-level key inside a JSON substring. Returns null if not found.
     */
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
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(open, i + 1);
            }
        }
        return null;
    }

    private static String find(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Parse all kudo-able entries (Activity, Post) from the feed response.
     */
    private static List<FeedEntry> parseFeed(String body) {
        List<FeedEntry> out = new ArrayList<>();
        for (String entry : splitEntries(body)) {
            String entity = find(P_ENTITY, entry);
            if (entity == null) continue;

            if ("Activity".equals(entity)) {
                String activityObj = subObject(entry, "activity");
                if (activityObj == null) continue;
                String id = find(P_ID_STR, activityObj);
                String kudosObj = subObject(activityObj, "kudosAndComments");
                String hk = find(P_HAS_KUDOED, kudosObj != null ? kudosObj : activityObj);
                String ck = find(P_CAN_KUDO, kudosObj != null ? kudosObj : activityObj);
                String name = find(P_ATHLETE_NAME, activityObj);
                String sportType = find(P_SPORT_TYPE, activityObj);
                if (sportType == null) sportType = find(P_ACTIVITY_TYPE, activityObj);
                double distanceMeters = extractDistanceMeters(activityObj);
                if (id != null) {
                    out.add(new FeedEntry("Activity", id, name != null ? name : "?",
                            "true".equals(hk), !"false".equals(ck), sportType, distanceMeters,
                            extractDurationSeconds(activityObj)));
                }
            } else if ("Post".equals(entity)) {
                String postObj = subObject(entry, "post");
                if (postObj == null) continue;
                String id = find(P_ENTITY_ID, postObj);
                String hk = find(P_HAS_KUDOED, postObj);
                String ck = find(P_CAN_KUDO, postObj);
                String name = stripHtml(find(P_ORIG_NAME, postObj));
                if (id != null) {
                    out.add(new FeedEntry("Post", id, name != null ? name : "?",
                            "true".equals(hk), !"false".equals(ck), null, Double.NaN, Double.NaN));
                }
            }
            // Challenge / AthleteFeedEntry etc. are intentionally skipped.
        }
        return out;
    }

    /**
     * Match a subtitle to its stat value and decode the feed's formatted HTML.
     */
    private static String extractStatText(String activityObj, String label) {
        Map<String, String> stats = new LinkedHashMap<>();
        Matcher objects = P_STAT_OBJECT.matcher(activityObj);
        while (objects.find()) {
            String object = objects.group();
            String key = find(P_STAT_KEY, object);
            String value = find(P_STAT_VALUE, object);
            if (key != null && value != null) stats.put(key, value);
        }
        for (Map.Entry<String, String> stat : stats.entrySet()) {
            if (!stat.getKey().endsWith("_subtitle") || !label.equals(stat.getValue())) continue;
            String valueKey = stat.getKey().substring(0, stat.getKey().length() - "_subtitle".length());
            String value = stats.get(valueKey);
            if (value == null) return null;
            Matcher escapes = P_UNICODE_ESCAPE.matcher(value);
            StringBuffer decoded = new StringBuffer();
            while (escapes.find()) {
                escapes.appendReplacement(decoded, Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(escapes.group(1), 16))));
            }
            escapes.appendTail(decoded);
            return stripHtml(decoded.toString())
                    .replace("&nbsp;", " ").replace("\u00a0", " ")
                    .replace(",", "").trim();
        }
        return null;
    }

    private static double extractDistanceMeters(String activityObj) {
        String text = extractStatText(activityObj, "Distance");
        if (text == null) return Double.NaN;
        Matcher distance = P_DISTANCE_VALUE.matcher(text);
        if (!distance.matches()) return Double.NaN;
        double amount = Double.parseDouble(distance.group(1));
        if (!Double.isFinite(amount)) return Double.NaN;
        switch (distance.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "km": return amount * 1000;
            case "mi": return amount * 1609.344;
            default: return amount;
        }
    }

    private static double extractDurationSeconds(String activityObj) {
        String text = null;
        for (String label : List.of("Time", "Elapsed Time", "Duration", "Moving Time")) {
            text = extractStatText(activityObj, label);
            if (text != null) break;
        }
        if (text == null) return Double.NaN;
        if (text.matches("\\d+:\\d{2}(?::\\d{2})?")) {
            String[] parts = text.split(":");
            double seconds = 0;
            for (int i = 0; i < parts.length; i++) {
                double part = Double.parseDouble(parts[i]);
                if (i > 0 && part >= 60) return Double.NaN;
                seconds = seconds * 60 + part;
            }
            return Double.isFinite(seconds) ? seconds : Double.NaN;
        }
        Matcher units = Pattern.compile(
                "\\s*(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\s*",
                Pattern.CASE_INSENSITIVE).matcher(text);
        double seconds = 0;
        int end = 0;
        while (units.find()) {
            if (units.start() != end) return Double.NaN;
            double amount = Double.parseDouble(units.group(1));
            char unit = Character.toLowerCase(units.group(2).charAt(0));
            seconds += amount * (unit == 'h' ? 3600 : unit == 'm' ? 60 : 1);
            end = units.end();
        }
        return end > 0 && end == text.length() && Double.isFinite(seconds)
                ? seconds : Double.NaN;
    }

    private static boolean isShortWeightTraining(FeedEntry entry) {
        return "Activity".equals(entry.kind) && isWeightTraining(entry.sportType)
                && (Double.isNaN(entry.durationSeconds) || entry.durationSeconds < 1200);
    }

    private static boolean isWeightTraining(String sportType) {
        return sportType != null
                && "WeightTraining".equalsIgnoreCase(sportType.replaceAll("[^A-Za-z]", ""));
    }

    private static boolean isShortRunWalkOrRide(FeedEntry entry) {
        if (!"Activity".equals(entry.kind) || entry.sportType == null) return false;
        String normalizedType = entry.sportType.replaceAll("[^A-Za-z]", "")
                .toLowerCase(java.util.Locale.ROOT);
        boolean filteredType = normalizedType.endsWith("run")
                || "walk".equals(normalizedType)
                || normalizedType.endsWith("ride");
        return filteredType && (Double.isNaN(entry.distanceMeters) || entry.distanceMeters <= 3000);
    }

    private static String formatActivityDetails(FeedEntry entry) {
        if (!"Activity".equals(entry.kind)) return "";
        String type = entry.sportType != null ? entry.sportType : "type unavailable";
        if (isWeightTraining(entry.sportType)) {
            String duration = Double.isNaN(entry.durationSeconds) ? "duration unavailable"
                    : String.format(java.util.Locale.ROOT, "%.2f min", entry.durationSeconds / 60);
            return " | " + type + " | " + duration;
        }
        String distance = Double.isNaN(entry.distanceMeters)
                ? "distance unavailable"
                : String.format(java.util.Locale.ROOT, "%.2f km", entry.distanceMeters / 1000);
        return " | " + type + " | " + distance;
    }

    /**
     * Next cursor = smallest "rank":<millis> found in body, strictly less than current.
     */
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
