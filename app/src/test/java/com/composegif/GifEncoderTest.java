package com.composegif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GifEncoderTest
{

	@Test
	void roundTripThreeFrames(@TempDir Path tempDir) throws Exception
	{
		// Create 3 frames of 2x2 indexed images with different colors
		List<FrameLoader.FrameData> frames = new ArrayList<>();

		frames.add(createSolidFrame(2, 2, 255, 0, 0));     // Red
		frames.add(createSolidFrame(2, 2, 0, 255, 0));     // Green
		frames.add(createSolidFrame(2, 2, 0, 0, 255));     // Blue

		File output = tempDir.resolve("test.gif").toFile();
		GifEncoder.encode(frames, output, 1,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, 100, null);

		assertTrue(output.exists());
		assertTrue(output.length() > 0);

		// Read back and verify frame count and dimensions
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(output))
		{
			reader.setInput(iis);
			int numFrames = reader.getNumImages(true);
			assertEquals(3, numFrames);

			for (int i = 0; i < numFrames; i++)
			{
				BufferedImage frame = reader.read(i);
				assertEquals(2, frame.getWidth());
				assertEquals(2, frame.getHeight());
			}
		}
		finally
		{
			reader.dispose();
		}
	}

	@Test
	void scalingDoublesResolution(@TempDir Path tempDir) throws Exception
	{
		List<FrameLoader.FrameData> frames = List.of(
				createSolidFrame(4, 4, 255, 0, 0)
		);

		File output = tempDir.resolve("scaled.gif").toFile();
		GifEncoder.encode(frames, output, 3,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, 100, null);

		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(output))
		{
			reader.setInput(iis);
			BufferedImage frame = reader.read(0);
			assertEquals(12, frame.getWidth());
			assertEquals(12, frame.getHeight());
		}
		finally
		{
			reader.dispose();
		}
	}

	@Test
	void transparencyPreserved(@TempDir Path tempDir) throws Exception
	{
		// Create a frame with a transparent pixel
		byte[] r = {(byte) 255, 0};
		byte[] g = {0, 0};
		byte[] b = {0, 0};
		int transparentIndex = 1;
		IndexColorModel icm = new IndexColorModel(8, 2, r, g, b, transparentIndex);

		BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_INDEXED, icm);
		img.getRaster().setSample(0, 0, 0, 0); // Red
		img.getRaster().setSample(1, 0, 0, 1); // Transparent
		img.getRaster().setSample(0, 1, 0, 1); // Transparent
		img.getRaster().setSample(1, 1, 0, 0); // Red

		List<FrameLoader.FrameData> frames = List.of(
				new FrameLoader.FrameData(img, transparentIndex)
		);

		File output = tempDir.resolve("transparent.gif").toFile();
		GifEncoder.encode(frames, output, 1,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, 100, null);

		assertTrue(output.exists());

		// Read back — verify the image has the right size
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(output))
		{
			reader.setInput(iis);
			BufferedImage readBack = reader.read(0);
			assertEquals(2, readBack.getWidth());
			assertEquals(2, readBack.getHeight());

			// Check that transparent pixels have alpha = 0
			int topRight = readBack.getRGB(1, 0);
			assertEquals(0, (topRight >> 24) & 0xFF, "Transparent pixel should have alpha=0");

			// Check that opaque pixels are red-ish
			int topLeft = readBack.getRGB(0, 0);
			assertTrue(((topLeft >> 24) & 0xFF) > 0, "Opaque pixel should have non-zero alpha");
		}
		finally
		{
			reader.dispose();
		}
	}

	@Test
	void progressListenerCalled(@TempDir Path tempDir) throws Exception
	{
		List<FrameLoader.FrameData> frames = List.of(
				createSolidFrame(2, 2, 255, 0, 0),
				createSolidFrame(2, 2, 0, 255, 0)
		);

		File output = tempDir.resolve("progress.gif").toFile();
		List<int[]> progressCalls = new ArrayList<>();

		GifEncoder.encode(frames, output, 1,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, 100,
				(current, total) -> progressCalls.add(new int[]{current, total}));

		assertEquals(2, progressCalls.size());
		assertArrayEquals(new int[]{1, 2}, progressCalls.get(0));
		assertArrayEquals(new int[]{2, 2}, progressCalls.get(1));
	}

	@Test
	void globalColorTableSetWhenPalettesIdentical(@TempDir Path tempDir) throws Exception
	{
		// Create 3 frames with identical palettes
		byte[] r = {(byte) 255, 0, 0};
		byte[] g = {0, (byte) 255, 0};
		byte[] b = {0, 0, (byte) 255};
		IndexColorModel sharedIcm = new IndexColorModel(8, 3, r, g, b);

		List<FrameLoader.FrameData> frames = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_INDEXED, sharedIcm);
			img.getRaster().setSample(0, 0, 0, i % 3); // Different pixel per frame
			frames.add(new FrameLoader.FrameData(img, -1));
		}

		File output = tempDir.resolve("global_ct.gif").toFile();
		GifEncoder.encode(frames, output, 1,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, 100, null);

		// Read back and verify GlobalColorTable is present in stream metadata
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(output))
		{
			reader.setInput(iis);
			IIOMetadata streamMeta = reader.getStreamMetadata();
			String formatName = streamMeta.getNativeMetadataFormatName();
			IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(formatName);

			// Find GlobalColorTable node
			IIOMetadataNode globalCT = null;
			for (int i = 0; i < root.getLength(); i++)
			{
				if ("GlobalColorTable".equals(root.item(i).getNodeName()))
				{
					globalCT = (IIOMetadataNode) root.item(i);
					break;
				}
			}
			assertNotNull(globalCT, "GlobalColorTable should be present when all frames share a palette");
			assertTrue(globalCT.getChildNodes().getLength() >= 3,
					"GlobalColorTable should have at least 3 color entries");
		}
		finally
		{
			reader.dispose();
		}
	}

	@Test
	void bilinearScalingProducesValidGif(@TempDir Path tempDir) throws Exception
	{
		List<FrameLoader.FrameData> frames = List.of(
				createSolidFrame(4, 4, 255, 0, 0),
				createSolidFrame(4, 4, 0, 255, 0)
		);

		File output = tempDir.resolve("bilinear.gif").toFile();
		GifEncoder.encode(frames, output, 3,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR, 100, null);

		assertTrue(output.exists());

		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(output))
		{
			reader.setInput(iis);
			assertEquals(2, reader.getNumImages(true));
			BufferedImage frame = reader.read(0);
			assertEquals(12, frame.getWidth());
			assertEquals(12, frame.getHeight());
		}
		finally
		{
			reader.dispose();
		}
	}

	private static FrameLoader.FrameData createSolidFrame(int w, int h, int red, int green, int blue)
	{
		byte[] r = {(byte) red, 0};
		byte[] g = {(byte) green, 0};
		byte[] b = {(byte) blue, 0};
		IndexColorModel icm = new IndexColorModel(8, 2, r, g, b);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
		// Default raster is all zeros = first palette entry = our color
		return new FrameLoader.FrameData(img, -1);
	}
}