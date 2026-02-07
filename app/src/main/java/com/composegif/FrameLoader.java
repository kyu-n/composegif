package com.composegif;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImageInfo;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FrameLoader
{

	private static final Pattern FRAME_PATTERN = Pattern.compile("^(\\d+)\\.png$", Pattern.CASE_INSENSITIVE);

	public record FrameData(BufferedImage image, int transparentIndex) {}

	public record LoadResult(List<FrameData> frames, int width, int height, List<String> warnings, int extractedDelayMs)
	{
		public LoadResult(List<FrameData> frames, int width, int height, List<String> warnings)
		{
			this(frames, width, height, warnings, -1);
		}
	}

	private static final Pattern NATURAL_SORT_SPLIT = Pattern.compile("(\\d+|\\D+)");

	static final Comparator<File> NATURAL_ORDER = (a, b) -> {
		String nameA = a.getName();
		String nameB = b.getName();
		List<String> partsA = splitNatural(nameA);
		List<String> partsB = splitNatural(nameB);
		int len = Math.min(partsA.size(), partsB.size());
		for (int i = 0; i < len; i++)
		{
			String pa = partsA.get(i);
			String pb = partsB.get(i);
			boolean aDigit = !pa.isEmpty() && Character.isDigit(pa.charAt(0));
			boolean bDigit = !pb.isEmpty() && Character.isDigit(pb.charAt(0));
			int cmp;
			if (aDigit && bDigit)
			{
				cmp = Long.compare(Long.parseLong(pa), Long.parseLong(pb));
				if (cmp == 0) cmp = pa.compareTo(pb); // shorter zero-padding first
			}
			else
			{
				cmp = pa.compareToIgnoreCase(pb);
			}
			if (cmp != 0) return cmp;
		}
		return Integer.compare(partsA.size(), partsB.size());
	};

	private static List<String> splitNatural(String s)
	{
		List<String> parts = new ArrayList<>();
		Matcher m = NATURAL_SORT_SPLIT.matcher(s);
		while (m.find())
		{
			parts.add(m.group());
		}
		return parts;
	}

	static boolean isFrameFilename(String name)
	{
		Matcher m = FRAME_PATTERN.matcher(name);
		if (!m.matches()) return false;
		try
		{
			return Integer.parseInt(m.group(1)) > 0;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	static int extractFrameNumber(String name)
	{
		Matcher m = FRAME_PATTERN.matcher(name);
		if (!m.matches()) throw new IllegalArgumentException("Not a frame file: " + name);
		return Integer.parseInt(m.group(1));
	}

	public static LoadResult load(File directory) throws IOException
	{
		if (!directory.isDirectory())
		{
			throw new IOException("Not a directory: " + directory);
		}

		File[] files = directory.listFiles();
		if (files == null)
		{
			throw new IOException("Cannot list directory: " + directory);
		}

		List<File> frameFiles = new ArrayList<>();
		for (File f : files)
		{
			if (f.isFile() && isFrameFilename(f.getName()))
			{
				frameFiles.add(f);
			}
		}

		if (frameFiles.isEmpty())
		{
			throw new IOException("No frame files found matching pattern <N>.png in: " + directory);
		}

		return load(frameFiles);
	}

	public static LoadResult load(List<File> files) throws IOException
	{
		if (files.isEmpty())
		{
			throw new IOException("No files provided");
		}

		List<File> sorted = new ArrayList<>(files);
		sorted.sort(NATURAL_ORDER);

		List<FrameData> frames = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int expectedWidth = -1;
		int expectedHeight = -1;
		List<String> dimensionErrors = new ArrayList<>();
		int extractedDelayMs = -1;

		for (int i = 0; i < sorted.size(); i++)
		{
			File file = sorted.get(i);

			if (file.getName().toLowerCase().endsWith(".gif"))
			{
				// Extract all frames from this GIF
				LoadResult gifResult = loadGif(file);

				// Use timing from the first GIF only
				if (extractedDelayMs < 0)
				{
					extractedDelayMs = gifResult.extractedDelayMs();
				}

				// Check dimensions once for the whole GIF
				if (expectedWidth < 0)
				{
					expectedWidth = gifResult.width();
					expectedHeight = gifResult.height();
				}
				else if (gifResult.width() != expectedWidth || gifResult.height() != expectedHeight)
				{
					dimensionErrors.add(String.format(
							"%s: %dx%d (expected %dx%d)", file.getName(),
							gifResult.width(), gifResult.height(), expectedWidth, expectedHeight));
				}

				frames.addAll(gifResult.frames());
				warnings.addAll(gifResult.warnings());
				continue;
			}

			int displayIndex = frames.size() + 1;

			BufferedImage image = ImageIO.read(file);
			if (image == null)
			{
				throw new IOException("Failed to load frame " + displayIndex + " (" + file.getName() + "): ImageIO returned null");
			}

			// Use Commons Imaging for metadata detection
			ImageInfo imageInfo = null;
			try
			{
				imageInfo = Imaging.getImageInfo(file);
			}
			catch (IOException e)
			{
				// Fall back gracefully — metadata enrichment will be skipped
			}

			if (expectedWidth < 0)
			{
				expectedWidth = image.getWidth();
				expectedHeight = image.getHeight();
			}
			else if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight)
			{
				dimensionErrors.add(String.format(
						"Frame %d (%s): %dx%d (expected %dx%d)",
						displayIndex, file.getName(),
						image.getWidth(), image.getHeight(),
						expectedWidth, expectedHeight
				));
			}

			int transparentIndex = -1;
			if (image.getColorModel() instanceof IndexColorModel icm)
			{
				transparentIndex = icm.getTransparentPixel();
				image = ensureByteIndexed(image, icm);
			}
			else
			{
				String detail = "";
				if (imageInfo != null)
				{
					boolean hasTransparency = imageInfo.isTransparent();
					detail = " (" + imageInfo.getBitsPerPixel() + " bpp"
							+ (hasTransparency ? ", transparent" : "") + ")";
				}
				warnings.add("Frame " + displayIndex + " (" + file.getName()
						+ "): truecolor" + detail
						+ " — quantizing to 256 colors for GIF compatibility");
				QuantizeResult qr = quantizeToIndexed(image);
				image = qr.image;
				transparentIndex = qr.transparentIndex;
			}

			frames.add(new FrameData(image, transparentIndex));
		}

		if (!dimensionErrors.isEmpty())
		{
			StringBuilder sb = new StringBuilder("Inconsistent frame dimensions:\n");
			sb.append("First frame: ").append(expectedWidth).append("x").append(expectedHeight).append("\n");
			for (String err : dimensionErrors)
			{
				sb.append(err).append("\n");
			}
			throw new IOException(sb.toString());
		}

		return new LoadResult(frames, expectedWidth, expectedHeight, warnings, extractedDelayMs);
	}

	private static LoadResult loadGif(File file) throws IOException
	{
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		ImageInputStream iis = ImageIO.createImageInputStream(file);
		if (iis == null)
		{
			throw new IOException("Cannot open image stream: " + file.getName());
		}
		try
		{
			reader.setInput(iis, false);
			int numFrames = reader.getNumImages(true);
			if (numFrames == 0)
			{
				throw new IOException("GIF contains no frames: " + file.getName());
			}
			List<FrameData> frames = new ArrayList<>();
			List<String> warnings = new ArrayList<>();
			Map<Integer, Integer> delayCounts = new LinkedHashMap<>();

			// Get logical screen size from stream metadata
			IIOMetadata streamMeta = reader.getStreamMetadata();
			int gifW = -1, gifH = -1;
			if (streamMeta != null)
			{
				IIOMetadataNode streamRoot = (IIOMetadataNode) streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
				IIOMetadataNode lsd = findChild(streamRoot, "LogicalScreenDescriptor");
				if (lsd != null)
				{
					gifW = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
					gifH = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
				}
			}
			// Fallback: use first frame dimensions
			if (gifW <= 0 || gifH <= 0)
			{
				BufferedImage first = reader.read(0);
				gifW = first.getWidth();
				gifH = first.getHeight();
			}
			BufferedImage canvas = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);

			for (int i = 0; i < numFrames; i++)
			{
				BufferedImage raw = reader.read(i);

				// Get frame metadata for position and delay
				IIOMetadata meta = reader.getImageMetadata(i);
				IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");

				int frameX = 0, frameY = 0;
				IIOMetadataNode desc = findChild(root, "ImageDescriptor");
				if (desc != null)
				{
					frameX = Integer.parseInt(desc.getAttribute("imageLeftPosition"));
					frameY = Integer.parseInt(desc.getAttribute("imageTopPosition"));
				}

				// Extract delay
				int delayCenti = 10; // default 100ms
				IIOMetadataNode gce = findChild(root, "GraphicControlExtension");
				if (gce != null)
				{
					String delayStr = gce.getAttribute("delayTime");
					if (delayStr != null && !delayStr.isEmpty())
					{
						delayCenti = Integer.parseInt(delayStr);
						if (delayCenti <= 0) delayCenti = 10;
					}
				}
				int delayMs = delayCenti * 10;
				delayCounts.merge(delayMs, 1, Integer::sum);

				// Determine disposal method
				String disposal = "none";
				if (gce != null)
				{
					String d = gce.getAttribute("disposalMethod");
					if (d != null) disposal = d;
				}

				// Save canvas state for restoreToPrevious
				BufferedImage previousCanvas = null;
				if ("restoreToPrevious".equals(disposal))
				{
					previousCanvas = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);
					Graphics2D pg = previousCanvas.createGraphics();
					pg.drawImage(canvas, 0, 0, null);
					pg.dispose();
				}

				// Composite frame onto canvas
				Graphics2D g2 = canvas.createGraphics();
				g2.drawImage(raw, frameX, frameY, null);
				g2.dispose();

				// Snapshot the composited canvas for this frame
				BufferedImage snapshot = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);
				Graphics2D sg = snapshot.createGraphics();
				sg.drawImage(canvas, 0, 0, null);
				sg.dispose();

				// Quantize to indexed
				QuantizeResult qr = quantizeToIndexed(snapshot);
				frames.add(new FrameData(qr.image(), qr.transparentIndex()));

				// Handle disposal
				if ("restoreToBackgroundColor".equals(disposal))
				{
					Graphics2D cg = canvas.createGraphics();
					cg.setComposite(java.awt.AlphaComposite.Clear);
					cg.fillRect(frameX, frameY, raw.getWidth(), raw.getHeight());
					cg.dispose();
				}
				else if ("restoreToPrevious".equals(disposal) && previousCanvas != null)
				{
					Graphics2D cg = canvas.createGraphics();
					cg.setComposite(java.awt.AlphaComposite.Src);
					cg.drawImage(previousCanvas, 0, 0, null);
					cg.dispose();
				}
			}

			// Use the most common delay
			int extractedDelayMs = delayCounts.entrySet().stream()
					.max(Map.Entry.comparingByValue())
					.map(Map.Entry::getKey)
					.orElse(100);

			if (delayCounts.size() > 1)
			{
				warnings.add("GIF has inconsistent frame delays " + delayCounts.keySet()
						+ " — using most common: " + extractedDelayMs + "ms");
			}

			return new LoadResult(frames, gifW, gifH, warnings, extractedDelayMs);
		}
		finally
		{
			iis.close();
			reader.dispose();
		}
	}

	private static IIOMetadataNode findChild(IIOMetadataNode parent, String name)
	{
		for (int i = 0; i < parent.getLength(); i++)
		{
			if (parent.item(i) instanceof IIOMetadataNode node && name.equals(node.getNodeName()))
			{
				return node;
			}
		}
		return null;
	}

	private static BufferedImage ensureByteIndexed(BufferedImage src, IndexColorModel icm)
	{
		if (src.getType() == BufferedImage.TYPE_BYTE_INDEXED) return src;
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
		WritableRaster srcRaster = src.getRaster();
		WritableRaster dstRaster = dst.getRaster();
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				dstRaster.setSample(x, y, 0, srcRaster.getSample(x, y, 0));
			}
		}
		return dst;
	}

	record QuantizeResult(BufferedImage image, int transparentIndex) {}

	static QuantizeResult quantizeToIndexed(BufferedImage src)
	{
		int w = src.getWidth();
		int h = src.getHeight();

		Map<Integer, Integer> colorCounts = new LinkedHashMap<>();
		boolean hasTransparency = false;

		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int argb = src.getRGB(x, y);
				int alpha = (argb >> 24) & 0xFF;
				if (alpha < 128)
				{
					hasTransparency = true;
				}
				else
				{
					int rgb = argb | 0xFF000000;
					colorCounts.merge(rgb, 1, Integer::sum);
				}
			}
		}

		int maxPaletteColors = hasTransparency ? 255 : 256;

		List<Integer> palette;
		if (colorCounts.size() <= maxPaletteColors)
		{
			palette = new ArrayList<>(colorCounts.keySet());
		}
		else
		{
			palette = colorCounts.entrySet().stream()
					.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
					.limit(maxPaletteColors)
					.map(Map.Entry::getKey)
					.collect(Collectors.toCollection(ArrayList::new));
		}

		int transparentIndex = -1;
		if (hasTransparency)
		{
			transparentIndex = palette.size();
			palette.add(0x00000000);
		}

		int size = palette.size();
		if (size == 0)
		{
			size = 1;
			palette.add(0xFF000000);
		}

		byte[] r = new byte[size];
		byte[] g = new byte[size];
		byte[] b = new byte[size];
		for (int i = 0; i < size; i++)
		{
			int c = palette.get(i);
			r[i] = (byte) ((c >> 16) & 0xFF);
			g[i] = (byte) ((c >> 8) & 0xFF);
			b[i] = (byte) (c & 0xFF);
		}

		IndexColorModel icm;
		if (transparentIndex >= 0)
		{
			icm = new IndexColorModel(8, size, r, g, b, transparentIndex);
		}
		else
		{
			icm = new IndexColorModel(8, size, r, g, b);
		}

		BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
		WritableRaster raster = indexed.getRaster();

		Map<Integer, Integer> colorToIndex = new HashMap<>();
		for (int i = 0; i < palette.size(); i++)
		{
			colorToIndex.put(palette.get(i), i);
		}

		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int argb = src.getRGB(x, y);
				int alpha = (argb >> 24) & 0xFF;
				int index;
				if (alpha < 128 && transparentIndex >= 0)
				{
					index = transparentIndex;
				}
				else
				{
					int rgb = argb | 0xFF000000;
					Integer exact = colorToIndex.get(rgb);
					if (exact != null)
					{
						index = exact;
					}
					else
					{
						index = findNearestColor(rgb, palette, transparentIndex);
					}
				}
				raster.setSample(x, y, 0, index);
			}
		}

		return new QuantizeResult(indexed, transparentIndex);
	}

	private static int findNearestColor(int targetRgb, List<Integer> palette, int transparentIndex)
	{
		int tr = (targetRgb >> 16) & 0xFF;
		int tg = (targetRgb >> 8) & 0xFF;
		int tb = targetRgb & 0xFF;

		int bestIndex = 0;
		int bestDist = Integer.MAX_VALUE;

		for (int i = 0; i < palette.size(); i++)
		{
			if (i == transparentIndex) continue;
			int c = palette.get(i);
			int dr = tr - ((c >> 16) & 0xFF);
			int dg = tg - ((c >> 8) & 0xFF);
			int db = tb - (c & 0xFF);
			int dist = dr * dr + dg * dg + db * db;
			if (dist < bestDist)
			{
				bestDist = dist;
				bestIndex = i;
			}
		}
		return bestIndex;
	}
}