package com.tencent.supersonic.headless.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChartImageRendererTest {

    @TempDir
    Path tempDir;

    private static ChartImageRenderer.ChartSpec spec() {
        return new ChartImageRenderer.ChartSpec("各机构存款余额排名", "bar",
                List.of("城东支行", "城西支行", "城南支行"), List.of(116.98, 88.5, 64.2), "机构名称",
                "存款余额");
    }

    @Test
    void rendersRealEchartsPngWhenHeadlessBrowserIsAvailable() throws Exception {
        ChartImageRenderer renderer = new ChartImageRenderer("", "");
        long started = System.nanoTime();

        byte[] png = renderer.renderPng(spec());

        assumeTrue(png != null,
                "headless Edge/Chrome or echarts.min.js not available on this machine");
        assertTrue(png.length > 1000, "screenshot should not be empty");
        assertTrue(png[0] == (byte) 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G',
                "screenshot should be a PNG");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(ChartImageRenderer.WINDOW_WIDTH, image.getWidth());
        assertEquals(ChartImageRenderer.WINDOW_HEIGHT, image.getHeight());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("headless chart render took " + elapsedMs + " ms");
        assertTrue(elapsedMs < 30_000, "rendering should stay well below the timeout");
    }

    @Test
    void returnsNullWhenBrowserCannotStart() throws Exception {
        // An existing but non-executable file forces the browser process launch to fail.
        Path fakeBrowser = tempDir.resolve("msedge.exe");
        Files.writeString(fakeBrowser, "not a browser");
        ChartImageRenderer renderer = new ChartImageRenderer(fakeBrowser.toString(), "");

        assertNull(renderer.renderPng(spec()));
    }
}
