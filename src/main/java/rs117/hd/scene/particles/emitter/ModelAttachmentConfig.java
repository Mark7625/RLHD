/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.kit.KitType;

/** Config for attaching particle emitters to vertices on a worn equipment model. */
@Value
public class ModelAttachmentConfig {
	int itemId;
	KitType kitType;
	/** Worn/equipped model id from cache (e.g. rl model). When set, used for vertex fingerprinting. */
	int wearModelId;
	@Nullable
	List<String> globalEffectors;
	@Nullable
	List<String> embeddedEffectors;
	@Nullable
	List<String> localEffectorFilter;
	List<ModelAttachmentVertexBinding> attachments;
}
