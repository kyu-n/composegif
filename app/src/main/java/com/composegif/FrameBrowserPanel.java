package com.composegif;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Horizontal frame ribbon showing thumbnails in a single scrollable row.
 * Supports drag-to-reorder, multi-select, hover buttons, and context menu.
 */
public class FrameBrowserPanel extends JPanel
{

	private static final Color CHECK_LIGHT = new Color(204, 204, 204);
	private static final Color CHECK_DARK = new Color(153, 153, 153);
	private static final int CHECK_SIZE = 8;
	private static final int THUMB_MIN = 48;
	private static final int THUMB_MAX = 128;
	private static final int THUMB_GAP = 16;
	private static final int THUMB_LABEL_HEIGHT = 16;
	private static final int MAX_SCALE = 16;
	private static final BasicStroke STROKE_1 = new BasicStroke(1);
	private static final BasicStroke STROKE_3 = new BasicStroke(3);
	private static final Color BTN_EYE_VISIBLE = new Color(100, 100, 100);
	private static final Color BTN_EYE_HIDDEN = new Color(100, 160, 100);
	private static final Color BTN_DELETE = new Color(200, 60, 60);
	private static final Color BTN_DUPLICATE = new Color(60, 140, 180);

	private final ArrayList<FrameLoader.FrameData> frames = new ArrayList<>();
	private final HashSet<Integer> hiddenIndices = new HashSet<>();
	private Runnable onChange;

	// Selection state
	private final HashSet<Integer> selectedIndices = new HashSet<>();
	private int anchorIndex = -1;
	private int hoveredIndex = -1;
	private int dragSourceIndex = -1;
	private int dragTargetIndex = -1;
	private int dragStartX = -1;
	private static final int DRAG_THRESHOLD = 5;

	// Callbacks
	private IntConsumer onDetailRequested;
	private IntConsumer onFrameSelected;

	public FrameBrowserPanel()
	{
		setFocusable(true);
		setBackground(UIManager.getColor("Panel.background"));

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e)) return;
				requestFocusInWindow();
				int idx = indexAt(e.getX(), e.getY());
				if (idx < 0 || idx >= frames.size())
				{
					selectedIndices.clear();
					anchorIndex = -1;
					repaint();
					return;
				}

				// Check if click is on the X delete button
				if (isOnDeleteButton(e.getX(), e.getY(), idx))
				{
					deleteFrame(idx);
					return;
				}

				// Check if click is on the eye hide/show button
				if (isOnEyeButton(e.getX(), e.getY(), idx))
				{
					toggleHidden(idx);
					return;
				}

				// Check if click is on the duplicate button
				if (isOnDuplicateButton(e.getX(), e.getY(), idx))
				{
					if (!selectedIndices.contains(idx))
					{
						selectedIndices.clear();
						selectedIndices.add(idx);
					}
					duplicateSelected();
					return;
				}

				// Multi-select logic
				if (e.isShiftDown() && anchorIndex >= 0 && anchorIndex < frames.size())
				{
					// Shift+Click: range select
					if (!e.isControlDown()) selectedIndices.clear();
					int lo = Math.min(anchorIndex, idx);
					int hi = Math.max(anchorIndex, idx);
					for (int i = lo; i <= hi; i++) selectedIndices.add(i);
					repaint();
				}
				else if (e.isControlDown())
				{
					// Ctrl+Click: toggle single frame
					if (selectedIndices.contains(idx))
					{
						selectedIndices.remove(Integer.valueOf(idx));
					}
					else
					{
						selectedIndices.add(idx);
					}
					anchorIndex = idx;
					repaint();
				}
				else if (e.getClickCount() == 2)
				{
					// Double-click: fire detail requested
					selectedIndices.clear();
					selectedIndices.add(idx);
					anchorIndex = idx;
					repaint();
					if (onDetailRequested != null) onDetailRequested.accept(idx);
				}
				else if (e.getClickCount() == 1)
				{
					// Plain single click: select only
					selectedIndices.clear();
					selectedIndices.add(idx);
					anchorIndex = idx;
					repaint();
					if (onFrameSelected != null) onFrameSelected.accept(idx);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (hoveredIndex != -1)
				{
					hoveredIndex = -1;
					repaint();
				}
			}
		});

		addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int idx = indexAt(e.getX(), e.getY());
				if (idx != hoveredIndex)
				{
					hoveredIndex = idx;
					repaint();
				}
			}
		});

		addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_DELETE && !selectedIndices.isEmpty())
				{
					deleteFrames(new HashSet<>(selectedIndices));
				}
				else if (e.getKeyCode() == KeyEvent.VK_A && e.isControlDown() && !frames.isEmpty())
				{
					selectedIndices.clear();
					for (int i = 0; i < frames.size(); i++) selectedIndices.add(i);
					repaint();
				}
				else if (e.getKeyCode() == KeyEvent.VK_D && e.isControlDown())
				{
					duplicateSelected();
				}
				else if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !selectedIndices.isEmpty())
				{
					selectedIndices.clear();
					anchorIndex = -1;
					repaint();
				}
			}
		});

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showContextMenu(e);
					return;
				}
				if (!SwingUtilities.isLeftMouseButton(e)) return;
				int idx = indexAt(e.getX(), e.getY());
				if (idx >= 0 && idx < frames.size()
						&& !e.isControlDown() && !e.isShiftDown()
						&& !isOnDeleteButton(e.getX(), e.getY(), idx)
						&& !isOnEyeButton(e.getX(), e.getY(), idx)
						&& !isOnDuplicateButton(e.getX(), e.getY(), idx))
				{
					dragSourceIndex = idx;
					dragStartX = e.getX();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					if (dragSourceIndex >= 0 && dragTargetIndex >= 0
							&& dragSourceIndex != dragTargetIndex)
					{
						performReorder(dragSourceIndex, dragTargetIndex);
					}
					dragSourceIndex = -1;
					dragTargetIndex = -1;
					repaint();
				}
				if (e.isPopupTrigger())
				{
					dragSourceIndex = -1;
					dragTargetIndex = -1;
					repaint();
					showContextMenu(e);
				}
			}
		});
		addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (dragSourceIndex >= 0)
				{
					if (Math.abs(e.getX() - dragStartX) < DRAG_THRESHOLD) return;
					int idx = indexAt(e.getX(), e.getY());
					if (idx >= 0 && idx != dragTargetIndex)
					{
						dragTargetIndex = idx;
						repaint();
					}
					else if (idx < 0 && dragTargetIndex >= 0)
					{
						dragTargetIndex = -1;
						repaint();
					}
				}
			}
		});

		// Revalidate on resize for dynamic thumb sizing
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				revalidate();
				repaint();
			}
		});
	}

	// --- Public API ---

	public void setFrames(List<FrameLoader.FrameData> newFrames)
	{
		frames.clear();
		frames.addAll(newFrames);
		hiddenIndices.clear();
		selectedIndices.clear();
		anchorIndex = -1;
		hoveredIndex = -1;
		revalidate();
		repaint();
	}

	public boolean hasFrames()
	{
		return !frames.isEmpty();
	}

	public List<FrameLoader.FrameData> getFrames()
	{
		if (hiddenIndices.isEmpty()) return List.copyOf(frames);
		ArrayList<FrameLoader.FrameData> visible = new ArrayList<>(frames.size());
		for (int i = 0; i < frames.size(); i++)
		{
			if (!hiddenIndices.contains(i)) visible.add(frames.get(i));
		}
		return List.copyOf(visible);
	}

	public int getFrameCount()
	{
		return frames.size();
	}

	public FrameLoader.FrameData getFrame(int index)
	{
		if (index < 0 || index >= frames.size()) return null;
		return frames.get(index);
	}

	public boolean isHidden(int index)
	{
		return hiddenIndices.contains(index);
	}

	public void toggleHidden(int index)
	{
		if (index < 0 || index >= frames.size()) return;
		if (hiddenIndices.contains(index))
		{
			hiddenIndices.remove(Integer.valueOf(index));
		}
		else
		{
			hiddenIndices.add(index);
		}
		repaint();
		fireChange();
	}

	public void duplicateFrame(int index)
	{
		if (index < 0 || index >= frames.size()) return;
		selectedIndices.clear();
		selectedIndices.add(index);
		duplicateSelected();
	}

	public void deleteFrame(int index)
	{
		if (index < 0 || index >= frames.size()) return;
		deleteFrames(new HashSet<>(Set.of(index)));
	}

	public void setOnChange(Runnable onChange)
	{
		this.onChange = onChange;
	}

	public void setOnDetailRequested(IntConsumer cb)
	{
		this.onDetailRequested = cb;
	}

	public void setOnFrameSelected(IntConsumer cb)
	{
		this.onFrameSelected = cb;
	}

	// --- Internal ---

	private void fireChange()
	{
		if (onChange != null) onChange.run();
	}

	private void performReorder(int srcIdx, int dstIdx)
	{
		if (srcIdx < 0 || srcIdx >= frames.size()
				|| dstIdx < 0 || dstIdx >= frames.size()) return;

		// Remap hidden indices for the move
		boolean srcWasHidden = hiddenIndices.remove(Integer.valueOf(srcIdx));
		HashSet<Integer> remapped = new HashSet<>();
		for (int h : hiddenIndices)
		{
			int adjusted = h;
			if (h > srcIdx) adjusted--;
			if (adjusted >= dstIdx) adjusted++;
			remapped.add(adjusted);
		}
		hiddenIndices.clear();
		hiddenIndices.addAll(remapped);
		if (srcWasHidden) hiddenIndices.add(dstIdx);

		// Remap selected indices for the move
		boolean srcWasSelected = selectedIndices.remove(Integer.valueOf(srcIdx));
		HashSet<Integer> remappedSel = new HashSet<>();
		for (int s : selectedIndices)
		{
			int adjusted = s;
			if (s > srcIdx) adjusted--;
			if (adjusted >= dstIdx) adjusted++;
			remappedSel.add(adjusted);
		}
		selectedIndices.clear();
		selectedIndices.addAll(remappedSel);
		if (srcWasSelected) selectedIndices.add(dstIdx);

		FrameLoader.FrameData moving = frames.remove(srcIdx);
		frames.add(dstIdx, moving);
		anchorIndex = dstIdx;
		revalidate();
		repaint();
		fireChange();
	}

	private void deleteFrames(HashSet<Integer> toDelete)
	{
		// Sort descending to avoid index shift issues
		ArrayList<Integer> sorted = new ArrayList<>(toDelete);
		sorted.sort(Collections.reverseOrder());

		for (int index : sorted)
		{
			if (index < 0 || index >= frames.size()) continue;
			frames.remove(index);

			// Adjust hidden indices
			hiddenIndices.remove(Integer.valueOf(index));
			HashSet<Integer> adjustedHidden = new HashSet<>();
			for (int h : hiddenIndices)
			{
				adjustedHidden.add(h > index ? h - 1 : h);
			}
			hiddenIndices.clear();
			hiddenIndices.addAll(adjustedHidden);

			// Adjust selected indices
			selectedIndices.remove(Integer.valueOf(index));
			HashSet<Integer> adjustedSelected = new HashSet<>();
			for (int s : selectedIndices)
			{
				adjustedSelected.add(s > index ? s - 1 : s);
			}
			selectedIndices.clear();
			selectedIndices.addAll(adjustedSelected);
		}

		if (frames.isEmpty())
		{
			selectedIndices.clear();
			anchorIndex = -1;
			fireChange();
			return;
		}

		if (anchorIndex >= frames.size()) anchorIndex = frames.size() - 1;
		revalidate();
		repaint();
		fireChange();
	}

	private void duplicateSelected()
	{
		if (selectedIndices.isEmpty()) return;

		// Sort descending so insertions don't affect earlier indices
		ArrayList<Integer> sorted = new ArrayList<>(selectedIndices);
		sorted.sort(Collections.reverseOrder());

		HashSet<Integer> newIndices = new HashSet<>();

		for (int idx : sorted)
		{
			if (idx < 0 || idx >= frames.size()) continue;
			int insertPos = idx + 1;
			frames.add(insertPos, frames.get(idx));

			// Adjust hidden indices: shift up those >= insertPos, copy hidden state
			boolean wasHidden = hiddenIndices.contains(idx);
			HashSet<Integer> adjustedHidden = new HashSet<>();
			for (int h : hiddenIndices)
			{
				adjustedHidden.add(h >= insertPos ? h + 1 : h);
			}
			if (wasHidden) adjustedHidden.add(insertPos);
			hiddenIndices.clear();
			hiddenIndices.addAll(adjustedHidden);

			// Adjust previously recorded new indices upward
			HashSet<Integer> adjustedNew = new HashSet<>();
			for (int n : newIndices)
			{
				adjustedNew.add(n >= insertPos ? n + 1 : n);
			}
			adjustedNew.add(insertPos);
			newIndices = adjustedNew;
		}

		selectedIndices.clear();
		selectedIndices.addAll(newIndices);
		anchorIndex = -1;
		revalidate();
		repaint();
		fireChange();
	}

	// --- Layout geometry ---

	private int thumbSize()
	{
		int available = getHeight() - THUMB_GAP - THUMB_LABEL_HEIGHT;
		return Math.max(THUMB_MIN, Math.min(THUMB_MAX, available));
	}

	private int cellWidth()
	{
		return thumbSize() + THUMB_GAP;
	}

	private int cellHeight()
	{
		return thumbSize() + THUMB_LABEL_HEIGHT;
	}

	private int indexAt(int mx, int my)
	{
		int cw = cellWidth();
		int idx = mx / cw;
		if (idx < 0 || idx >= frames.size()) return -1;
		return idx;
	}

	private Rectangle thumbRect(int index)
	{
		int ts = thumbSize();
		int cw = cellWidth();
		int x = index * cw + THUMB_GAP / 2;
		int y = THUMB_GAP / 2;
		return new Rectangle(x, y, ts, ts);
	}

	private boolean isOnDeleteButton(int mx, int my, int index)
	{
		Rectangle r = thumbRect(index);
		int btnX = r.x + r.width - 16;
		int btnY = r.y;
		return mx >= btnX && mx <= btnX + 16 && my >= btnY && my <= btnY + 16;
	}

	private boolean isOnEyeButton(int mx, int my, int index)
	{
		Rectangle r = thumbRect(index);
		int btnX = r.x;
		int btnY = r.y;
		return mx >= btnX && mx <= btnX + 16 && my >= btnY && my <= btnY + 16;
	}

	private boolean isOnDuplicateButton(int mx, int my, int index)
	{
		Rectangle r = thumbRect(index);
		int btnX = r.x + r.width - 16;
		int btnY = r.y + r.height - 16;
		return mx >= btnX && mx <= btnX + 16 && my >= btnY && my <= btnY + 16;
	}

	@Override
	public Dimension getPreferredSize()
	{
		if (frames.isEmpty()) return new Dimension(0, 0);
		int scrollBarHeight = UIManager.getInt("ScrollBar.width");
		if (scrollBarHeight <= 0) scrollBarHeight = 16;
		return new Dimension(frames.size() * cellWidth() + THUMB_GAP,
				cellHeight() + THUMB_GAP + scrollBarHeight);
	}

	// --- Context menu ---

	private void showContextMenu(MouseEvent e)
	{
		int idx = indexAt(e.getX(), e.getY());
		if (idx < 0 || idx >= frames.size()) return;

		// If right-clicked frame isn't in selection, select it alone
		if (!selectedIndices.contains(idx))
		{
			selectedIndices.clear();
			selectedIndices.add(idx);
			anchorIndex = idx;
			repaint();
		}

		boolean allHid = true;
		for (int i : selectedIndices)
		{
			if (!hiddenIndices.contains(i)) { allHid = false; break; }
		}
		final boolean allHidden = allHid;

		JPopupMenu menu = new JPopupMenu();

		JMenuItem duplicateItem = new JMenuItem("Duplicate");
		duplicateItem.addActionListener(ev -> duplicateSelected());

		JMenuItem hideItem = new JMenuItem(allHidden ? "Show" : "Hide");
		hideItem.addActionListener(ev -> {
			if (allHidden)
			{
				for (int i : selectedIndices) hiddenIndices.remove(Integer.valueOf(i));
			}
			else
			{
				hiddenIndices.addAll(selectedIndices);
			}
			repaint();
			fireChange();
		});

		JMenuItem deleteItem = new JMenuItem("Delete");
		deleteItem.addActionListener(ev -> deleteFrames(new HashSet<>(selectedIndices)));

		menu.add(duplicateItem);
		menu.add(hideItem);
		menu.add(deleteItem);
		menu.show(this, e.getX(), e.getY());
	}

	// --- Painting ---

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		if (frames.isEmpty())
		{
			g2.setColor(Color.GRAY);
			String msg = "No frames";
			int sw = g2.getFontMetrics().stringWidth(msg);
			g2.drawString(msg, (getWidth() - sw) / 2, getHeight() / 2);
			return;
		}

		int ts = thumbSize();

		for (int i = 0; i < frames.size(); i++)
		{
			Rectangle r = thumbRect(i);
			BufferedImage img = frames.get(i).image();
			int imgW = img.getWidth();
			int imgH = img.getHeight();

			// Integer scale to fit thumb size; fall back to shrink-to-fit for large images
			int maxDim = Math.max(imgW, imgH);
			int drawW, drawH;
			if (maxDim <= ts)
			{
				int scale = Math.min(ts / maxDim, MAX_SCALE);
				drawW = imgW * scale;
				drawH = imgH * scale;
			}
			else
			{
				drawW = imgW * ts / maxDim;
				drawH = imgH * ts / maxDim;
			}
			int drawX = r.x + (ts - drawW) / 2;
			int drawY = r.y + (ts - drawH) / 2;

			// Clip to cell, draw checkerboard + thumbnail
			Shape oldClip = g2.getClip();
			g2.clipRect(r.x, r.y, ts, ts);
			drawCheckerboard(g2, drawX, drawY, drawW, drawH);
			g2.drawImage(img, drawX, drawY, drawW, drawH, null);
			g2.setClip(oldClip);

			// Hidden frame overlay
			boolean hidden = hiddenIndices.contains(i);
			if (hidden)
			{
				Composite oldComp = g2.getComposite();
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
				g2.setColor(getBackground());
				g2.fillRect(r.x, r.y, ts, ts);
				g2.setComposite(oldComp);
			}

			// Cell border (on top of image)
			g2.setColor(UIManager.getColor("Component.borderColor"));
			g2.drawRect(r.x, r.y, ts - 1, ts - 1);

			// Selection border
			if (selectedIndices.contains(i))
			{
				g2.setColor(UIManager.getColor("Component.focusColor"));
				g2.setStroke(STROKE_3);
				g2.drawRect(r.x - 1, r.y - 1, ts + 1, ts + 1);
				g2.setStroke(STROKE_1);
			}

			// Frame label
			g2.setColor(getForeground());
			String label = String.valueOf(i + 1);
			if (hidden) label += " (hidden)";
			int labelW = g2.getFontMetrics().stringWidth(label);
			g2.drawString(label, r.x + (ts - labelW) / 2, r.y + ts + THUMB_LABEL_HEIGHT - 2);

			// Hover buttons
			if (i == hoveredIndex)
			{
				// Eye hide/show button (top-left)
				int eyeX = r.x;
				int eyeY = r.y;
				g2.setColor(hidden ? BTN_EYE_HIDDEN : BTN_EYE_VISIBLE);
				g2.fillRoundRect(eyeX, eyeY, 16, 16, 4, 4);
				g2.setColor(Color.WHITE);
				// Draw eye icon: oval + pupil
				g2.drawOval(eyeX + 3, eyeY + 5, 10, 6);
				g2.fillOval(eyeX + 6, eyeY + 6, 4, 4);
				if (hidden)
				{
					// Strikethrough line for hidden state
					g2.drawLine(eyeX + 3, eyeY + 13, eyeX + 13, eyeY + 3);
				}

				// Delete X button (top-right)
				int btnX = r.x + r.width - 16;
				int btnY = r.y;
				g2.setColor(BTN_DELETE);
				g2.fillRoundRect(btnX, btnY, 16, 16, 4, 4);
				g2.setColor(Color.WHITE);
				g2.drawLine(btnX + 4, btnY + 4, btnX + 12, btnY + 12);
				g2.drawLine(btnX + 12, btnY + 4, btnX + 4, btnY + 12);

				// Duplicate + button (bottom-right)
				int dupX = r.x + r.width - 16;
				int dupY = r.y + ts - 16;
				g2.setColor(BTN_DUPLICATE);
				g2.fillRoundRect(dupX, dupY, 16, 16, 4, 4);
				g2.setColor(Color.WHITE);
				g2.drawLine(dupX + 4, dupY + 8, dupX + 12, dupY + 8);
				g2.drawLine(dupX + 8, dupY + 4, dupX + 8, dupY + 12);
			}
		}

		// Drop indicator line during drag-to-reorder
		if (dragSourceIndex >= 0 && dragTargetIndex >= 0 && dragSourceIndex != dragTargetIndex)
		{
			Rectangle target = thumbRect(dragTargetIndex);
			int lineX = (dragTargetIndex > dragSourceIndex) ? target.x + target.width : target.x;
			g2.setColor(UIManager.getColor("Component.focusColor"));
			g2.setStroke(STROKE_3);
			g2.drawLine(lineX, THUMB_GAP / 2, lineX, THUMB_GAP / 2 + ts);
			g2.setStroke(STROKE_1);
		}
	}

	// --- Shared utilities ---

	static void drawCheckerboard(Graphics2D g2, int x, int y, int w, int h)
	{
		for (int cy = 0; cy < h; cy += CHECK_SIZE)
		{
			for (int cx = 0; cx < w; cx += CHECK_SIZE)
			{
				g2.setColor(((cx / CHECK_SIZE + cy / CHECK_SIZE) % 2 == 0) ? CHECK_LIGHT : CHECK_DARK);
				g2.fillRect(x + cx, y + cy,
						Math.min(CHECK_SIZE, w - cx),
						Math.min(CHECK_SIZE, h - cy));
			}
		}
	}

}
