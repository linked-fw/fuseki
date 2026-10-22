package cm.linked.fuseki;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** A minimal HTTP client for the tests. */
public final class Http {
    private Http() {}

    static final HttpClient CLIENT = HttpClient.newHttpClient();

    public record Resp(int status, String body) {}

    public static Resp send(String method, String url, String contentType, String accept, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if ( contentType != null )
            b.header("Content-Type", contentType);
        if ( accept != null )
            b.header("Accept", accept);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static Resp update(String endpoint, String update) {
        return send("POST", endpoint, "application/sparql-update", null, TestLib.PREFIXES + update);
    }

    public static boolean ask(String endpoint, String query) {
        Resp r = send("GET", endpoint + "?query=" + enc(TestLib.PREFIXES + query), null, "application/sparql-results+json", null);
        if ( r.status() != 200 )
            throw new AssertionError("ASK failed " + r.status() + ": " + r.body());
        return r.body().contains("true");
    }

    /** Non-blank lines of an N-Quads body. */
    public static long lines(String body) {
        return body.lines().filter(l -> !l.isBlank()).count();
    }
}
