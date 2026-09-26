package com.ziptool.uploader;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads every file out of a .zip and PUTs each one to
 * https://api.github.com/repos/{owner}/{repo}/contents/{path}
 * which creates or updates that file in one commit per file.
 *
 * Runs entirely on whatever thread calls run() - callers must call it
 * from a background thread, never the UI thread.
 */
public class GitHubUploader {

    public interface Listener {
        void onLog(String line);
        void onProgress(int done, int total);
        void onFinished(boolean success, String repoUrl, int uploaded, int failed);
    }

    private static class FileEntry {
        String path;
        byte[] data;
    }

    private final String token;
    private final String owner;
    private final String repo;
    private final String commitMessage;
    private final boolean createIfMissing;
    private final boolean makePrivate;
    private final boolean stripTopFolder;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int MAX_FILE_BYTES = 1024 * 1024; // GitHub Contents API limit

    public GitHubUploader(String token, String owner, String repo, String commitMessage,
                           boolean createIfMissing, boolean makePrivate, boolean stripTopFolder,
                           Listener listener) {
        this.token = token.trim();
        this.owner = owner.trim();
        this.repo = repo.trim();
        this.commitMessage = commitMessage.trim().isEmpty() ? "Upload via Zip to GitHub app" : commitMessage.trim();
        this.createIfMissing = createIfMissing;
        this.makePrivate = makePrivate;
        this.stripTopFolder = stripTopFolder;
        this.listener = listener;
    }

    public void run(InputStream zipStream) {
        try {
            List<FileEntry> entries = readZip(zipStream);
            if (entries.isEmpty()) {
                fail("The zip file has no files inside it.");
                return;
            }
            log("Found " + entries.size() + " file(s) in the zip.");

            String prefix = stripTopFolder ? findCommonTopFolder(entries) : null;
            if (prefix != null) {
                log("Removing outer folder: " + prefix);
            }

            if (!ensureRepoExists()) {
                return; // ensureRepoExists already reported the failure
            }

            int uploaded = 0, failed = 0, total = entries.size(), done = 0;
            for (FileEntry entry : entries) {
                String path = entry.path;
                if (prefix != null && path.startsWith(prefix)) {
                    path = path.substring(prefix.length());
                }
                if (path.isEmpty()) {
                    done++;
                    progress(done, total);
                    continue;
                }
                if (entry.data.length > MAX_FILE_BYTES) {
                    log("Skipped (too large for the API, >1MB): " + path);
                    failed++;
                    done++;
                    progress(done, total);
                    continue;
                }
                try {
                    uploadFile(path, entry.data);
                    log("Uploaded: " + path);
                    uploaded++;
                } catch (Exception e) {
                    log("Failed: " + path + " (" + e.getMessage() + ")");
                    failed++;
                }
                done++;
                progress(done, total);
            }

            String url = "https://github.com/" + owner + "/" + repo;
            finished(failed == 0, url, uploaded, failed);
        } catch (Exception e) {
            fail("Error: " + e.getMessage());
        }
    }

    private List<FileEntry> readZip(InputStream in) throws IOException {
        List<FileEntry> result = new ArrayList<>();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int n;
            while ((n = zis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            FileEntry fe = new FileEntry();
            fe.path = entry.getName();
            fe.data = baos.toByteArray();
            result.add(fe);
            zis.closeEntry();
        }
        zis.close();
        return result;
    }

    /** If every entry shares the same first path segment, returns that segment + "/". Otherwise null. */
    private String findCommonTopFolder(List<FileEntry> entries) {
        String common = null;
        for (FileEntry e : entries) {
            int slash = e.path.indexOf('/');
            if (slash <= 0) return null; // a file sits at the zip root - nothing to strip
            String top = e.path.substring(0, slash + 1);
            if (common == null) {
                common = top;
            } else if (!common.equals(top)) {
                return null;
            }
        }
        return common;
    }

    private boolean ensureRepoExists() throws IOException {
        HttpResult check = call("GET", "https://api.github.com/repos/" + owner + "/" + repo, null);
        if (check.code == 200) {
            log("Repository found, continuing.");
            return true;
        }
        if (check.code == 404) {
            if (!createIfMissing) {
                fail("Repository " + owner + "/" + repo + " does not exist, and 'Create repository' is unchecked.");
                return false;
            }
            log("Repository not found, creating it...");
            JSONObject body = new JSONObject();
            try {
                body.put("name", repo);
                body.put("private", makePrivate);
                body.put("auto_init", false);
            } catch (Exception ignored) {}
            HttpResult create = call("POST", "https://api.github.com/user/repos", body.toString());
            if (create.code == 201) {
                log("Repository created.");
                return true;
            } else {
                fail("Could not create repository (HTTP " + create.code + "): " + create.body);
                return false;
            }
        }
        if (check.code == 401) {
            fail("GitHub rejected the token (401 Unauthorized). Check the token is correct and not expired.");
            return false;
        }
        fail("Could not reach repository (HTTP " + check.code + "): " + check.body);
        return false;
    }

    private void uploadFile(String path, byte[] data) throws IOException {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + encodePath(path);

        // Look up the existing file's sha (needed to update rather than create).
        String sha = null;
        HttpResult existing = call("GET", url, null);
        if (existing.code == 200) {
            try {
                sha = new JSONObject(existing.body).getString("sha");
            } catch (Exception ignored) {}
        } else if (existing.code != 404) {
            throw new IOException("HTTP " + existing.code + " checking existing file");
        }

        JSONObject body = new JSONObject();
        try {
            body.put("message", commitMessage);
            body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
            if (sha != null) body.put("sha", sha);
        } catch (Exception e) {
            throw new IOException("Could not build request: " + e.getMessage());
        }

        HttpResult put = call("PUT", url, body.toString());
        if (put.code != 200 && put.code != 201) {
            throw new IOException("HTTP " + put.code + ": " + put.body);
        }
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodeSegment(segments[i]));
        }
        return sb.toString();
    }

    private static String encodeSegment(String s) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append(String.format("%%%02X", c));
            }
        }
        return out.toString();
    }

    private static class HttpResult {
        int code;
        String body;
    }

    private HttpResult call(String method, String urlString, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "token " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "ZipToGitHubApp");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = stream != null ? readAll(stream) : "";

            HttpResult result = new HttpResult();
            result.code = code;
            result.body = body;
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toString("UTF-8");
    }

    private void log(String line) {
        mainHandler.post(() -> listener.onLog(line));
    }

    private void progress(int done, int total) {
        mainHandler.post(() -> listener.onProgress(done, total));
    }

    private void finished(boolean success, String url, int uploaded, int failed) {
        mainHandler.post(() -> listener.onFinished(success, url, uploaded, failed));
    }

    private void fail(String message) {
        mainHandler.post(() -> {
            listener.onLog(message);
            listener.onFinished(false, null, 0, 0);
        });
    }
}
