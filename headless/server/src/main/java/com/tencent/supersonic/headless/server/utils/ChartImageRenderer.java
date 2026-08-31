package com.tencent.supersonic.headless.server.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Renders a chart specification to a PNG image by screenshotting a real ECharts page with a
 * headless Edge/Chrome browser. A self-contained HTML page is generated into a temporary
 * directory together with a copy of echarts.min.js (same-directory reference avoids file://
 * cross-directory restrictions), then the browser is invoked with --headless=new --screenshot.
 * Any failure (no browser, missing echarts.min.js, timeout, empty output) is reported as null so
 * callers can fall back to another rendering path; this renderer never breaks an export.
 */
@Slf4j
@Component
public class ChartImageRenderer {

    static final int WINDOW_WIDTH = 900;
    static final int WINDOW_HEIGHT = 500;
    static final long RENDER_TIMEOUT_SECONDS = 10;

    private static final String ECHARTS_PNP_PATH =
            "webapp/node_modules/.pnpm/echarts@5.5.0/node_modules/echarts/dist/echarts.min.js";
    private static final String ECHARTS_FLAT_PATH = "webapp/node_modules/echarts/dist/echarts.min.js";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String edgePathConfig;
    private final String echartsJsConfig;
    private volatile Path edgeBinary;
    private volatile Path echartsJs;
    private volatile boolean resolved;

    public ChartImageRenderer(@Value("${s2.export.chart.edge-path:}") String edgePath,
            @Value("${s2.export.chart.echarts-js:}") String echartsJs) {
        this.edgePathConfig = edgePath == null ? "" : edgePath.trim();
        this.echartsJsConfig = echartsJs == null ? "" : echartsJs.trim();
    }

    /** Chart specification: bar/line chart with a single value series. */
    public record ChartSpec(String title, String type, List<String> categories,
            List<Double> values, String categoryName, String valueName) {}

    /**
     * Renders the chart to PNG bytes, or returns null when rendering is unavailable or fails.
     */
    public byte[] renderPng(ChartSpec spec) {
        Path edge = edgeBinary();
        Path echarts = echartsJs();
        if (edge == null || echarts == null) {
            return null;
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("chart-render-");
            Path script = directory.resolve("echarts.min.js");
            Files.copy(echarts, script);
            Path html = directory.resolve("chart.html");
            Files.writeString(html, buildHtml(spec));
            Path screenshot = directory.resolve("chart.png");
            List<String> command = new ArrayList<>(List.of(edge.toString(), "--headless=new",
                    "--disable-gpu", "--no-first-run", "--disable-extensions",
                    "--user-data-dir=" + directory.resolve("profile"),
                    "--screenshot=" + screenshot,
                    "--window-size=" + WINDOW_WIDTH + "," + WINDOW_HEIGHT,
                    "--virtual-time-budget=3000", html.toUri().toString()));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            drain(process);
            if (!process.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Chart image rendering timed out after {}s", RENDER_TIMEOUT_SECONDS);
                return null;
            }
            if (!Files.isRegularFile(screenshot) || Files.size(screenshot) == 0) {
                log.warn("Chart image rendering produced no screenshot, exit={}",
                        process.exitValue());
                return null;
            }
            return Files.readAllBytes(screenshot);
        } catch (Exception e) {
            log.warn("Chart image rendering failed: errorType={}", e.getClass().getSimpleName());
            return null;
        } finally {
            deleteRecursively(directory);
        }
    }

    private String buildHtml(ChartSpec spec) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", spec.title());
        payload.put("type", spec.type());
        payload.put("categories", spec.categories());
        payload.put("values", spec.values());
        payload.put("categoryName", spec.categoryName());
        payload.put("valueName", spec.valueName());
        String json = objectMapper.writeValueAsString(payload);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<style>html,body{margin:0;padding:0}#chart{width:" + WINDOW_WIDTH
                + "px;height:" + WINDOW_HEIGHT + "px}</style>"
                + "<script src=\"echarts.min.js\"></script></head><body>"
                + "<div id=\"chart\"></div><script>"
                + "var spec=" + json + ";"
                + "var chart=echarts.init(document.getElementById('chart'),null,"
                + "{renderer:'canvas'});"
                + "chart.setOption({animation:false,"
                + "title:{text:spec.title,left:'center'},"
                + "grid:{left:80,right:40,top:60,bottom:70},"
                + "xAxis:{type:'category',data:spec.categories,name:spec.categoryName},"
                + "yAxis:{type:'value',name:spec.valueName},"
                + "series:[{type:spec.type,data:spec.values}]});"
                + "</script></body></html>";
    }

    private Path edgeBinary() {
        resolve();
        return edgeBinary;
    }

    private Path echartsJs() {
        resolve();
        return echartsJs;
    }

    private synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        edgeBinary = firstExisting(edgePathConfig, browserCandidates());
        echartsJs = firstExisting(echartsJsConfig, echartsCandidates());
        if (edgeBinary == null) {
            log.warn("No headless Edge/Chrome found; set s2.export.chart.edge-path to enable "
                    + "ECharts chart images in PDF exports");
        }
        if (echartsJs == null) {
            log.warn("echarts.min.js not found; set s2.export.chart.echarts-js to enable "
                    + "ECharts chart images in PDF exports");
        }
    }

    private static Path firstExisting(String configured, List<Path> candidates) {
        if (!configured.isEmpty()) {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) {
                return path;
            }
            log.warn("Configured chart renderer path does not exist: {}", configured);
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** Common Edge/Chrome install locations; headless Chrome accepts the same CLI flags. */
    private static List<Path> browserCandidates() {
        List<Path> candidates = new ArrayList<>();
        String[][] roots = {{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"},
                {"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}};
        String[] binaries = {"Microsoft\\Edge\\Application\\msedge.exe",
                "Google\\Chrome\\Application\\chrome.exe"};
        for (int i = 0; i < binaries.length; i++) {
            for (String root : roots[i]) {
                String base = System.getenv(root);
                if (base != null && !base.isBlank()) {
                    candidates.add(Path.of(base, binaries[i]));
                }
            }
        }
        return candidates;
    }

    /**
     * Locates the webapp echarts.min.js by walking up from the working directory; standalone
     * deployments should set s2.export.chart.echarts-js to an absolute path instead.
     */
    private static List<Path> echartsCandidates() {
        List<Path> candidates = new ArrayList<>();
        Path base = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && base != null; depth++, base = base.getParent()) {
            candidates.add(base.resolve(ECHARTS_PNP_PATH));
            candidates.add(base.resolve(ECHARTS_FLAT_PATH));
        }
        return candidates;
    }

    /** Consumes the process output so the browser cannot block on a full pipe buffer. */
    private static void drain(Process process) {
        Thread thread = new Thread(() -> {
            try (OutputStream sink = OutputStream.nullOutputStream()) {
                process.getInputStream().transferTo(sink);
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
