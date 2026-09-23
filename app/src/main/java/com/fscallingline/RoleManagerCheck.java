package com.fscallingline;
import android.app.role.RoleManager;
import android.content.Context;
public final class RoleManagerCheck {
 public static boolean granted(Context c){return c.getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_DIALER);}
 public static void require(Context c){if(!granted(c))throw new SecurityException("Choose FS Calling Line as the default phone app first");}
}
