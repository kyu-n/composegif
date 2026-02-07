package com.composegif;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PaletteExtractionTest
{

	@Test
	void palettePreservedFromIndexedPng(@TempDir Path tempDir) throws Exception
	{
		// Create a paletted PNG with known palette: red, green, blue, white
		byte[] reds = {(byte) 255, 0, 0, (byte) 255};
		byte[] greens = {0, (byte) 255, 0, (byte) 255};
		byte[] blues = {0, 0, (byte) 255, (byte) 255};
		IndexColorModel icm = new IndexColorModel(8, 4, reds, greens, blues);

		BufferedImage img = new BufferedImage(4, 1, BufferedImage.TYPE_BYTE_INDEXED, icm);
		img.getRaster().setSample(0, 0, 0, 0); // Red
		img.getRaster().setSample(1, 0, 0, 1); // Green
		img.getRaster().setSample(2, 0, 0, 2); // Blue
		img.getRaster().setSample(3, 0, 0, 3); // White

		File pngFile = tempDir.resolve("palette_test.png").toFile();
		ImageIO.write(img, "png", pngFile);

		// Load via ImageIO (preserves IndexColorModel for paletted PNGs)
		BufferedImage loaded = ImageIO.read(pngFile);
		assertInstanceOf(IndexColorModel.class, loaded.getColorModel());
		IndexColorModel loadedIcm = (IndexColorModel) loaded.getColorModel();

		assertTrue(loadedIcm.getMapSize() >= 4, "Palette should have at least 4 entries");

		// Verify pixel colors (via getRGB which accounts for palette mapping)
		int px0 = loaded.getRGB(0, 0);
		int px1 = loaded.getRGB(1, 0);
		int px2 = loaded.getRGB(2, 0);
		int px3 = loaded.getRGB(3, 0);

		assertColorEquals(255, 0, 0, px0, "Pixel 0 should be red");
		assertColorEquals(0, 255, 0, px1, "Pixel 1 should be green");
		assertColorEquals(0, 0, 255, px2, "Pixel 2 should be blue");
		assertColorEquals(255, 255, 255, px3, "Pixel 3 should be white");
	}

	@Test
	void transparentIndexPreserved(@TempDir Path tempDir) throws Exception
	{
		// Create a paletted PNG with transparent entry at index 1
		byte[] reds = {(byte) 255, 0};
		byte[] greens = {0, 0};
		byte[] blues = {0, 0};
		int transparentIdx = 1;
		IndexColorModel icm = new IndexColorModel(8, 2, reds, greens, blues, transparentIdx);

		BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_INDEXED, icm);
		img.getRaster().setSample(0, 0, 0, 0); // Red (opaque)
		img.getRaster().setSample(1, 0, 0, 1); // Transparent

		File pngFile = tempDir.resolve("transparent_test.png").toFile();
		ImageIO.write(img, "png", pngFile);

		// Load via ImageIO
		BufferedImage loaded = ImageIO.read(pngFile);
		assertInstanceOf(IndexColorModel.class, loaded.getColorModel());
		IndexColorModel loadedIcm = (IndexColorModel) loaded.getColorModel();

		int loadedTransparent = loadedIcm.getTransparentPixel();
		assertTrue(loadedTransparent >= 0, "Transparent index should be preserved");

		// Verify the transparent pixel has alpha = 0
		int transparentPixel = loaded.getRGB(1, 0);
		assertEquals(0, (transparentPixel >> 24) & 0xFF,
				"Transparent pixel should have alpha=0");

		// Verify the opaque pixel has full alpha
		int opaquePixel = loaded.getRGB(0, 0);
		assertEquals(255, (opaquePixel >> 24) & 0xFF,
				"Opaque pixel should have alpha=255");
	}

	@Test
	void paletteRgbValuesExtractedCorrectly(@TempDir Path tempDir) throws Exception
	{
		// Create specific palette: (128, 64, 32), (200, 100, 50), (10, 20, 30)
		byte[] reds = {(byte) 128, (byte) 200, (byte) 10};
		byte[] greens = {(byte) 64, (byte) 100, (byte) 20};
		byte[] blues = {(byte) 32, (byte) 50, (byte) 30};
		IndexColorModel icm = new IndexColorModel(8, 3, reds, greens, blues);

		BufferedImage img = new BufferedImage(3, 1, BufferedImage.TYPE_BYTE_INDEXED, icm);
		img.getRaster().setSample(0, 0, 0, 0);
		img.getRaster().setSample(1, 0, 0, 1);
		img.getRaster().setSample(2, 0, 0, 2);

		File pngFile = tempDir.resolve("rgb_test.png").toFile();
		ImageIO.write(img, "png", pngFile);

		BufferedImage loaded = ImageIO.read(pngFile);
		assertInstanceOf(IndexColorModel.class, loaded.getColorModel());
		IndexColorModel loadedIcm = (IndexColorModel) loaded.getColorModel();

		// Extract palette arrays
		int mapSize = loadedIcm.getMapSize();
		byte[] loadedReds = new byte[mapSize];
		byte[] loadedGreens = new byte[mapSize];
		byte[] loadedBlues = new byte[mapSize];
		loadedIcm.getReds(loadedReds);
		loadedIcm.getGreens(loadedGreens);
		loadedIcm.getBlues(loadedBlues);

		// Verify pixel colors (via getRGB which accounts for palette mapping)
		assertColorEquals(128, 64, 32, loaded.getRGB(0, 0), "Pixel 0");
		assertColorEquals(200, 100, 50, loaded.getRGB(1, 0), "Pixel 1");
		assertColorEquals(10, 20, 30, loaded.getRGB(2, 0), "Pixel 2");
	}

	@Test
	void quantizationProducesIndexedImage()
	{
		// Create a truecolor image with a few distinct colors
		BufferedImage truecolor = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
		truecolor.setRGB(0, 0, 0xFFFF0000); // Red
		truecolor.setRGB(1, 0, 0xFF00FF00); // Green
		truecolor.setRGB(2, 0, 0xFF0000FF); // Blue

		FrameLoader.QuantizeResult result = FrameLoader.quantizeToIndexed(truecolor);
		BufferedImage indexed = result.image();

		assertEquals(BufferedImage.TYPE_BYTE_INDEXED, indexed.getType());
		assertInstanceOf(IndexColorModel.class, indexed.getColorModel());

		// Verify colors preserved
		assertColorEquals(255, 0, 0, indexed.getRGB(0, 0), "Red");
		assertColorEquals(0, 255, 0, indexed.getRGB(1, 0), "Green");
		assertColorEquals(0, 0, 255, indexed.getRGB(2, 0), "Blue");
	}

	@Test
	void quantizationHandlesTransparency()
	{
		BufferedImage truecolor = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
		truecolor.setRGB(0, 0, 0xFFFF0000); // Opaque red
		truecolor.setRGB(1, 0, 0x00000000); // Fully transparent

		FrameLoader.QuantizeResult result = FrameLoader.quantizeToIndexed(truecolor);
		assertTrue(result.transparentIndex() >= 0, "Should have a transparent index");

		int transparentPixel = result.image().getRGB(1, 0);
		assertEquals(0, (transparentPixel >> 24) & 0xFF, "Transparent pixel alpha should be 0");
	}

	@Test
	void palettePreservedThroughFrameLoader(@TempDir Path tempDir) throws Exception
	{
		// End-to-end: write a paletted PNG, load via FrameLoader, verify palette
		byte[] reds = {(byte) 255, 0, 0};
		byte[] greens = {0, (byte) 255, 0};
		byte[] blues = {0, 0, (byte) 255};
		IndexColorModel icm = new IndexColorModel(8, 3, reds, greens, blues);

		BufferedImage img = new BufferedImage(3, 1, BufferedImage.TYPE_BYTE_INDEXED, icm);
		img.getRaster().setSample(0, 0, 0, 0); // Red
		img.getRaster().setSample(1, 0, 0, 1); // Green
		img.getRaster().setSample(2, 0, 0, 2); // Blue

		ImageIO.write(img, "png", tempDir.resolve("1.png").toFile());

		FrameLoader.LoadResult result = FrameLoader.load(tempDir.toFile());
		assertEquals(1, result.frames().size());
		assertTrue(result.warnings().isEmpty(), "Paletted PNG should produce no warnings");

		BufferedImage loaded = result.frames().get(0).image();
		assertInstanceOf(IndexColorModel.class, loaded.getColorModel());

		assertColorEquals(255, 0, 0, loaded.getRGB(0, 0), "Pixel 0 should be red");
		assertColorEquals(0, 255, 0, loaded.getRGB(1, 0), "Pixel 1 should be green");
		assertColorEquals(0, 0, 255, loaded.getRGB(2, 0), "Pixel 2 should be blue");
	}

	@Test
	void commonsImagingDetectsPalettedPng(@TempDir Path tempDir) throws Exception
	{
		// Create a paletted PNG
		byte[] reds = {(byte) 255, 0};
		byte[] greens = {0, (byte) 255};
		byte[] blues = {0, 0};
		IndexColorModel icm = new IndexColorModel(8, 2, reds, greens, blues);
		BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_INDEXED, icm);
		File pngFile = tempDir.resolve("1.png").toFile();
		ImageIO.write(img, "png", pngFile);

		// Verify Commons Imaging can read metadata
		ImageInfo info = Imaging.getImageInfo(pngFile);
		assertEquals(ImageInfo.ColorType.RGB, info.getColorType());
		assertTrue(info.getBitsPerPixel() <= 8, "Paletted PNG should be <= 8 bpp");
	}

	@Test
	void commonsImagingDetectsTruecolorPng(@TempDir Path tempDir) throws Exception
	{
		// Create a truecolor (ARGB) PNG
		BufferedImage truecolor = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		truecolor.setRGB(0, 0, 0xFFFF0000);
		truecolor.setRGB(1, 0, 0xFF00FF00);
		truecolor.setRGB(0, 1, 0xFF0000FF);
		truecolor.setRGB(1, 1, 0x80FFFFFF);
		File pngFile = tempDir.resolve("1.png").toFile();
		ImageIO.write(truecolor, "png", pngFile);

		ImageInfo info = Imaging.getImageInfo(pngFile);
		assertTrue(info.getBitsPerPixel() > 8, "Truecolor PNG should be > 8 bpp");
		assertTrue(info.isTransparent(), "ARGB PNG should report transparency");
	}

	private static void assertColorEquals(int expectedR, int expectedG, int expectedB, int actualArgb, String msg)
	{
		int ar = (actualArgb >> 16) & 0xFF;
		int ag = (actualArgb >> 8) & 0xFF;
		int ab = actualArgb & 0xFF;
		assertEquals(expectedR, ar, msg + " red component");
		assertEquals(expectedG, ag, msg + " green component");
		assertEquals(expectedB, ab, msg + " blue component");
	}
}