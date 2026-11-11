package rs117.hd.scene.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

@Slf4j
@Singleton
public class PlayerEquipmentParts {
	private static final int[] UNSUPPORTED = new int[0];
	private static final int EMPTY_HAND = -512;
	private static final String[] SLOT_NAMES = {
		"Head", "Cape", "Amulet", "Weapon", "Body", "Shield", "Arms", "Legs",
		"Hair", "Hands", "Feet", "Jaw"
	};

	@Inject
	private Client client;

	private final Map<Integer, int[]> wornModels = new HashMap<>();
	private final Map<Integer, SourceMesh> sourceMeshes = new HashMap<>();

	static final class Part {
		final int itemId;
		final int modelId;
		final int firstFace;
		final int faceCount;
		final String label;

		Part(int itemId, int modelId, int firstFace, int faceCount, String label) {
			this.itemId = itemId;
			this.modelId = modelId;
			this.firstFace = firstFace;
			this.faceCount = faceCount;
			this.label = label;
		}
	}

	public int[] effectiveEquipment(Player player) {
		int[] equipment = player.getPlayerComposition().getEquipmentIds().clone();
		int animation = player.getAnimation();
		if (animation != -1) {
			var sequence = client.loadAnimation(animation);
			if (sequence == null)
				return null;
			equipment[3] = handEquipment(sequence.getRightHandItem(), equipment[3]);
			equipment[5] = handEquipment(sequence.getLeftHandItem(), equipment[5]);
		}
		return equipment;
	}

	@Nullable
	public ModelSnapshot capture(Player player) {
		var composition = player.getPlayerComposition();
		if (composition == null || composition.getTransformedNpcId() != -1)
			return null;
		return capture(player, effectiveEquipment(player), composition.getGender());
	}

	@Nullable
	ModelSnapshot capture(Player player, @Nullable int[] equipment, int gender) {
		assert client.isClientThread();
		if (equipment == null)
			return null;

		List<Part> parts = new ArrayList<>();
		int face = 0;
		for (int slot = 0; slot < equipment.length; slot++) {
			int value = equipment[slot];
			if (value < PlayerComposition.KIT_OFFSET)
				continue;
			boolean item = value >= PlayerComposition.ITEM_OFFSET;
			int id = value - (item ? PlayerComposition.ITEM_OFFSET : PlayerComposition.KIT_OFFSET);
			int[] models = wornModels.computeIfAbsent(value * 2 + gender, key -> {
				byte[] data = client.getIndexConfig().loadData(item ? 10 : 3, id);
				if (data == null)
					return null;
				try {
					int[] result = item ? PlayerModelDefinitions.readWornModels(data, gender == 1) :
						PlayerModelDefinitions.readKitModels(data);
					return result == null ? new int[0] : result;
				} catch (IllegalArgumentException | java.nio.BufferUnderflowException ex) {
					log.debug("Cannot resolve player equipment definition {}", id, ex);
					return UNSUPPORTED;
				}
			});
			if (models == null || models == UNSUPPORTED)
				return null;

			for (int modelId : models) {
				if (modelId == -1)
					break;
				SourceMesh source = sourceMeshes.computeIfAbsent(modelId, key -> {
					var model = client.loadModelData(key);
					return model == null ? null : new SourceMesh(model);
				});
				if (source == null)
					return null;
				int count = source.faceCount;
				if (item && count > 0) {
					String slotName = slot < SLOT_NAMES.length ? SLOT_NAMES[slot] : "Slot " + slot;
					var definition = client.getItemDefinition(id);
					String name = definition == null ? "Item " + id : definition.getName();
					parts.add(new Part(id, modelId, face, count, slotName + ": " + name));
				}
				face += count;
			}
		}

		// Spot animation geometry is appended after equipment. Read it before asking for the
		// assembled model because the client may reuse its temporary animation buffers.
		for (ActorSpotAnim effect : player.getSpotAnims()) {
			if (effect.getFrame() >= 0 && effect.getStartCycle() <= client.getGameCycle()) {
				var model = effect.getModel();
				if (model == null)
					return null;
				face += model.getFaceCount();
			}
		}
		var model = player.getModel();
		if (model == null || model.getFaceCount() != face)
			return null;
		for (Part part : parts)
			if (!sourceMeshes.get(part.modelId).matches(model, part.firstFace))
				return null;
		return ModelSnapshot.capture(model, parts);
	}

	static final class SourceMesh {
		final int vertexCount;
		final int faceCount;
		final int[] first;
		final int[] second;
		final int[] third;

		SourceMesh(ModelData source) {
			vertexCount = source.getVerticesCount();
			faceCount = source.getFaceCount();
			first = Arrays.copyOf(source.getFaceIndices1(), faceCount);
			second = Arrays.copyOf(source.getFaceIndices2(), faceCount);
			third = Arrays.copyOf(source.getFaceIndices3(), faceCount);
		}

		boolean matches(Model model, int start) {
			int[] mapped = new int[vertexCount];
			Arrays.fill(mapped, -1);
			int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
			for (int i = 0; i < faceCount; i++) {
				int face = start + i;
				if (!mapVertex(mapped, first[i], a[face]) || !mapVertex(mapped, second[i], b[face]) ||
					!mapVertex(mapped, third[i], c[face]))
					return false;
			}
			return true;
		}

		private static boolean mapVertex(int[] mapped, int source, int assembled) {
			if (mapped[source] == -1)
				mapped[source] = assembled;
			return mapped[source] == assembled;
		}
	}

	private static int handEquipment(int item, int equipped) {
		if (item == -1)
			return equipped;
		if (item == EMPTY_HAND)
			return 0;
		return item >= 0 ? item + PlayerComposition.ITEM_OFFSET : equipped;
	}
}
