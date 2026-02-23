package rs117.hd.renderer.zone;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.OcclusionShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.uniforms.UBOOcclusion;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class OcclusionManager {
	public static final int SCENE_QUERY = 0;
	public static final int DIRECTIONAL_QUERY = 1;
	public static final int SCENE_DEBUG = 2;
	public static final int QUERY_COUNT = 2;

	private static final int[] DEBUG_DRAW = new int[] { SCENE_DEBUG };
	private static final int[] OCCLUSION_DRAW = new int[] { SCENE_QUERY, DIRECTIONAL_QUERY };
	private static final int FRAMES_IN_FLIGHT = 3;

	@Getter
	private static OcclusionManager instance;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private OcclusionShaderProgram occlusionProgram;

	@Inject
	private OcclusionShaderProgram.Debug occlusionDebugProgram;

	private RenderState renderState;
	private UBOOcclusion uboOcclusion;

	private final ConcurrentLinkedQueue<OcclusionQuery> freeQueries = new ConcurrentLinkedQueue<>();
	private final List<OcclusionQuery> queuedQueries = new ArrayList<>();
	private final List<OcclusionQuery> prevQueuedQueries = new ArrayList<>();

	private final int[] result = new int[1];
	private final float[] vec = new float[4];
	private final float[] directionalFwd = new float[3];

	@Getter
	private boolean active;

	private boolean wireframe;

	@Getter
	private int queryCount = 0;
	private final int[] passedQueryCount = new int[QUERY_COUNT];

	private int glCubeVAO;
	private int glCubeVBO;
	private int glCubeEBO;

	public void toggleWireframe() {
		wireframe = !wireframe;
	}

	public void initialize(RenderState renderState, UBOOcclusion uboOcclusion) {
		this.renderState = renderState;
		this.uboOcclusion = uboOcclusion;

		instance = this;
		active = config.occlusionCulling();

		try(MemoryStack stack = MemoryStack.stackPush())
		{
			// Create cube VAO
			glCubeVAO = glGenVertexArrays();
			glCubeVBO = glGenBuffers();
			glBindVertexArray(glCubeVAO);

			FloatBuffer vboCubeData = stack.mallocFloat(8 * 3)
				.put(new float[] {
					// 8 unique cube corners
					-1, -1, -1, // 0
					1, -1, -1, // 1
					1,  1, -1, // 2
					-1,  1, -1, // 3
					-1, -1,  1, // 4
					1, -1,  1, // 5
					1,  1,  1, // 6
					-1,  1,  1  // 7
				})
				.flip();
			glBindBuffer(GL_ARRAY_BUFFER, glCubeVBO);
			glBufferData(GL_ARRAY_BUFFER, vboCubeData, GL_STATIC_DRAW);

			IntBuffer eboCubeData = stack.mallocInt(36)
				.put(new int[] {
					// Front face (-Z)
					0, 1, 2,
					0, 2, 3,

					// Back face (+Z)
					4, 6, 5,
					4, 7, 6,

					// Left face (-X)
					0, 3, 7,
					0, 7, 4,

					// Right face (+X)
					1, 5, 6,
					1, 6, 2,

					// Bottom face (-Y)
					0, 4, 5,
					0, 5, 1,

					// Top face (+Y)
					3, 2, 6,
					3, 6, 7
				})
				.flip();

			glCubeEBO = glGenBuffers();
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, glCubeEBO);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, eboCubeData, GL_STATIC_DRAW);

			// position attribute
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);

			// reset
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		occlusionProgram.compile(includes);
		occlusionDebugProgram.compile(includes);
	}

	public void destroyShaders() {
		occlusionProgram.destroy();
		occlusionDebugProgram.destroy();
	}

	public void destroy() {
		if(glCubeVAO != 0)
			glDeleteVertexArrays(glCubeVAO);
		glCubeVAO = 0;

		if(glCubeVBO != 0)
			glDeleteBuffers(glCubeVBO);
		glCubeVBO = 0;

		for(OcclusionQuery query : freeQueries) {
			if(query.id[0] != 0)
				glDeleteQueries(query.id);
		}
		freeQueries.clear();
	}

	public int getPassedQueryCount(int type) {
		return passedQueryCount[type];
	}

	public OcclusionQuery obtainQuery() {
		OcclusionQuery query = freeQueries.poll();
		if(query == null)
			query = new OcclusionQuery();
		return query;
	}

	public void readbackQueries() {
		active = config.occlusionCulling();

		if(prevQueuedQueries.isEmpty())
			return;

		frameTimer.begin(Timer.OCCLUSION_READBACK);
		queryCount = prevQueuedQueries.size();
		Arrays.fill(passedQueryCount, 0);
		for (int i = 0; i < queryCount; i++) {
			final OcclusionQuery query = prevQueuedQueries.get(i);
			if (!query.queued)
				continue;
			query.queued = false;

			for(int k = 0; k < QUERY_COUNT; k++) {
				final int id = query.getReadbackId(k);
				if (id == 0)
					continue;

				glGetQueryObjectiv(id, GL_QUERY_RESULT_AVAILABLE, result);
				if (result[0] == 0) {
					query.occluded[k] = false;
					continue;
				}

				if (!plugin.freezeCulling)
					query.occluded[k] = glGetQueryObjectui64(id, GL_QUERY_RESULT) == 0;

				if(!query.occluded[k])
					passedQueryCount[k]++;
			}
		}
		frameTimer.end(Timer.OCCLUSION_READBACK);
		prevQueuedQueries.clear();

		checkGLErrors();
	}

	public void occlusionDebugPass() {
		if(queuedQueries.isEmpty() || !wireframe)
			return;

		renderState.disable.set(GL_CULL_FACE);
		renderState.enable.set(GL_BLEND);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(false);
		renderState.ebo.set(glCubeEBO);
		renderState.vao.set(glCubeVAO);
		renderState.apply();

		glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
		glLineWidth(2.5f);

		processQueries(queuedQueries, DEBUG_DRAW);

		glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

		renderState.ebo.set(0);
		renderState.vao.set(0);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.apply();
	}

	public void occlusionPass() {
		if(queuedQueries.isEmpty())
			return;

		frameTimer.begin(Timer.RENDER_OCCLUSION);

		zoneRenderer.directionalCamera.getForwardDirection(directionalFwd);

		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(false);
		renderState.colorMask.set(false, false, false, false);
		renderState.ebo.set(glCubeEBO);
		renderState.vao.set(glCubeVAO);
		renderState.apply();

		// TODO: Add to RenderState
		glCullFace(GL_FRONT); // Switch to front face culling for more conservative occlusion culling

		processQueries(queuedQueries, OCCLUSION_DRAW);

		glCullFace(GL_BACK);

		renderState.ebo.set(0);
		renderState.vao.set(0);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.colorMask.set(true, true, true, true);
		renderState.apply();

		for(int i = 0; i < queuedQueries.size(); i++)
			queuedQueries.get(i).advance();

		prevQueuedQueries.addAll(queuedQueries);
		queuedQueries.clear();

		frameTimer.end(Timer.RENDER_OCCLUSION);

		checkGLErrors();
	}

	private void processQueries(List<OcclusionQuery> queries, int[] queryTypes) {
		for(int k = 0; k < queryTypes.length; k++) {
			switch (queryTypes[k]) {
				case SCENE_QUERY:
				case DIRECTIONAL_QUERY:
					renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
					renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboSceneDepth);
					renderState.depthFunc.set(GL_GEQUAL);

					occlusionProgram.use();
					occlusionProgram.viewProj.set(zoneRenderer.sceneCamera.getViewProjMatrix());
					break;
				case SCENE_DEBUG:
					renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
					renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
					renderState.depthFunc.set(GL_GEQUAL);

					occlusionDebugProgram.use();
					occlusionDebugProgram.viewProj.set(zoneRenderer.sceneCamera.getViewProjMatrix());
					break;
			}
			renderState.apply();

			int start = 0;
			int uboOffset = 0;

			for (int i = 0; i < queries.size(); i++) {
				final OcclusionQuery query = queries.get(i);
				if (query.count == 0)
					continue;
				assert query.count < UBOOcclusion.MAX_AABBS;

				if (uboOffset + query.count >= UBOOcclusion.MAX_AABBS) {
					flushQueries(queries, start, i, queryTypes[k]);
					start = i;
					uboOffset = 0;
				}

				if (query.id[0] == 0)
					glGenQueries(query.id);

				query.uboOffset = uboOffset;
				for (int j = 0; j < query.count; j++) {
					float posX = query.offsetX + query.aabb[j * 6];
					float posY = query.offsetY + query.aabb[j * 6 + 1];
					float posZ = query.offsetZ + query.aabb[j * 6 + 2];

					float sizeX = query.aabb[j * 6 + 3] + 0.1f;
					float sizeY = query.aabb[j * 6 + 4] + 0.1f;
					float sizeZ = query.aabb[j * 6 + 5] + 0.1f;

					if (query.worldView != null) {
						float sizeXHalf = sizeX / 2;
						float sizeYHalf = sizeY / 2;
						float sizeZHalf = sizeZ / 2;

						query.worldView.project(vec4(vec, posX - sizeXHalf, posY - sizeYHalf, posZ - sizeZHalf, 1.0f));
						float minX = vec[0];
						float minY = vec[1];
						float minZ = vec[2];

						query.worldView.project(vec4(vec, posX + sizeXHalf, posY + sizeYHalf, posZ + sizeZHalf, 1.0f));
						float maxX = vec[0];
						float maxY = vec[1];
						float maxZ = vec[2];

						posX = (minX + maxX) / 2;
						posY = (minY + maxY) / 2;
						posZ = (minZ + maxZ) / 2;

						sizeX = maxX - minX;
						sizeY = maxY - minY;
						sizeZ = maxZ - minZ;
					}

					if (queryTypes[k] == DIRECTIONAL_QUERY) {
						final float EXPAND_FACTOR = 2.0f;
						float dirX = directionalFwd[0];
						float dirY = directionalFwd[1];
						float dirZ = directionalFwd[2];

						float projected =
							abs(dirX) * (sizeX / 2) +
							abs(dirY) * (sizeY / 2) +
							abs(dirZ) * (sizeZ / 2);

						sizeX += abs(dirX) * projected * EXPAND_FACTOR;
						sizeY += abs(dirY) * projected * EXPAND_FACTOR;
						sizeZ += abs(dirZ) * projected * EXPAND_FACTOR;

						posX += dirX * projected;
						posY += dirY * projected;
						posZ += dirZ * projected;
					}

					uboOcclusion.positions[uboOffset].set(posX, posY, posZ);
					uboOcclusion.sizes[uboOffset].set(sizeX, sizeY, sizeZ);
					uboOffset++;
				}
			}
			flushQueries(queries, start, queries.size(), queryTypes[k]);
		}
		checkGLErrors();
	}

	private void flushQueries(List<OcclusionQuery> queries, int start, int end, int type) {
		uboOcclusion.upload();
		for (int i = start; i < end; i++) {
			final OcclusionQuery query = queries.get(i);
			if (query.count == 0)
				continue;
			assert query.count < UBOOcclusion.MAX_AABBS;

			if(type == SCENE_DEBUG) {
				occlusionDebugProgram.offset.set(query.uboOffset);
				occlusionDebugProgram.queryId.set(query.id[0]);
				glDrawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0, query.count);
			} else {
				occlusionProgram.offset.set(query.uboOffset);
				glBeginQuery(GL_ANY_SAMPLES_PASSED, query.getSampleId(type));
				glDrawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0, query.count);
				glEndQuery(GL_ANY_SAMPLES_PASSED);
			}
		}
	}

	public void shutdown() {
		if(glCubeVAO != 0)
			glDeleteVertexArrays(glCubeVAO);
		glCubeVAO = 0;

		if(glCubeEBO != 0)
			glDeleteBuffers(glCubeEBO);
		glCubeEBO = 0;

		if(glCubeVBO != 0)
			glDeleteBuffers(glCubeVBO);
		glCubeVBO = 0;
	}

	public final class OcclusionQuery {
		private final int[] id = new int[QUERY_COUNT * FRAMES_IN_FLIGHT];
		private final boolean[] sampled = new boolean[QUERY_COUNT * FRAMES_IN_FLIGHT];
		private final boolean[] occluded = new boolean[QUERY_COUNT];

		@Getter
		private boolean queued;

		private int activeId;

		private int uboOffset;
		private float offsetX = 0;
		private float offsetY = 0;
		private float offsetZ = 0;

		@Setter
		private WorldViewStruct worldView;
		private float[] aabb = new float[6];
		private int count = 0;

		private void advance() {
			activeId = (activeId + 1) % FRAMES_IN_FLIGHT;
		}

		private int getReadbackId(int type) {
			int idx = type * FRAMES_IN_FLIGHT + (activeId + 1) % FRAMES_IN_FLIGHT;
			if(!sampled[idx])
				return 0;
			sampled[idx] = false;
			return id[idx];
		}

		private int getSampleId(int type) {
			int idx = type * FRAMES_IN_FLIGHT + activeId;
			sampled[idx] = true;
			return id[idx];
		}

		public boolean isOccluded(int type) {
			return occluded[type] && active;
		}

		public boolean isVisible(int type) {
			return !occluded[type] || !active;
		}

		public boolean isFullyOccluded() {
			for(int i = 0; i < QUERY_COUNT; i++) {
				if(!isOccluded(i))
					return false;
			}
			return true;
		}

		public void setOffset(float x, float y, float z) {
			offsetX = x;
			offsetY = y;
			offsetZ = z;
		}

		public void addSphere(float x, float y, float z, float radius) {
			// TODO: Support drawing spheres for more exact occlusion
			float halfRadius = radius / 2;
			addAABB(x - halfRadius, y - halfRadius, z - halfRadius, radius, radius, radius);
		}

		public void addAABB(AABB aabb) {
			addAABB(aabb, 0, 0, 0);
		}

		public void addAABB(AABB aabb, float x, float y, float z) {
			addAABB(x + aabb.getCenterX(), y + aabb.getCenterY(), z + aabb.getCenterZ(), aabb.getExtremeX(), aabb.getExtremeY(), aabb.getExtremeZ());
		}

		public void addMinMax(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			float sizeX = maxX - minX;
			float sizeY = maxY - minY;
			float sizeZ = maxZ - minZ;
			addAABB(minX + sizeX / 2, minY + sizeY / 2, minZ + sizeZ / 2, sizeX, sizeY, sizeZ);
		}

		public void addAABB(
			float posX, float posY, float posZ,
			float sizeX, float sizeY, float sizeZ) {
			if(count * 6 >= aabb.length)
				aabb = Arrays.copyOf(aabb, aabb.length * 2);

			aabb[count * 6] = posX;
			aabb[count * 6 + 1] = posY;
			aabb[count * 6 + 2] = posZ;

			aabb[count * 6 + 3] = sizeX;
			aabb[count * 6 + 4] = sizeY;
			aabb[count * 6 + 5] = sizeZ;

			count++;
		}

		public void reset() {
			count = 0;
		}

		public void queue() {
			if(!active || queued) {
				return;
			}

			queued = true;
			synchronized (queuedQueries) {
				queuedQueries.add(this);
			}
		}

		public void free() {
			count = 0;
			queued = false;
			worldView = null;
			offsetX = 0;
			offsetY = 0;
			offsetZ = 0;
			Arrays.fill(occluded, false);
			freeQueries.add(this);
		}
	}
}
