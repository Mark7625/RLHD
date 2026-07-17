/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.particles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.GLFence;
import rs117.hd.opengl.shader.ParticleShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.pass.ScenePass;
import rs117.hd.renderer.zone.pass.ScenePassContext;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GLBuffer;

import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL30.GL_MAP_INVALIDATE_RANGE_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glMapBufferRange;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_PARTICLE;

@Slf4j
@Singleton
public class ParticlePass implements ScenePass {

	private static final int MAX_DRAWN = ParticlesManager.MAX_DRAWN_PARTICLES;
	private static final int INSTANCE_BUFFER_COUNT = 3;
	private static final int QUAD_VERTS = 6;
	private static final int FLOATS_PER_INSTANCE = 14;
	private static final int INSTANCE_STRIDE_BYTES = 64;
	private static final int INSTANCE_PADDING_BYTES = INSTANCE_STRIDE_BYTES - FLOATS_PER_INSTANCE * 4;
	private static final float[] PARTICLE_QUAD_CORNERS = {
		-1, -1,  1, -1,  1, 1,
		-1, -1,  1, 1,  -1, 1
	};

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ParticlesManager particlesManager;

	@Inject
	private ParticleShaderProgram particleProgram;

	@Inject
	private ParticleTextureLoader textureLoader;

	private int vaoParticles;
	private int vboParticleQuad;
	private int[] vboParticleInstances;
	private GLBuffer[] particleInstanceBuffers;
	private final GLFence[] instanceFences = new GLFence[INSTANCE_BUFFER_COUNT];
	private int instanceBufferSlot;
	private FloatBuffer particleStagingBuffer;
	private final float[] particleDistSq = new float[MAX_DRAWN];
	private final int[] particleSortOrder = new int[MAX_DRAWN];
	private final float[] colorScratch = new float[4];
	private final Particle[] visibleParticles = new Particle[MAX_DRAWN];
	private ByteBuffer batchUploadBuffer;

	@Getter
	private int lastDrawn;

	@Override
	public String passName() {
		return "Particles";
	}

	@Override
	public boolean shouldDraw(ScenePassContext ctx) {
		return client.isGpu() && !plugin.isLoadingScene();
	}

	@Override
	public void initialize() {
		textureLoader.initialize();
		vaoParticles = glGenVertexArrays();
		vboParticleQuad = glGenBuffers();
		long instanceVboBytes = (long) MAX_DRAWN * INSTANCE_STRIDE_BYTES;
		vboParticleInstances = new int[INSTANCE_BUFFER_COUNT];
		if (HdPlugin.SUPPORTS_STORAGE_BUFFERS) {
			particleInstanceBuffers = new GLBuffer[INSTANCE_BUFFER_COUNT];
			for (int i = 0; i < INSTANCE_BUFFER_COUNT; i++) {
				particleInstanceBuffers[i] = new GLBuffer("particle instances " + i, GL_ARRAY_BUFFER, GL_STREAM_DRAW, GLBuffer.STORAGE_PERSISTENT | GLBuffer.STORAGE_WRITE);
				particleInstanceBuffers[i].initialize(instanceVboBytes);
				vboParticleInstances[i] = particleInstanceBuffers[i].id;
				instanceFences[i] = new GLFence();
			}
		} else {
			particleInstanceBuffers = null;
			for (int i = 0; i < INSTANCE_BUFFER_COUNT; i++) {
				vboParticleInstances[i] = glGenBuffers();
				glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances[i]);
				glBufferData(GL_ARRAY_BUFFER, instanceVboBytes, GL_STREAM_DRAW);
				instanceFences[i] = new GLFence();
			}
			glBindBuffer(GL_ARRAY_BUFFER, 0);
		}

		FloatBuffer quadBuffer = BufferUtils.createFloatBuffer(PARTICLE_QUAD_CORNERS.length).put(PARTICLE_QUAD_CORNERS).flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleQuad);
		glBufferData(GL_ARRAY_BUFFER, quadBuffer, GL_STATIC_DRAW);
		glBindVertexArray(vaoParticles);
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleQuad);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
		glVertexAttribDivisor(0, 0);
		bindInstanceBuffer(0);
		setupInstanceAttribs();
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		particleStagingBuffer = BufferUtils.createFloatBuffer(MAX_DRAWN * FLOATS_PER_INSTANCE);
		batchUploadBuffer = BufferUtils.createByteBuffer(MAX_DRAWN * INSTANCE_STRIDE_BYTES);
	}

	@Override
	public void destroy() {
		if (vaoParticles != 0) {
			glDeleteVertexArrays(vaoParticles);
			vaoParticles = 0;
		}
		if (vboParticleQuad != 0) {
			glDeleteBuffers(vboParticleQuad);
			vboParticleQuad = 0;
		}
		if (vboParticleInstances != null) {
			for (int i = 0; i < INSTANCE_BUFFER_COUNT; i++) {
				if (particleInstanceBuffers != null) {
					particleInstanceBuffers[i].destroy();
				} else if (vboParticleInstances[i] != 0) {
					glDeleteBuffers(vboParticleInstances[i]);
				}
				vboParticleInstances[i] = 0;
			}
			vboParticleInstances = null;
			particleInstanceBuffers = null;
		}
		particleStagingBuffer = null;
		batchUploadBuffer = null;
		textureLoader.destroy();
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		particleProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		particleProgram.destroy();
	}

	@Override
	public void draw(ScenePassContext ctx) {
		if (vaoParticles == 0 || !particleProgram.isValid())
			return;

		List<Particle> particles = particlesManager.getParticleSystem().getParticles();
		int instanceCount = prepareInstances(particles);
		lastDrawn = instanceCount;
		particlesManager.recordRenderStats(instanceCount, MAX_DRAWN);
		if (instanceCount == 0)
			return;

		var renderState = ctx.getRenderState();
		renderState.program.set(particleProgram);
		renderState.enable.set(GL_BLEND);
		renderState.blendFunc.reset();
		renderState.blendFunc.set(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		renderState.disable.set(GL_CULL_FACE);
		renderState.depthMask.set(false);
		renderState.apply();

		glActiveTexture(TEXTURE_UNIT_PARTICLE);
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureLoader.getTextureArrayId());
		particleProgram.setParticleTextureUnit(TEXTURE_UNIT_PARTICLE);
		glBindVertexArray(vaoParticles);

		ctx.beginTimer(Timer.RENDER_PARTICLES);
		int slot = instanceBufferSlot;
		if (particleInstanceBuffers != null)
			instanceFences[slot].sync();
		uploadInstanceDataToVbo(instanceCount, slot);
		bindInstanceBuffer(slot);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 0);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 12);
		glVertexAttribPointer(3, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 28);
		glVertexAttribPointer(4, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 32);
		glVertexAttribPointer(5, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 36);
		glVertexAttribPointer(6, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 40);
		glVertexAttribPointer(7, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 44);
		glVertexAttribPointer(8, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 48);
		glVertexAttribPointer(9, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 52);
		glDrawArraysInstanced(GL_TRIANGLES, 0, QUAD_VERTS, instanceCount);
		if (particleInstanceBuffers != null)
			instanceFences[slot].handle = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		instanceBufferSlot = (instanceBufferSlot + 1) % INSTANCE_BUFFER_COUNT;
		ctx.endTimer(Timer.RENDER_PARTICLES);

		glBindVertexArray(0);
	}

	@Override
	public void afterDraw(ScenePassContext ctx) {
		ctx.getRenderState().depthMask.set(true);
	}

	private int prepareInstances(List<Particle> particles) {
		WorldView wv = client.getWorldView(particlesManager.getAnchorWorldView());
		if (wv == null || particles.isEmpty())
			return 0;

		float maxDist = plugin.getDrawDistance() * LOCAL_TILE_SIZE;
		float maxDistSq = maxDist * maxDist;
		float camX = plugin.cameraPosition[0];
		float camY = plugin.cameraPosition[1];
		float camZ = plugin.cameraPosition[2];
		float[][] frustum = plugin.cameraFrustum;

		int count = 0;
		for (Particle p : particles) {
			int sceneX = ((int) p.getX()) >> 7;
			int sceneY = ((int) p.getY()) >> 7;
			if (sceneX < 0 || sceneX >= wv.getSizeX() || sceneY < 0 || sceneY >= wv.getSizeY())
			{
				if (!p.isGroundClip())
				{
					p.kill();
				}
				continue;
			}

			float px = p.getX() + plugin.cameraShift[0];
			float py = p.getZ();
			float pz = p.getY() + plugin.cameraShift[1];
			float dx = px - camX;
			float dy = py - camY;
			float dz = pz - camZ;
			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > maxDistSq)
				continue;

			float radius = p.getStyle().getGpuRadius(p);
			if (!HDUtils.isSphereIntersectingFrustum(px, py, pz, radius, frustum, frustum.length))
				continue;

			if (count < MAX_DRAWN)
			{
				visibleParticles[count] = p;
				particleDistSq[count] = distSq;
				count++;
			}
			else
			{
				int farthest = 0;
				for (int i = 1; i < MAX_DRAWN; i++)
				{
					if (particleDistSq[i] > particleDistSq[farthest])
					{
						farthest = i;
					}
				}
				if (distSq < particleDistSq[farthest])
				{
					visibleParticles[farthest] = p;
					particleDistSq[farthest] = distSq;
				}
			}
		}

		if (count == 0)
			return 0;

		for (int i = 0; i < count; i++)
			particleSortOrder[i] = i;
		sortIndicesByDistance(count);

		float sinYaw = 0f;
		float cosYaw = 1f;
		float sinPitch = 0f;
		float cosPitch = 1f;
		if (client.isGpu()) {
			float yaw = client.getCameraFpYaw();
			float pitch = client.getCameraFpPitch();
			sinYaw = (float) Math.sin(yaw);
			cosYaw = (float) Math.cos(yaw);
			sinPitch = (float) Math.sin(pitch);
			cosPitch = (float) Math.cos(pitch);
		}

		particleStagingBuffer.clear();
		for (int k = 0; k < count; k++) {
			Particle p = visibleParticles[particleSortOrder[k]];
			ParticleStyle style = p.getStyle();
			style.writeGpuColor(p, colorScratch);

			float bias = style.getDepthBias();
			float cx = p.getX() + plugin.cameraShift[0] + bias * sinYaw * cosPitch;
			float cy = p.getZ() - bias * sinPitch;
			float cz = p.getY() + plugin.cameraShift[1] - bias * cosYaw * cosPitch;

			particleStagingBuffer.put(cx).put(cy).put(cz);
			particleStagingBuffer.put(colorScratch[0]).put(colorScratch[1]).put(colorScratch[2]).put(colorScratch[3]);
			particleStagingBuffer.put(style.getGpuRadius(p));
			String tex = style.getTextureFile();
			particleStagingBuffer.put((float) textureLoader.getTextureLayer(tex != null ? tex : ""));
			float flipbookCols = 0f;
			float flipbookRows = 0f;
			float flipbookFrameVal = 0f;
			if (style.hasFlipbook())
			{
				flipbookCols = style.getFlipbookColumns();
				flipbookRows = style.getFlipbookRows();
				flipbookFrameVal = style.getGpuFlipbookFrame(p);
			}
			particleStagingBuffer.put(flipbookCols).put(flipbookRows).put(flipbookFrameVal);
			particleStagingBuffer.put(style.isUseEnvironmentLight() ? 1f : 0f);
			particleStagingBuffer.put(p.getYaw());
		}

		batchUploadBuffer.clear();
		for (int k = 0; k < count; k++) {
			int src = k * FLOATS_PER_INSTANCE;
			for (int f = 0; f < FLOATS_PER_INSTANCE; f++)
				batchUploadBuffer.putFloat(particleStagingBuffer.get(src + f));
			for (int p = 0; p < INSTANCE_PADDING_BYTES; p++)
				batchUploadBuffer.put((byte) 0);
		}
		return count;
	}

	private void sortIndicesByDistance(int count) {
		for (int i = 1; i < count; i++) {
			int idx = particleSortOrder[i];
			float dist = particleDistSq[idx];
			int j = i - 1;
			while (j >= 0 && particleDistSq[particleSortOrder[j]] < dist) {
				particleSortOrder[j + 1] = particleSortOrder[j];
				j--;
			}
			particleSortOrder[j + 1] = idx;
		}
	}

	private void bindInstanceBuffer(int slot) {
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances[slot]);
	}

	private void setupInstanceAttribs() {
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 0);
		glVertexAttribDivisor(1, 1);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 12);
		glVertexAttribDivisor(2, 1);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(3, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 28);
		glVertexAttribDivisor(3, 1);
		glEnableVertexAttribArray(4);
		glVertexAttribPointer(4, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 32);
		glVertexAttribDivisor(4, 1);
		glEnableVertexAttribArray(5);
		glVertexAttribPointer(5, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 36);
		glVertexAttribDivisor(5, 1);
		glEnableVertexAttribArray(6);
		glVertexAttribPointer(6, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 40);
		glVertexAttribDivisor(6, 1);
		glEnableVertexAttribArray(7);
		glVertexAttribPointer(7, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 44);
		glVertexAttribDivisor(7, 1);
		glEnableVertexAttribArray(8);
		glVertexAttribPointer(8, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 48);
		glVertexAttribDivisor(8, 1);
		glEnableVertexAttribArray(9);
		glVertexAttribPointer(9, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 52);
		glVertexAttribDivisor(9, 1);
	}

	private void uploadInstanceDataToVbo(int instanceCount, int slot) {
		int bytes = instanceCount * INSTANCE_STRIDE_BYTES;
		batchUploadBuffer.flip();
		if (particleInstanceBuffers != null && particleInstanceBuffers[slot].isMapped()) {
			particleInstanceBuffers[slot].upload(batchUploadBuffer);
		} else {
			glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances[slot]);
			ByteBuffer mapped = glMapBufferRange(GL_ARRAY_BUFFER, 0, bytes, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
			if (mapped != null) {
				mapped.put(batchUploadBuffer);
				glUnmapBuffer(GL_ARRAY_BUFFER);
			} else {
				glBufferSubData(GL_ARRAY_BUFFER, 0, batchUploadBuffer);
			}
			glBindBuffer(GL_ARRAY_BUFFER, 0);
		}
	}
}
