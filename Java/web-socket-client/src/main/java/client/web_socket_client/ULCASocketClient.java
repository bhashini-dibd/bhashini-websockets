package client.web_socket_client;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

import org.json.JSONArray;
import org.json.JSONObject;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class ULCASocketClient {
	private Socket socket;
	private final String serverUrl;
	private final String apiKey;
	private CountDownLatch connectionLatch;
	private CountDownLatch readyLatch;
	private volatile boolean stopRecording = false;

	private ExecutorService executor = Executors.newSingleThreadExecutor();

	private static final int SAMPLING_RATE = 8000;
	private static final int BUFFER_SIZE = 3200;
	private static final String SOURCE_LANGUAGE = "en";
	private static final String TARGET_LANGUAGE = "en";

	public ULCASocketClient(String serverUrl, String apiKey) {
		this.serverUrl = serverUrl;
		this.apiKey = apiKey;
		this.connectionLatch = new CountDownLatch(1);
		this.readyLatch = new CountDownLatch(1);
	}

	public void connect() throws URISyntaxException {
		// Configure connection options
		IO.Options options = new IO.Options();

		// Set auth data
		Map<String, String> auth = new HashMap<>();
		auth.put("Authorization", apiKey);
		options.auth = auth;
		options.transports = new String[] { "websocket" }; // Restrict to WebSocket transport

		// Set timeouts
		/*
		 * options.timeout = 40000; // Wait 40 seconds for the initial connection
		 * options.reconnectionAttempts = 10; // Retry up to 10 times
		 * options.reconnectionDelay = 10000; // Wait 10 seconds between retries
		 */
		// Initialize socket
		socket = IO.socket(serverUrl, options);

		// Set up event listeners
		socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Connected to server" + Arrays.deepToString(args));
				System.out.println(socket.id());
				connectionLatch.countDown();
			}
		});

		socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Disconnected from server" + Arrays.deepToString(args));
			}
		});

		socket.on("message", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Disconnected from server Message " + Arrays.deepToString(args));
			}
		});

		socket.on("response", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Response " + Arrays.deepToString(args));
			}
		});

		socket.on("abort", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Received abort: " + args[0]);
			}
		});

		socket.on("ready", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Server is ready");
				readyLatch.countDown();
				startContinuousAudioStreamingWithVAD();
			}
		});

		socket.on("terminate", new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				System.out.println("Received terminate signal");
			}
		});

		// Connect to the server
		socket.connect();
	}

	public void startStream(JSONArray taskSequence, JSONObject streamingConfig) throws InterruptedException {
		// Wait for connection before sending start event
		connectionLatch.await();

		// Emit start event with task sequence and streaming config
		socket.emit("start", taskSequence, streamingConfig);

		// Wait for ready signal
		readyLatch.await();
	}

	public void stop(boolean disconnectStream) {
		if (socket != null && socket.connected()) {
			socket.emit("stop", null, null, true, true);
		}
	}

	public void disconnect() {
		if (socket != null) {
			socket.disconnect();
		}
	}

	public void startContinuousAudioStreamingWithVAD() {
		// if (!isConnected) return;
		final int ENERGY_THRESHOLD = 2000; // Adjust based on microphone sensitivity and environment
		final int FRAME_LENGTH = 1024;

		executor.submit(() -> {
			AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 8000, 16, 1, 2, 16000, false);
			DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

			if (!AudioSystem.isLineSupported(info)) {
				System.out.println("Audio format not supported!");
				return;
			}

			try (TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info)) {
				microphone.open(format);
				microphone.start();
				System.out.println("Continuous recording with VAD started. Speak near the microphone...");

				ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				int bytesRead;

				Thread keyListenerThread = new Thread(() -> {
					try {
						System.in.read(); // Wait for Enter key press
						stopRecording = true; // Set flag to stop recording
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
				keyListenerThread.start();

				while (!stopRecording) {
					bytesRead = microphone.read(buffer, 0, buffer.length);
					byteStream.write(buffer, 0, bytesRead);

					byte[] pcmData = byteStream.toByteArray();

					JSONObject jsonObject = new JSONObject();
					JSONArray jsonArray = new JSONArray();
					JSONObject audioContent = new JSONObject();
					audioContent.put("audioContent", pcmData);
					jsonArray.put(audioContent);
					jsonObject.put("audio", jsonArray);

					// System.out.println(socket.id()+ jsonObject.toString());
					System.out.println("sending audio...");
					socket.emit("data", jsonObject, new JSONObject(), false, false);

					byteStream.reset();
				}

				System.out.println("Continuous recording stopped.");

				if (socket != null && socket.connected()) {
					socket.emit("data", null, null, true, false);
					socket.emit("data", null, null, true, true);
				}
				microphone.stop();
				microphone.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	public static short[] convertByteToInt16(byte[] pcmData) {
		if (pcmData.length % 2 != 0) {
			throw new IllegalArgumentException("PCM byte array length must be even to convert to int16.");
		}

		short[] int16Array = new short[pcmData.length / 2];
		for (int i = 0; i < int16Array.length; i++) {
			// Combine two bytes into one short (16-bit integer)
			int16Array[i] = (short) ((pcmData[2 * i + 1] << 8) | (pcmData[2 * i] & 0xFF));
		}
		return int16Array;
	}

	public static byte[] toUnsignedBytes(short[] pcmData) {
		byte[] unsignedBytes = new byte[pcmData.length * 2];

		for (int i = 0; i < pcmData.length; i++) {
			unsignedBytes[i * 2] = (byte) (pcmData[i] & 0xFF); // LSB
			unsignedBytes[i * 2 + 1] = (byte) ((pcmData[i] >> 8) & 0xFF); // MSB
		}

		return unsignedBytes;
	}

	// Example usage
	public static void main(String[] args) {
		try {
			// Initialize client

			ULCASocketClient client = new ULCASocketClient("INSERT-WEB-SOCKET-SERVER-URL-HERE", "INSERT-API-KEY-HERE");

			client.connect();

			// Create sample task sequence for ASR
			JSONArray taskSequenceArray = new JSONArray();

			JSONObject asrTask = new JSONObject();
			asrTask.put("taskType", "asr");
			JSONObject asrConfig = new JSONObject();

			asrConfig.put("serviceId", "INSERT-SEVICE-ID-HERE");

			JSONObject asrLanguage = new JSONObject();
			asrLanguage.put("sourceLanguage", SOURCE_LANGUAGE);
			asrConfig.put("language", asrLanguage);
			asrConfig.put("samplingRate", SAMPLING_RATE);
			asrConfig.put("audioFormat", "wav");
			asrConfig.put("encoding", JSONObject.NULL);
			asrTask.put("config", asrConfig);

			taskSequenceArray.put(asrTask);

			// Create streaming config
			JSONObject streamingConfig = new JSONObject();
			streamingConfig.put("responseFrequencyInSecs", 2.0);
			streamingConfig.put("responseTaskSequenceDepth", 1);

			// Start the stream
			client.startStream(taskSequenceArray, streamingConfig);

			// Simulate some work
			Thread.sleep(5000);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
