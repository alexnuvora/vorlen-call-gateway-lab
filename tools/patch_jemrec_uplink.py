#!/usr/bin/env python3
from pathlib import Path
p=Path("third_party/JemRec/shellserver/src/com/jemcik/jemrec/shell/Main.java")
s=p.read_text()
s=s.replace("import android.media.AudioManager;","""import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;\nimport android.media.AudioRecord;
import android.media.MediaRecorder;""")
s=s.replace("private static final int COMMAND_RECORD = 'R';","""private static final int COMMAND_RECORD = 'R';
    // Vorlen lab only: bounded digital telephony uplink proof.
    private static final int COMMAND_UPLINK_TEST = 'U';
    private static final int COMMAND_UPLINK_PCM = 'T';
    private static final int COMMAND_DUPLEX = 'D';\n    private static final int COMMAND_CHATGPT_BRIDGE = 'G';
    private static final int COMMAND_REVERSE_BRIDGE = 'H';\n    private static final int COMMAND_LAPTOP_BRIDGE = 'L';""")
anchor="""        if (command == COMMAND_RECORD) {"""
handler=r'''        if (command == COMMAND_LAPTOP_BRIDGE) {
            // Main.session() applies a short command-header timeout to every
            // connection. This command is a long-lived full-duplex stream, so
            // remove that timeout after authentication or a quiet/stalled audio
            // interval can tear down an otherwise healthy phone call bridge.
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);
            OutputStream os = client.getOutputStream();
            AudioRecord rx = null;
            AudioTrack tx = null;
            try {
                Context context = FakeContext.get();
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am.getMode() != AudioManager.MODE_IN_CALL) {
                    os.write(("BLOCKED mode=" + am.getMode() + " (cellular call must be active)\n").getBytes(StandardCharsets.UTF_8));
                    os.flush(); return;
                }
                AudioDeviceInfo telephonyRx = null, telephonyTx = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS))
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) telephonyRx = d;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) telephonyTx = d;
                if (telephonyRx == null || telephonyTx == null) throw new IllegalStateException("Telephony RX/TX unavailable");

                final int rate=48000;
                int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                rx=new AudioRecord(MediaRecorder.AudioSource.VOICE_CALL,rate,AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,Math.max(min,3840));
                if(rx.getState()!=AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("VOICE_CALL RX not initialized");
                rx.setPreferredDevice(telephonyRx);

                AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                AudioFormat fmt=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                int outMin=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                tx=new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                        .setBufferSizeInBytes(Math.max(outMin,3840)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if(!tx.setPreferredDevice(telephonyTx)) throw new IllegalStateException("Telephony TX rejected");

                StringBuilder txRates=new StringBuilder();
                for(int r:telephonyTx.getSampleRates()){if(txRates.length()>0)txRates.append(",");txRates.append(r);}
                os.write(("READY 48000 PCM16 MONO FULL_DUPLEX txDeviceRates=["+txRates+"] trackRate="+tx.getSampleRate()+
                        " playbackRate="+tx.getPlaybackRate()+" bufferFrames="+tx.getBufferSizeInFrames()+"\n")
                        .getBytes(StandardCharsets.UTF_8)); os.flush();
                final AudioRecord frx=rx; final AudioTrack ftx=tx;
                final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean(true);
                final java.util.concurrent.atomic.AtomicReference<Throwable> pumpError=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                rx.startRecording(); tx.play();

                Thread down=new Thread(new Runnable(){ public void run(){
                    byte[] b=new byte[960];
                    try {
                        while(running.get()){
                            int n=frx.read(b,0,b.length,AudioRecord.READ_BLOCKING);
                            if(n<=0) continue;
                            synchronized(os){ os.write(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n}); os.write(b,0,n); os.flush(); }
                        }
                    } catch(Throwable t){ pumpError.set(t); running.set(false); }
                }}, "vorlen-call-rx");
                down.start();

                DataInputStream din=new DataInputStream(client.getInputStream());
                while(running.get()){
                    int n;
                    try { n=din.readInt(); } catch(EOFException eof){ break; }
                    if(n==0) break;
                    if(n<0 || n>192000) throw new IllegalArgumentException("laptop frame "+n);
                    byte[] b=new byte[n]; din.readFully(b);
                    if ((n & 1) != 0) throw new IllegalArgumentException("unaligned PCM frame "+n);
                    int off=0;
                    while(off<n){
                        int w=tx.write(b,off,Math.min(960,n-off),AudioTrack.WRITE_BLOCKING);
                        if(w<=0) throw new IllegalStateException("Telephony TX write "+w);
                        off+=w;
                    }
                }
                running.set(false);
                try{rx.stop();}catch(Throwable ignored){}
                try{down.join(1500);}catch(Throwable ignored){}
                Throwable pe=pumpError.get(); if(pe!=null && !(pe instanceof java.net.SocketException)) throw pe;
                tx.stop();
            } catch(Throwable t) {
                try { os.write(("FAILED "+t.getClass().getName()+": "+String.valueOf(t.getMessage())+"\n").getBytes(StandardCharsets.UTF_8)); os.flush(); } catch(Throwable ignored){}
            } finally {
                if(rx!=null)try{rx.release();}catch(Throwable ignored){}
                if(tx!=null)try{tx.release();}catch(Throwable ignored){}
            }
            return;
        }

        if (command == COMMAND_REVERSE_BRIDGE) {
            OutputStream os = client.getOutputStream();
            AudioRecord rx = null;
            AudioTrack inject = null;
            Object policy = null;
            try {
                Context context = FakeContext.get();
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am.getMode() != AudioManager.MODE_IN_CALL) {
                    os.write(("BLOCKED mode=" + am.getMode() + " (cellular call must be active)\n").getBytes(StandardCharsets.UTF_8));
                    os.flush(); return;
                }
                final int rate = 48000;
                AudioDeviceInfo telephonyRx = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) telephonyRx = d;
                }

                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rx = new AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 9600));
                if (rx.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("VOICE_CALL RX not initialized");
                if (telephonyRx != null) rx.setPreferredDevice(telephonyRx);

                // Create an AudioPolicy injection mix targeting VOICE_COMMUNICATION capture.
                // createAudioTrackSource() is the reverse of the playback-capture sink used by
                // the outbound bridge: PCM written here becomes input to matching record clients.
                Class<?> rbc = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
                Object rb = rbc.getConstructor().newInstance();
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                // Match the actual ChatGPT recorder preset. Samsung may map VOICE_COMMUNICATION
                // clients onto the VOICE_RECOGNITION capture preset internally, so create the
                // injection mix with the same preset the active record client reports.
                int targetPreset = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
                try {
                    AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class)
                            .invoke(ab, targetPreset);
                } catch (Throwable e) {
                    throw new IllegalStateException("setCapturePreset unavailable: " + e);
                }
                AudioAttributes capture = ab.build();
                // RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 2
                try {
                    // Public Android builds commonly expose addRule(AudioAttributes, int);
                    // some branches name the equivalent helper addMixRule. Support both.
                    rbc.getMethod("addRule", AudioAttributes.class, int.class).invoke(rb, capture, 2);
                } catch (NoSuchMethodException noAddRule) {
                    rbc.getMethod("addMixRule", AudioAttributes.class, int.class).invoke(rb, capture, 2);
                }
                Object rule = rbc.getMethod("build").invoke(rb);
                Class<?> rc = Class.forName("android.media.audiopolicy.AudioMixingRule");
                Class<?> mbc = Class.forName("android.media.audiopolicy.AudioMix$Builder");
                Object mb = mbc.getConstructor(rc).newInstance(rule);
                AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                mbc.getMethod("setFormat", AudioFormat.class).invoke(mb, fmt);
                mbc.getMethod("setRouteFlags", int.class).invoke(mb, 2);
                Object mix = mbc.getMethod("build").invoke(mb);
                Class<?> mc = Class.forName("android.media.audiopolicy.AudioMix");
                Class<?> pbc = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
                Object pb = pbc.getConstructor(Context.class).newInstance(context);
                pbc.getMethod("addMix", mc).invoke(pb, mix);
                policy = pbc.getMethod("build").invoke(pb);
                Class<?> pc = Class.forName("android.media.audiopolicy.AudioPolicy");
                int status = ((Integer)AudioManager.class.getMethod("registerAudioPolicy", pc).invoke(am, policy)).intValue();
                if (status != 0) throw new IllegalStateException("register reverse AudioPolicy=" + status);
                inject = (AudioTrack)pc.getMethod("createAudioTrackSource", mc).invoke(policy, mix);
                if (inject == null || inject.getState() != AudioTrack.STATE_INITIALIZED)
                    throw new IllegalStateException("reverse injection track not initialized");

                os.write("READY — REVERSE BRIDGE active for 60 seconds; remote caller speak to ChatGPT\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                // Start the injection endpoint before taking the cellular receive capture.
                inject.play();
                Thread.sleep(250);
                rx.startRecording();
                byte[] pcm = new byte[1920];
                long end = System.currentTimeMillis() + 60000L;
                long samples=0,sumSq=0,nonZero=0; int peak=0,reads=0,errors=0,written=0;
                while (System.currentTimeMillis() < end) {
                    int n=rx.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                    if(n<=0){errors++;continue;} reads++;
                    for(int i=0;i+1<n;i+=2){
                        int v=(short)((pcm[i]&255)|(pcm[i+1]<<8)); int a=Math.abs(v);
                        if(a>peak)peak=a; if(v!=0)nonZero++; sumSq+=(long)v*v; samples++;
                    }
                    int off=0;
                    while(off<n){int w=inject.write(pcm,off,n-off,AudioTrack.WRITE_BLOCKING);if(w<=0)throw new IllegalStateException("reverse write "+w);off+=w;written+=w;}
                }
                rx.stop(); inject.stop();
                double rms=samples==0?0.0:Math.sqrt((double)sumSq/samples);
                double nz=samples==0?0.0:100.0*nonZero/samples;
                AudioDeviceInfo rr=rx.getRoutedDevice(),ir=inject.getRoutedDevice();
                os.write(("COMPLETE reverseCaptureRms="+String.format(java.util.Locale.US,"%.1f",rms)+
                        " reverseCapturePeak="+peak+" reverseNonZeroPct="+String.format(java.util.Locale.US,"%.2f",nz)+
                        " reverseReads="+reads+" reverseReadErrors="+errors+" reverseInjectedBytes="+written+
                        " telephonyRxRoute="+(rr==null?"null":rr.getType()+"/"+rr.getId())+
                        " injectionRoute="+(ir==null?"null":ir.getType()+"/"+ir.getId())+
                        " verdict="+(peak>8&&rms>1.0?"REVERSE_SIGNAL_PRESENT":"REVERSE_SILENT")+"\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch(Throwable t) {
                os.write(("FAILED "+t.getClass().getName()+": "+String.valueOf(t.getMessage())+"\n").getBytes(StandardCharsets.UTF_8)); os.flush();
            } finally {
                if(rx!=null)try{rx.release();}catch(Throwable ignored){}
                if(inject!=null)try{inject.release();}catch(Throwable ignored){}
                if(policy!=null)try{
                    Class<?> pc=Class.forName("android.media.audiopolicy.AudioPolicy");
                    AudioManager am=(AudioManager)FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
                    AudioManager.class.getMethod("unregisterAudioPolicy",pc).invoke(am,policy);
                }catch(Throwable ignored){}
            }
            return;
        }

        if (command == COMMAND_CHATGPT_BRIDGE) {
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
                AudioDeviceInfo remoteIn = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) telephony = d;
                }
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) remoteIn = d;
                }
                if (telephony == null) throw new IllegalStateException("Telephony Tx unavailable");
                if (remoteIn == null) throw new IllegalStateException("Remote Submix In unavailable");

                final int rate = 48000;
                final int channelIn = AudioFormat.CHANNEL_IN_STEREO;
                String capturePath = "unknown";
                try {
                    Class<?> rbc = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
                    Object rb = rbc.getConstructor().newInstance();
                    AudioAttributes captureAttrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                    rbc.getMethod("addRule", AudioAttributes.class, int.class).invoke(rb, captureAttrs, 1);
                    Object rule = rbc.getMethod("build").invoke(rb);

                    Class<?> rc = Class.forName("android.media.audiopolicy.AudioMixingRule");
                    Class<?> mbc = Class.forName("android.media.audiopolicy.AudioMix$Builder");
                    Object mb = mbc.getConstructor(rc).newInstance(rule);
                    AudioFormat mixFormat = new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
                    mbc.getMethod("setFormat", AudioFormat.class).invoke(mb, mixFormat);
                    mbc.getMethod("setRouteFlags", int.class).invoke(mb, 2);
                    Object mix = mbc.getMethod("build").invoke(mb);

                    Class<?> mc = Class.forName("android.media.audiopolicy.AudioMix");
                    Class<?> pbc = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
                    Object pb = pbc.getConstructor(Context.class).newInstance(context);
                    pbc.getMethod("addMix", mc).invoke(pb, mix);
                    Object policy = pbc.getMethod("build").invoke(pb);
                    Class<?> pc = Class.forName("android.media.audiopolicy.AudioPolicy");
                    int status = ((Integer) AudioManager.class.getMethod("registerAudioPolicy", pc)
                            .invoke(am, policy)).intValue();
                    if (status != 0) throw new IllegalStateException("registerAudioPolicy=" + status);
                    record = (AudioRecord) pc.getMethod("createAudioRecordSink", mc).invoke(policy, mix);
                    if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED)
                        throw new IllegalStateException("loopback sink not initialized");
                    capturePath = "policy-loopback";
                } catch (Throwable e) {
                    throw new IllegalStateException("AudioPolicy loopback failed: " + e.toString(), e);
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

                os.write("READY — switch to ChatGPT Voice — bridge active for 60 seconds\n".getBytes(StandardCharsets.UTF_8)); os.flush();
                record.startRecording();
                track.play();
                byte[] stereo = new byte[19200];
                byte[] mono = new byte[9600];
                long end = System.currentTimeMillis() + 60000L;
                long samples = 0, sumSq = 0;
                int peak = 0, forwarded = 0;
                long nonZeroSamples = 0;
                int readCalls = 0, readErrors = 0;
                int routeChecks = 0, routeLosses = 0, shortWrites = 0, txRearms = 0;
                String firstTxRoute = "null", lastTxRoute = "null";
                while (System.currentTimeMillis() < end) {
                    int n = record.read(stereo, 0, stereo.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) { readErrors++; continue; }
                    readCalls++;
                    int frames = n / 4;
                    for (int i=0; i<frames; i++) {
                        int p=i*4;
                        short l=(short)((stereo[p]&255)|(stereo[p+1]<<8));
                        short r=(short)((stereo[p+2]&255)|(stereo[p+3]<<8));
                        int v=(l+r)/2;
                        mono[i*2]=(byte)(v&255); mono[i*2+1]=(byte)((v>>8)&255);
                        int a=Math.abs(v); if(a>peak) peak=a;
                        if (v != 0) nonZeroSamples++;
                        sumSq += (long)v*v; samples++;
                    }
                    int bytes=frames*2;
                    // Feed Telephony Tx in ~20 ms PCM frames. Large ~100 ms writes can let
                    // Samsung's in-call route accept the buffer but only transmit its first burst.
                    final int txChunkBytes = 1920; // 48 kHz * 20 ms * mono * PCM16
                    int off=0;
                    while(off<bytes) {
                        AudioDeviceInfo liveRoute = track.getRoutedDevice();
                        routeChecks++;
                        if (liveRoute == null || liveRoute.getType() != AudioDeviceInfo.TYPE_TELEPHONY) {
                            routeLosses++;
                            if (!track.setPreferredDevice(telephony))
                                throw new IllegalStateException("Telephony Tx route lost");
                            liveRoute = track.getRoutedDevice();
                        }
                        String liveRouteName = liveRoute == null ? "null" : liveRoute.getType()+"/"+liveRoute.getId();
                        if ("null".equals(firstTxRoute)) firstTxRoute = liveRouteName;
                        lastTxRoute = liveRouteName;
                        int want = Math.min(txChunkBytes, bytes-off);
                        int w=track.write(mono,off,want,AudioTrack.WRITE_BLOCKING);
                        if(w<=0) throw new IllegalStateException("Telephony write "+w);
                        if(w<want) shortWrites++;
                        off+=w;
                        // Re-arm Telephony Tx every ~1 second to test whether Samsung only
                        // passes the initial burst after AudioTrack activation.
                        if ((forwarded + off) > 0 && ((forwarded + off) % 96000) < txChunkBytes) {
                            try {
                                txRearms++;
                                track.pause();
                                track.flush();
                                track.setPreferredDevice(telephony);
                                track.play();
                            } catch (Throwable ignored) {}
                        }
                    }
                    forwarded += bytes;
                }
                record.stop(); track.stop();
                double rms = samples == 0 ? 0.0 : Math.sqrt((double)sumSq / samples);
                AudioDeviceInfo recRoute=record.getRoutedDevice(), outRoute=track.getRoutedDevice();
                String remoteRoute = recRoute==null ? "null" : recRoute.getType()+"/"+recRoute.getId();
                String txRoute = outRoute==null ? "null" : outRoute.getType()+"/"+outRoute.getId();
                double nonZeroPct = samples == 0 ? 0.0 : (100.0 * nonZeroSamples / samples);
                os.write(("COMPLETE capturePath=" + capturePath +
                        " playbackCaptureRms=" + String.format(java.util.Locale.US,"%.1f",rms) +
                        " playbackCapturePeak=" + peak +
                        " playbackNonZeroPct=" + String.format(java.util.Locale.US,"%.2f",nonZeroPct) +
                        " playbackReadCalls=" + readCalls + " playbackReadErrors=" + readErrors +
                        " remoteSubmixRoute=" + remoteRoute +
                        " telephonyTxBytes=" + forwarded +
                        " telephonyTxRoute=" + txRoute +
                        " telephonyTxFirstRoute=" + firstTxRoute +
                        " telephonyTxLastRoute=" + lastTxRoute +
                        " telephonyTxRouteChecks=" + routeChecks +
                        " telephonyTxRouteLosses=" + routeLosses +
                        " telephonyTxShortWrites=" + shortWrites +
                        " telephonyTxRearms=" + txRearms +
                        " verdict=" + (peak > 8 && rms > 1.0 ? "CAPTURE_SIGNAL_PRESENT" : "CAPTURE_SILENT") + "\n")
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

