package com.vorlen.callgateway.lab

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import com.vorlen.callgateway.lab.audio.ShellCallAudio
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class GatewayService : Service() {
    private val pollIo=Executors.newSingleThreadExecutor()
    private val outboundIo=Executors.newSingleThreadExecutor()
    private val bridgeIo=Executors.newSingleThreadExecutor()
    private val daemonIo=Executors.newSingleThreadExecutor()
    private val daemonRecoveryLock=Any()
    @Volatile private var running=false
    @Volatile private var activeRequestId:String?=null
    @Volatile private var sawOffHook=false
    @Volatile private var lastCallState=TelephonyManager.CALL_STATE_IDLE
    @Volatile private var requestStartedAtMs=0L
    @Volatile private var bridgeRunning=false
    @Volatile private var bridgePending=false
    @Volatile private var bridgeEverActive=false
    @Volatile private var daemonReady=false
    @Volatile private var daemonHealthyStreak=0
    @Volatile private var daemonLastHealthyAtMs=0L
    @Volatile private var lastDaemonFailure:String?=null
    @Volatile private var lastBridgeFailure:String?=null
    private lateinit var telephony:TelephonyManager

    private fun bridgeEnabled():Boolean =
        getSharedPreferences("gateway",MODE_PRIVATE).getBoolean("laptop_bridge_enabled",false)

    @Suppress("DEPRECATION")
    private val phoneListener=object:PhoneStateListener(){
        override fun onCallStateChanged(state:Int,phoneNumber:String?){
            handleCallState(state)
        }
    }

    private fun handleCallState(state:Int){
        val previous=lastCallState
        lastCallState=state
        val name=when(state){TelephonyManager.CALL_STATE_RINGING->"ringing";TelephonyManager.CALL_STATE_OFFHOOK->"active";else->"idle"}
        if(state==TelephonyManager.CALL_STATE_OFFHOOK){
            sawOffHook=true
            if(bridgeEnabled()){
                bridgePending=true
                startDigitalBridgeIfConfigured()
            }
        }
        val id=activeRequestId?:return
        if(state!=previous)sendEvent("call_state",name,id)
        if(state==TelephonyManager.CALL_STATE_IDLE&&sawOffHook){
            sendEvent("call_ended","idle",id)
            completeRequest(id)
            activeRequestId=null
            sawOffHook=false
            requestStartedAtMs=0L
            bridgePending=false
            bridgeEverActive=false
            // The per-call telephony stream ends here, but the persistent bridge
            // preference remains enabled. The next call re-attaches automatically.
            ShellCallAudio.stopLaptopNetworkBridge()
        }
    }

    private fun startDigitalBridgeIfConfigured(){
        if(!bridgeEnabled()){
            bridgePending=false
            return
        }
        bridgePending=true
        if(bridgeRunning)return
        val prefs=getSharedPreferences("gateway",MODE_PRIVATE)
        val host=prefs.getString("laptop_host","192.168.1.4")?.trim().orEmpty()
        val port=prefs.getInt("laptop_port",28761)
        if(host.isBlank()||port !in 1..65535){
            sendEvent("digital_bridge_failed","invalid_target",activeRequestId)
            bridgePending=false
            return
        }
        bridgeRunning=true
        val bridgeRequestId=activeRequestId
        bridgeIo.execute{
            try{
                val deadline=SystemClock.elapsedRealtime()+45_000L
                while(running && activeRequestId==bridgeRequestId && bridgeRequestId!=null && (bridgePending || bridgeEverActive) && (bridgeEverActive || SystemClock.elapsedRealtime()<deadline)){
                    val result=runBlocking{
                        ShellCallAudio.runLaptopNetworkBridge(this@GatewayService,host,port){ _ ->
                            if(activeRequestId==bridgeRequestId){
                                bridgePending=false
                                bridgeEverActive=true
                                lastBridgeFailure=null
                                sendEvent("digital_bridge_active","active",bridgeRequestId)
                            }
                        }
                    }

                    // A normal call teardown closes the laptop socket too. Once this
                    // worker's request is no longer the active call, exit silently:
                    // that socket close is expected and must not be reported as a
                    // transport retry/reconnect failure for the next/ended call.
                    if(!running || activeRequestId!=bridgeRequestId || !sawOffHook){
                        break
                    }

                    // A bridge ending while the same cellular call is still active is
                    // transport loss, not call completion. Explicitly tear down any
                    // half-open sockets, allow the shell AudioRecord/AudioTrack handler
                    // to release, then reacquire with bounded exponential backoff.
                    bridgePending=true
                    lastBridgeFailure=result.report
                    ShellCallAudio.stopLaptopNetworkBridge()
                    if(bridgeEverActive){
                        sendEvent("digital_bridge_reconnect",("reconnect_"+result.report).take(240),bridgeRequestId)
                    } else {
                        sendEvent("digital_bridge_retry",("retry_"+result.report).take(240),bridgeRequestId)
                    }
                    // The Samsung telephony AudioRecord/AudioTrack endpoints can
                    // remain busy briefly after a socket dies. Give both the shell
                    // handler and Windows listener time to return to a clean accept
                    // state before opening the next duplex session.
                    Thread.sleep(if(bridgeEverActive) 2500L else 1200L)
                }
                if(bridgePending && !bridgeEverActive && activeRequestId==bridgeRequestId && bridgeRequestId!=null){
                    val detail=(lastBridgeFailure ?: "timeout_no_laptop_handshake").take(220)
                    sendEvent("digital_bridge_failed",("timeout_"+detail).take(240),bridgeRequestId)
                    bridgePending=false
                }
            }catch(_:InterruptedException){
                Thread.currentThread().interrupt()
            }catch(_:Throwable){
                if(activeRequestId!=null)sendEvent("digital_bridge_failed","exception",activeRequestId)
                bridgePending=false
            }finally{
                bridgeRunning=false
            }
        }
    }

    private fun recoverDaemonBlocking():Result<Unit> = synchronized(daemonRecoveryLock){
        runBlocking{ShellCallAudio.ensureResidentDaemon(this@GatewayService)}
    }

    private fun markDaemonHealthy(){
        daemonReady=true
        daemonHealthyStreak=(daemonHealthyStreak+1).coerceAtMost(3)
        daemonLastHealthyAtMs=SystemClock.elapsedRealtime()
        lastDaemonFailure=null
    }

    private fun daemonHealthyEnough():Boolean =
        daemonReady && daemonHealthyStreak>=2 &&
            SystemClock.elapsedRealtime()-daemonLastHealthyAtMs <= 6_000L

    private fun waitForDaemonHealthy(timeoutMs:Long=10_000L):Result<Unit>{
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        var lastError="daemon health check timed out"
        while(running && SystemClock.elapsedRealtime()<deadline){
            if(ShellCallAudio.ping()){
                markDaemonHealthy()
                Thread.sleep(250)
                if(ShellCallAudio.ping()){
                    markDaemonHealthy()
                    return Result.success(Unit)
                }
                lastError="daemon ping failed on stability confirmation"
            }else{
                daemonReady=false
                daemonHealthyStreak=0
                val recovered=recoverDaemonBlocking()
                if(recovered.isFailure){
                    lastError=recovered.exceptionOrNull()?.message ?: "daemon recovery failed"
                    lastDaemonFailure=lastError
                }else{
                    Thread.sleep(300)
                }
            }
        }
        daemonReady=false
        daemonHealthyStreak=0
        lastDaemonFailure=lastError
        return Result.failure(IllegalStateException(lastError))
    }

    private fun superviseDaemon(){
        var reportedHealthy=false
        var backoffMs=1_000L
        while(running&&!Thread.currentThread().isInterrupted){
            try{
                // Do not probe/restart the shell process while its long-lived audio
                // session owns the daemon. Supervision resumes immediately after the
                // call/bridge worker releases it.
                if(activeRequestId!=null || bridgeRunning){
                    Thread.sleep(750)
                    continue
                }

                if(ShellCallAudio.ping()){
                    markDaemonHealthy()
                    backoffMs=1_000L
                    if(!reportedHealthy && daemonHealthyStreak>=2){
                        sendEvent("audio_daemon_ready","resident_supervised",null)
                        reportedHealthy=true
                    }
                    Thread.sleep(1_500)
                    continue
                }

                daemonReady=false
                daemonHealthyStreak=0
                if(reportedHealthy){
                    sendEvent("audio_daemon_lost","ping_failed",null)
                    reportedHealthy=false
                }

                val recovered=recoverDaemonBlocking()
                if(recovered.isSuccess && ShellCallAudio.ping()){
                    markDaemonHealthy()
                    Thread.sleep(300)
                    if(ShellCallAudio.ping()){
                        markDaemonHealthy()
                        sendEvent("audio_daemon_recovered","resident_supervised",null)
                        reportedHealthy=true
                        backoffMs=1_000L
                        continue
                    }
                }

                lastDaemonFailure=recovered.exceptionOrNull()?.message ?: "ping failed after recovery"
                sendEvent("audio_daemon_recovery_failed",lastDaemonFailure!!.take(240),null)
                Thread.sleep(backoffMs)
                backoffMs=(backoffMs*2).coerceAtMost(10_000L)
            }catch(_:InterruptedException){
                Thread.currentThread().interrupt()
                break
            }catch(t:Throwable){
                daemonReady=false
                daemonHealthyStreak=0
                lastDaemonFailure=t.message ?: t.javaClass.simpleName
                try{Thread.sleep(backoffMs)}catch(_:InterruptedException){Thread.currentThread().interrupt();break}
                backoffMs=(backoffMs*2).coerceAtMost(10_000L)
            }
        }
    }

    private fun reconcileCallState(){
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return
        try{
            @Suppress("DEPRECATION") val state=telephony.callState
            if(state!=lastCallState)handleCallState(state)
        }catch(_:Exception){}
    }

    private fun enforcePlacementWatchdog(){
        val id=activeRequestId?:return
        if(sawOffHook||requestStartedAtMs==0L)return
        if(SystemClock.elapsedRealtime()-requestStartedAtMs<90000L)return
        try{endSimCall()}catch(_:Exception){}
        sendEvent("call_timeout","idle",id)
        failRequest(id,"Call did not reach active state within 90 seconds")
        activeRequestId=null
        sawOffHook=false
        requestStartedAtMs=0L
    }

    override fun onCreate(){
        super.onCreate(); createChannel()
        startForeground(NOTIFICATION_ID,notification())
        telephony=getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION") telephony.listen(phoneListener,PhoneStateListener.LISTEN_CALL_STATE)
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopGateway();return START_NOT_STICKY}
        if(intent?.action==ACTION_ENABLE_BRIDGE){
            getSharedPreferences("gateway",MODE_PRIVATE).edit().putBoolean("laptop_bridge_enabled",true).apply()
            bridgePending=lastCallState==TelephonyManager.CALL_STATE_OFFHOOK || sawOffHook
            if(bridgePending)startDigitalBridgeIfConfigured()
        }
        if(intent?.action==ACTION_DISABLE_BRIDGE){
            getSharedPreferences("gateway",MODE_PRIVATE).edit().putBoolean("laptop_bridge_enabled",false).apply()
            bridgePending=false
            bridgeEverActive=false
            ShellCallAudio.stopLaptopNetworkBridge()
            sendEvent("digital_bridge_disabled","manual",activeRequestId)
            if(!running){
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val token=getSharedPreferences("gateway",MODE_PRIVATE).getString("device_token",null)
        if(token.isNullOrBlank()){stopGateway();return START_NOT_STICKY}
        if(!running){
            running=true
            // Dedicated daemon supervisor. It continuously proves localhost health
            // and self-heals the privileged shell process between calls rather than
            // waiting for the next customer call to discover that Android killed it.
            daemonIo.execute{superviseDaemon()}
            pollIo.execute{poll(token)}
        }
        // This is a user-approved foreground calling session. If Android reclaims
        // the process, recreate the service and resume supervision/polling.
        return START_STICKY
    }

    private fun poll(token:String){
        while(running&&!Thread.currentThread().isInterrupted){
            try{
                val command=gatewayGet(token).optJSONObject("command")
                if(command!=null&&command.optString("action")=="dtmf"){
                    val id=command.optString("id")
                    val requestId=command.optString("request_id")
                    val payload=command.optJSONObject("payload") ?: JSONObject()
                    val tones=payload.optString("tones").replace(Regex("\\s+"),"")
                    // 250 ms is much more reliably recognised by corporate IVRs
                    // than the previous 180 ms while still feeling immediate.
                    val toneDuration=maxOf(250L,payload.optLong("tone_duration_ms",250L)).coerceIn(70L,1000L)
                    val gapMs=maxOf(150L,payload.optLong("gap_ms",150L)).coerceIn(50L,2000L)
                    val valid=tones.matches(Regex("^[0-9*#,]{1,64}$"))
                    if(!valid){
                        gatewayAck(token,id,"failed","command","Invalid DTMF sequence")
                    }else if(activeRequestId==null || requestId.isBlank() || requestId!=activeRequestId || lastCallState!=TelephonyManager.CALL_STATE_OFFHOOK){
                        gatewayAck(token,id,"failed","command","No matching active call for DTMF")
                    }else{
                        // Claim before generating tones so the command lifecycle proves
                        // that the handset actually received it. The final ACK then
                        // distinguishes successful keypad delivery from a local failure.
                        gatewayAck(token,id,"claimed","command",null)
                        sendEvent("dtmf_received","count_"+tones.count{it!=','},requestId)
                        val result=VorlenCallControl.sendDtmf(tones,toneDuration,gapMs)
                        result.fold(
                            onSuccess={count->
                                sendEvent("dtmf_sent","count_$count",requestId)
                                gatewayAck(token,id,"completed","command",null)
                            },
                            onFailure={e->
                                sendEvent("dtmf_failed",(e.message?:"DTMF failed").take(220),requestId)
                                gatewayAck(token,id,"failed","command",(e.message?:"DTMF failed").take(240))
                            }
                        )
                    }
                }else if(command!=null&&command.optString("action")=="hangup"){
                    val id=command.optString("id"); val requestId=command.optString("request_id")
                    if(requestId.isBlank()||activeRequestId==null||requestId==activeRequestId){
                        val result=endSimCall()
                        // A claimed/dialling request can outlive the actual Telecom call
                        // (for example when placeCall was accepted but never reached OFFHOOK).
                        // In that state there is nothing for TelecomManager.endCall() to end,
                        // but the gateway request still must be released.
                        val staleRequest = activeRequestId != null && !sawOffHook &&
                            lastCallState == TelephonyManager.CALL_STATE_IDLE
                        if(result.success || staleRequest){
                            val staleId=activeRequestId
                            bridgePending=false
                            bridgeEverActive=false
                            ShellCallAudio.stopLaptopNetworkBridge()
                            activeRequestId=null
                            sawOffHook=false
                            requestStartedAtMs=0L
                            if(staleId!=null){
                                sendEvent("call_cancelled","idle",staleId)
                                completeRequest(staleId)
                            }
                            gatewayAck(token,id,"completed","command",null)
                        }else{
                            gatewayAck(token,id,"failed","command",result.message)
                        }
                    }else gatewayAck(token,id,"failed","command","Command does not match active request")
                }else if(command!=null&&command.optString("action")=="call"){
                    val id=command.optString("id");val phone=command.optString("phone_number")
                    if(validNumber(phone)&&activeRequestId==null){
                        var preflightError:String?=null
                        if(bridgeEnabled()){
                            // Never place a client call while the previous bridge worker is
                            // still unwinding. This was the race that allowed the next
                            // handset call to connect while the digital path was unavailable.
                            val waitUntil=SystemClock.elapsedRealtime()+6000L
                            while(bridgeRunning && SystemClock.elapsedRealtime()<waitUntil){
                                Thread.sleep(100)
                            }
                            if(bridgeRunning){
                                ShellCallAudio.stopLaptopNetworkBridge()
                                preflightError="previous_bridge_still_closing"
                            }else{
                                val prefs=getSharedPreferences("gateway",MODE_PRIVATE)
                                val host=prefs.getString("laptop_host","192.168.1.4")?.trim().orEmpty()
                                val port=prefs.getInt("laptop_port",28761)
                                // Require two consecutive daemon health proofs immediately
                                // before dialling. If Android killed the shell daemon, recover it
                                // here and fail closed unless it remains stable.
                                val daemon=if(daemonHealthyEnough()) Result.success(Unit) else waitForDaemonHealthy(10_000L)
                                if(daemon.isFailure){
                                    preflightError="audio_daemon_unavailable: "+(daemon.exceptionOrNull()?.message?:lastDaemonFailure?:"health check failed")
                                }else{
                                    val laptop=runBlocking{ShellCallAudio.preflightLaptopBridge(host,port)}
                                    if(laptop.isFailure){
                                        preflightError="laptop_preflight: "+(laptop.exceptionOrNull()?.message?:"failed")
                                    }
                                }
                            }
                        }
                        if(preflightError!=null){
                            sendEvent("digital_bridge_preflight_failed",preflightError.take(240),id)
                            gatewayAck(token,id,"failed","request",preflightError.take(500))
                        }else{
                            activeRequestId=id;sawOffHook=lastCallState==TelephonyManager.CALL_STATE_OFFHOOK;requestStartedAtMs=SystemClock.elapsedRealtime();bridgePending=bridgeEnabled();bridgeEverActive=false;lastBridgeFailure=null
                            val result=placeSimCall(phone)
                            if(!result.success){activeRequestId=null;sawOffHook=false;requestStartedAtMs=0L}
                            gatewayAck(token,id,if(result.success)"claimed" else "failed","request",if(result.success)null else result.message)
                            if(result.success){
                                sendEvent("call_requested",if(sawOffHook)"active" else "dialing",id)
                                if(bridgeEnabled())startDigitalBridgeIfConfigured()
                            }
                        }
                    }
                }
                reconcileCallState()
                if(activeRequestId!=null && !bridgeRunning && bridgePending && bridgeEnabled())startDigitalBridgeIfConfigured()

                enforcePlacementWatchdog()
                // DTMF is interactive. Poll quickly while a call is active so an
                // IVR keypress arrives in hundreds of milliseconds, not several seconds.
                Thread.sleep(if(activeRequestId!=null) 500L else 2000L)
            }catch(_:InterruptedException){Thread.currentThread().interrupt();break}
            catch(_:Exception){try{Thread.sleep(5000)}catch(_:InterruptedException){break}}
        }
    }

    private fun gatewayGet(token:String):JSONObject{
        val c=URL(GATEWAY_URL).openConnection() as HttpURLConnection;c.requestMethod="GET";c.connectTimeout=10000;c.readTimeout=10000
        c.setRequestProperty("x-device-code",DEVICE_CODE);c.setRequestProperty("x-device-token",token)
        val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};c.disconnect()
        if(code !in 200..299)throw IllegalStateException("HTTP $code");return JSONObject(body)
    }
    private fun gatewayAck(token:String,id:String,state:String,kind:String="request",error:String?=null)=post(token,JSONObject().put("id",id).put("status",state).put("kind",kind).put("error",error))
    private fun sendEvent(type:String,state:String,requestId:String?){
        val token=getSharedPreferences("gateway",MODE_PRIVATE).getString("device_token",null)?:return
        if(!outboundIo.isShutdown)outboundIo.execute{try{post(token,JSONObject().put("type","event").put("event_type",type).put("call_state",state).put("request_id",requestId))}catch(_:Exception){}}
    }
    private fun completeRequest(id:String){
        val token=getSharedPreferences("gateway",MODE_PRIVATE).getString("device_token",null)?:return
        if(!outboundIo.isShutdown)outboundIo.execute{try{gatewayAck(token,id,"completed")}catch(_:Exception){}}
    }
    private fun failRequest(id:String,error:String){
        val token=getSharedPreferences("gateway",MODE_PRIVATE).getString("device_token",null)?:return
        if(!outboundIo.isShutdown)outboundIo.execute{try{gatewayAck(token,id,"failed","request",error)}catch(_:Exception){}}
    }
    private fun post(token:String,json:JSONObject){
        val c=URL(GATEWAY_URL).openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10000;c.readTimeout=10000
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-device-code",DEVICE_CODE);c.setRequestProperty("x-device-token",token)
        c.outputStream.use{it.write(json.toString().toByteArray())};val code=c.responseCode
        if(code in 200..299)c.inputStream.close() else c.errorStream?.close();c.disconnect();if(code !in 200..299)throw IllegalStateException("POST HTTP $code")
    }
    private fun placeSimCall(number:String):CallResult{
        if(!validNumber(number))return CallResult(false,"Invalid number")
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)return CallResult(false,"Call permission required")
        return try{val extras=Bundle().apply{putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,false)};getSystemService(TelecomManager::class.java).placeCall(Uri.parse("tel:$number"),extras);CallResult(true,"Call requested")}catch(e:Exception){CallResult(false,e.message?:"Call failed")}
    }
    private fun endSimCall():CallResult{
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ANSWER_PHONE_CALLS)!=PackageManager.PERMISSION_GRANTED)return CallResult(false,"Phone-control permission required")
        return try{@Suppress("DEPRECATION") val ended=getSystemService(TelecomManager::class.java).endCall();CallResult(ended,if(ended)"Call ended" else "No active call")}catch(e:Exception){CallResult(false,e.message?:"Hang-up failed")}
    }
    private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Vorlen calling session",NotificationManager.IMPORTANCE_LOW))}
    private fun notification():Notification{
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,GatewayService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("Vorlen Call Gateway").setContentText("Calling session approved — gateway active").setOngoing(true).setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel,"End session",stop).build()
    }
    private fun stopGateway(){running=false;daemonReady=false;daemonHealthyStreak=0;bridgePending=false;bridgeEverActive=false;ShellCallAudio.stopLaptopNetworkBridge();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){running=false;daemonReady=false;daemonHealthyStreak=0;bridgePending=false;bridgeEverActive=false;ShellCallAudio.stopLaptopNetworkBridge();if(::telephony.isInitialized){@Suppress("DEPRECATION") telephony.listen(phoneListener,PhoneStateListener.LISTEN_NONE)};pollIo.shutdownNow();outboundIo.shutdownNow();bridgeIo.shutdownNow();daemonIo.shutdownNow();super.onDestroy()}
    override fun onBind(intent:Intent?)=null
    data class CallResult(val success:Boolean,val message:String)
    private fun validNumber(n:String):Boolean{if(!n.matches(Regex("^\\+?[0-9]{7,15}$")))return false;return n.filter(Char::isDigit) !in setOf("999","112","911","000")}
    companion object{
        const val ACTION_STOP="com.vorlen.callgateway.lab.STOP_GATEWAY"
        const val ACTION_ENABLE_BRIDGE="com.vorlen.callgateway.lab.ENABLE_BRIDGE"
        const val ACTION_DISABLE_BRIDGE="com.vorlen.callgateway.lab.DISABLE_BRIDGE"
        private const val CHANNEL="vorlen_gateway_session";private const val NOTIFICATION_ID=2001;private const val DEVICE_CODE="s24fe-digital";private const val GATEWAY_URL="https://mzkaodoruhklzluikagy.supabase.co/functions/v1/vorlen-call-device"}
}
