package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads a resource-pack archive and verifies its advertised size. */
final class ResourcePackDownloader {
	interface Listener {
		void onStarted();

		void onFailure(Call call, IOException exception);

		void onProgress(int progress);

		void onFinished();
	}

	private final OkHttpClient client;

	ResourcePackDownloader(OkHttpClient client) {
		this.client = client;
	}

	void download(String url, File destination, Long expectedFileSize, Listener listener) {
		listener.onStarted();
		client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException exception) {
				listener.onFailure(call, exception);
			}

			@Override
			public void onResponse(Call call, Response response) {
				try (Response ignored = response) {
					if (!response.isSuccessful())
						throw new IOException("Unexpected response code " + response.code());

					ResponseBody body = response.body();
					if (body == null)
						throw new IOException("Response body is null");

					long contentLength = expectedFileSize != null ? expectedFileSize : body.contentLength();
					long bytesRead = 0;
					byte[] buffer = new byte[4096];
					try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(destination)) {
						for (int read; (read = input.read(buffer)) != -1;) {
							output.write(buffer, 0, read);
							bytesRead += read;
							listener.onProgress(contentLength > 0 ? (int) (bytesRead * 100 / contentLength) : -1);
						}
					}
					if (expectedFileSize != null && bytesRead != expectedFileSize)
						throw new IOException("Downloaded " + bytesRead + " bytes, expected " + expectedFileSize);
					listener.onFinished();
				} catch (IOException exception) {
					listener.onFailure(call, exception);
				}
			}
		});
	}
}
