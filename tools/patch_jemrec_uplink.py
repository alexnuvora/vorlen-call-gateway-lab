#!/usr/bin/env python3
from pathlib import Path
p=Path("third_party/JemRec/shellserver/src/com/jemcik/jemrec/shell/Main.java")
s=p.read_text()
s=s.replace("import android.media.AudioManager;","""import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;""")
s=s.replace("private static final int COMMAND_RECORD = 'R';","""private static final int COMMAND_RECORD = 'R';
    // Vorlen lab only: bounded digital telephony uplink proof.
    private static final int COMMAND_UPLINK_TEST = 'U';
    private static final int COMMAND_UPLINK_PCM = 'T';
    private static final int COMMAND_DUPLEX = 'D';""")
anchor="""        if (command == COMMAND_RECORD) {"""
handler=r'''        if (command == COMMAND_DUPLEX) {
            OutputStream os = client.getOutputStream();
            try {
                Context context = FakeContext.get();
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am.getMode() != AudioManager.MODE_IN_CALL) {
                    os.write("BLOCKED not in call\n".getBytes(StandardCharsets.UTF_8)); os.flush(); return;
                }
                AudioDeviceInfo telephony = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) { telephony = d; break; }
                }
                if (telephony == null) { os.write("NO_TELEPHONY_TX\n".getBytes(StandardCharsets.UTF_8)); os.flush(); return; }
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack track = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(min, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build();
                try {
                    if (!track.setPreferredDevice(telephony)) throw new IllegalStateException("Telephony Tx rejected");
                    track.play();
                    os.write("READY 48000 PCM16 MONO\n".getBytes(StandardCharsets.UTF_8)); os.flush();
                    DataInputStream din = new DataInputStream(client.getInputStream());
                    while (true) {
                        int length;
                        try { length = din.readInt(); } catch (EOFException eof) { break; }
                        if (length == 0) break;
                        if (length < 0 || length > 192000) throw new IllegalArgumentException("chunk " + length);
                        byte[] pcm = new byte[length]; din.readFully(pcm);
                        int off = 0;
                        while (off < pcm.length) {
                            int n = track.write(pcm, off, pcm.length - off, AudioTrack.WRITE_BLOCKING);
                            if (n <= 0) throw new IllegalStateException("AudioTrack write " + n);
                            off += n;
                        }
                    }
                    track.stop();
                    AudioDeviceInfo actual = track.getRoutedDevice();
                    os.write(("COMPLETE actual=" + (actual == null ? "null" : actual.getType()+"/"+actual.getId()) + "\n")
                            .getBytes(StandardCharsets.UTF_8)); os.flush();
                } finally { track.release(); }
            } catch (Throwable t) {
                os.write(("FAILED " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()) + "\n")
                        .getBytes(StandardCharsets.UTF_8)); os.flush();
            }
            return;
        }

        if (command == COMMAND_UPLINK_PCM) {
            OutputStream os = client.getOutputStream();
            try {
                Context context = FakeContext.get();
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am.getMode() != AudioManager.MODE_IN_CALL) {
                    os.write("BLOCKED not in call\n".getBytes(StandardCharsets.UTF_8)); os.flush(); return;
                }
                AudioDeviceInfo telephony = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) { telephony = d; break; }
                }
                if (telephony == null) {
                    os.write("NO_TELEPHONY_TX\n".getBytes(StandardCharsets.UTF_8)); os.flush(); return;
                }
                DataInputStream din = new DataInputStream(client.getInputStream());
                int length = din.readInt();
                if (length <= 0 || length > 48000 * 2 * 15) {
                    throw new IllegalArgumentException("PCM length out of bounds: " + length);
                }
                byte[] pcm = new byte[length];
                din.readFully(pcm);
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                AudioTrack track = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
                        .setBufferSizeInBytes(length).setTransferMode(AudioTrack.MODE_STATIC).build();
                try {
                    if (!track.setPreferredDevice(telephony)) throw new IllegalStateException("Telephony Tx rejected");
                    int written = track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                    if (written != pcm.length) throw new IllegalStateException("short write " + written + "/" + pcm.length);
                    track.play();
                    long ms = Math.min(15000L, Math.max(250L, (pcm.length / 2L) * 1000L / 48000L + 150L));
                    Thread.sleep(ms);
                    track.stop();
                    AudioDeviceInfo actual = track.getRoutedDevice();
                    os.write(("COMPLETE bytes=" + written + " actual=" +
                            (actual == null ? "null" : actual.getType() + "/" + actual.getId()) + "\n")
                            .getBytes(StandardCharsets.UTF_8)); os.flush();
                } finally { track.release(); }
            } catch (Throwable t) {
                os.write(("FAILED " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()) + "\n")
                        .getBytes(StandardCharsets.UTF_8)); os.flush();
            }
            return;
        }

        if (command == COMMAND_UPLINK_TEST) {
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

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                try {
                    int beforeState = track.getState();
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
