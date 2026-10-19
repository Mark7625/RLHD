package rs117.hd.scene.daylight_cycle;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ConstellationMode;
import rs117.hd.opengl.shader.NebulaBakeShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.utils.Props;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SEED;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_NEBULA_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.utils.ColorUtils.colorTemperatureToLinearRgb;
import static rs117.hd.utils.HDUtils.randomPointOnSphere;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public final class StarField {
	private static final float[] UP_VECTOR = { 0, 1, 0 };
	private static final float[] LEFT_VECTOR = { 1, 0, 0 };

	private static final float[][] STAR_COLORS = {
		colorTemperatureToLinearRgb(4000),
		colorTemperatureToLinearRgb(5000),
		colorTemperatureToLinearRgb(6000),
		colorTemperatureToLinearRgb(7000),
		colorTemperatureToLinearRgb(9000),
		colorTemperatureToLinearRgb(12000),
	};

	static {
		// Temperature colors have unit luminance. Retain roughly the old palette's
		// mean luminance while keeping temperature independent of stellar brightness.
		for (float[] color : STAR_COLORS)
			multiply(color, color, 0.78f);
	}

	// VBO layout: direction.xyz, size, brightness, color.rgb, artistic rotation speed, marker boost.
	private static final int FLOATS_PER_STAR = 10;

	// Match the old field's density independently of screen resolution.
	private static final int BRIGHT_STAR_COUNT = 350;   // layer 0: sparse/bright/large
	private static final int DIM_STAR_COUNT = 2200;     // layer 1: dense/dim/small
	private static final int CLUSTER_STAR_COUNT = 1600; // layer 2: clustered
	// Sized generously so "Debug: show all constellations" can fit every entry in
	// star_constellations.json at once, not just the one normally shown at a time.
	private static final int MAX_CONSTELLATION_NODES = 128;
	private static final int MAX_STAR_COUNT =
		BRIGHT_STAR_COUNT + DIM_STAR_COUNT + CLUSTER_STAR_COUNT + MAX_CONSTELLATION_NODES;

	public static final int CLUSTER_COUNT = 12;
	private static final float CLUSTER_ANGULAR_SPREAD = 0.09f;

	private static final int NEBULA_CUBE_MAP_RESOLUTION = 512;

	// Constellation line VBO layout: dirA.xyz, dirB.xyz.
	private static final int FLOATS_PER_EDGE = 6;
	private static final int MAX_CONSTELLATION_EDGES = 160;
	private static final float CONSTELLATION_ANGULAR_RADIUS = 0.22f;

	private static final float[] CONSTELLATION_MARKER_COLOR = colorTemperatureToLinearRgb(8000);

	static {
		multiply(CONSTELLATION_MARKER_COLOR, CONSTELLATION_MARKER_COLOR, 1.3f);
	}

	// Exposed so the in-game ::117hd constellations dev tool can write back to the same path
	// this watches, letting saved edits hot-reload immediately.
	public static final ResourcePath CONSTELLATIONS_PATH = Props
		.getFile("rlhd.star-constellations-path", () -> path(StarField.class, "star_constellations.json"));

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private NebulaBakeShaderProgram nebulaBakeProgram;

	private final Random random = new Random(SEED);
	private final Random constellationRandom = new Random();
	private final RenderState nebulaBakeRenderState = new RenderState();

	private volatile ConstellationPools constellationPools =
		new ConstellationPools(new ConstellationDefinition[0], new ConstellationDefinition[0]);

	private int texNebulaCubemap;
	private int fboNebulaBake;

	@Getter
	private int vaoStars;
	@Getter
	private int vaoStarTriangles;
	@Getter
	private int vaoConstellationEdges;

	public int starCount;
	public int constellationEdgeCount;

	private GLBuffer vboStars;
	private GLBuffer vboConstellationEdges;

	private boolean starGeometryCurrent;
	private boolean nebulaMapCurrent;

	private static final class ConstellationPools {
		final ConstellationDefinition[] realistic;
		final ConstellationDefinition[] fun;

		ConstellationPools(ConstellationDefinition[] realistic, ConstellationDefinition[] fun) {
			this.realistic = realistic;
			this.fun = fun;
		}
	}

	public void initialize() {
		resetStarfield();

		vaoStars = glGenVertexArrays();
		vaoStarTriangles = glGenVertexArrays();

		vboStars = new GLBuffer("Stars::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboStars.initialize(FLOATS_PER_STAR * MAX_STAR_COUNT);
		vboStars.bind();

		int stride = FLOATS_PER_STAR * Float.BYTES;
		for (int divisor = 0; divisor <= 1; divisor++) {
			glBindVertexArray(divisor == 0 ? vaoStars : vaoStarTriangles);
			// Each VAO owns its attribute layout; only the underlying buffer is shared.
			// direction.xyz, size, brightness, color.rgb, artistic rotation speed, marker boost
			glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
			glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3L * Float.BYTES);
			glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 4L * Float.BYTES);
			glVertexAttribPointer(3, 3, GL_FLOAT, false, stride, 5L * Float.BYTES);
			glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
			glVertexAttribPointer(5, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
			for (int i = 0; i < 6; i++) {
				glEnableVertexAttribArray(i);
				glVertexAttribDivisor(i, divisor);
			}
		}
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		vaoConstellationEdges = glGenVertexArrays();
		vboConstellationEdges = new GLBuffer("Constellation::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboConstellationEdges.initialize(FLOATS_PER_EDGE * MAX_CONSTELLATION_EDGES);
		vboConstellationEdges.bind();

		glBindVertexArray(vaoConstellationEdges);
		int edgeStride = FLOATS_PER_EDGE * Float.BYTES;
		// Both endpoints are instance attributes; quad corners come from gl_VertexID in the shader.
		glVertexAttribPointer(0, 3, GL_FLOAT, false, edgeStride, 0L);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, edgeStride, 3L * Float.BYTES);
		for (int i = 0; i < 2; i++) {
			glEnableVertexAttribArray(i);
			glVertexAttribDivisor(i, 1);
		}
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		CONSTELLATIONS_PATH.watch(path -> loadConstellations(plugin.getGson(), path));

		fboNebulaBake = glGenFramebuffers();
		texNebulaCubemap = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_NEBULA_MAP);
		glBindTexture(GL_TEXTURE_CUBE_MAP, texNebulaCubemap);

		for (int face = 0; face < 6; face++) {
			glTexImage2D(
				GL_TEXTURE_CUBE_MAP_POSITIVE_X + face,
				0,
				GL_RGBA16F,
				NEBULA_CUBE_MAP_RESOLUTION,
				NEBULA_CUBE_MAP_RESOLUTION,
				0,
				GL_RGBA,
				GL_FLOAT,
				0
			);
		}

		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
	}

	public void destroy() {
		if (vboStars != null)
			vboStars.destroy();
		vboStars = null;

		if (vaoStars != 0)
			glDeleteVertexArrays(vaoStars);
		vaoStars = 0;

		if (vaoStarTriangles != 0)
			glDeleteVertexArrays(vaoStarTriangles);
		vaoStarTriangles = 0;

		if (vboConstellationEdges != null)
			vboConstellationEdges.destroy();
		vboConstellationEdges = null;

		if (vaoConstellationEdges != 0)
			glDeleteVertexArrays(vaoConstellationEdges);
		vaoConstellationEdges = 0;

		if (fboNebulaBake != 0)
			glDeleteFramebuffers(fboNebulaBake);
		fboNebulaBake = 0;

		if (texNebulaCubemap != 0)
			glDeleteTextures(texNebulaCubemap);
		texNebulaCubemap = 0;

		resetStarfield();
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		nebulaBakeProgram.compile(includes);
	}

	public void destroyShaders() {
		nebulaBakeProgram.destroy();
	}

	public void resetStarfield() {
		starGeometryCurrent = false;
		nebulaMapCurrent = false;
	}

	public boolean rebuildIfNeeded() {
		boolean rebuilt = false;
		if (!starGeometryCurrent) {
			random.setSeed(SEED);
			FloatBuffer vertexData = BufferUtils.createFloatBuffer(MAX_STAR_COUNT * FLOATS_PER_STAR);
			FloatBuffer edgeData = BufferUtils.createFloatBuffer(MAX_CONSTELLATION_EDGES * FLOATS_PER_EDGE);

			generateLayer(vertexData, BRIGHT_STAR_COUNT, 1.2f, 1.0f, 1);
			generateLayer(vertexData, DIM_STAR_COUNT, 0.4f, 0.8f, .7f);

			if (config.enableNebulae())
				generateClusteredLayer(vertexData, CLUSTER_STAR_COUNT, CLUSTER_COUNT, CLUSTER_ANGULAR_SPREAD, 0.5f, 0.5f, 1);

			constellationEdgeCount = 0;
			if (config.constellationMode() != ConstellationMode.OFF)
				generateConstellation(vertexData, edgeData);

			starCount = vertexData.position() / FLOATS_PER_STAR;
			vboStars.upload(vertexData.flip());
			vboConstellationEdges.upload(edgeData.flip());
			starGeometryCurrent = true;
			rebuilt = true;
		}

		if (!config.enableNebulae() || nebulaMapCurrent || fboNebulaBake == 0 || texNebulaCubemap == 0 || !nebulaBakeProgram.isValid())
			return rebuilt;

		nebulaBakeRenderState.framebuffer.set(GL_FRAMEBUFFER, fboNebulaBake);
		nebulaBakeRenderState.viewport.set(0, 0, NEBULA_CUBE_MAP_RESOLUTION, NEBULA_CUBE_MAP_RESOLUTION);
		nebulaBakeRenderState.vao.setVao(plugin.vaoTri);
		nebulaBakeRenderState.disable.set(GL_DEPTH_TEST);
		nebulaBakeRenderState.disable.set(GL_BLEND);
		nebulaBakeRenderState.disable.set(GL_CULL_FACE);
		nebulaBakeRenderState.apply();

		plugin.uboSky.upload();

		nebulaBakeProgram.use();
		for (int face = 0; face < 6; face++) {
			nebulaBakeProgram.uniCubeFace.set(face);
			glFramebufferTexture2D(
				GL_FRAMEBUFFER,
				GL_COLOR_ATTACHMENT0,
				GL_TEXTURE_CUBE_MAP_POSITIVE_X + face,
				texNebulaCubemap,
				0
			);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		nebulaBakeRenderState.reset();
		nebulaMapCurrent = true;
		return true;
	}

	private void generateLayer(FloatBuffer vertexBuffer, int count, float maxBrightness, float sizeScale, float artisticRotationSpeed) {
		final float[] center = new float[3];
		for (int i = 0; i < count; i++) {
			randomPointOnSphere(center, random);
			writeStar(vertexBuffer, center, maxBrightness, sizeScale, artisticRotationSpeed, 1);
		}
	}

	private void generateClusteredLayer(
		FloatBuffer vertexBuffer,
		int count,
		int clusterCount,
		float angularSpread,
		float maxBrightness,
		float sizeScale,
		float artisticRotationSpeed
	) {
		final float[][] clusterCenters = new float[clusterCount][3];
		final float[][] clusterTangentU = new float[clusterCount][3];
		final float[][] clusterTangentV = new float[clusterCount][3];

		for (int c = 0; c < clusterCount; c++) {
			final float[] dir = clusterCenters[c];
			final float[] u = clusterTangentU[c];
			final float[] v = clusterTangentV[c];

			randomPointOnSphere(dir, random);

			cross(u, dir, (Math.abs(dir[1]) < 0.99f) ? UP_VECTOR : LEFT_VECTOR);
			normalize(u, u);
			cross(v, dir, u);

			plugin.uboSky.nebulaClusters[c].set(dir[0], dir[1], dir[2], CLUSTER_ANGULAR_SPREAD * 0.5f);
		}

		float[] pos = new float[3];

		for (int i = 0; i < count; i++) {
			final int c = random.nextInt(clusterCount);

			final float[] dir = clusterCenters[c];
			final float[] u = clusterTangentU[c];
			final float[] v = clusterTangentV[c];

			float radius = min(abs((float) random.nextGaussian()), 3) * angularSpread;
			float theta = (float) (random.nextFloat() * 2.0 * PI);
			float cosR = cos(radius);
			float sinR = sin(radius);
			float cosT = cos(theta);
			float sinT = sin(theta);
			for (int j = 0; j < 3; j++)
				pos[j] = cosR * dir[j] + sinR * (cosT * u[j] + sinT * v[j]);

			writeStar(vertexBuffer, pos, maxBrightness, sizeScale, artisticRotationSpeed, .5f);
		}
	}

	private void writeStar(
		FloatBuffer vertexBuffer,
		float[] position,
		float maxBrightness,
		float sizeScale,
		float artisticRotationSpeed,
		float brightnessScale
	) {
		// Power-law brightness: many dim, few bright (matches pow(seed, 2.5)).
		float brightnessSeed = random.nextFloat();
		float brightness = min(pow(brightnessSeed, 2.5f) * maxBrightness, .4f) * brightnessScale;

		// Per-star size variation, skewed toward the small end (squaring the
		// random factor biases most stars small with only a few larger ones),
		// scaled per layer. Range ~0.4x to 1.0x.
		float sizeSeed = random.nextFloat();
		float size = (0.4f + sizeSeed * sizeSeed * 0.6f) * sizeScale;

		// Representative stellar temperatures, rather than independent RGB tints.
		final float[] starColor = STAR_COLORS[random.nextInt(STAR_COLORS.length)];

		vertexBuffer
			.put(position)
			.put(size)
			.put(brightness)
			.put(starColor)
			.put(artisticRotationSpeed)
			.put(0f); // Only constellation markers get a prominence boost.
	}

	private void writeConstellationNode(FloatBuffer vertexBuffer, float[] direction) {
		// Same size as a normal bright-layer star, so markers read as ordinary stars.
		// Brightness starts at the same cap too; the boost (applied in the shader,
		// after the normal brightness clamp) is what actually makes them stand out.
		float boost = config.constellationProminence() / 100f - 1f;
		vertexBuffer
			.put(direction)
			.put(1.0f)
			.put(.4f)
			.put(CONSTELLATION_MARKER_COLOR)
			.put(1f) // Rotate like a normal star, following the Stars setting above.
			.put(boost);
	}

	// Picks a random constellation (zodiac-style ones in Realistic mode, or any custom
	// shape defined in star_constellations.json in Fun mode, on top of the Realistic ones)
	// and places it at a random orientation above the horizon. Its nodes are added as
	// extra bright stars, and its edges are written out as connecting lines.
	//
	// With the "Debug: show all constellations" toggle, every shape in the current mode's
	// pool is placed at once instead of just one, to make it easy to preview every shape.
	private void generateConstellation(FloatBuffer vertexBuffer, FloatBuffer edgeBuffer) {
		ConstellationPools pools = constellationPools;
		ConstellationDefinition[] pool = config.constellationMode() == ConstellationMode.FUN ? pools.fun : pools.realistic;
		if (pool.length == 0)
			return;

		if (config.constellationDebugShowAll()) {
			int nodeBudget = MAX_CONSTELLATION_NODES;
			int edgeBudget = MAX_CONSTELLATION_EDGES;
			for (ConstellationDefinition shape : pool) {
				if (nodeBudget <= 0 || edgeBudget <= 0)
					break;
				int[] written = placeConstellation(
					shape,
					randomConstellationCenter(),
					constellationRandom.nextFloat() * TWO_PI,
					vertexBuffer,
					edgeBuffer,
					nodeBudget,
					edgeBudget
				);
				nodeBudget -= written[0];
				edgeBudget -= written[1];
				constellationEdgeCount += written[1];
			}
			return;
		}

		ConstellationDefinition shape = pool[constellationRandom.nextInt(pool.length)];
		int[] written = placeConstellation(
			shape,
			randomConstellationCenter(),
			constellationRandom.nextFloat() * TWO_PI,
			vertexBuffer,
			edgeBuffer,
			MAX_CONSTELLATION_NODES,
			MAX_CONSTELLATION_EDGES
		);
		constellationEdgeCount = written[1];
	}

	// Keep constellations comfortably above the horizon.
	private float[] randomConstellationCenter() {
		float[] center = new float[3];
		do {
			randomPointOnSphere(center, constellationRandom);
		} while (center[1] < 0.15f);
		return center;
	}

	// Writes one constellation's nodes and edges, clamped to the given budgets so a run of
	// several placements (debug mode) can't overflow the shared vertex/edge buffers.
	// Returns { nodesWritten, edgesWritten }.
	private int[] placeConstellation(
		ConstellationDefinition shape,
		float[] center,
		float rotation,
		FloatBuffer vertexBuffer,
		FloatBuffer edgeBuffer,
		int nodeBudget,
		int edgeBudget
	) {
		final float[] u = new float[3];
		final float[] v = new float[3];
		cross(u, center, Math.abs(center[1]) < 0.99f ? UP_VECTOR : LEFT_VECTOR);
		normalize(u, u);
		cross(v, center, u);

		float cosR = cos(rotation);
		float sinR = sin(rotation);
		float angularRadius = CONSTELLATION_ANGULAR_RADIUS * shape.scale;

		int nodeCount = Math.min(shape.points.length, nodeBudget);
		float[][] nodeDirs = new float[nodeCount][];
		for (int i = 0; i < nodeCount; i++) {
			float x = shape.points[i][0];
			float y = shape.points[i][1];
			float rx = x * cosR - y * sinR;
			float ry = x * sinR + y * cosR;

			float[] dir = new float[3];
			for (int j = 0; j < 3; j++)
				dir[j] = center[j] + angularRadius * (rx * u[j] + ry * v[j]);
			normalize(dir, dir);

			nodeDirs[i] = dir;
			writeConstellationNode(vertexBuffer, dir);
		}

		int edgeCount = 0;
		for (int[] edge : shape.edges) {
			if (edgeCount >= edgeBudget)
				break;
			if (edge[0] >= nodeCount || edge[1] >= nodeCount)
				continue;
			edgeBuffer.put(nodeDirs[edge[0]]).put(nodeDirs[edge[1]]);
			edgeCount++;
		}
		return new int[] { nodeCount, edgeCount };
	}

	private void loadConstellations(Gson gson, ResourcePath path) {
		ConstellationDefinition[] definitions;
		try {
			definitions = path.loadJson(gson, ConstellationDefinition[].class);
			if (definitions == null) {
				log.warn("Skipping empty star_constellations.json");
				return;
			}
		} catch (IOException ex) {
			log.error("Failed to load star constellations", ex);
			return;
		}

		List<ConstellationDefinition> realistic = new ArrayList<>();
		List<ConstellationDefinition> fun = new ArrayList<>();
		for (int i = 0; i < definitions.length; i++) {
			ConstellationDefinition def = definitions[i];
			try {
				def.normalize();
			} catch (RuntimeException ex) {
				log.warn("Ignoring invalid constellation at index {}: {}", i, ex.getMessage());
				continue;
			}

			fun.add(def);
			if (def.mode == ConstellationMode.REALISTIC)
				realistic.add(def);
		}

		log.debug("Loaded {} constellations ({} realistic)", fun.size(), realistic.size());
		constellationPools = new ConstellationPools(
			realistic.toArray(new ConstellationDefinition[0]),
			fun.toArray(new ConstellationDefinition[0])
		);
		resetStarfield();
	}
}
