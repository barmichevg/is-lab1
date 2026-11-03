package ru.itmo.is.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.util.Base64;

public final class Passwords {
    private Passwords() {}
    public static String hash(String password) {
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 600000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "600000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Password hashing failed", e); }
        finally { spec.clearPassword(); }
    }
    public static boolean verify(String password,String encoded) {
        if(password==null || encoded==null) return false;
        PBEKeySpec spec=null;
        try {
            String[] p=encoded.split("\\$");
            if(p.length!=3) return false;
            int iterations=Integer.parseInt(p[0]);
            if(iterations<100000 || iterations>2000000) return false;
            byte[] salt=Base64.getDecoder().decode(p[1]), expected=Base64.getDecoder().decode(p[2]);
            if(salt.length<16 || expected.length!=32) return false;
            spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
            byte[] actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected,actual);
        } catch(Exception e) { return false; }
        finally { if(spec!=null) spec.clearPassword(); }
    }
}
