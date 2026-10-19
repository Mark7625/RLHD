package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ConstellationMode {
	OFF("Off"),
	REALISTIC("Realistic"),
	FUN("Fun"),
	;

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
