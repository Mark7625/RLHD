/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import javax.annotation.Nullable;
import lombok.Value;

/** Maps a vertex index (relative to the detected equipment mesh start) to a particle definition. */
@Value
public class ModelAttachmentVertexBinding {
	int vertexIndex;
	@Nullable
	String particleId;
}
