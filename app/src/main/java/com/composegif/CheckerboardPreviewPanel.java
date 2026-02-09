package com.composegif;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A panel that displays a BufferedImage scaled to fit, with a checkerboard
 * background for transparency visualization. Used by import dialogs.
 */
class CheckerboardPreviewPanel extends JPanel
{
	private static final Color CHECK_LIGHT = new Color(204, 204, 204);
	private static final Color CHECK_DARK = new Color(153, 153, 153);

	private BufferedImage image;

	void setImage(BufferedImage img)
	{
		this.image = img;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (image == null)
		{
			g.setColor(Color.GRAY);
			String msg = "No selection";
			int sw = g.getFontMetrics().stringWidth(msg);
			g.drawString(msg, (getWidth() - sw) / 2, getHeight() / 2);
			return;
		}

		// Scale to fit preserving aspect ratio
		Insets insets = getInsets();
		int areaW = getWidth() - insets.left - insets.right;
		int areaH = getHeight() - insets.top - insets.bottom;
		if (areaW <= 0 || areaH <= 0) return;

		int imgW = image.getWidth();
		int imgH = image.getHeight();
		double scale = Math.min((double) areaW / imgW, (double) areaH / imgH);

		int drawW = (int) (imgW * scale);
		int drawH = (int) (imgH * scale);
		int drawX = insets.left + (areaW - drawW) / 2;
		int drawY = insets.top + (areaH - drawH) / 2;

		// Draw checkerboard background (scale checker size with image)
		Graphics2D g2 = (Graphics2D) g;
		int checkSize = Math.max(4, (int) Math.ceil(8 * scale));
		for (int cy = 0; cy < drawH; cy += checkSize)
		{
			for (int cx = 0; cx < drawW; cx += checkSize)
			{
				g2.setColor(((cx / checkSize + cy / checkSize) % 2 == 0) ? CHECK_LIGHT : CHECK_DARK);
				g2.fillRect(drawX + cx, drawY + cy,
						Math.min(checkSize, drawW - cx),
						Math.min(checkSize, drawH - cy));
			}
		}

		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.drawImage(image, drawX, drawY, drawW, drawH, null);
	}
}
