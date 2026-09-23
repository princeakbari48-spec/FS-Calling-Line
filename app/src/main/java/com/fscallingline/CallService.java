package com.fscallingline;
import android.app.*;
import android.content.*;
import android.os.*;
import android.telecom.*;
import org.json.*;
import java.util.*;

public class CallService extends InCallService {
 public static CallService instance;
 private final LinkedHashMap<String,Entry> calls=new LinkedHashMap<>();
 private final Handler handler=new Handler(Looper.getMainLooper());
 private static final class Entry {Call call;String id;AutoDtmf dtmf=new AutoDtmf();Call.Callback callback;boolean active;boolean recorded;boolean autoAnswered;String audio="Phone managed";long ended;long connected;String digit;Runnable toneStop;}
 @Override public void onCreate(){super.onCreate();instance=this;}
 @Override public void onCallAdded(Call call){
  Entry e=new Entry();e.call=call;
  e.id=UUID.nameUUIDFromBytes((call.getDetails().getCreationTimeMillis()+"|"+call.getDetails().getHandle()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  long existing=call.getDetails().getConnectTimeMillis();if(existing>0){e.connected=existing;e.active=true;if(System.currentTimeMillis()-existing>1000)e.dtmf.state(true,false,SystemClock.elapsedRealtime(),0);}calls.put(e.id,e);
  e.callback=new Call.Callback(){@Override public void onStateChanged(Call c,int s){changed(e);}@Override public void onDetailsChanged(Call c,Call.Details d){changed(e);}};
  call.registerCallback(e.callback,handler);changed(e);
 }
 @Override public void onCallRemoved(Call call){Entry found=null;for(Entry e:calls.values())if(e.call==call)found=e;if(found!=null){changed(found);found.dtmf.cancel();stopTone(found);call.unregisterCallback(found.callback);calls.remove(found.id);}publish();notifyCall();}
 public JSONArray snapshot(){JSONArray arr=new JSONArray();for(Entry e:calls.values())if(e.call.getState()!=Call.STATE_DISCONNECTED)arr.put(json(e));return arr;}
 private JSONObject json(Entry e){
  try{
   Call.Details d=e.call.getDetails();long duration=e.connected>0?Math.max(0,(e.ended>0?e.ended:System.currentTimeMillis())-e.connected)/1000:0;
   String number=d.getHandle()==null||d.getHandlePresentation()!=TelecomManager.PRESENTATION_ALLOWED?"":d.getHandle().getSchemeSpecificPart();
   String name=d.getCallerDisplayName()==null||d.getCallerDisplayNamePresentation()!=TelecomManager.PRESENTATION_ALLOWED?"":d.getCallerDisplayName();
   int state=e.call.getState();String result="";
   if(state==Call.STATE_DISCONNECTED){int cause=d.getDisconnectCause().getCode();result=e.active?"ANSWERED":cause==DisconnectCause.MISSED?"MISSED":cause==DisconnectCause.REJECTED?"DECLINED":"ENDED";}
   return new JSONObject().put("id",e.id).put("state",stateName(state)).put("number",number).put("name",name).put("incoming",d.getCallDirection()==Call.Details.DIRECTION_INCOMING).put("created",d.getCreationTimeMillis()).put("connected",e.connected).put("duration",duration).put("result",result).put("audioDevice",e.audio).put("bluetooth",BluetoothService.instance!=null&&BluetoothService.instance.connected()?"CONNECTED":"DISCONNECTED");
  }catch(Exception ex){return new JSONObject();}
 }
 private static String stateName(int s){switch(s){case Call.STATE_RINGING:return "RINGING";case Call.STATE_ACTIVE:return "CONNECTED";case Call.STATE_HOLDING:return "ON HOLD";case Call.STATE_DISCONNECTED:return "ENDED";case Call.STATE_DISCONNECTING:return "ENDING";case Call.STATE_DIALING:return "DIALING";case Call.STATE_CONNECTING:return "CONNECTING";case Call.STATE_SELECT_PHONE_ACCOUNT:return "SELECT SIM ON PHONE";default:return "CONNECTING";}}
 private void changed(Entry e){
  boolean active=e.call.getState()==Call.STATE_ACTIVE;
  if(e.call.getState()==Call.STATE_RINGING&&!e.autoAnswered&&getSharedPreferences("fs",0).getBoolean("autoAnswer",false)&&controlConnected()){
   e.autoAnswered=true;try{RoleManagerCheck.require(this);e.call.answer(VideoProfile.STATE_AUDIO_ONLY);}catch(Exception ex){sendError("Automatic answer unavailable");}
  }
  if(active&&getCallAudioState()!=null)e.audio=audioName(getCallAudioState().getRoute());
  if(active){e.active=true;if(e.connected==0)e.connected=e.call.getDetails().getConnectTimeMillis();if(e.connected==0)e.connected=System.currentTimeMillis();}
  android.content.SharedPreferences p=getSharedPreferences("fs",0);
  long delay=p.getInt("delay",2000);e.digit=p.getString("digit","1");
  boolean allowed=p.getBoolean("autoDtmf",true)&&!p.getBoolean("dtmfAttempted:"+e.id,false);
  e.dtmf.state(active,allowed,SystemClock.elapsedRealtime(),delay);
  if(active)handler.postDelayed(()->{
   if(!calls.containsKey(e.id)||!getSharedPreferences("fs",0).getBoolean("autoDtmf",true))return;
   if(e.dtmf.poll(e.call.getState()==Call.STATE_ACTIVE,SystemClock.elapsedRealtime())){
    // Persist before transmission; never replay an automatic digit after reconnect/restart.
    if(!getSharedPreferences("fs",0).edit().putBoolean("dtmfAttempted:"+e.id,true).commit()){sendError("Automatic DTMF cancelled: could not persist attempt");return;}
    try{tone(e,e.digit.charAt(0));}catch(Exception ex){sendError("Automatic DTMF unavailable");}
   }
  },delay);
  else stopTone(e);
  if(e.call.getState()==Call.STATE_DISCONNECTED&&!e.recorded){e.recorded=true;e.ended=System.currentTimeMillis();JSONObject result=json(e);new Journal(this).save(result);try{sendToPc(BluetoothService.message("CALL_ENDED").put("call",result));}catch(Exception ignored){}}
  publish();notifyCall();
 }
 private void stopTone(Entry e){if(e.toneStop!=null){handler.removeCallbacks(e.toneStop);e.toneStop=null;try{e.call.stopDtmfTone();}catch(Exception ignored){}}}
 private void tone(Entry e,char digit){if(e.call.getState()!=Call.STATE_ACTIVE)throw new IllegalStateException("Call is not active");if("0123456789*#".indexOf(digit)<0)throw new IllegalArgumentException("Invalid DTMF digit");stopTone(e);e.call.playDtmfTone(digit);e.toneStop=()->{e.call.stopDtmfTone();e.toneStop=null;};handler.postDelayed(e.toneStop,180);}


 private boolean bluetoothConnectGranted(){
  return android.os.Build.VERSION.SDK_INT<31 ||
          checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==android.content.pm.PackageManager.PERMISSION_GRANTED;
 }
 private String bluetoothName(android.bluetooth.BluetoothDevice d){
  if(d==null || !bluetoothConnectGranted())return "";
  try{String n=d.getName();return n==null?"":n;}catch(SecurityException ignored){return "";}
 }
 private String bluetoothAddress(android.bluetooth.BluetoothDevice d){
  if(d==null || !bluetoothConnectGranted())return "";
  try{String a=d.getAddress();return a==null?"":a;}catch(SecurityException ignored){return "";}
 }
 private JSONArray bluetoothDevices(){
  JSONArray out=new JSONArray();
  CallAudioState audio=getCallAudioState();
  if(audio==null||android.os.Build.VERSION.SDK_INT<28)return out;
  try{
   for(android.bluetooth.BluetoothDevice d:audio.getSupportedBluetoothDevices()){
    JSONObject item=new JSONObject();
    String name=bluetoothName(d);String address=bluetoothAddress(d);
    item.put("name",name).put("address",address);out.put(item);
   }
  }catch(Exception ignored){}
  return out;
 }
 private String activeBluetoothName(){
  if(android.os.Build.VERSION.SDK_INT<28)return "";
  CallAudioState audio=getCallAudioState();if(audio==null)return "";
  try{android.bluetooth.BluetoothDevice d=audio.getActiveBluetoothDevice();return bluetoothName(d);}catch(SecurityException ignored){return "";}
 }
 private void routeBluetooth(String preferredName)throws Exception{
  CallAudioState audio=getCallAudioState();
  if(audio==null||(audio.getSupportedRouteMask()&CallAudioState.ROUTE_BLUETOOTH)==0)throw new IllegalStateException("Bluetooth call audio is not available on the phone");
  if(android.os.Build.VERSION.SDK_INT>=28){
   java.util.Collection<android.bluetooth.BluetoothDevice> devices=audio.getSupportedBluetoothDevices();
   android.bluetooth.BluetoothDevice selected=null;
   if(preferredName!=null&&!preferredName.trim().isEmpty()){
    for(android.bluetooth.BluetoothDevice d:devices){
     String name=bluetoothName(d);
     if(name.equalsIgnoreCase(preferredName)||name.toLowerCase(java.util.Locale.ROOT).contains(preferredName.toLowerCase(java.util.Locale.ROOT))||preferredName.toLowerCase(java.util.Locale.ROOT).contains(name.toLowerCase(java.util.Locale.ROOT))){selected=d;break;}
    }
   }
   if(selected==null&&devices.size()==1)selected=devices.iterator().next();
   if(selected==null){
    StringBuilder names=new StringBuilder();
    for(android.bluetooth.BluetoothDevice d:devices){String n="";try{n=d.getName()==null?"":d.getName();}catch(SecurityException ignored){}if(!n.isEmpty()){if(names.length()>0)names.append(", ");names.append(n);}}
    throw new IllegalStateException(names.length()==0?"No Bluetooth call-audio device is available":"PC was not found in Bluetooth call-audio devices. Available: "+names);
   }
   requestBluetoothAudio(selected);
  }else setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
 }
 public void command(JSONObject msg)throws Exception{
  RoleManagerCheck.require(this);
  String type=msg.getString("type");
  if(type.equals("DIAL")){if(checkSelfPermission(android.Manifest.permission.CALL_PHONE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Grant calling permission first");String number=msg.getString("number");if(!number.matches("[+0-9*#]{1,32}"))throw new IllegalArgumentException("Invalid number");getSystemService(TelecomManager.class).placeCall(android.net.Uri.fromParts("tel",number,null),new Bundle());return;}
  Entry e=calls.get(msg.getString("callId"));if(e==null)throw new IllegalStateException("Call no longer exists");
  switch(type){
   case "ANSWER":if(e.call.getState()!=Call.STATE_RINGING)throw new IllegalStateException("Call is not ringing");e.call.answer(VideoProfile.STATE_AUDIO_ONLY);break;
   case "DECLINE":if(e.call.getState()!=Call.STATE_RINGING)throw new IllegalStateException("Call is not ringing");e.call.reject(false,null);break;
   case "END":e.call.disconnect();break;
   case "DTMF":String digit=msg.getString("digit");if(digit.length()!=1)throw new IllegalArgumentException("One digit required");tone(e,digit.charAt(0));break;
   case "MUTE":setMuted(msg.getBoolean("muted"));break;
   case "AUDIO_ROUTE":
    int route=msg.getInt("route");CallAudioState audio=getCallAudioState();
    if(route!=CallAudioState.ROUTE_EARPIECE&&route!=CallAudioState.ROUTE_SPEAKER&&route!=CallAudioState.ROUTE_BLUETOOTH)throw new IllegalArgumentException("Unsupported audio route");
    if(audio==null||(audio.getSupportedRouteMask()&route)==0)throw new IllegalStateException("This audio route is not available on your phone");
    if(route==CallAudioState.ROUTE_BLUETOOTH)routeBluetooth(msg.optString("bluetoothName",""));else setAudioRoute(route);break;
   default:throw new IllegalArgumentException("Unsupported call command");
  }
 }
 private boolean controlConnected(){
  NetworkService n=NetworkService.instance;
  if(n!=null&&n.connected())return true;
  BluetoothService b=BluetoothService.instance;
  return b!=null&&b.connected();
 }
 private void sendToPc(JSONObject msg){
  NetworkService n=NetworkService.instance;
  if(n!=null&&n.connected()){
   try{n.send(msg);return;}catch(Exception ignored){}
  }
  BluetoothService b=BluetoothService.instance;
  if(b!=null&&b.connected()){
   try{b.send(msg);}catch(Exception ignored){}
  }
 }
 public void publish(){
  try{
   sendToPc(BluetoothService.message("SNAPSHOT")
           .put("calls",snapshot())
           .put("callControl",RoleManagerCheck.granted(this))
           .put("muted",getCallAudioState()!=null&&getCallAudioState().isMuted())
           .put("supportedRoutes",getCallAudioState()==null?0:getCallAudioState().getSupportedRouteMask())
           .put("bluetoothDevices",bluetoothDevices())
           .put("activeBluetoothDevice",activeBluetoothName()));
  }catch(Exception ignored){}
 }
 private void sendError(String text){
  try{sendToPc(BluetoothService.message("ERROR").put("message",text));}catch(Exception ignored){}
 }
 private void notifyCall(){
  NotificationManager nm=getSystemService(NotificationManager.class);Entry display=null;for(Entry e:calls.values())if(e.call.getState()!=Call.STATE_DISCONNECTED){display=e;if(e.call.getState()==Call.STATE_RINGING)break;}
  if(display==null){nm.cancel(2);return;}
  nm.createNotificationChannel(new NotificationChannel("calls","Cellular calls",NotificationManager.IMPORTANCE_HIGH));
  PendingIntent pi=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  boolean ringing=display.call.getState()==Call.STATE_RINGING;
  Notification.Builder n=new Notification.Builder(this,"calls").setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("FS Calling Line").setContentText(ringing?"Incoming call — tap to answer":"Call in progress — tap for controls").setContentIntent(pi).setOngoing(true).setCategory(Notification.CATEGORY_CALL);
  if(ringing){n.setFullScreenIntent(pi,true);n.addAction(new Notification.Action.Builder(null,"Answer",action(display.id,"ANSWER",20)).build());n.addAction(new Notification.Action.Builder(null,"Decline",action(display.id,"DECLINE",21)).build());}
  else n.addAction(new Notification.Action.Builder(null,"End call",action(display.id,"END",22)).build());
  nm.notify(2,n.build());
 }
 private PendingIntent action(String id,String type,int request){return PendingIntent.getBroadcast(this,request,new Intent(this,CallActionReceiver.class).putExtra("callId",id).putExtra("type",type),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 private static String audioName(int route){switch(route){case CallAudioState.ROUTE_BLUETOOTH:return "Android Bluetooth headset";case CallAudioState.ROUTE_SPEAKER:return "Android speaker";case CallAudioState.ROUTE_WIRED_HEADSET:return "Android wired headset";case CallAudioState.ROUTE_EARPIECE:return "Android earpiece";default:return "Phone managed";}}
 @Override public void onCallAudioStateChanged(CallAudioState state){super.onCallAudioStateChanged(state);for(Entry e:calls.values())if(e.call.getState()!=Call.STATE_DISCONNECTED)e.audio=audioName(state.getRoute());publish();}
 @Override public void onDestroy(){for(Entry e:calls.values()){e.dtmf.cancel();stopTone(e);e.call.unregisterCallback(e.callback);}handler.removeCallbacksAndMessages(null);calls.clear();instance=null;getSystemService(NotificationManager.class).cancel(2);super.onDestroy();}
}





