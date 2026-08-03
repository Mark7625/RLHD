package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LavaMode {
	CLASSIC("Classic"),
	MODERN("Modern");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
