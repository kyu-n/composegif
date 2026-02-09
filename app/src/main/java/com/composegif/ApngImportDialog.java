package com.composegif;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ApngImportDialog extends BaseImportDialog<ApngReader.ApngFrame>
{
	private final ApngReader.ApngResult apng;
	private final boolean[] checked;
	private final JList<String> frameList;

	private ApngImportDialog(JFrame parent, ApngReader.ApngResult apng, String filename)
	{
		super(parent, "Import APNG: " + filename,
			"Import as frames (into current layer)",
			"Import as layers (one per selection)");

		this.apng = apng;
		this.checked = new boolean[apng.frames().size()];
		for (int i = 0; i < checked.length; i++)
		{
			checked[i] = true;
		}

		// Build list model
		DefaultListModel<String> listModel = new DefaultListModel<>();
		for (int i = 0; i < apng.frames().size(); i++)
		{
			ApngReader.ApngFrame frame = apng.frames().get(i);
			listModel.addElement("Frame " + (i + 1) + " (" + frame.delayMs() + "ms)");
		}

		// Create list
		frameList = new JList<>(listModel);
		frameList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		frameList.setCellRenderer(new CheckboxListCellRenderer());
		frameList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int index = frameList.locationToIndex(e.getPoint());
				if (index < 0) return;
				// Only toggle checkbox if click is in the checkbox area (left ~20px)
				if (e.getPoint().x > 20) return;
				checked[index] = !checked[index];
				frameList.repaint();
			}
		});
		frameList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				updatePreview();
			}
		});

		// Select first item to show initial preview
		if (!apng.frames().isEmpty())
		{
			frameList.setSelectedIndex(0);
		}

		init(new JScrollPane(frameList));
	}

	public static ImportResult<ApngReader.ApngFrame> show(JFrame parent, ApngReader.ApngResult apng, String filename)
	{
		ApngImportDialog dialog = new ApngImportDialog(parent, apng, filename);
		return dialog.showDialog();
	}

	@Override
	protected void selectAll()
	{
		for (int i = 0; i < checked.length; i++) checked[i] = true;
	}

	@Override
	protected void selectNone()
	{
		for (int i = 0; i < checked.length; i++) checked[i] = false;
	}

	@Override
	protected List<ApngReader.ApngFrame> getSelectedItems()
	{
		List<ApngReader.ApngFrame> result = new ArrayList<>();
		for (int i = 0; i < checked.length; i++)
		{
			if (checked[i])
			{
				result.add(apng.frames().get(i));
			}
		}
		return result;
	}

	@Override
	protected void onSelectionChanged()
	{
		frameList.repaint();
		updatePreview();
	}

	private void updatePreview()
	{
		int index = frameList.getSelectedIndex();
		if (index < 0 || index >= apng.frames().size())
		{
			previewArea.setImage(null);
			return;
		}
		previewArea.setImage(apng.frames().get(index).image());
	}

	// --- Checkbox list cell renderer ---

	private class CheckboxListCellRenderer implements ListCellRenderer<String>
	{
		private final JCheckBox checkBox = new JCheckBox();
		private final JPanel panel = new JPanel(new BorderLayout());

		CheckboxListCellRenderer()
		{
			panel.setOpaque(true);
			checkBox.setOpaque(false);
			panel.add(checkBox, BorderLayout.CENTER);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends String> list, String value,
			int index, boolean isSelected, boolean cellHasFocus)
		{
			checkBox.setText(value);
			checkBox.setSelected(index >= 0 && index < checked.length && checked[index]);

			if (isSelected)
			{
				panel.setBackground(UIManager.getColor("List.selectionBackground"));
				checkBox.setForeground(UIManager.getColor("List.selectionForeground"));
			}
			else
			{
				panel.setBackground(UIManager.getColor("List.background"));
				checkBox.setForeground(UIManager.getColor("List.foreground"));
			}

			return panel;
		}
	}
}
