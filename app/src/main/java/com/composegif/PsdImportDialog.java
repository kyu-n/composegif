package com.composegif;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PsdImportDialog extends BaseImportDialog<PsdNode>
{
	private final PsdImporter.PsdTree tree;
	private final Map<PsdNode, Boolean> checkState = new HashMap<>();
	private final JTree jTree;
	private final Timer previewDebounce;
	private SwingWorker<BufferedImage, Void> currentPreviewWorker;

	private PsdImportDialog(JFrame parent, PsdImporter.PsdTree tree, String filename)
	{
		super(parent, "Import PSD: " + filename,
			"Import folders as frames (into current layer)",
			"Import folders as layers (one per selection)");

		this.tree = tree;

		// Build tree model
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("root");
		for (PsdNode node : tree.roots())
		{
			rootNode.add(buildTreeNode(node));
		}

		// Initialize check state: all nodes checked
		initCheckState(tree.roots());

		// Create JTree
		jTree = new JTree(new DefaultTreeModel(rootNode));
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

		// Debounce timer: fires firePreviewUpdate() after 50ms of inactivity
		previewDebounce = new Timer(50, e -> firePreviewUpdate());
		previewDebounce.setRepeats(false);

		init(new JScrollPane(jTree));

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

	public static ImportResult<PsdNode> show(JFrame parent, PsdImporter.PsdTree tree, String filename)
	{
		PsdImportDialog dialog = new PsdImportDialog(parent, tree, filename);
		return dialog.showDialog();
	}

	@Override
	protected void selectAll()
	{
		for (Map.Entry<PsdNode, Boolean> entry : checkState.entrySet())
		{
			entry.setValue(true);
		}
	}

	@Override
	protected void selectNone()
	{
		for (Map.Entry<PsdNode, Boolean> entry : checkState.entrySet())
		{
			entry.setValue(false);
		}
	}

	@Override
	protected List<PsdNode> getSelectedItems()
	{
		List<PsdNode> result = new ArrayList<>();
		collectSelectedNodes(tree.roots(), result);
		return result;
	}

	@Override
	protected void onSelectionChanged()
	{
		jTree.repaint();
		updatePreview();
	}

	/**
	 * Collects checked nodes with no checked ancestor.
	 * If a group is checked, the group is returned (not its children separately).
	 * If a group is unchecked but some of its children are checked, those children are returned.
	 */
	private void collectSelectedNodes(List<PsdNode> nodes, List<PsdNode> result)
	{
		for (PsdNode node : nodes)
		{
			if (isChecked(node))
			{
				result.add(node);
			}
			else if (node.isGroup)
			{
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
		previewDebounce.restart();
	}

	private void firePreviewUpdate()
	{
		List<PsdNode> selected = getSelectedItems();
		if (selected.isEmpty())
		{
			previewArea.setImage(null);
			return;
		}

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
}
