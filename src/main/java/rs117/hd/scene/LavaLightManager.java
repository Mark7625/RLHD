package rs117.hd.scene;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import rs117.hd.HdPlugin;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.LavaMode;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.lava_types.LavaLightDefinition;
import rs117.hd.scene.lava_types.LavaType;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.tile_overrides.TileOverride;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Constants.MAX_Z;
import static net.runelite.api.Perspective.LOCAL_HALF_TILE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.MathUtils.max;

@Slf4j
@Singleton
public class LavaLightManager {
	private static final String LAVA_GROUND_MATERIAL = "HD_LAVA";

	@Inject
	private HdPlugin plugin;

	@Inject
	private TileOverrideManager tileOverrideManager;

	public void loadLavaLights(SceneContext sceneContext) {
		if (plugin.configLavaMode != LavaMode.MODERN || plugin.configDynamicLights == DynamicLights.NONE)
			return;

		boolean hasAnyLavaLights = false;
		for (LavaType lavaType : LavaTypeManager.LAVA_TYPES) {
			if (lavaType.hasLight()) {
				hasAnyLavaLights = true;
				break;
			}
		}
		if (!hasAnyLavaLights)
			return;

		Scene scene = sceneContext.scene;
		Tile[][][] tiles = scene.getExtendedTiles();
		int[][][] tileHeights = scene.getTileHeights();
		int sceneOffset = sceneContext.sceneOffset;

		for (int plane = 0; plane < MAX_Z; plane++) {
			for (LavaType lavaType : LavaTypeManager.LAVA_TYPES) {
				if (!lavaType.hasLight())
					continue;

				LavaLightDefinition templateDef = lavaType.light;
				LavaLightDefinition lightDef = templateDef.instantiate(lavaType.name);
				int spacingTiles = max(1, templateDef.getSpacing() / LOCAL_TILE_SIZE);

				for (int tileExY = 0; tileExY < EXTENDED_SCENE_SIZE; tileExY += spacingTiles) {
					for (int tileExX = 0; tileExX < EXTENDED_SCENE_SIZE; tileExX += spacingTiles) {
						int sumLocalX = 0;
						int sumLocalZ = 0;
						int lavaTileCount = 0;
						int tileZ = 0;
						int heightSum = 0;

						for (int dy = 0; dy < spacingTiles && tileExY + dy < EXTENDED_SCENE_SIZE; dy++) {
							for (int dx = 0; dx < spacingTiles && tileExX + dx < EXTENDED_SCENE_SIZE; dx++) {
								int x = tileExX + dx;
								int y = tileExY + dy;
								Tile tile = tiles[plane][x][y];
								if (tile == null)
									continue;

								if (tile.getBridge() != null)
									tile = tile.getBridge();

								if (getLavaType(sceneContext, tile, x, y) != lavaType)
									continue;

								tileZ = tile.getRenderLevel();
								int tileX = x - sceneOffset;
								int tileY = y - sceneOffset;
								sumLocalX += tileX * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE;
								sumLocalZ += tileY * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE;
								heightSum += tileHeights[tileZ][x][y];
								lavaTileCount++;
							}
						}

						if (lavaTileCount == 0)
							continue;

						int localX = sumLocalX / lavaTileCount;
						int localZ = sumLocalZ / lavaTileCount;
						int height = heightSum / lavaTileCount;

						Light light = new Light(lightDef);
						light.persistent = true;
						light.plane = tileZ;
						light.origin[0] = localX;
						light.origin[1] = height - lightDef.height - 1;
						light.origin[2] = localZ;
						sceneContext.lights.add(light);
					}
				}
			}
		}
	}

	private LavaType getLavaType(SceneContext sceneContext, Tile tile, int tileExX, int tileExY) {
		int[] worldPos = sceneContext.extendedSceneToWorld(tileExX, tileExY, tile.getRenderLevel());
		TileOverride override = tileOverrideManager.getOverride(sceneContext, tile, worldPos);
		if (!isLavaOverride(override))
			return null;

		Material material = override.groundMaterial.getRandomMaterial(worldPos);
		if (material == null || !material.hasShaderLava())
			return null;

		return material.getLavaType();
	}

	private boolean isLavaOverride(TileOverride override) {
		if (override == null || override == TileOverride.NONE)
			return false;

		GroundMaterial groundMaterial = override.groundMaterial;
		return groundMaterial != null &&
			groundMaterial != GroundMaterial.NONE &&
			LAVA_GROUND_MATERIAL.equals(groundMaterial.name);
	}
}
