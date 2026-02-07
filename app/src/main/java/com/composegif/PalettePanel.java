package com.composegif;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.IndexColorModel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PalettePanel extends JPanel
{

	private static final int SWATCH_SIZE = 28;
	private static final int GAP = 4;

	private int[] paletteColors = new int[0];
	private final Set<Integer> transparentColors = new LinkedHashSet<>();
	private Runnable onChange;

	public PalettePanel()
	{
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				handleClick(e.getX(), e.getY());
			}
		});
	}

	public void setPalette(List<IndexColorModel> colorModels)
	{
		transparentColors.clear();
		if (colorModels == null || colorModels.isEmpty())
		{
			paletteColors = new int[0];
			revalidate();
			repaint();
			return;
		}
		// Union all frames' palettes, deduplicated, preserving first-seen order
		var seen = new LinkedHashSet<Integer>();
		for (IndexColorModel icm : colorModels)
		{
			int size = icm.getMapSize();
			for (int i = 0; i < size; i++)
			{
				int rgb = icm.getRGB(i) | 0xFF000000;
				seen.add(rgb);
			}
		}
		paletteColors = seen.stream().mapToInt(Integer::intValue).toArray();
		revalidate();
		repaint();
	}

	public Set<Integer> getTransparentColors()
	{
		return Set.copyOf(transparentColors);
	}

	public void setTransparentColors(Set<Integer> colors)
	{
		transparentColors.clear();
		if (colors != null) transparentColors.addAll(colors);
		repaint();
	}

	public void setOnChange(Runnable onChange)
	{
		this.onChange = onChange;
	}

	private int getCols(int availableWidth)
	{
		return Math.max(1, availableWidth / (SWATCH_SIZE + GAP));
	}

	@Override
	public Dimension getPreferredSize()
	{
		if (paletteColors.length == 0) return new Dimension(0, 0);
		// Use own width if already laid out, otherwise use a reasonable default
		int w = getWidth() > 0 ? getWidth() : 256;
		int cols = getCols(w);
		int rows = (paletteColors.length + cols - 1) / cols;
		return new Dimension(w, rows * (SWATCH_SIZE + GAP));
	}

	@Override
	public void setBounds(int x, int y, int width, int height)
	{
		// GridBagLayout assigns width via HORIZONTAL fill, then we need the correct
		// height for that width. Recalculate preferred height based on assigned width.
		if (paletteColors.length > 0 && width > 0)
		{
			int cols = getCols(width);
			int rows = (paletteColors.length + cols - 1) / cols;
			height = rows * (SWATCH_SIZE + GAP);
		}
		super.setBounds(x, y, width, height);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;

		int cols = getCols(getWidth());

		for (int i = 0; i < paletteColors.length; i++)
		{
			int col = i % cols;
			int row = i / cols;
			int x = col * (SWATCH_SIZE + GAP);
			int y = row * (SWATCH_SIZE + GAP);

			int rgb = paletteColors[i];
			g2.setColor(new Color(rgb));
			g2.fillRect(x, y, SWATCH_SIZE, SWATCH_SIZE);

			// Border
			g2.setColor(Color.DARK_GRAY);
			g2.drawRect(x, y, SWATCH_SIZE - 1, SWATCH_SIZE - 1);

			// Mark transparent with an X
			if (transparentColors.contains(rgb))
			{
				g2.setColor(Color.WHITE);
				g2.drawLine(x + 2, y + 2, x + SWATCH_SIZE - 3, y + SWATCH_SIZE - 3);
				g2.drawLine(x + SWATCH_SIZE - 3, y + 2, x + 2, y + SWATCH_SIZE - 3);
				g2.setColor(Color.BLACK);
				g2.drawLine(x + 1, y + 1, x + SWATCH_SIZE - 4, y + SWATCH_SIZE - 4);
				g2.drawLine(x + SWATCH_SIZE - 4, y + 1, x + 1, y + SWATCH_SIZE - 4);
			}
		}
	}

	private void handleClick(int mx, int my)
	{
		if (paletteColors.length == 0) return;
		int cols = getCols(getWidth());
		int col = mx / (SWATCH_SIZE + GAP);
		int row = my / (SWATCH_SIZE + GAP);
		int index = row * cols + col;
		if (index < 0 || index >= paletteColors.length) return;

		int rgb = paletteColors[index];
		if (transparentColors.contains(rgb))
		{
			transparentColors.remove(rgb);
		}
		else
		{
			transparentColors.add(rgb);
		}
		repaint();
		if (onChange != null) onChange.run();
	}
}