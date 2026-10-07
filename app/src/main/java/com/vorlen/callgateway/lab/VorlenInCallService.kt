package com.vorlen.callgateway.lab

import android.telecom.Call
import android.telecom.InCallService
import java.util.concurrent.atomic.AtomicReference

object VorlenCallControl {
    private val active = AtomicReference<Call?>(null)

    fun attach(call: Call) {
        active.set(call)
    }

    fun detach(call: Call) {
        active.compareAndSet(call, null)
    }

    fun hasActiveCall(): Boolean = active.get()?.state?.let { it != Call.STATE_DISCONNECTED && it != Call.STATE_DISCONNECTING } == true

    fun sendDtmf(sequence: String, toneDurationMs: Long, gapMs: Long): Result<Int> = runCatching {
        val call = active.get() ?: error("No Telecom call is attached. Enable Vorlen as the default phone app for DTMF control.")
        val symbols = sequence.filter { it != ',' }
        require(symbols.isNotEmpty()) { "No DTMF digits supplied" }
        require(symbols.all { it in "0123456789*#" }) { "Unsupported DTMF digit" }

        var sent = 0
        for (ch in sequence) {
            if (ch == ',') {
                Thread.sleep(maxOf(350L, gapMs * 3))
                continue
            }
            check(call.state != Call.STATE_DISCONNECTED && call.state != Call.STATE_DISCONNECTING) { "Call disconnected before DTMF could be sent" }
            call.playDtmfTone(ch)
            try {
                Thread.sleep(toneDurationMs)
            } finally {
                call.stopDtmfTone()
            }
            sent++
            Thread.sleep(gapMs)
        }
        sent
    }
}

class VorlenInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        VorlenCallControl.attach(call)
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(changedCall: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED) {
                    VorlenCallControl.detach(changedCall)
                    changedCall.unregisterCallback(this)
                }
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        VorlenCallControl.detach(call)
        super.onCallRemoved(call)
    }
}
