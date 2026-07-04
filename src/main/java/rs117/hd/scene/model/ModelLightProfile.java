package rs117.hd.scene.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import rs117.hd.utils.GsonUtils;

@Getter
@Setter
@GsonUtils.ExcludeDefaults
public class ModelLightProfile {
	private String name;
	private String meshKey;
	private boolean enabled = true;
	private Map<Integer, TriangleAnchor> triangles = new LinkedHashMap<>();
	private Map<Integer, String> vertices = new LinkedHashMap<>();
	private Set<Integer> itemIds = new HashSet<>();
	private Set<Integer> npcIds = new HashSet<>();
	private String lightDescription = "Torch";
	private float offsetX;
	private float offsetY;
	private float offsetZ;

	public ModelLightProfile() {}

	public ModelLightProfile(String name) {
		this.name = name;
	}

	public boolean hasTriangle(int localFace) {
		return triangles.containsKey(localFace);
	}

	public boolean hasVertex(int localVertex) {
		return vertices.containsKey(localVertex);
	}

	public String lightDescriptionForTriangle(int localFace) {
		TriangleAnchor anchor = triangles.get(localFace);
		if (anchor == null)
			return lightDescription;
		String light = anchor.getLight();
		return light != null && !light.isEmpty() ? light : lightDescription;
	}

	public String lightDescriptionForVertex(int localVertex) {
		String light = vertices.get(localVertex);
		return light != null && !light.isEmpty() ? light : lightDescription;
	}

	public Set<Integer> triangleIndices() {
		return Set.copyOf(triangles.keySet());
	}

	public Set<Integer> vertexIndices() {
		return Set.copyOf(vertices.keySet());
	}

	public boolean isNpcProfile() {
		return !npcIds.isEmpty();
	}

	public ModelLightProfile copy() {
		ModelLightProfile c = new ModelLightProfile(name);
		c.meshKey = meshKey;
		c.enabled = enabled;
		c.vertices = new LinkedHashMap<>(vertices);
		c.triangles = new LinkedHashMap<>();
		triangles.forEach((k, v) -> c.triangles.put(k, v.copy()));
		c.itemIds = new HashSet<>(itemIds);
		c.npcIds = new HashSet<>(npcIds);
		c.lightDescription = lightDescription;
		c.offsetX = offsetX;
		c.offsetY = offsetY;
		c.offsetZ = offsetZ;
		return c;
	}

	public void copySettingsFrom(ModelLightProfile other) {
		lightDescription = other.lightDescription;
		vertices = new LinkedHashMap<>(other.vertices);
		triangles = new LinkedHashMap<>();
		other.triangles.forEach((k, v) -> triangles.put(k, v.copy()));
		offsetX = other.offsetX;
		offsetY = other.offsetY;
		offsetZ = other.offsetZ;
		itemIds = new HashSet<>(other.itemIds);
		npcIds = new HashSet<>(other.npcIds);
	}
}
