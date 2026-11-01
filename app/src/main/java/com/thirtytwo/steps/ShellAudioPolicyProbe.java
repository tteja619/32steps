package com.thirtytwo.steps;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * One-shot device probe. Run this class with app_process from an ADB shell
 * session so Binder calls are made by UID 2000 (com.android.shell), not by
 * the ordinary 32steps application UID.
 *
 * This test registers and immediately unregisters a loopback policy matching
 * only the shell UID. It does not capture or reroute another app's audio.
 */
public final class ShellAudioPolicyProbe {
    private static final String TAG = "32steps-AudioProbe";

    private ShellAudioPolicyProbe() {}

    public static void main(String[] args) {
        Object policy = null;
        AudioManager audioManager = null;
        int result = Integer.MIN_VALUE;

        try {
            relaxHiddenApiForProbeProcess();
            Context context = obtainSystemContext();
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                throw new IllegalStateException("AudioManager unavailable from system context");
            }

            int uid = Process.myUid();
            print("PROCESS_UID=" + uid);
            print("EXPECTED_SHELL_UID=2000");
            int routingPermission = context.checkCallingOrSelfPermission(
                    "android.permission.MODIFY_AUDIO_ROUTING");
            print("MODIFY_AUDIO_ROUTING="
                    + (routingPermission == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? "granted" : "denied"));

            // Read the full active-player list while executing as the shell UID.
            // Play a test track in another app before running this probe.
            try {
                java.util.List<?> active = audioManager.getActivePlaybackConfigurations();
                print("ACTIVE_PLAYBACK_CONFIG_COUNT=" + active.size());
                for (Object config : active) {
                    int clientUid = (Integer) readHidden(config, "getClientUid");
                    int sessionId = (Integer) readHidden(config, "getSessionId");
                    Object state = readHidden(config, "getPlayerState");
                    String[] packages = context.getPackageManager().getPackagesForUid(clientUid);
                    print("ACTIVE_PLAYER uid=" + clientUid
                            + " session=" + sessionId
                            + " state=" + state
                            + " packages=" + java.util.Arrays.toString(packages));
                }
            } catch (Throwable playbackError) {
                print("PLAYBACK_QUERY_ERROR=" + playbackError);
            }

            Object ruleBuilder = construct("android.media.audiopolicy.AudioMixingRule$Builder");
            int matchUid = staticInt(
                    "android.media.audiopolicy.AudioMixingRule",
                    "RULE_MATCH_UID"
            );
            invoke(ruleBuilder,
                    "android.media.audiopolicy.AudioMixingRule$Builder",
                    "addMixRule",
                    new Class<?>[]{int.class, Object.class},
                    new Object[]{matchUid, Integer.valueOf(uid)});
            Object rule = invoke(ruleBuilder,
                    "android.media.audiopolicy.AudioMixingRule$Builder",
                    "build",
                    new Class<?>[0],
                    new Object[0]);

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();

            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Object mixBuilder = construct(
                    "android.media.audiopolicy.AudioMix$Builder",
                    new Class<?>[]{ruleClass},
                    new Object[]{rule}
            );
            invoke(mixBuilder,
                    "android.media.audiopolicy.AudioMix$Builder",
                    "setFormat",
                    new Class<?>[]{AudioFormat.class},
                    new Object[]{format});
            int loopback = staticInt(
                    "android.media.audiopolicy.AudioMix",
                    "ROUTE_FLAG_LOOP_BACK"
            );
            invoke(mixBuilder,
                    "android.media.audiopolicy.AudioMix$Builder",
                    "setRouteFlags",
                    new Class<?>[]{int.class},
                    new Object[]{loopback});
            Object mix = invoke(mixBuilder,
                    "android.media.audiopolicy.AudioMix$Builder",
                    "build",
                    new Class<?>[0],
                    new Object[0]);

            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = construct(
                    "android.media.audiopolicy.AudioPolicy$Builder",
                    new Class<?>[]{Context.class},
                    new Object[]{context}
            );
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            invoke(policyBuilder,
                    "android.media.audiopolicy.AudioPolicy$Builder",
                    "addMix",
                    new Class<?>[]{mixClass},
                    new Object[]{mix});
            policy = invoke(policyBuilder,
                    "android.media.audiopolicy.AudioPolicy$Builder",
                    "build",
                    new Class<?>[0],
                    new Object[0]);

            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            result = (Integer) invoke(
                    audioManager,
                    "android.media.AudioManager",
                    "registerAudioPolicy",
                    new Class<?>[]{policyClass},
                    new Object[]{policy}
            );

            print("REGISTER_AUDIO_POLICY_RESULT=" + result);
            print(result == 0 ? "AUDIO_POLICY_REGISTERED=true" : "AUDIO_POLICY_REGISTERED=false");
        } catch (Throwable error) {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            print("AUDIO_POLICY_REGISTERED=false");
            print("ERROR=" + root.getClass().getName() + ": " + root.getMessage());
            Log.e(TAG, "Shell AudioPolicy probe failed", error);
        } finally {
            if (policy != null && audioManager != null) {
                try {
                    Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
                    invoke(audioManager,
                            "android.media.AudioManager",
                            "unregisterAudioPolicy",
                            new Class<?>[]{policyClass},
                            new Object[]{policy});
                    print("POLICY_UNREGISTERED=true");
                } catch (Throwable error) {
                    print("POLICY_UNREGISTERED=false: " + error);
                }
            }
        }

        System.out.flush();
        System.err.flush();
        System.exit(result == 0 ? 0 : 2);
    }

    private static Context obtainSystemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static void relaxHiddenApiForProbeProcess() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    String[].class
            );
            exemptions.setAccessible(true);
            exemptions.invoke(runtime, (Object) new String[]{"Landroid/", "Lcom/android/"});
        } catch (Throwable error) {
            print("HIDDEN_API_EXEMPTION_WARNING=" + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
    }

    private static Object readHidden(Object receiver, String methodName) throws Exception {
        Method method = receiver.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(receiver);
    }

    private static int staticInt(String className, String fieldName) throws Exception {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Object construct(String className) throws Exception {
        return construct(className, new Class<?>[0], new Object[0]);
    }

    private static Object construct(
            String className,
            Class<?>[] parameterTypes,
            Object[] arguments
    ) throws Exception {
        Constructor<?> constructor = Class.forName(className).getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static Object invoke(
            Object receiver,
            String className,
            String methodName,
            Class<?>[] parameterTypes,
            Object[] arguments
    ) throws Exception {
        Method method = Class.forName(className).getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(receiver, arguments);
    }

    private static void print(String message) {
        System.out.println("32STEPS_AUDIO_PROBE " + message);
        Log.i(TAG, message);
    }
}
