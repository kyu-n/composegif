package com.composegif;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mutable per-layer state. Holds the layer data, UI components, and metadata.
 */
public class LayerState
{
	final String name;
	final FrameBrowserPanel browser;
	int delayMs;
	boolean visible;

	// Per-layer transparent color selections (persisted across layer switches)
	final Set<Integer> transparentColors = new LinkedHashSet<>();

	// Set when frames are loaded, null when cleared
	Compositor.Layer layer;

	// Dimensions from first load
	int width;
	int height;

	// Per-layer canvas offset (set by drag in preview)
	int offsetX;
	int offsetY;

	LayerState(String name)
	{
		this.name = name;
		this.browser = new FrameBrowserPanel();
		this.delayMs = 100;
		this.visible = true;
	}

	boolean hasFrames()
	{
		return layer != null && browser.hasFrames();
	}
}
