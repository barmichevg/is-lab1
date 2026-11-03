package ru.itmo.is;
import org.junit.jupiter.api.Test;
import ru.itmo.is.security.Passwords;
import static org.junit.jupiter.api.Assertions.*;
class PasswordsTest {
 @Test void verifiesHashAndRejectsWrongPassword() {
  String hash="600000$MTIzNDU2Nzg5MGFiY2RlZg==$MVScSqqilqyIXipE6nFenK85mDK49H7vQmri3UZsLe8=";
  assertTrue(Passwords.verify("test-password",hash));
  assertFalse(Passwords.verify("wrong",hash));
  assertFalse(Passwords.verify("test-password","invalid"));
 }
}
