#!/usr/bin/env python3
from pathlib import Path
p=Path("third_party/JemRec/shellserver/src/com/jemcik/jemrec/shell/Main.java")
s=p.read_text()
s=s.replace("import android.media.AudioManager;","""import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;\nimport android.media.AudioRecord;\nimport android.media.audiopolicy.AudioMix;\nimport android.media.audiopolicy.AudioMixingRule;\nimport android.media.audiopolicy.AudioPolicy;""")
s=s.replace("private static final int COMMAND_RECORD = 'R';","""private static final int COMMAND_RECORD = 'R';
    // Vorlen lab only: bounded digital telephony uplink proof.
    private static final int COMMAND_UPLINK_TEST = 'U';
    private static final int COMMAND_UPLINK_PCM = 'T';
    private static final int COMMAND_DUPLEX = 'D';\n    private static final int COMMAND_CHATGPT_BRIDGE = 'G';""")
anchor="""        if (command == COMMAND_RECORD) {"""
handler=r'''        if (command == COMMAND_CHATGPT_BRIDGE) {
            OutputStream os = client.getOutputStream();
            AudioRecord record = null;
            AudioTrack track = null;
            try {
                Context context = FakeContext.get();
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am.getMode() != AudioManager.MODE_IN_CALL) {
                    os.write(("BLOCKED mode=" + am.getMode() + " (cellular call must be active)\n").getBytes(StandardCharsets.UTF_8));
                    os.flush(); return;
                }
                AudioDeviceInfo telephony = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) telephony = d;
                }
                if (telephony == null) throw new IllegalStateException("Telephony Tx unavailable");

                // REMOTE_SUBMIX alone is silent unless an AudioPolicy mix feeds it. Build a
                // loopback mix for VOICE_COMMUNICATION playback (ChatGPT Voice), and capture
                // the policy's record sink directly.
                final int rate = 48000;
                final int channelIn = AudioFormat.CHANNEL_IN_STEREO;
                AudioFormat mixFormat = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
                AudioAttributes voiceAttrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build();
                AudioMixingRule rule = new AudioMixingRule.Builder()
                        .addRule(voiceAttrs, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE).build();
                AudioMix mix = new AudioMix.Builder(rule).setFormat(mixFormat)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK | AudioMix.ROUTE_FLAG_RENDER).build();
                AudioPolicy policy = new AudioPolicy.Builder(context).addMix(mix).build();
                int policyStatus = am.registerAudioPolicy(policy);
                if (policyStatus != AudioManager.SUCCESS)
                    throw new IllegalStateException("AudioPolicy registration failed status=" + policyStatus);
                record = policy.createAudioRecordSink(mix);
                if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                    try { am.unregisterAudioPolicy(policy); } catch (Throwable ignored) {}
                    throw new IllegalStateException("AudioPolicy loopback sink not initialized");
                }

                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                AudioFormat outFormat = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                int outMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(outFormat)
                        .setBufferSizeInBytes(Math.max(outMin, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if (!track.setPreferredDevice(telephony)) throw new IllegalStateException("Telephony Tx rejected");

                os.write("READY — switch to ChatGPT Voice and make it speak for 10 seconds\n".getBytes(StandardCharsets.UTF_8)); os.flush();
                record.startRecording();
                track.play();
                byte[] stereo = new byte[19200];
                byte[] mono = new byte[9600];
                long end = System.currentTimeMillis() + 10000L;
                long samples = 0, sumSq = 0;
                int peak = 0, forwarded = 0;
                while (System.currentTimeMillis() < end) {
                    int n = record.read(stereo, 0, stereo.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) continue;
                    int frames = n / 4;
                    for (int i=0; i<frames; i++) {
                        int p=i*4;
                        short l=(short)((stereo[p]&255)|(stereo[p+1]<<8));
                        short r=(short)((stereo[p+2]&255)|(stereo[p+3]<<8));
                        int v=(l+r)/2;
                        mono[i*2]=(byte)(v&255); mono[i*2+1]=(byte)((v>>8)&255);
                        int a=Math.abs(v); if(a>peak) peak=a;
                        sumSq += (long)v*v; samples++;
                    }
                    int bytes=frames*2;
                    int off=0;
                    while(off<bytes) {
                        int w=track.write(mono,off,bytes-off,AudioTrack.WRITE_BLOCKING);
                        if(w<=0) throw new IllegalStateException("Telephony write "+w);
                        off+=w;
                    }
                    forwarded += bytes;
                }
                record.stop(); track.stop();
                double rms = samples == 0 ? 0.0 : Math.sqrt((double)sumSq / samples);
                AudioDeviceInfo recRoute=record.getRoutedDevice(), outRoute=track.getRoutedDevice();
                os.write(("COMPLETE policyLoopback=VOICE_COMMUNICATION capturedRms=" + String.format(java.util.Locale.US,"%.1f",rms) +
                        " peak=" + peak + " forwarded=" + forwarded +
                        " remoteIn=" + (recRoute==null?"null":recRoute.getType()+"/"+recRoute.getId()) +
                        " telephonyTx=" + (outRoute==null?"null":outRoute.getType()+"/"+outRoute.getId()) + "\n")
                        .getBytes(StandardCharsets.UTF_8)); os.flush();
            } catch (Throwable t) {
                os.write(("FAILED " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()) + "\n")
                        .getBytes(StandardCharsets.UTF_8)); os.flush();
            } finally {
                if (record != null) { try { record.release(); } catch (Throwable ignored) {} }
                if (track != null) { try { track.release(); } catch (Throwable ignored) {} }
            }
            return;
        }

        if (command == COMMAND_DUPLEX) {
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
