package rs117.hd.scene.model.debug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.scene.model.ModelLightManager;
import rs117.hd.scene.model.ModelLightProfile;
import rs117.hd.scene.model.ModelLightStore;
import rs117.hd.scene.model.ModelSnapshot;

@Singleton
@Slf4j
public class ModelLightEditor implements ModelLightViewerFrame.Callbacks {
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ModelLightManager modelLightManager;

	@Inject
	private ModelLightStore store;

	@Nullable
	private ModelLightViewerFrame frame;

	@Nullable
	private ModelSnapshot viewerSnapshot;

	private boolean eventBusRegistered;

	private int recordTicksLeft;
	@Nullable
	private ModelSnapshot recordSnapshot;
	private final List<float[]> recordXs = new ArrayList<>();
	private final List<float[]> recordYs = new ArrayList<>();
	private final List<float[]> recordZs = new ArrayList<>();
	private final List<Integer> recordFrames = new ArrayList<>();

	private final Map<Integer, String> pieceProfileKeys = new HashMap<>();

	@Getter
	private String selectedLightDescription = "Torch";

	public void open() {
		openViewer(null);
	}

	public void openViewer(@Nullable String profileKey) {
		SwingUtilities.invokeLater(() -> {
			if (frame == null) {
				frame = new ModelLightViewerFrame(this);
				store.setImportListener(() -> SwingUtilities.invokeLater(this::onStoreImported));
				modelLightManager.setLightDefinitionsListener(
					() -> SwingUtilities.invokeLater(this::refreshLightDescriptions));
			}
			if (!eventBusRegistered) {
				eventBus.register(this);
				eventBusRegistered = true;
			}
			frame.setVisible(true);
			frame.toFront();
			refreshSnapshot();
		});
	}

	private void onStoreImported() {
		if (frame != null)
			frame.refreshMarkers();
	}

	private void refreshLightDescriptions() {
		if (frame != null)
			frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
	}

	@Override
	public void refreshSnapshot() {
		clientThread.invokeLater(() -> {
			ModelLightManager.ViewerCapture capture = modelLightManager.captureViewerSnapshot();
			ModelSnapshot snapshot = capture != null ? capture.snapshot : null;
			boolean startRecording = capture != null && capture.startRecording;
			SwingUtilities.invokeLater(() -> {
				if (frame == null)
					return;
				viewerSnapshot = snapshot;
				pieceProfileKeys.clear();
				if (startRecording)
					startRecording();
				frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
				frame.setSnapshot(viewerSnapshot, "Player");
			});
		});
	}

	@Override
	public Set<Integer> litFaces(int pieceIndex) {
		if (viewerSnapshot == null)
			return Set.of();
		if (pieceIndex >= 0) {
			ModelSnapshot.Piece piece = viewerSnapshot.getPieces().get(pieceIndex);
			String profileKey = resolveProfileKey(pieceIndex, piece);
			if (profileKey == null)
				return Set.of();
			ModelLightProfile profile = store.get(profileKey);
			if (profile == null)
				return Set.of();
			Set<Integer> out = new HashSet<>();
			for (int localFace : profile.triangleIndices()) {
				if (localFace >= 0 && localFace < piece.getFaces().length)
					out.add(piece.getFaces()[localFace]);
			}
			return out;
		}

		Set<Integer> out = new HashSet<>();
		for (int i = 0; i < viewerSnapshot.getPieces().size(); i++) {
			out.addAll(litFaces(i));
		}
		return out;
	}

	@Override
	public Set<Integer> litVertices(int pieceIndex) {
		if (viewerSnapshot == null)
			return Set.of();
		if (pieceIndex >= 0) {
			ModelSnapshot.Piece piece = viewerSnapshot.getPieces().get(pieceIndex);
			String profileKey = resolveProfileKey(pieceIndex, piece);
			if (profileKey == null)
				return Set.of();
			ModelLightProfile profile = store.get(profileKey);
			if (profile == null)
				return Set.of();
			Set<Integer> out = new HashSet<>();
			for (var entry : profile.getVertices().entrySet()) {
				int local = entry.getKey();
				if (local >= 0 && local < piece.getVertices().length)
					out.add(piece.getVertices()[local]);
			}
			return out;
		}

		Set<Integer> out = new HashSet<>();
		for (int i = 0; i < viewerSnapshot.getPieces().size(); i++) {
			out.addAll(litVertices(i));
		}
		return out;
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (recordTicksLeft <= 0)
			return;
		sampleRecording();
	}

	private void startRecording() {
		recordSnapshot = null;
		recordXs.clear();
		recordYs.clear();
		recordZs.clear();
		recordFrames.clear();

		if (client.getLocalPlayer() == null)
			return;

		recordTicksLeft = 150;
	}

	private void sampleRecording() {
		recordTicksLeft--;
		Player local = client.getLocalPlayer();
		Model model = local == null ? null : local.getModel();
		int frame = local == null ? -1 : local.getAnimationFrame();

		if (model == null && !recordXs.isEmpty())
			recordTicksLeft = 0;

		if (model != null) {
			if (recordSnapshot == null)
				recordSnapshot = ModelSnapshot.capture(model);
			if (model.getVerticesCount() != recordSnapshot.getVertexCount()) {
				recordTicksLeft = 0;
				recordSnapshot = null;
				recordXs.clear();
				recordYs.clear();
				recordZs.clear();
				recordFrames.clear();
				return;
			}
			int count = recordSnapshot.getVertexCount();
			recordXs.add(Arrays.copyOf(model.getVerticesX(), count));
			recordYs.add(Arrays.copyOf(model.getVerticesY(), count));
			recordZs.add(Arrays.copyOf(model.getVerticesZ(), count));
			recordFrames.add(frame);
		}

		if (recordTicksLeft == 0)
			finishRecording();
	}

	private void finishRecording() {
		ModelSnapshot snapshot = recordSnapshot;
		recordSnapshot = null;
		if (snapshot == null || recordXs.isEmpty())
			return;

		float[][] xs = recordXs.toArray(new float[0][]);
		float[][] ys = recordYs.toArray(new float[0][]);
		float[][] zs = recordZs.toArray(new float[0][]);
		int[] frames = new int[recordFrames.size()];
		for (int i = 0; i < frames.length; i++)
			frames[i] = recordFrames.get(i);
		recordXs.clear();
		recordYs.clear();
		recordZs.clear();
		recordFrames.clear();

		SwingUtilities.invokeLater(() -> {
			if (frame != null)
				frame.setRecording(xs, ys, zs, frames);
		});
	}

	@Override
	public void pickClicked(ViewportPanel.Pick pick, ViewportPanel.PickAction action) {
		if (viewerSnapshot == null || frame == null)
			return;

		if (pick.target == ViewportPanel.PickTarget.FACE)
			handleFacePick(pick, action);
		else
			handlePointPick(pick, action);
	}

	private void handleFacePick(ViewportPanel.Pick pick, ViewportPanel.PickAction action) {
		int preferredPiece = frame.getAppliedPieceIndex();
		ModelSnapshot.Piece piece = viewerSnapshot.pieceForFace(pick.globalIndex, preferredPiece);
		if (piece == null)
			return;

		int localFace = piece.localFaceIndex(pick.globalIndex);
		if (localFace < 0)
			return;

		String profileKey = profileKeyFor(piece);
		if (profileKey == null)
			return;

		if (action == ViewportPanel.PickAction.ADD) {
			if (!store.hasTriangle(profileKey, localFace)) {
				store.toggleTriangle(profileKey, localFace, pick.bary0, pick.bary1, pick.bary2);
				store.setTriangleLightDescription(profileKey, localFace, pickLightDescription());
			}
			frame.refreshMarkers();
			return;
		}

		if (!store.hasTriangle(profileKey, localFace))
			return;

		if (action == ViewportPanel.PickAction.REMOVE) {
			store.toggleTriangle(profileKey, localFace, pick.bary0, pick.bary1, pick.bary2);
		} else if (action == ViewportPanel.PickAction.CHANGE) {
			store.setTriangleLightDescription(profileKey, localFace, pickLightDescription());
		}
		frame.refreshMarkers();
	}

	private void handlePointPick(ViewportPanel.Pick pick, ViewportPanel.PickAction action) {
		int preferredPiece = frame.getAppliedPieceIndex();
		ModelSnapshot.Piece piece = viewerSnapshot.pieceForVertex(pick.globalIndex, preferredPiece);
		if (piece == null)
			return;

		int local = piece.localIndexOf(pick.globalIndex);
		if (local < 0)
			return;

		String profileKey = profileKeyFor(piece);
		if (profileKey == null)
			return;

		if (action == ViewportPanel.PickAction.ADD) {
			if (!store.hasVertex(profileKey, local)) {
				store.toggleVertex(profileKey, local);
				store.setVertexLightDescription(profileKey, local, pickLightDescription());
			}
			frame.refreshMarkers();
			return;
		}

		if (!store.hasVertex(profileKey, local))
			return;

		if (action == ViewportPanel.PickAction.REMOVE) {
			store.toggleVertex(profileKey, local);
		} else if (action == ViewportPanel.PickAction.CHANGE) {
			store.setVertexLightDescription(profileKey, local, pickLightDescription());
		}
		frame.refreshMarkers();
	}

	@Override
	public void boxSelected(Set<Integer> globalIndices, boolean add, ViewportPanel.PickTarget target) {
		if (viewerSnapshot == null || frame == null)
			return;

		int preferredPiece = frame.getAppliedPieceIndex();
		if (target == ViewportPanel.PickTarget.FACE) {
			for (int globalFace : globalIndices) {
				ModelSnapshot.Piece piece = viewerSnapshot.pieceForFace(globalFace, preferredPiece);
				if (piece == null)
					continue;
				int localFace = piece.localFaceIndex(globalFace);
				if (localFace < 0)
					continue;
				String profileKey = profileKeyFor(piece);
				if (profileKey == null)
					continue;
				if (add) {
					if (!store.hasTriangle(profileKey, localFace)) {
						store.toggleTriangle(profileKey, localFace, 1f / 3f, 1f / 3f, 1f / 3f);
						store.setTriangleLightDescription(profileKey, localFace, pickLightDescription());
					}
				} else if (store.hasTriangle(profileKey, localFace)) {
					store.toggleTriangle(profileKey, localFace, 1f / 3f, 1f / 3f, 1f / 3f);
				}
			}
		} else {
			for (int globalVertex : globalIndices) {
				ModelSnapshot.Piece piece = viewerSnapshot.pieceForVertex(globalVertex, preferredPiece);
				if (piece == null)
					continue;
				int local = piece.localIndexOf(globalVertex);
				if (local < 0)
					continue;
				String profileKey = profileKeyFor(piece);
				if (profileKey == null)
					continue;
				if (add) {
					if (!store.hasVertex(profileKey, local)) {
						store.toggleVertex(profileKey, local);
						store.setVertexLightDescription(profileKey, local, pickLightDescription());
					}
				} else if (store.hasVertex(profileKey, local)) {
					store.toggleVertex(profileKey, local);
				}
			}
		}
		frame.refreshMarkers();
	}

	@Override
	public List<String> getLightDescriptions() {
		return modelLightManager.getAvailableLightDescriptions();
	}

	@Override
	public void onLightBrushChanged(String description) {
		if (description != null && !description.isEmpty())
			selectedLightDescription = description;
	}

	@Override
	public String getPieceName(int pieceIndex) {
		ModelLightProfile profile = profileForPiece(pieceIndex);
		if (profile != null && profile.getName() != null && !profile.getName().isEmpty())
			return profile.getName();
		return "Piece " + (pieceIndex + 1);
	}

	@Override
	public String getPieceItemIdsText(int pieceIndex) {
		ModelLightProfile profile = profileForPiece(pieceIndex);
		if (profile == null || profile.getItemIds().isEmpty())
			return "";
		return profile.getItemIds().stream()
			.sorted()
			.map(String::valueOf)
			.collect(Collectors.joining(", "));
	}

	@Override
	public void applyPieceSettings(int pieceIndex, String name, String itemIdsText) {
		if (viewerSnapshot == null || pieceIndex < 0 || pieceIndex >= viewerSnapshot.getPieces().size())
			return;

		ModelSnapshot.Piece piece = viewerSnapshot.getPieces().get(pieceIndex);
		Set<Integer> itemIds = parseItemIds(itemIdsText);
		String trimmedName = name == null ? "" : name.trim();
		String defaultName = trimmedName.isEmpty() ? "Piece " + (pieceIndex + 1) : trimmedName;

		@Nullable String existingKey = store.findProfileKey(piece.getMeshKey(), itemIds);
		String profileKey;
		if (existingKey != null) {
			profileKey = existingKey;
			store.setItemIds(profileKey, itemIds);
			store.rename(profileKey, trimmedName.isEmpty() ? null : trimmedName);
		} else {
			@Nullable String currentKey = pieceProfileKeys.get(pieceIndex);
			ModelLightProfile current = currentKey != null ? store.get(currentKey) : null;
			if (current != null
				&& piece.getMeshKey().equals(current.getMeshKey())
				&& !current.isNpcProfile()) {
				profileKey = currentKey;
				store.setItemIds(profileKey, itemIds);
				store.rename(profileKey, trimmedName.isEmpty() ? null : trimmedName);
			} else {
				profileKey = store.ensureProfileFor(piece.getMeshKey(), defaultName, itemIds);
				if (!trimmedName.isEmpty())
					store.rename(profileKey, trimmedName);
			}
		}

		pieceProfileKeys.put(pieceIndex, profileKey);
		if (frame != null) {
			frame.refreshMarkers();
			frame.rebuildPieceListRow(pieceIndex);
		}
	}

	@Override
	public String getPieceListLabel(int pieceIndex) {
		if (viewerSnapshot == null || pieceIndex < 0 || pieceIndex >= viewerSnapshot.getPieces().size())
			return "Piece " + (pieceIndex + 1);

		ModelSnapshot.Piece piece = viewerSnapshot.getPieces().get(pieceIndex);
		String name = getPieceName(pieceIndex);
		String itemSuffix = formatItemSuffix(profileForPiece(pieceIndex));
		return name + " (" + piece.getVertices().length + "v, " + piece.getFaces().length + "f)" + itemSuffix;
	}

	@Override
	public void saveChanges() {
		try {
			var path = store.saveToDisk();
			String resourcePath = "src/main/resources/rs117/hd/scene/model/model_lights.json";
			String message = path.equals(ModelLightStore.getResourcePath())
				? "Saved to:\n" + path + "\n\nCommit this file to ship the changes."
				: "Exported to:\n" + path
					+ "\n\nCopy into " + resourcePath + " and rebuild to ship the changes.";
			JOptionPane.showMessageDialog(frame, message, "Model lights saved", JOptionPane.INFORMATION_MESSAGE);
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(
				frame,
				"Failed to save model lights:\n" + ex.getMessage(),
				"Save failed",
				JOptionPane.ERROR_MESSAGE
			);
		}
	}

	public Map<String, ModelLightProfile> getProfiles() {
		return store.snapshotAll();
	}

	public void setProfileEnabled(String profileKey, boolean enabled) {
		store.setEnabled(profileKey, enabled);
	}

	public void setAllProfilesEnabled(boolean enabled) {
		store.setAllEnabled(enabled);
	}

	private String pickLightDescription() {
		return frame != null ? frame.getPickLightDescription() : selectedLightDescription;
	}

	private static boolean isPlayerProfile(ModelLightProfile profile) {
		return !profile.isNpcProfile();
	}

	@Nullable
	private String profileKeyFor(ModelSnapshot.Piece piece) {
		int pieceIndex = viewerSnapshot != null ? viewerSnapshot.getPieces().indexOf(piece) : -1;
		if (pieceIndex >= 0) {
			@Nullable String key = resolveProfileKey(pieceIndex, piece);
			if (key != null)
				return key;
		}

		String fallback = pieceIndex >= 0 ? "Piece " + (pieceIndex + 1) : piece.getVertices().length + "v";
		Set<Integer> itemIds = frame != null ? parseItemIds(frame.getItemIdsFieldText()) : Set.of();
		return store.ensureProfileFor(piece.getMeshKey(), fallback, itemIds);
	}

	@Nullable
	private ModelLightProfile profileForPiece(int pieceIndex) {
		if (viewerSnapshot == null || pieceIndex < 0 || pieceIndex >= viewerSnapshot.getPieces().size())
			return null;
		ModelSnapshot.Piece piece = viewerSnapshot.getPieces().get(pieceIndex);
		@Nullable String profileKey = resolveProfileKey(pieceIndex, piece);
		return profileKey == null ? null : store.get(profileKey);
	}

	@Nullable
	private String resolveProfileKey(int pieceIndex, ModelSnapshot.Piece piece) {
		@Nullable String cached = pieceProfileKeys.get(pieceIndex);
		if (cached != null) {
			ModelLightProfile cachedProfile = store.get(cached);
			if (cachedProfile != null
				&& piece.getMeshKey().equals(cachedProfile.getMeshKey())
				&& isPlayerProfile(cachedProfile))
				return cached;
		}

		Set<Integer> itemIds = frame != null && frame.getAppliedPieceIndex() == pieceIndex
			? parseItemIds(frame.getItemIdsFieldText())
			: Set.of();

		@Nullable String exact = store.findProfileKey(piece.getMeshKey(), itemIds);
		if (exact != null) {
			ModelLightProfile profile = store.get(exact);
			if (profile != null && isPlayerProfile(profile)) {
				pieceProfileKeys.put(pieceIndex, exact);
				return exact;
			}
		}

		for (Map.Entry<String, ModelLightProfile> entry : store.snapshotAll().entrySet()) {
			ModelLightProfile profile = entry.getValue();
			if (!isPlayerProfile(profile) || !piece.getMeshKey().equals(profile.getMeshKey()))
				continue;
			pieceProfileKeys.put(pieceIndex, entry.getKey());
			return entry.getKey();
		}
		return null;
	}

	private static Set<Integer> parseItemIds(@Nullable String text) {
		if (text == null || text.trim().isEmpty())
			return Set.of();
		Set<Integer> ids = new HashSet<>();
		for (String part : text.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty())
				continue;
			try {
				ids.add(Integer.parseInt(trimmed));
			} catch (NumberFormatException ignored) {
			}
		}
		return ids;
	}

	@Nullable
	private static String formatItemSuffix(@Nullable ModelLightProfile profile) {
		if (profile == null || profile.getItemIds().isEmpty())
			return "";
		String ids = profile.getItemIds().stream()
			.sorted()
			.map(String::valueOf)
			.collect(Collectors.joining(", "));
		return " [item " + ids + "]";
	}
}
