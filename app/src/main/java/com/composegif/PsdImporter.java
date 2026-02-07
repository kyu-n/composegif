package com.composegif;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

public class PsdImporter
{
	public record PsdTree(List<PsdNode> roots, int canvasWidth, int canvasHeight,
						  ImageReader reader, ImageInputStream stream) implements Closeable
	{
		@Override
		public void close() throws IOException
		{
			reader.dispose();
			stream.close();
		}
	}

	public static PsdTree parseTree(File psdFile) throws IOException
	{
		ImageInputStream stream = ImageIO.createImageInputStream(psdFile);
		if (stream == null) throw new IOException("Cannot open: " + psdFile.getName());

		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("psd");
		if (!readers.hasNext())
		{
			stream.close();
			throw new IOException("No PSD reader available");
		}
		ImageReader reader = readers.next();
		reader.setInput(stream, false);

		try
		{
			int canvasW = reader.getWidth(0);
			int canvasH = reader.getHeight(0);

			// Get metadata from image 0 (composite), which contains ALL layer info
			IIOMetadata meta = reader.getImageMetadata(0);
			String formatName = "com_twelvemonkeys_imageio_psd_image_1.0";
			IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(formatName);

			// Find the Layers element
			IIOMetadataNode layersNode = findChild(root, "Layers");
			if (layersNode == null)
			{
				return new PsdTree(List.of(), canvasW, canvasH, reader, stream);
			}

			// Parse LayerInfo nodes into flat list with metadata
			List<RawLayer> rawLayers = new ArrayList<>();
			int layerIndex = 0;
			for (int i = 0; i < layersNode.getLength(); i++)
			{
				if (layersNode.item(i) instanceof IIOMetadataNode node
					&& "LayerInfo".equals(node.getNodeName()))
				{
					rawLayers.add(parseRawLayer(node, layerIndex));
					layerIndex++;
				}
			}

			// Build tree from group/sectionDivider markers
			List<PsdNode> roots = buildTree(rawLayers);

			return new PsdTree(roots, canvasW, canvasH, reader, stream);
		}
		catch (Exception e)
		{
			reader.dispose();
			stream.close();
			if (e instanceof IOException) throw (IOException) e;
			throw new IOException(e);
		}
	}

	public record FlattenedFrame(BufferedImage image, List<String> warnings) {}

	public static FlattenedFrame flattenNode(PsdTree tree, PsdNode node) throws IOException
	{
		List<String> warnings = new ArrayList<>();
		BufferedImage canvas = new BufferedImage(
			tree.canvasWidth(), tree.canvasHeight(), BufferedImage.TYPE_INT_ARGB);

		if (node.isGroup)
		{
			// "pass" = Photoshop pass-through: composites children directly onto parent,
			// which is equivalent to normal compositing for our flatten-to-single-image use case
			if (!"norm".equals(node.blendMode) && !"pass".equals(node.blendMode))
			{
				warnings.add("Group \"" + node.name + "\" uses unsupported blend mode: "
					+ node.blendMode + " (treated as normal)");
			}

			compositeGroup(tree, node, canvas, warnings);

			// Apply group opacity by rendering to a new canvas
			if (node.opacity < 255)
			{
				BufferedImage result = new BufferedImage(
					tree.canvasWidth(), tree.canvasHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = result.createGraphics();
				g.setComposite(AlphaComposite.getInstance(
					AlphaComposite.SRC_OVER, node.opacity / 255f));
				g.drawImage(canvas, 0, 0, null);
				g.dispose();
				canvas = result;
			}
		}
		else
		{
			drawLayer(tree, node, canvas, warnings);
		}

		return new FlattenedFrame(canvas, warnings);
	}

	private static void compositeGroup(PsdTree tree, PsdNode group,
									   BufferedImage canvas, List<String> warnings) throws IOException
	{
		// Children are in bottom-to-top order (PSD metadata order), which is correct for compositing
		for (PsdNode child : group.children)
		{
			if (!child.isVisible) continue;

			if (child.isGroup)
			{
				// "pass" = pass-through (see comment in flattenNode)
				if (!"norm".equals(child.blendMode) && !"pass".equals(child.blendMode))
				{
					warnings.add("Group \"" + child.name + "\" uses unsupported blend mode: "
						+ child.blendMode + " (treated as normal)");
				}

				// Recurse: flatten sub-group to temp image, then draw with sub-group opacity
				BufferedImage subCanvas = new BufferedImage(
					tree.canvasWidth(), tree.canvasHeight(), BufferedImage.TYPE_INT_ARGB);
				compositeGroup(tree, child, subCanvas, warnings);

				Graphics2D g = canvas.createGraphics();
				if (child.opacity < 255)
				{
					g.setComposite(AlphaComposite.getInstance(
						AlphaComposite.SRC_OVER, child.opacity / 255f));
				}
				g.drawImage(subCanvas, 0, 0, null);
				g.dispose();
			}
			else
			{
				drawLayer(tree, child, canvas, warnings);
			}
		}
	}

	private static void drawLayer(PsdTree tree, PsdNode node,
								  BufferedImage canvas, List<String> warnings) throws IOException
	{
		if (!"norm".equals(node.blendMode))
		{
			warnings.add("Layer \"" + node.name + "\" uses unsupported blend mode: "
				+ node.blendMode + " (treated as normal)");
		}

		int readerIndex = node.imageIndex + 1; // index 0 is composite
		BufferedImage layerImg = tree.reader().read(readerIndex);
		if (layerImg == null) return;

		Graphics2D g = canvas.createGraphics();
		if (node.opacity < 255)
		{
			g.setComposite(AlphaComposite.getInstance(
				AlphaComposite.SRC_OVER, node.opacity / 255f));
		}
		g.drawImage(layerImg, node.left, node.top, null);
		g.dispose();
	}

	private static List<PsdNode> buildTree(List<RawLayer> rawLayers)
	{
		// Stack-based parsing: divider (type 3) pushes a new group context,
		// group marker (type 1/2) pops it and creates the group node
		ArrayDeque<List<PsdNode>> stack = new ArrayDeque<>();
		stack.push(new ArrayList<>()); // root level

		for (RawLayer raw : rawLayers)
		{
			if (raw.isSectionDivider)
			{
				// Start collecting children for a new group
				stack.push(new ArrayList<>());
			}
			else if (raw.isGroup)
			{
				if (stack.size() < 2)
				{
					// Mismatched group/divider — treat as regular layer
					stack.peek().add(new PsdNode(
						raw.name, raw.index, false, raw.isVisible,
						raw.opacity, raw.blendMode,
						raw.top, raw.left, raw.bottom, raw.right));
					continue;
				}
				// Pop children collected since the matching divider
				List<PsdNode> children = stack.pop();
				PsdNode group = new PsdNode(
					raw.name, raw.index, true, raw.isVisible,
					raw.opacity, raw.blendMode,
					raw.top, raw.left, raw.bottom, raw.right);
				group.children.addAll(children);
				group.children = List.copyOf(group.children);
				stack.peek().add(group);
			}
			else
			{
				// Regular layer
				PsdNode layer = new PsdNode(
					raw.name, raw.index, false, raw.isVisible,
					raw.opacity, raw.blendMode,
					raw.top, raw.left, raw.bottom, raw.right);
				stack.peek().add(layer);
			}
		}

		// Merge any unclosed groups into root level (malformed PSD)
		while (stack.size() > 1)
		{
			List<PsdNode> orphans = stack.pop();
			stack.peek().addAll(orphans);
		}
		return List.copyOf(stack.pop()); // root level (unmodifiable)
	}

	private record RawLayer(String name, int index, boolean isGroup, boolean isSectionDivider,
							boolean isVisible, int opacity, String blendMode,
							int top, int left, int bottom, int right) {}

	private static RawLayer parseRawLayer(IIOMetadataNode node, int index)
	{
		String name = getAttr(node, "name", "");
		boolean isGroup = "true".equals(getAttr(node, "group", ""));
		boolean isSectionDivider = "true".equals(getAttr(node, "sectionDivider", ""));
		int flags = Integer.parseInt(getAttr(node, "flags", "0"));
		boolean isVisible = (flags & 2) == 0;  // bit 1 set = HIDDEN in Photoshop
		int opacity = Integer.parseInt(getAttr(node, "opacity", "255"));
		String blendMode = getAttr(node, "blendMode", "norm");
		int top = Integer.parseInt(getAttr(node, "top", "0"));
		int left = Integer.parseInt(getAttr(node, "left", "0"));
		int bottom = Integer.parseInt(getAttr(node, "bottom", "0"));
		int right = Integer.parseInt(getAttr(node, "right", "0"));

		return new RawLayer(name, index, isGroup, isSectionDivider, isVisible, opacity, blendMode,
						   top, left, bottom, right);
	}

	private static String getAttr(IIOMetadataNode node, String name, String defaultValue)
	{
		String val = node.getAttribute(name);
		return val != null ? val : defaultValue;
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
}
