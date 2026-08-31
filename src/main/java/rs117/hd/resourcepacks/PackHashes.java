package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class PackHashes {
	private PackHashes() {
	}

	static MessageDigest sha256Digest() throws IOException {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IOException("SHA-256 is unavailable", ex);
		}
	}

	static String sha256(File file) throws IOException {
		MessageDigest digest = sha256Digest();
		byte[] buffer = new byte[8192];
		try (FileInputStream input = new FileInputStream(file)) {
			for (int read; (read = input.read(buffer)) != -1;)
				digest.update(buffer, 0, read);
		}
		return toHex(digest.digest());
	}

	static String toHex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
			result.append(String.format("%02x", value));
		return result.toString();
	}
}
