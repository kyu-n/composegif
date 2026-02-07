package com.composegif;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class MainFrame extends JFrame
{

	private final PreviewPanel previewPanel;
	private final JLabel outputInfoLabel;
	private final JComboBox<String> interpCombo;
	private final JComboBox<String> scaleCombo;
	private final JButton exportButton;
	private final JProgressBar progressBar;

	// Layer state
	private final LayerListPanel layerListPanel = new LayerListPanel();
	private final JSpinner delaySpinner;
	private final PalettePanel palettePanel;
	private final JLabel layerInfoLabel;
	private LayerState previouslySelectedLayer;

	// Center layout
	private final JSplitPane centerSplit;
	private final JPanel topCardPanel;
	private final CardLayout topCardLayout;
	private final DetailPanel detailPanel;
	private final JScrollPane ribbonScroll;
	private boolean inDetailMode;
	private int savedDividerSize = -1;
	private SwingWorker<?, ?> activePreviewWorker;

	private File lastDirectory;
	private File lastExportFile = new File("animation.gif");

	private static final String[] INTERP_LABELS = {"Nearest Neighbor", "Bilinear", "Bicubic"};
	private static final Object[] INTERP_VALUES =
	{
			RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR,
			RenderingHints.VALUE_INTERPOLATION_BICUBIC
	};

	private static final int MAX_SCALE = 16;

	public MainFrame()
	{
		super("ComposeGIF");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setMinimumSize(new Dimension(800, 600));

		previewPanel = new PreviewPanel();

		// --- Control panel (right sidebar) ---
		JPanel controlPanel = new JPanel(new GridBagLayout());
		controlPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(4, 4, 4, 4);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridx = 0;
		gbc.gridwidth = 2;
		int row = 0;

		// Layer list panel
		gbc.gridy = row++;
		gbc.fill = GridBagConstraints.BOTH;
		gbc.weighty = 0.3;
		layerListPanel.setPreferredSize(new Dimension(0, 200));
		controlPanel.add(layerListPanel, gbc);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.weighty = 0;

		// Separator
		gbc.gridy = row++;
		controlPanel.add(new JSeparator(), gbc);

		// Selected layer controls
		gbc.gridy = row++;
		controlPanel.add(new JLabel("Selected Layer"), gbc);

		gbc.gridwidth = 1;
		gbc.gridy = row;
		gbc.gridx = 0;
		JButton loadButton = new JButton("Load...");
		loadButton.addActionListener(e -> loadSelectedLayer());
		controlPanel.add(loadButton, gbc);
		gbc.gridx = 1;
		JButton clearButton = new JButton("Clear");
		clearButton.addActionListener(e -> clearSelectedLayer());
		controlPanel.add(clearButton, gbc);
		row++;

		gbc.gridy = row;
		gbc.gridx = 0;
		gbc.gridwidth = 2;
		JButton importPsdButton = new JButton("Import PSD...");
		importPsdButton.addActionListener(e -> importPsd());
		controlPanel.add(importPsdButton, gbc);
		row++;

		gbc.gridy = row;
		gbc.gridx = 0;
		gbc.gridwidth = 2;
		layerInfoLabel = new JLabel("empty");
		controlPanel.add(layerInfoLabel, gbc);
		row++;

		gbc.gridy = row;
		gbc.gridwidth = 1;
		gbc.gridx = 0;
		controlPanel.add(new JLabel("Delay (ms):"), gbc);
		delaySpinner = new JSpinner(new SpinnerNumberModel(100, 20, 10000, 10));
		delaySpinner.addChangeListener(e -> onDelayChanged());
		gbc.gridx = 1;
		controlPanel.add(delaySpinner, gbc);
		row++;

		gbc.gridy = row;
		gbc.gridx = 0;
		gbc.gridwidth = 2;
		palettePanel = new PalettePanel();
		palettePanel.setOnChange(this::updatePreview);
		controlPanel.add(palettePanel, gbc);
		row++;

		// Separator
		gbc.gridy = row++;
		controlPanel.add(new JSeparator(), gbc);

		// Output info
		outputInfoLabel = new JLabel("No frames loaded");
		gbc.gridy = row++;
		controlPanel.add(outputInfoLabel, gbc);

		// Interpolation
		gbc.gridwidth = 1;
		gbc.gridy = row;
		gbc.gridx = 0;
		controlPanel.add(new JLabel("Interpolation:"), gbc);
		interpCombo = new JComboBox<>(INTERP_LABELS);
		interpCombo.setSelectedIndex(0);
		interpCombo.addActionListener(e -> {
			previewPanel.setInterpolationHint(INTERP_VALUES[interpCombo.getSelectedIndex()]);
		});
		gbc.gridx = 1;
		controlPanel.add(interpCombo, gbc);
		row++;

		// Scale combo
		gbc.gridy = row;
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		controlPanel.add(new JLabel("Export Scale:"), gbc);
		String[] scaleLabels = new String[MAX_SCALE];
		for (int i = 0; i < MAX_SCALE; i++) scaleLabels[i] = (i + 1) + "x";
		scaleCombo = new JComboBox<>(scaleLabels);
		scaleCombo.setSelectedIndex(0);
		gbc.gridx = 1;
		controlPanel.add(scaleCombo, gbc);
		row++;

		// Export button
		gbc.gridy = row;
		gbc.gridx = 0;
		gbc.gridwidth = 2;
		exportButton = new JButton("Export GIF");
		exportButton.setEnabled(false);
		exportButton.addActionListener(e -> exportGif());
		controlPanel.add(exportButton, gbc);
		row++;

		// Progress bar
		gbc.gridy = row;
		progressBar = new JProgressBar(0, 100);
		progressBar.setStringPainted(true);
		progressBar.setString("");
		controlPanel.add(progressBar, gbc);
		row++;

		// Spacer
		gbc.gridy = row;
		gbc.weighty = 1.0;
		controlPanel.add(new JPanel(), gbc);

		// --- Center layout: JSplitPane with preview/detail on top, ribbon on bottom ---
		topCardLayout = new CardLayout();
		topCardPanel = new JPanel(topCardLayout);
		topCardPanel.add(previewPanel, "preview");
		detailPanel = new DetailPanel();
		topCardPanel.add(detailPanel, "detail");

		ribbonScroll = new JScrollPane(new JPanel(),
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

		centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topCardPanel, ribbonScroll);
		centerSplit.setResizeWeight(1.0);
		centerSplit.setContinuousLayout(true);

		// --- Wire layer list callbacks ---
		layerListPanel.setLayerInitializer(this::wireLayerCallbacks);
		layerListPanel.setOnSelectionChanged(this::onLayerSelectionChanged);
		layerListPanel.setOnStructureChanged(this::updatePreview);

		// Wire the first layer that already exists
		for (LayerState ls : layerListPanel.getLayers())
		{
			wireLayerCallbacks(ls);
		}

		// Initialize editor to first layer
		onLayerSelectionChanged();

		// --- Layout ---
		setLayout(new BorderLayout());
		add(centerSplit, BorderLayout.CENTER);

		JScrollPane controlScroll = new JScrollPane(controlPanel,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		controlScroll.setPreferredSize(new Dimension(296, 0));
		controlScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0,
				UIManager.getColor("Separator.foreground")));
		add(controlScroll, BorderLayout.EAST);

		pack();
		setSize(1280, 960);
		setLocationRelativeTo(null);
	}

	private void wireLayerCallbacks(LayerState ls)
	{
		ls.browser.setOnChange(() -> onBrowserChanged(ls));
		ls.browser.setOnDetailRequested(frameIndex -> showDetailView(frameIndex));
		ls.browser.setOnFrameSelected(frameIndex -> {
			if (inDetailMode) detailPanel.showFrame(ls.browser, frameIndex);
		});
	}

	// --- Preview/Detail mode switching ---

	private void showPreview()
	{
		inDetailMode = false;
		topCardLayout.show(topCardPanel, "preview");
	}

	private void showDetailView(int frameIndex)
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null || !ls.hasFrames()) return;
		inDetailMode = true;
		detailPanel.showFrame(ls.browser, frameIndex);
		topCardLayout.show(topCardPanel, "detail");
	}

	// --- Ribbon visibility ---

	private void hideRibbon()
	{
		ribbonScroll.setVisible(false);
		if (savedDividerSize < 0) savedDividerSize = centerSplit.getDividerSize();
		centerSplit.setDividerSize(0);
		centerSplit.setDividerLocation(1.0);
	}

	private void showRibbon()
	{
		ribbonScroll.setVisible(true);
		if (savedDividerSize > 0) centerSplit.setDividerSize(savedDividerSize);
		SwingUtilities.invokeLater(() ->
				centerSplit.setDividerLocation(centerSplit.getHeight() - 180));
	}

	// --- Layer selection ---

	private void onLayerSelectionChanged()
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null) return;

		// Return to preview if in detail mode
		if (inDetailMode) showPreview();

		// Persist outgoing layer's transparent colors
		if (previouslySelectedLayer != null)
		{
			previouslySelectedLayer.transparentColors.clear();
			previouslySelectedLayer.transparentColors.addAll(palettePanel.getTransparentColors());
		}
		previouslySelectedLayer = ls;

		// Update shared controls to reflect selected layer
		delaySpinner.setValue(ls.delayMs);
		layerInfoLabel.setText(ls.hasFrames() ? ls.layer.frames().size() + " frames" : "empty");

		// Swap palette panel content and restore transparent color selections
		if (ls.hasFrames())
		{
			List<IndexColorModel> colorModels = ls.browser.getFrames().stream()
					.map(fd -> (IndexColorModel) fd.image().getColorModel())
					.toList();
			palettePanel.setPalette(colorModels);
			palettePanel.setTransparentColors(ls.transparentColors);
		}
		else
		{
			palettePanel.setPalette(List.of());
		}

		// Swap ribbon content
		if (ls.hasFrames())
		{
			ribbonScroll.setViewportView(ls.browser);
			showRibbon();
		}
		else
		{
			ribbonScroll.setViewportView(new JPanel());
			hideRibbon();
		}

		updateDragOverlay();
	}

	private void updateDragOverlay()
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null || !ls.hasFrames())
		{
			previewPanel.clearDraggableLayer();
			return;
		}
		previewPanel.setDraggableLayer(ls.offsetX, ls.offsetY, ls.width, ls.height,
				(newX, newY) -> {
					ls.offsetX = newX;
					ls.offsetY = newY;
					updatePreview();
					updateDragOverlay();
				});
	}

	private void loadSelectedLayer()
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null) return;

		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(true);
		chooser.setDialogTitle("Select frame files (" + ls.name + ")");
		chooser.setFileFilter(new FileNameExtensionFilter(
				"Images (PNG, GIF, BMP, JPEG)", "png", "gif", "bmp", "jpg", "jpeg"));
		if (lastDirectory != null)
		{
			chooser.setCurrentDirectory(lastDirectory);
		}

		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

		File[] selectedFiles = chooser.getSelectedFiles();
		if (selectedFiles.length == 0) return;
		lastDirectory = selectedFiles[0].getParentFile();
		List<File> fileList = Arrays.asList(selectedFiles);

		new SwingWorker<FrameLoader.LoadResult, Void>()
		{
			@Override
			protected FrameLoader.LoadResult doInBackground() throws Exception
			{
				return FrameLoader.load(fileList);
			}

			@Override
			protected void done()
			{
				try
				{
					FrameLoader.LoadResult result = get();

					// If the source has embedded timing, use it
					if (result.extractedDelayMs() > 0)
					{
						ls.delayMs = result.extractedDelayMs();
						delaySpinner.setValue(ls.delayMs);
					}

					ls.width = result.width();
					ls.height = result.height();
					ls.layer = new Compositor.Layer(
							result.frames(), result.width(), result.height(), ls.delayMs);
					ls.browser.setFrames(result.frames());

					for (String w : result.warnings())
					{
						System.out.println("[warn] " + w);
					}

					onLayerSelectionChanged(); // refresh editor
					updatePreview();
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					if (cause instanceof OutOfMemoryError)
					{
						JOptionPane.showMessageDialog(MainFrame.this,
								"Not enough memory to load these frames.\n"
										+ "Increase the -Xmx value in the launcher or JVM arguments.",
								"Out of Memory", JOptionPane.ERROR_MESSAGE);
					}
					else
					{
						JOptionPane.showMessageDialog(MainFrame.this,
								cause.getMessage(), "Load Error", JOptionPane.ERROR_MESSAGE);
					}
				}
			}
		}.execute();
	}

	private void clearSelectedLayer()
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null || !ls.hasFrames()) return;

		ls.layer = null;
		ls.transparentColors.clear();
		ls.offsetX = 0;
		ls.offsetY = 0;
		ls.browser.setFrames(List.of());
		onLayerSelectionChanged();
		updatePreview();
	}

	private void importPsd()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		chooser.setDialogTitle("Select PSD file");
		chooser.setFileFilter(new FileNameExtensionFilter("Photoshop files (PSD)", "psd"));
		if (lastDirectory != null) chooser.setCurrentDirectory(lastDirectory);

		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

		File psdFile = chooser.getSelectedFile();
		lastDirectory = psdFile.getParentFile();

		// Parse tree off-EDT to avoid blocking UI on file I/O
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		new SwingWorker<PsdImporter.PsdTree, Void>()
		{
			@Override
			protected PsdImporter.PsdTree doInBackground() throws Exception
			{
				return PsdImporter.parseTree(psdFile);
			}

			@Override
			protected void done()
			{
				setCursor(Cursor.getDefaultCursor());
				PsdImporter.PsdTree tree;
				try
				{
					tree = get();
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					JOptionPane.showMessageDialog(MainFrame.this,
							cause.getMessage(), "PSD Error", JOptionPane.ERROR_MESSAGE);
					return;
				}

				importPsdWithTree(tree, psdFile.getName());
			}
		}.execute();
	}

	private void importPsdWithTree(PsdImporter.PsdTree tree, String filename)
	{
		try
		{
			PsdImportDialog.ImportResult result = PsdImportDialog.show(this, tree, filename);
			if (result == null)
			{
				try { tree.close(); } catch (IOException ignored) {}
				return;
			}

			List<PsdNode> selectedNodes = result.selectedNodes();
			PsdImportDialog.ImportMode mode = result.mode();

			// Flatten and quantize off-EDT with wait cursor
			setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
			new SwingWorker<List<PsdImportFrame>, Void>()
			{
				@Override
				protected List<PsdImportFrame> doInBackground() throws Exception
				{
					List<PsdImportFrame> frames = new ArrayList<>();
					for (PsdNode node : selectedNodes)
					{
						PsdImporter.FlattenedFrame flat = PsdImporter.flattenNode(tree, node);
						FrameLoader.QuantizeResult qr = FrameLoader.quantizeToIndexed(flat.image());
						frames.add(new PsdImportFrame(node.name, qr, flat.warnings()));
					}
					return frames;
				}

				@Override
				protected void done()
				{
					setCursor(Cursor.getDefaultCursor());
					try
					{
						List<PsdImportFrame> frames = get();
						tree.close();

						if (mode == PsdImportDialog.ImportMode.FRAMES)
						{
							importPsdAsFrames(frames);
						}
						else
						{
							importPsdAsLayers(frames);
						}
					}
					catch (Exception ex)
					{
						try { tree.close(); } catch (IOException ignored) {}
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						JOptionPane.showMessageDialog(MainFrame.this,
								"PSD import failed: " + cause.getMessage(),
								"Import Error", JOptionPane.ERROR_MESSAGE);
					}
				}
			}.execute();
		}
		catch (Exception ex)
		{
			try { tree.close(); } catch (IOException ignored) {}
			JOptionPane.showMessageDialog(this,
					"PSD import failed: " + ex.getMessage(),
					"Import Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private record PsdImportFrame(String name, FrameLoader.QuantizeResult quantized, List<String> warnings) {}

	private void importPsdAsFrames(List<PsdImportFrame> frames)
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null) return;

		List<FrameLoader.FrameData> frameDataList = new ArrayList<>();
		for (PsdImportFrame pf : frames)
		{
			frameDataList.add(new FrameLoader.FrameData(pf.quantized().image(), pf.quantized().transparentIndex()));
			for (String w : pf.warnings())
			{
				System.out.println("[warn] " + w);
			}
		}

		if (frameDataList.isEmpty()) return;

		int w = frameDataList.get(0).image().getWidth();
		int h = frameDataList.get(0).image().getHeight();

		ls.width = w;
		ls.height = h;
		ls.layer = new Compositor.Layer(frameDataList, w, h, ls.delayMs);
		ls.browser.setFrames(frameDataList);

		onLayerSelectionChanged();
		updatePreview();
	}

	private void importPsdAsLayers(List<PsdImportFrame> frames)
	{
		for (PsdImportFrame pf : frames)
		{
			FrameLoader.FrameData fd = new FrameLoader.FrameData(
					pf.quantized().image(), pf.quantized().transparentIndex());

			int w = fd.image().getWidth();
			int h = fd.image().getHeight();

			LayerState ls = layerListPanel.addLayer(pf.name());
			if (ls == null) break; // hit max layers

			ls.width = w;
			ls.height = h;
			ls.layer = new Compositor.Layer(List.of(fd), w, h, ls.delayMs);
			ls.browser.setFrames(List.of(fd));

			for (String warn : pf.warnings())
			{
				System.out.println("[warn] " + warn);
			}
		}

		onLayerSelectionChanged();
		updatePreview();
	}

	private void onBrowserChanged(LayerState ls)
	{
		List<FrameLoader.FrameData> frames = ls.browser.getFrames();

		if (!ls.browser.hasFrames())
		{
			ls.layer = null;

			// If in detail mode and all frames deleted, return to preview
			if (inDetailMode) showPreview();

			onLayerSelectionChanged();
			updatePreview();
			return;
		}

		boolean isSelected = (ls == layerListPanel.getSelectedLayer());
		Set<Integer> transparentColors = isSelected
				? palettePanel.getTransparentColors()
				: Set.copyOf(ls.transparentColors);
		ls.layer = new Compositor.Layer(frames, ls.width, ls.height, ls.delayMs, transparentColors);

		// Update shared controls if this is the selected layer
		if (isSelected)
		{
			layerInfoLabel.setText(frames.size() + " frames");

			List<IndexColorModel> colorModels = frames.stream()
					.map(fd -> (IndexColorModel) fd.image().getColorModel())
					.toList();
			palettePanel.setPalette(colorModels);
			palettePanel.setTransparentColors(ls.transparentColors);
		}

		updatePreview();
	}

	private void onDelayChanged()
	{
		LayerState ls = layerListPanel.getSelectedLayer();
		if (ls == null) return;

		int delayMs = (int) delaySpinner.getValue();
		ls.delayMs = delayMs;

		if (ls.hasFrames())
		{
			ls.layer = new Compositor.Layer(
					ls.layer.frames(), ls.layer.width(), ls.layer.height(),
					delayMs, palettePanel.getTransparentColors());
		}
		updatePreview();
	}

	private void syncSelectedLayerTransparency()
	{
		LayerState selected = layerListPanel.getSelectedLayer();
		if (selected == null || !selected.hasFrames()) return;

		Set<Integer> currentTransparent = palettePanel.getTransparentColors();
		selected.transparentColors.clear();
		selected.transparentColors.addAll(currentTransparent);
		selected.layer = new Compositor.Layer(
				selected.layer.frames(), selected.layer.width(), selected.layer.height(),
				selected.delayMs, currentTransparent);
	}

	private List<Compositor.Layer> buildEffectiveLayers()
	{
		syncSelectedLayerTransparency();

		List<Compositor.Layer> effectiveLayers = new ArrayList<>();
		for (LayerState ls : layerListPanel.getLayers())
		{
			if (!ls.hasFrames() || !ls.visible) continue;
			effectiveLayers.add(ls.layer.withOffset(ls.offsetX, ls.offsetY));
		}
		return effectiveLayers;
	}

	private void updatePreview()
	{
		// Cancel any in-progress background compositing
		if (activePreviewWorker != null)
		{
			activePreviewWorker.cancel(true);
			activePreviewWorker = null;
		}

		List<LayerState> allLayers = layerListPanel.getLayers();
		boolean anyLoaded = allLayers.stream().anyMatch(LayerState::hasFrames);

		if (!anyLoaded)
		{
			previewPanel.setFrames(null);
			outputInfoLabel.setText("No frames loaded");
			exportButton.setEnabled(false);
			return;
		}

		List<Compositor.Layer> effectiveLayers = buildEffectiveLayers();

		if (effectiveLayers.isEmpty())
		{
			// All layers hidden -- produce transparent frame
			LayerState ref = allLayers.stream()
					.filter(LayerState::hasFrames).findFirst().orElse(null);
			if (ref == null)
			{
				previewPanel.setFrames(null);
				outputInfoLabel.setText("No frames loaded");
				exportButton.setEnabled(false);
				return;
			}
			Compositor.FlattenResult result = Compositor.generateTransparentFrames(ref.layer);
			List<BufferedImage> images = result.frames().stream()
					.map(FrameLoader.FrameData::image).toList();
			previewPanel.setFrames(images);
			previewPanel.setDelay(result.delayMs());
			outputInfoLabel.setText("All layers hidden");
			exportButton.setEnabled(false);
			return;
		}

		// Run compositor off-EDT to avoid freezing the animation timer
		SwingWorker<Compositor.FlattenResult, Void> worker =
				new SwingWorker<>()
		{
			@Override
			protected Compositor.FlattenResult doInBackground() throws Exception
			{
				return Compositor.flatten(effectiveLayers);
			}

			@Override
			protected void done()
			{
				if (isCancelled()) return;
				try
				{
					Compositor.FlattenResult result = get();

					List<BufferedImage> images = result.frames().stream()
							.map(FrameLoader.FrameData::image).toList();
					previewPanel.setFrames(images);
					previewPanel.setDelay(result.delayMs());

					outputInfoLabel.setText(result.frames().size() + " output frames @ "
							+ result.width() + "\u00d7" + result.height()
							+ ", " + result.delayMs() + "ms tick");
					exportButton.setEnabled(true);
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					previewPanel.setFrames(null);
					outputInfoLabel.setText("Error: " + cause.getMessage());
					exportButton.setEnabled(false);
				}
				if (activePreviewWorker == this) activePreviewWorker = null;
			}
		};
		activePreviewWorker = worker;
		worker.execute();
	}

	private void exportGif()
	{
		List<Compositor.Layer> effectiveLayers = buildEffectiveLayers();

		if (effectiveLayers.isEmpty()) return;

		Compositor.FlattenResult flat;
		try
		{
			flat = Compositor.flatten(effectiveLayers);
		}
		catch (IOException ex)
		{
			JOptionPane.showMessageDialog(this, ex.getMessage(), "Export Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		int delayMs = flat.delayMs();
		if (delayMs % 10 != 0)
		{
			JOptionPane.showMessageDialog(this,
					"Tick delay " + delayMs + "ms is not evenly divisible by 10.\n"
							+ "GIF delays are in centiseconds (10ms units). "
							+ "The value will be rounded to " + (Math.round(delayMs / 10.0f) * 10) + "ms.",
					"Delay Rounding", JOptionPane.WARNING_MESSAGE);
		}

		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save animated GIF");
		chooser.setFileFilter(new FileNameExtensionFilter("GIF files", "gif"));
		if (lastDirectory != null)
		{
			chooser.setCurrentDirectory(lastDirectory);
		}
		chooser.setSelectedFile(lastExportFile);

		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

		File output = chooser.getSelectedFile();
		if (!output.getName().toLowerCase().endsWith(".gif"))
		{
			output = new File(output.getAbsolutePath() + ".gif");
		}
		lastExportFile = new File(output.getName());

		Object interpHint = INTERP_VALUES[interpCombo.getSelectedIndex()];
		int scale = scaleCombo.getSelectedIndex() + 1;

		exportButton.setEnabled(false);
		progressBar.setValue(0);
		progressBar.setString("Exporting...");

		File finalOutput = output;
		List<FrameLoader.FrameData> framesToEncode = flat.frames();
		new SwingWorker<Void, Integer>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				GifEncoder.encode(framesToEncode, finalOutput, scale, interpHint, delayMs,
						(current, total) -> {
							int pct = (int) ((current * 100L) / total);
							publish(pct);
						});
				return null;
			}

			@Override
			protected void process(List<Integer> chunks)
			{
				int latest = chunks.get(chunks.size() - 1);
				progressBar.setValue(latest);
				progressBar.setString(latest + "%");
			}

			@Override
			protected void done()
			{
				exportButton.setEnabled(true);
				try
				{
					get();
					progressBar.setValue(100);
					progressBar.setString("Done!");
					JOptionPane.showMessageDialog(MainFrame.this,
							"GIF exported to:\n" + finalOutput.getAbsolutePath(),
							"Export Complete", JOptionPane.INFORMATION_MESSAGE);
				}
				catch (Exception ex)
				{
					progressBar.setValue(0);
					progressBar.setString("Failed");
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					JOptionPane.showMessageDialog(MainFrame.this,
							"Export failed: " + cause.getMessage(),
							"Export Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	// --- Detail Panel (full-size frame view shown in top card) ---

	private class DetailPanel extends JPanel
	{

		private final JLabel frameLabel = new JLabel("", SwingConstants.CENTER);
		private final JButton hideBtn = new JButton("Hide");
		private final JPanel imagePanel;
		private FrameBrowserPanel currentBrowser;
		private int detailIndex = -1;

		DetailPanel()
		{
			setLayout(new BorderLayout());

			imagePanel = new JPanel()
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					super.paintComponent(g);
					if (currentBrowser == null || detailIndex < 0
							|| detailIndex >= currentBrowser.getFrameCount()) return;

					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
							RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

					BufferedImage img = currentBrowser.getFrame(detailIndex).image();
					int imgW = img.getWidth();
					int imgH = img.getHeight();

					int scale = Math.max(1, Math.min(Math.min(getWidth() / imgW, getHeight() / imgH), MAX_SCALE));
					int drawW = imgW * scale;
					int drawH = imgH * scale;
					int drawX = (getWidth() - drawW) / 2;
					int drawY = (getHeight() - drawH) / 2;

					FrameBrowserPanel.drawCheckerboard(g2, drawX, drawY, drawW, drawH);
					g2.drawImage(img, drawX, drawY, drawW, drawH, null);

					// Hidden overlay
					if (currentBrowser.isHidden(detailIndex))
					{
						Composite oldComp = g2.getComposite();
						g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
						g2.setColor(getBackground());
						g2.fillRect(drawX, drawY, drawW, drawH);
						g2.setComposite(oldComp);
					}
				}
			};
			add(imagePanel, BorderLayout.CENTER);

			JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
			JButton backBtn = new JButton("Back");
			backBtn.addActionListener(e -> showPreview());
			JButton prevBtn = new JButton("<");
			prevBtn.addActionListener(e -> {
				if (currentBrowser != null && detailIndex > 0)
				{
					detailIndex--;
					updateLabel();
					imagePanel.repaint();
				}
			});
			JButton nextBtn = new JButton(">");
			nextBtn.addActionListener(e -> {
				if (currentBrowser != null && detailIndex < currentBrowser.getFrameCount() - 1)
				{
					detailIndex++;
					updateLabel();
					imagePanel.repaint();
				}
			});
			hideBtn.addActionListener(e -> {
				if (currentBrowser != null && detailIndex >= 0
						&& detailIndex < currentBrowser.getFrameCount())
				{
					currentBrowser.toggleHidden(detailIndex);
					updateLabel();
					imagePanel.repaint();
				}
			});
			JButton duplicateBtn = new JButton("Duplicate");
			duplicateBtn.addActionListener(e -> {
				if (currentBrowser != null && detailIndex >= 0
						&& detailIndex < currentBrowser.getFrameCount())
				{
					currentBrowser.duplicateFrame(detailIndex);
					detailIndex++;
					updateLabel();
					imagePanel.repaint();
				}
			});
			JButton deleteBtn = new JButton("Delete");
			deleteBtn.addActionListener(e -> {
				if (currentBrowser != null && detailIndex >= 0
						&& detailIndex < currentBrowser.getFrameCount())
				{
					currentBrowser.deleteFrame(detailIndex);
					if (!currentBrowser.hasFrames())
					{
						showPreview();
					}
					else
					{
						if (detailIndex >= currentBrowser.getFrameCount())
						{
							detailIndex = currentBrowser.getFrameCount() - 1;
						}
						updateLabel();
						imagePanel.repaint();
					}
				}
			});

			toolbar.add(backBtn);
			toolbar.add(prevBtn);
			toolbar.add(frameLabel);
			toolbar.add(nextBtn);
			toolbar.add(hideBtn);
			toolbar.add(duplicateBtn);
			toolbar.add(deleteBtn);
			add(toolbar, BorderLayout.NORTH);

			// Escape key binding to return to preview
			getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
					.put(KeyStroke.getKeyStroke("ESCAPE"), "backToPreview");
			getActionMap().put("backToPreview", new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					showPreview();
				}
			});
		}

		void showFrame(FrameBrowserPanel browser, int frameIndex)
		{
			this.currentBrowser = browser;
			this.detailIndex = frameIndex;
			updateLabel();
			imagePanel.repaint();
		}

		private void updateLabel()
		{
			if (currentBrowser != null && detailIndex >= 0
					&& detailIndex < currentBrowser.getFrameCount())
			{
				frameLabel.setText("Frame " + (detailIndex + 1) + " of " + currentBrowser.getFrameCount());
				hideBtn.setText(currentBrowser.isHidden(detailIndex) ? "Show" : "Hide");
			}
			else
			{
				frameLabel.setText("");
				hideBtn.setText("Hide");
			}
		}
	}
}
