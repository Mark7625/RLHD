package rs117.hd.scene;

import java.util.ArrayList;
import java.util.List;
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

import static net.runelite.api.Perspective.LOCAL_HALF_TILE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;

@Slf4j
@Singleton
public class LavaLightManager {
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
		int[][][] tileHeights = scene.getTileHeights();
		int sceneOffset = sceneContext.sceneOffset;

		for (LavaType lavaType : LavaTypeManager.LAVA_TYPES) {
			if (!lavaType.hasLight())
				continue;

			LavaLightDefinition templateDef = lavaType.light;
			LavaLightDefinition lightDef = templateDef.instantiate(lavaType.name);
			int spacing = templateDef.getSpacing();
			int spacingSq = spacing * spacing;
			List<int[]> placedLights = new ArrayList<>();

			for (Tile[][] plane : scene.getExtendedTiles()) {
				for (Tile[] column : plane) {
					for (Tile tile : column) {
						if (tile == null)
							continue;

						if (tile.getBridge() != null)
							tile = tile.getBridge();

						var pos = tile.getSceneLocation();
						int tileExX = pos.getX() + sceneOffset;
						int tileExY = pos.getY() + sceneOffset;

						LavaType tileLavaType = getLavaType(sceneContext, tile, tileExX, tileExY);
						if (tileLavaType != lavaType)
							continue;

						int tileZ = tile.getRenderLevel();
						int tileX = tileExX - sceneOffset;
						int tileY = tileExY - sceneOffset;
						int localX = tileX * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE;
						int localZ = tileY * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE;

						boolean tooClose = false;
						for (int[] placed : placedLights) {
							int dx = localX - placed[0];
							int dz = localZ - placed[1];
							if (dx * dx + dz * dz < spacingSq) {
								tooClose = true;
								break;
							}
						}
						if (tooClose)
							continue;

						placedLights.add(new int[] { localX, localZ });

						Light light = new Light(lightDef);
						light.persistent = true;
						light.plane = tileZ;
						light.origin[0] = localX;
						light.origin[1] = tileHeights[tileZ][tileExX][tileExY] - lightDef.height - 1;
						light.origin[2] = localZ;
						sceneContext.lights.add(light);
					}
				}
			}
		}
	}

	private LavaType getLavaType(SceneContext sceneContext, Tile tile, int tileExX, int tileExY) {
		TileOverride override = getLavaTileOverride(sceneContext, tile, tileExX, tileExY);
		if (!isLavaGroundMaterial(override))
			return null;

		int[] worldPos = sceneContext.extendedSceneToWorld(tileExX, tileExY, tile.getRenderLevel());
		Material material = override.groundMaterial.getRandomMaterial(worldPos);
		if (material == null || !material.hasShaderLava())
			return null;

		return material.getLavaType();
	}

	/**
	 * Lava is applied via overlay tile overrides (e.g. overlay id 19), not underlay.
	 */
	private TileOverride getLavaTileOverride(SceneContext sceneContext, Tile tile, int tileExX, int tileExY) {
		int tileZ = tile.getRenderLevel();
		int overlayId = OVERLAY_FLAG | sceneContext.scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int[] worldPos = sceneContext.extendedSceneToWorld(tileExX, tileExY, tileZ);
		return tileOverrideManager.getOverrideBeforeReplacements(worldPos, overlayId);
	}

	private boolean isLavaGroundMaterial(TileOverride override) {
		if (override == null || override == TileOverride.NONE)
			return false;

		GroundMaterial groundMaterial = override.groundMaterial;
		if (groundMaterial == null || groundMaterial == GroundMaterial.NONE)
			return false;

		if (groundMaterial.hasShaderLava())
			return true;

		// Fallback for ground materials that use lava materials but failed to resolve at load time
		String name = groundMaterial.name;
		return name != null && name.contains("LAVA");
	}
}
