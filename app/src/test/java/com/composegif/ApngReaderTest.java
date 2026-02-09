package com.composegif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ApngReaderTest
{

	// --- isApng detection ---

	@Test
	void detectsApngFile(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.GREEN}, 100);

		assertTrue(ApngReader.isApng(apng));
	}

	@Test
	void regularPngIsNotApng(@TempDir Path tempDir) throws Exception
	{
		File png = tempDir.resolve("still.png").toFile();
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 4, 4);
		g.dispose();
		ImageIO.write(img, "png", png);

		assertFalse(ApngReader.isApng(png));
	}

	@Test
	void nonPngFileIsNotApng(@TempDir Path tempDir) throws Exception
	{
		File txt = tempDir.resolve("test.txt").toFile();
		try (FileOutputStream fos = new FileOutputStream(txt))
		{
			fos.write("not a png".getBytes(StandardCharsets.UTF_8));
		}

		assertFalse(ApngReader.isApng(txt));
	}

	// --- Multi-frame extraction ---

	@Test
	void extractsMultipleFrames(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.GREEN, Color.BLUE}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(3, result.frames().size());
	}

	@Test
	void frameDimensionsMatchCanvas(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 8, 6, new Color[]{Color.RED, Color.GREEN}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(8, result.width());
		assertEquals(6, result.height());
		for (ApngReader.ApngFrame frame : result.frames())
		{
			assertEquals(8, frame.image().getWidth());
			assertEquals(6, frame.image().getHeight());
		}
	}

	@Test
	void frameColorsAreCorrect(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.GREEN, Color.BLUE}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertFrameColor(result.frames().get(0), Color.RED, "Frame 1");
		assertFrameColor(result.frames().get(1), Color.GREEN, "Frame 2");
		assertFrameColor(result.frames().get(2), Color.BLUE, "Frame 3");
	}

	// --- Delay extraction ---

	@Test
	void extractsFrameDelay(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.GREEN}, 200);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		for (ApngReader.ApngFrame frame : result.frames())
		{
			assertEquals(200, frame.delayMs());
		}
	}

	@Test
	void framesAreArgb(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(BufferedImage.TYPE_INT_ARGB, result.frames().get(0).image().getType());
	}

	// --- Disposal operations ---

	@Test
	void disposeOpNoneAccumulatesCanvas(@TempDir Path tempDir) throws Exception
	{
		// Two frames: first fills left half red, second fills right half green
		// With DISPOSE_NONE, frame 2's canvas should show both halves
		File apng = tempDir.resolve("dispose_none.apng").toFile();
		writeTestApngWithSubFrames(apng, 8, 4,
			new SubFrame[]{
				new SubFrame(0, 0, 4, 4, Color.RED, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
				new SubFrame(4, 0, 4, 4, Color.GREEN, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
			}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(2, result.frames().size());

		// Frame 2 should have red on left, green on right
		BufferedImage frame2 = result.frames().get(1).image();
		assertPixelColor(frame2, 1, 1, Color.RED, "Frame 2 left side");
		assertPixelColor(frame2, 5, 1, Color.GREEN, "Frame 2 right side");
	}

	@Test
	void disposeOpBackgroundClearsRegion(@TempDir Path tempDir) throws Exception
	{
		// Frame 1 fills left half red with DISPOSE_BACKGROUND, frame 2 fills right half green
		// After frame 1's disposal, left half should be cleared
		// Frame 2 should only have green on right, left should be transparent
		File apng = tempDir.resolve("dispose_bg.apng").toFile();
		writeTestApngWithSubFrames(apng, 8, 4,
			new SubFrame[]{
				new SubFrame(0, 0, 4, 4, Color.RED, ApngReader.DISPOSE_OP_BACKGROUND, ApngReader.BLEND_OP_SOURCE),
				new SubFrame(4, 0, 4, 4, Color.GREEN, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
			}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(2, result.frames().size());

		// Frame 2: left should be transparent (alpha=0), right should be green
		BufferedImage frame2 = result.frames().get(1).image();
		int leftPixel = frame2.getRGB(1, 1);
		assertEquals(0, (leftPixel >> 24) & 0xFF, "Frame 2 left side should be transparent");
		assertPixelColor(frame2, 5, 1, Color.GREEN, "Frame 2 right side");
	}

	@Test
	void disposeOpPreviousRestoresCanvas(@TempDir Path tempDir) throws Exception
	{
		// Frame 1 fills full canvas blue with DISPOSE_NONE
		// Frame 2 fills left half red with DISPOSE_PREVIOUS
		// Frame 3 fills right half green — canvas should be restored to post-frame-1 state (all blue)
		// So frame 3 should have blue on left, green on right
		File apng = tempDir.resolve("dispose_prev.apng").toFile();
		writeTestApngWithSubFrames(apng, 8, 4,
			new SubFrame[]{
				new SubFrame(0, 0, 8, 4, Color.BLUE, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
				new SubFrame(0, 0, 4, 4, Color.RED, ApngReader.DISPOSE_OP_PREVIOUS, ApngReader.BLEND_OP_SOURCE),
				new SubFrame(4, 0, 4, 4, Color.GREEN, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
			}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(3, result.frames().size());

		// Frame 3: left should be blue (restored), right should be green
		BufferedImage frame3 = result.frames().get(2).image();
		assertPixelColor(frame3, 1, 1, Color.BLUE, "Frame 3 left side (restored)");
		assertPixelColor(frame3, 5, 1, Color.GREEN, "Frame 3 right side");
	}

	// --- Blend operations ---

	@Test
	void blendOpSourceOverwritesRegion(@TempDir Path tempDir) throws Exception
	{
		// Frame 1: full canvas red. Frame 2: left half green with BLEND_SOURCE
		// Frame 2 should have green on left (overwritten), red on right (accumulated)
		File apng = tempDir.resolve("blend_source.apng").toFile();
		writeTestApngWithSubFrames(apng, 8, 4,
			new SubFrame[]{
				new SubFrame(0, 0, 8, 4, Color.RED, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
				new SubFrame(0, 0, 4, 4, Color.GREEN, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
			}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		BufferedImage frame2 = result.frames().get(1).image();
		assertPixelColor(frame2, 1, 1, Color.GREEN, "Frame 2 left (overwritten)");
		assertPixelColor(frame2, 5, 1, Color.RED, "Frame 2 right (accumulated)");
	}

	@Test
	void blendOpOverCompositesWithAlpha(@TempDir Path tempDir) throws Exception
	{
		// Frame 1: full canvas red. Frame 2: left half semi-transparent green with BLEND_OVER
		// Frame 2's left side should be a blend of red and green
		File apng = tempDir.resolve("blend_over.apng").toFile();
		Color semiGreen = new Color(0, 255, 0, 128);
		writeTestApngWithSubFrames(apng, 8, 4,
			new SubFrame[]{
				new SubFrame(0, 0, 8, 4, Color.RED, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
				new SubFrame(0, 0, 4, 4, semiGreen, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_OVER),
			}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		BufferedImage frame2 = result.frames().get(1).image();

		// Left side should be a blend — not pure red, not pure green
		int pixel = frame2.getRGB(1, 1);
		int r = (pixel >> 16) & 0xFF;
		int g = (pixel >> 8) & 0xFF;
		assertTrue(r > 0 && r < 255, "Red component should be blended: " + r);
		assertTrue(g > 0 && g < 255, "Green component should be blended: " + g);

		// Right side should still be pure red
		assertPixelColor(frame2, 5, 1, Color.RED, "Frame 2 right (unchanged)");
	}

	// --- Sub-frame positioning ---

	@Test
	void subFramePositionedAtOffset(@TempDir Path tempDir) throws Exception
	{
		// Canvas 8x8, frame placed at offset (4,4) with size 4x4
		File apng = tempDir.resolve("offset.apng").toFile();
		writeTestApngWithSubFrames(apng, 8, 8,
			new SubFrame[]{
				new SubFrame(4, 4, 4, 4, Color.RED, ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE),
			}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		BufferedImage frame = result.frames().get(0).image();

		// Top-left should be transparent
		int topLeft = frame.getRGB(1, 1);
		assertEquals(0, (topLeft >> 24) & 0xFF, "Top-left should be transparent");

		// Bottom-right (in the sub-frame region) should be red
		assertPixelColor(frame, 5, 5, Color.RED, "Sub-frame region");
	}

	// --- Single frame ---

	@Test
	void singleFrameApng(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("single.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.CYAN}, 100);

		ApngReader.ApngResult result = ApngReader.loadApng(apng);
		assertEquals(1, result.frames().size());
		assertFrameColor(result.frames().get(0), Color.CYAN, "Single frame");
	}

	// --- Integration via FrameLoader ---

	@Test
	void frameLoaderDetectsApng(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.GREEN}, 100);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(apng));
		assertEquals(2, result.frames().size());
		assertEquals(4, result.width());
		assertEquals(4, result.height());
		assertEquals(100, result.extractedDelayMs());
	}

	@Test
	void pngExtensionWithApngContentDetected(@TempDir Path tempDir) throws Exception
	{
		// APNG file with .png extension should still be detected
		File apng = tempDir.resolve("sneaky.png").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.BLUE}, 150);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(apng));
		assertEquals(2, result.frames().size());
		assertEquals(150, result.extractedDelayMs());
	}

	@Test
	void frameLoaderApngFramesAreIndexed(@TempDir Path tempDir) throws Exception
	{
		File apng = tempDir.resolve("anim.apng").toFile();
		writeTestApng(apng, 4, 4, new Color[]{Color.RED, Color.GREEN}, 100);

		FrameLoader.LoadResult result = FrameLoader.load(List.of(apng));
		for (FrameLoader.FrameData frame : result.frames())
		{
			assertInstanceOf(java.awt.image.IndexColorModel.class, frame.image().getColorModel());
		}
	}

	// --- fcTL parsing ---

	@Test
	void parseFcTLCorrectly()
	{
		ByteBuffer buf = ByteBuffer.allocate(26);
		buf.putInt(42);   // sequence number
		buf.putInt(100);  // width
		buf.putInt(80);   // height
		buf.putInt(10);   // x offset
		buf.putInt(20);   // y offset
		buf.putShort((short) 1);  // delay num
		buf.putShort((short) 10); // delay den
		buf.put(ApngReader.DISPOSE_OP_BACKGROUND);
		buf.put(ApngReader.BLEND_OP_OVER);

		ApngReader.FrameControl fc = ApngReader.parseFcTL(buf.array());
		assertEquals(42, fc.sequenceNumber());
		assertEquals(100, fc.width());
		assertEquals(80, fc.height());
		assertEquals(10, fc.xOffset());
		assertEquals(20, fc.yOffset());
		assertEquals(1, fc.delayNum());
		assertEquals(10, fc.delayDen());
		assertEquals(ApngReader.DISPOSE_OP_BACKGROUND, fc.disposeOp());
		assertEquals(ApngReader.BLEND_OP_OVER, fc.blendOp());
	}

	// --- Helpers ---

	private static void assertFrameColor(ApngReader.ApngFrame frame, Color expected, String label)
	{
		assertPixelColor(frame.image(), 1, 1, expected, label);
	}

	private static void assertPixelColor(BufferedImage image, int x, int y, Color expected, String label)
	{
		int rgb = image.getRGB(x, y);
		int actualR = (rgb >> 16) & 0xFF;
		int actualG = (rgb >> 8) & 0xFF;
		int actualB = rgb & 0xFF;
		assertEquals(expected.getRed(), actualR, 2, label + " red component");
		assertEquals(expected.getGreen(), actualG, 2, label + " green component");
		assertEquals(expected.getBlue(), actualB, 2, label + " blue component");
	}

	/**
	 * Builds a simple APNG where each frame is a full-canvas solid color.
	 */
	private static void writeTestApng(File file, int w, int h, Color[] frameColors, int delayMs)
		throws Exception
	{
		SubFrame[] subFrames = new SubFrame[frameColors.length];
		for (int i = 0; i < frameColors.length; i++)
		{
			subFrames[i] = new SubFrame(0, 0, w, h, frameColors[i],
				ApngReader.DISPOSE_OP_NONE, ApngReader.BLEND_OP_SOURCE);
		}
		writeTestApngWithSubFrames(file, w, h, subFrames, delayMs);
	}

	private record SubFrame(int xOffset, int yOffset, int width, int height,
		Color color, byte disposeOp, byte blendOp) {}

	/**
	 * Builds an APNG with configurable sub-frame positions, colors, disposal, and blend ops.
	 */
	private static void writeTestApngWithSubFrames(File file, int canvasW, int canvasH,
		SubFrame[] subFrames, int delayMs) throws Exception
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// PNG signature
		out.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

		// IHDR
		ByteBuffer ihdr = ByteBuffer.allocate(13);
		ihdr.putInt(canvasW);
		ihdr.putInt(canvasH);
		ihdr.put((byte) 8);  // bit depth
		ihdr.put((byte) 6);  // color type: RGBA
		ihdr.put((byte) 0);  // compression
		ihdr.put((byte) 0);  // filter
		ihdr.put((byte) 0);  // interlace
		writeChunk(out, "IHDR", ihdr.array());

		// acTL
		ByteBuffer actl = ByteBuffer.allocate(8);
		actl.putInt(subFrames.length);  // num_frames
		actl.putInt(0);                  // num_plays (0 = infinite)
		writeChunk(out, "acTL", actl.array());

		// Convert delay to num/den
		int delayNum = delayMs;
		int delayDen = 1000;

		int sequenceNumber = 0;

		for (int i = 0; i < subFrames.length; i++)
		{
			SubFrame sf = subFrames[i];

			// fcTL
			ByteBuffer fctl = ByteBuffer.allocate(26);
			fctl.putInt(sequenceNumber++);
			fctl.putInt(sf.width());
			fctl.putInt(sf.height());
			fctl.putInt(sf.xOffset());
			fctl.putInt(sf.yOffset());
			fctl.putShort((short) delayNum);
			fctl.putShort((short) delayDen);
			fctl.put(sf.disposeOp());
			fctl.put(sf.blendOp());
			writeChunk(out, "fcTL", fctl.array());

			// Generate frame image data by creating a PNG and extracting IDAT
			byte[] idatData = generateFrameIdatData(sf.width(), sf.height(), sf.color());

			if (i == 0)
			{
				// First frame uses IDAT
				writeChunk(out, "IDAT", idatData);
			}
			else
			{
				// Subsequent frames use fdAT (prepend sequence number)
				ByteBuffer fdat = ByteBuffer.allocate(4 + idatData.length);
				fdat.putInt(sequenceNumber++);
				fdat.put(idatData);
				writeChunk(out, "fdAT", fdat.array());
			}
		}

		// IEND
		writeChunk(out, "IEND", new byte[0]);

		try (FileOutputStream fos = new FileOutputStream(file))
		{
			fos.write(out.toByteArray());
		}
	}

	/**
	 * Creates a solid-color RGBA image, writes it as PNG, extracts the IDAT data.
	 */
	private static byte[] generateFrameIdatData(int w, int h, Color color) throws Exception
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, w, h);
		g.dispose();

		ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
		ImageIO.write(img, "png", pngOut);
		byte[] pngBytes = pngOut.toByteArray();

		// Parse the PNG to extract IDAT data
		ByteArrayOutputStream idatData = new ByteArrayOutputStream();
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(pngBytes));
		in.skipBytes(8); // PNG signature

		while (true)
		{
			int length;
			try
			{
				length = in.readInt();
			}
			catch (java.io.EOFException e)
			{
				break;
			}
			byte[] typeBytes = new byte[4];
			in.readFully(typeBytes);
			String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
			byte[] data = new byte[length];
			in.readFully(data);
			in.readInt(); // CRC

			if ("IDAT".equals(type))
			{
				idatData.write(data);
			}
			if ("IEND".equals(type)) break;
		}

		return idatData.toByteArray();
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException
	{
		byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);

		ByteBuffer lenBuf = ByteBuffer.allocate(4);
		lenBuf.putInt(data.length);
		out.write(lenBuf.array());

		out.write(typeBytes);
		out.write(data);

		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		ByteBuffer crcBuf = ByteBuffer.allocate(4);
		crcBuf.putInt((int) crc.getValue());
		out.write(crcBuf.array());
	}
}
