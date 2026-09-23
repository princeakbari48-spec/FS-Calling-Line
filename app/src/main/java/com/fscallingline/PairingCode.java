package com.fscallingline;
public final class PairingCode {
 private PairingCode(){}
 public static void validate(String app,int version,String address,String token,long expires,long now){
  if(!"FSCallingLine".equals(app)||version!=1)throw new IllegalArgumentException("Not an FS Calling Line code.");
  if(!address.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")||"00:00:00:00:00:00".equals(address))throw new IllegalArgumentException("Invalid Bluetooth address.");
  if(!token.matches("[0-9A-F]{48}"))throw new IllegalArgumentException("Invalid pairing token.");
  if(expires<=now||expires-now>300000)throw new IllegalArgumentException("Code expired or device clocks differ. Create a new code.");
 }
 public static void validateNetwork(
         String app,
         int version,
         String host,
         int port,
         String token,
         long expires,
         long now) {

  if (!"FSCallingLine".equals(app) || version != 1)
   throw new IllegalArgumentException("Not an FS Calling Line code.");

  if (host == null || host.trim().isEmpty() || host.length() > 255)
   throw new IllegalArgumentException("Invalid PC network address.");

  if (port < 1 || port > 65535)
   throw new IllegalArgumentException("Invalid network port.");

  if (!token.matches("[0-9A-F]{48}"))
   throw new IllegalArgumentException("Invalid pairing token.");

  if (expires <= now || expires - now > 300000)
   throw new IllegalArgumentException(
           "Code expired or device clocks differ. Create a new code.");
 }
}
