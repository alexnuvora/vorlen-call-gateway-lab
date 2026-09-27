#!/usr/bin/env python3
from pathlib import Path
p=Path("third_party/JemRec/shellserver/src/com/jemcik/jemrec/shell/Main.java")
s=p.read_text()
s=s.replace("import android.media.AudioManager;","""import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.lang.reflect.Method;""")
s=s.replace("private static final int COMMAND_RECORD = 'R';","""private static final int COMMAND_RECORD = 'R';
    // Vorlen lab only: bounded digital telephony uplink proof.
    private static final int COMMAND_UPLINK_TEST = 'U';""")
anchor="""        if (command == COMMAND_RECORD) {"""
handler=r'''        if (command == COMMAND_UPLINK_TEST) {
            OutputStream os = client.getOutputStream();
            try {
                Context context = FakeContext.get();
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                int mode = am.getMode();
                if (mode != AudioManager.MODE_IN_CALL) {
                    os.write(("BLOCKED mode=" + mode + " (not MODE_IN_CALL)\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    return;
                }

                AudioDeviceInfo telephony = null;
                StringBuilder seen = new StringBuilder();
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    seen.append(d.getId()).append(":").append(d.getType()).append(":")
                            .append(d.getProductName()).append(";");
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) telephony = d;
                }
                if (telephony == null) {
                    os.write(("NO_TELEPHONY_TX visible outputs=" + seen + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    return;
                }

                final int rate = 48000;
                final int frames = rate;
                short[] pcm = new short[frames];
                for (int i = 0; i < frames; i++) {
                    pcm[i] = (short) (Math.sin(2.0 * Math.PI * 700.0 * i / rate) * 1800.0);
                }
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                // The normal builder selects the primary voice-communication mixer and Samsung
                // rejects it for Telephony Tx. Build the track with the policy's explicit
                // AUDIO_OUTPUT_FLAG_INCALL_MUSIC (0x10000) from the shell process.
                AudioTrack.Builder builder = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC);
                Method flagMethod = null;
                for (Method m : AudioTrack.Builder.class.getDeclaredMethods()) {
                    if (m.getName().equals("setAudioTrackFlags") && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == int.class) {
                        flagMethod = m;
                        break;
                    }
                }
                if (flagMethod == null) {
                    throw new IllegalStateException("shell AudioTrack.Builder has no setAudioTrackFlags(int)");
                }
                flagMethod.setAccessible(true);
                flagMethod.invoke(builder, 0x10000);
                AudioTrack track = builder.build();
                try {
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        throw new IllegalStateException("INCALL_MUSIC AudioTrack not initialized; session="
                                + track.getAudioSessionId() + " nativeRate=" + AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_VOICE_CALL));
                    }
                    boolean routed = track.setPreferredDevice(telephony);
                    if (!routed) {
                        os.write(("ROUTE_REJECTED telephonyId=" + telephony.getId() + "\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        return;
                    }
                    track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                    track.play();
                    Thread.sleep(1100);
                    track.stop();
                    AudioDeviceInfo actual = track.getRoutedDevice();
                    os.write(("COMPLETE preferred=TELEPHONY_TX(" + telephony.getId() + ") actual="
                            + (actual == null ? "null" : actual.getType() + "/" + actual.getId())
                            + " outputs=" + seen + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } finally {
                    track.release();
                }
            } catch (Throwable t) {
                os.write(("FAILED " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return;
        }
'''
if anchor not in s: raise SystemExit("handler anchor not found")
s=s.replace(anchor,handler+anchor,1)
p.write_text(s)
print("Patched JemRec shell daemon with Vorlen uplink command")
