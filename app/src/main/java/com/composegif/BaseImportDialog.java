package com.composegif;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Base class for import dialogs that let the user select items from a
 * multi-item container (APNG frames, PSD layers, TIFF pages, etc.).
 *
 * Provides the shared layout: selection panel (left) + preview (right),
 * Select All / None buttons, Frames / Layers radio, OK / Cancel.
 *
 * Subclasses must call {@link #init(JComponent)} at the end of their
 * constructor to assemble the layout.
 */
abstract class BaseImportDialog<R> extends JDialog
{
	public record ImportResult<R>(List<R> selected, ImportMode mode) {}

	protected final CheckerboardPreviewPanel previewArea = new CheckerboardPreviewPanel();
	private final String framesLabel;
	private final String layersLabel;
	private JRadioButton layersRadio;
	private boolean confirmed = false;

	protected BaseImportDialog(JFrame parent, String title,
		String framesLabel, String layersLabel)
	{
		super(parent, title, true);
		this.framesLabel = framesLabel;
		this.layersLabel = layersLabel;

		setSize(700, 500);
		setLocationRelativeTo(parent);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
	}

	/**
	 * Assembles the layout. Must be called at the end of the subclass constructor.
	 *
	 * @param selectionComponent the selection widget (e.g. JScrollPane wrapping a JList or JTree)
	 */
	protected void init(JComponent selectionComponent)
	{
		// Left panel: Select All/None buttons + selection component
		JPanel leftPanel = new JPanel(new BorderLayout(0, 4));
		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JButton selectAllBtn = new JButton("Select All");
		selectAllBtn.addActionListener(e -> {
			selectAll();
			onSelectionChanged();
		});
		JButton selectNoneBtn = new JButton("None");
		selectNoneBtn.addActionListener(e -> {
			selectNone();
			onSelectionChanged();
		});
		buttonRow.add(selectAllBtn);
		buttonRow.add(selectNoneBtn);
		leftPanel.add(buttonRow, BorderLayout.NORTH);
		leftPanel.add(selectionComponent, BorderLayout.CENTER);

		// Right panel: preview
		previewArea.setBorder(BorderFactory.createTitledBorder("Preview"));

		// Split: selection + preview
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, previewArea);
		splitPane.setDividerLocation(300);
		splitPane.setResizeWeight(0.4);

		// Bottom panel: radio buttons + OK/Cancel
		JPanel bottomPanel = new JPanel(new BorderLayout());
		JPanel radioPanel = new JPanel();
		radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.Y_AXIS));
		radioPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		JRadioButton framesRadio = new JRadioButton(framesLabel);
		layersRadio = new JRadioButton(layersLabel);
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

	/** Toggle all items to checked. */
	protected abstract void selectAll();

	/** Toggle all items to unchecked. */
	protected abstract void selectNone();

	/** Return the currently selected/checked items. */
	protected abstract List<R> getSelectedItems();

	/** Called after Select All / None. Subclasses should repaint and update preview. */
	protected abstract void onSelectionChanged();

	/**
	 * Shows this modal dialog and returns the result, or null if cancelled
	 * or nothing was selected.
	 */
	ImportResult<R> showDialog()
	{
		setVisible(true);

		if (!confirmed) return null;

		List<R> selected = getSelectedItems();
		if (selected.isEmpty()) return null;

		ImportMode mode = layersRadio.isSelected() ? ImportMode.LAYERS : ImportMode.FRAMES;
		return new ImportResult<>(selected, mode);
	}
}
