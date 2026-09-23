package com.fscallingline;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class BluetoothService extends Service {
 public static final UUID UUID_SERVICE=UUID.fromString("a76a4196-73ba-4ee6-9f2e-2c53645ced82");
 public static volatile BluetoothService instance;
 public static volatile String status="Stopped";
 private volatile boolean running;
 private BluetoothServerSocket server;
 private volatile BluetoothSocket peer;
 private volatile java.util.concurrent.BlockingQueue<String> outbound; private final java.util.concurrent.Semaphore pending=new java.util.concurrent.Semaphore(8);
 private final Handler main=new Handler(Looper.getMainLooper());
 public static JSONObject message(String type) { try { return new JSONObject().put("version",1).put("type",type); } catch(JSONException e){throw new IllegalStateException(e);} }
 @Override public void onCreate(){ super.onCreate(); instance=this; }
 @Override public int onStartCommand(Intent i,int flags,int id){
  NotificationManager nm=getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel("bluetooth","Bluetooth connection",NotificationManager.IMPORTANCE_LOW));
  PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  startForeground(1,new Notification.Builder(this,"bluetooth").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("FS Calling Line").setContentText("Bluetooth companion is running").setContentIntent(pi).build());
  if(!running){ running=true; new Thread(this::listen,"FS-Bluetooth").start(); }
  return START_NOT_STICKY;
 }
 private void listen(){
  try{
   if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Grant Nearby Devices permission first");
   BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
   if(adapter==null||!adapter.isEnabled())throw new IOException("Bluetooth unavailable or disabled");
   server=adapter.listenUsingRfcommWithServiceRecord("FS Calling Line",UUID_SERVICE);
   while(running){
    status="Waiting for approved PC";
    BluetoothSocket candidate=server.accept();
    BluetoothDevice device=candidate.getRemoteDevice();
    String approved=getSharedPreferences("fs",0).getString("trusted","");
    if(device.getBondState()!=BluetoothDevice.BOND_BONDED||!device.getAddress().equals(approved)){candidate.close();continue;}
    peer=candidate;
    try{
     final java.util.concurrent.BlockingQueue<String> queue=new java.util.concurrent.ArrayBlockingQueue<>(64);outbound=queue;
     new Thread(()->{try{DataOutputStream target=new DataOutputStream(candidate.getOutputStream());while(peer==candidate){String value=queue.poll(1,java.util.concurrent.TimeUnit.SECONDS);if(value!=null){byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);target.writeInt(bytes.length);target.write(bytes);target.flush();}}}catch(Exception ex){try{candidate.close();}catch(IOException ignored){}}},"FS-Bluetooth-writer").start();
     DataInputStream in=new DataInputStream(peer.getInputStream());
     status="Connected to "+device.getName();
     android.content.SharedPreferences prefs=getSharedPreferences("fs",0); String token=prefs.getLong("qrExpires",0)>System.currentTimeMillis()?prefs.getString("qrToken",""):""; send(message("READY").put("pairingToken",token));
     while(running&&peer!=null){
      int size=in.readInt();
      if(size<2||size>65536)throw new IOException("Invalid frame length");
      byte[] bytes=new byte[size];in.readFully(bytes);
      JSONObject msg=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
      if(msg.getInt("version")!=1)throw new IOException("Unsupported protocol version");
      pending.acquire();main.post(()->{try{if(peer==candidate)handle(msg);}finally{pending.release();}});
     }
    }catch(Exception e){status="Disconnected: "+e.getClass().getSimpleName();}
    finally{disconnect();}
   }
  }catch(SecurityException e){status="Bluetooth permission denied";running=false;stopSelf();}catch(Exception e){status="Bluetooth unavailable: "+e.getMessage();running=false;stopSelf();}
 }
 private final java.util.LinkedHashSet<String> requests=new java.util.LinkedHashSet<>();
 private void handle(JSONObject msg){
  try{
   String type=msg.getString("type");
   if(type.equals("PING")){send(message("PONG"));return;}
   if(type.equals("DISCONNECT")){disconnect();return;}
   if(type.equals("TRANSCRIPT_STATE")||type.equals("TRANSCRIPT_ENTRY")||type.equals("TRANSCRIPT_CLEAR")){TranscriptStore.handle(this,msg);return;}
   if(type.equals("SYNC")){
    if(CallService.instance!=null)CallService.instance.publish();else send(message("SNAPSHOT").put("calls",new JSONArray()).put("callControl",RoleManagerCheck.granted(this)));
    send(message("SETTINGS").put("autoAnswer",getSharedPreferences("fs",0).getBoolean("autoAnswer",false)).put("autoDtmf",getSharedPreferences("fs",0).getBoolean("autoDtmf",true)).put("digit",getSharedPreferences("fs",0).getString("digit","1")).put("delay",getSharedPreferences("fs",0).getInt("delay",2000)));return;
   }
   if(type.equals("HISTORY")){int offset=msg.optInt("offset",0);if(offset<0||offset>1000000)throw new IllegalArgumentException("Invalid history page");send(message("HISTORY").put("calls",new Journal(this).page(offset)).put("offset",offset));return;}
   if(type.equals("CONTACTS")){
    if(checkSelfPermission(android.Manifest.permission.READ_CONTACTS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Grant Contacts permission on Android first");
    int offset=msg.optInt("offset",0);if(offset<0||offset>100000)throw new IllegalArgumentException("Invalid contacts page");JSONArray contacts=new JSONArray();
    try(android.database.Cursor c=getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{"display_name","data1"},null,null,"display_name ASC")){
     if(c!=null){c.moveToPosition(offset-1);while(contacts.length()<20&&c.moveToNext())contacts.put(new JSONObject().put("name",c.getString(0)).put("number",c.getString(1)));}
    }
    send(message("CONTACTS").put("contacts",contacts).put("offset",offset));return;
   }
   String request=msg.getString("requestId");if(request.length()>64||requests.contains(request))throw new IllegalArgumentException("Invalid or duplicate command");requests.add(request);while(requests.size()>128)requests.remove(requests.iterator().next());
   if(type.equals("SETTINGS")){
    String digit=msg.getString("digit");int delay=msg.getInt("delay");if(!digit.matches("[0-9*#]")||delay<0||delay>30000)throw new IllegalArgumentException("Invalid DTMF settings");
    getSharedPreferences("fs",0).edit().putBoolean("autoAnswer",msg.optBoolean("autoAnswer",false)).putBoolean("autoDtmf",msg.getBoolean("autoDtmf")).putString("digit",digit).putInt("delay",delay).apply();
   }else if(type.equals("CLEAR_HISTORY")){new Journal(this).clear();}
   else if(type.equals("DIAL")&&CallService.instance==null){RoleManagerCheck.require(this);String number=msg.getString("number");if(!number.matches("[+0-9*#]{1,32}"))throw new IllegalArgumentException("Invalid number");getSystemService(android.telecom.TelecomManager.class).placeCall(android.net.Uri.fromParts("tel",number,null),new Bundle());}
   else {if(CallService.instance==null)throw new IllegalStateException("Call control unavailable; no current call");CallService.instance.command(msg);}
   send(message("ACK").put("requestId",request).put("message","Request submitted; awaiting phone state"));
  }catch(Exception e){try{send(message("ERROR").put("message",e.getMessage()==null?"Operation unavailable":e.getMessage()));}catch(JSONException ignored){}}
 }
 public void send(JSONObject msg){
  java.util.concurrent.BlockingQueue<String> queue=outbound;if(queue==null)return;
  String value=msg.toString();if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536||!queue.offer(value))disconnect();
 } public boolean connected(){return peer!=null;}
 public synchronized void disconnect(){try{if(peer!=null)peer.close();}catch(IOException ignored){}peer=null;outbound=null;status="Disconnected";}
 @Override public void onDestroy(){running=false;disconnect();try{if(server!=null)server.close();}catch(IOException ignored){}instance=null;super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}





