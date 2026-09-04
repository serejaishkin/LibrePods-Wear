package me.kavishdevar.librepods.wear.relay

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService

class PhoneRelayListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneRelayService"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = String(messageEvent.data)

        Log.d(TAG, "Message: path=$path data=${data.take(200)}")

        when {
            path == PhoneRelayProtocol.PATH_STATE -> {
                Log.d(TAG, "State update from phone")
            }
            path == PhoneRelayProtocol.PATH_ERROR -> {
                Log.e(TAG, "Phone relay error: $data")
            }
            path == PhoneRelayProtocol.PATH_HANDSHAKE -> {
                Log.i(TAG, "Phone handshake")
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            Log.d(TAG, "Data changed: ${uri.path}")
        }
    }

    override fun onPeerConnected(peer: Node) {
        Log.i(TAG, "Peer connected: ${peer.id}")
    }

    override fun onPeerDisconnected(peer: Node) {
        Log.i(TAG, "Peer disconnected: ${peer.id}")
    }
}
