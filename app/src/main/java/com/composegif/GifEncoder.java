package com.composegif;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class GifEncoder
{

	public interface ProgressListener
	{
		void onProgress(int current, int total);
	}

	public static void encode(
			List<FrameLoader.FrameData> frames,
			File output,
			int scale,
			Object interpolationHint,
			int delayMs,
			ProgressListener listener
	) throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IOException("No frames to encode");
		}

		int delayCentiseconds = Math.round(delayMs / 10.0f);
		if (delayCentiseconds < 2) delayCentiseconds = 2;

		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(output))
		{
			writer.setOutput(ios);

			int scaledWidth = frames.get(0).image().getWidth() * scale;
			int scaledHeight = frames.get(0).image().getHeight() * scale;

			IIOMetadata streamMeta = writer.getDefaultStreamMetadata(null);
			IndexColorModel sharedPalette = findSharedPalette(frames);
			configureStreamMeta(streamMeta, scaledWidth, scaledHeight, sharedPalette);

			writer.prepareWriteSequence(streamMeta);

			boolean isNearestNeighbor = RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR.equals(interpolationHint);
			int total = frames.size();

			for (int i = 0; i < total; i++)
			{
				FrameLoader.FrameData frameData = frames.get(i);
				BufferedImage scaledFrame = scaleFrame(frameData, scale, interpolationHint, isNearestNeighbor);

				int transparentIndex = frameData.transparentIndex();
				if (scaledFrame.getColorModel() instanceof IndexColorModel icm)
				{
					transparentIndex = icm.getTransparentPixel();
				}

				IIOMetadata imageMeta = writer.getDefaultImageMetadata(
						new ImageTypeSpecifier(scaledFrame), null);
				configureImageMeta(imageMeta, delayCentiseconds, transparentIndex, i == 0);

				writer.writeToSequence(new IIOImage(scaledFrame, null, imageMeta), null);

				if (listener != null)
				{
					listener.onProgress(i + 1, total);
				}
			}

			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
	}

	private static BufferedImage scaleFrame(
			FrameLoader.FrameData frameData,
			int scale,
			Object interpolationHint,
			boolean isNearestNeighbor
	)
	{
		BufferedImage src = frameData.image();
		if (scale == 1) return src;

		int srcW = src.getWidth();
		int srcH = src.getHeight();
		int dstW = srcW * scale;
		int dstH = srcH * scale;

		if (isNearestNeighbor && src.getType() == BufferedImage.TYPE_BYTE_INDEXED)
		{
			return scaleIndexedNearest(src, scale, dstW, dstH);
		}

		// Bilinear/Bicubic path: scale in ARGB, then remap to indexed
		BufferedImage argb = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = argb.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolationHint);
		g.drawImage(src, 0, 0, dstW, dstH, null);
		g.dispose();

		if (src.getColorModel() instanceof IndexColorModel icm)
		{
			return remapToIndexed(argb, icm, frameData.transparentIndex());
		}

		// Shouldn't happen (FrameLoader always outputs indexed), but handle gracefully
		FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(argb);
		return qr.image();
	}

	private static BufferedImage scaleIndexedNearest(BufferedImage src, int scale, int dstW, int dstH)
	{
		IndexColorModel icm = (IndexColorModel) src.getColorModel();
		BufferedImage dst = new BufferedImage(dstW, dstH, BufferedImage.TYPE_BYTE_INDEXED, icm);
		WritableRaster srcRaster = src.getRaster();
		WritableRaster dstRaster = dst.getRaster();

		for (int y = 0; y < dstH; y++)
		{
			int srcY = y / scale;
			for (int x = 0; x < dstW; x++)
			{
				dstRaster.setSample(x, y, 0, srcRaster.getSample(x / scale, srcY, 0));
			}
		}
		return dst;
	}

	private static BufferedImage remapToIndexed(BufferedImage argb, IndexColorModel icm, int transparentIndex)
	{
		int w = argb.getWidth();
		int h = argb.getHeight();
		BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
		WritableRaster raster = indexed.getRaster();

		int mapSize = icm.getMapSize();
		int[] paletteRgb = new int[mapSize];
		for (int i = 0; i < mapSize; i++)
		{
			paletteRgb[i] = icm.getRGB(i);
		}

		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int pixel = argb.getRGB(x, y);
				int alpha = (pixel >> 24) & 0xFF;
				if (alpha < 128 && transparentIndex >= 0)
				{
					raster.setSample(x, y, 0, transparentIndex);
				}
				else
				{
					raster.setSample(x, y, 0, findNearest(pixel, paletteRgb, transparentIndex));
				}
			}
		}
		return indexed;
	}

	private static int findNearest(int targetArgb, int[] paletteRgb, int transparentIndex)
	{
		int tr = (targetArgb >> 16) & 0xFF;
		int tg = (targetArgb >> 8) & 0xFF;
		int tb = targetArgb & 0xFF;

		int bestIdx = 0;
		int bestDist = Integer.MAX_VALUE;
		for (int i = 0; i < paletteRgb.length; i++)
		{
			if (i == transparentIndex) continue;
			int pr = (paletteRgb[i] >> 16) & 0xFF;
			int pg = (paletteRgb[i] >> 8) & 0xFF;
			int pb = paletteRgb[i] & 0xFF;
			int dist = (tr - pr) * (tr - pr) + (tg - pg) * (tg - pg) + (tb - pb) * (tb - pb);
			if (dist < bestDist)
			{
				bestDist = dist;
				bestIdx = i;
			}
		}
		return bestIdx;
	}

	static IndexColorModel findSharedPalette(List<FrameLoader.FrameData> frames)
	{
		IndexColorModel first = null;
		for (FrameLoader.FrameData frame : frames)
		{
			if (!(frame.image().getColorModel() instanceof IndexColorModel icm))
			{
				return null;
			}
			if (first == null)
			{
				first = icm;
			}
			else
			{
				if (icm.getMapSize() != first.getMapSize()) return null;
				int size = icm.getMapSize();
				for (int i = 0; i < size; i++)
				{
					if (icm.getRGB(i) != first.getRGB(i)) return null;
				}
			}
		}
		return first;
	}

	private static void configureStreamMeta(IIOMetadata streamMeta, int width, int height,
											 IndexColorModel globalPalette) throws IOException
	{
		String formatName = streamMeta.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(formatName);

		IIOMetadataNode lsd = getOrCreateChild(root, "LogicalScreenDescriptor");
		lsd.setAttribute("logicalScreenWidth", String.valueOf(width));
		lsd.setAttribute("logicalScreenHeight", String.valueOf(height));
		lsd.setAttribute("colorResolution", "8");
		lsd.setAttribute("pixelAspectRatio", "0");

		if (globalPalette != null)
		{
			IIOMetadataNode gct = getOrCreateChild(root, "GlobalColorTable");
			int mapSize = globalPalette.getMapSize();
			// GIF requires power-of-2 color table size
			int gifSize = 2;
			while (gifSize < mapSize) gifSize *= 2;
			gct.setAttribute("sizeOfGlobalColorTable", String.valueOf(gifSize));
			gct.setAttribute("backgroundColorIndex", "0");
			gct.setAttribute("sortFlag", "FALSE");

			while (gct.hasChildNodes())
			{
				gct.removeChild(gct.getFirstChild());
			}

			for (int i = 0; i < gifSize; i++)
			{
				IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
				entry.setAttribute("index", String.valueOf(i));
				if (i < mapSize)
				{
					int rgb = globalPalette.getRGB(i);
					entry.setAttribute("red", String.valueOf((rgb >> 16) & 0xFF));
					entry.setAttribute("green", String.valueOf((rgb >> 8) & 0xFF));
					entry.setAttribute("blue", String.valueOf(rgb & 0xFF));
				}
				else
				{
					entry.setAttribute("red", "0");
					entry.setAttribute("green", "0");
					entry.setAttribute("blue", "0");
				}
				gct.appendChild(entry);
			}
		}

		streamMeta.setFromTree(formatName, root);
	}

	private static void configureImageMeta(
			IIOMetadata imageMeta,
			int delayCentiseconds,
			int transparentIndex,
			boolean firstFrame
	) throws IOException
	{
		String formatName = imageMeta.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) imageMeta.getAsTree(formatName);

		IIOMetadataNode gce = getOrCreateChild(root, "GraphicControlExtension");
		gce.setAttribute("disposalMethod", "restoreToBackgroundColor");
		gce.setAttribute("userInputFlag", "FALSE");
		gce.setAttribute("delayTime", String.valueOf(delayCentiseconds));

		if (transparentIndex >= 0)
		{
			gce.setAttribute("transparentColorFlag", "TRUE");
			gce.setAttribute("transparentColorIndex", String.valueOf(transparentIndex));
		}
		else
		{
			gce.setAttribute("transparentColorFlag", "FALSE");
			gce.setAttribute("transparentColorIndex", "0");
		}

		if (firstFrame)
		{
			IIOMetadataNode appExtensions = getOrCreateChild(root, "ApplicationExtensions");
			IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
			netscape.setAttribute("applicationID", "NETSCAPE");
			netscape.setAttribute("authenticationCode", "2.0");
			netscape.setUserObject(new byte[]{1, 0, 0}); // loop count 0 = infinite
			appExtensions.appendChild(netscape);
		}

		imageMeta.setFromTree(formatName, root);
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