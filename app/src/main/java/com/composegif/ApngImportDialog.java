package com.composegif;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class ApngImportDialog extends JDialog
{
	public enum ImportMode { FRAMES, LAYERS }

	public record ImportResult(List<ApngReader.ApngFrame> selectedFrames, ImportMode mode) {}

	private final ApngReader.ApngResult apng;
	private final boolean[] checked;
	private final JList<String> frameList;
	private final DefaultListModel<String> listModel;
	private final CheckerboardPreviewPanel previewArea;
	private final JRadioButton framesRadio;
	private final JRadioButton layersRadio;
	private boolean confirmed = false;

	private ApngImportDialog(JFrame parent, ApngReader.ApngResult apng, String filename)
	{
		super(parent, "Import APNG: " + filename, true);
		this.apng = apng;
		this.checked = new boolean[apng.frames().size()];
		for (int i = 0; i < checked.length; i++)
		{
			checked[i] = true;
		}

		setSize(700, 500);
		setLocationRelativeTo(parent);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		// Build list model
		listModel = new DefaultListModel<>();
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

		// Left panel: Select All/None buttons + list
		JPanel leftPanel = new JPanel(new BorderLayout(0, 4));
		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JButton selectAllBtn = new JButton("Select All");
		selectAllBtn.addActionListener(e -> {
			for (int i = 0; i < checked.length; i++) checked[i] = true;
			frameList.repaint();
		});
		JButton selectNoneBtn = new JButton("None");
		selectNoneBtn.addActionListener(e -> {
			for (int i = 0; i < checked.length; i++) checked[i] = false;
			frameList.repaint();
		});
		buttonRow.add(selectAllBtn);
		buttonRow.add(selectNoneBtn);
		leftPanel.add(buttonRow, BorderLayout.NORTH);
		leftPanel.add(new JScrollPane(frameList), BorderLayout.CENTER);

		// Right panel: preview
		previewArea = new CheckerboardPreviewPanel();
		previewArea.setBorder(BorderFactory.createTitledBorder("Preview"));

		// Top split: list + preview
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, previewArea);
		splitPane.setDividerLocation(300);
		splitPane.setResizeWeight(0.4);

		// Bottom panel: radio buttons + OK/Cancel
		JPanel bottomPanel = new JPanel(new BorderLayout());
		JPanel radioPanel = new JPanel();
		radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.Y_AXIS));
		radioPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		framesRadio = new JRadioButton("Import as frames (into current layer)");
		layersRadio = new JRadioButton("Import as layers (one per selection)");
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
	}

	/**
	 * Shows the dialog and returns the result, or null if cancelled.
	 */
	public static ImportResult show(JFrame parent, ApngReader.ApngResult apng, String filename)
	{
		ApngImportDialog dialog = new ApngImportDialog(parent, apng, filename);
		dialog.setVisible(true);

		if (!dialog.confirmed) return null;

		List<ApngReader.ApngFrame> selected = dialog.getSelectedFrames();
		if (selected.isEmpty()) return null;

		ImportMode mode = dialog.layersRadio.isSelected() ? ImportMode.LAYERS : ImportMode.FRAMES;
		return new ImportResult(selected, mode);
	}

	private List<ApngReader.ApngFrame> getSelectedFrames()
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
