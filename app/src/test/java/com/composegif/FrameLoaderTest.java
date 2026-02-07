package com.composegif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameLoaderTest
{

	// --- Filename pattern matching ---

	@Test
	void acceptsSimpleNumericFilenames()
	{
		assertTrue(FrameLoader.isFrameFilename("1.png"));
		assertTrue(FrameLoader.isFrameFilename("2.png"));
		assertTrue(FrameLoader.isFrameFilename("100.png"));
		assertTrue(FrameLoader.isFrameFilename("999.png"));
	}

	@Test
	void acceptsZeroPaddedFilenames()
	{
		assertTrue(FrameLoader.isFrameFilename("01.png"));
		assertTrue(FrameLoader.isFrameFilename("02.png"));
		assertTrue(FrameLoader.isFrameFilename("001.png"));
		assertTrue(FrameLoader.isFrameFilename("0001.png"));
	}

	@Test
	void acceptsCaseInsensitivePngExtension()
	{
		assertTrue(FrameLoader.isFrameFilename("1.PNG"));
		assertTrue(FrameLoader.isFrameFilename("1.Png"));
	}

	@Test
	void rejectsNonNumericFilenames()
	{
		assertFalse(FrameLoader.isFrameFilename("a.png"));
		assertFalse(FrameLoader.isFrameFilename("frame1.png"));
		assertFalse(FrameLoader.isFrameFilename("test.png"));
	}

	@Test
	void rejectsEmptyName()
	{
		assertFalse(FrameLoader.isFrameFilename(".png"));
	}

	@Test
	void rejectsWrongExtension()
	{
		assertFalse(FrameLoader.isFrameFilename("1.jpg"));
		assertFalse(FrameLoader.isFrameFilename("1.gif"));
		assertFalse(FrameLoader.isFrameFilename("1.bmp"));
	}

	@Test
	void rejectsZeroFrameNumber()
	{
		assertFalse(FrameLoader.isFrameFilename("0.png"));
		assertFalse(FrameLoader.isFrameFilename("00.png"));
		assertFalse(FrameLoader.isFrameFilename("000.png"));
	}

	// --- Numeric sort order ---

	@Test
	void extractsCorrectFrameNumbers()
	{
		assertEquals(1, FrameLoader.extractFrameNumber("1.png"));
		assertEquals(1, FrameLoader.extractFrameNumber("01.png"));
		assertEquals(1, FrameLoader.extractFrameNumber("001.png"));
		assertEquals(10, FrameLoader.extractFrameNumber("10.png"));
		assertEquals(100, FrameLoader.extractFrameNumber("100.png"));
		assertEquals(2, FrameLoader.extractFrameNumber("02.png"));
	}

	@Test
	void framesLoadedInNumericOrder(@TempDir Path tempDir) throws Exception
	{
		// Create frames out of order: 3.png, 1.png, 10.png, 2.png
		// Each with a distinct color so we can verify ordering
		Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
		int[] frameNums = {3, 1, 10, 2};
		for (int i = 0; i < frameNums.length; i++)
		{
			writeSolidColorPng(tempDir.resolve(frameNums[i] + ".png").toFile(), 4, 4, colors[i]);
		}

		FrameLoader.LoadResult result = FrameLoader.load(tempDir.toFile());
		assertEquals(4, result.frames().size());

		// Expected numeric order: 1, 2, 3, 10
		// Frame 1 was created with GREEN (colors[1])
		// Frame 2 was created with YELLOW (colors[3])
		// Frame 3 was created with RED (colors[0])
		// Frame 10 was created with BLUE (colors[2])
		assertFrameColor(result.frames().get(0), Color.GREEN,  "Frame 1");
		assertFrameColor(result.frames().get(1), Color.YELLOW, "Frame 2");
		assertFrameColor(result.frames().get(2), Color.RED,    "Frame 3");
		assertFrameColor(result.frames().get(3), Color.BLUE,   "Frame 10");
	}

	@Test
	void numericSortNotLexicographic(@TempDir Path tempDir) throws Exception
	{
		// Lexicographic would sort: 1, 10, 2, 20, 3
		// Numeric should sort: 1, 2, 3, 10, 20
		Color[] colors = {Color.MAGENTA, Color.CYAN, Color.ORANGE, Color.PINK, Color.WHITE};
		int[] frameNums = {20, 3, 10, 1, 2};
		for (int i = 0; i < frameNums.length; i++)
		{
			writeSolidColorPng(tempDir.resolve(frameNums[i] + ".png").toFile(), 4, 4, colors[i]);
		}

		FrameLoader.LoadResult result = FrameLoader.load(tempDir.toFile());
		assertEquals(5, result.frames().size());

		// Expected numeric order: 1, 2, 3, 10, 20
		// Frame 1 -> PINK (colors[3])
		// Frame 2 -> WHITE (colors[4])
		// Frame 3 -> CYAN (colors[1])
		// Frame 10 -> ORANGE (colors[2])
		// Frame 20 -> MAGENTA (colors[0])
		assertFrameColor(result.frames().get(0), Color.PINK,    "Frame 1");
		assertFrameColor(result.frames().get(1), Color.WHITE,   "Frame 2");
		assertFrameColor(result.frames().get(2), Color.CYAN,    "Frame 3");
		assertFrameColor(result.frames().get(3), Color.ORANGE,  "Frame 10");
		assertFrameColor(result.frames().get(4), Color.MAGENTA, "Frame 20");
	}

	// --- Error handling ---

	@Test
	void errorOnNoMatchingFiles(@TempDir Path tempDir) throws Exception
	{
		// Create a non-matching file
		writeSolidColorPng(tempDir.resolve("a.png").toFile(), 4, 4, Color.RED);

		assertThrows(Exception.class, () -> FrameLoader.load(tempDir.toFile()));
	}

	@Test
	void errorOnInconsistentDimensions(@TempDir Path tempDir) throws Exception
	{
		writeSolidColorPng(tempDir.resolve("1.png").toFile(), 4, 4, Color.RED);
		writeSolidColorPng(tempDir.resolve("2.png").toFile(), 8, 8, Color.RED);

		Exception ex = assertThrows(Exception.class, () -> FrameLoader.load(tempDir.toFile()));
		assertTrue(ex.getMessage().contains("Inconsistent"));
	}

	@Test
	void ignoresNonMatchingFiles(@TempDir Path tempDir) throws Exception
	{
		writeSolidColorPng(tempDir.resolve("1.png").toFile(), 4, 4, Color.RED);
		writeSolidColorPng(tempDir.resolve("2.png").toFile(), 4, 4, Color.GREEN);
		writeSolidColorPng(tempDir.resolve("readme.txt").toFile(), 4, 4, Color.BLUE);

		FrameLoader.LoadResult result = FrameLoader.load(tempDir.toFile());
		assertEquals(2, result.frames().size());
	}

	// --- Truecolor quantization ---

	@Test
	void truecolorPngProducesWarning(@TempDir Path tempDir) throws Exception
	{
		// Write a truecolor (ARGB) PNG
		BufferedImage truecolor = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = truecolor.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 4, 4);
		g.dispose();
		ImageIO.write(truecolor, "png", tempDir.resolve("1.png").toFile());

		FrameLoader.LoadResult result = FrameLoader.load(tempDir.toFile());
		assertEquals(1, result.frames().size());
		assertFalse(result.warnings().isEmpty());
		assertTrue(result.warnings().get(0).contains("truecolor"));

		// Result should be indexed
		BufferedImage loaded = result.frames().get(0).image();
		assertInstanceOf(IndexColorModel.class, loaded.getColorModel());
	}

	// --- Natural sort comparator ---

	@Test
	void naturalSortComparator()
	{
		File f2 = new File("2.png");
		File f10 = new File("10.png");
		File frame01 = new File("frame_01.png");
		File frame02 = new File("frame_02.png");
		File frame10 = new File("frame_10.png");

		assertTrue(FrameLoader.NATURAL_ORDER.compare(f2, f10) < 0, "2.png < 10.png");
		assertTrue(FrameLoader.NATURAL_ORDER.compare(f10, f2) > 0, "10.png > 2.png");
		assertTrue(FrameLoader.NATURAL_ORDER.compare(frame01, frame02) < 0, "frame_01 < frame_02");
		assertTrue(FrameLoader.NATURAL_ORDER.compare(frame02, frame10) < 0, "frame_02 < frame_10");
		assertEquals(0, FrameLoader.NATURAL_ORDER.compare(f2, new File("2.png")), "equal files");
	}

	// --- load(List<File>) ---

	@Test
	void loadFromFileList(@TempDir Path tempDir) throws Exception
	{
		File f1 = tempDir.resolve("frame_a.png").toFile();
		File f2 = tempDir.resolve("frame_b.png").toFile();
		File f3 = tempDir.resolve("frame_c.png").toFile();
		writeSolidColorPng(f1, 4, 4, Color.RED);
		writeSolidColorPng(f2, 4, 4, Color.GREEN);
		writeSolidColorPng(f3, 4, 4, Color.BLUE);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(f1, f2, f3));
		assertEquals(3, result.frames().size());
		assertEquals(4, result.width());
		assertEquals(4, result.height());
	}

	@Test
	void emptyFileListThrows()
	{
		assertThrows(IOException.class, () -> FrameLoader.load(List.of()));
	}

	@Test
	void singleFileLoads(@TempDir Path tempDir) throws Exception
	{
		File f = tempDir.resolve("only.png").toFile();
		writeSolidColorPng(f, 8, 8, Color.CYAN);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(f));
		assertEquals(1, result.frames().size());
		assertEquals(8, result.width());
		assertEquals(8, result.height());
	}

	@Test
	void fileListSortsNaturally(@TempDir Path tempDir) throws Exception
	{
		// Create files with names that would sort wrong lexicographically
		File f1 = tempDir.resolve("frame_1.png").toFile();
		File f2 = tempDir.resolve("frame_2.png").toFile();
		File f10 = tempDir.resolve("frame_10.png").toFile();
		writeSolidColorPng(f1, 4, 4, Color.RED);
		writeSolidColorPng(f2, 4, 4, Color.GREEN);
		writeSolidColorPng(f10, 4, 4, Color.BLUE);

		// Pass in scrambled order: 10, 1, 2
		FrameLoader.LoadResult result = FrameLoader.load(Arrays.asList(f10, f1, f2));
		assertEquals(3, result.frames().size());

		// Natural sort: frame_1, frame_2, frame_10
		assertFrameColor(result.frames().get(0), Color.RED,   "frame_1");
		assertFrameColor(result.frames().get(1), Color.GREEN, "frame_2");
		assertFrameColor(result.frames().get(2), Color.BLUE,  "frame_10");
	}

	private static void assertFrameColor(FrameLoader.FrameData frame, Color expected, String label)
	{
		int rgb = frame.image().getRGB(0, 0);
		int actualR = (rgb >> 16) & 0xFF;
		int actualG = (rgb >> 8) & 0xFF;
		int actualB = rgb & 0xFF;
		assertEquals(expected.getRed(), actualR, label + " red component");
		assertEquals(expected.getGreen(), actualG, label + " green component");
		assertEquals(expected.getBlue(), actualB, label + " blue component");
	}

	// --- GIF loading ---

	@Test
	void loadMultiFrameGif(@TempDir Path tempDir) throws Exception
	{
		File gif = tempDir.resolve("anim.gif").toFile();
		writeTestGif(gif, 4, 4, new Color[]{Color.RED, Color.GREEN, Color.BLUE}, 10);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(gif));
		assertEquals(3, result.frames().size());
		assertEquals(4, result.width());
		assertEquals(4, result.height());
		assertEquals(100, result.extractedDelayMs());
	}

	@Test
	void loadSingleFrameGif(@TempDir Path tempDir) throws Exception
	{
		File gif = tempDir.resolve("still.gif").toFile();
		writeTestGif(gif, 8, 8, new Color[]{Color.CYAN}, 5);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(gif));
		assertEquals(1, result.frames().size());
		assertEquals(8, result.width());
		assertEquals(8, result.height());
		assertEquals(50, result.extractedDelayMs());
	}

	@Test
	void gifFramesAreIndexed(@TempDir Path tempDir) throws Exception
	{
		File gif = tempDir.resolve("anim.gif").toFile();
		writeTestGif(gif, 4, 4, new Color[]{Color.RED, Color.GREEN}, 10);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(gif));
		for (FrameLoader.FrameData frame : result.frames())
		{
			assertInstanceOf(IndexColorModel.class, frame.image().getColorModel());
		}
	}

	@Test
	void gifExtractsDelay(@TempDir Path tempDir) throws Exception
	{
		File gif = tempDir.resolve("slow.gif").toFile();
		writeTestGif(gif, 4, 4, new Color[]{Color.RED, Color.GREEN}, 20); // 200ms

		FrameLoader.LoadResult result = FrameLoader.load(List.of(gif));
		assertEquals(200, result.extractedDelayMs());
	}

	@Test
	void stillImagesHaveNoExtractedDelay(@TempDir Path tempDir) throws Exception
	{
		File f = tempDir.resolve("still.png").toFile();
		writeSolidColorPng(f, 4, 4, Color.RED);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(f));
		assertEquals(-1, result.extractedDelayMs());
	}

	@Test
	void multipleGifsExtractAllFrames(@TempDir Path tempDir) throws Exception
	{
		File gif1 = tempDir.resolve("a.gif").toFile();
		File gif2 = tempDir.resolve("b.gif").toFile();
		writeTestGif(gif1, 4, 4, new Color[]{Color.RED, Color.GREEN}, 10);    // 100ms
		writeTestGif(gif2, 4, 4, new Color[]{Color.BLUE, Color.CYAN}, 20);    // 200ms

		FrameLoader.LoadResult result = FrameLoader.load(List.of(gif1, gif2));
		assertEquals(4, result.frames().size()); // all frames from both GIFs
		assertEquals(100, result.extractedDelayMs()); // timing from first GIF only
	}

	// --- BMP/JPEG loading ---

	@Test
	void loadBmpWithQuantization(@TempDir Path tempDir) throws Exception
	{
		File bmp = tempDir.resolve("frame.bmp").toFile();
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 4, 4);
		g.dispose();
		ImageIO.write(img, "bmp", bmp);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(bmp));
		assertEquals(1, result.frames().size());
		assertInstanceOf(IndexColorModel.class, result.frames().get(0).image().getColorModel());
		assertFalse(result.warnings().isEmpty());
		assertTrue(result.warnings().get(0).contains("truecolor"));
	}

	@Test
	void loadJpegWithQuantization(@TempDir Path tempDir) throws Exception
	{
		File jpg = tempDir.resolve("frame.jpg").toFile();
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, 4, 4);
		g.dispose();
		ImageIO.write(img, "jpg", jpg);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(jpg));
		assertEquals(1, result.frames().size());
		assertInstanceOf(IndexColorModel.class, result.frames().get(0).image().getColorModel());
	}

	// --- Helpers ---

	private static void writeSolidColorPng(File file, int w, int h, Color color) throws Exception
	{
		// Create a paletted (indexed) PNG
		byte[] r = {(byte) color.getRed(), 0};
		byte[] g = {(byte) color.getGreen(), 0};
		byte[] b = {(byte) color.getBlue(), 0};
		IndexColorModel icm = new IndexColorModel(1, 2, r, g, b);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
		// Fill with palette index 0 (the color)
		// Default raster is all zeros, which maps to index 0 = our color
		ImageIO.write(img, "png", file);
	}

	private static void writeTestGif(File file, int w, int h, Color[] frameColors, int delayCentiseconds) throws Exception
	{
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(file))
		{
			writer.setOutput(ios);
			IIOMetadata streamMeta = writer.getDefaultStreamMetadata(null);
			writer.prepareWriteSequence(streamMeta);

			for (int i = 0; i < frameColors.length; i++)
			{
				BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = frame.createGraphics();
				g.setColor(frameColors[i]);
				g.fillRect(0, 0, w, h);
				g.dispose();

				// Convert to indexed for GIF
				BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED);
				Graphics2D ig = indexed.createGraphics();
				ig.drawImage(frame, 0, 0, null);
				ig.dispose();

				IIOMetadata imageMeta = writer.getDefaultImageMetadata(
						new ImageTypeSpecifier(indexed), null);
				String formatName = imageMeta.getNativeMetadataFormatName();
				IIOMetadataNode root = (IIOMetadataNode) imageMeta.getAsTree(formatName);

				// Set delay
				IIOMetadataNode gce = getOrCreateChild(root, "GraphicControlExtension");
				gce.setAttribute("disposalMethod", "none");
				gce.setAttribute("userInputFlag", "FALSE");
				gce.setAttribute("transparentColorFlag", "FALSE");
				gce.setAttribute("transparentColorIndex", "0");
				gce.setAttribute("delayTime", String.valueOf(delayCentiseconds));

				// Set looping
				if (i == 0)
				{
					IIOMetadataNode appExts = getOrCreateChild(root, "ApplicationExtensions");
					IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
					netscape.setAttribute("applicationID", "NETSCAPE");
					netscape.setAttribute("authenticationCode", "2.0");
					netscape.setUserObject(new byte[]{1, 0, 0});
					appExts.appendChild(netscape);
				}

				imageMeta.setFromTree(formatName, root);
				writer.writeToSequence(new IIOImage(indexed, null, imageMeta), null);
			}
			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
	}

	private static IIOMetadataNode getOrCreateChild(IIOMetadataNode parent, String name)
	{
		for (int i = 0; i < parent.getLength(); i++)
		{
			if (parent.item(i).getNodeName().equals(name))
			{
				return (IIOMetadataNode) parent.item(i);
			}
		}
		IIOMetadataNode child = new IIOMetadataNode(name);
		parent.appendChild(child);
		return child;
	}
}