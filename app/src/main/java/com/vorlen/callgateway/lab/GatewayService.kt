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
    @Volatile private var running=false
    @Volatile private var activeRequestId:String?=null
    @Volatile private var sawOffHook=false
    @Volatile private var lastCallState=TelephonyManager.CALL_STATE_IDLE
    @Volatile private var requestStartedAtMs=0L
    @Volatile private var bridgeRunning=false
    private lateinit var telephony:TelephonyManager

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
            startDigitalBridgeIfConfigured()
        }
        val id=activeRequestId?:return
        if(state!=previous)sendEvent("call_state",name,id)
        if(state==TelephonyManager.CALL_STATE_IDLE&&sawOffHook){
            sendEvent("call_ended","idle",id)
            completeRequest(id)
            activeRequestId=null
            sawOffHook=false
            requestStartedAtMs=0L
        }
    }

    private fun startDigitalBridgeIfConfigured(){
        if(bridgeRunning)return
        val prefs=getSharedPreferences("gateway",MODE_PRIVATE)
        val host=prefs.getString("laptop_host","192.168.1.8")?.trim().orEmpty()
        val port=prefs.getInt("laptop_port",28761)
        if(host.isBlank()||port !in 1..65535)return
        bridgeRunning=true
        bridgeIo.execute{
            try{
                // Samsung/One UI does not always deliver CALL_STATE_OFFHOOK reliably.
                // Start from the successful placeCall path as well and retry while the
                // cellular audio route is coming up. The shell bridge itself is the
                // authority on whether Telephony RX/TX is ready.
                var connected=false
                var lastReport="Digital bridge did not become ready"
                val deadline=SystemClock.elapsedRealtime()+30_000L
                while(running && activeRequestId!=null && !connected && SystemClock.elapsedRealtime()<deadline){
                    val result=runBlocking{
                        ShellCallAudio.runLaptopNetworkBridge(this@GatewayService,host,port){ _ ->
                            connected=true
                            sendEvent("digital_bridge_active","active",activeRequestId)
                        }
                    }
                    if(connected) break
                    lastReport=result.report
                    Thread.sleep(750)
                }
                if(!connected && activeRequestId!=null){
                    sendEvent("digital_bridge_failed","active",activeRequestId)
                }
            }catch(_:InterruptedException){
                Thread.currentThread().interrupt()
            }catch(_:Throwable){
                if(activeRequestId!=null)sendEvent("digital_bridge_failed","active",activeRequestId)
            }finally{
                bridgeRunning=false
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
        val token=getSharedPreferences("gateway",MODE_PRIVATE).getString("device_token",null)
        if(token.isNullOrBlank()){stopGateway();return START_NOT_STICKY}
        if(!running){running=true;pollIo.execute{poll(token)}}
        return START_NOT_STICKY
    }

    private fun poll(token:String){
        while(running&&!Thread.currentThread().isInterrupted){
            try{
                val command=gatewayGet(token).optJSONObject("command")
                if(command!=null&&command.optString("action")=="hangup"){
                    val id=command.optString("id"); val requestId=command.optString("request_id")
                    if(requestId.isBlank()||activeRequestId==null||requestId==activeRequestId){
                        val result=endSimCall(); gatewayAck(token,id,if(result.success)"completed" else "failed","command",if(result.success)null else result.message)
                    }else gatewayAck(token,id,"failed","command","Command does not match active request")
                }else if(command!=null&&command.optString("action")=="call"){
                    val id=command.optString("id");val phone=command.optString("phone_number")
                    if(validNumber(phone)&&activeRequestId==null){
                        activeRequestId=id;sawOffHook=lastCallState==TelephonyManager.CALL_STATE_OFFHOOK;requestStartedAtMs=SystemClock.elapsedRealtime()
                        val result=placeSimCall(phone)
                        if(!result.success){activeRequestId=null;sawOffHook=false;requestStartedAtMs=0L}
                        gatewayAck(token,id,if(result.success)"claimed" else "failed","request",if(result.success)null else result.message)
                        if(result.success){
                            sendEvent("call_requested",if(sawOffHook)"active" else "dialing",id)
                            // Do not depend solely on PhoneStateListener: begin bridge
                            // acquisition immediately after Android accepts placeCall().
                            startDigitalBridgeIfConfigured()
                        }
                    }
                }
                reconcileCallState()
                enforcePlacementWatchdog()
                Thread.sleep(3000)
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
    private fun stopGateway(){running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){running=false;if(::telephony.isInitialized){@Suppress("DEPRECATION") telephony.listen(phoneListener,PhoneStateListener.LISTEN_NONE)};pollIo.shutdownNow();outboundIo.shutdownNow();bridgeIo.shutdownNow();super.onDestroy()}
    override fun onBind(intent:Intent?)=null
    data class CallResult(val success:Boolean,val message:String)
    private fun validNumber(n:String):Boolean{if(!n.matches(Regex("^\\+?[0-9]{7,15}$")))return false;return n.filter(Char::isDigit) !in setOf("999","112","911","000")}
    companion object{const val ACTION_STOP="com.vorlen.callgateway.lab.STOP_GATEWAY";private const val CHANNEL="vorlen_gateway_session";private const val NOTIFICATION_ID=2001;private const val DEVICE_CODE="s24fe-digital";private const val GATEWAY_URL="https://mzkaodoruhklzluikagy.supabase.co/functions/v1/vorlen-call-device"}
}
