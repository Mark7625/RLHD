package rs117.hd.scene;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.MinimapStyle;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.GroundMaterial;
import rs117.hd.data.materials.Material;
import rs117.hd.model.ModelPusher;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.utils.ColorUtils;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.scene.tile_overrides.TileOverride.NONE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.clamp;

@Singleton
public class MinimapRenderer {

	private static final float[][] zoomPairs = {
		{2.0f, 0.51f},
		{2.25f, 0.58f},
		{2.5f, 0.64f},
		{2.75f, 0.7f},
		{3.0f, 0.77f},
		{3.25f, 0.83f},
		{3.5f, 0.9f},
		{3.75f, 0.95f},
		{4.0f, 1.0f},
		{4.25f, 1.05f},
		{4.5f, 1.13f},
		{4.75f, 1.2f},
		{5.0f, 1.25f},
		{5.25f, 1.32f},
		{5.5f, 1.4f},
		{5.75f, 1.45f},
		{6.0f, 1.5f},
		{6.25f, 1.54f},
		{6.5f, 1.64f},
		{6.75f, 1.67f},
		{7.0f, 1.73f},
		{7.25f, 1.84f},
		{7.5f, 1.87f},
		{7.75f, 1.97f},
		{8.0f, 2.0f}
	};

	public float interpolate(double zoom) {
		float zoomFloat = (float) zoom;
		for (int i = 0; i < zoomPairs.length - 1; i++) {
			if (zoomFloat >= zoomPairs[i][0] && zoomFloat <= zoomPairs[i + 1][0]) {
				float t = (zoomFloat - zoomPairs[i][0]) / (zoomPairs[i + 1][0] - zoomPairs[i][0]);
				return zoomPairs[i][1] + t * (zoomPairs[i + 1][1] - zoomPairs[i][1]);
			}
		}
		return -1; // Invalid zoom level
	}


	public static SceneTileModel[] tileModels = new SceneTileModel[52];

	static {
		for (int index = 0; index < tileModels.length; ++index) {
			int shape = index >> 2;
			int rotation = index & 0x3;

			tileModels[index] = new SceneTileModelCustom(shape, rotation, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0);
		}
	}


	public boolean configUseShadedMinimap;

	public boolean configUse117Minimap;

	public boolean updateMinimapLighting;

	public boolean shouldUpdateMinimapLighting() {
		return updateMinimapLighting && configUse117Minimap;
	}

	public int[][][][] minimapTilePaintColorsLighting = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][8];
	public int[][][][][] minimapTileModelColorsLighting = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][6][6];


	/**
	 * Saved image of the scene, no reason to draw the whole thing every
	 * frame.
	 */
	public volatile BufferedImage miniMapImageFull;

	/**
	 * Saved image of the scene, no reason to draw the whole thing every
	 * frame.
	 */
	public volatile BufferedImage miniMapImage;

	public Boolean createdMinimapSnapshot = false;


	/**
	 * The size of tiles on the map. The way the client renders requires
	 * this value to be 4. Changing this will break the method for rendering
	 * complex tiles
	 */
	static final int TILE_SIZE = 4;


	@Inject
	ProceduralGenerator proceduralGenerator;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelPusher modelPusher;


	private final int[] tmpScreenX = new int[6];
	private final int[] tmpScreenY = new int[6];

	@Inject
	private FrameTimer frameTimer;

	public void updateConfigs() {
		configUse117Minimap = config.minimapType() == MinimapStyle.DEFAULT;
		configUseShadedMinimap = configUse117Minimap || config.minimapType() == MinimapStyle.HD2008;
		if (configUse117Minimap) {
			updateMinimapLighting = true;
		}
	}

	public void reset() {
		client.setMinimapTileDrawer(null);
		createdMinimapSnapshot = false;
		miniMapImage = null;
		miniMapImageFull = null;
	}

	public void generateMinimapImage() {
		createdMinimapSnapshot = false;
		captureMinimap();
	}

	public void captureMinimap() {
		if (createdMinimapSnapshot) return;

		try {
			SpritePixels map = client.drawInstanceMap(client.getTopLevelWorldView().getPlane());

			BufferedImage image = minimapToBufferedImage(map, client.isResized() ? client.getExpandedMapLoading() : 0);

			synchronized (this) {
				miniMapImageFull = image;
				miniMapImage = extractRenderedMinimap(miniMapImageFull);
				plugin.uploadMinimapImage();
				createdMinimapSnapshot = true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static BufferedImage extractRenderedMinimap(BufferedImage originalImage) {
		int squareSize = 416;

		BufferedImage resultImage = new BufferedImage(squareSize, squareSize, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < squareSize; i++) {
			for (int j = 0; j < squareSize; j++) {
				resultImage.setRGB(i, j, originalImage.getRGB(i, j));
			}
		}

		return resultImage;
	}

	private static BufferedImage minimapToBufferedImage(SpritePixels spritePixels, int expandedChunks) {
		int width = spritePixels.getWidth();
		int height = spritePixels.getHeight();
		int[] pixels = spritePixels.getPixels();

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, width, height, pixels, 0, width);

		int maxChunks = ((Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2) / 8;
		if (expandedChunks < maxChunks) {
			int cropChunks = maxChunks - expandedChunks;
			img = img.getSubimage(
				TILE_SIZE * (cropChunks * 8),
				TILE_SIZE * (cropChunks * 8),
				(Constants.SCENE_SIZE + expandedChunks * 8) * TILE_SIZE,
				(Constants.SCENE_SIZE + expandedChunks * 8) * TILE_SIZE
			);
		}

		return img;
	}


	public int[] getPlayerMinimapLocation() {
		if (client.getLocalPlayer() == null)
			return new int[2];

		var lp = client.getLocalPlayer().getLocalLocation();
		int expandedChunks = plugin.getSceneContext() == null ? 0 : plugin.getSceneContext().expandedMapLoadingChunks;
		int padding = expandedChunks * CHUNK_SIZE * LOCAL_TILE_SIZE;
		return new int[] { lp.getX() + padding, lp.getY() + padding };
	}

	public void prepareScene(SceneContext sceneContext) {
		final Scene scene = sceneContext.scene;
		boolean classicLighting = config.minimapType() == MinimapStyle.HD2008;

		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = scene.getExtendedTiles()[z][x][y];
					if (tile == null) continue;
					processTilePaint(scene, sceneContext, tile, z, x, y, classicLighting);
					processTileModel(scene, sceneContext, tile, z, x, y, classicLighting);
				}
			}
		}
	}

	private Material getWater(WaterType type) {
		if (type == WaterType.ICE || type == WaterType.ICE_FLAT) {
			return Material.WATER_ICE;
		} else if (type == WaterType.SWAMP_WATER || type == WaterType.SWAMP_WATER_FLAT) {
			return Material.SWAMP_WATER_FLAT;
		}

		return Material.WATER_FLAT;
	}

	private final boolean minimapGroundBlending = false;

	private void processTilePaint(Scene scene, SceneContext sceneContext, Tile tile, int z, int x, int y, boolean classicLighting) {
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint == null) {
			return;
		}

		int[] colors = { paint.getSwColor(), paint.getSeColor(), paint.getNwColor(), paint.getNeColor() };
		Material[] materials = new Material[4];

		Arrays.fill(materials, Material.NONE);

		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileZ = tile.getRenderLevel();
		boolean hiddenTile = paint.getNwColor() == 12345678;
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		int[] vertexKeys = ProceduralGenerator.tileVertexKeys(scene, tile);
		int swVertexKey = vertexKeys[0];
		int seVertexKey = vertexKeys[1];
		int nwVertexKey = vertexKeys[2];
		int neVertexKey = vertexKeys[3];
		int hiddenRBG = ColorUtils.srgbToPackedHsl(ColorUtils.srgba(paint.getRBG()));

		// Ignore certain tiles that aren't supposed to be visible,
		// but which we can still make a height-adjusted version of for underwater


			int textureId = paint.getTexture();

			var override = tileOverrideManager.getOverride(sceneContext.scene, tile);
			WaterType waterType = proceduralGenerator.seasonalWaterType(override, paint.getTexture());

			if (waterType == WaterType.NONE) {
				if (textureId != -1) {
					var material = Material.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material == Material.VANILLA)
						override = NONE;
					materials[0] = materials[1] = materials[2] = materials[3] = material;
				}


				boolean useBlendedMaterialAndColor =
					plugin.configGroundBlending &&
					textureId == -1 &&
					!proceduralGenerator.useDefaultColor(tile, override);
				GroundMaterial groundMaterial = null;
				if (override != TileOverride.NONE) {
					groundMaterial = override.groundMaterial;
					if (!useBlendedMaterialAndColor) {
						colors[0] = override.modifyColor(colors[0]);
						colors[1] = override.modifyColor(colors[1]);
						colors[2] = override.modifyColor(colors[2]);
						colors[3] = override.modifyColor(colors[3]);
					}
				} else if (textureId == -1) {
					// Fall back to the default ground material if the tile is untextured
					groundMaterial = override.groundMaterial;
				}

				if (useBlendedMaterialAndColor) {
					// get the vertices' colors and textures from hashmaps
					colors[0] = sceneContext.vertexTerrainColor.getOrDefault(swVertexKey, colors[0]);
					colors[1] = sceneContext.vertexTerrainColor.getOrDefault(seVertexKey, colors[1]);
					colors[2] = sceneContext.vertexTerrainColor.getOrDefault(neVertexKey, colors[2]);
					colors[3] = sceneContext.vertexTerrainColor.getOrDefault(nwVertexKey, colors[3]);

					if (plugin.configGroundTextures) {
						materials[0] = sceneContext.vertexTerrainTexture.getOrDefault(swVertexKey, materials[0]);
						materials[1] = sceneContext.vertexTerrainTexture.getOrDefault(seVertexKey, materials[1]);
						materials[2] = sceneContext.vertexTerrainTexture.getOrDefault(neVertexKey, materials[2]);
						materials[3] = sceneContext.vertexTerrainTexture.getOrDefault(nwVertexKey, materials[3]);
					}
				} else if (plugin.configGroundTextures && groundMaterial != null) {
					materials[0] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY);
					materials[1] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY);
					materials[2] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY + 1);
					materials[3] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY + 1);
				}
			}
			else
			{
				Arrays.fill(colors, ColorUtils.srgbToPackedHsl(waterType.surfaceColor));
				Arrays.fill(materials, getWater(waterType));
			}


		float[][] textureColors = new float[4][];
		int[] texturePackedColors = new int[4];
		for (int i = 0; i < 4; i++) {
			if (hiddenTile) {
				colors[i] = hiddenRBG;
			}

			textureColors[i] = textureManager.getTextureAverageColor(materials[i]);
			float[] colorRGB = ColorUtils.srgbToLinear(ColorUtils.packedHslToSrgb(colors[i]));
			texturePackedColors[i] = multiplyAndPackColors(textureColors[i], colorRGB);

			if (hiddenTile) {
				texturePackedColors[i] = hiddenRBG;
			} else {
				texturePackedColors[i] =
					texturePackedColors[0] & 0xFF80 | texturePackedColors[i] & 0x7F; // Ensure hue and saturation consistency
			}
		}

		// Update sceneContext with color information
		System.arraycopy(colors, 0, sceneContext.minimapTilePaintColors[z][x][y], 0, colors.length);
		System.arraycopy(texturePackedColors, 0, sceneContext.minimapTilePaintColors[z][x][y], colors.length, texturePackedColors.length);
	}

	private void processTileModel(Scene scene, SceneContext sceneContext, Tile tile, int z, int x, int y, boolean classicLighting) {
		SceneTileModel model = tile.getSceneTileModel();
		if (model == null) {
			return;
		}
		final int[] faceColorA = model.getTriangleColorA();
		final int[] faceColorB = model.getTriangleColorB();
		final int[] faceColorC = model.getTriangleColorC();
		final int[] faceTextures = model.getTriangleTextureId();
		final int faceCount = model.getFaceX().length;

		Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileZ = tile.getRenderLevel();
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		final int tileExX = tileX + SCENE_OFFSET;
		final int tileExY = tileY + SCENE_OFFSET;

		int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);
		int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];

		for (int face = 0; face < faceCount; ++face) {

			int[] colors = { faceColorA[face], faceColorB[face], faceColorC[face] };
			Material[] materials = new Material[3];
			Arrays.fill(materials, Material.NONE);

			int[][] localVertices = ProceduralGenerator.faceLocalVertices(tile, face);

			int[] vertexKeys = ProceduralGenerator.faceVertexKeys(tile, face);
			int vertexKeyA = vertexKeys[0];
			int vertexKeyB = vertexKeys[1];
			int vertexKeyC = vertexKeys[2];

			int textureId;

			WaterType waterType;



			boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
			var override = tileOverrideManager.getOverride(scene, tile, worldPos, isOverlay ? overlayId : underlayId);
			textureId = faceTextures == null ? -1 : faceTextures[face];
			waterType = proceduralGenerator.seasonalWaterType(override, textureId);
			if (waterType == WaterType.NONE) {
				if (textureId != -1) {
					var material = Material.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material == Material.VANILLA)
						override = NONE;
					materials[0] = materials[1] = materials[2] = material;
				}

				GroundMaterial groundMaterial = null;

				boolean useBlendedMaterialAndColor =
					plugin.configGroundBlending &&
					textureId == -1 &&
					!(isOverlay && proceduralGenerator.useDefaultColor(tile, override));
				if (override != TileOverride.NONE) {
					groundMaterial = override.groundMaterial;
					if (!useBlendedMaterialAndColor) {
						colors[0] = override.modifyColor(colors[0]);
						colors[1] = override.modifyColor(colors[1]);
						colors[2] = override.modifyColor(colors[2]);
					}
				} else if (textureId == -1) {
					// Fall back to the default ground material if the tile is untextured
					groundMaterial = override.groundMaterial;
				}

				if (useBlendedMaterialAndColor) {
					// get the vertices' colors and textures from hashmaps
					colors[0] = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyA, colors[0]);
					colors[1] = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyB, colors[1]);
					colors[2] = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyC, colors[2]);

					if (plugin.configGroundTextures) {
						materials[0] = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyA, materials[0]);
						materials[1] = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyB, materials[1]);
						materials[2] = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyC, materials[2]);
					}
				} else if (plugin.configGroundTextures && groundMaterial != null) {
					materials[0] = groundMaterial.getRandomMaterial(
						tileZ,
						baseX + tileX + (int) Math.floor((float) localVertices[0][0] / LOCAL_TILE_SIZE),
						baseY + tileY + (int) Math.floor((float) localVertices[0][1] / LOCAL_TILE_SIZE)
					);
					materials[1] = groundMaterial.getRandomMaterial(
						tileZ,
						baseX + tileX + (int) Math.floor((float) localVertices[1][0] / LOCAL_TILE_SIZE),
						baseY + tileY + (int) Math.floor((float) localVertices[1][1] / LOCAL_TILE_SIZE)
					);
					materials[2] = groundMaterial.getRandomMaterial(
						tileZ,
						baseX + tileX + (int) Math.floor((float) localVertices[2][0] / LOCAL_TILE_SIZE),
						baseY + tileY + (int) Math.floor((float) localVertices[2][1] / LOCAL_TILE_SIZE)
					);
				}
			} else {
				Arrays.fill(colors, ColorUtils.srgbToPackedHsl(waterType.surfaceColor));
				Arrays.fill(materials, getWater(waterType));
			}

			float[][] linearColors = new float[colors.length][];
			for (int i = 0; i < colors.length; i++) {
				linearColors[i] = ColorUtils.srgbToLinear(ColorUtils.packedHslToSrgb(colors[i]));
			}


			// Store the processed colors directly
			System.arraycopy(colors, 0, sceneContext.minimapTileModelColors[z][x][y][face], 0, colors.length);

			// Process materials and store them
			int firstMaterialColor = 0;
			for (int i = 0; i < materials.length; i++) {
				float[] materialColor = textureManager.getTextureAverageColor(materials[i]);
				int packedColor = multiplyAndPackColors(materialColor, linearColors[i]);

				if (i == 0) {
					firstMaterialColor = packedColor;
					sceneContext.minimapTileModelColors[z][x][y][face][3 + i] = packedColor;
				} else {
					// Applying the blending operation separately
					int blendedColor = firstMaterialColor & 0xFF80 | packedColor & 0x7F;
					sceneContext.minimapTileModelColors[z][x][y][face][3 + i] = blendedColor;
				}
			}

		}
	}

	public int multiplyAndPackColors(float[] color1, float[] color2) {
		// Ensure each color array has exactly 3 elements (R, G, B)
		if (color1.length != 3 || color2.length != 3) {
			throw new IllegalArgumentException("Each color array must have exactly 3 elements.");
		}

		float red = color1[0] * color2[0];
		float green = color1[1] * color2[1];
		float blue = color1[2] * color2[2];

		return ColorUtils.srgbToPackedHsl(ColorUtils.linearToSrgb(new float[] { red, green, blue }));
	}



	public void applyLighting(SceneContext sceneContext) {
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = sceneContext.scene.getExtendedTiles()[z][x][y];
					if (tile == null)
						continue;

					var paint = tile.getSceneTilePaint();
					if (paint != null) {
						int swColor = sceneContext.minimapTilePaintColors[z][x][y][0];
						int seColor = sceneContext.minimapTilePaintColors[z][x][y][1];
						int nwColor = sceneContext.minimapTilePaintColors[z][x][y][2];
						int neColor = sceneContext.minimapTilePaintColors[z][x][y][3];

						int swTexture = sceneContext.minimapTilePaintColors[z][x][y][4];
						int seTexture = sceneContext.minimapTilePaintColors[z][x][y][5];
						int nwTexture = sceneContext.minimapTilePaintColors[z][x][y][6];
						int neTexture = sceneContext.minimapTilePaintColors[z][x][y][7];

						swColor = environmentalLighting(swColor);
						seColor = environmentalLighting(seColor);
						nwColor = environmentalLighting(nwColor);
						neColor = environmentalLighting(neColor);
						swTexture = environmentalLighting(swTexture);
						seTexture = environmentalLighting(seTexture);
						nwTexture = environmentalLighting(nwTexture);
						neTexture = environmentalLighting(neTexture);

						seColor = swColor & 0xFF80 | seColor & 0x7F;
						nwColor = swColor & 0xFF80 | nwColor & 0x7F;
						neColor = swColor & 0xFF80 | neColor & 0x7F;

						seTexture = swTexture & 0xFF80 | seTexture & 0x7F;
						nwTexture = swTexture & 0xFF80 | nwTexture & 0x7F;
						neTexture = swTexture & 0xFF80 | neTexture & 0x7F;

						minimapTilePaintColorsLighting[z][x][y][0] = swColor;
						minimapTilePaintColorsLighting[z][x][y][1] = seColor;
						minimapTilePaintColorsLighting[z][x][y][2] = nwColor;
						minimapTilePaintColorsLighting[z][x][y][3] = neColor;

						minimapTilePaintColorsLighting[z][x][y][4] = swTexture;
						minimapTilePaintColorsLighting[z][x][y][5] = seTexture;
						minimapTilePaintColorsLighting[z][x][y][6] = nwTexture;
						minimapTilePaintColorsLighting[z][x][y][7] = neTexture;
					}

					var model = tile.getSceneTileModel();
					if (model != null) {
						final int faceCount = model.getFaceX().length;
						for (int face = 0; face < faceCount; ++face) {
							int c1 = sceneContext.minimapTileModelColors[z][x][y][face][0];
							int c2 = sceneContext.minimapTileModelColors[z][x][y][face][1];
							int c3 = sceneContext.minimapTileModelColors[z][x][y][face][2];

							int mc1 = sceneContext.minimapTileModelColors[z][x][y][face][3];
							int mc2 = sceneContext.minimapTileModelColors[z][x][y][face][4];
							int mc3 = sceneContext.minimapTileModelColors[z][x][y][face][5];

							c1 = environmentalLighting(c1);
							c2 = environmentalLighting(c2);
							c3 = environmentalLighting(c3);
							mc1 = environmentalLighting(mc1);
							mc2 = environmentalLighting(mc2);
							mc3 = environmentalLighting(mc3);

							c2 = c1 & 0xFF80 | c2 & 0x7F;
							c3 = c1 & 0xFF80 | c3 & 0x7F;

							mc2 = mc1 & 0xFF80 | mc2 & 0x7F;
							mc3 = mc1 & 0xFF80 | mc3 & 0x7F;

							minimapTileModelColorsLighting[z][x][y][face][0] = c1;
							minimapTileModelColorsLighting[z][x][y][face][1] = c2;
							minimapTileModelColorsLighting[z][x][y][face][2] = c3;

							minimapTileModelColorsLighting[z][x][y][face][3] = mc1;
							minimapTileModelColorsLighting[z][x][y][face][4] = mc2;
							minimapTileModelColorsLighting[z][x][y][face][5] = mc3;

						}
					}
				}
			}
		}
		updateMinimapLighting = false;
	}

	private void drawMinimapOSRS(Tile tile, int tx, int ty, int px0, int py0, int px1, int py1) {
		client.getRasterizer().setRasterGouraudLowRes(false);
		try {
			SceneTilePaint paint = tile.getSceneTilePaint();
			if (paint != null) {
				int color = paint.getRBG();
				if (color != 0) {
					client.getRasterizer().fillRectangle(px0, py0, px1 - px0, py1 - py0, color);
				}
			} else {
				SceneTileModel model = tile.getSceneTileModel();
				if (model != null) {
					SceneTileModel stm = tileModels[(model.getShape() << 2) | model.getRotation() & 3];

					int[] vertexX = stm.getVertexX();
					int[] vertexZ = stm.getVertexZ();

					int[] indicies1 = stm.getFaceX();
					int[] indicies2 = stm.getFaceY();
					int[] indicies3 = stm.getFaceZ();

					int w = px1 - px0;
					int h = py1 - py0;

					for (int vert = 0; vert < vertexX.length; ++vert) {
						tmpScreenX[vert] = px0 + ((vertexX[vert] * w) >> Perspective.LOCAL_COORD_BITS);
						tmpScreenY[vert] = py0 + (((LOCAL_TILE_SIZE - vertexZ[vert]) * h) >> Perspective.LOCAL_COORD_BITS);
					}

					for (int face = 0; face < indicies1.length; ++face) {
						int idx1 = indicies1[face];
						int idx2 = indicies2[face];
						int idx3 = indicies3[face];

						boolean isUnderlay = stm.getTriangleColorA()[face] == 0;
						int color = isUnderlay
							? model.getModelUnderlay()
							: model.getModelOverlay();
						if (!isUnderlay || color != 0) {
							client.getRasterizer().rasterFlat(
								tmpScreenY[idx1], tmpScreenY[idx2], tmpScreenY[idx3],
								tmpScreenX[idx1], tmpScreenX[idx2], tmpScreenX[idx3],
								color
							);
						}
					}
				}
			}
		} finally {
			client.getRasterizer().setRasterGouraudLowRes(true);
		}
	}

	private static int blend(int baseHsl, int brightnessFactorHsl) {
		int lightness = (baseHsl & 0x7F) * (brightnessFactorHsl & 0x7F) >> 7;
		return (baseHsl & ~0x7F) | clamp(lightness, 0, 0x7F);
	}

	private void drawMinimapShaded(Tile tile, int tx, int ty, int px0, int py0, int px1, int py1) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int plane = tile.getPlane();
		int tileExX = tile.getSceneLocation().getX() + SCENE_OFFSET;
		int tileExY = tile.getSceneLocation().getY() + SCENE_OFFSET;

		var paint = tile.getSceneTilePaint();
		if (paint != null) {

			int[] colors = getTilePaintColor(sceneContext, plane, tileExX, tileExY);

			int swColor = colors[0];
			int seColor = colors[1];
			int nwColor = colors[2];
			int neColor = colors[3];

			int swTexture = colors[4];
			int seTexture = colors[5];
			int nwTexture = colors[6];
			int neTexture = colors[7];

			int tex = paint.getTexture();
			if (tex == -1) {
				if (paint.getNwColor() != 12345678) {
					fillGradient(px0, py0, px1, py1, nwColor, neColor, swColor, seColor);
				} else {
					client.getRasterizer().fillRectangle(px0, py0, px1 - px0, py1 - py0, paint.getRBG());
				}
			} else {
				boolean hasTexture = nwTexture != 0;
				fillGradient(
					px0,
					py0,
					px1,
					py1,
					hasTexture ? nwTexture : nwColor,
					hasTexture ? neTexture : neColor,
					hasTexture ? swTexture : swColor,
					hasTexture ? seTexture : seColor
				);
			}
		}

		var model = tile.getSceneTileModel();
		if (model != null) {
			int[] vertexX = model.getVertexX();
			int[] vertexZ = model.getVertexZ();

			int[] indicies1 = model.getFaceX();
			int[] indicies2 = model.getFaceY();
			int[] indicies3 = model.getFaceZ();

			int[] color1 = model.getTriangleColorA();
			int[] textures = model.getTriangleTextureId();

			int localX = tx << Perspective.LOCAL_COORD_BITS;
			int localY = ty << Perspective.LOCAL_COORD_BITS;

			int w = px1 - px0;
			int h = py1 - py0;

			for (int vert = 0; vert < vertexX.length; ++vert) {
				tmpScreenX[vert] = px0 + (((vertexX[vert] - localX) * w) >> Perspective.LOCAL_COORD_BITS);
				tmpScreenY[vert] = py0 + (((Perspective.LOCAL_TILE_SIZE - (vertexZ[vert] - localY)) * h) >> Perspective.LOCAL_COORD_BITS);
			}

			for (int face = 0; face < indicies1.length; ++face) {
				int idx1 = indicies1[face];
				int idx2 = indicies2[face];
				int idx3 = indicies3[face];

				int[] colors = getTileModelColor(sceneContext, plane, tileExX, tileExY, face);

				int c1 = colors[0];
				int c2 = colors[1];
				int c3 = colors[2];


				int mc1 = colors[3];
				int mc2 = colors[4];
				int mc3 = colors[5];


				if ((textures != null && textures[face] != -1)) {
					client.getRasterizer().rasterGouraud(
						tmpScreenY[idx1], tmpScreenY[idx2], tmpScreenY[idx3],
						tmpScreenX[idx1], tmpScreenX[idx2], tmpScreenX[idx3],
						mc1, mc2, mc3
					);
				} else if (color1[face] != 12345678) {
					client.getRasterizer().rasterGouraud(
						tmpScreenY[idx1], tmpScreenY[idx2], tmpScreenY[idx3],
						tmpScreenX[idx1], tmpScreenX[idx2], tmpScreenX[idx3],
						mc1 == 0 ? c1 : mc1, mc2 == 0 ? c2 : mc2, mc3 == 0 ? c3 : mc3
					);
				}

			}
		}
	}


	public int[] getTileModelColor(SceneContext sceneContext, int plane, int tileExX, int tileExY, int face) {
		return (!configUse117Minimap)
			? sceneContext.minimapTileModelColors[plane][tileExX][tileExY][face]
			: minimapTileModelColorsLighting[plane][tileExX][tileExY][face];
	}

	public int[] getTilePaintColor(SceneContext sceneContext, int plane, int tileExX, int tileExY) {
		return (!configUse117Minimap)
			? sceneContext.minimapTilePaintColors[plane][tileExX][tileExY]
			: minimapTilePaintColorsLighting[plane][tileExX][tileExY];
	}

	public void drawTile(Tile tile, int tx, int ty, int px0, int py0, int px1, int py1) {
		if (plugin.config.minimapType() == MinimapStyle.RUNELITE) return;
		frameTimer.begin(Timer.MINIMAP_DRAW);

		if (!createdMinimapSnapshot) {
			if (configUseShadedMinimap) {
				drawMinimapShaded(tile, tx, ty, px0, py0, px1, py1);
			} else {
				drawMinimapOSRS(tile, tx, ty, px0, py0, px1, py1);
			}
		} else {
			client.getRasterizer().fillRectangle(px0, py0, px1 - px0, py1 - py0, 12345678);
		}


		frameTimer.end(Timer.MINIMAP_DRAW);
	}

	private int environmentalLighting(int packedHsl) {
		var rgb = ColorUtils.srgbToLinear(ColorUtils.packedHslToSrgb(packedHsl));
		for (int i = 0; i < 3; i++) {
			rgb[i] *=
				environmentManager.currentAmbientColor[i] * environmentManager.currentAmbientStrength +
				environmentManager.currentDirectionalColor[i] * environmentManager.currentDirectionalStrength;
			rgb[i] = clamp(rgb[i], 0, 1);
		}
		return ColorUtils.srgbToPackedHsl(ColorUtils.linearToSrgb(rgb));
	}

	private void fillGradient(int px0, int py0, int px1, int py1, int c00, int c10, int c01, int c11) {
		Rasterizer g3d = client.getRasterizer();
		g3d.rasterGouraud(py0, py0, py1, px0, px1, px0, c00, c10, c01);
		g3d.rasterGouraud(py0, py1, py1, px1, px0, px1, c10, c01, c11);
	}
}
