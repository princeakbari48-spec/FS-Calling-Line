package com.fscallingline;
import android.content.*;
import org.json.*;
public class CallActionReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){try{if(CallService.instance!=null)CallService.instance.command(new JSONObject().put("type",i.getStringExtra("type")).put("callId",i.getStringExtra("callId")));}catch(Exception e){android.widget.Toast.makeText(c,"Call control unavailable",android.widget.Toast.LENGTH_LONG).show();}}
}
