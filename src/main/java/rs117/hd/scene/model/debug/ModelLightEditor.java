package rs117.hd.scene.model.debug;

import java.io.IOException;
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
import rs117.hd.scene.LightManager;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.debug.LightMeshCapture;
import rs117.hd.scene.lights.debug.LightViewerFrame;
import rs117.hd.scene.model.ModelLightManager;
import rs117.hd.scene.model.ModelLightProfile;
import rs117.hd.scene.model.ModelLightStore;
import rs117.hd.scene.model.ModelSnapshot;
import rs117.hd.scene.model.TriangleAnchor;

@Singleton
@Slf4j
public class ModelLightEditor implements LightViewerFrame.Callbacks {
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

	@Inject
	private LightManager lightManager;

	@Inject
	private LightMeshCapture meshCapture;

	@Nullable
	private LightViewerFrame frame;

	@Nullable
	private ModelSnapshot viewerSnapshot;

	private boolean eventBusRegistered;
	private int captureMode;
	private int viewerObjectId = -1;
	private int viewerNpcId = -1;
	private int viewerGraphicId = -1;

	private int recordTicksLeft;
	@Nullable
	private ModelSnapshot recordSnapshot;
	private final java.util.List<float[]> recordXs = new java.util.ArrayList<>();
	private final java.util.List<float[]> recordYs = new java.util.ArrayList<>();
	private final java.util.List<float[]> recordZs = new java.util.ArrayList<>();
	private final java.util.List<Integer> recordFrames = new java.util.ArrayList<>();

	private final Map<Integer, String> pieceProfileKeys = new HashMap<>();
	private List<LightMeshCapture.Sighting> cachedObjectSightings = List.of();
	private List<LightMeshCapture.Sighting> cachedNpcSightings = List.of();

	@Getter
	private String selectedLightDescription = "Torch";

	public void open() {
		openViewer(null);
	}

	public void openViewer(@Nullable String profileKey) {
		SwingUtilities.invokeLater(() -> {
			if (frame == null) {
				frame = new LightViewerFrame(this);
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
			frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
			refreshSnapshot();
		});
	}

	private void onStoreImported() {
		if (frame != null)
			frame.refreshMarkers();
	}

	private void refreshLightDescriptions() {
		if (frame != null) {
			frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
			frame.refreshDefinitionList(null);
		}
	}

	@Override
	public void refreshSightings() {
		clientThread.invokeLater(() -> {
			cachedObjectSightings = meshCapture.objectSightings();
			cachedNpcSightings = meshCapture.npcSightings();
			SwingUtilities.invokeLater(() -> {
				if (frame != null)
					frame.rebuildSightingsList();
			});
		});
	}

	@Override
	public void refreshSnapshot() {
		SwingUtilities.invokeLater(() -> {
			if (frame != null)
				frame.setLoading(true);
		});
		clientThread.invokeLater(() -> {
			if (captureMode == 1 || captureMode == 2) {
				cachedObjectSightings = meshCapture.objectSightings();
				cachedNpcSightings = meshCapture.npcSightings();
			}
			captureSnapshot();
		});
	}

	private void captureSnapshot() {
		LightMeshCapture.CaptureResult capture;
		switch (captureMode) {
			case 1:
				capture = viewerObjectId >= 0
					? meshCapture.captureObject(viewerObjectId)
					: meshCapture.capturePlayer();
				break;
			case 2:
				capture = viewerNpcId >= 0
					? meshCapture.captureNpc(viewerNpcId)
					: meshCapture.capturePlayer();
				break;
			case 3:
				capture = viewerGraphicId >= 0
					? meshCapture.captureGraphic(viewerGraphicId)
					: meshCapture.capturePlayer();
				break;
			default:
				capture = meshCapture.capturePlayer();
		}
		LightMeshCapture.CaptureResult result = capture;
		SwingUtilities.invokeLater(() -> {
			if (frame == null)
				return;
			frame.setLoading(false);
			if (result == null && captureMode > 0) {
				frame.showHint("Could not capture mesh — try Refresh, then Load again");
				return;
			}
			viewerSnapshot = result != null ? result.snapshot : null;
			pieceProfileKeys.clear();
			if (result != null && result.startRecording)
				startRecording();
			frame.setSnapshot(viewerSnapshot, result != null ? result.targetLabel : null);
		});
	}

	@Override
	public void playerViewSelected() {
		captureMode = 0;
		viewerObjectId = -1;
		viewerNpcId = -1;
		viewerGraphicId = -1;
	}

	@Override
	public void loadObject(int objectId) {
		if (captureMode == 1 && viewerObjectId == objectId && viewerSnapshot != null)
			return;
		captureMode = 1;
		viewerObjectId = objectId;
		viewerNpcId = -1;
		viewerGraphicId = -1;
		SwingUtilities.invokeLater(() -> {
			if (frame != null)
				frame.setLoading(true);
		});
		clientThread.invokeLater(this::captureSnapshot);
	}

	@Override
	public void loadNpc(int npcId) {
		if (captureMode == 2 && viewerNpcId == npcId && viewerSnapshot != null)
			return;
		captureMode = 2;
		viewerNpcId = npcId;
		viewerObjectId = -1;
		viewerGraphicId = -1;
		SwingUtilities.invokeLater(() -> {
			if (frame != null)
				frame.setLoading(true);
		});
		clientThread.invokeLater(this::captureSnapshot);
	}

	@Override
	public void loadGraphic(int graphicId) {
		if (captureMode == 3 && viewerGraphicId == graphicId && viewerSnapshot != null)
			return;
		captureMode = 3;
		viewerGraphicId = graphicId;
		viewerObjectId = -1;
		viewerNpcId = -1;
		SwingUtilities.invokeLater(() -> {
			if (frame != null)
				frame.setLoading(true);
		});
		clientThread.invokeLater(this::captureSnapshot);
	}

	@Override
	public void poseAnimation(int animId) {
		if (viewerNpcId < 0) {
			if (frame != null)
				frame.selectAnchor(null);
			JOptionPane.showMessageDialog(
				frame,
				"Pose needs an NPC snapshot. Load an NPC first.",
				"Pose",
				JOptionPane.INFORMATION_MESSAGE
			);
			return;
		}
		// NPC cache posing can be added later; for now use live recording
		refreshSnapshot();
	}

	@Override
	public List<LightMeshCapture.Sighting> getObjectSightings() {
		return cachedObjectSightings;
	}

	@Override
	public List<LightMeshCapture.Sighting> getNpcSightings() {
		return cachedNpcSightings;
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
		for (int i = 0; i < viewerSnapshot.getPieces().size(); i++)
			out.addAll(litFaces(i));
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
		for (int i = 0; i < viewerSnapshot.getPieces().size(); i++)
			out.addAll(litVertices(i));
		return out;
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (recordTicksLeft <= 0)
			return;
		sampleRecording();
	}

	private void startRecording() {
		releaseRecordSnapshot();
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
		int frameNum = local == null ? -1 : local.getAnimationFrame();

		if (model == null && !recordXs.isEmpty())
			recordTicksLeft = 0;

		if (model != null) {
			if (recordSnapshot == null)
				recordSnapshot = ModelSnapshot.capture(model);
			if (model.getVerticesCount() != recordSnapshot.getVertexCount()) {
				recordTicksLeft = 0;
				releaseRecordSnapshot();
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
			recordFrames.add(frameNum);
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
		snapshot.release();
	}

	private void releaseRecordSnapshot() {
		if (recordSnapshot != null) {
			recordSnapshot.release();
			recordSnapshot = null;
		}
	}

	@Override
	public void pickClicked(ViewportPanel.Pick pick, ViewportPanel.PickAction action) {
		if (viewerSnapshot == null || frame == null)
			return;

		if (action == ViewportPanel.PickAction.SELECT) {
			selectAnchorFromPick(pick);
			return;
		}

		if (pick.target == ViewportPanel.PickTarget.FACE)
			handleFacePick(pick, action);
		else
			handlePointPick(pick, action);
	}

	private void selectAnchorFromPick(ViewportPanel.Pick pick) {
		int preferredPiece = frame.getAppliedPieceIndex();
		if (pick.target == ViewportPanel.PickTarget.FACE) {
			ModelSnapshot.Piece piece = viewerSnapshot.pieceForFace(pick.globalIndex, preferredPiece);
			if (piece == null)
				return;
			int localFace = piece.localFaceIndex(pick.globalIndex);
			if (localFace < 0)
				return;
			int pieceIndex = viewerSnapshot.getPieces().indexOf(piece);
			frame.selectAnchor(new LightViewerFrame.AnchorSelection(pieceIndex, localFace, true));
		} else {
			ModelSnapshot.Piece piece = viewerSnapshot.pieceForVertex(pick.globalIndex, preferredPiece);
			if (piece == null)
				return;
			int local = piece.localIndexOf(pick.globalIndex);
			if (local < 0)
				return;
			int pieceIndex = viewerSnapshot.getPieces().indexOf(piece);
			frame.selectAnchor(new LightViewerFrame.AnchorSelection(pieceIndex, local, false));
		}
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
			selectAnchorFromPick(pick);
			return;
		}

		if (!store.hasTriangle(profileKey, localFace))
			return;

		if (action == ViewportPanel.PickAction.REMOVE) {
			store.toggleTriangle(profileKey, localFace, pick.bary0, pick.bary1, pick.bary2);
			frame.selectAnchor(null);
		} else if (action == ViewportPanel.PickAction.CHANGE) {
			store.setTriangleLightDescription(profileKey, localFace, pickLightDescription());
			selectAnchorFromPick(pick);
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
			selectAnchorFromPick(pick);
			return;
		}

		if (!store.hasVertex(profileKey, local))
			return;

		if (action == ViewportPanel.PickAction.REMOVE) {
			store.toggleVertex(profileKey, local);
			frame.selectAnchor(null);
		} else if (action == ViewportPanel.PickAction.CHANGE) {
			store.setVertexLightDescription(profileKey, local, pickLightDescription());
			selectAnchorFromPick(pick);
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
	public void anchorSelected(LightViewerFrame.AnchorSelection anchor) {
		if (frame != null)
			frame.selectAnchor(anchor);
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
		String trimmedName = name == null ? "" : name.trim();
		String defaultName = trimmedName.isEmpty() ? "Piece " + (pieceIndex + 1) : trimmedName;
		String profileKey;

		if (captureMode == 1 && viewerObjectId >= 0) {
			@Nullable String existingKey = store.findObjectProfileKey(piece.getMeshKey(), viewerObjectId);
			if (existingKey != null) {
				profileKey = existingKey;
				store.setObjectIds(profileKey, Set.of(viewerObjectId));
			} else {
				profileKey = store.ensureProfileForObject(piece.getMeshKey(), defaultName, viewerObjectId);
			}
			store.rename(profileKey, trimmedName.isEmpty() ? null : trimmedName);
		} else if (captureMode == 2 && viewerNpcId >= 0) {
			@Nullable String existingKey = store.findNpcProfileKey(piece.getMeshKey(), viewerNpcId);
			if (existingKey != null) {
				profileKey = existingKey;
				store.setNpcIds(profileKey, Set.of(viewerNpcId));
			} else {
				profileKey = store.ensureProfileForNpc(piece.getMeshKey(), defaultName, viewerNpcId);
			}
			store.rename(profileKey, trimmedName.isEmpty() ? null : trimmedName);
		} else {
			Set<Integer> itemIds = parseItemIds(itemIdsText);
			@Nullable String existingKey = store.findProfileKey(piece.getMeshKey(), itemIds);
			if (existingKey != null) {
				profileKey = existingKey;
				store.setItemIds(profileKey, itemIds);
				store.rename(profileKey, trimmedName.isEmpty() ? null : trimmedName);
			} else {
				@Nullable String currentKey = pieceProfileKeys.get(pieceIndex);
				ModelLightProfile current = currentKey != null ? store.get(currentKey) : null;
				if (current != null
					&& piece.getMeshKey().equals(current.getMeshKey())
					&& isEquipmentProfile(current)) {
					profileKey = currentKey;
					store.setItemIds(profileKey, itemIds);
					store.rename(profileKey, trimmedName.isEmpty() ? null : trimmedName);
				} else {
					profileKey = store.ensureProfileFor(piece.getMeshKey(), defaultName, itemIds);
					if (!trimmedName.isEmpty())
						store.rename(profileKey, trimmedName);
				}
			}
		}

		pieceProfileKeys.put(pieceIndex, profileKey);
		if (frame != null) {
			frame.refreshMarkers();
			frame.rebuildPieceListRow(pieceIndex);
		}
	}

	@Override
	public void applyProfileOffset(int pieceIndex, float x, float y, float z) {
		String profileKey = profileKeyForPiece(pieceIndex);
		if (profileKey != null)
			store.setProfileOffset(profileKey, x, y, z);
	}

	@Override
	public float[] getProfileOffset(int pieceIndex) {
		ModelLightProfile profile = profileForPiece(pieceIndex);
		if (profile == null)
			return new float[3];
		return new float[] { profile.getOffsetX(), profile.getOffsetY(), profile.getOffsetZ() };
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
	@Nullable
	public String getAnchorLightDescription(LightViewerFrame.AnchorSelection anchor) {
		String profileKey = profileKeyForPiece(anchor.pieceIndex);
		if (profileKey == null)
			return null;
		ModelLightProfile profile = store.get(profileKey);
		if (profile == null)
			return null;
		if (anchor.triangle)
			return profile.lightDescriptionForTriangle(anchor.localIndex);
		return profile.lightDescriptionForVertex(anchor.localIndex);
	}

	@Override
	public void setAnchorLightDescription(LightViewerFrame.AnchorSelection anchor, String description) {
		String profileKey = profileKeyForPiece(anchor.pieceIndex);
		if (profileKey == null)
			return;
		if (anchor.triangle)
			store.setTriangleLightDescription(profileKey, anchor.localIndex, description);
		else
			store.setVertexLightDescription(profileKey, anchor.localIndex, description);
		if (frame != null)
			frame.refreshMarkers();
	}

	@Override
	@Nullable
	public float[] getAnchorBarycentric(LightViewerFrame.AnchorSelection anchor) {
		if (!anchor.triangle)
			return null;
		String profileKey = profileKeyForPiece(anchor.pieceIndex);
		if (profileKey == null)
			return null;
		ModelLightProfile profile = store.get(profileKey);
		if (profile == null)
			return null;
		TriangleAnchor tri = profile.getTriangles().get(anchor.localIndex);
		if (tri == null)
			return null;
		return new float[] { tri.getBary0(), tri.getBary1(), tri.getBary2() };
	}

	@Override
	public void setAnchorBarycentric(LightViewerFrame.AnchorSelection anchor, float b0, float b1, float b2) {
		if (!anchor.triangle)
			return;
		String profileKey = profileKeyForPiece(anchor.pieceIndex);
		if (profileKey != null)
			store.setTriangleBarycentric(profileKey, anchor.localIndex, b0, b1, b2);
	}

	@Override
	public LightDefinition getDefinition(String description) {
		return lightManager.copyDefinitionForEditor(description);
	}

	@Override
	public void saveDefinition(String description, LightDefinition definition) {
		lightManager.updateDefinitionTemplate(description, definition);
		if (frame != null)
			frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
	}

	@Override
	public void createDefinition(String description) {
		LightDefinition def = new LightDefinition();
		def.description = description;
		def.color = new float[] { 1f, 0.8f, 0.4f };
		lightManager.addDefinitionTemplate(def);
		if (frame != null)
			frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
	}

	@Override
	public void deleteDefinition(String description) {
		lightManager.removeDefinitionTemplate(description);
		if (frame != null)
			frame.setLightDescriptions(modelLightManager.getAvailableLightDescriptions());
	}

	@Override
	public void saveModelLights() {
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

	@Override
	public void saveLightDefinitions() {
		try {
			lightManager.saveDefinitionsToDisk();
			String path = LightManager.getLightsResourcePath().toString();
			JOptionPane.showMessageDialog(
				frame,
				"Saved to:\n" + path + "\n\nCommit lights.json to ship the changes.",
				"Lights saved",
				JOptionPane.INFORMATION_MESSAGE
			);
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(
				frame,
				"Failed to save lights.json:\n" + ex.getMessage(),
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

	private static boolean isEquipmentProfile(ModelLightProfile profile) {
		return !profile.isNpcProfile() && !profile.isObjectProfile();
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
		if (captureMode == 1 && viewerObjectId >= 0)
			return store.ensureProfileForObject(piece.getMeshKey(), fallback, viewerObjectId);
		if (captureMode == 2 && viewerNpcId >= 0)
			return store.ensureProfileForNpc(piece.getMeshKey(), fallback, viewerNpcId);
		Set<Integer> itemIds = frame != null ? parseItemIds(frame.getItemIdsFieldText()) : Set.of();
		return store.ensureProfileFor(piece.getMeshKey(), fallback, itemIds);
	}

	@Nullable
	private String profileKeyForPiece(int pieceIndex) {
		if (viewerSnapshot == null || pieceIndex < 0 || pieceIndex >= viewerSnapshot.getPieces().size())
			return null;
		return resolveProfileKey(pieceIndex, viewerSnapshot.getPieces().get(pieceIndex));
	}

	@Nullable
	private ModelLightProfile profileForPiece(int pieceIndex) {
		@Nullable String profileKey = profileKeyForPiece(pieceIndex);
		return profileKey == null ? null : store.get(profileKey);
	}

	@Nullable
	private String resolveProfileKey(int pieceIndex, ModelSnapshot.Piece piece) {
		@Nullable String cached = pieceProfileKeys.get(pieceIndex);
		if (cached != null) {
			ModelLightProfile cachedProfile = store.get(cached);
			if (cachedProfile != null
				&& piece.getMeshKey().equals(cachedProfile.getMeshKey())
				&& matchesCaptureTarget(cachedProfile))
				return cached;
		}

		if (captureMode == 1 && viewerObjectId >= 0) {
			@Nullable String exact = store.findObjectProfileKey(piece.getMeshKey(), viewerObjectId);
			if (exact != null) {
				pieceProfileKeys.put(pieceIndex, exact);
				return exact;
			}
			for (Map.Entry<String, ModelLightProfile> entry : store.snapshotAll().entrySet()) {
				ModelLightProfile profile = entry.getValue();
				if (!piece.getMeshKey().equals(profile.getMeshKey())
					|| profile.isNpcProfile()
					|| !profile.getItemIds().isEmpty()
					|| !profile.getObjectIds().isEmpty()
					|| (profile.getVertices().isEmpty() && profile.getTriangles().isEmpty()))
					continue;
				store.setObjectIds(entry.getKey(), Set.of(viewerObjectId));
				pieceProfileKeys.put(pieceIndex, entry.getKey());
				return entry.getKey();
			}
		} else if (captureMode == 2 && viewerNpcId >= 0) {
			@Nullable String exact = store.findNpcProfileKey(piece.getMeshKey(), viewerNpcId);
			if (exact != null) {
				pieceProfileKeys.put(pieceIndex, exact);
				return exact;
			}
		} else {
			Set<Integer> itemIds = frame != null && frame.getAppliedPieceIndex() == pieceIndex
				? parseItemIds(frame.getItemIdsFieldText())
				: Set.of();

			@Nullable String exact = store.findProfileKey(piece.getMeshKey(), itemIds);
			if (exact != null) {
				ModelLightProfile profile = store.get(exact);
				if (profile != null && isEquipmentProfile(profile)) {
					pieceProfileKeys.put(pieceIndex, exact);
					return exact;
				}
			}

			for (Map.Entry<String, ModelLightProfile> entry : store.snapshotAll().entrySet()) {
				ModelLightProfile profile = entry.getValue();
				if (!isEquipmentProfile(profile) || !piece.getMeshKey().equals(profile.getMeshKey()))
					continue;
				pieceProfileKeys.put(pieceIndex, entry.getKey());
				return entry.getKey();
			}
		}
		return null;
	}

	private boolean matchesCaptureTarget(ModelLightProfile profile) {
		if (captureMode == 1 && viewerObjectId >= 0)
			return profile.isObjectProfile() && profile.getObjectIds().contains(viewerObjectId);
		if (captureMode == 2 && viewerNpcId >= 0)
			return profile.isNpcProfile() && profile.getNpcIds().contains(viewerNpcId);
		return isEquipmentProfile(profile);
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
			} catch (NumberFormatException ignored) {}
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
