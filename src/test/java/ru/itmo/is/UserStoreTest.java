package ru.itmo.is;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.itmo.is.security.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class UserStoreTest {
 @TempDir Path directory;
 @Test void accountsPersistAndExistingPasswordsStayCompatible() throws Exception {
  Path file=directory.resolve("users.properties");
  String old=Passwords.hash("old");Files.writeString(file,"existing="+old+"\n");
  var store=new UserStore(file);assertTrue(Passwords.verify("old",store.hash("existing")));
  assertTrue(store.register("new",Passwords.hash("1")));
  var restored=new UserStore(file);assertTrue(Passwords.verify("1",restored.hash("new")));
  assertTrue(Passwords.verify("old",restored.hash("existing")));
  assertFalse(Files.readString(file).contains("new=1\n"));
 }
 @Test void onlyOneConcurrentRegistrationWins() throws Exception {
  var store=new UserStore(directory.resolve("users.properties"));
  var pool=Executors.newFixedThreadPool(2);
  try {
   var a=pool.submit(()->store.register("same","first"));
   var b=pool.submit(()->store.register("same","second"));
   assertNotEquals(a.get(),b.get());
   assertNotNull(new UserStore(directory.resolve("users.properties")).hash("same"));
  } finally {pool.shutdownNow();}
 }
 @Test void passwordsHaveNoLengthOrCompositionPolicy() {
  for(String password:new String[]{"", "1", "пробелы и символы !", "a".repeat(300)}) {
   String hash=Passwords.hash(password);assertTrue(Passwords.verify(password,hash));assertFalse(Passwords.verify(password+"x",hash));
  }
 }
}
