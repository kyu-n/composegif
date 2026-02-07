package com.composegif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PsdImporterTest
{

	// --- Tree structure ---

	@Test
	void parsesGroupsAndLayers(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			assertEquals(4, tree.canvasWidth());
			assertEquals(4, tree.canvasHeight());

			// Root should have 2 nodes: groupA and groupB (bottom-to-top order)
			List<PsdNode> roots = tree.roots();
			assertEquals(2, roots.size());

			// groupA (first in bottom-to-top order)
			PsdNode groupA = roots.get(0);
			assertTrue(groupA.isGroup);
			assertEquals("groupA", groupA.name);
			assertTrue(groupA.isVisible);
			assertEquals(255, groupA.opacity);

			// groupA children: red and blue (bottom-to-top)
			assertEquals(2, groupA.children.size());

			PsdNode red = groupA.children.get(0);
			assertEquals("red", red.name);
			assertFalse(red.isGroup);
			assertTrue(red.isVisible);
			assertEquals(255, red.opacity);
			assertEquals(0, red.top);
			assertEquals(0, red.left);
			assertEquals(2, red.bottom);
			assertEquals(2, red.right);

			PsdNode blue = groupA.children.get(1);
			assertEquals("blue", blue.name);
			assertFalse(blue.isGroup);
			assertFalse(blue.isVisible, "blue layer should be hidden (flags bit 1 set)");
			assertEquals(255, blue.opacity);
			assertEquals(2, blue.top);
			assertEquals(2, blue.left);
			assertEquals(4, blue.bottom);
			assertEquals(4, blue.right);

			// groupB (second in bottom-to-top order)
			PsdNode groupB = roots.get(1);
			assertTrue(groupB.isGroup);
			assertEquals("groupB", groupB.name);
			assertTrue(groupB.isVisible);
			assertEquals(128, groupB.opacity);

			// groupB children: green
			assertEquals(1, groupB.children.size());

			PsdNode green = groupB.children.get(0);
			assertEquals("green", green.name);
			assertFalse(green.isGroup);
			assertTrue(green.isVisible);
			assertEquals(255, green.opacity);
			assertEquals(0, green.top);
			assertEquals(0, green.left);
			assertEquals(4, green.bottom);
			assertEquals(4, green.right);
		}
	}

	@Test
	void blendModeIsParsed(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			PsdNode groupA = tree.roots().get(0);
			assertEquals("norm", groupA.blendMode);

			PsdNode red = groupA.children.get(0);
			assertEquals("norm", red.blendMode);
		}
	}

	@Test
	void imageIndicesMatchLayerOrder(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			// Divider at index 0, red at index 1, blue at index 2,
			// groupA at index 3, divider at index 4, green at index 5, groupB at index 6
			PsdNode red = tree.roots().get(0).children.get(0);
			assertEquals(1, red.imageIndex);

			PsdNode blue = tree.roots().get(0).children.get(1);
			assertEquals(2, blue.imageIndex);

			PsdNode green = tree.roots().get(1).children.get(0);
			assertEquals(5, green.imageIndex);
		}
	}

	@Test
	void emptyPsdReturnsEmptyRoots(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("empty.psd").toFile();
		writeEmptyPsd(psd, 4, 4);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			assertEquals(4, tree.canvasWidth());
			assertEquals(4, tree.canvasHeight());
			assertTrue(tree.roots().isEmpty());
		}
	}

	@Test
	void parseTreeHandlesNestedGroups(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("nested.psd").toFile();
		writeNestedTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			// Root should have 1 node: outer group
			List<PsdNode> roots = tree.roots();
			assertEquals(1, roots.size());

			PsdNode outer = roots.get(0);
			assertTrue(outer.isGroup);
			assertEquals("outer", outer.name);
			assertEquals(2, outer.children.size());

			// First child: inner group
			PsdNode inner = outer.children.get(0);
			assertTrue(inner.isGroup);
			assertEquals("inner", inner.name);
			assertEquals(1, inner.children.size());

			// Inner's child: the pixel layer
			PsdNode pixel = inner.children.get(0);
			assertFalse(pixel.isGroup);
			assertEquals("pixel", pixel.name);

			// Second child of outer: a loose layer
			PsdNode loose = outer.children.get(1);
			assertFalse(loose.isGroup);
			assertEquals("loose", loose.name);
		}
	}

	@Test
	void flattenNonNormalBlendModeWarns(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("blendmode.psd").toFile();
		writeBlendModeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			// Layer "multiply" has blend mode "mul " — should produce a warning
			PsdNode layer = tree.roots().get(0);
			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, layer);

			assertEquals(1, result.warnings().size());
			assertTrue(result.warnings().get(0).contains("mul "),
				"warning should mention blend mode: " + result.warnings().get(0));
			assertTrue(result.warnings().get(0).contains("multiply"),
				"warning should mention layer name: " + result.warnings().get(0));
		}
	}

	@Test
	void flattenGroupWithNonNormalBlendModeWarns(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("groupblend.psd").toFile();
		writeGroupBlendModeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			PsdNode group = tree.roots().get(0);
			assertTrue(group.isGroup);
			assertEquals("mulGroup", group.name);

			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, group);

			// The group itself has "mul " blend mode — should produce a warning
			assertTrue(result.warnings().size() >= 1,
				"expected at least 1 warning, got: " + result.warnings());
			assertTrue(result.warnings().stream().anyMatch(w -> w.contains("mulGroup") && w.contains("mul ")),
				"warning should mention group name and blend mode: " + result.warnings());
		}
	}

	// --- Compositing ---

	@Test
	void flattenLooseLayer(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			PsdNode red = tree.roots().get(0).children.get(0); // "red" at bounds 0,0,2,2
			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, red);

			BufferedImage img = result.image();
			assertEquals(4, img.getWidth());
			assertEquals(4, img.getHeight());

			// Top-left 2x2 should be red (opaque)
			int pixel = img.getRGB(0, 0);
			assertEquals(0xFF, (pixel >> 16) & 0xFF, "red channel at (0,0)");
			assertEquals(0x00, (pixel >> 8) & 0xFF, "green channel at (0,0)");
			assertEquals(0x00, pixel & 0xFF, "blue channel at (0,0)");
			assertEquals(0xFF, (pixel >> 24) & 0xFF, "alpha at (0,0)");

			// Bottom-right should be transparent
			int corner = img.getRGB(3, 3);
			assertEquals(0, (corner >> 24) & 0xFF, "alpha at (3,3) should be transparent");
		}
	}

	@Test
	void flattenGroupSkipsHidden(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			PsdNode groupA = tree.roots().get(0); // groupA: red (visible) + blue (hidden)
			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, groupA);

			BufferedImage img = result.image();

			// Red layer (0,0)-(2,2) should be present
			int topLeft = img.getRGB(0, 0);
			assertEquals(0xFF, (topLeft >> 16) & 0xFF, "red should appear at (0,0)");
			assertTrue(((topLeft >> 24) & 0xFF) > 0, "top-left should be opaque");

			// Blue layer (2,2)-(4,4) should NOT appear (hidden)
			int bottomRight = img.getRGB(3, 3);
			assertEquals(0, (bottomRight >> 24) & 0xFF, "blue should not appear (hidden)");
		}
	}

	@Test
	void flattenGroupAppliesOpacity(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			PsdNode groupB = tree.roots().get(1); // groupB: opacity=128, contains green
			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, groupB);

			BufferedImage img = result.image();

			// Green layer covers full 4x4, but group opacity is 128
			int pixel = img.getRGB(2, 2);
			int alpha = (pixel >> 24) & 0xFF;
			// Alpha should be approximately 128 (128/255 * 255 = 128)
			assertTrue(alpha > 100 && alpha < 156,
				"alpha should be ~128 from group opacity, got: " + alpha);

			int green = (pixel >> 8) & 0xFF;
			assertTrue(green > 200, "green channel should be high, got: " + green);
		}
	}

	@Test
	void flattenProducesCanvasSizeImage(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			// Flatten a 2x2 layer — output should still be 4x4 (canvas size)
			PsdNode red = tree.roots().get(0).children.get(0);
			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, red);
			assertEquals(4, result.image().getWidth());
			assertEquals(4, result.image().getHeight());

			// Flatten a group — also canvas size
			PsdNode groupA = tree.roots().get(0);
			PsdImporter.FlattenedFrame groupResult = PsdImporter.flattenNode(tree, groupA);
			assertEquals(4, groupResult.image().getWidth());
			assertEquals(4, groupResult.image().getHeight());
		}
	}

	@Test
	void flattenQuantizesViaExistingPipeline(@TempDir Path tempDir) throws Exception
	{
		File psd = tempDir.resolve("test.psd").toFile();
		writeTestPsd(psd);

		try (PsdImporter.PsdTree tree = PsdImporter.parseTree(psd))
		{
			PsdNode groupA = tree.roots().get(0);
			PsdImporter.FlattenedFrame result = PsdImporter.flattenNode(tree, groupA);

			// Run through existing quantization pipeline
			FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(result.image());
			assertInstanceOf(IndexColorModel.class, qr.image().getColorModel());
			assertEquals(4, qr.image().getWidth());
			assertEquals(4, qr.image().getHeight());
		}
	}

	// --- PSD binary writer helpers ---

	/**
	 * Writes a test PSD with this layer structure (bottom-to-top in PSD metadata order):
	 * <pre>
	 * Layer 0: Section divider for groupA (lsct type=3)
	 * Layer 1: "red" pixel layer (visible, opacity=255, bounds 0,0,2,2)
	 * Layer 2: "blue" pixel layer (HIDDEN flags=2, opacity=255, bounds 2,2,4,4)
	 * Layer 3: Group "groupA" open (lsct type=1, visible, opacity=255)
	 * Layer 4: Section divider for groupB (lsct type=3)
	 * Layer 5: "green" pixel layer (visible, opacity=255, bounds 0,0,4,4)
	 * Layer 6: Group "groupB" open (lsct type=1, visible, opacity=128)
	 * </pre>
	 *
	 * Canvas is 4x4 RGB.
	 */
	private static void writeTestPsd(File file) throws IOException
	{
		int canvasW = 4;
		int canvasH = 4;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// === PSD Header (26 bytes) ===
		dos.writeBytes("8BPS");             // signature
		dos.writeShort(1);                  // version
		dos.write(new byte[6]);             // reserved
		dos.writeShort(3);                  // channels (RGB)
		dos.writeInt(canvasH);              // height
		dos.writeInt(canvasW);              // width
		dos.writeShort(8);                  // bits per channel
		dos.writeShort(3);                  // color mode: RGB

		// === Color Mode Data ===
		dos.writeInt(0);

		// === Image Resources ===
		dos.writeInt(0);

		// === Layer and Mask Information ===
		// Build layer info into a sub-buffer so we can measure its length
		byte[] layerAndMaskData = buildLayerAndMaskSection(canvasW, canvasH);
		dos.writeInt(layerAndMaskData.length);
		dos.write(layerAndMaskData);

		// === Composite Image Data ===
		// Compression type 0 = raw, followed by planar RGB data
		dos.writeShort(0); // raw compression
		int pixelCount = canvasW * canvasH;
		for (int ch = 0; ch < 3; ch++)
		{
			for (int p = 0; p < pixelCount; p++)
			{
				dos.writeByte(0xFF); // white composite
			}
		}

		dos.flush();

		try (FileOutputStream fos = new FileOutputStream(file))
		{
			fos.write(baos.toByteArray());
		}
	}

	private static byte[] buildLayerAndMaskSection(int canvasW, int canvasH) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Build layer info sub-section into another buffer
		byte[] layerInfoData = buildLayerInfoSection(canvasW, canvasH);

		// Layer info sub-section length
		dos.writeInt(layerInfoData.length);
		dos.write(layerInfoData);

		// Global layer mask info length
		dos.writeInt(0);

		dos.flush();
		return baos.toByteArray();
	}

	private static byte[] buildLayerInfoSection(int canvasW, int canvasH) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Layer count (positive = normal, no merged alpha)
		dos.writeShort(7);

		// Define all 7 layers: dividerA, red, blue, groupA, dividerB, green, groupB
		// Each layer record consists of bounds + channels + blend mode data + extra data

		// Layer 0: Section divider for groupA (lsct type=3, flags=24, 0x0 bounds)
		writeLayerRecord(dos, "", 0, 0, 0, 0, 0, (byte) 24, 255, 3);

		// Layer 1: "red" pixel layer (visible flags=0, opacity=255, bounds top=0,left=0,bottom=2,right=2)
		writeLayerRecord(dos, "red", 0, 0, 2, 2, 0, (byte) 0, 255, 0);

		// Layer 2: "blue" pixel layer (HIDDEN flags=2, opacity=255, bounds top=2,left=2,bottom=4,right=4)
		writeLayerRecord(dos, "blue", 2, 2, 4, 4, 0, (byte) 2, 255, 0);

		// Layer 3: Group "groupA" open (lsct type=1, visible flags=0, opacity=255, 0x0 bounds)
		writeLayerRecord(dos, "groupA", 0, 0, 0, 0, 0, (byte) 0, 255, 1);

		// Layer 4: Section divider for groupB (lsct type=3, flags=24, 0x0 bounds)
		writeLayerRecord(dos, "", 0, 0, 0, 0, 0, (byte) 24, 255, 3);

		// Layer 5: "green" pixel layer (visible flags=0, opacity=255, bounds top=0,left=0,bottom=4,right=4)
		writeLayerRecord(dos, "green", 0, 0, 4, 4, 0, (byte) 0, 255, 0);

		// Layer 6: Group "groupB" open (lsct type=1, visible flags=0, opacity=128, 0x0 bounds)
		writeLayerRecord(dos, "groupB", 0, 0, 0, 0, 0, (byte) 0, 128, 1);

		// Channel image data for each layer
		// Layer 0: divider (0x0) - 4 channels, each just compression type (2 bytes)
		writeEmptyChannelData(dos, 4);

		// Layer 1: "red" 2x2 - 4 channels (R,G,B,A) each 2+4 bytes
		writePixelChannelData(dos, 2, 2, new byte[]{(byte) 0xFF, 0x00, 0x00, (byte) 0xFF}); // RGBA

		// Layer 2: "blue" 2x2 - 4 channels
		writePixelChannelData(dos, 2, 2, new byte[]{0x00, 0x00, (byte) 0xFF, (byte) 0xFF}); // RGBA

		// Layer 3: groupA (0x0) - 4 channels
		writeEmptyChannelData(dos, 4);

		// Layer 4: divider (0x0) - 4 channels
		writeEmptyChannelData(dos, 4);

		// Layer 5: "green" 4x4 - 4 channels
		writePixelChannelData(dos, 4, 4, new byte[]{0x00, (byte) 0xFF, 0x00, (byte) 0xFF}); // RGBA

		// Layer 6: groupB (0x0) - 4 channels
		writeEmptyChannelData(dos, 4);

		dos.flush();
		return baos.toByteArray();
	}

	/**
	 * Writes one layer record (bounds, channel info, blend mode, extra data).
	 *
	 * @param name       layer name
	 * @param top        top bound
	 * @param left       left bound
	 * @param bottom     bottom bound
	 * @param right      right bound
	 * @param clipping   clipping (0=base)
	 * @param flags      flags byte (bit 1 = hidden in Photoshop)
	 * @param opacity    opacity 0-255
	 * @param lsctType   lsct section divider type: 0=normal, 1=open group, 2=closed group, 3=divider
	 */
	private static void writeLayerRecord(DataOutputStream dos, String name,
										 int top, int left, int bottom, int right,
										 int clipping, byte flags, int opacity,
										 int lsctType) throws IOException
	{
		int layerW = right - left;
		int layerH = bottom - top;
		int pixelCount = layerW * layerH;

		// Bounds
		dos.writeInt(top);
		dos.writeInt(left);
		dos.writeInt(bottom);
		dos.writeInt(right);

		// Number of channels
		int numChannels = 4; // R, G, B, A
		dos.writeShort(numChannels);

		// Channel info: id (2 bytes) + data length (4 bytes) per channel
		// Channel data length = 2 (compression type) + pixelCount (raw pixel bytes)
		int channelDataLen = 2 + pixelCount;
		short[] channelIds = {0, 1, 2, -1}; // R=0, G=1, B=2, A=-1
		for (short chId : channelIds)
		{
			dos.writeShort(chId);
			dos.writeInt(channelDataLen);
		}

		// Blend mode signature
		dos.writeBytes("8BIM");

		// Blend mode key: "norm"
		dos.writeBytes("norm");

		// Opacity
		dos.writeByte(opacity);

		// Clipping
		dos.writeByte(clipping);

		// Flags
		dos.writeByte(flags);

		// Filler
		dos.writeByte(0);

		// Extra data
		byte[] extraData = buildExtraData(name, lsctType);
		dos.writeInt(extraData.length);
		dos.write(extraData);
	}

	private static byte[] buildExtraData(String name, int lsctType) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Layer mask data length
		dos.writeInt(0);

		// Layer blending ranges length
		dos.writeInt(0);

		// Pascal string name (padded to multiple of 4 bytes)
		writePascalString(dos, name);

		// lsct additional layer information block
		dos.writeBytes("8BIM");     // signature
		dos.writeBytes("lsct");     // key
		dos.writeInt(4);            // data length
		dos.writeInt(lsctType);     // section divider type

		dos.flush();
		return baos.toByteArray();
	}

	/**
	 * Writes a Pascal string (length byte + chars) padded to 4-byte boundary.
	 */
	private static void writePascalString(DataOutputStream dos, String s) throws IOException
	{
		byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
		dos.writeByte(bytes.length);
		dos.write(bytes);
		// Pad to 4-byte boundary (length byte + string bytes)
		int written = 1 + bytes.length;
		int padding = (4 - (written % 4)) % 4;
		for (int i = 0; i < padding; i++)
		{
			dos.writeByte(0);
		}
	}

	/**
	 * Writes channel image data for an empty (0x0) layer: just compression type per channel.
	 */
	private static void writeEmptyChannelData(DataOutputStream dos, int numChannels) throws IOException
	{
		for (int ch = 0; ch < numChannels; ch++)
		{
			dos.writeShort(0); // compression = raw
		}
	}

	/**
	 * Writes channel image data for a pixel layer with uniform color.
	 * Each channel gets compression type 0 (raw) followed by w*h bytes.
	 *
	 * @param rgba array of 4 bytes: R, G, B, A values for all pixels
	 */
	private static void writePixelChannelData(DataOutputStream dos, int w, int h, byte[] rgba) throws IOException
	{
		int pixelCount = w * h;
		// Channel order matches channelIds in writeLayerRecord: R(0), G(1), B(2), A(-1)
		for (int ch = 0; ch < 4; ch++)
		{
			dos.writeShort(0); // compression = raw
			for (int p = 0; p < pixelCount; p++)
			{
				dos.writeByte(rgba[ch]);
			}
		}
	}

	/**
	 * Writes a PSD with nested groups:
	 * <pre>
	 * Layer 0: Section divider for outer (lsct type=3)
	 * Layer 1: Section divider for inner (lsct type=3)
	 * Layer 2: "pixel" layer (visible, 2x2 at 0,0)
	 * Layer 3: Group "inner" open (lsct type=1)
	 * Layer 4: "loose" layer (visible, 2x2 at 2,2)
	 * Layer 5: Group "outer" open (lsct type=1)
	 * </pre>
	 */
	private static void writeNestedTestPsd(File file) throws IOException
	{
		int canvasW = 4;
		int canvasH = 4;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Header
		dos.writeBytes("8BPS");
		dos.writeShort(1);
		dos.write(new byte[6]);
		dos.writeShort(3);
		dos.writeInt(canvasH);
		dos.writeInt(canvasW);
		dos.writeShort(8);
		dos.writeShort(3);

		// Color mode data
		dos.writeInt(0);
		// Image resources
		dos.writeInt(0);

		// Layer and mask info
		byte[] layerAndMask = buildNestedLayerAndMaskSection(canvasW, canvasH);
		dos.writeInt(layerAndMask.length);
		dos.write(layerAndMask);

		// Composite image data
		dos.writeShort(0);
		int pixelCount = canvasW * canvasH;
		for (int ch = 0; ch < 3; ch++)
			for (int p = 0; p < pixelCount; p++)
				dos.writeByte(0xFF);

		dos.flush();
		try (FileOutputStream fos = new FileOutputStream(file))
		{
			fos.write(baos.toByteArray());
		}
	}

	private static byte[] buildNestedLayerAndMaskSection(int canvasW, int canvasH) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		byte[] layerInfo = buildNestedLayerInfoSection(canvasW, canvasH);
		dos.writeInt(layerInfo.length);
		dos.write(layerInfo);
		dos.writeInt(0); // global layer mask info
		dos.flush();
		return baos.toByteArray();
	}

	private static byte[] buildNestedLayerInfoSection(int canvasW, int canvasH) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		dos.writeShort(6); // 6 layers

		// Layer 0: divider for outer (lsct type=3)
		writeLayerRecord(dos, "", 0, 0, 0, 0, 0, (byte) 24, 255, 3);
		// Layer 1: divider for inner (lsct type=3)
		writeLayerRecord(dos, "", 0, 0, 0, 0, 0, (byte) 24, 255, 3);
		// Layer 2: "pixel" (visible, 2x2 at 0,0)
		writeLayerRecord(dos, "pixel", 0, 0, 2, 2, 0, (byte) 0, 255, 0);
		// Layer 3: group "inner" (lsct type=1)
		writeLayerRecord(dos, "inner", 0, 0, 0, 0, 0, (byte) 0, 255, 1);
		// Layer 4: "loose" (visible, 2x2 at 2,2)
		writeLayerRecord(dos, "loose", 2, 2, 4, 4, 0, (byte) 0, 255, 0);
		// Layer 5: group "outer" (lsct type=1)
		writeLayerRecord(dos, "outer", 0, 0, 0, 0, 0, (byte) 0, 255, 1);

		// Channel data
		writeEmptyChannelData(dos, 4); // divider outer
		writeEmptyChannelData(dos, 4); // divider inner
		writePixelChannelData(dos, 2, 2, new byte[]{(byte) 0xFF, 0x00, 0x00, (byte) 0xFF}); // pixel
		writeEmptyChannelData(dos, 4); // group inner
		writePixelChannelData(dos, 2, 2, new byte[]{0x00, (byte) 0xFF, 0x00, (byte) 0xFF}); // loose
		writeEmptyChannelData(dos, 4); // group outer

		dos.flush();
		return baos.toByteArray();
	}

	/**
	 * Writes a PSD with a single layer that has a non-normal blend mode.
	 * <pre>
	 * Layer 0: "multiply" layer (visible, blend mode "mul ", 2x2 at 0,0)
	 * </pre>
	 */
	private static void writeBlendModeTestPsd(File file) throws IOException
	{
		int canvasW = 4;
		int canvasH = 4;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Header
		dos.writeBytes("8BPS");
		dos.writeShort(1);
		dos.write(new byte[6]);
		dos.writeShort(3);
		dos.writeInt(canvasH);
		dos.writeInt(canvasW);
		dos.writeShort(8);
		dos.writeShort(3);

		// Color mode data
		dos.writeInt(0);
		// Image resources
		dos.writeInt(0);

		// Layer and mask info
		byte[] layerAndMask = buildBlendModeLayerAndMaskSection();
		dos.writeInt(layerAndMask.length);
		dos.write(layerAndMask);

		// Composite image data
		dos.writeShort(0);
		int pixelCount = canvasW * canvasH;
		for (int ch = 0; ch < 3; ch++)
			for (int p = 0; p < pixelCount; p++)
				dos.writeByte(0xFF);

		dos.flush();
		try (FileOutputStream fos = new FileOutputStream(file))
		{
			fos.write(baos.toByteArray());
		}
	}

	private static byte[] buildBlendModeLayerAndMaskSection() throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		byte[] layerInfo = buildBlendModeLayerInfoSection();
		dos.writeInt(layerInfo.length);
		dos.write(layerInfo);
		dos.writeInt(0); // global layer mask info
		dos.flush();
		return baos.toByteArray();
	}

	private static byte[] buildBlendModeLayerInfoSection() throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		dos.writeShort(1); // 1 layer

		// "multiply" layer with blend mode "mul "
		writeLayerRecordWithBlendMode(dos, "multiply", 0, 0, 2, 2, 0, (byte) 0, 255, 0, "mul ");

		// Channel data
		writePixelChannelData(dos, 2, 2, new byte[]{(byte) 0xFF, 0x00, 0x00, (byte) 0xFF});

		dos.flush();
		return baos.toByteArray();
	}

	/**
	 * Writes a PSD with a group that has a non-normal blend mode.
	 * <pre>
	 * Layer 0: Section divider for mulGroup (lsct type=3)
	 * Layer 1: "child" layer (visible, 2x2 at 0,0)
	 * Layer 2: Group "mulGroup" open (lsct type=1, blend mode "mul ")
	 * </pre>
	 */
	private static void writeGroupBlendModeTestPsd(File file) throws IOException
	{
		int canvasW = 4;
		int canvasH = 4;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Header
		dos.writeBytes("8BPS");
		dos.writeShort(1);
		dos.write(new byte[6]);
		dos.writeShort(3);
		dos.writeInt(canvasH);
		dos.writeInt(canvasW);
		dos.writeShort(8);
		dos.writeShort(3);

		// Color mode data
		dos.writeInt(0);
		// Image resources
		dos.writeInt(0);

		// Layer and mask info
		ByteArrayOutputStream lmBaos = new ByteArrayOutputStream();
		DataOutputStream lmDos = new DataOutputStream(lmBaos);
		ByteArrayOutputStream liBaos = new ByteArrayOutputStream();
		DataOutputStream liDos = new DataOutputStream(liBaos);

		liDos.writeShort(3); // 3 layers

		// Layer 0: divider (lsct type=3)
		writeLayerRecord(liDos, "", 0, 0, 0, 0, 0, (byte) 24, 255, 3);
		// Layer 1: "child" (visible, 2x2 at 0,0)
		writeLayerRecord(liDos, "child", 0, 0, 2, 2, 0, (byte) 0, 255, 0);
		// Layer 2: group "mulGroup" (lsct type=1, blend mode "mul ")
		writeLayerRecordWithBlendMode(liDos, "mulGroup", 0, 0, 0, 0, 0, (byte) 0, 255, 1, "mul ");

		// Channel data
		writeEmptyChannelData(liDos, 4); // divider
		writePixelChannelData(liDos, 2, 2, new byte[]{(byte) 0xFF, 0x00, 0x00, (byte) 0xFF}); // child
		writeEmptyChannelData(liDos, 4); // group

		liDos.flush();
		byte[] layerInfo = liBaos.toByteArray();

		lmDos.writeInt(layerInfo.length);
		lmDos.write(layerInfo);
		lmDos.writeInt(0); // global layer mask info
		lmDos.flush();
		byte[] layerAndMask = lmBaos.toByteArray();

		dos.writeInt(layerAndMask.length);
		dos.write(layerAndMask);

		// Composite image data
		dos.writeShort(0);
		int pixelCount = canvasW * canvasH;
		for (int ch = 0; ch < 3; ch++)
			for (int p = 0; p < pixelCount; p++)
				dos.writeByte(0xFF);

		dos.flush();
		try (FileOutputStream fos = new FileOutputStream(file))
		{
			fos.write(baos.toByteArray());
		}
	}

	/**
	 * Like writeLayerRecord but with a custom blend mode key.
	 */
	private static void writeLayerRecordWithBlendMode(DataOutputStream dos, String name,
													  int top, int left, int bottom, int right,
													  int clipping, byte flags, int opacity,
													  int lsctType, String blendModeKey) throws IOException
	{
		int layerW = right - left;
		int layerH = bottom - top;
		int pixelCount = layerW * layerH;

		dos.writeInt(top);
		dos.writeInt(left);
		dos.writeInt(bottom);
		dos.writeInt(right);

		int numChannels = 4;
		dos.writeShort(numChannels);

		int channelDataLen = 2 + pixelCount;
		short[] channelIds = {0, 1, 2, -1};
		for (short chId : channelIds)
		{
			dos.writeShort(chId);
			dos.writeInt(channelDataLen);
		}

		dos.writeBytes("8BIM");
		dos.writeBytes(blendModeKey); // custom blend mode
		dos.writeByte(opacity);
		dos.writeByte(clipping);
		dos.writeByte(flags);
		dos.writeByte(0);

		byte[] extraData = buildExtraData(name, lsctType);
		dos.writeInt(extraData.length);
		dos.write(extraData);
	}

	/**
	 * Writes a PSD with no layers (just a composite).
	 */
	private static void writeEmptyPsd(File file, int w, int h) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		// Header
		dos.writeBytes("8BPS");
		dos.writeShort(1);
		dos.write(new byte[6]);
		dos.writeShort(3); // RGB
		dos.writeInt(h);
		dos.writeInt(w);
		dos.writeShort(8);
		dos.writeShort(3); // RGB mode

		// Color mode data
		dos.writeInt(0);

		// Image resources
		dos.writeInt(0);

		// Layer and mask info (empty - just length 0)
		dos.writeInt(0);

		// Composite image data: raw compression + RGB planar data
		dos.writeShort(0);
		int pixelCount = w * h;
		for (int ch = 0; ch < 3; ch++)
		{
			for (int p = 0; p < pixelCount; p++)
			{
				dos.writeByte(0xFF);
			}
		}

		dos.flush();

		try (FileOutputStream fos = new FileOutputStream(file))
		{
			fos.write(baos.toByteArray());
		}
	}
}
