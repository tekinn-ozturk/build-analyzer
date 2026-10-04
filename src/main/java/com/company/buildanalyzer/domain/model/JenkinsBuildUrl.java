package com.company.buildanalyzer.domain.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Jenkins build URL: {@code .../job/<name>[/job/<name>...]/<number>/[anything]}.
 * Folders become the Jenkins full name: {@code /job/Team/job/UI-Test/125/} → {@code Team/UI-Test} #125.
 * Blue Ocean, {@code lastFailedBuild} and other special URLs are not supported.
 * (The frontend's {@code utils/jenkinsUrl.ts} applies the same rules for instant form feedback.)
 *
 * @param jobPath     Jenkins full name, folders joined with "/"
 * @param buildNumber the build number
 * @param url         the build page, without trailing parts such as {@code console}, query or fragment
 */
public record JenkinsBuildUrl(String jobPath, int buildNumber, String url) {

    /** @throws IllegalArgumentException with a message for the user when the text is not such a URL */
    public static JenkinsBuildUrl parse(String text) {
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + text);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException("Not a valid http(s) URL: " + text);
        }

        String[] segments = Arrays.stream(uri.getRawPath().split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        int i = Arrays.asList(segments).indexOf("job"); // Jenkins may run under a context path, e.g. /jenkins/job/...
        List<String> names = new ArrayList<>();
        while (i >= 0 && i + 1 < segments.length && segments[i].equals("job")) {
            // "+" is a literal plus in a path, not a space.
            String name = URLDecoder.decode(segments[i + 1].replace("+", "%2B"), StandardCharsets.UTF_8);
            if (name.contains("/")) {
                throw new IllegalArgumentException("Job names containing '/' (e.g. multibranch branches) are not supported: " + text);
            }
            names.add(name);
            i += 2;
        }
        if (names.isEmpty() || i >= segments.length || !segments[i].matches("[1-9]\\d{0,8}")) {
            throw new IllegalArgumentException(
                    "Not a Jenkins build URL (expected .../job/<name>/<build number>/): " + text);
        }

        String url = uri.getScheme() + "://" + uri.getRawAuthority() + "/"
                + String.join("/", Arrays.copyOfRange(segments, 0, i + 1)) + "/";
        return new JenkinsBuildUrl(String.join("/", names), Integer.parseInt(segments[i]), url);
    }
}
