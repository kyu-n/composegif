package com.composegif;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Compositor
{

	public record Layer(List<FrameLoader.FrameData> frames, int width, int height, int delayMs,
					   Set<Integer> transparentColors, int offsetX, int offsetY)
	{
		public Layer(List<FrameLoader.FrameData> frames, int width, int height, int delayMs)
		{
			this(frames, width, height, delayMs, Set.of(), 0, 0);
		}

		public Layer(List<FrameLoader.FrameData> frames, int width, int height, int delayMs,
					 Set<Integer> transparentColors)
		{
			this(frames, width, height, delayMs, transparentColors, 0, 0);
		}

		public Layer withOffset(int offsetX, int offsetY)
		{
			return new Layer(frames, width, height, delayMs, transparentColors, offsetX, offsetY);
		}
	}

	public record FlattenResult(List<FrameLoader.FrameData> frames, int width, int height, int delayMs) {}

	static long gcd(long a, long b)
	{
		a = Math.abs(a);
		b = Math.abs(b);
		while (b != 0)
		{
			long t = b;
			b = a % b;
			a = t;
		}
		return a;
	}

	static long lcm(long a, long b)
	{
		if (a == 0 || b == 0) return 0;
		return Math.abs(a / gcd(a, b) * b);
	}

	public static FlattenResult flatten(List<Layer> layers) throws IOException
	{
		if (layers == null || layers.isEmpty())
		{
			throw new IOException("No layers loaded");
		}

		// Single layer with no offset — passthrough optimization
		if (layers.size() == 1 && layers.get(0).offsetX() == 0 && layers.get(0).offsetY() == 0)
		{
			return singleLayerPassthrough(layers.get(0));
		}

		// Multiple layers or offset — composite
		return compositeMultipleLayers(layers);
	}

	private static FlattenResult singleLayerPassthrough(Layer layer)
	{
		if (layer.transparentColors().isEmpty())
		{
			return new FlattenResult(
					layer.frames(),
					layer.width(),
					layer.height(),
					layer.delayMs()
			);
		}
		// Must render with transparency applied
		return applyTransparentColors(layer);
	}

	static FlattenResult generateTransparentFrames(Layer ref)
	{
		int w = ref.width();
		int h = ref.height();
		BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		// canvas is already fully transparent (all zeros)
		FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(canvas);
		FrameLoader.FrameData transparentFrame = new FrameLoader.FrameData(qr.image(), qr.transparentIndex());
		return new FlattenResult(List.of(transparentFrame), w, h, ref.delayMs());
	}

	private static FlattenResult applyTransparentColors(Layer layer)
	{
		int w = layer.width();
		int h = layer.height();
		Set<Integer> transparentColors = layer.transparentColors();
		List<FrameLoader.FrameData> outputFrames = new ArrayList<>();

		for (FrameLoader.FrameData frame : layer.frames())
		{
			BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			BufferedImage src = frame.image();
			for (int y = 0; y < h; y++)
			{
				for (int x = 0; x < w; x++)
				{
					int argb = src.getRGB(x, y);
					int opaqueRgb = argb | 0xFF000000;
					if (transparentColors.contains(opaqueRgb))
					{
						canvas.setRGB(x, y, 0x00000000);
					}
					else
					{
						canvas.setRGB(x, y, argb);
					}
				}
			}
			FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(canvas);
			outputFrames.add(new FrameLoader.FrameData(qr.image(), qr.transparentIndex()));
		}

		return new FlattenResult(outputFrames, w, h, layer.delayMs());
	}

	private static FlattenResult compositeMultipleLayers(List<Layer> layers) throws IOException
	{
		// Canvas = largest layer dimensions; offset layers get clipped at edges
		int w = 0, h = 0;
		for (Layer layer : layers)
		{
			w = Math.max(w, layer.width());
			h = Math.max(h, layer.height());
		}

		// Compute tick delay as GCD across all layer delays
		long tickDelay = layers.get(0).delayMs();
		for (int i = 1; i < layers.size(); i++)
		{
			tickDelay = gcd(tickDelay, layers.get(i).delayMs());
		}
		int tickDelayInt = (int) tickDelay;

		// Compute steps and total ticks via LCM
		int[] steps = new int[layers.size()];
		int[] frameCounts = new int[layers.size()];
		long totalTicks = 1;
		for (int i = 0; i < layers.size(); i++)
		{
			steps[i] = layers.get(i).delayMs() / tickDelayInt;
			frameCounts[i] = layers.get(i).frames().size();
			totalTicks = lcm(totalTicks, (long) frameCounts[i] * steps[i]);
		}

		if (totalTicks > 100_000)
		{
			throw new IOException(
					"Animation cycle too long (" + totalTicks + " frames). "
					+ "Reduce the number of frames or adjust delays so they share a common factor.");
		}

		// Precompute fast-path flags
		boolean[] fastPath = new boolean[layers.size()];
		for (int i = 0; i < layers.size(); i++)
		{
			fastPath[i] = layers.get(i).transparentColors().isEmpty();
		}

		List<FrameLoader.FrameData> outputFrames = new ArrayList<>();
		for (long t = 0; t < totalTicks; t++)
		{
			BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = canvas.createGraphics();
			try
			{
				// Draw layers bottom to top
				for (int i = 0; i < layers.size(); i++)
				{
					int frameIdx = (int) ((t / steps[i]) % frameCounts[i]);
					Layer layer = layers.get(i);
					if (fastPath[i])
					{
						g2d.drawImage(layer.frames().get(frameIdx).image(),
								layer.offsetX(), layer.offsetY(), null);
					}
					else
					{
						drawLayerFrame(canvas, layer.frames().get(frameIdx).image(),
								layer.transparentColors(), layer.offsetX(), layer.offsetY());
					}
				}
			}
			finally
			{
				g2d.dispose();
			}

			FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(canvas);
			outputFrames.add(new FrameLoader.FrameData(qr.image(), qr.transparentIndex()));
		}

		return new FlattenResult(outputFrames, w, h, tickDelayInt);
	}

	private static void drawLayerFrame(BufferedImage canvas, BufferedImage src,
									   Set<Integer> transparentColors, int offsetX, int offsetY)
	{
		int canvasW = canvas.getWidth();
		int canvasH = canvas.getHeight();
		int srcW = src.getWidth();
		int srcH = src.getHeight();

		int startX = Math.max(0, -offsetX);
		int startY = Math.max(0, -offsetY);
		int endX = Math.min(srcW, canvasW - offsetX);
		int endY = Math.min(srcH, canvasH - offsetY);

		for (int sy = startY; sy < endY; sy++)
		{
			for (int sx = startX; sx < endX; sx++)
			{
				int argb = src.getRGB(sx, sy);
				int alpha = (argb >> 24) & 0xFF;
				if (alpha == 0) continue;
				int opaqueRgb = argb | 0xFF000000;
				if (transparentColors.contains(opaqueRgb)) continue;
				canvas.setRGB(offsetX + sx, offsetY + sy, argb);
			}
		}
	}
}