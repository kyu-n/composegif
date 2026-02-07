package com.composegif;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.BiConsumer;

public class PreviewPanel extends JPanel
{

	private static final Color CHECK_LIGHT = new Color(204, 204, 204);
	private static final Color CHECK_DARK = new Color(153, 153, 153);
	private static final int CHECK_SIZE = 8;
	private static final int MAX_SCALE = 16;
	private static final Stroke DASH_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT,
			BasicStroke.JOIN_MITER, 10, new float[]{4, 4}, 0);

	private List<BufferedImage> frames;
	private int currentFrame;
	private final Timer timer;
	private Object interpolationHint = RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;

	// Shared checkerboard — one image reused for all frames
	private BufferedImage checkerCache;
	private int cachedCheckW;
	private int cachedCheckH;

	// Draggable layer overlay
	private int layerOffsetX, layerOffsetY, layerW, layerH;
	private boolean hasLayerOverlay;
	private boolean dragging;
	private int dragStartMouseX, dragStartMouseY;
	private int dragStartOffsetX, dragStartOffsetY;
	private BiConsumer<Integer, Integer> onDragComplete;

	public PreviewPanel()
	{
		setPreferredSize(new Dimension(800, 800));
		timer = new Timer(100, e -> advanceFrame());

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!hasLayerOverlay || frames == null || frames.isEmpty()) return;
				dragging = true;
				dragStartMouseX = e.getX();
				dragStartMouseY = e.getY();
				dragStartOffsetX = layerOffsetX;
				dragStartOffsetY = layerOffsetY;
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (!dragging) return;
				dragging = false;
				if (onDragComplete != null)
				{
					onDragComplete.accept(layerOffsetX, layerOffsetY);
				}
			}
		});

		addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (!dragging || frames == null || frames.isEmpty()) return;
				int scale = computeScale();
				int deltaX = (e.getX() - dragStartMouseX) / scale;
				int deltaY = (e.getY() - dragStartMouseY) / scale;
				layerOffsetX = dragStartOffsetX + deltaX;
				layerOffsetY = dragStartOffsetY + deltaY;
				repaint();
			}
		});
	}

	public void setFrames(List<BufferedImage> frames)
	{
		this.frames = frames;
		if (frames != null && !frames.isEmpty())
		{
			// Preserve playback position when updating frames mid-animation
			if (currentFrame >= frames.size()) currentFrame = 0;
			if (!timer.isRunning()) timer.start();
		}
		else
		{
			currentFrame = 0;
			timer.stop();
		}
		repaint();
	}

	public void setDelay(int delayMs)
	{
		timer.setDelay(delayMs);
	}

	public void setInterpolationHint(Object hint)
	{
		this.interpolationHint = hint;
		repaint();
	}

	public void setDraggableLayer(int offsetX, int offsetY, int layerW, int layerH,
								  BiConsumer<Integer, Integer> onDragComplete)
	{
		this.layerOffsetX = offsetX;
		this.layerOffsetY = offsetY;
		this.layerW = layerW;
		this.layerH = layerH;
		this.hasLayerOverlay = true;
		this.onDragComplete = onDragComplete;
		repaint();
	}

	public void clearDraggableLayer()
	{
		this.hasLayerOverlay = false;
		this.dragging = false;
		this.onDragComplete = null;
		repaint();
	}

	private int computeScale()
	{
		if (frames == null || frames.isEmpty()) return 1;
		BufferedImage frame = frames.get(currentFrame);
		return Math.max(1, Math.min(
				Math.min(getWidth() / frame.getWidth(), getHeight() / frame.getHeight()),
				MAX_SCALE));
	}

	private void advanceFrame()
	{
		if (frames == null || frames.isEmpty()) return;
		currentFrame = (currentFrame + 1) % frames.size();
		paintImmediately(0, 0, getWidth(), getHeight());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		if (frames == null || frames.isEmpty())
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g;
			g2.setColor(Color.GRAY);
			String msg = "No frames loaded";
			int sw = g2.getFontMetrics().stringWidth(msg);
			g2.drawString(msg, (getWidth() - sw) / 2, getHeight() / 2);
			return;
		}

		Graphics2D g2 = (Graphics2D) g;
		BufferedImage frame = frames.get(currentFrame);
		int imgW = frame.getWidth();
		int imgH = frame.getHeight();

		int scale = computeScale();
		int drawW = imgW * scale;
		int drawH = imgH * scale;
		int drawX = (getWidth() - drawW) / 2;
		int drawY = (getHeight() - drawH) / 2;

		// Clear only the margin areas around the frame
		g2.setColor(getBackground());
		if (drawY > 0) g2.fillRect(0, 0, getWidth(), drawY);
		if (drawY + drawH < getHeight()) g2.fillRect(0, drawY + drawH, getWidth(), getHeight() - drawY - drawH);
		if (drawX > 0) g2.fillRect(0, drawY, drawX, drawH);
		if (drawX + drawW < getWidth()) g2.fillRect(drawX + drawW, drawY, getWidth() - drawX - drawW, drawH);

		// Checkerboard
		g2.drawImage(getCheckerboard(drawW, drawH), drawX, drawY, null);

		// Frame — scale directly, no cache (integer scale + nearest neighbor is trivial)
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolationHint);
		g2.drawImage(frame, drawX, drawY, drawW, drawH, null);

		// Layer selection overlay
		if (hasLayerOverlay)
		{
			int rectX = drawX + layerOffsetX * scale;
			int rectY = drawY + layerOffsetY * scale;
			int rectW = layerW * scale;
			int rectH = layerH * scale;

			g2.setColor(Color.CYAN);
			g2.setStroke(DASH_STROKE);
			g2.drawRect(rectX, rectY, rectW, rectH);
		}

		Toolkit.getDefaultToolkit().sync();
	}

	private BufferedImage getCheckerboard(int w, int h)
	{
		if (checkerCache != null && cachedCheckW == w && cachedCheckH == h)
		{
			return checkerCache;
		}
		checkerCache = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D cg = checkerCache.createGraphics();
		for (int cy = 0; cy < h; cy += CHECK_SIZE)
		{
			for (int cx = 0; cx < w; cx += CHECK_SIZE)
			{
				cg.setColor(((cx / CHECK_SIZE + cy / CHECK_SIZE) % 2 == 0) ? CHECK_LIGHT : CHECK_DARK);
				cg.fillRect(cx, cy,
						Math.min(CHECK_SIZE, w - cx),
						Math.min(CHECK_SIZE, h - cy));
			}
		}
		cg.dispose();
		cachedCheckW = w;
		cachedCheckH = h;
		return checkerCache;
	}
}
