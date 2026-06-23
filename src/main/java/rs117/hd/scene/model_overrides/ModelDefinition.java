package rs117.hd.scene.model_overrides;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import rs117.hd.scene.ModelDefinitionManager;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.collections.Int2ObjectHashMap;

@Slf4j
@NoArgsConstructor
public class ModelDefinition {
	public String name;
	public String description;

	public int[] modelIds = new int[0];
	public int contrast = 0;
	public int ambient = 0;

	public int offsetX = 0;
	public int offsetY = 0;
	public int offsetZ = 0;

	public int modelSizeX = 128;
	public int modelSizeY = 128;
	public int modelHeight = 128;

	public boolean rotated = false;

	public int[] recolorFrom;
	public int[] recolorTo;
	public short[] retextureFrom;
	public short[] retextureTo;

	/** Named sub-models merged together. Each entry inherits unset fields from this definition. */
	public Map<String, ModelPartDefinition> models;

	private static final int[] ROTATE_TYPE_INDICES = { 6, 7, 8, 9, 13, 14, 19 };
	public static final int[] PRELOAD_TYPES = { 0, 2, 4, 6, 7, 8, 9, 10, 11, 13, 14, 19, 22 };

	private static final Int2ObjectHashMap<ModelData> modelDataCache = new Int2ObjectHashMap<>();
	private static final Int2ObjectHashMap<Model> orientedModelCache = new Int2ObjectHashMap<>();

	public static void preloadModelData(Client client) {
		synchronized (modelDataCache) {
			for (ModelDefinition def : ModelDefinitionManager.MODEL_MAP.values()) {
				for (int modelId : def.collectModelIds()) {
					if (modelDataCache.containsKey(modelId))
						continue;

					ModelData model = client.loadModelData(modelId);
					if (model == null)
						continue;

					modelDataCache.put(modelId, model);
				}
			}
		}
	}

	public static void release() {
		synchronized (modelDataCache) {
			modelDataCache.clear();
			modelDataCache.trimToSize();
		}
		synchronized (orientedModelCache) {
			orientedModelCache.clear();
			orientedModelCache.trimToSize();
		}
	}

	public int[] collectModelIds() {
		var ids = new LinkedHashMap<Integer, Boolean>();
		for (int modelId : modelIds)
			ids.put(modelId, true);

		if (models != null) {
			for (ModelPartDefinition part : models.values()) {
				if (part.modelIds == null)
					continue;
				for (int modelId : part.modelIds)
					ids.put(modelId, true);
			}
		}

		return ids.keySet().stream().mapToInt(Integer::intValue).toArray();
	}

	public void validate(String path) throws IOException {
		if (hasCompositeParts()) {
			if (models.isEmpty())
				throw new IOException("Replacement model '" + name + "' has an empty models map in " + path);

			for (var entry : models.entrySet()) {
				ModelDefinition merged = entry.getValue().mergeInto(this, entry.getKey());
				if (merged.modelIds == null || merged.modelIds.length == 0)
					throw new IOException(
						"Replacement model '" + name + "' part '" + entry.getKey() + "' has no modelIds in " + path
					);
			}
		} else if (modelIds == null || modelIds.length == 0) {
			throw new IOException("Replacement model '" + name + "' is missing modelIds in " + path);
		}
	}

	private boolean hasCompositeParts() {
		return models != null && !models.isEmpty();
	}

	private List<ModelDefinition> resolveParts() {
		if (!hasCompositeParts())
			return List.of(this);

		var resolved = new ArrayList<ModelDefinition>(models.size());
		for (var entry : models.entrySet())
			resolved.add(entry.getValue().mergeInto(this, entry.getKey()));
		return resolved;
	}

	private static boolean shouldRotateType(int type) {
		for (int rotateType : ROTATE_TYPE_INDICES) {
			if (type == rotateType)
				return true;
		}
		return false;
	}

	public final Model getModel(Client client, int type, int orientation) {
		boolean rotate45 = shouldRotateType(type);
		int cacheKey = packOrientedModelCacheKey(type, orientation, rotate45);

		synchronized (orientedModelCache) {
			Model cached = orientedModelCache.get(cacheKey);
			if (cached != null)
				return cached;

			ModelData data = loadAndMergeWithRotation(client, type, orientation, rotate45);
			if (data == null)
				return null;

			Model model = data.shallowCopy().light(ambient + 64, contrast + 768, -50, -10, -50);
			orientedModelCache.put(cacheKey, model);
			return model;
		}
	}

	private int packOrientedModelCacheKey(int type, int orientation, boolean rotate45) {
		int definitionKey = name != null ? name.hashCode() : System.identityHashCode(this);
		return definitionKey
			^ (type << 24)
			^ (orientation << 20)
			^ (rotate45 ? 1 << 19 : 0);
	}

	private ModelData loadAndMergeWithRotation(Client client, int type, int orientation, boolean rotate45) {
		List<ModelDefinition> parts = resolveParts();
		if (parts.isEmpty())
			return null;

		ModelData[] partDatas = new ModelData[parts.size()];
		for (int i = 0; i < parts.size(); i++) {
			partDatas[i] = parts.get(i).buildPartModelData(client, type, orientation, rotate45);
			if (partDatas[i] == null)
				return null;
		}

		ModelData result = partDatas.length == 1 ? partDatas[0] : client.mergeModels(partDatas);

		if (rotate45)
			result = rotate(result, 256).translate(45, 0, -45);

		switch (orientation & 3) {
			case 1:
				result.rotateY90Ccw();
				break;
			case 2:
				result.rotateY180Ccw();
				break;
			case 3:
				result.rotateY270Ccw();
				break;
		}

		return result;
	}

	private ModelData buildPartModelData(Client client, int type, int orientation, boolean rotate45) {
		if (modelIds.length == 0)
			return null;

		ModelData[] datas = new ModelData[modelIds.length];
		boolean rotate = rotated;

		if (type == 2 && orientation > 3)
			rotate = !rotate;

		for (int i = 0; i < datas.length; i++) {
			int modelId = modelIds[i];
			ModelData model;
			synchronized (modelDataCache) {
				model = modelDataCache.get(modelId);
				if (model == null) {
					model = client.loadModelData(modelId);
					if (model != null)
						modelDataCache.put(modelId, model);
				}
			}
			if (model == null)
				return null;
			if (rotate)
				model.rotateY90Ccw();
			datas[i] = model;
		}

		ModelData result = client.mergeModels(datas);

		if (modelSizeX != 128 || modelHeight != 128 || modelSizeY != 128)
			result.scale(modelSizeX, modelHeight, modelSizeY);

		if (offsetX != 0 || offsetY != 0 || offsetZ != 0)
			result.translate(offsetX, offsetZ, offsetY);

		if (recolorFrom != null && recolorTo != null) {
			if (recolorFrom.length != recolorTo.length) {
				log.error("Mismatched recolor arrays in {}: from={}, to={}", name, recolorFrom.length, recolorTo.length);
			} else {
				for (int i = 0; i < recolorFrom.length; i++)
					result.recolor((short) recolorFrom[i], (short) recolorTo[i]);
			}
		}

		if (retextureFrom != null && retextureTo != null) {
			if (retextureFrom.length != retextureTo.length) {
				log.error(
					"Mismatched retexture arrays in {}: from={}, to={}",
					name,
					retextureFrom.length,
					retextureTo.length
				);
			} else {
				for (int i = 0; i < retextureFrom.length; i++)
					result.retexture(retextureFrom[i], retextureTo[i]);
			}
		}

		return result;
	}

	private static ModelData rotate(ModelData model, int angle) {
		int sin = Perspective.SINE[angle];
		int cos = Perspective.COSINE[angle];

		float[] xVerts = model.getVerticesX();
		float[] zVerts = model.getVerticesZ();

		for (int i = 0; i < xVerts.length; i++) {
			float x = xVerts[i];
			float z = zVerts[i];
			xVerts[i] = (cos * x + sin * z) / 65536.0f;
			zVerts[i] = (cos * z - sin * x) / 65536.0f;
		}

		return model;
	}

	@Override
	public String toString() {
		return name;
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<ModelDefinition> {
		@Override
		public ModelDefinition read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;

			if (in.peek() == JsonToken.STRING) {
				String name = in.nextString();
				ModelDefinition match = ModelDefinitionManager.MODEL_MAP.get(name);
				if (match != null)
					return match;
				log.error("Missing replacement model '{}' at {}", name, GsonUtils.location(in), new Throwable());
				return null;
			}

			log.error("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			return null;
		}

		@Override
		public void write(JsonWriter out, ModelDefinition model) throws IOException {
			if (model == null) {
				out.nullValue();
			} else {
				out.value(model.name);
			}
		}
	}
}
