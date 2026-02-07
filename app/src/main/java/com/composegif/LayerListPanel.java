package com.composegif;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Sidebar panel listing all layers with visibility toggles and selection.
 */
public class LayerListPanel extends JPanel
{
	private static final int ROW_HEIGHT = 32;
	private static final int MAX_LAYERS = 100;
	private static final Color SELECTED_BG = UIManager.getColor("List.selectionBackground");
	private static final Color SELECTED_FG = UIManager.getColor("List.selectionForeground");

	private final ArrayList<LayerState> layers = new ArrayList<>();
	private int selectedIndex = -1;
	private int nextLayerNum = 0;
	private Runnable onSelectionChanged;
	private Runnable onStructureChanged;
	private java.util.function.Consumer<LayerState> layerInitializer;

	private final JPanel listPanel;
	private final JButton addButton;
	private final JButton removeButton;
	private final JButton moveUpButton;
	private final JButton moveDownButton;

	public LayerListPanel()
	{
		setLayout(new BorderLayout());

		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

		JScrollPane scroll = new JScrollPane(listPanel,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll, BorderLayout.CENTER);

		JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
		addButton = new JButton("+");
		addButton.setToolTipText("Add layer");
		addButton.addActionListener(e -> addLayer());
		removeButton = new JButton("\u2212"); // minus sign
		removeButton.setToolTipText("Remove layer");
		removeButton.addActionListener(e -> removeSelectedLayer());
		moveUpButton = new JButton("\u2191"); // up arrow
		moveUpButton.setToolTipText("Move layer up (draws later)");
		moveUpButton.addActionListener(e -> moveSelectedLayer(1));
		moveDownButton = new JButton("\u2193"); // down arrow
		moveDownButton.setToolTipText("Move layer down (draws earlier)");
		moveDownButton.addActionListener(e -> moveSelectedLayer(-1));

		buttonBar.add(addButton);
		buttonBar.add(removeButton);
		buttonBar.add(moveUpButton);
		buttonBar.add(moveDownButton);
		add(buttonBar, BorderLayout.SOUTH);

		// Start with one layer
		addLayer();
	}

	public List<LayerState> getLayers()
	{
		return List.copyOf(layers);
	}

	public LayerState getSelectedLayer()
	{
		if (selectedIndex < 0 || selectedIndex >= layers.size()) return null;
		return layers.get(selectedIndex);
	}

	public int getSelectedIndex()
	{
		return selectedIndex;
	}

	public void setOnSelectionChanged(Runnable r)
	{
		this.onSelectionChanged = r;
	}

	public void setOnStructureChanged(Runnable r)
	{
		this.onStructureChanged = r;
	}

	public void setLayerInitializer(java.util.function.Consumer<LayerState> initializer)
	{
		this.layerInitializer = initializer;
	}

	public void addLayer()
	{
		if (addLayer("Layer " + (nextLayerNum + 1)) != null)
		{
			nextLayerNum++;
		}
	}

	public LayerState addLayer(String name)
	{
		if (layers.size() >= MAX_LAYERS) return null;
		LayerState state = new LayerState(name);
		if (layerInitializer != null) layerInitializer.accept(state);
		layers.add(state);
		selectedIndex = layers.size() - 1;
		rebuildList();
		fireSelectionChanged();
		fireStructureChanged();
		return state;
	}

	private void removeSelectedLayer()
	{
		if (selectedIndex < 0 || layers.size() <= 1) return;
		layers.remove(selectedIndex);
		if (selectedIndex >= layers.size()) selectedIndex = layers.size() - 1;
		rebuildList();
		fireSelectionChanged();
		fireStructureChanged();
	}

	private void moveSelectedLayer(int direction)
	{
		int newIndex = selectedIndex + direction;
		if (selectedIndex < 0 || newIndex < 0 || newIndex >= layers.size()) return;
		LayerState moving = layers.remove(selectedIndex);
		layers.add(newIndex, moving);
		selectedIndex = newIndex;
		rebuildList();
		fireStructureChanged();
	}

	private void selectLayer(int index)
	{
		if (index < 0 || index >= layers.size() || index == selectedIndex) return;
		selectedIndex = index;
		rebuildList();
		fireSelectionChanged();
	}

	private void rebuildList()
	{
		listPanel.removeAll();
		for (int i = layers.size() - 1; i >= 0; i--)
		{
			final int idx = i;
			LayerState ls = layers.get(i);

			JPanel row = new JPanel(new BorderLayout());
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
			row.setPreferredSize(new Dimension(0, ROW_HEIGHT));
			row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

			if (idx == selectedIndex)
			{
				row.setBackground(SELECTED_BG);
				row.setOpaque(true);
			}

			JCheckBox visCheck = new JCheckBox();
			visCheck.setSelected(ls.visible);
			visCheck.setOpaque(false);
			visCheck.addActionListener(e -> {
				ls.visible = visCheck.isSelected();
				fireStructureChanged();
			});
			row.add(visCheck, BorderLayout.WEST);

			JLabel label = new JLabel(ls.name + (ls.hasFrames() ? "" : " (empty)"));
			if (idx == selectedIndex && SELECTED_FG != null)
			{
				label.setForeground(SELECTED_FG);
			}
			label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
			row.add(label, BorderLayout.CENTER);

			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					selectLayer(idx);
				}
			});

			listPanel.add(row);
		}

		addButton.setEnabled(layers.size() < MAX_LAYERS);
		removeButton.setEnabled(layers.size() > 1 && selectedIndex >= 0);
		moveUpButton.setEnabled(selectedIndex >= 0 && selectedIndex < layers.size() - 1);
		moveDownButton.setEnabled(selectedIndex > 0);

		listPanel.revalidate();
		listPanel.repaint();
	}

	private void fireSelectionChanged()
	{
		if (onSelectionChanged != null) onSelectionChanged.run();
	}

	private void fireStructureChanged()
	{
		if (onStructureChanged != null) onStructureChanged.run();
	}
}
