package com.thirtytwo.steps;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Temporary shell-UID diagnostic for testing UID-targeted loopback capture.
 *
 * Run with app_process as UID 2000:
 *   CLASSPATH=/data/local/tmp/32steps-shell-audio-probe.apk app_process \
 *     /system/bin com.thirtytwo.steps.ShellAudioPolicyCaptureProbe <targetUid> [seconds] [gain]
 *
 * The policy is loopback-only. The selected app is diverted while this process
 * copies PCM from AudioRecord to AudioTrack. All resources and the policy are
 * released in finally, including when setup or playback fails.
 */
public final class ShellAudioPolicyCaptureProbe {
    private static final String TAG = "32steps-AudioCaptureProbe";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = 4;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private ShellAudioPolicyCaptureProbe() {}

    public static void main(String[] args) {
        Object policy = null;
        AudioManager audioManager = null;
        AudioRecord record = null;
        AudioTrack track = null;
        int registerResult = Integer.MIN_VALUE;
        long bytesRead = 0L;
        long bytesWritten = 0L;
        long sumSquares = 0L;
        long sampleCount = 0L;
        int inputPeak = 0;
        int outputPeak = 0;

        try {
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare();
            }
            relaxHiddenApi();
            if (Process.myUid() != 2000) {
                throw new SecurityException("Expected shell UID 2000, got " + Process.myUid());
            }
            if (args.length < 1) {
                throw new IllegalArgumentException("Usage: <targetUid> [seconds=15] [gain=1.0]");
            }

            final int targetUid = Integer.parseInt(args[0]);
            final int durationSeconds = args.length > 1
                    ? Math.max(3, Math.min(30, Integer.parseInt(args[1]))) : 15;
            final float gain = args.length > 2
                    ? Math.max(0.0f, Math.min(1.0f, Float.parseFloat(args[2]))) : 1.0f;

            Context context = systemContext();
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                throw new IllegalStateException("AudioManager unavailable");
            }

            String[] packages = context.getPackageManager().getPackagesForUid(targetUid);
            print("PROCESS_UID=" + Process.myUid());
            print("TARGET_UID=" + targetUid);
            print("TARGET_PACKAGES=" + Arrays.toString(packages));
            print("DURATION_SECONDS=" + durationSeconds);
            print("GAIN=" + gain);
            print("FORMAT=48000Hz/PCM_16BIT/STEREO");

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();

            int recordBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, ENCODING);
            int trackBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, ENCODING);
            if (recordBuffer <= 0 || trackBuffer <= 0) {
                throw new IllegalStateException("Invalid audio buffer sizes: record="
                        + recordBuffer + ", track=" + trackBuffer);
            }

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(trackBuffer * 2, 4096))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack not initialized");
            }
            track.play();

            Object ruleBuilder = construct("android.media.audiopolicy.AudioMixingRule$Builder");
            int matchUid = staticInt("android.media.audiopolicy.AudioMixingRule", "RULE_MATCH_UID");
            invoke(ruleBuilder, "android.media.audiopolicy.AudioMixingRule$Builder",
                    "addMixRule", new Class<?>[]{int.class, Object.class},
                    new Object[]{matchUid, Integer.valueOf(targetUid)});
            Object rule = invoke(ruleBuilder, "android.media.audiopolicy.AudioMixingRule$Builder",
                    "build", new Class<?>[0], new Object[0]);

            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Object mixBuilder = construct("android.media.audiopolicy.AudioMix$Builder",
                    new Class<?>[]{ruleClass}, new Object[]{rule});
            invoke(mixBuilder, "android.media.audiopolicy.AudioMix$Builder", "setFormat",
                    new Class<?>[]{AudioFormat.class}, new Object[]{format});
            int loopback = staticInt("android.media.audiopolicy.AudioMix", "ROUTE_FLAG_LOOP_BACK");
            invoke(mixBuilder, "android.media.audiopolicy.AudioMix$Builder", "setRouteFlags",
                    new Class<?>[]{int.class}, new Object[]{loopback});
            Object mix = invoke(mixBuilder, "android.media.audiopolicy.AudioMix$Builder",
                    "build", new Class<?>[0], new Object[0]);

            Object policyBuilder = construct("android.media.audiopolicy.AudioPolicy$Builder",
                    new Class<?>[]{Context.class}, new Object[]{context});
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            invoke(policyBuilder, "android.media.audiopolicy.AudioPolicy$Builder", "addMix",
                    new Class<?>[]{mixClass}, new Object[]{mix});
            policy = invoke(policyBuilder, "android.media.audiopolicy.AudioPolicy$Builder",
                    "build", new Class<?>[0], new Object[0]);

            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            registerResult = (Integer) invoke(audioManager, "android.media.AudioManager",
                    "registerAudioPolicy", new Class<?>[]{policyClass}, new Object[]{policy});
            print("REGISTER_AUDIO_POLICY_RESULT=" + registerResult);
            if (registerResult != AudioManager.SUCCESS) {
                throw new IllegalStateException("AudioPolicy registration failed: " + registerResult);
            }

            record = (AudioRecord) invoke(policy, "android.media.audiopolicy.AudioPolicy",
                    "createAudioRecordSink", new Class<?>[]{mixClass}, new Object[]{mix});
            if (record == null) {
                throw new IllegalStateException("createAudioRecordSink returned null");
            }
            print("AUDIO_RECORD_STATE=" + record.getState());
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Loopback AudioRecord not initialized");
            }

            record.startRecording();
            print("AUDIO_RECORD_STARTED=" + (record.getRecordingState()
                    == AudioRecord.RECORDSTATE_RECORDING));

            int chunkBytes = Math.max(recordBuffer, 4096);
            chunkBytes -= chunkBytes % BYTES_PER_FRAME;
            byte[] pcm = new byte[chunkBytes];
            long deadline = SystemClock.elapsedRealtime() + durationSeconds * 1000L;
            long lastMeter = SystemClock.elapsedRealtime();
            int readErrors = 0;
            int writeErrors = 0;

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (SystemClock.elapsedRealtime() < deadline) {
                int count = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    readErrors++;
                    print("AUDIO_RECORD_READ_ERROR=" + count);
                    if (readErrors >= 3) break;
                    continue;
                }
                if (count == 0) continue;
                count -= count % BYTES_PER_FRAME;
                if (count == 0) continue;

                for (int offset = 0; offset + 1 < count; offset += 2) {
                    int sample = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
                    int magnitude = Math.abs(sample);
                    if (magnitude > inputPeak) inputPeak = magnitude;
                    sumSquares += (long) sample * sample;
                    sampleCount++;
                    int processed = Math.round(sample * gain);
                    if (processed > 32767) processed = 32767;
                    if (processed < -32768) processed = -32768;
                    if (Math.abs(processed) > outputPeak) outputPeak = Math.abs(processed);
                    pcm[offset] = (byte) (processed & 0xff);
                    pcm[offset + 1] = (byte) ((processed >> 8) & 0xff);
                }

                int written = 0;
                while (written < count) {
                    int amount = track.write(pcm, written, count - written, AudioTrack.WRITE_BLOCKING);
                    if (amount < 0) {
                        writeErrors++;
                        print("AUDIO_TRACK_WRITE_ERROR=" + amount);
                        break;
                    }
                    if (amount == 0) break;
                    written += amount;
                }
                bytesRead += count;
                bytesWritten += Math.max(0, written);

                long now = SystemClock.elapsedRealtime();
                if (now - lastMeter >= 1000) {
                    print("METER bytesRead=" + bytesRead + " bytesWritten=" + bytesWritten
                            + " inputPeak=" + inputPeak + " outputPeak=" + outputPeak);
                    lastMeter = now;
                }
                if (writeErrors >= 3) break;
            }

            double rms = sampleCount == 0 ? 0.0
                    : Math.sqrt((double) sumSquares / (double) sampleCount);
            print("CAPTURE_TEST_COMPLETE=true");
            print("TOTAL_BYTES_READ=" + bytesRead);
            print("TOTAL_BYTES_WRITTEN=" + bytesWritten);
            print("INPUT_PEAK=" + inputPeak);
            print("OUTPUT_PEAK=" + outputPeak);
            print("INPUT_RMS=" + String.format(java.util.Locale.US, "%.2f", rms));
            print("READ_ERRORS=" + readErrors);
            print("WRITE_ERRORS=" + writeErrors);
        } catch (Throwable error) {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            print("CAPTURE_TEST_COMPLETE=false");
            print("ERROR=" + root.getClass().getName() + ": " + root.getMessage());
            Log.e(TAG, "UID loopback capture test failed", error);
        } finally {
            if (record != null) {
                try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
                catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
            }
            if (track != null) {
                try { track.pause(); } catch (Throwable ignored) {}
                try { track.flush(); } catch (Throwable ignored) {}
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
            if (policy != null && audioManager != null && registerResult == AudioManager.SUCCESS) {
                try {
                    Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
                    invoke(audioManager, "android.media.AudioManager", "unregisterAudioPolicy",
                            new Class<?>[]{policyClass}, new Object[]{policy});
                    print("POLICY_UNREGISTERED=true");
                } catch (Throwable error) {
                    print("POLICY_UNREGISTERED=false: " + error);
                }
            }
            System.out.flush();
            System.err.flush();
        }
        System.exit(0);
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static void relaxHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions",
                    String[].class);
            exemptions.setAccessible(true);
            exemptions.invoke(runtime, (Object) new String[]{"Landroid/", "Lcom/android/"});
        } catch (Throwable error) {
            print("HIDDEN_API_EXEMPTION_WARNING=" + error);
        }
    }

    private static Object construct(String className) throws Exception {
        return construct(className, new Class<?>[0], new Object[0]);
    }

    private static Object construct(String className, Class<?>[] types, Object[] values)
            throws Exception {
        Constructor<?> constructor = Class.forName(className).getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Object invoke(Object receiver, String className, String methodName,
                                 Class<?>[] types, Object[] values) throws Exception {
        Method method = Class.forName(className).getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(receiver, values);
    }

    private static int staticInt(String className, String fieldName) throws Exception {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void print(String message) {
        System.out.println("32STEPS_AUDIO_CAPTURE " + message);
        Log.i(TAG, message);
    }
}
