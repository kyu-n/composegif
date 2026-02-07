package com.composegif;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PsdImportDialog extends JDialog
{
	public enum ImportMode { FRAMES, LAYERS }

	public record ImportResult(List<PsdNode> selectedNodes, ImportMode mode) {}

	private final PsdImporter.PsdTree tree;
	private final Map<PsdNode, Boolean> checkState = new HashMap<>();
	private final DefaultTreeModel treeModel;
	private final JTree jTree;
	private final PreviewArea previewArea;
	private final JRadioButton framesRadio;
	private final JRadioButton layersRadio;
	private final Timer previewDebounce;
	private SwingWorker<BufferedImage, Void> currentPreviewWorker;
	private boolean confirmed = false;

	private PsdImportDialog(JFrame parent, PsdImporter.PsdTree tree, String filename)
	{
		super(parent, "Import PSD: " + filename, true);
		this.tree = tree;

		setSize(700, 500);
		setLocationRelativeTo(parent);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		// Build tree model
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("root");
		for (PsdNode node : tree.roots())
		{
			rootNode.add(buildTreeNode(node));
		}
		treeModel = new DefaultTreeModel(rootNode);

		// Initialize check state: visible nodes checked, hidden unchecked
		initCheckState(tree.roots());

		// Create JTree
		jTree = new JTree(treeModel);
		jTree.setRootVisible(false);
		jTree.setShowsRootHandles(true);
		jTree.setCellRenderer(new CheckboxTreeCellRenderer());
		jTree.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				TreePath path = jTree.getPathForLocation(e.getX(), e.getY());
				if (path == null) return;
				DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) path.getLastPathComponent();
				Object userObj = treeNode.getUserObject();
				if (!(userObj instanceof PsdNode psdNode)) return;

				boolean newState = !isChecked(psdNode);
				checkState.put(psdNode, newState);

				// If group, propagate to all descendants
				if (psdNode.isGroup)
				{
					setDescendantsChecked(psdNode, newState);
				}

				jTree.repaint();
				updatePreview();
			}
		});

		// Expand all nodes
		for (int i = 0; i < jTree.getRowCount(); i++)
		{
			jTree.expandRow(i);
		}

		// Left panel: Select All/None buttons + tree
		JPanel leftPanel = new JPanel(new BorderLayout(0, 4));
		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JButton selectAllBtn = new JButton("Select All");
		selectAllBtn.addActionListener(e -> {
			setAllChecked(true);
			jTree.repaint();
			updatePreview();
		});
		JButton selectNoneBtn = new JButton("None");
		selectNoneBtn.addActionListener(e -> {
			setAllChecked(false);
			jTree.repaint();
			updatePreview();
		});
		buttonRow.add(selectAllBtn);
		buttonRow.add(selectNoneBtn);
		leftPanel.add(buttonRow, BorderLayout.NORTH);
		leftPanel.add(new JScrollPane(jTree), BorderLayout.CENTER);

		// Right panel: preview
		previewArea = new PreviewArea();
		previewArea.setBorder(BorderFactory.createTitledBorder("Preview"));

		// Top split: tree + preview
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, previewArea);
		splitPane.setDividerLocation(300);
		splitPane.setResizeWeight(0.4);

		// Bottom panel: radio buttons + OK/Cancel
		JPanel bottomPanel = new JPanel(new BorderLayout());
		JPanel radioPanel = new JPanel();
		radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.Y_AXIS));
		radioPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		framesRadio = new JRadioButton("Import folders as frames (into current layer)");
		layersRadio = new JRadioButton("Import folders as layers (one per selection)");
		framesRadio.setSelected(true);
		ButtonGroup bg = new ButtonGroup();
		bg.add(framesRadio);
		bg.add(layersRadio);
		radioPanel.add(framesRadio);
		radioPanel.add(layersRadio);
		bottomPanel.add(radioPanel, BorderLayout.CENTER);

		JPanel okCancelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
		JButton okBtn = new JButton("OK");
		JButton cancelBtn = new JButton("Cancel");
		okBtn.addActionListener(e -> {
			confirmed = true;
			dispose();
		});
		cancelBtn.addActionListener(e -> dispose());
		okCancelPanel.add(okBtn);
		okCancelPanel.add(cancelBtn);
		bottomPanel.add(okCancelPanel, BorderLayout.EAST);

		// Assemble
		setLayout(new BorderLayout());
		add(splitPane, BorderLayout.CENTER);
		add(bottomPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(okBtn);

		// Debounce timer: fires firePreviewUpdate() after 50ms of inactivity
		previewDebounce = new Timer(50, e -> firePreviewUpdate());
		previewDebounce.setRepeats(false);

		// Force repaint after window manager shows the dialog (Wayland fix)
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowOpened(WindowEvent e)
			{
				revalidate();
				repaint();
			}
		});

		// Initial preview (fire directly, no debounce for initial load)
		firePreviewUpdate();
	}

	@Override
	public void dispose()
	{
		previewDebounce.stop();
		if (currentPreviewWorker != null && !currentPreviewWorker.isDone())
		{
			currentPreviewWorker.cancel(false);
			try { currentPreviewWorker.get(); } catch (Exception ignored) {}
		}
		super.dispose();
	}

	/**
	 * Shows the dialog and returns the result, or null if cancelled.
	 */
	public static ImportResult show(JFrame parent, PsdImporter.PsdTree tree, String filename)
	{
		PsdImportDialog dialog = new PsdImportDialog(parent, tree, filename);
		dialog.setVisible(true);

		if (!dialog.confirmed) return null;

		List<PsdNode> selectedNodes = dialog.getSelectedRootNodes();
		if (selectedNodes.isEmpty()) return null;

		ImportMode mode = dialog.layersRadio.isSelected() ? ImportMode.LAYERS : ImportMode.FRAMES;
		return new ImportResult(selectedNodes, mode);
	}

	/**
	 * Returns all checked nodes that have no checked ancestor.
	 * If a group is checked, the group is returned (not its children separately).
	 * If a group is unchecked but some of its children are checked, those children are returned.
	 */
	private List<PsdNode> getSelectedRootNodes()
	{
		List<PsdNode> result = new ArrayList<>();
		collectSelectedNodes(tree.roots(), result);
		return result;
	}

	private void collectSelectedNodes(List<PsdNode> nodes, List<PsdNode> result)
	{
		for (PsdNode node : nodes)
		{
			if (isChecked(node))
			{
				// This node is checked — return it; don't descend into children
				result.add(node);
			}
			else if (node.isGroup)
			{
				// Group is unchecked, but maybe some children are checked
				collectSelectedNodes(node.children, result);
			}
		}
	}

	private boolean isChecked(PsdNode node)
	{
		Boolean val = checkState.get(node);
		return val != null && val;
	}

	private void initCheckState(List<PsdNode> nodes)
	{
		for (PsdNode node : nodes)
		{
			// Default all nodes to checked — the user opened the dialog to import.
			// PSD visibility is shown via "(H)" and italic styling for reference only.
			checkState.put(node, true);
			if (node.isGroup)
			{
				initCheckState(node.children);
			}
		}
	}

	private void setDescendantsChecked(PsdNode group, boolean checked)
	{
		for (PsdNode child : group.children)
		{
			checkState.put(child, checked);
			if (child.isGroup)
			{
				setDescendantsChecked(child, checked);
			}
		}
	}

	private void setAllChecked(boolean checked)
	{
		for (Map.Entry<PsdNode, Boolean> entry : checkState.entrySet())
		{
			entry.setValue(checked);
		}
	}

	private DefaultMutableTreeNode buildTreeNode(PsdNode node)
	{
		DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
		if (node.isGroup)
		{
			for (PsdNode child : node.children)
			{
				treeNode.add(buildTreeNode(child));
			}
		}
		return treeNode;
	}

	private void updatePreview()
	{
		// Debounce: restart the timer on each call, fire after 50ms of inactivity
		previewDebounce.restart();
	}

	private void firePreviewUpdate()
	{
		List<PsdNode> selected = getSelectedRootNodes();
		if (selected.isEmpty())
		{
			previewArea.setImage(null);
			return;
		}

		// Cancel any previous worker to prevent concurrent ImageReader access
		if (currentPreviewWorker != null && !currentPreviewWorker.isDone())
		{
			currentPreviewWorker.cancel(false);
		}

		SwingWorker<BufferedImage, Void> worker = new SwingWorker<>()
		{
			@Override
			protected BufferedImage doInBackground() throws Exception
			{
				BufferedImage canvas = new BufferedImage(
					tree.canvasWidth(), tree.canvasHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = canvas.createGraphics();
				try
				{
					for (PsdNode node : selected)
					{
						if (isCancelled()) return null;
						PsdImporter.FlattenedFrame flat = PsdImporter.flattenNode(tree, node);
						g.drawImage(flat.image(), 0, 0, null);
					}
					return canvas;
				}
				finally
				{
					g.dispose();
				}
			}

			@Override
			protected void done()
			{
				if (isCancelled()) return;
				try
				{
					previewArea.setImage(get());
				}
				catch (Exception ex)
				{
					previewArea.setImage(null);
				}
			}
		};
		currentPreviewWorker = worker;
		worker.execute();
	}

	// --- Custom tree cell renderer ---

	private class CheckboxTreeCellRenderer implements TreeCellRenderer
	{
		private final JCheckBox checkBox = new JCheckBox();
		private final JPanel panel = new JPanel(new BorderLayout());
		private final JLabel label = new JLabel();

		CheckboxTreeCellRenderer()
		{
			panel.setOpaque(false);
			checkBox.setOpaque(false);
			panel.add(checkBox, BorderLayout.WEST);
			panel.add(label, BorderLayout.CENTER);
		}

		@Override
		public Component getTreeCellRendererComponent(JTree tree, Object value,
				boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus)
		{
			DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) value;
			Object userObj = treeNode.getUserObject();

			if (userObj instanceof PsdNode psdNode)
			{
				checkBox.setSelected(isChecked(psdNode));

				StringBuilder text = new StringBuilder(psdNode.name);
				if (!psdNode.isVisible)
				{
					text.append(" (H)");
				}
				label.setText(text.toString());

				if (!psdNode.isVisible)
				{
					label.setFont(label.getFont().deriveFont(Font.ITALIC));
				}
				else
				{
					label.setFont(label.getFont().deriveFont(Font.PLAIN));
				}

				if (selected)
				{
					label.setForeground(UIManager.getColor("Tree.selectionForeground"));
					panel.setOpaque(true);
					panel.setBackground(UIManager.getColor("Tree.selectionBackground"));
				}
				else
				{
					label.setForeground(UIManager.getColor("Tree.textForeground"));
					panel.setOpaque(false);
				}
			}
			else
			{
				checkBox.setSelected(false);
				label.setText(String.valueOf(userObj));
				label.setFont(label.getFont().deriveFont(Font.PLAIN));
				panel.setOpaque(false);
			}

			return panel;
		}
	}

	// --- Preview area ---

	private static class PreviewArea extends JPanel
	{
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
			Color checkLight = new Color(204, 204, 204);
			Color checkDark = new Color(153, 153, 153);
			for (int cy = 0; cy < drawH; cy += checkSize)
			{
				for (int cx = 0; cx < drawW; cx += checkSize)
				{
					g2.setColor(((cx / checkSize + cy / checkSize) % 2 == 0) ? checkLight : checkDark);
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
}
