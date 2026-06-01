package rs117.hd.scene.lava;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import rs117.hd.HdPlugin;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.LavaMode;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.tile_overrides.TileOverride;

import static net.runelite.api.Perspective.LOCAL_HALF_TILE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.scene.ProceduralGenerator.isOverlayFace;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;

@Slf4j
@Singleton
public class LavaLightManager {
	private static final int VANILLA_LAVA_TEXTURE_ID = 31;

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
		Map<String, List<int[]>> placedLightsByType = new HashMap<>();

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

					LavaType lavaType = getLavaType(sceneContext, tile, tileExX, tileExY);
					if (lavaType == null || !lavaType.hasLight())
						continue;

					int tileZ = tile.getRenderLevel();
					int tileX = tileExX - sceneOffset;
					int tileY = tileExY - sceneOffset;
					int localX = tileX * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE;
					int localZ = tileY * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE;

					List<int[]> placedLights = placedLightsByType.computeIfAbsent(lavaType.name, k -> new ArrayList<>());
					int spacing = lavaType.light.getSpacing();
					int spacingSq = spacing * spacing;

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

					LavaType.LavaLightDefinition lightDef = lavaType.light.instantiate(lavaType.name);
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

	private LavaType getLavaType(SceneContext sceneContext, Tile tile, int tileExX, int tileExY) {
		int tileZ = tile.getRenderLevel();
		int[] worldPos = sceneContext.extendedSceneToWorld(tileExX, tileExY, tileZ);
		Scene scene = sceneContext.scene;
		int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];

		var overlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, overlayId);
		var underlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, underlayId);

		LavaType lavaType = lavaTypeFromOverride(overlayOverride, worldPos);
		if (lavaType != null)
			return lavaType;

		lavaType = lavaTypeFromOverride(underlayOverride, worldPos);
		if (lavaType != null)
			return lavaType;

		if (tile.getSceneTileModel() != null) {
			var model = tile.getSceneTileModel();
			int shape = model.getShape();
			if (shape > 0) {
				for (int face = 0; face < model.getFaceX().length; face++) {
					if (isOverlayFace(tile, face)) {
						lavaType = lavaTypeFromOverride(overlayOverride, worldPos);
						if (lavaType != null)
							return lavaType;
						break;
					}
				}
			}
		}

		var combinedOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos);
		if (isLavaGroundMaterial(combinedOverride))
			return lavaTypeFromOverride(combinedOverride, worldPos);

		if (tile.getSceneTilePaint() != null && tile.getSceneTilePaint().getTexture() == VANILLA_LAVA_TEXTURE_ID)
			return findLavaTypeByName("LAVA");

		return null;
	}

	private LavaType lavaTypeFromOverride(TileOverride override, int[] worldPos) {
		if (!isLavaGroundMaterial(override))
			return null;

		Material material = override.groundMaterial.getRandomMaterial(worldPos);
		if (material != null && material.hasShaderLava())
			return material.getLavaType();

		for (Material candidate : override.groundMaterial.getMaterials()) {
			if (candidate != null && candidate.hasShaderLava())
				return candidate.getLavaType();
		}

		return null;
	}

	private static LavaType findLavaTypeByName(String name) {
		for (LavaType lavaType : LavaTypeManager.LAVA_TYPES) {
			if (name.equals(lavaType.name))
				return lavaType;
		}
		return null;
	}

	private boolean isLavaGroundMaterial(TileOverride override) {
		if (override == null || override == TileOverride.NONE)
			return false;

		GroundMaterial groundMaterial = override.groundMaterial;
		if (groundMaterial == null || groundMaterial == GroundMaterial.NONE)
			return false;

		if (groundMaterial.hasShaderLava())
			return true;

		String name = groundMaterial.name;
		return name != null && name.contains("LAVA");
	}
}
