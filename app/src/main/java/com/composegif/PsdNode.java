package com.composegif;

import java.util.ArrayList;
import java.util.List;

public class PsdNode
{
	final String name;
	final int imageIndex;       // metadata array index (reader index = imageIndex + 1)
	final boolean isGroup;
	final boolean isVisible;    // true = visible in Photoshop (flags bit 1 NOT set)
	final int opacity;          // 0-255
	final String blendMode;     // e.g. "norm"
	final int top, left, bottom, right;
	List<PsdNode> children;     // mutable during tree construction, frozen by buildTree()

	PsdNode(String name, int imageIndex, boolean isGroup, boolean isVisible,
			int opacity, String blendMode, int top, int left, int bottom, int right)
	{
		this.name = name;
		this.imageIndex = imageIndex;
		this.isGroup = isGroup;
		this.isVisible = isVisible;
		this.opacity = opacity;
		this.blendMode = blendMode;
		this.top = top;
		this.left = left;
		this.bottom = bottom;
		this.right = right;
		this.children = isGroup ? new ArrayList<>() : List.of();
	}
}
