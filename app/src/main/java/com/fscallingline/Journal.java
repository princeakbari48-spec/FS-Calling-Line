package com.fscallingline;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import org.json.*;
public final class Journal extends SQLiteOpenHelper {
 public Journal(Context c){super(c,"fs-calls.db",null,1);}
 @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE calls(id TEXT PRIMARY KEY, value TEXT NOT NULL, created INTEGER NOT NULL)");}
 @Override public void onUpgrade(SQLiteDatabase d,int a,int b){}
 public void save(JSONObject call){try{ContentValues v=new ContentValues();v.put("id",call.getString("id"));v.put("value",call.toString());v.put("created",call.getLong("created"));getWritableDatabase().insertWithOnConflict("calls",null,v,SQLiteDatabase.CONFLICT_REPLACE);getWritableDatabase().execSQL("DELETE FROM calls WHERE id NOT IN (SELECT id FROM calls ORDER BY created DESC LIMIT 500)");}catch(Exception e){android.util.Log.e("FS Calling Line","History write failed",e);}finally{close();}}
 public JSONArray page(int offset){JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM calls ORDER BY created DESC LIMIT 20 OFFSET ?",new String[]{String.valueOf(offset)})){while(c.moveToNext())out.put(new JSONObject(c.getString(0)));}catch(Exception e){android.util.Log.e("FS Calling Line","History read failed",e);}finally{close();}return out;}
 public JSONArray all(){JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM calls ORDER BY created DESC LIMIT 500",null)){while(c.moveToNext())out.put(new JSONObject(c.getString(0)));}catch(Exception e){android.util.Log.e("FS Calling Line","History read failed",e);}finally{close();}return out;}
 public void clear(){getWritableDatabase().delete("calls",null,null);close();}
}
