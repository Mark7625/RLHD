/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Actor;
import net.runelite.api.Model;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.definition.ParticleDefinition;

import static net.runelite.api.Perspective.COSINE;
import static net.runelite.api.Perspective.SINE;
import static rs117.hd.utils.MathUtils.*;

/**
 * Particle emitter bound to one or more vertices on an actor's animated model.
 * Spawn positions follow the model each frame so attachments move with animations.
 */
@Getter
public class ModelAttachmentEmitter extends ParticleEmitter {
	public static final class VertexEmitter {
		public final int absoluteVertexIndex;
		public final String particleId;
		public final ParticleEmitter emitter;

		VertexEmitter(int absoluteVertexIndex, String particleId, ParticleEmitter emitter) {
			this.absoluteVertexIndex = absoluteVertexIndex;
			this.particleId = particleId;
			this.emitter = emitter;
		}
	}

	private final Actor actor;
	private final int itemId;
	private final List<VertexEmitter> vertexEmitters = new ArrayList<>();

	@Setter
	private int baseVertexIndex = -1;

	@Setter
	private int drawX;
	@Setter
	private int drawY;
	@Setter
	private int drawZ;
	@Setter
	private int drawOrientation;
	@Setter
	private int drawPlane;

	@Nullable
	private Model frameModel;

	private long lastAttachmentTickCycle = -1;

	public ModelAttachmentEmitter(Actor actor, int itemId, WorldPoint worldPoint) {
		super();
		this.actor = actor;
		this.itemId = itemId;
		at(worldPoint);
	}

	public void setAttachments(
		int baseVertexIndex,
		List<ModelAttachmentVertexBinding> bindings,
		ParticleManager manager
	) {
		this.baseVertexIndex = baseVertexIndex;
		vertexEmitters.clear();
		lastAttachmentTickCycle = -1;
		if (bindings == null || baseVertexIndex < 0)
			return;

		for (ModelAttachmentVertexBinding binding : bindings) {
			if (binding == null || binding.getParticleId() == null || binding.getParticleId().isEmpty())
				continue;
			ParticleDefinition def = manager.getDefinition(binding.getParticleId());
			if (def == null)
				continue;
			ParticleEmitter vertexEmitter = manager.createAttachmentVertexEmitter(def, getWorldPoint());
			vertexEmitter.particleId(def.id);
			vertexEmitters.add(new VertexEmitter(
				baseVertexIndex + binding.getVertexIndex(),
				binding.getParticleId().toUpperCase(),
				vertexEmitter
			));
		}
	}

	public boolean isReady() {
		return baseVertexIndex >= 0 && !vertexEmitters.isEmpty();
	}

	public void setFrameModel(@Nullable Model model) {
		this.frameModel = model;
	}

	public boolean resolveVertexPosition(int vertexIndex, float[] out) {
		if (out == null || out.length < 3)
			return false;

		Model model = frameModel;
		if (model == null)
			model = actor.getModel();
		if (model == null || vertexIndex < 0 || vertexIndex >= model.getVerticesCount())
			return false;

		float vertexX = model.getVerticesX()[vertexIndex];
		float vertexY = model.getVerticesY()[vertexIndex];
		float vertexZ = model.getVerticesZ()[vertexIndex];

		int orientation = mod(drawOrientation, 2048);
		if (orientation != 0) {
			float sin = SINE[orientation] / 65536f;
			float cos = COSINE[orientation] / 65536f;
			float rotatedX = vertexZ * sin + vertexX * cos;
			float rotatedZ = vertexZ * cos - vertexX * sin;
			vertexX = rotatedX;
			vertexZ = rotatedZ;
		}

		out[0] = drawX + vertexX;
		out[1] = drawY + vertexY;
		out[2] = drawZ + vertexZ;
		return true;
	}

	public void tickAttachments(float dt, long gameCycle, ParticleManager manager) {
		if (!isActive() || !isReady())
			return;

		Model model = frameModel;
		if (model == null)
			model = actor.getModel();
		if (model == null)
			return;

		if (lastAttachmentTickCycle == gameCycle)
			return;
		lastAttachmentTickCycle = gameCycle;

		ParticleBuffer buf = manager.getParticleBuffer();
		int maxParticles = manager.getMaxParticles();
		float[] pos = new float[3];

		for (VertexEmitter vertexEmitter : vertexEmitters) {
			if (!resolveVertexPosition(vertexEmitter.absoluteVertexIndex, pos))
				continue;

			ParticleEmitter emitter = vertexEmitter.emitter;
			if (!emitter.isEmissionAllowedAtCycle(gameCycle))
				continue;

			int toSpawn = emitter.advanceEmission(dt);
			for (int i = 0; i < toSpawn && buf.count < maxParticles; i++) {
				emitter.spawnIntoBuffer(buf, pos[0], pos[1], pos[2], drawPlane);
			}
		}
	}

	@Override
	public boolean isActive() {
		return super.isActive() && actor != null && !actor.isDead();
	}
}
