package com.composegif;

import java.util.List;

record ImportedFrame(
	String name,
	FrameLoader.FrameData frame,
	int delayMs,
	List<String> warnings)
{
	ImportedFrame(String name, FrameLoader.FrameData frame, int delayMs)
	{
		this(name, frame, delayMs, List.of());
	}

	ImportedFrame(String name, FrameLoader.FrameData frame)
	{
		this(name, frame, -1, List.of());
	}
}
