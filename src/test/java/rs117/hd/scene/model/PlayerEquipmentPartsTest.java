package rs117.hd.scene.model;

import net.runelite.api.Model;
import net.runelite.api.ModelData;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlayerEquipmentPartsTest {
	@Test
	public void verifiesPartTopologyAfterVertexIndicesAreRemapped() {
		ModelData source = mock(ModelData.class);
		when(source.getVerticesCount()).thenReturn(4);
		when(source.getFaceCount()).thenReturn(2);
		when(source.getFaceIndices1()).thenReturn(new int[] { 0, 0 });
		when(source.getFaceIndices2()).thenReturn(new int[] { 1, 2 });
		when(source.getFaceIndices3()).thenReturn(new int[] { 2, 3 });
		var mesh = new PlayerEquipmentParts.SourceMesh(source);

		Model assembled = mock(Model.class);
		when(assembled.getFaceIndices1()).thenReturn(new int[] { 0, 8, 8 });
		when(assembled.getFaceIndices2()).thenReturn(new int[] { 1, 7, 9 });
		when(assembled.getFaceIndices3()).thenReturn(new int[] { 2, 9, 4 });
		assertTrue(mesh.matches(assembled, 1));

		when(assembled.getFaceIndices2()).thenReturn(new int[] { 1, 7, 6 });
		assertFalse(mesh.matches(assembled, 1));
	}
}
