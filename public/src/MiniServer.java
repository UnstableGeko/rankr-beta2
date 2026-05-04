import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;

public class MiniServer {
    public static void main(String[] args) throws Exception {
        int port = 80;
        Path root = Paths.get("..").toAbsolutePath().normalize();

        Properties config = new Properties();
        try (FileInputStream fis = new FileInputStream("../../config.properties")) {
            config.load(fis);
        } catch (IOException e) {
            System.err.println("ERROR: config.properties file not found!");
            System.err.println("Please create config.properties with your IGDB credentials");
            System.err.println("See README.md for setup instructions");
            System.exit(1);
        }
        
        final String configClientId = config.getProperty("igdb.client.id");
        final String configAccessToken = config.getProperty("igdb.access.token");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/games/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String slug = path.substring("/games/".length());
            
            if (slug.isEmpty()) {
                exchange.sendResponseHeaders(302, 0);
                exchange.getResponseHeaders().set("Location", "/");
                exchange.close();
                return;
            }

            Path file = root.resolve("game.html").normalize();

            if (!Files.exists(file)) {
                String msg = "404 Not Found";
                exchange.sendResponseHeaders(404, msg.length());
                exchange.getResponseBody().write(msg.getBytes());
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            byte[] data = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            
            if (path.startsWith("/api/")) {
                return;
            }

            if (path.equals("/")) {
                exchange.getResponseHeaders().set("Location", "/home");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            if (path.equals("/home")) {
                path = "/index.html";
            }

            if (path.equals("/about")) {
                path = "/about.html";
            }

            Path file = root.resolve(path.substring(1)).normalize();

            if (!file.startsWith(root) || Files.isDirectory(file) || !Files.exists(file)) {
                String msg = "404 Not Found";
                exchange.sendResponseHeaders(404, msg.length());
                exchange.getResponseBody().write(msg.getBytes());
                exchange.close();
                return;
            }

            String contentType = guessContentType(file);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] data = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });

        server.createContext("/api/games", exchange -> {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.sendResponseHeaders(405, 0);
            exchange.close();
            return;
        }

        try {
            String clientId = configClientId;
            String accessToken = configAccessToken;
            
            if (clientId == null || accessToken == null) {
                String error = "{\"error\": \"Missing IGDB credentials in config.properties\"}";
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
                return;
            }
            
            // Read request body
            InputStream requestBody = exchange.getRequestBody();
            String bodyStr = new String(requestBody.readAllBytes());
            
            // Parse sortBy parameter
            String sortBy = "rating"; // default
            if (bodyStr.contains("\"sortBy\"")) {
                int sortStart = bodyStr.indexOf("\"sortBy\":") + 9;
                String afterColon = bodyStr.substring(sortStart).trim();
                
                if (afterColon.startsWith("\"")) {
                    int openQuote = bodyStr.indexOf("\"", sortStart);
                    int closeQuote = bodyStr.indexOf("\"", openQuote + 1);
                    sortBy = bodyStr.substring(openQuote + 1, closeQuote);
                }
            }

            // Parse page and limit parameters
            int page = 1;
            int limit = 40;

            if (bodyStr.contains("\"page\"")) {
                int pageStart = bodyStr.indexOf("\"page\":") + 7;
                String afterColon = bodyStr.substring(pageStart).trim();
                int commaOrBrace = Math.min(
                    afterColon.indexOf(",") == -1 ? Integer.MAX_VALUE : afterColon.indexOf(","),
                    afterColon.indexOf("}") == -1 ? Integer.MAX_VALUE : afterColon.indexOf("}")
                );
                String pageStr = afterColon.substring(0, commaOrBrace).trim();
                page = Integer.parseInt(pageStr);
            }

            if (bodyStr.contains("\"limit\"")) {
                int limitStart = bodyStr.indexOf("\"limit\":") + 8;
                String afterColon = bodyStr.substring(limitStart).trim();
                int commaOrBrace = Math.min(
                    afterColon.indexOf(",") == -1 ? Integer.MAX_VALUE : afterColon.indexOf(","),
                    afterColon.indexOf("}") == -1 ? Integer.MAX_VALUE : afterColon.indexOf("}")
                );
                String limitStr = afterColon.substring(0, commaOrBrace).trim();
                limit = Integer.parseInt(limitStr);
            }

            int offset = (page - 1) * limit;
            
            // Build sort clause
            String sortClause;
            switch (sortBy) {
                case "rating_count":
                    sortClause = "sort rating_count desc";
                    break;
                case "release_date":
                    sortClause = "sort first_release_date desc";
                    break;
                default: // "rating"
                    sortClause = "sort rating desc";
                    break;
            }

            String fields = "fields name, slug, summary, rating, rating_count, cover.image_id, genres.name, genres.slug, themes.name, themes.slug, platforms.name, platforms.slug, involved_companies.publisher, involved_companies.developer, involved_companies.company.name";
            if (sortBy.equals("release_date")) {
                fields += ", first_release_date";
            }
            
            String igdbQuery = fields + "; where cover != null & rating != null & rating_count > 500; " + sortClause + "; limit " + limit + "; offset " + offset + ";";
            
            URI uri = new URI("https://api.igdb.com/v4/games");
            URL url = uri.toURL();

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Client-ID", clientId);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            
            conn.getOutputStream().write(igdbQuery.getBytes());
            
            InputStream responseStream = conn.getInputStream();
byte[] gamesData = responseStream.readAllBytes();

        // Wrap response with totalPages
        String gamesJson = new String(gamesData);
        int totalPages = 10; // Hardcoded for now
        String wrappedResponse = "{\"games\":" + gamesJson + ",\"totalPages\":" + totalPages + "}";
        byte[] responseData = wrappedResponse.getBytes();

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, responseData.length);
        exchange.getResponseBody().write(responseData);
        exchange.close();
            
        } catch (Exception e) {
            e.printStackTrace();
            String error = "{\"error\": \"" + e.getMessage() + "\"}";
            exchange.sendResponseHeaders(500, error.length());
            exchange.getResponseBody().write(error.getBytes());
            exchange.close();
        }
    });

        server.createContext("/api/game-single", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            try {
                String query = exchange.getRequestURI().getQuery();
                String slug = "";
                
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("slug=")) {
                            slug = URLDecoder.decode(param.substring(5), "UTF-8");
                        }
                    }
                }

                if (slug.isEmpty()) {
                    String error = "{\"error\": \"Missing slug parameter\"}";
                    exchange.sendResponseHeaders(400, error.length());
                    exchange.getResponseBody().write(error.getBytes());
                    exchange.close();
                    return;
                }

                URI uri = new URI("https://api.igdb.com/v4/games");
                URL url = uri.toURL();

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Client-ID", configClientId);
                conn.setRequestProperty("Authorization", "Bearer " + configAccessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                String body = "fields name, slug, summary, rating, rating_count, total_rating, total_rating_count, cover.image_id, genres.name, genres.slug, themes.name, themes.slug, platforms.name, platforms.slug, involved_companies.publisher, involved_companies.developer, involved_companies.company.name; where slug = \"" + slug + "\"; limit 1;";
                conn.getOutputStream().write(body.getBytes());

                InputStream responseStream = conn.getInputStream();
                byte[] responseData = responseStream.readAllBytes();

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, responseData.length);
                exchange.getResponseBody().write(responseData);
                exchange.close();

            } catch (Exception e) {
                e.printStackTrace();
                String error = "{\"error\": \"" + e.getMessage() + "\"}";
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
            }
        });

        server.createContext("/api/browse", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            try {
                InputStream requestBody = exchange.getRequestBody();
                String bodyStr = new String(requestBody.readAllBytes());
                
                System.out.println("=== BROWSE REQUEST ===");
                System.out.println("Request Body: " + bodyStr);
                
                String genreId = parseJsonString(bodyStr, "genre");
                String platformSlug = parseJsonString(bodyStr, "platform");
                String yearStr = parseJsonString(bodyStr, "year");

                URI uri = new URI("https://api.igdb.com/v4/games");
                URL url = uri.toURL();

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Client-ID", configClientId);
                conn.setRequestProperty("Authorization", "Bearer " + configAccessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                // Parse sortBy parameter
                String sortBy = "rating"; // default
                if (bodyStr.contains("\"sortBy\"")) {
                    int sortStart = bodyStr.indexOf("\"sortBy\":") + 9;
                    String afterColon = bodyStr.substring(sortStart).trim();

                    if (afterColon.startsWith("\"")) {
                        int openQuote = bodyStr.indexOf("\"", sortStart);
                        int closeQuote = bodyStr.indexOf("\"", openQuote + 1);
                        sortBy = bodyStr.substring(openQuote + 1, closeQuote);
                    }
                }

                // Newest sort: only require a cover and at least one rating (filters out unreleased/2040 placeholders)
                String whereClause = sortBy.equals("release_date")
                    ? "where cover != null & rating != null"
                    : "where cover != null & rating != null & rating_count > 100 & version_parent = null & parent_game = null";

                // Parse page and limit parameters
                int page = 1;
                int limit = 42;

                if (bodyStr.contains("\"page\"")) {
                    int pageStart = bodyStr.indexOf("\"page\":") + 7;
                    String afterColon = bodyStr.substring(pageStart).trim();
                    int commaOrBrace = Math.min(
                        afterColon.indexOf(",") == -1 ? Integer.MAX_VALUE : afterColon.indexOf(","),
                        afterColon.indexOf("}") == -1 ? Integer.MAX_VALUE : afterColon.indexOf("}")
                    );
                    String pageStr = afterColon.substring(0, commaOrBrace).trim();
                    page = Integer.parseInt(pageStr);
                }

                if (bodyStr.contains("\"limit\"")) {
                    int limitStart = bodyStr.indexOf("\"limit\":") + 8;
                    String afterColon = bodyStr.substring(limitStart).trim();
                    int commaOrBrace = Math.min(
                        afterColon.indexOf(",") == -1 ? Integer.MAX_VALUE : afterColon.indexOf(","),
                        afterColon.indexOf("}") == -1 ? Integer.MAX_VALUE : afterColon.indexOf("}")
                    );
                    String limitStr = afterColon.substring(0, commaOrBrace).trim();
                    limit = Integer.parseInt(limitStr);
                }

                int offset = (page - 1) * limit;
                if (genreId != null) {
                    whereClause += " & genres = (" + genreId + ")";
                }
                boolean nicheplatform = false;
                if (platformSlug != null) {
                    whereClause += " & platforms.slug = \"" + platformSlug + "\"";
                    java.util.Set<String> mainPlatforms = new java.util.HashSet<>(java.util.Arrays.asList(
                        "win", "mac", "linux",
                        "ps5", "ps4", "ps3",
                        "series-x-s", "xboxone", "xbox360",
                        "switch", "3ds", "wiiu"
                    ));
                    if (!mainPlatforms.contains(platformSlug)) {
                        // For niche platforms, drop all quality filters — just match the platform
                        whereClause = "where platforms.slug = \"" + platformSlug + "\"";
                        nicheplatform = true;
                    }
                }
                if (yearStr != null) {
                    int yr = Integer.parseInt(yearStr.replaceAll("[^0-9]", ""));
                    long yearStart = java.time.LocalDate.of(yr, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
                    long yearEnd   = java.time.LocalDate.of(yr + 1, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
                    whereClause += " & first_release_date >= " + yearStart + " & first_release_date < " + yearEnd;
                }

                // minRating filter (stacks with other filters)
                if (bodyStr.contains("\"minRating\"")) {
                    int mrStart = bodyStr.indexOf("\"minRating\":") + 12;
                    String afterColon = bodyStr.substring(mrStart).trim();
                    int end = afterColon.indexOf(",") == -1 ? afterColon.indexOf("}") : Math.min(afterColon.indexOf(","), afterColon.indexOf("}"));
                    try {
                        double minRating = Double.parseDouble(afterColon.substring(0, end).trim());
                        if (minRating > 0) {
                            int rawMin = (int)(minRating * 20);
                            whereClause = whereClause.replace("rating != null", "rating >= " + rawMin);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            // Build sort clause
            String sortClause;
            switch (sortBy) {
                case "rating_count":
                    sortClause = "sort rating_count desc";
                    break;
                case "release_date":
                    sortClause = "sort first_release_date desc";
                    break;
                default: // "rating"
                    sortClause = "sort rating desc";
                    break;
            }

            // Query IGDB count endpoint for exact total
            int totalCount = 0;
            try {
                URI countUri = new URI("https://api.igdb.com/v4/games/count");
                HttpURLConnection countConn = (HttpURLConnection) countUri.toURL().openConnection();
                countConn.setRequestMethod("POST");
                countConn.setRequestProperty("Client-ID", configClientId);
                countConn.setRequestProperty("Authorization", "Bearer " + configAccessToken);
                countConn.setRequestProperty("Accept", "application/json");
                countConn.setDoOutput(true);
                countConn.getOutputStream().write((whereClause + ";").getBytes());
                String countJson = new String(countConn.getInputStream().readAllBytes());
                int countIdx = countJson.indexOf("\"count\":") + 8;
                if (countIdx > 7) {
                    String countStr = countJson.substring(countIdx).replaceAll("[^0-9]", "");
                    if (!countStr.isEmpty()) totalCount = Integer.parseInt(countStr);
                }
            } catch (Exception countEx) {
                System.out.println("Count query failed: " + countEx.getMessage());
            }

            String fields = "fields name, slug, cover.image_id, rating, rating_count, genres.name, first_release_date";
            String igdbQuery;
            if (nicheplatform) {
                // Fetch all games for this platform (max 500) and sort by Bayesian score server-side
                igdbQuery = fields + "; " + whereClause + "; sort rating_count desc; limit 500; offset 0;";
            } else {
                igdbQuery = fields + "; " + whereClause + "; " + sortClause + "; limit " + limit + "; offset " + offset + ";";
            }

            System.out.println("=== FINAL IGDB QUERY ===");
            System.out.println(igdbQuery);
            System.out.println("========================");

            conn.getOutputStream().write(igdbQuery.getBytes());

            InputStream responseStream = conn.getInputStream();
            byte[] gamesData = responseStream.readAllBytes();
            String gamesJson = new String(gamesData);

            String finalGamesJson = gamesJson;
            int totalPages;

            if (nicheplatform) {
                java.util.List<String> gameObjs = splitJsonArray(gamesJson);
                if (gameObjs.size() >= 10) {
                    // Enough games to warrant weighted sort
                    final double C = 50.0, M = 65.0;
                    gameObjs.sort((a, b) -> {
                        double ra = extractNumber(a, "rating"), rca = extractNumber(a, "rating_count");
                        double rb = extractNumber(b, "rating"), rcb = extractNumber(b, "rating_count");
                        double scoreA = (ra * rca + C * M) / (rca + C);
                        double scoreB = (rb * rcb + C * M) / (rcb + C);
                        return Double.compare(scoreB, scoreA);
                    });
                } else {
                    // Too few games — just sort by highest rating
                    gameObjs.sort((a, b) -> Double.compare(extractNumber(b, "rating"), extractNumber(a, "rating")));
                }
                int fromIdx = (page - 1) * limit;
                int toIdx = Math.min(fromIdx + limit, gameObjs.size());
                java.util.List<String> pageSlice = fromIdx < gameObjs.size() ? gameObjs.subList(fromIdx, toIdx) : java.util.Collections.emptyList();
                finalGamesJson = "[" + String.join(",", pageSlice) + "]";
                totalCount = gameObjs.size();
                totalPages = (int) Math.ceil((double) totalCount / limit);
            } else {
                int resultCount = 0;
                for (int ci = 0; ci < gamesJson.length(); ci++) { if (gamesJson.charAt(ci) == '{') resultCount++; }
                if (totalCount > 0) {
                    totalPages = (int) Math.ceil((double) totalCount / limit);
                    totalPages = Math.min(totalPages, 250);
                } else {
                    totalPages = resultCount < limit ? page : page + 2;
                }
            }

            String wrappedResponse = "{\"games\":" + finalGamesJson + ",\"totalPages\":" + totalPages + ",\"resultCount\":" + totalCount + "}";
            byte[] responseData = wrappedResponse.getBytes();

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, responseData.length);
            exchange.getResponseBody().write(responseData);
            exchange.close();

            } catch (Exception e) {
                e.printStackTrace();
                String error = "{\"error\": \"" + e.getMessage() + "\"}";
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
            }
        });

        server.createContext("/api/platforms", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            try {
                URI uri = new URI("https://api.igdb.com/v4/platforms");
                URL url = uri.toURL();

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Client-ID", configClientId);
                conn.setRequestProperty("Authorization", "Bearer " + configAccessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                String body = "fields name, slug; sort name asc; limit 500;";
                conn.getOutputStream().write(body.getBytes());

                InputStream responseStream = conn.getInputStream();
                byte[] responseData = responseStream.readAllBytes();

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, responseData.length);
                exchange.getResponseBody().write(responseData);
                exchange.close();

            } catch (Exception e) {
                e.printStackTrace();
                String error = "{\"error\": \"" + e.getMessage() + "\"}";
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
            }
        });
        server.createContext("/api/search", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            try {
                String query = exchange.getRequestURI().getQuery();
                String searchQuery = "";
                
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("q=")) {
                            searchQuery = URLDecoder.decode(param.substring(2), "UTF-8");
                        }
                    }
                }

                if (searchQuery.isEmpty()) {
                    String error = "{\"error\": \"Missing search query\"}";
                    exchange.sendResponseHeaders(400, error.length());
                    exchange.getResponseBody().write(error.getBytes());
                    exchange.close();
                    return;
                }

                URI uri = new URI("https://api.igdb.com/v4/games");
                URL url = uri.toURL();

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Client-ID", configClientId);
                conn.setRequestProperty("Authorization", "Bearer " + configAccessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                String body = "search \"" + searchQuery + "\"; " +
                            "fields name, slug, cover.image_id, summary, rating, rating_count, genres.name, first_release_date; " +
                            "where cover != null; " +
                            "limit 20;";
                
                conn.getOutputStream().write(body.getBytes());

                InputStream responseStream = conn.getInputStream();
                byte[] responseData = responseStream.readAllBytes();

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, responseData.length);
                exchange.getResponseBody().write(responseData);
                exchange.close();

            } catch (Exception e) {
                e.printStackTrace();
                String error = "{\"error\": \"" + e.getMessage() + "\"}";
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
            }
        });
        server.createContext("/api/trending", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }
            try {
                // Games released in the past two years, sorted by most ratings (community activity)
                long twoYearsAgo = System.currentTimeMillis() / 1000 - (2L * 365 * 24 * 3600);
                String igdbQuery = "fields name, slug, cover.image_id, rating, rating_count, genres.name, first_release_date; " +
                    "where cover != null & rating != null & rating_count > 150 & first_release_date > " + twoYearsAgo + "; " +
                    "sort rating desc; limit 10;";

                URI uri = new URI("https://api.igdb.com/v4/games");
                URL url = uri.toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Client-ID", configClientId);
                conn.setRequestProperty("Authorization", "Bearer " + configAccessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write(igdbQuery.getBytes());

                byte[] responseData = conn.getInputStream().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, responseData.length);
                exchange.getResponseBody().write(responseData);
                exchange.close();
            } catch (Exception e) {
                e.printStackTrace();
                String error = "{\"error\": \"" + e.getMessage() + "\"}";
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Server running at http://localhost:" + port);
    }

    private static String guessContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }

    // Splits a JSON array string into individual object strings
    static java.util.List<String> splitJsonArray(String json) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start != -1) { result.add(json.substring(start, i + 1)); start = -1; } }
        }
        return result;
    }

    // Extracts a numeric field value from a JSON object string; returns 0 if missing
    static double extractNumber(String obj, String field) {
        String key = "\"" + field + "\":";
        int idx = obj.indexOf(key);
        if (idx == -1) return 0;
        String after = obj.substring(idx + key.length()).trim();
        int end = 0;
        while (end < after.length() && (Character.isDigit(after.charAt(end)) || after.charAt(end) == '.' || after.charAt(end) == '-')) end++;
        try { return Double.parseDouble(after.substring(0, end)); } catch (NumberFormatException e) { return 0; }
    }

    // Parses a JSON string or number field; returns null if missing or "null"
    static String parseJsonString(String body, String key) {
        String search = "\"" + key + "\":";
        int idx = body.indexOf(search);
        if (idx == -1) return null;
        String after = body.substring(idx + search.length()).trim();
        if (after.startsWith("null")) return null;
        if (after.startsWith("\"")) {
            int open = after.indexOf("\"");
            int close = after.indexOf("\"", open + 1);
            return after.substring(open + 1, close);
        }
        // numeric value
        int end = after.indexOf(",");
        if (end == -1) end = after.indexOf("}");
        String val = after.substring(0, end).trim();
        return val.equals("null") ? null : val;
    }
}