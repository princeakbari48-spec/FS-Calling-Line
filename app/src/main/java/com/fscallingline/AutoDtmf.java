package com.fscallingline;
/** Pure state logic: one automatic DTMF attempt per call, never on Answer or resume. */
public final class AutoDtmf {
 private boolean seenActive;
 private long due=-1;
 public void state(boolean active,boolean enabled,long now,long delay){
  if(!active){due=-1;return;}
  if(!seenActive){seenActive=true;if(enabled)due=now+delay;}
 }
 public boolean poll(boolean active,long now){if(!active){due=-1;return false;}if(due>=0&&now>=due){due=-1;return true;}return false;}
 public void cancel(){due=-1;}
}
