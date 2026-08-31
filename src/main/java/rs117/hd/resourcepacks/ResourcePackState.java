package rs117.hd.resourcepacks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent user state for official resource packs. */
final class ResourcePackState {
	List<String> packOrder = new ArrayList<>();
	Map<String, String> sha256ByPack = new LinkedHashMap<>();
}
