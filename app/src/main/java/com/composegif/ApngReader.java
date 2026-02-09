package com.composegif;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

public class ApngReader
{
	static final byte DISPOSE_OP_NONE = 0;
	static final byte DISPOSE_OP_BACKGROUND = 1;
	static final byte DISPOSE_OP_PREVIOUS = 2;

	static final byte BLEND_OP_SOURCE = 0;
	static final byte BLEND_OP_OVER = 1;

	private static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
	};

	private static final Set<String> ANCILLARY_CHUNK_TYPES = Set.of(
		"PLTE", "tRNS", "cHRM", "gAMA", "iCCP", "sRGB", "sBIT", "bKGD", "pHYs", "sPLT"
	);

	public record ApngFrame(BufferedImage image, int delayMs) {}

	public record ApngResult(List<ApngFrame> frames, int width, int height) {}

	record FrameControl(
		int sequenceNumber,
		int width, int height,
		int xOffset, int yOffset,
		int delayNum, int delayDen,
		byte disposeOp, byte blendOp
	) {}

	private record Chunk(String type, byte[] data) {}

	/**
	 * Quick check: does this file have a valid PNG signature and an acTL chunk before IDAT?
	 */
	public static boolean isApng(File file) throws IOException
	{
		try (DataInputStream in = new DataInputStream(new FileInputStream(file)))
		{
			byte[] sig = new byte[8];
			in.readFully(sig);
			if (!Arrays.equals(sig, PNG_SIGNATURE)) return false;

			while (true)
			{
				int length;
				try
				{
					length = in.readInt();
				}
				catch (java.io.EOFException e)
				{
					return false;
				}
				byte[] typeBytes = new byte[4];
				in.readFully(typeBytes);
				String type = new String(typeBytes, java.nio.charset.StandardCharsets.ISO_8859_1);

				if ("acTL".equals(type)) return true;
				if ("IDAT".equals(type)) return false;

				// Skip data + CRC (skipBytes may not skip the full amount)
				int toSkip = length + 4;
				while (toSkip > 0)
				{
					int skipped = in.skipBytes(toSkip);
					if (skipped <= 0)
					{
						return false;
					}
					toSkip -= skipped;
				}
			}
		}
	}

	/**
	 * Parse all APNG frames, composite them, and return ARGB images with timing.
	 */
	public static ApngResult loadApng(File file) throws IOException
	{
		List<Chunk> chunks = readAllChunks(file);

		// Extract IHDR
		byte[] ihdrData = null;
		List<byte[]> ancillaryChunks = new ArrayList<>();
		List<String> ancillaryTypes = new ArrayList<>();
		int numFrames = -1;

		// Collect chunks by category
		List<FrameControl> frameControls = new ArrayList<>();
		List<List<byte[]>> frameImageData = new ArrayList<>();
		List<byte[]> currentImageData = null;
		boolean firstIdatSeen = false;
		boolean defaultImageIsFirstFrame = false;

		for (Chunk chunk : chunks)
		{
			switch (chunk.type())
			{
				case "IHDR" ->
				{
					ihdrData = chunk.data();
				}
				case "acTL" ->
				{
					ByteBuffer buf = ByteBuffer.wrap(chunk.data());
					numFrames = buf.getInt();
				}
				case "fcTL" ->
				{
					FrameControl fc = parseFcTL(chunk.data());
					frameControls.add(fc);

					// If we see fcTL before any IDAT, the default image is the first frame
					if (!firstIdatSeen && frameControls.size() == 1)
					{
						defaultImageIsFirstFrame = true;
					}

					// Start collecting image data for this frame
					if (currentImageData != null)
					{
						frameImageData.add(currentImageData);
					}
					currentImageData = new ArrayList<>();
				}
				case "IDAT" ->
				{
					firstIdatSeen = true;
					if (defaultImageIsFirstFrame && currentImageData != null)
					{
						currentImageData.add(chunk.data());
					}
				}
				case "fdAT" ->
				{
					if (currentImageData != null)
					{
						// Strip 4-byte sequence number prefix
						byte[] stripped = new byte[chunk.data().length - 4];
						System.arraycopy(chunk.data(), 4, stripped, 0, stripped.length);
						currentImageData.add(stripped);
					}
				}
				default ->
				{
					if (!firstIdatSeen && ANCILLARY_CHUNK_TYPES.contains(chunk.type()))
					{
						ancillaryChunks.add(chunk.data());
						ancillaryTypes.add(chunk.type());
					}
				}
			}
		}

		// Don't forget the last frame's image data
		if (currentImageData != null)
		{
			frameImageData.add(currentImageData);
		}

		if (ihdrData == null)
		{
			throw new IOException("APNG missing IHDR chunk");
		}
		if (numFrames < 0)
		{
			throw new IOException("APNG missing acTL chunk");
		}
		if (frameControls.size() != numFrames)
		{
			throw new IOException("APNG frame count mismatch: acTL says " + numFrames
				+ " but found " + frameControls.size() + " fcTL chunks");
		}
		if (frameImageData.size() != numFrames)
		{
			throw new IOException("APNG frame data mismatch: expected " + numFrames
				+ " frames but found image data for " + frameImageData.size());
		}

		// Parse canvas dimensions from IHDR
		ByteBuffer ihdrBuf = ByteBuffer.wrap(ihdrData);
		int canvasWidth = ihdrBuf.getInt();
		int canvasHeight = ihdrBuf.getInt();

		// Composite frames
		BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
		List<ApngFrame> frames = new ArrayList<>();

		for (int i = 0; i < numFrames; i++)
		{
			FrameControl fc = frameControls.get(i);
			List<byte[]> imageData = frameImageData.get(i);

			// Reconstruct a valid PNG for this frame
			byte[] framePng = buildFramePng(ihdrData, fc.width(), fc.height(),
				ancillaryChunks, ancillaryTypes, imageData);
			BufferedImage frameImage = ImageIO.read(new ByteArrayInputStream(framePng));
			if (frameImage == null)
			{
				throw new IOException("Failed to decode APNG frame " + (i + 1));
			}

			// Save canvas for DISPOSE_OP_PREVIOUS
			BufferedImage previousCanvas = null;
			if (fc.disposeOp() == DISPOSE_OP_PREVIOUS)
			{
				previousCanvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
				Graphics2D pg = previousCanvas.createGraphics();
				pg.drawImage(canvas, 0, 0, null);
				pg.dispose();
			}

			// Draw frame onto canvas
			Graphics2D g2 = canvas.createGraphics();
			if (fc.blendOp() == BLEND_OP_SOURCE)
			{
				// Clear the frame region, then draw with Src composite
				g2.setClip(fc.xOffset(), fc.yOffset(), fc.width(), fc.height());
				g2.setComposite(AlphaComposite.Clear);
				g2.fillRect(fc.xOffset(), fc.yOffset(), fc.width(), fc.height());
				g2.setComposite(AlphaComposite.Src);
				g2.drawImage(frameImage, fc.xOffset(), fc.yOffset(), null);
			}
			else
			{
				// BLEND_OP_OVER uses default SrcOver
				g2.drawImage(frameImage, fc.xOffset(), fc.yOffset(), null);
			}
			g2.dispose();

			// Snapshot the composited canvas
			BufferedImage snapshot = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D sg = snapshot.createGraphics();
			sg.drawImage(canvas, 0, 0, null);
			sg.dispose();

			// Calculate delay
			int delayMs;
			if (fc.delayDen() == 0)
			{
				delayMs = fc.delayNum() * 1000 / 100;
			}
			else
			{
				delayMs = fc.delayNum() * 1000 / fc.delayDen();
			}
			if (delayMs <= 0) delayMs = 100;

			frames.add(new ApngFrame(snapshot, delayMs));

			// Handle disposal
			if (fc.disposeOp() == DISPOSE_OP_BACKGROUND)
			{
				Graphics2D cg = canvas.createGraphics();
				cg.setComposite(AlphaComposite.Clear);
				cg.fillRect(fc.xOffset(), fc.yOffset(), fc.width(), fc.height());
				cg.dispose();
			}
			else if (fc.disposeOp() == DISPOSE_OP_PREVIOUS && previousCanvas != null)
			{
				Graphics2D cg = canvas.createGraphics();
				cg.setComposite(AlphaComposite.Src);
				cg.drawImage(previousCanvas, 0, 0, null);
				cg.dispose();
			}
		}

		return new ApngResult(frames, canvasWidth, canvasHeight);
	}

	static FrameControl parseFcTL(byte[] data)
	{
		ByteBuffer buf = ByteBuffer.wrap(data);
		int sequenceNumber = buf.getInt();
		int width = buf.getInt();
		int height = buf.getInt();
		int xOffset = buf.getInt();
		int yOffset = buf.getInt();
		short delayNum = buf.getShort();
		short delayDen = buf.getShort();
		byte disposeOp = buf.get();
		byte blendOp = buf.get();
		return new FrameControl(
			sequenceNumber, width, height, xOffset, yOffset,
			Short.toUnsignedInt(delayNum), Short.toUnsignedInt(delayDen),
			disposeOp, blendOp
		);
	}

	private static List<Chunk> readAllChunks(File file) throws IOException
	{
		try (DataInputStream in = new DataInputStream(new FileInputStream(file)))
		{
			byte[] sig = new byte[8];
			in.readFully(sig);
			if (!Arrays.equals(sig, PNG_SIGNATURE))
			{
				throw new IOException("Not a PNG file: " + file.getName());
			}

			List<Chunk> chunks = new ArrayList<>();
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
				if (length < 0 || length > 100_000_000)
				{
					throw new IOException("Invalid chunk length: " + Integer.toUnsignedString(length));
				}
				byte[] typeBytes = new byte[4];
				in.readFully(typeBytes);
				String type = new String(typeBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
				byte[] data = new byte[length];
				in.readFully(data);
				in.readInt(); // CRC

				chunks.add(new Chunk(type, data));

				if ("IEND".equals(type)) break;
			}
			return chunks;
		}
	}

	private static byte[] buildFramePng(byte[] originalIhdr, int frameWidth, int frameHeight,
		List<byte[]> ancillaryData, List<String> ancillaryTypes, List<byte[]> imageData)
		throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PNG_SIGNATURE);

		// IHDR with frame dimensions patched in
		byte[] ihdr = originalIhdr.clone();
		ByteBuffer ihdrBuf = ByteBuffer.wrap(ihdr);
		ihdrBuf.putInt(0, frameWidth);
		ihdrBuf.putInt(4, frameHeight);
		writeChunk(out, "IHDR", ihdr);

		// Ancillary chunks
		for (int i = 0; i < ancillaryData.size(); i++)
		{
			String type = ancillaryTypes.get(i);
			writeChunk(out, type, ancillaryData.get(i));
		}

		// Image data as IDAT chunks
		for (byte[] data : imageData)
		{
			writeChunk(out, "IDAT", data);
		}

		// IEND
		writeChunk(out, "IEND", new byte[0]);

		return out.toByteArray();
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException
	{
		byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

		// Length
		ByteBuffer lenBuf = ByteBuffer.allocate(4);
		lenBuf.putInt(data.length);
		out.write(lenBuf.array());

		// Type
		out.write(typeBytes);

		// Data
		out.write(data);

		// CRC (over type + data)
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		ByteBuffer crcBuf = ByteBuffer.allocate(4);
		crcBuf.putInt((int) crc.getValue());
		out.write(crcBuf.array());
	}
}
