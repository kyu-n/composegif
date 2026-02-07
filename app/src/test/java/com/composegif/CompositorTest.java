package com.composegif;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompositorTest
{

	// --- GCD / LCM ---

	@Test
	void gcdBasic()
	{
		assertEquals(100, Compositor.gcd(200, 100));
		assertEquals(100, Compositor.gcd(100, 200));
		assertEquals(10, Compositor.gcd(70, 110));
		assertEquals(1, Compositor.gcd(7, 13));
	}

	@Test
	void lcmBasic()
	{
		assertEquals(15, Compositor.lcm(3, 5));
		assertEquals(7, Compositor.lcm(1, 7));
		assertEquals(5, Compositor.lcm(5, 5));
		assertEquals(12, Compositor.lcm(4, 6));
	}

	// --- Flatten: single layer passthrough ---

	@Test
	void singleBgLayerPassthrough() throws IOException
	{
		List<FrameLoader.FrameData> bgFrames = List.of(
				solidFrame(4, 4, Color.RED),
				solidFrame(4, 4, Color.GREEN)
		);
		var bg = new Compositor.Layer(bgFrames, 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg));

		assertEquals(2, result.frames().size());
		assertEquals(100, result.delayMs());
		// Passthrough — same frame objects, no re-quantization
		assertSame(bgFrames.get(0), result.frames().get(0));
		assertSame(bgFrames.get(1), result.frames().get(1));
	}

	@Test
	void singleFgLayerPassthrough() throws IOException
	{
		List<FrameLoader.FrameData> fgFrames = List.of(
				solidFrame(4, 4, Color.BLUE)
		);
		var fg = new Compositor.Layer(fgFrames, 4, 4, 80);

		Compositor.FlattenResult result = Compositor.flatten(List.of(fg));

		assertEquals(1, result.frames().size());
		assertEquals(80, result.delayMs());
		assertSame(fgFrames.get(0), result.frames().get(0));
	}

	// --- Flatten: both layers hidden ---

	@Test
	void bothHiddenProducesTransparentFrame()
	{
		var bg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);

		// All layers hidden — use generateTransparentFrames directly
		Compositor.FlattenResult result = Compositor.generateTransparentFrames(bg);
		assertEquals(1, result.frames().size());
		assertEquals(4, result.width());
		assertEquals(4, result.height());
		int pixel = result.frames().get(0).image().getRGB(0, 0);
		assertEquals(0, (pixel >> 24) & 0xFF, "Hidden layers should produce transparent output");
	}

	@Test
	void singleLayerHiddenProducesTransparentFrame()
	{
		var fg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 80);

		// Only fg loaded, but hidden — use generateTransparentFrames directly
		Compositor.FlattenResult result = Compositor.generateTransparentFrames(fg);
		assertEquals(1, result.frames().size());
		assertEquals(4, result.width());
		assertEquals(4, result.height());
		int pixel = result.frames().get(0).image().getRGB(0, 0);
		assertEquals(0, (pixel >> 24) & 0xFF, "Hidden single layer should produce transparent output");
	}

	// --- Flatten: two layers composited ---

	@Test
	void twoLayersCorrectFrameCount() throws IOException
	{
		// bg: 2 frames @ 200ms, fg: 3 frames @ 100ms
		// tickDelay = gcd(200,100) = 100
		// bgStep = 2, fgStep = 1
		// totalTicks = lcm(2*2, 3*1) = lcm(4, 3) = 12
		var bg = new Compositor.Layer(List.of(
				solidFrame(4, 4, Color.RED),
				solidFrame(4, 4, Color.GREEN)
		), 4, 4, 200);
		var fg = new Compositor.Layer(List.of(
				solidFrame(4, 4, Color.BLUE),
				solidFrame(4, 4, Color.YELLOW),
				solidFrame(4, 4, Color.CYAN)
		), 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(12, result.frames().size());
		assertEquals(100, result.delayMs());
		assertEquals(4, result.width());
		assertEquals(4, result.height());
	}

	@Test
	void twoLayersSameDelayAndCount() throws IOException
	{
		// bg: 3 frames @ 100ms, fg: 3 frames @ 100ms
		// tickDelay = 100, bgStep = 1, fgStep = 1
		// totalTicks = lcm(3, 3) = 3
		var bg = new Compositor.Layer(List.of(
				solidFrame(4, 4, Color.RED),
				solidFrame(4, 4, Color.GREEN),
				solidFrame(4, 4, Color.BLUE)
		), 4, 4, 100);
		var fg = new Compositor.Layer(List.of(
				solidFrame(4, 4, Color.YELLOW),
				solidFrame(4, 4, Color.CYAN),
				solidFrame(4, 4, Color.MAGENTA)
		), 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(3, result.frames().size());
		assertEquals(100, result.delayMs());
	}

	@Test
	void tickIndexingCorrect() throws IOException
	{
		// bg: 2 frames @ 100ms, fg: 1 frame @ 100ms
		// tickDelay = 100, bgStep = 1, fgStep = 1
		// totalTicks = lcm(2, 1) = 2
		// tick 0: bg[0] + fg[0]
		// tick 1: bg[1] + fg[0]
		// With opaque bg and transparent fg, output should show bg colors
		var bg = new Compositor.Layer(List.of(
				solidFrame(4, 4, Color.RED),
				solidFrame(4, 4, Color.BLUE)
		), 4, 4, 100);

		// fg: fully transparent frame
		BufferedImage transparentImg = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		// Leave all pixels at 0x00000000 (transparent)
		FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(transparentImg);
		var fg = new Compositor.Layer(List.of(
				new FrameLoader.FrameData(qr.image(), qr.transparentIndex())
		), 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(2, result.frames().size());
		// Frame 0 should be red-ish (bg[0] shows through transparent fg)
		assertColorClose(Color.RED, result.frames().get(0).image().getRGB(0, 0), "Frame 0");
		// Frame 1 should be blue-ish (bg[1] shows through transparent fg)
		assertColorClose(Color.BLUE, result.frames().get(1).image().getRGB(0, 0), "Frame 1");
	}

	// --- Different sizes with offsets ---

	@Test
	void differentSizeLayersProduceCorrectCanvas() throws IOException
	{
		// bg: 8x8 red, fg: 4x4 blue at offset (2,2)
		var bg = new Compositor.Layer(List.of(solidFrame(8, 8, Color.RED)), 8, 8, 100);
		var fg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 100)
				.withOffset(2, 2);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(8, result.width());
		assertEquals(8, result.height());
		assertColorClose(Color.RED, result.frames().get(0).image().getRGB(0, 0), "outside fg");
		assertColorClose(Color.BLUE, result.frames().get(0).image().getRGB(3, 3), "inside fg");
	}

	@Test
	void smallerLayerAtOriginLargerLayerExpands() throws IOException
	{
		var small = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var large = new Compositor.Layer(List.of(solidFrame(8, 8, Color.BLUE)), 8, 8, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(small, large));

		assertEquals(8, result.width());
		assertEquals(8, result.height());
	}

	@Test
	void layerOffsetClampedToCanvas() throws IOException
	{
		// 4x4 layer at offset (6,6) → canvas stays 4x4, content clipped
		var layer = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100)
				.withOffset(6, 6);

		Compositor.FlattenResult result = Compositor.flatten(List.of(layer));

		assertEquals(4, result.width());
		assertEquals(4, result.height());
	}

	@Test
	void negativeOffsetClipsLayer() throws IOException
	{
		// 4x4 red at (-2,-2) on 4x4 blue bg
		var bg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 100);
		var fg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100)
				.withOffset(-2, -2);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(4, result.width());
		assertEquals(4, result.height());
		assertColorClose(Color.RED, result.frames().get(0).image().getRGB(0, 0), "fg clipped visible");
		assertColorClose(Color.BLUE, result.frames().get(0).image().getRGB(2, 2), "bg only");
	}

	@Test
	void differentSizesNoLongerThrow() throws IOException
	{
		var bg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var fg = new Compositor.Layer(List.of(solidFrame(8, 8, Color.BLUE)), 8, 8, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));
		assertEquals(8, result.width());
		assertEquals(8, result.height());
	}

	// --- No layers throws ---

	@Test
	void noLayersThrows()
	{
		assertThrows(IOException.class, () -> Compositor.flatten(List.of()));
	}

	// --- Visibility toggles ---

	@Test
	void hiddenBgShowsOnlyFg() throws IOException
	{
		var bg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var fgFrames = List.of(solidFrame(4, 4, Color.BLUE));
		var fg = new Compositor.Layer(fgFrames, 4, 4, 80);

		Compositor.FlattenResult result = Compositor.flatten(List.of(fg));

		// Should passthrough fg only
		assertEquals(1, result.frames().size());
		assertEquals(80, result.delayMs());
		assertSame(fgFrames.get(0), result.frames().get(0));
	}

	@Test
	void hiddenFgShowsOnlyBg() throws IOException
	{
		var bgFrames = List.of(solidFrame(4, 4, Color.RED));
		var bg = new Compositor.Layer(bgFrames, 4, 4, 100);
		var fg = new Compositor.Layer(List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 80);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg));

		assertEquals(1, result.frames().size());
		assertEquals(100, result.delayMs());
		assertSame(bgFrames.get(0), result.frames().get(0));
	}

	// --- Transparent colors ---

	@Test
	void singleColorTransparent() throws IOException
	{
		// Red/Green palette, all pixels red (index 0)
		var frames = List.of(solidFrame(4, 4, Color.RED));
		var layer = new Compositor.Layer(frames, 4, 4, 100, Set.of(Color.RED.getRGB() | 0xFF000000));

		Compositor.FlattenResult result = Compositor.flatten(List.of(layer));

		// Red pixels should now be transparent (alpha 0)
		int pixel = result.frames().get(0).image().getRGB(0, 0);
		assertEquals(0, (pixel >> 24) & 0xFF, "Red pixel should be transparent");
	}

	@Test
	void multipleColorsTransparent() throws IOException
	{
		// 3-color palette: red, green, blue. Pixels: red at (0,0), green at (1,0), blue at (2,0)
		byte[] r = {(byte) 255, 0, 0};
		byte[] g = {0, (byte) 255, 0};
		byte[] b = {0, 0, (byte) 255};
		IndexColorModel icm = new IndexColorModel(8, 3, r, g, b);
		BufferedImage img = new BufferedImage(3, 1, BufferedImage.TYPE_BYTE_INDEXED, icm);
		img.getRaster().setSample(0, 0, 0, 0); // red
		img.getRaster().setSample(1, 0, 0, 1); // green
		img.getRaster().setSample(2, 0, 0, 2); // blue

		var frames = List.of(new FrameLoader.FrameData(img, -1));
		// Mark red and blue as transparent
		var transparentColors = Set.of(0xFFFF0000, 0xFF0000FF);
		var layer = new Compositor.Layer(frames, 3, 1, 100, transparentColors);

		Compositor.FlattenResult result = Compositor.flatten(List.of(layer));

		int px0 = result.frames().get(0).image().getRGB(0, 0);
		int px1 = result.frames().get(0).image().getRGB(1, 0);
		int px2 = result.frames().get(0).image().getRGB(2, 0);
		assertEquals(0, (px0 >> 24) & 0xFF, "Red should be transparent");
		assertTrue(((px1 >> 24) & 0xFF) > 0, "Green should be opaque");
		assertEquals(0, (px2 >> 24) & 0xFF, "Blue should be transparent");
	}

	@Test
	void emptyTransparentSetNoChange() throws IOException
	{
		var frames = List.of(solidFrame(4, 4, Color.RED));
		var layer = new Compositor.Layer(frames, 4, 4, 100, Set.of());

		Compositor.FlattenResult result = Compositor.flatten(List.of(layer));

		// Passthrough — same object
		assertSame(frames.get(0), result.frames().get(0));
	}

	// --- Transparent colors in two-layer compositing ---

	@Test
	void twoLayersBgTransparentShowsFg() throws IOException
	{
		// Background: solid red, with red marked as transparent
		// Foreground: solid blue, no transparent colors
		// Result: blue should show through because bg red pixels are treated as transparent
		var bg = new Compositor.Layer(
				List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100,
				Set.of(0xFFFF0000));
		var fg = new Compositor.Layer(
				List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(1, result.frames().size());
		assertColorClose(Color.BLUE, result.frames().get(0).image().getRGB(0, 0), "bg-transparent pixel");
	}

	@Test
	void twoLayersFgTransparentShowsBg() throws IOException
	{
		// Background: solid red, no transparent colors
		// Foreground: solid green, with green marked as transparent
		// Result: red should show through because fg green pixels are treated as transparent
		var bg = new Compositor.Layer(
				List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var fg = new Compositor.Layer(
				List.of(solidFrame(4, 4, Color.GREEN)), 4, 4, 100,
				Set.of(0xFF00FF00));

		Compositor.FlattenResult result = Compositor.flatten(List.of(bg, fg));

		assertEquals(1, result.frames().size());
		assertColorClose(Color.RED, result.frames().get(0).image().getRGB(0, 0), "fg-transparent pixel");
	}

	// --- Multi-layer flatten(List<Layer>) ---

	@Test
	void flattenSingleLayerList() throws IOException
	{
		var frames = List.of(solidFrame(4, 4, Color.RED));
		var layer = new Compositor.Layer(frames, 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(layer));

		assertEquals(1, result.frames().size());
		assertEquals(100, result.delayMs());
		assertSame(frames.get(0), result.frames().get(0));
	}

	@Test
	void flattenThreeLayersBottomToTop() throws IOException
	{
		// Layer 0 (bottom): red, Layer 1: green transparent, Layer 2 (top): blue
		// All same delay and frame count for simplicity
		var bottom = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var middle = new Compositor.Layer(
				List.of(solidFrame(4, 4, Color.GREEN)), 4, 4, 100,
				Set.of(0xFF00FF00));  // green is transparent — should be skipped
		var top = new Compositor.Layer(List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(bottom, middle, top));

		assertEquals(1, result.frames().size());
		// Top layer (blue) is opaque, so it should be the final color
		assertColorClose(Color.BLUE, result.frames().get(0).image().getRGB(0, 0), "top layer wins");
	}

	@Test
	void flattenEmptyListThrows()
	{
		assertThrows(IOException.class, () -> Compositor.flatten(List.of()));
	}

	@Test
	void flattenThreeLayersDifferentDelays() throws IOException
	{
		// Layer 0: 1 frame @ 100ms, Layer 1: 1 frame @ 200ms, Layer 2: 1 frame @ 300ms
		// GCD(100,200,300) = 100, steps = [1,2,3], totalTicks = lcm(1,2,3) = 6
		var l0 = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var l1 = new Compositor.Layer(List.of(solidFrame(4, 4, Color.GREEN)), 4, 4, 200);
		var l2 = new Compositor.Layer(List.of(solidFrame(4, 4, Color.BLUE)), 4, 4, 300);

		Compositor.FlattenResult result = Compositor.flatten(List.of(l0, l1, l2));

		assertEquals(6, result.frames().size());
		assertEquals(100, result.delayMs());
	}

	@Test
	void flattenListDifferentSizes() throws IOException
	{
		var l0 = new Compositor.Layer(List.of(solidFrame(4, 4, Color.RED)), 4, 4, 100);
		var l1 = new Compositor.Layer(List.of(solidFrame(8, 8, Color.BLUE)), 8, 8, 100);

		Compositor.FlattenResult result = Compositor.flatten(List.of(l0, l1));
		assertEquals(8, result.width());
		assertEquals(8, result.height());
	}

	@Test
	void flattenTooManyTicksThrows()
	{
		// 97 * 89 * 83 = 716,639 ticks > 100,000 cap
		var l0 = new Compositor.Layer(nFrames(97, Color.RED), 4, 4, 100);
		var l1 = new Compositor.Layer(nFrames(89, Color.GREEN), 4, 4, 100);
		var l2 = new Compositor.Layer(nFrames(83, Color.BLUE), 4, 4, 100);

		IOException ex = assertThrows(IOException.class,
				() -> Compositor.flatten(List.of(l0, l1, l2)));
		assertTrue(ex.getMessage().contains("too long"));
	}

	// --- Helpers ---

	private static FrameLoader.FrameData solidFrame(int w, int h, Color color)
	{
		byte[] r = {(byte) color.getRed(), 0};
		byte[] g = {(byte) color.getGreen(), 0};
		byte[] b = {(byte) color.getBlue(), 0};
		IndexColorModel icm = new IndexColorModel(8, 2, r, g, b);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
		return new FrameLoader.FrameData(img, -1);
	}

	private static List<FrameLoader.FrameData> nFrames(int count, Color color)
	{
		FrameLoader.FrameData frame = solidFrame(4, 4, color);
		List<FrameLoader.FrameData> frames = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) frames.add(frame);
		return frames;
	}

	private static void assertColorClose(Color expected, int actualArgb, String label)
	{
		int ar = (actualArgb >> 16) & 0xFF;
		int ag = (actualArgb >> 8) & 0xFF;
		int ab = actualArgb & 0xFF;
		// Allow some tolerance due to quantization
		assertTrue(Math.abs(expected.getRed() - ar) < 30, label + " red: expected " + expected.getRed() + " got " + ar);
		assertTrue(Math.abs(expected.getGreen() - ag) < 30, label + " green: expected " + expected.getGreen() + " got " + ag);
		assertTrue(Math.abs(expected.getBlue() - ab) < 30, label + " blue: expected " + expected.getBlue() + " got " + ab);
	}
}